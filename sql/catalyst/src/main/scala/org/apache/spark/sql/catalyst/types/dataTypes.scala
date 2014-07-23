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

package org.apache.spark.sql.catalyst.types

import java.sql.Timestamp

import scala.reflect.ClassTag
import scala.reflect.runtime.universe.{typeTag, TypeTag, runtimeMirror}
import scala.util.parsing.combinator.RegexParsers

import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeReference, Expression}
import org.apache.spark.util.Utils

/**
 * A JVM-global lock that should be used to prevent thread safety issues when using things in
 * scala.reflect.*.  Note that Scala Reflection API is made thread-safe in 2.11, but not yet for
 * 2.10.* builds.  See SI-6240 for more details.
 */
protected[catalyst] object ScalaReflectionLock

object DataType extends RegexParsers {
  protected lazy val primitiveType: Parser[DataType] =
    "StringType" ^^^ StringType |
    "FloatType" ^^^ FloatType |
    "IntegerType" ^^^ IntegerType |
    "ByteType" ^^^ ByteType |
    "ShortType" ^^^ ShortType |
    "DoubleType" ^^^ DoubleType |
    "LongType" ^^^ LongType |
    "BinaryType" ^^^ BinaryType |
    "BooleanType" ^^^ BooleanType |
    "DecimalType" ^^^ DecimalType |
    "TimestampType" ^^^ TimestampType

  protected lazy val arrayType: Parser[DataType] =
    "ArrayType" ~> "(" ~> dataType ~ "," ~ boolVal <~ ")" ^^ {
      case tpe ~ _ ~ containsNull => ArrayType(tpe, containsNull)
    }

  protected lazy val mapType: Parser[DataType] =
    "MapType" ~> "(" ~> dataType ~ "," ~ dataType <~ ")" ^^ {
      case t1 ~ _ ~ t2 => MapType(t1, t2)
    }

  protected lazy val structField: Parser[StructField] =
    ("StructField(" ~> "[a-zA-Z0-9_]*".r) ~ ("," ~> dataType) ~ ("," ~> boolVal <~ ")") ^^ {
      case name ~ tpe ~ nullable  =>
          StructField(name, tpe, nullable = nullable)
    }

  protected lazy val boolVal: Parser[Boolean] =
    "true" ^^^ true |
    "false" ^^^ false

  protected lazy val structType: Parser[DataType] =
    "StructType\\([A-zA-z]*\\(".r ~> repsep(structField, ",") <~ "))" ^^ {
      case fields => new StructType(fields)
    }

  protected lazy val dataType: Parser[DataType] =
    arrayType |
      mapType |
      structType |
      primitiveType

  /**
   * Parses a string representation of a DataType.
   *
   * TODO: Generate parser as pickler...
   */
  def apply(asString: String): DataType = parseAll(dataType, asString) match {
    case Success(result, _) => result
    case failure: NoSuccess => sys.error(s"Unsupported dataType: $asString, $failure")
  }

  protected[types] def buildFormattedString(
      dataType: DataType,
      prefix: String,
      builder: StringBuilder): Unit = {
    dataType match {
      case array: ArrayType =>
        array.buildFormattedString(prefix, builder)
      case struct: StructType =>
        struct.buildFormattedString(prefix, builder)
      case map: MapType =>
        map.buildFormattedString(prefix, builder)
      case _ =>
    }
  }
}

abstract class DataType {
  /** Matches any expression that evaluates to this DataType */
  def unapply(a: Expression): Boolean = a match {
    case e: Expression if e.dataType == this => true
    case _ => false
  }

  def isPrimitive: Boolean = false

  def simpleString: String
}

case object NullType extends DataType {
  def simpleString: String = "null"
}

trait PrimitiveType extends DataType {
  override def isPrimitive = true
}

abstract class NativeType extends DataType {
  private[sql] type JvmType
  @transient private[sql] val tag: TypeTag[JvmType]
  private[sql] val ordering: Ordering[JvmType]

  @transient private[sql] val classTag = ScalaReflectionLock.synchronized {
    val mirror = runtimeMirror(Utils.getSparkClassLoader)
    ClassTag[JvmType](mirror.runtimeClass(tag.tpe))
  }
}

