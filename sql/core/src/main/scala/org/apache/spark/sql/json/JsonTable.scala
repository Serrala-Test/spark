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

package org.apache.spark.sql.json

import scala.collection.JavaConversions._
import scala.math.BigDecimal

import com.fasterxml.jackson.databind.ObjectMapper

import org.apache.spark.annotation.{AlphaComponent, DeveloperApi, Experimental}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.analysis.HiveTypeCoercion
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.types._
import org.apache.spark.sql.execution.{ExistingRdd, SparkLogicalPlan}
import org.apache.spark.sql.{SQLContext, Logging, SchemaRDD}

/**
 * :: Experimental ::
 *
 * A JSON dataset (text files with one JSON object per line or a RDD[String] with one JSON object
 * per string) can be directly loaded as a [[SchemaRDD]]. The schema of this [[SchemaRDD]] is
 * automatically inferred from the dataset.
 *
 * == SQL Queries ==
 * {{{
 *  val sc: SparkContext // An existing spark context.
 *
 *  import org.apache.spark.sql.SQLContext
 *  val sqlContext = new SQLContext(sc)
 *
 *  // Importing the SQL context gives access to all the SQL functions and implicit conversions.
 *  import sqlContext._
 *
 *  // Create a SchemaRDD from a JSON file (or a directory having JSON files).
 *  val jsonTable = jsonFile("examples/src/main/resources/people.json")
 *  // Or, if you have a JSON dataset as RDD[String]
 *  // val json = sc.textFile("examples/src/main/resources/people.json")
 *  // val jsonTable = jsonRDD(json)
 *
 *  // See the schema of jsonTable.
 *  jsonTable.printSchema()
 *
 *  // Register jsonTable as a table.
 *  jsonTable.registerAsTable("jsonTable")
 *
 *  // Run some queries.
 *  sql("SELECT name, age FROM jsonTable").collect().foreach(println)
 *  sql("SELECT name FROM jsonTable WHERE age >= 10 and age <= 19").collect().foreach(println)
 *
 *  // Create another RDD[String] storing JSON objects.
 *  val anotherDataset = sc.parallelize(
 *  """{"name":"Yin","address":{"city":"Columbus","state":"Ohio"}}""" :: Nil)
 *  val anotherJsonTable = jsonRDD(anotherDataset)
 *
 *  // See the schema of anotherJsonTable.
 *  anotherJsonTable.printSchema()
 *
 *  // Union jsonTable and anotherJsonTable.
 *  val unionedJsonTable = jsonTable.unionAll(anotherJsonTable)
 *
 *  // See the schema of unionedJsonTable.
 *  unionedJsonTable.printSchema()
 *
 *  // Register unionedJsonTable as table.
 *  unionedJsonTable.registerAsTable("unionedJsonTable")
 *
 *  // Run some queries.
 *  sql("SELECT name, age FROM unionedJsonTable").collect().foreach(println)
 *  sql("SELECT name, age, address FROM unionedJsonTable WHERE address.city = 'Columbus'").
 *    collect().foreach(println)
 * }}}
 *
 * == Language Integrated Queries ==
 * {{{
 *  val sc: SparkContext // An existing spark context.
 *
 *  import org.apache.spark.sql.SQLContext
 *  val sqlContext = new SQLContext(sc)
 *
 *  // Importing the SQL context gives access to all the SQL functions and implicit conversions.
 *  import sqlContext._
 *
 *  // Create a SchemaRDD from a JSON file (or a directory having JSON files).
 *  val jsonTable = jsonFile("examples/src/main/resources/people.json")
 *  // Or, if you have a JSON dataset as RDD[String]
 *  // val json = sc.textFile("examples/src/main/resources/people.json")
 *  // val jsonTable = jsonRDD(json)
 *
 *  // See the schema of jsonTable.
 *  jsonTable.printSchema()
 *
 *  // Run some queries.
 *  jsonTable.select('name, 'age).collect().foreach(println)
 *  jsonTable.where('age <=19).select('name).collect().foreach(println)
 *
 *  // Create another RDD[String] storing JSON objects.
 *  val anotherDataset = sc.parallelize(
 *  """{"name":"Yin","address":{"city":"Columbus","state":"Ohio"}}""" :: Nil)
 *  val anotherJsonTable = jsonRDD(anotherDataset)
 *
 *  // See the schema of anotherJsonTable.
 *  anotherJsonTable.printSchema()
 *
 *  // Union jsonTable and anotherJsonTable.
 *  val unionedJsonTable = jsonTable.unionAll(anotherJsonTable)
 *
 *  // See the schema of unionedJsonTable.
 *  unionedJsonTable.printSchema()
 *
 *  // Run some queries.
 *  unionedJsonTable.select('name, 'age).collect().foreach(println)
 *  unionedJsonTable.where(
 *    "address.city".attr === "Columbus").select(
 *      'name, 'age, 'address).collect().foreach(println)
 * }}}
 */

