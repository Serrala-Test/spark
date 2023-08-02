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
package org.apache.spark.sql

import scala.reflect.runtime.universe.TypeTag

import org.apache.spark.sql.catalyst.{JavaTypeInference, ScalaReflection}
import org.apache.spark.sql.catalyst.encoders.AgnosticEncoder
import org.apache.spark.sql.catalyst.encoders.AgnosticEncoders._

/**
 * Methods for creating an [[Encoder]].
 *
 * @since 3.5.0
 */
object Encoders {

  /**
   * An encoder for nullable boolean type. The Scala primitive encoder is available as
   * [[scalaBoolean]].
   * @since 3.5.0
   */
  def BOOLEAN: Encoder[java.lang.Boolean] = BoxedBooleanEncoder

  /**
   * An encoder for nullable byte type. The Scala primitive encoder is available as [[scalaByte]].
   * @since 3.5.0
   */
  def BYTE: Encoder[java.lang.Byte] = BoxedByteEncoder

  /**
   * An encoder for nullable short type. The Scala primitive encoder is available as
   * [[scalaShort]].
   * @since 3.5.0
   */
  def SHORT: Encoder[java.lang.Short] = BoxedShortEncoder

  /**
   * An encoder for nullable int type. The Scala primitive encoder is available as [[scalaInt]].
   * @since 3.5.0
   */
  def INT: Encoder[java.lang.Integer] = BoxedIntEncoder

  /**
   * An encoder for nullable long type. The Scala primitive encoder is available as [[scalaLong]].
   * @since 3.5.0
   */
  def LONG: Encoder[java.lang.Long] = BoxedLongEncoder

  /**
   * An encoder for nullable float type. The Scala primitive encoder is available as
   * [[scalaFloat]].
   * @since 3.5.0
   */
  def FLOAT: Encoder[java.lang.Float] = BoxedFloatEncoder

  /**
   * An encoder for nullable double type. The Scala primitive encoder is available as
   * [[scalaDouble]].
   * @since 3.5.0
   */
  def DOUBLE: Encoder[java.lang.Double] = BoxedDoubleEncoder

  /**
   * An encoder for nullable string type.
   *
   * @since 3.5.0
   */
  def STRING: Encoder[java.lang.String] = StringEncoder

  /**
   * An encoder for nullable decimal type.
   *
   * @since 3.5.0
   */
  def DECIMAL: Encoder[java.math.BigDecimal] = DEFAULT_JAVA_DECIMAL_ENCODER

  /**
   * An encoder for nullable date type.
   *
   * @since 3.5.0
   */
  def DATE: Encoder[java.sql.Date] = DateEncoder(lenientSerialization = false)

  /**
   * Creates an encoder that serializes instances of the `java.time.LocalDate` class to the
   * internal representation of nullable Catalyst's DateType.
   *
   * @since 3.5.0
   */
  def LOCALDATE: Encoder[java.time.LocalDate] = STRICT_LOCAL_DATE_ENCODER

  /**
   * Creates an encoder that serializes instances of the `java.time.LocalDateTime` class to the
   * internal representation of nullable Catalyst's TimestampNTZType.
   *
   * @since 3.5.0
   */
  def LOCALDATETIME: Encoder[java.time.LocalDateTime] = LocalDateTimeEncoder

  /**
   * An encoder for nullable timestamp type.
   *
   * @since 3.5.0
   */
  def TIMESTAMP: Encoder[java.sql.Timestamp] = STRICT_TIMESTAMP_ENCODER

  /**
   * Creates an encoder that serializes instances of the `java.time.Instant` class to the internal
   * representation of nullable Catalyst's TimestampType.
   *
   * @since 3.5.0
   */
  def INSTANT: Encoder[java.time.Instant] = STRICT_INSTANT_ENCODER

  /**
   * An encoder for arrays of bytes.
   *
   * @since 3.5.0
   */
  def BINARY: Encoder[Array[Byte]] = BinaryEncoder

  /**
   * Creates an encoder that serializes instances of the `java.time.Duration` class to the
   * internal representation of nullable Catalyst's DayTimeIntervalType.
   *
   * @since 3.5.0
   */
  def DURATION: Encoder[java.time.Duration] = DayTimeIntervalEncoder