case object StringType extends NativeType with PrimitiveType {
  private[sql] type JvmType = String
  @transient private[sql] lazy val tag = ScalaReflectionLock.synchronized { typeTag[JvmType] }
  private[sql] val ordering = implicitly[Ordering[JvmType]]
  def simpleString: String = "string"
}

case object BinaryType extends DataType with PrimitiveType {
  private[sql] type JvmType = Array[Byte]
  def simpleString: String = "binary"
}

case object BooleanType extends NativeType with PrimitiveType {
  private[sql] type JvmType = Boolean
  @transient private[sql] lazy val tag = ScalaReflectionLock.synchronized { typeTag[JvmType] }
  private[sql] val ordering = implicitly[Ordering[JvmType]]
  def simpleString: String = "boolean"
}

case object TimestampType extends NativeType {
  private[sql] type JvmType = Timestamp

  @transient private[sql] lazy val tag = ScalaReflectionLock.synchronized { typeTag[JvmType] }

  private[sql] val ordering = new Ordering[JvmType] {
    def compare(x: Timestamp, y: Timestamp) = x.compareTo(y)
  }

  def simpleString: String = "timestamp"
}

abstract class NumericType extends NativeType with PrimitiveType {
  // Unfortunately we can't get this implicitly as that breaks Spark Serialization. In order for
  // implicitly[Numeric[JvmType]] to be valid, we have to change JvmType from a type variable to a
  // type parameter and and add a numeric annotation (i.e., [JvmType : Numeric]). This gets
  // desugared by the compiler into an argument to the objects constructor. This means there is no
  // longer an no argument constructor and thus the JVM cannot serialize the object anymore.
  private[sql] val numeric: Numeric[JvmType]
}

/** Matcher for any expressions that evaluate to [[IntegralType]]s */
object IntegralType {
  def unapply(a: Expression): Boolean = a match {
    case e: Expression if e.dataType.isInstanceOf[IntegralType] => true
    case _ => false
  }
}

abstract class IntegralType extends NumericType {
  private[sql] val integral: Integral[JvmType]
}

case object LongType extends IntegralType {
  private[sql] type JvmType = Long
  @transient private[sql] lazy val tag = ScalaReflectionLock.synchronized { typeTag[JvmType] }
  private[sql] val numeric = implicitly[Numeric[Long]]
  private[sql] val integral = implicitly[Integral[Long]]
  private[sql] val ordering = implicitly[Ordering[JvmType]]
  def simpleString: String = "long"
}

case object IntegerType extends IntegralType {
  private[sql] type JvmType = Int
  @transient private[sql] lazy val tag = ScalaReflectionLock.synchronized { typeTag[JvmType] }
  private[sql] val numeric = implicitly[Numeric[Int]]
  private[sql] val integral = implicitly[Integral[Int]]
  private[sql] val ordering = implicitly[Ordering[JvmType]]
  def simpleString: String = "integer"
}

case object ShortType extends IntegralType {
  private[sql] type JvmType = Short
  @transient private[sql] lazy val tag = ScalaReflectionLock.synchronized { typeTag[JvmType] }
  private[sql] val numeric = implicitly[Numeric[Short]]
  private[sql] val integral = implicitly[Integral[Short]]
  private[sql] val ordering = implicitly[Ordering[JvmType]]
  def simpleString: String = "short"
}

case object ByteType extends IntegralType {
  private[sql] type JvmType = Byte
  @transient private[sql] lazy val tag = ScalaReflectionLock.synchronized { typeTag[JvmType] }
  private[sql] val numeric = implicitly[Numeric[Byte]]
  private[sql] val integral = implicitly[Integral[Byte]]
  private[sql] val ordering = implicitly[Ordering[JvmType]]
  def simpleString: String = "byte"
}

/** Matcher for any expressions that evaluate to [[FractionalType]]s */
object FractionalType {
  def unapply(a: Expression): Boolean = a match {
    case e: Expression if e.dataType.isInstanceOf[FractionalType] => true
    case _ => false
  }
}
abstract class FractionalType extends NumericType {
  private[sql] val fractional: Fractional[JvmType]
}

