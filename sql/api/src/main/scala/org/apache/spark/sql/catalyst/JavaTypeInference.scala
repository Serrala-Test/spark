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
package org.apache.spark.sql.catalyst

import java.beans.{Introspector, PropertyDescriptor}
import java.lang.reflect.{ParameterizedType, Type, TypeVariable}
import java.util.{ArrayDeque, List => JList, Map => JMap}
import javax.annotation.Nonnull

import scala.annotation.tailrec
import scala.reflect.ClassTag

import org.apache.spark.sql.catalyst.encoders.AgnosticEncoder
import org.apache.spark.sql.catalyst.encoders.AgnosticEncoders.{ArrayEncoder, BinaryEncoder, BoxedBooleanEncoder, BoxedByteEncoder, BoxedDoubleEncoder, BoxedFloatEncoder, BoxedIntEncoder, BoxedLongEncoder, BoxedShortEncoder, DayTimeIntervalEncoder, DEFAULT_JAVA_DECIMAL_ENCODER, EncoderField, IterableEncoder, JavaBeanEncoder, JavaBigIntEncoder, JavaEnumEncoder, LocalDateTimeEncoder, MapEncoder, PrimitiveBooleanEncoder, PrimitiveByteEncoder, PrimitiveDoubleEncoder, PrimitiveFloatEncoder, PrimitiveIntEncoder, PrimitiveLongEncoder, PrimitiveShortEncoder, STRICT_DATE_ENCODER, STRICT_INSTANT_ENCODER, STRICT_LOCAL_DATE_ENCODER, STRICT_TIMESTAMP_ENCODER, StringEncoder, UDTEncoder, YearMonthIntervalEncoder}
import org.apache.spark.sql.errors.ExecutionErrors
import org.apache.spark.sql.types._

/**
 * Type-inference utilities for POJOs and Java collections.
 */
object JavaTypeInference {
  /**
   * Infers the corresponding SQL data type of a Java type.
   * @param beanType Java type
   * @return (SQL data type, nullable)
   */
  def inferDataType(beanType: Type): (DataType, Boolean) = {
    val encoder = encoderFor(beanType)
    (encoder.dataType, encoder.nullable)
  }

  /**
   * Infer an [[AgnosticEncoder]] for the [[Class]] `cls`.
   */
  def encoderFor[T](cls: Class[T]): AgnosticEncoder[T] = {
    encoderFor(cls.asInstanceOf[Type])
  }

  /**
   * Infer an [[AgnosticEncoder]] for the `beanType`.
   */
  def encoderFor[T](beanType: Type): AgnosticEncoder[T] = {
    encoderFor(beanType, Set.empty).asInstanceOf[AgnosticEncoder[T]]
  }