@AlphaComponent
class JsonTable(
    @transient override val sqlContext: SQLContext,
    @transient override protected[spark] val logicalPlan: LogicalPlan,
    protected[spark] val baseRDD: RDD[String],
    protected[spark] val baseSchema: StructType)
  extends SchemaRDD(sqlContext, logicalPlan) {

  // We widen the type those fields with NullType to StringType. We want to use the original
  // schema (baseSchema) with those fields with NullType when we union this JsonTable with another
  // JsonTable.
  protected[json] lazy val schema = JsonTable.nullTypeToStringType(baseSchema)

  /**
   * Combines the tuples of two JsonTables and union their schemas, keeping duplicates.
   *
   * @group Query
   */
  def unionAll(otherPlan: JsonTable): JsonTable = {
    val unionedBaseSchema =
      JsonTable.getCompatibleType(baseSchema, otherPlan.baseSchema).asInstanceOf[StructType]
    val unionedJsonRDD = baseRDD.union(otherPlan.baseRDD)
    val logicalPlan = JsonTable.createLogicalPlan(unionedJsonRDD, unionedBaseSchema)

    new JsonTable(sqlContext, logicalPlan, unionedJsonRDD, unionedBaseSchema)
  }
}

@Experimental
object JsonTable extends Logging {

  @DeveloperApi
  protected[sql] def apply(
      sqlContext: SQLContext,
      json: RDD[String],
      samplingRatio: Double = 1.0): JsonTable = {
    require(samplingRatio > 0)
    val schemaData = if (samplingRatio > 0.99) json else json.sample(false, samplingRatio, 1)

    val allKeys = parseJson(schemaData).map(getAllKeysWithValueTypes).reduce(_ ++ _)

    val baseSchema = createSchema(allKeys)

    new JsonTable(sqlContext, createLogicalPlan(json, baseSchema), json, baseSchema)
  }

  protected[json] def createLogicalPlan(
      json: RDD[String],
      baseSchema: StructType): LogicalPlan = {
    val schema = nullTypeToStringType(baseSchema)

    SparkLogicalPlan(ExistingRdd(asAttributes(schema), parseJson(json).map(asRow(_, schema))))
  }