case object DecimalType extends FractionalType {
  private[sql] type JvmType = BigDecimal
  @transient private[sql] lazy val tag = ScalaReflectionLock.synchronized { typeTag[JvmType] }
  private[sql] val numeric = implicitly[Numeric[BigDecimal]]
  private[sql] val fractional = implicitly[Fractional[BigDecimal]]
  private[sql] val ordering = implicitly[Ordering[JvmType]]
  def simpleString: String = "decimal"
}

case object DoubleType extends FractionalType {
  private[sql] type JvmType = Double
  @transient private[sql] lazy val tag = ScalaReflectionLock.synchronized { typeTag[JvmType] }
  private[sql] val numeric = implicitly[Numeric[Double]]
  private[sql] val fractional = implicitly[Fractional[Double]]
  private[sql] val ordering = implicitly[Ordering[JvmType]]
  def simpleString: String = "double"
}

case object FloatType extends FractionalType {
  private[sql] type JvmType = Float
  @transient private[sql] lazy val tag = ScalaReflectionLock.synchronized { typeTag[JvmType] }
  private[sql] val numeric = implicitly[Numeric[Float]]
  private[sql] val fractional = implicitly[Fractional[Float]]
  private[sql] val ordering = implicitly[Ordering[JvmType]]
  def simpleString: String = "float"
}

object ArrayType {
  /** Construct a [[ArrayType]] object with the given element type. The `containsNull` is false. */
  def apply(elementType: DataType): ArrayType = ArrayType(elementType, false)
}

case class ArrayType(elementType: DataType, containsNull: Boolean) extends DataType {
  private[sql] def buildFormattedString(prefix: String, builder: StringBuilder): Unit = {
    builder.append(
      s"${prefix}-- element: ${elementType.simpleString} (containsNull = ${containsNull})\n")
    DataType.buildFormattedString(elementType, s"$prefix    |", builder)
  }

  def simpleString: String = "array"
}

case class StructField(name: String, dataType: DataType, nullable: Boolean) {

  private[sql] def buildFormattedString(prefix: String, builder: StringBuilder): Unit = {
    builder.append(s"${prefix}-- ${name}: ${dataType.simpleString} (nullable = ${nullable})\n")
    DataType.buildFormattedString(dataType, s"$prefix    |", builder)
  }
}

object StructType {
  protected[sql] def fromAttributes(attributes: Seq[Attribute]): StructType =
    StructType(attributes.map(a => StructField(a.name, a.dataType, a.nullable)))

  private def validateFields(fields: Seq[StructField]): Boolean =
    fields.map(field => field.name).distinct.size == fields.size
}

case class StructType(fields: Seq[StructField]) extends DataType {
  require(StructType.validateFields(fields), "Found fields with the same name.")

  /**
   * Extracts a [[StructField]] of the given name. If the [[StructType]] object does not
   * have a name matching the given name, `null` will be returned.
   */
  def apply(name: String): StructField = {
    fields.find(f => f.name == name).orNull
  }

  /**
   * Returns a [[StructType]] containing [[StructField]]s of the given names.
   * Those names which do not have matching fields will be ignored.
   */
  def apply(names: Set[String]): StructType = {
    StructType(fields.filter(f => names.contains(f.name)))
  }

  protected[sql] def toAttributes =
    fields.map(f => AttributeReference(f.name, f.dataType, f.nullable)())

  def treeString: String = {
    val builder = new StringBuilder
    builder.append("root\n")
    val prefix = " |"
    fields.foreach(field => field.buildFormattedString(prefix, builder))

    builder.toString()
  }

  def printTreeString(): Unit = println(treeString)

  private[sql] def buildFormattedString(prefix: String, builder: StringBuilder): Unit = {
    fields.foreach(field => field.buildFormattedString(prefix, builder))
  }

  def simpleString: String = "struct"
}

case class MapType(keyType: DataType, valueType: DataType) extends DataType {
  private[sql] def buildFormattedString(prefix: String, builder: StringBuilder): Unit = {
    builder.append(s"${prefix}-- key: ${keyType.simpleString}\n")
    builder.append(s"${prefix}-- value: ${valueType.simpleString}\n")
    DataType.buildFormattedString(keyType, s"$prefix    |", builder)
    DataType.buildFormattedString(valueType, s"$prefix    |", builder)
  }

  def simpleString: String = "map"
}