  /**
   * Creates an encoder that serializes instances of the `java.time.Period` class to the internal
   * representation of nullable Catalyst's YearMonthIntervalType.
   *
   * @since 3.5.0
   */
  def PERIOD: Encoder[java.time.Period] = YearMonthIntervalEncoder

  /**
   * Creates an encoder for Java Bean of type T.
   *
   * T must be publicly accessible.
   *
   * supported types for java bean field:
   *   - primitive types: boolean, int, double, etc.
   *   - boxed types: Boolean, Integer, Double, etc.
   *   - String
   *   - java.math.BigDecimal, java.math.BigInteger
   *   - time related: java.sql.Date, java.sql.Timestamp, java.time.LocalDate, java.time.Instant
   *   - collection types: array, java.util.List, and map
   *   - nested java bean.
   *
   * @since 3.5.0
   */
  def bean[T](beanClass: Class[T]): Encoder[T] = JavaTypeInference.encoderFor(beanClass)

  private def tupleEncoder[T](encoders: Encoder[_]*): Encoder[T] = {
    ProductEncoder.tuple(encoders.asInstanceOf[Seq[AgnosticEncoder[_]]]).asInstanceOf[Encoder[T]]
  }

  /**
   * An encoder for 2-ary tuples.
   *
   * @since 3.5.0
   */
  def tuple[T1, T2](e1: Encoder[T1], e2: Encoder[T2]): Encoder[(T1, T2)] = tupleEncoder(e1, e2)

  /**
   * An encoder for 3-ary tuples.
   *
   * @since 3.5.0
   */
  def tuple[T1, T2, T3](
      e1: Encoder[T1],
      e2: Encoder[T2],
      e3: Encoder[T3]): Encoder[(T1, T2, T3)] = tupleEncoder(e1, e2, e3)

  /**
   * An encoder for 4-ary tuples.
   *
   * @since 3.5.0
   */
  def tuple[T1, T2, T3, T4](
      e1: Encoder[T1],
      e2: Encoder[T2],
      e3: Encoder[T3],
      e4: Encoder[T4]): Encoder[(T1, T2, T3, T4)] = tupleEncoder(e1, e2, e3, e4)

  /**
   * An encoder for 5-ary tuples.
   *
   * @since 3.5.0
   */
  def tuple[T1, T2, T3, T4, T5](
      e1: Encoder[T1],
      e2: Encoder[T2],
      e3: Encoder[T3],
      e4: Encoder[T4],
      e5: Encoder[T5]): Encoder[(T1, T2, T3, T4, T5)] = tupleEncoder(e1, e2, e3, e4, e5)

  /**
   * An encoder for Scala's product type (tuples, case classes, etc).
   * @since 3.5.0
   */
  def product[T <: Product: TypeTag]: Encoder[T] = ScalaReflection.encoderFor[T]

  /**
   * An encoder for Scala's primitive int type.
   * @since 3.5.0
   */
  def scalaInt: Encoder[Int] = PrimitiveIntEncoder

  /**
   * An encoder for Scala's primitive long type.
   * @since 3.5.0
   */
  def scalaLong: Encoder[Long] = PrimitiveLongEncoder

  /**
   * An encoder for Scala's primitive double type.
   * @since 3.5.0
   */
  def scalaDouble: Encoder[Double] = PrimitiveDoubleEncoder

  /**
   * An encoder for Scala's primitive float type.
   * @since 3.5.0
   */
  def scalaFloat: Encoder[Float] = PrimitiveFloatEncoder

  /**
   * An encoder for Scala's primitive byte type.
   * @since 3.5.0
   */
  def scalaByte: Encoder[Byte] = PrimitiveByteEncoder

  /**
   * An encoder for Scala's primitive short type.
   * @since 3.5.0
   */
  def scalaShort: Encoder[Short] = PrimitiveShortEncoder

  /**
   * An encoder for Scala's primitive boolean type.
   * @since 3.5.0
   */
  def scalaBoolean: Encoder[Boolean] = PrimitiveBooleanEncoder
}