  protected[json] def createSchema(allKeys: Set[(String, DataType)]): StructType = {
    // Resolve type conflicts
    val resolved = allKeys.groupBy {
      case (key, dataType) => key
    }.map {
      // Now, keys and types are organized in the format of
      // key -> Set(type1, type2, ...).
      case (key, typeSet) => {
        val fieldName = key.substring(1, key.length - 1).split("`.`").toSeq
        val dataType = typeSet.map {
          case (_, dataType) => dataType
        }.reduce((type1: DataType, type2: DataType) => getCompatibleType(type1, type2))

        (fieldName, dataType)
      }
    }

    def makeStruct(values: Seq[Seq[String]], prefix: Seq[String]): StructType = {
      val (topLevel, structLike) = values.partition(_.size == 1)
      val topLevelFields = topLevel.filter {
        name => resolved.get(prefix ++ name).get match {
          case ArrayType(StructType(Nil)) => false
          case ArrayType(_) => true
          case struct: StructType => false
          case _ => true
        }
      }.map {
        a => StructField(a.head, resolved.get(prefix ++ a).get, nullable = true)
      }

      val structFields: Seq[StructField] = structLike.groupBy(_(0)).map {
        case (name, fields) => {
          val nestedFields = fields.map(_.tail)
          val structType = makeStruct(nestedFields, prefix :+ name)
          val dataType = resolved.get(prefix :+ name).get
          dataType match {
            case array: ArrayType => Some(StructField(name, ArrayType(structType), nullable = true))
            case struct: StructType => Some(StructField(name, structType, nullable = true))
            // dataType is StringType means that we have resolved type conflicts involving
            // primitive types and complex types. So, the type of name has been relaxed to
            // StringType. Also, this field should have already been put in topLevelFields.
            case StringType => None
          }
        }
      }.flatMap(field => field).toSeq

      StructType(
        (topLevelFields ++ structFields).sortBy {
        case StructField(name, _, _) => name
      })
    }

    makeStruct(resolved.keySet.toSeq, Nil)
  }

  /**
   * Returns the most general data type for two given data types.
   */
  protected[json] def getCompatibleType(t1: DataType, t2: DataType): DataType = {
    // Try and find a promotion rule that contains both types in question.
    val applicableConversion = HiveTypeCoercion.allPromotions.find(p => p.contains(t1) && p
      .contains(t2))

    // If found return the widest common type, otherwise None
    val returnType = applicableConversion.map(_.filter(t => t == t1 || t == t2).last)

    if (returnType.isDefined) {
      returnType.get
    } else {
      // t1 or t2 is a StructType, ArrayType, or an unexpected type.
      (t1, t2) match {
        case (other: DataType, NullType) => other
        case (NullType, other: DataType) => other
        case (StructType(fields1), StructType(fields2)) => {
          val newFields = (fields1 ++ fields2).groupBy(field => field.name).map {
            case (name, fieldTypes) => {
              val dataType = fieldTypes.map(field => field.dataType).reduce(
                (type1: DataType, type2: DataType) => getCompatibleType(type1, type2))
              StructField(name, dataType, true)
            }
          }
          StructType(newFields.toSeq.sortBy {
            case StructField(name, _, _) => name
          })
        }
        case (ArrayType(elementType1), ArrayType(elementType2)) =>
          ArrayType(getCompatibleType(elementType1, elementType2))
        // TODO: We should use JsonObjectStringType to mark that values of field will be
        // strings and every string is a Json object.
        case (_, _) => StringType
      }
    }
  }

  protected def getPrimitiveType(value: Any): DataType = {
    value match {
      case value: java.lang.String => StringType
      case value: java.lang.Integer => IntegerType
      case value: java.lang.Long => LongType
      // Since we do not have a data type backed by BigInteger,
      // when we see a Java BigInteger, we use DecimalType.
      case value: java.math.BigInteger => DecimalType
      case value: java.lang.Double => DoubleType
      case value: java.math.BigDecimal => DecimalType
      case value: java.lang.Boolean => BooleanType
      case null => NullType
      // Unexpected data type.
      case _ => StringType
    }
  }

  /**
   * Returns the element type of an JSON array. We go through all elements of this array
   * to detect any possible type conflict. We use [[getCompatibleType]] to resolve
   * type conflicts. Right now, when the element of an array is another array, we
   * treat the element as String.
   */
  protected def getTypeOfArray(l: Seq[Any]): ArrayType = {
    val elements = l.flatMap(v => Option(v))
    if (elements.isEmpty) {
      // If this JSON array is empty, we use NullType as a placeholder.
      // If this array is not empty in other JSON objects, we can resolve
      // the type after we have passed through all JSON objects.
      ArrayType(NullType)
    } else {
      val elementType = elements.map {
        e => e match {
          case map: Map[_, _] => StructType(Nil)
          // We have an array of arrays. If those element arrays do not have the same
          // element types, we will return ArrayType[StringType].
          case seq: Seq[_] =>  getTypeOfArray(seq)
          case value => getPrimitiveType(value)
        }
      }.reduce((type1: DataType, type2: DataType) => getCompatibleType(type1, type2))

      ArrayType(elementType)
    }
  }

