package org.apache.spark.sql.catalyst

import java.util.{Map => JavaMap}

import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.types._

/**
 * Functions to convert Scala types to Catalyst types and vice versa.
 */
object ReflectionConverters {
  // The Predef.Map is scala.collection.immutable.Map.
  // Since the map values can be mutable, we explicitly import scala.collection.Map at here.
  import scala.collection.Map

  /**
   * Converts Scala objects to catalyst rows / types.
   * Note: This is always called after schemaFor has been called.
   *       This ordering is important for UDT registration.
   */
  def convertToCatalyst(a: Any, dataType: DataType): Any = (a, dataType) match {
    // Check UDT first since UDTs can override other types
    case (obj, udt: UserDefinedType[_]) => udt.serialize(obj)
    case (o: Option[_], _) => o.map(convertToCatalyst(_, dataType)).orNull
    case (s: Seq[_], arrayType: ArrayType) => s.map(convertToCatalyst(_, arrayType.elementType))
    case (s: Array[_], arrayType: ArrayType) =>
      s.toSeq.map(convertToCatalyst(_, arrayType.elementType))
    case (m: Map[_, _], mapType: MapType) => m.map { case (k, v) =>
      convertToCatalyst(k, mapType.keyType) -> convertToCatalyst(v, mapType.valueType)
    }
    case (jmap: JavaMap[_, _], mapType: MapType) =>
      val iter = jmap.entrySet.iterator
      var listOfEntries: List[(Any, Any)] = List()
      while (iter.hasNext) {
        val entry = iter.next()
        listOfEntries :+= (convertToCatalyst(entry.getKey, mapType.keyType),
          convertToCatalyst(entry.getValue, mapType.valueType))
      }
      listOfEntries.toMap
    case (p: Product, structType: StructType) =>
      val ar = new Array[Any](structType.size)
      val iter = p.productIterator
      var idx = 0
      while (idx < structType.size) {
        ar(idx) = convertToCatalyst(iter.next(), structType.fields(idx).dataType)
        idx += 1
      }
      new GenericRowWithSchema(ar, structType)
    case (d: BigDecimal, _) => Decimal(d)
    case (d: java.math.BigDecimal, _) => Decimal(d)
    case (d: java.sql.Date, _) => DateUtils.fromJavaDate(d)
    case (r: Row, structType: StructType) =>
      val ar = new Array[Any](structType.size)
      var idx = 0
      while (idx < structType.size) {
        ar(idx) = convertToCatalyst(r(idx), structType.fields(idx).dataType)
        idx += 1
      }
      new GenericRowWithSchema(ar, structType)
    case (other, _) => other
  }

  /**
   * Creates a converter function that will convert Scala objects to the specified catalyst type.
   */
  private[sql] def createCatalystConverter(dataType: DataType): Any => Any = {
    def extractOption(item: Any): Any = item match {
      case s: Some[_] => s.get
      case None => null
      case other => other
    }

    dataType match {
      // Check UDT first since UDTs can override other types
      case udt: UserDefinedType[_] =>
        (item) => {
          if (item == None) null else udt.serialize(extractOption(item))
        }

      case arrayType: ArrayType =>
        val elementConverter = createCatalystConverter(arrayType.elementType)
        (item: Any) => {
          extractOption(item) match {
            case a: Array[_] => a.toSeq.map(elementConverter)
            case s: Seq[_] => s.map(elementConverter)
            case null => null
          }
        }

      case mapType: MapType =>
        val keyConverter = createCatalystConverter(mapType.keyType)
        val valueConverter = createCatalystConverter(mapType.valueType)
        (item: Any) => {
          extractOption(item) match {
            case m: Map[_, _] =>
              m.map { case (k, v) =>
                keyConverter(k) -> valueConverter(v)
              }

            case jmap: JavaMap[_, _] =>
              val iter = jmap.entrySet.iterator
              var listOfEntries: List[(Any, Any)] = List()
              while (iter.hasNext) {
                val entry = iter.next()
                listOfEntries :+= (keyConverter(entry.getKey), valueConverter(entry.getValue))
              }
              listOfEntries.toMap

            case null => null
          }
        }

      case structType: StructType =>
        val converters = new Array[Any => Any](structType.length)
        val iter = structType.fields.iterator
        var idx = 0
        while (iter.hasNext) {
          converters(idx) = createCatalystConverter(iter.next().dataType)
          idx += 1
        }
        (item: Any) => {
          extractOption(item) match {
            case r: Row =>
              val ar = new Array[Any](structType.size)
              var idx = 0
              while (idx < structType.size) {
                ar(idx) = convertToCatalyst(r(idx), structType.fields(idx).dataType)
                idx += 1
              }
              new GenericRowWithSchema(ar, structType)

            case p: Product =>
              val ar = new Array[Any](structType.size)
              val iter = p.productIterator
              var idx = 0
              while (idx < structType.size) {
                ar(idx) = converters(idx)(iter.next())
                idx += 1
              }
              new GenericRowWithSchema(ar, structType)

            case null => null
          }
        }

      case _ =>
        (item: Any) => extractOption(item) match {
          case d: BigDecimal => Decimal(d)
          case d: java.math.BigDecimal => Decimal(d)
          case d: java.sql.Date => DateUtils.fromJavaDate(d)
          case other => other
        }
    }
  }

