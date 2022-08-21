package org.apache.spark.sql.proto

import java.nio.ByteBuffer
import com.google.protobuf.Descriptors.{Descriptor, FieldDescriptor}

import scala.collection.JavaConverters._
import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{SpecializedGetters, SpecificInternalRow}
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.proto.ProtoUtils.{ProtoMatchedField, toFieldStr}
import org.apache.spark.sql.types._
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType._
import com.google.protobuf.Descriptors.FieldDescriptor.Type
import com.google.protobuf.DynamicMessage
import org.apache.spark.sql.proto.SchemaConverters.{IncompatibleSchemaException, UnsupportedProtoTypeException}

/**
 * A serializer to serialize data in catalyst format to data in proto format.
 */
private[sql] class ProtoSerializer(
                                   rootCatalystType: DataType,
                                   rootProtoType: Descriptor,
                                   nullable: Boolean,
                                   positionalFieldMatch: Boolean) extends Logging {

  def this(rootCatalystType: DataType, rootProtoType: Descriptor, nullable: Boolean) = {
    this(rootCatalystType, rootProtoType, nullable, false)
  }

  def serialize(catalystData: Any): Any = {
    converter.apply(catalystData)
  }

  private val converter: Any => Any = {
    val actualProtoType = resolveNullableType(rootProtoType, nullable)
    val baseConverter = try {
      rootCatalystType match {
        case st: StructType =>
          newStructConverter(st, actualProtoType.right.get, Nil, Nil).asInstanceOf[Any => Any]
        case _ =>
          val tmpRow = new SpecificInternalRow(Seq(rootCatalystType))
          val converter = newConverter(rootCatalystType, actualProtoType.left.get, Nil, Nil)
          (data: Any) =>
            tmpRow.update(0, data)
            converter.apply(tmpRow, 0)
      }
    } catch {
      case ise: IncompatibleSchemaException => throw new IncompatibleSchemaException(
        s"Cannot convert SQL type ${rootCatalystType.sql} to Proto type $rootProtoType.", ise)
    }
    if (nullable) {
      (data: Any) =>
        if (data == null) {
          null
        } else {
          baseConverter.apply(data)
        }
    } else {
      baseConverter
    }
  }

  private type Converter = (SpecializedGetters, Int) => Any


  private def newConverter(
                            catalystType: DataType,
                            protoType: FieldDescriptor,
                            catalystPath: Seq[String],
                            protoPath: Seq[String]): Converter = {
    val errorPrefix = s"Cannot convert SQL ${toFieldStr(catalystPath)} " +
      s"to Proto ${toFieldStr(protoPath)} because "
    (catalystType, protoType.getJavaType) match {
      case (NullType, null) =>
        (getter, ordinal) => null
      case (BooleanType, BOOLEAN) =>
        (getter, ordinal) => getter.getBoolean(ordinal)
      case (ByteType, INT) =>
        (getter, ordinal) => getter.getByte(ordinal).toInt
      case (ShortType, INT) =>
        (getter, ordinal) => getter.getShort(ordinal).toInt
      case (IntegerType, INT) =>
        (getter, ordinal) => getter.getInt(ordinal)
      case (LongType, LONG) =>
        (getter, ordinal) => getter.getLong(ordinal)
      case (FloatType, FLOAT) =>
        (getter, ordinal) => getter.getFloat(ordinal)
      case (DoubleType, DOUBLE) =>
        (getter, ordinal) => getter.getDouble(ordinal)
      case (StringType, ENUM) =>
        val enumSymbols: Set[String] = protoType.getEnumType.getValues.asScala.map(e=>e.toString).toSet
        (getter, ordinal) =>
          val data = getter.getUTF8String(ordinal).toString
          if (!enumSymbols.contains(data)) {
            throw new IncompatibleSchemaException(errorPrefix +
              s""""$data" cannot be written since it's not defined in enum """ +
              enumSymbols.mkString("\"", "\", \"", "\""))
          }
          protoType.getEnumType.toProto.toBuilder.setField(protoType, data)
      case (StringType, STRING) =>
        (getter, ordinal) => getter.getUTF8String(ordinal).getBytes

      case (BinaryType, BYTE_STRING) =>
        (getter, ordinal) => ByteBuffer.wrap(getter.getBinary(ordinal))

      case (DateType, INT) =>
        (getter, ordinal) => getter.getInt(ordinal)

      case (TimestampType, LONG) => protoType.getContainingType match {
        // For backward compatibility, if the Avro type is Long and it is not logical type
        // (the `null` case), output the timestamp value as with millisecond precision.
        case null => (getter, ordinal) =>
          DateTimeUtils.microsToMillis(getter.getLong(ordinal))
        case other => throw new IncompatibleSchemaException(errorPrefix +
          s"SQL type ${TimestampType.sql} cannot be converted to Avro logical type $other")
      }

      case (TimestampNTZType, LONG) => protoType.getContainingType match {
        // To keep consistent with TimestampType, if the Avro type is Long and it is not
        // logical type (the `null` case), output the TimestampNTZ as long value
        // in millisecond precision.
        case null => (getter, ordinal) =>
          DateTimeUtils.microsToMillis(getter.getLong(ordinal))
        case other => throw new IncompatibleSchemaException(errorPrefix +
          s"SQL type ${TimestampNTZType.sql} cannot be converted to Avro logical type $other")
      }

      case (ArrayType(et, containsNull), _) =>
        val elementConverter = newConverter(
          et, resolveNullableType(protoType.getContainingType, containsNull).left.get,
          catalystPath :+ "element", protoPath :+ "element")
        (getter, ordinal) => {
          val arrayData = getter.getArray(ordinal)
          val len = arrayData.numElements()
          val result = new Array[Any](len)
          var i = 0
          while (i < len) {
            if (containsNull && arrayData.isNullAt(i)) {
              result(i) = null
            } else {
              result(i) = elementConverter(arrayData, i)
            }
            i += 1
          }
          // proto writer is expecting a Java Collection, so we convert it into
          // `ArrayList` backed by the specified array without data copying.
          java.util.Arrays.asList(result: _*)
        }

      case (st: StructType, MESSAGE) =>
        val structConverter = newStructConverter(st, protoType.getMessageType, catalystPath, protoPath)
        val numFields = st.length
        (getter, ordinal) => structConverter(getter.getStruct(ordinal, numFields))

      case (MapType(kt, vt, valueContainsNull), _) if kt == StringType =>
        val valueConverter = newConverter(
          vt, resolveNullableType(protoType.getMessageType, valueContainsNull).left.get,
          catalystPath :+ "value", protoPath :+ "value")
        (getter, ordinal) =>
          val mapData = getter.getMap(ordinal)
          val len = mapData.numElements()
          val result = new java.util.HashMap[String, Any](len)
          val keyArray = mapData.keyArray()
          val valueArray = mapData.valueArray()
          var i = 0
          while (i < len) {
            val key = keyArray.getUTF8String(i).toString
            if (valueContainsNull && valueArray.isNullAt(i)) {
              result.put(key, null)
            } else {
              result.put(key, valueConverter(valueArray, i))
            }
            i += 1
          }
          result

      case (_: YearMonthIntervalType, INT) =>
        (getter, ordinal) => getter.getInt(ordinal)

      case (_: DayTimeIntervalType, LONG) =>
        (getter, ordinal) => getter.getLong(ordinal)

      case _ =>
        throw new IncompatibleSchemaException(errorPrefix +
          s"schema is incompatible (sqlType = ${catalystType.sql}, protoType = $protoType)")
    }
  }

  private def newStructConverter(
                                  catalystStruct: StructType,
                                  protoStruct: Descriptor,
                                  catalystPath: Seq[String],
                                  protoPath: Seq[String]): InternalRow => DynamicMessage = {

    val protoSchemaHelper = new ProtoUtils.ProtoSchemaHelper(
      protoStruct, catalystStruct, protoPath, catalystPath, false)

    protoSchemaHelper.validateNoExtraCatalystFields(ignoreNullable = false)
    protoSchemaHelper.validateNoExtraRequiredProtoFields()

    val (protoIndices, fieldConverters) = protoSchemaHelper.matchedFields.map {
      case ProtoMatchedField(catalystField, _, protoField) =>
        val converter = newConverter(catalystField.dataType,
          resolveNullableType(protoField.getMessageType, catalystField.nullable).left.get,
          catalystPath :+ catalystField.name, protoPath :+ protoField.getName)
        (protoField, converter)
    }.toArray.unzip

    val numFields = catalystStruct.length
    row: InternalRow =>
      val result = DynamicMessage.newBuilder(protoStruct)
      var i = 0
      while (i < numFields) {
        if (row.isNullAt(i)) {
          result.setField(protoIndices(i), null)
        } else {
          result.setField(protoIndices(i), fieldConverters(i).apply(row, i))
        }
        i += 1
      }
      result.build()
  }

  /**
   * Resolve a possibly nullable Avro Type.
   *
   * An Avro type is nullable when it is a [[UNION]] of two types: one null type and another
   * non-null type. This method will check the nullability of the input Avro type and return the
   * non-null type within when it is nullable. Otherwise it will return the input Avro type
   * unchanged. It will throw an [[UnsupportedAvroTypeException]] when the input Avro type is an
   * unsupported nullable type.
   *
   * It will also log a warning message if the nullability for Avro and catalyst types are
   * different.
   */
  private def resolveNullableType(protoType: Descriptor, nullable: Boolean): Either[FieldDescriptor, Descriptor] = {
    val (protoNullable, resolvedProtoType) = resolveProtoType(protoType)
    warnNullabilityDifference(protoNullable, nullable)
    resolvedProtoType
  }

  /**
   * Check the nullability of the input Avro type and resolve it when it is nullable. The first
   * return value is a [[Boolean]] indicating if the input Proto type is nullable. The second
   * return value is the possibly resolved type.
   */
  private def resolveProtoType(protoType: Descriptor): (Boolean, Either[FieldDescriptor, Descriptor]) = {
    if (protoType.getContainingType.getContainingType.getName.equals(Type.GROUP.name())) {
      val fields = protoType.getFields.asScala
      val actualType = fields.filter(_.getType != null)
      if (fields.length != 2 || actualType.length != 1) {
        throw new UnsupportedProtoTypeException(
          s"Unsupported Proto UNION type $protoType: Only UNION of a null type and a non-null " +
            "type is supported")
      }
      (true, Left(actualType.head))
    } else {
      (false, Right(protoType))
    }
  }

  /**
   * log a warning message if the nullability for Proto and catalyst types are different.
   */
  private def warnNullabilityDifference(protoNullable: Boolean, catalystNullable: Boolean): Unit = {
    if (protoNullable && !catalystNullable) {
      logWarning("Writing Proto files with nullable Proto schema and non-nullable catalyst schema.")
    }
    if (!protoNullable && catalystNullable) {
      logWarning("Writing Proto files with non-nullable Proto schema and nullable catalyst " +
        "schema will throw runtime exception if there is a record with null value.")
    }
  }
}

