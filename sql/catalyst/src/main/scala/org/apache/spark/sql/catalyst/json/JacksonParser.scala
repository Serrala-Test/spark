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

package org.apache.spark.sql.catalyst.json

import java.io.ByteArrayOutputStream

import scala.collection.mutable.ArrayBuffer
import scala.util.Try

import com.fasterxml.jackson.core._

import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.util._
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String
import org.apache.spark.util.Utils

private[sql] class SparkSQLJsonProcessingException(msg: String) extends RuntimeException(msg)

/**
 * Constructs a parser for a given schema that translates a json string to an [[InternalRow]].
 */
class JacksonParser(
    schema: StructType,
    options: JSONOptions) extends Logging {
  import JacksonUtils._
  import ParseModes._
  import com.fasterxml.jackson.core.JsonToken._

  // A `ValueConverter` is responsible for converting a value from `JsonParser`
  // to a value in a field for `InternalRow`.
  private type ValueConverter = JsonParser => AnyRef

  // `ValueConverter`s for the root schema for all fields in the schema
  private val rootConverter = makeRootConverter(schema)

  private val factory = new JsonFactory()
  options.setJacksonOptions(factory)

  private val emptyRow: Seq[InternalRow] = Seq(new GenericInternalRow(schema.length))

  private val corruptFieldIndex = schema.getFieldIndex(options.columnNameOfCorruptRecord)
  corruptFieldIndex.foreach { corrFieldIndex =>
    require(schema(corrFieldIndex).dataType == StringType)
    require(schema(corrFieldIndex).nullable)
  }

  @transient
  private[this] var isWarningPrinted: Boolean = false

  @transient
  private def printWarningForMalformedRecord(record: () => UTF8String): Unit = {
    def sampleRecord: String = {
      if (options.wholeFile) {
        ""
      } else {
        s"Sample record: ${record()}\n"
      }
    }

    def footer: String = {
      s"""Code example to print all malformed records (scala):
         |===================================================
         |// The corrupted record exists in column ${options.columnNameOfCorruptRecord}.
         |val parsedJson = spark.read.json("/path/to/json/file/test.json")
         |
       """.stripMargin
    }

    if (options.permissive) {
      logWarning(
        s"""Found at least one malformed record. The JSON reader will replace
           |all malformed records with placeholder null in current $PERMISSIVE_MODE parser mode.
           |To find out which corrupted records have been replaced with null, please use the
           |default inferred schema instead of providing a custom schema.
           |
           |${sampleRecord ++ footer}
           |
         """.stripMargin)
    } else if (options.dropMalformed) {
      logWarning(
        s"""Found at least one malformed record. The JSON reader will drop
           |all malformed records in current $DROP_MALFORMED_MODE parser mode. To find out which
           |corrupted records have been dropped, please switch the parser mode to $PERMISSIVE_MODE
           |mode and use the default inferred schema.
           |
           |${sampleRecord ++ footer}
           |
         """.stripMargin)
    }
  }

  @transient
  private def printWarningIfWholeFile(): Unit = {
    if (options.wholeFile && corruptFieldIndex.isDefined) {
      logWarning(
        s"""Enabling wholeFile mode and defining columnNameOfCorruptRecord may result
           |in very large allocations or OutOfMemoryExceptions being raised.
           |
         """.stripMargin)
    }
  }

  /**
   * This function deals with the cases it fails to parse. This function will be called
   * when exceptions are caught during converting. This functions also deals with `mode` option.
   */
  private def failedRecord(record: () => UTF8String): Seq[InternalRow] = {
    corruptFieldIndex match {
      case _ if options.failFast =>
        if (options.wholeFile) {
          throw new SparkSQLJsonProcessingException("Malformed line in FAILFAST mode")
        } else {
          throw new SparkSQLJsonProcessingException(s"Malformed line in FAILFAST mode: ${record()}")
        }

      case _ if options.dropMalformed =>
        if (!isWarningPrinted) {
          printWarningForMalformedRecord(record)
          isWarningPrinted = true
        }
        Nil

      case None =>
        if (!isWarningPrinted) {
          printWarningForMalformedRecord(record)
          isWarningPrinted = true
        }
        emptyRow

      case Some(corruptIndex) =>
        if (!isWarningPrinted) {
          printWarningIfWholeFile()
          isWarningPrinted = true
        }
        val row = new GenericInternalRow(schema.length)
        row.update(corruptIndex, record())
        Seq(row)
    }
  }

  /**
   * Create a converter which converts the JSON documents held by the `JsonParser`
   * to a value according to a desired schema. This is a wrapper for the method
   * `makeConverter()` to handle a row wrapped with an array.
   */
  private def makeRootConverter(st: StructType): JsonParser => Seq[InternalRow] = {
    val elementConverter = makeConverter(st)
    val fieldConverters = st.map(_.dataType).map(makeConverter).toArray
    (parser: JsonParser) => parseJsonToken[Seq[InternalRow]](parser, st) {
      case START_OBJECT => convertObject(parser, st, fieldConverters) :: Nil
        // SPARK-3308: support reading top level JSON arrays and take every element
        // in such an array as a row
        //
        // For example, we support, the JSON data as below:
        //
        // [{"a":"str_a_1"}]
        // [{"a":"str_a_2"}, {"b":"str_b_3"}]
        //
        // resulting in:
        //
        // List([str_a_1,null])
        // List([str_a_2,null], [null,str_b_3])
        //
      case START_ARRAY =>
        val array = convertArray(parser, elementConverter)
        // Here, as we support reading top level JSON arrays and take every element
        // in such an array as a row, this case is possible.
        if (array.numElements() == 0) {
          Nil
        } else {
          array.toArray[InternalRow](schema).toSeq
        }
    }
  }

  /**
   * Create a converter which converts the JSON documents held by the `JsonParser`
   * to a value according to a desired schema.
   */
  def makeConverter(dataType: DataType): ValueConverter = dataType match {
    case BooleanType =>
      (parser: JsonParser) => parseJsonToken[java.lang.Boolean](parser, dataType) {
        case VALUE_TRUE => true
        case VALUE_FALSE => false
      }

    case ByteType =>
      (parser: JsonParser) => parseJsonToken[java.lang.Byte](parser, dataType) {
        case VALUE_NUMBER_INT => parser.getByteValue
      }

    case ShortType =>
      (parser: JsonParser) => parseJsonToken[java.lang.Short](parser, dataType) {
        case VALUE_NUMBER_INT => parser.getShortValue
      }

    case IntegerType =>
      (parser: JsonParser) => parseJsonToken[java.lang.Integer](parser, dataType) {
        case VALUE_NUMBER_INT => parser.getIntValue
      }

    case LongType =>
      (parser: JsonParser) => parseJsonToken[java.lang.Long](parser, dataType) {
        case VALUE_NUMBER_INT => parser.getLongValue
      }

    case FloatType =>
      (parser: JsonParser) => parseJsonToken[java.lang.Float](parser, dataType) {
        case VALUE_NUMBER_INT | VALUE_NUMBER_FLOAT =>
          parser.getFloatValue

        case VALUE_STRING =>
          // Special case handling for NaN and Infinity.
          val value = parser.getText
          val lowerCaseValue = value.toLowerCase
          if (lowerCaseValue.equals("nan") ||
            lowerCaseValue.equals("infinity") ||
            lowerCaseValue.equals("-infinity") ||
            lowerCaseValue.equals("inf") ||
            lowerCaseValue.equals("-inf")) {
            value.toFloat
          } else {
            throw new SparkSQLJsonProcessingException(s"Cannot parse $value as FloatType.")
          }
      }

    case DoubleType =>
      (parser: JsonParser) => parseJsonToken[java.lang.Double](parser, dataType) {
        case VALUE_NUMBER_INT | VALUE_NUMBER_FLOAT =>
          parser.getDoubleValue

        case VALUE_STRING =>
          // Special case handling for NaN and Infinity.
          val value = parser.getText
          val lowerCaseValue = value.toLowerCase
          if (lowerCaseValue.equals("nan") ||
            lowerCaseValue.equals("infinity") ||
            lowerCaseValue.equals("-infinity") ||
            lowerCaseValue.equals("inf") ||
            lowerCaseValue.equals("-inf")) {
            value.toDouble
          } else {
            throw new SparkSQLJsonProcessingException(s"Cannot parse $value as DoubleType.")
          }
      }

    case StringType =>
      (parser: JsonParser) => parseJsonToken[UTF8String](parser, dataType) {
        case VALUE_STRING =>
          UTF8String.fromString(parser.getText)

        case _ =>
          // Note that it always tries to convert the data as string without the case of failure.
          val writer = new ByteArrayOutputStream()
          Utils.tryWithResource(factory.createGenerator(writer, JsonEncoding.UTF8)) {
            generator => generator.copyCurrentStructure(parser)
          }
          UTF8String.fromBytes(writer.toByteArray)
      }

    case TimestampType =>
      (parser: JsonParser) => parseJsonToken[java.lang.Long](parser, dataType) {
        case VALUE_STRING =>
          val stringValue = parser.getText
          // This one will lose microseconds parts.
          // See https://issues.apache.org/jira/browse/SPARK-10681.
          Long.box {
            Try(options.timestampFormat.parse(stringValue).getTime * 1000L)
              .getOrElse {
                // If it fails to parse, then tries the way used in 2.0 and 1.x for backwards
                // compatibility.
                DateTimeUtils.stringToTime(stringValue).getTime * 1000L
              }
          }

        case VALUE_NUMBER_INT =>
          parser.getLongValue * 1000000L
      }

    case DateType =>
      (parser: JsonParser) => parseJsonToken[java.lang.Integer](parser, dataType) {
        case VALUE_STRING =>
          val stringValue = parser.getText
          // This one will lose microseconds parts.
          // See https://issues.apache.org/jira/browse/SPARK-10681.x
          Int.box {
            Try(DateTimeUtils.millisToDays(options.dateFormat.parse(stringValue).getTime))
              .orElse {
                // If it fails to parse, then tries the way used in 2.0 and 1.x for backwards
                // compatibility.
                Try(DateTimeUtils.millisToDays(DateTimeUtils.stringToTime(stringValue).getTime))
              }
              .getOrElse {
                // In Spark 1.5.0, we store the data as number of days since epoch in string.
                // So, we just convert it to Int.
                stringValue.toInt
              }
          }
      }

    case BinaryType =>
      (parser: JsonParser) => parseJsonToken[Array[Byte]](parser, dataType) {
        case VALUE_STRING => parser.getBinaryValue
      }

    case dt: DecimalType =>
      (parser: JsonParser) => parseJsonToken[Decimal](parser, dataType) {
        case (VALUE_NUMBER_INT | VALUE_NUMBER_FLOAT) =>
          Decimal(parser.getDecimalValue, dt.precision, dt.scale)
      }

    case st: StructType =>
      val fieldConverters = st.map(_.dataType).map(makeConverter).toArray
      (parser: JsonParser) => parseJsonToken[InternalRow](parser, dataType) {
        case START_OBJECT => convertObject(parser, st, fieldConverters)
      }

    case at: ArrayType =>
      val elementConverter = makeConverter(at.elementType)
      (parser: JsonParser) => parseJsonToken[ArrayData](parser, dataType) {
        case START_ARRAY => convertArray(parser, elementConverter)
      }

    case mt: MapType =>
      val valueConverter = makeConverter(mt.valueType)
      (parser: JsonParser) => parseJsonToken[MapData](parser, dataType) {
        case START_OBJECT => convertMap(parser, valueConverter)
      }

    case udt: UserDefinedType[_] =>
      makeConverter(udt.sqlType)

    case _ =>
      (parser: JsonParser) =>
        // Here, we pass empty `PartialFunction` so that this case can be
        // handled as a failed conversion. It will throw an exception as
        // long as the value is not null.
        parseJsonToken[AnyRef](parser, dataType)(PartialFunction.empty[JsonToken, AnyRef])
  }

  /**
   * This method skips `FIELD_NAME`s at the beginning, and handles nulls ahead before trying
   * to parse the JSON token using given function `f`. If the `f` failed to parse and convert the
   * token, call `failedConversion` to handle the token.
   */
  private def parseJsonToken[R >: Null](
      parser: JsonParser,
      dataType: DataType)(f: PartialFunction[JsonToken, R]): R = {
    parser.getCurrentToken match {
      case FIELD_NAME =>
        // There are useless FIELD_NAMEs between START_OBJECT and END_OBJECT tokens
        parser.nextToken()
        parseJsonToken[R](parser, dataType)(f)

      case null | VALUE_NULL => null

      case other => f.applyOrElse(other, failedConversion(parser, dataType))
    }
  }

  /**
   * This function throws an exception for failed conversion, but returns null for empty string,
   * to guard the non string types.
   */
  private def failedConversion[R >: Null](
      parser: JsonParser,
      dataType: DataType): PartialFunction[JsonToken, R] = {
    case VALUE_STRING if parser.getTextLength < 1 =>
      // If conversion is failed, this produces `null` rather than throwing exception.
      // This will protect the mismatch of types.
      null

    case token =>
      // We cannot parse this token based on the given data type. So, we throw a
      // SparkSQLJsonProcessingException and this exception will be caught by
      // `parse` method.
      throw new SparkSQLJsonProcessingException(
        s"Failed to parse a value for data type $dataType (current token: $token).")
  }

  /**
   * Parse an object from the token stream into a new Row representing the schema.
   * Fields in the json that are not defined in the requested schema will be dropped.
   */
  private def convertObject(
      parser: JsonParser,
      schema: StructType,
      fieldConverters: Array[ValueConverter]): InternalRow = {
    val row = new GenericInternalRow(schema.length)
    while (nextUntil(parser, JsonToken.END_OBJECT)) {
      schema.getFieldIndex(parser.getCurrentName) match {
        case Some(index) =>
          row.update(index, fieldConverters(index).apply(parser))

        case None =>
          parser.skipChildren()
      }
    }

    row
  }

  /**
   * Parse an object as a Map, preserving all fields.
   */
  private def convertMap(
      parser: JsonParser,
      fieldConverter: ValueConverter): MapData = {
    val keys = ArrayBuffer.empty[UTF8String]
    val values = ArrayBuffer.empty[Any]
    while (nextUntil(parser, JsonToken.END_OBJECT)) {
      keys += UTF8String.fromString(parser.getCurrentName)
      values += fieldConverter.apply(parser)
    }

    ArrayBasedMapData(keys.toArray, values.toArray)
  }

  /**
   * Parse an object as a Array.
   */
  private def convertArray(
      parser: JsonParser,
      fieldConverter: ValueConverter): ArrayData = {
    val values = ArrayBuffer.empty[Any]
    while (nextUntil(parser, JsonToken.END_ARRAY)) {
      values += fieldConverter.apply(parser)
    }

    new GenericArrayData(values.toArray)
  }

  /**
   * Parse the JSON input to the set of [[InternalRow]]s.
   *
   * @param recordLiteral an optional function that will be used to generate
   *   the corrupt record text instead of record.toString
   */
  def parse[T](
      record: T,
      createParser: (JsonFactory, T) => JsonParser,
      recordLiteral: T => UTF8String): Seq[InternalRow] = {
    try {
      Utils.tryWithResource(createParser(factory, record)) { parser =>
        // a null first token is equivalent to testing for input.trim.isEmpty
        // but it works on any token stream and not just strings
        parser.nextToken() match {
          case null => Nil
          case _ => rootConverter.apply(parser) match {
            case null => throw new SparkSQLJsonProcessingException("Root converter returned null")
            case rows => rows
          }
        }
      }
    } catch {
      case _: JsonProcessingException | _: SparkSQLJsonProcessingException =>
        failedRecord(() => recordLiteral(record))
    }
  }
}