  private def encoderFor(t: Type, seenTypeSet: Set[Class[_]]): AgnosticEncoder[_] = t match {

    case c: Class[_] if c == java.lang.Boolean.TYPE => PrimitiveBooleanEncoder
    case c: Class[_] if c == java.lang.Byte.TYPE => PrimitiveByteEncoder
    case c: Class[_] if c == java.lang.Short.TYPE => PrimitiveShortEncoder
    case c: Class[_] if c == java.lang.Integer.TYPE => PrimitiveIntEncoder
    case c: Class[_] if c == java.lang.Long.TYPE => PrimitiveLongEncoder
    case c: Class[_] if c == java.lang.Float.TYPE => PrimitiveFloatEncoder
    case c: Class[_] if c == java.lang.Double.TYPE => PrimitiveDoubleEncoder

    case c: Class[_] if c == classOf[java.lang.Boolean] => BoxedBooleanEncoder
    case c: Class[_] if c == classOf[java.lang.Byte] => BoxedByteEncoder
    case c: Class[_] if c == classOf[java.lang.Short] => BoxedShortEncoder
    case c: Class[_] if c == classOf[java.lang.Integer] => BoxedIntEncoder
    case c: Class[_] if c == classOf[java.lang.Long] => BoxedLongEncoder
    case c: Class[_] if c == classOf[java.lang.Float] => BoxedFloatEncoder
    case c: Class[_] if c == classOf[java.lang.Double] => BoxedDoubleEncoder

    case c: Class[_] if c == classOf[java.lang.String] => StringEncoder
    case c: Class[_] if c == classOf[Array[Byte]] => BinaryEncoder
    case c: Class[_] if c == classOf[java.math.BigDecimal] => DEFAULT_JAVA_DECIMAL_ENCODER
    case c: Class[_] if c == classOf[java.math.BigInteger] => JavaBigIntEncoder
    case c: Class[_] if c == classOf[java.time.LocalDate] => STRICT_LOCAL_DATE_ENCODER
    case c: Class[_] if c == classOf[java.sql.Date] => STRICT_DATE_ENCODER
    case c: Class[_] if c == classOf[java.time.Instant] => STRICT_INSTANT_ENCODER
    case c: Class[_] if c == classOf[java.sql.Timestamp] => STRICT_TIMESTAMP_ENCODER
    case c: Class[_] if c == classOf[java.time.LocalDateTime] => LocalDateTimeEncoder
    case c: Class[_] if c == classOf[java.time.Duration] => DayTimeIntervalEncoder
    case c: Class[_] if c == classOf[java.time.Period] => YearMonthIntervalEncoder

    case c: Class[_] if c.isEnum => JavaEnumEncoder(ClassTag(c))

    case c: Class[_] if c.isAnnotationPresent(classOf[SQLUserDefinedType]) =>
      val udt = c.getAnnotation(classOf[SQLUserDefinedType]).udt()
        .getConstructor().newInstance().asInstanceOf[UserDefinedType[Any]]
      val udtClass = udt.userClass.getAnnotation(classOf[SQLUserDefinedType]).udt()
      UDTEncoder(udt, udtClass)

    case c: Class[_] if UDTRegistration.exists(c.getName) =>
      val udt = UDTRegistration.getUDTFor(c.getName).get.getConstructor().
        newInstance().asInstanceOf[UserDefinedType[Any]]
      UDTEncoder(udt, udt.getClass)

    case c: Class[_] if c.isArray =>
      val elementEncoder = encoderFor(c.getComponentType, seenTypeSet)
      ArrayEncoder(elementEncoder, elementEncoder.nullable)

    case ImplementsList(c, Array(elementCls)) =>
      val element = encoderFor(elementCls, seenTypeSet)
      IterableEncoder(ClassTag(c), element, element.nullable, lenientSerialization = false)

    case ImplementsMap(c, Array(keyCls, valueCls)) =>
      val keyEncoder = encoderFor(keyCls, seenTypeSet)
      val valueEncoder = encoderFor(valueCls, seenTypeSet)
      MapEncoder(ClassTag(c), keyEncoder, valueEncoder, valueEncoder.nullable)

    case c: Class[_] =>
      if (seenTypeSet.contains(c)) {
        throw ExecutionErrors.cannotHaveCircularReferencesInBeanClassError(c)
      }

      // TODO: we should only collect properties that have getter and setter. However, some tests
      //   pass in scala case class as java bean class which doesn't have getter and setter.
      val properties = getJavaBeanReadableProperties(c)
      // Note that the fields are ordered by name.
      val fields = properties.map { property =>
        val readMethod = property.getReadMethod
        val encoder = encoderFor(readMethod.getGenericReturnType, seenTypeSet + c)
        // The existence of `javax.annotation.Nonnull`, means this field is not nullable.
        val hasNonNull = readMethod.isAnnotationPresent(classOf[Nonnull])
        EncoderField(
          property.getName,
          encoder,
          encoder.nullable && !hasNonNull,
          Metadata.empty,
          Option(readMethod.getName),
          Option(property.getWriteMethod).map(_.getName))
      }
      JavaBeanEncoder(ClassTag(c), fields)

    case _ =>
      throw ExecutionErrors.cannotFindEncoderForTypeError(t.toString)
  }

  def getJavaBeanReadableProperties(beanClass: Class[_]): Array[PropertyDescriptor] = {
    val beanInfo = Introspector.getBeanInfo(beanClass)
    beanInfo.getPropertyDescriptors.filterNot(_.getName == "class")
      .filterNot(_.getName == "declaringClass")
      .filter(_.getReadMethod != null)
  }

  private class ImplementsGenericInterface(interface: Class[_]) {
    assert(interface.isInterface)
    assert(interface.getTypeParameters.nonEmpty)

    def unapply(t: Type): Option[(Class[_], Array[Type])] = implementsInterface(t).map { cls =>
      cls -> findTypeArgumentsForInterface(t)
    }

    @tailrec
    private def implementsInterface(t: Type): Option[Class[_]] = t match {
      case pt: ParameterizedType => implementsInterface(pt.getRawType)
      case c: Class[_] if interface.isAssignableFrom(c) => Option(c)
      case _ => None
    }

    private def findTypeArgumentsForInterface(t: Type): Array[Type] = {
      val queue = new ArrayDeque[(Type, Map[Any, Type])]
      queue.add(t -> Map.empty)
      while (!queue.isEmpty) {
        queue.poll() match {
          case (pt: ParameterizedType, bindings) =>
            // translate mappings...
            val mappedTypeArguments = pt.getActualTypeArguments.map {
              case v: TypeVariable[_] => bindings(v.getName)
              case v => v
            }
            if (pt.getRawType == interface) {
              return mappedTypeArguments
            } else {
              val mappedTypeArgumentMap = mappedTypeArguments
                .zipWithIndex.map(_.swap)
                .toMap[Any, Type]
              queue.add(pt.getRawType -> mappedTypeArgumentMap)
            }
          case (c: Class[_], indexedBindings) =>
            val namedBindings = c.getTypeParameters.zipWithIndex.map {
              case (parameter, index) =>
                parameter.getName -> indexedBindings(index)
            }.toMap[Any, Type]
            val superClass = c.getGenericSuperclass
            if (superClass != null) {
              queue.add(superClass -> namedBindings)
            }
            c.getGenericInterfaces.foreach { iface =>
              queue.add(iface -> namedBindings)
            }
        }
      }
      throw ExecutionErrors.unreachableError()
    }
  }

  private object ImplementsList extends ImplementsGenericInterface(classOf[JList[_]])
  private object ImplementsMap extends ImplementsGenericInterface(classOf[JMap[_, _]])
}