  /** Converts Catalyst types used internally in rows to standard Scala types */
  def convertToScala(a: Any, dataType: DataType): Any = (a, dataType) match {
    // Check UDT first since UDTs can override other types
    case (d, udt: UserDefinedType[_]) => udt.deserialize(d)
    case (s: Seq[_], arrayType: ArrayType) => s.map(convertToScala(_, arrayType.elementType))
    case (m: Map[_, _], mapType: MapType) => m.map { case (k, v) =>
      convertToScala(k, mapType.keyType) -> convertToScala(v, mapType.valueType)
    }
    case (r: Row, s: StructType) => convertRowToScala(r, s)
    case (d: Decimal, _: DecimalType) => d.toJavaBigDecimal
    case (i: Int, DateType) => DateUtils.toJavaDate(i)
    case (other, _) => other
  }

  /**
   * Creates a converter function that will convert Catalyst types to Scala type.
   */
  private[sql] def createScalaConverter(dataType: DataType): Any => Any = dataType match {
    // Check UDT first since UDTs can override other types
    case udt: UserDefinedType[_] =>
      (item: Any) => if (item == null) null else udt.deserialize(item)

    case arrayType: ArrayType =>
      val elementConverter = createScalaConverter(arrayType.elementType)
      (item: Any) => if (item == null) null else item.asInstanceOf[Seq[_]].map(elementConverter)

    case mapType: MapType =>
      val keyConverter = createScalaConverter(mapType.keyType)
      val valueConverter = createScalaConverter(mapType.valueType)
      (item: Any) => if (item == null) {
        null
      } else {
        item.asInstanceOf[Map[_, _]].map { case (k, v) =>
          keyConverter(k) -> valueConverter(v)
        }
      }

    case s: StructType =>
      val converters = createScalaConvertersForStruct(s)
      (item: Any) => item match {
        case r: Row => convertRowWithConverters(r, s, converters)
        case other => other
      }

    case _: DecimalType =>
      (item: Any) => item match {
        case d: Decimal => d.toJavaBigDecimal
        case other => other
      }

    case DateType =>
      (item: Any) => item match {
        case i: Int => DateUtils.toJavaDate(i)
        case other => other
      }

    case other =>
      (item: Any) => item
  }

  def convertRowToScala(r: Row, schema: StructType): Row = {
    val ar = new Array[Any](r.size)
    var idx = 0
    while (idx < r.size) {
      ar(idx) = convertToScala(r(idx), schema.fields(idx).dataType)
      idx += 1
    }
    new GenericRowWithSchema(ar, schema)
  }

  /**
   * Creates Catalyst->Scala converter functions for each field of the given StructType.
   */
  private[sql] def createScalaConvertersForStruct(s: StructType): Array[Any => Any] = {
    s.fields.map(f => createScalaConverter(f.dataType))
  }

  /**
   * Creates Scala->Catalyst converter functions for each field of the given StructType.
   */
  private[sql] def createCatalystConvertersForStruct(s: StructType): Array[Any => Any] = {
    s.fields.map(f => createCatalystConverter(f.dataType))
  }

  /**
   * Converts a row by applying the provided set of converter functions. It is used for both
   * toScala and toCatalyst conversions.
   */
  private[sql] def convertRowWithConverters(
      row: Row,
      schema: StructType,
      converters: Array[Any => Any]): Row = {
    val ar = new Array[Any](row.size)
    var idx = 0
    while (idx < row.size) {
      ar(idx) = converters(idx)(row(idx))
      idx += 1
    }
    new GenericRowWithSchema(ar, schema)
  }
}
