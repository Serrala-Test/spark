/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.parquet

import java.nio.{ByteBuffer, ByteOrder}

import scala.collection.JavaConverters.mapAsJavaMapConverter

import org.apache.hadoop.conf.Configuration
import org.apache.parquet.column.ParquetProperties
import org.apache.parquet.hadoop.ParquetOutputFormat
import org.apache.parquet.hadoop.api.WriteSupport
import org.apache.parquet.hadoop.api.WriteSupport.WriteContext
import org.apache.parquet.io.api.{Binary, RecordConsumer}

import org.apache.spark.Logging
import org.apache.spark.sql.SQLConf
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.SpecificMutableRow
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.types._

private[parquet] class CatalystWriteSupport extends WriteSupport[InternalRow] with Logging {
  type ValueWriter = (InternalRow, Int) => Unit

  private var schema: StructType = _

  private var recordConsumer: RecordConsumer = _

  private var followParquetFormatSpec: Boolean = _

  // Byte array used to write timestamps as Parquet INT96 values
  private val timestampBuffer = new Array[Byte](12)

  // Byte array used to write decimal values
  private val decimalBuffer = new Array[Byte](8)

  override def init(configuration: Configuration): WriteContext = {
    val schemaString = configuration.get(CatalystWriteSupport.SPARK_ROW_SCHEMA)
    schema = StructType.fromString(schemaString)

    assert(configuration.get(SQLConf.PARQUET_FOLLOW_PARQUET_FORMAT_SPEC.key) != null)
    followParquetFormatSpec =
      configuration.getBoolean(
        SQLConf.PARQUET_FOLLOW_PARQUET_FORMAT_SPEC.key,
        SQLConf.PARQUET_FOLLOW_PARQUET_FORMAT_SPEC.defaultValue.get)

    val messageType = new CatalystSchemaConverter(configuration).convert(schema)
    val metadata = Map(CatalystReadSupport.SPARK_METADATA_KEY -> schemaString).asJava

    logDebug(
      s"""Initialized Parquet WriteSupport with Catalyst schema:
         |${schema.prettyJson}
         |and corresponding Parquet message type:
         |$messageType
       """.stripMargin)

    new WriteContext(messageType, metadata)
  }

  override def prepareForWrite(recordConsumer: RecordConsumer): Unit = {
    this.recordConsumer = recordConsumer
  }

  override def write(row: InternalRow): Unit = {
    consumeMessage(writeFields(row, schema))
  }

  private def writeFields(row: InternalRow, schema: StructType): Unit = {
    val writers = schema.map(_.dataType).map(makeWriter)
    var i = 0

    while (i < row.numFields) {
      if (!row.isNullAt(i)) {
        consumeField(schema(i).name, i) {
          writers(i).apply(row, i)
        }
      }

      i += 1
    }
  }

  private def makeWriter(dataType: DataType): ValueWriter = {
    dataType match {
      case BooleanType =>
        (row: InternalRow, ordinal: Int) =>
          recordConsumer.addBoolean(row.getBoolean(ordinal))

      case ByteType =>
        (row: InternalRow, ordinal: Int) =>
          recordConsumer.addInteger(row.getByte(ordinal))

      case ShortType =>
        (row: InternalRow, ordinal: Int) =>
          recordConsumer.addInteger(row.getShort(ordinal))

      case IntegerType | DateType =>
        (row: InternalRow, ordinal: Int) =>
          recordConsumer.addInteger(row.getInt(ordinal))

      case LongType =>
        (row: InternalRow, ordinal: Int) =>
          recordConsumer.addLong(row.getLong(ordinal))

      case FloatType =>
        (row: InternalRow, ordinal: Int) =>
          recordConsumer.addFloat(row.getFloat(ordinal))

      case DoubleType =>
        (row: InternalRow, ordinal: Int) =>
          recordConsumer.addDouble(row.getDouble(ordinal))

      case StringType =>
        (row: InternalRow, ordinal: Int) =>
          recordConsumer.addBinary(Binary.fromByteArray(row.getUTF8String(ordinal).getBytes))

      case TimestampType =>
        (row: InternalRow, ordinal: Int) => {
          val (julianDay, timeOfDayNanos) = DateTimeUtils.toJulianDay(row.getLong(ordinal))
          val buf = ByteBuffer.wrap(timestampBuffer)
          buf.order(ByteOrder.LITTLE_ENDIAN).putLong(timeOfDayNanos).putInt(julianDay)
          recordConsumer.addBinary(Binary.fromByteArray(timestampBuffer))
        }

      case BinaryType =>
        (row: InternalRow, ordinal: Int) =>
          recordConsumer.addBinary(Binary.fromByteArray(row.getBinary(ordinal)))

      case DecimalType.Fixed(precision, _) if precision > 18 =>
        sys.error(s"Unsupported data type $dataType. Decimal precision cannot be greater than 18.")

      case DecimalType.Fixed(precision, _) =>
        (row: InternalRow, ordinal: Int) => {
          val decimal = row.getDecimal(ordinal)
          val numBytes = CatalystWriteSupport.BYTES_FOR_PRECISION(precision)
          val unscaledLong = decimal.toUnscaledLong

          var i = 0
          var shift = 8 * (numBytes - 1)

          while (i < numBytes) {
            decimalBuffer(i) = (unscaledLong >> shift).toByte
            i += 1
            shift -= 8
          }

          recordConsumer.addBinary(Binary.fromByteArray(decimalBuffer, 0, numBytes))
        }

      case structType @ StructType(fields) =>
        (row: InternalRow, ordinal: Int) =>
          consumeGroup(writeFields(row.getStruct(ordinal, fields.length), structType))

      case arrayType: ArrayType if followParquetFormatSpec =>
        makeStandardArrayWriter(arrayType.elementType)

      case arrayType: ArrayType if !followParquetFormatSpec =>
        makeLegacyArrayWriter(arrayType.elementType, arrayType.containsNull)

      case mapType: MapType if followParquetFormatSpec =>
        makeMapWriter(mapType.keyType, mapType.valueType, "key_value")

      case mapType: MapType if !followParquetFormatSpec =>
        makeMapWriter(mapType.keyType, mapType.valueType, "map")

      case udt: UserDefinedType[_] =>
        makeWriter(udt.sqlType)

      case _ =>
        sys.error(s"Unsupported data type $dataType.")
    }
  }

  private def makeStandardArrayWriter(elementType: DataType): ValueWriter = {
    makeThreeLevelArrayWriter(elementType, "list", "element")
  }

  private def makeLegacyArrayWriter(
      elementType: DataType,
      containsNull: Boolean): ValueWriter = {
    if (containsNull) {
      makeThreeLevelArrayWriter(elementType, "bag", "array")
    } else {
      makeTwoLevelArrayWriter(elementType, "array")
    }
  }

  private def makeThreeLevelArrayWriter(
      elementType: DataType,
      repeatedGroupName: String,
      elementFieldName: String): ValueWriter = {
    val elementWriter = makeWriter(elementType)
    val mutableRow = new SpecificMutableRow(elementType :: Nil)

    (row: InternalRow, ordinal: Int) => {
      consumeGroup {
        consumeField(repeatedGroupName, 0) {
          val array = row.get(ordinal).asInstanceOf[Seq[_]]
          var i = 0

          while (i < array.length) {
            consumeGroup {
              if (array(i) != null) {
                mutableRow.update(0, array(i))
                consumeField(elementFieldName, 0)(elementWriter.apply(mutableRow, 0))
              }
            }

            i += 1
          }
        }
      }
    }
  }

  private def makeTwoLevelArrayWriter(
      elementType: DataType,
      repeatedFieldName: String): ValueWriter = {
    val elementWriter = makeWriter(elementType)
    val mutableRow = new SpecificMutableRow(elementType :: Nil)

    (row: InternalRow, ordinal: Int) => {
      consumeGroup {
        consumeField(repeatedFieldName, 0) {
          val array = row.get(ordinal).asInstanceOf[Seq[_]]
          var i = 0

          while (i < array.length) {
            mutableRow.update(0, array(i))
            elementWriter.apply(mutableRow, 0)
            i += 1
          }
        }
      }
    }
  }

  private def makeMapWriter(
      keyType: DataType,
      valueType: DataType,
      repeatedGroupName: String): ValueWriter = {
    val keyWriter = makeWriter(keyType)
    val valueWriter = makeWriter(valueType)
    val mutableRow = new SpecificMutableRow(keyType :: valueType :: Nil)

    (row: InternalRow, ordinal: Int) => {
      consumeGroup {
        consumeField(repeatedGroupName, 0) {
          val map = row.get(ordinal).asInstanceOf[Map[_, _]]
          for ((key, value) <- map) {
            consumeGroup {
              mutableRow.update(0, key)
              consumeField("key", 0)(keyWriter.apply(mutableRow, 0))
              if (value != null) {
                mutableRow.update(1, value)
                consumeField("value", 1)(valueWriter.apply(mutableRow, 1))
              }
            }
          }
        }
      }
    }
  }

  private def consumeMessage(f: => Unit): Unit = {
    recordConsumer.startMessage()
    f
    recordConsumer.endMessage()
  }

  private def consumeGroup(f: => Unit): Unit = {
    recordConsumer.startGroup()
    f
    recordConsumer.endGroup()
  }

  private def consumeField(field: String, index: Int)(f: => Unit): Unit = {
    recordConsumer.startField(field, index)
    f
    recordConsumer.endField(field, index)
  }
}

private[parquet] object CatalystWriteSupport {
  val SPARK_ROW_SCHEMA: String = "org.apache.spark.sql.parquet.row.attributes"

  def setSchema(schema: StructType, configuration: Configuration): Unit = {
    schema.map(_.name).foreach(CatalystSchemaConverter.checkFieldName)
    configuration.set(SPARK_ROW_SCHEMA, schema.json)
    configuration.set(
      ParquetOutputFormat.WRITER_VERSION,
      ParquetProperties.WriterVersion.PARQUET_1_0.toString)
  }

  /**
   * Compute the FIXED_LEN_BYTE_ARRAY length needed to represent a given DECIMAL precision.
   */
  private[parquet] val BYTES_FOR_PRECISION = Array.tabulate[Int](38) { precision =>
    var length = 1
    while (math.pow(2.0, 8 * length - 1) < math.pow(10.0, precision)) {
      length += 1
    }
    length
  }
}