  /**
   * Figures out all key names and data types of values from a parsed JSON object
   * (in the format of Map[Stirng, Any]). When the value of a key is an JSON object, we
   * only use a placeholder (StructType(Nil)) to mark that it should be a struct
   * instead of getting all fields of this struct because a field does not appear
   * in this JSON object can appear in other JSON objects.
   */
  protected[json] def getAllKeysWithValueTypes(m: Map[String, Any]): Set[(String, DataType)] = {
    m.map{
      // Quote the key with backticks to handle cases which have dots
      // in the field name.
      case (key, dataType) => (s"`$key`", dataType)
    }.flatMap {
      case (key: String, struct: Map[String, Any]) => {
        // The value associted with the key is an JSON object.
        getAllKeysWithValueTypes(struct).map {
          case (k, dataType) => (s"$key.$k", dataType)
        } ++ Set((key, StructType(Nil)))
      }
      case (key: String, array: List[Any]) => {
        // The value associted with the key is an array.
        getTypeOfArray(array) match {
          case ArrayType(StructType(Nil)) => {
            // The elements of this arrays are structs.
            array.asInstanceOf[List[Map[String, Any]]].flatMap {
              element => getAllKeysWithValueTypes(element)
            }.map {
              case (k, dataType) => (s"$key.$k", dataType)
            } :+ (key, ArrayType(StructType(Nil)))
          }
          case ArrayType(elementType) => (key, ArrayType(elementType)) :: Nil
        }
      }
      case (key: String, value) => (key, getPrimitiveType(value)) :: Nil
    }.toSet
  }

  /**
   * Converts a Java Map/List to a Scala Map/List.
   * We do not use Jackson's scala module at here because
   * DefaultScalaModule in jackson-module-scala will make
   * the parsing very slow.
   */
  protected def scalafy(obj: Any): Any = obj match {
    case map: java.util.Map[String, Object] =>
      // .map(identity) is used as a workaround of non-serializable Map
      // generated by .mapValues.
      // This issue is documented at https://issues.scala-lang.org/browse/SI-7005
      map.toMap.mapValues(scalafy).map(identity)
    case list: java.util.List[Object] =>
      list.toList.map(scalafy)
    case atom => atom
  }

  protected[json] def parseJson(json: RDD[String]): RDD[Map[String, Any]] = {
    // According to [Jackson-72: https://jira.codehaus.org/browse/JACKSON-72],
    // ObjectMapper will not return BigDecimal when
    // "DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS" is disabled
    // (see NumberDeserializer.deserialize for the logic).
    // But, we do not want to enable this feature because it will use BigDecimal
    // for every float number, which will be slow.
    // So, right now, we will have Infinity for those BigDecimal number.
    // TODO: Support BigDecimal.
    json.mapPartitions(iter => {
      // When there is a key appearing multiple times (a duplicate key),
      // the ObjectMapper will take the last value associated with this duplicate key.
      // For example: for {"key": 1, "key":2}, we will get "key"->2.
      val mapper = new ObjectMapper()
      iter.map(record => mapper.readValue(record, classOf[Object]))
    }).map(scalafy).map(_.asInstanceOf[Map[String, Any]])
  }

  protected def toLong(value: Any): Long = {
    value match {
      case value: java.lang.Integer => value.asInstanceOf[Int].toLong
      case value: java.lang.Long => value.asInstanceOf[Long]
    }
  }

  protected def toDouble(value: Any): Double = {
    value match {
      case value: java.lang.Integer => value.asInstanceOf[Int].toDouble
      case value: java.lang.Long => value.asInstanceOf[Long].toDouble
      case value: java.lang.Double => value.asInstanceOf[Double]
    }
  }

  protected def toDecimal(value: Any): BigDecimal = {
    value match {
      case value: java.lang.Integer => BigDecimal(value)
      case value: java.lang.Long => BigDecimal(value)
      case value: java.math.BigInteger => BigDecimal(value)
      case value: java.lang.Double => BigDecimal(value)
      case value: java.math.BigDecimal => BigDecimal(value)
    }
  }

  protected def toJsonArrayString(seq: Seq[Any]): String = {
    val builder = new StringBuilder
    builder.append("[")
    var count = 0
    seq.foreach {
      element =>
        if (count > 0) builder.append(",")
        count += 1
        builder.append(toString(element))
    }
    builder.append("]")

    builder.toString()
  }

  protected def toJsonObjectString(map: Map[String, Any]): String = {
    val builder = new StringBuilder
    builder.append("{")
    var count = 0
    map.foreach {
      case (key, value) =>
        if (count > 0) builder.append(",")
        count += 1
        builder.append(s"""\"${key}\":${toString(value)}""")
    }
    builder.append("}")

    builder.toString()
  }

  protected def toString(value: Any): String = {
    value match {
      case value: Map[String, Any] => toJsonObjectString(value)
      case value: Seq[Any] => toJsonArrayString(value)
      case value => Option(value).map(_.toString).orNull
    }
  }

  protected[json] def enforceCorrectType(value: Any, desiredType: DataType): Any ={
    if (value == null) {
      null
    } else {
      desiredType match {
        case ArrayType(elementType) =>
          value.asInstanceOf[Seq[Any]].map(enforceCorrectType(_, elementType))
        case StringType => toString(value)
        case IntegerType => value.asInstanceOf[IntegerType.JvmType]
        case LongType => toLong(value)
        case DoubleType => toDouble(value)
        case DecimalType => toDecimal(value)
        case BooleanType => value.asInstanceOf[BooleanType.JvmType]
        case NullType => null
      }
    }
  }

  protected[json] def asRow(json: Map[String,Any], schema: StructType): Row = {
    val row = new GenericMutableRow(schema.fields.length)
    schema.fields.zipWithIndex.foreach {
      // StructType
      case (StructField(name, fields: StructType, _), i) =>
        row.update(i, json.get(name).flatMap(v => Option(v)).map(
          v => asRow(v.asInstanceOf[Map[String, Any]], fields)).orNull)

      // ArrayType(StructType)
      case (StructField(name, ArrayType(structType: StructType), _), i) =>
        row.update(i,
          json.get(name).flatMap(v => Option(v)).map(
            v => v.asInstanceOf[Seq[Any]].map(
              e => asRow(e.asInstanceOf[Map[String, Any]], structType))).orNull)

      // Other cases
      case (StructField(name, dataType, _), i) =>
        row.update(i, json.get(name).flatMap(v => Option(v)).map(
          enforceCorrectType(_, dataType)).getOrElse(null))
    }

    row
  }

  protected def nullTypeToStringType(struct: StructType): StructType = {
    val fields = struct.fields.map {
      case StructField(fieldName, dataType, nullable) => {
        val newType = dataType match {
          case NullType => StringType
          case ArrayType(NullType) => ArrayType(StringType)
          case struct: StructType => nullTypeToStringType(struct)
          case other: DataType => other
        }
        StructField(fieldName, newType, nullable)
      }
    }

    StructType(fields)
  }

  protected[json] def asAttributes(struct: StructType): Seq[AttributeReference] = {
    struct.fields.map(f => AttributeReference(f.name, f.dataType, nullable = true)())
  }

  protected[json] def asStruct(attributes: Seq[AttributeReference]): StructType = {
    val fields = attributes.map {
      case AttributeReference(name, dataType, nullable) => StructField(name, dataType, nullable)
    }

    StructType(fields)
  }
}
