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

package org.apache.spark.ml.feature

import org.apache.spark.annotation.{Experimental, Since}
import org.apache.spark.ml.Transformer
import org.apache.spark.ml.param.{BooleanParam, Param, ParamMap, StringArrayParam}
import org.apache.spark.ml.param.shared.{HasInputCol, HasOutputCol}
import org.apache.spark.ml.util._
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.{col, udf}
import org.apache.spark.sql.types.{ArrayType, StringType, StructType}

/**
 * :: Experimental ::
 * A feature transformer that filters out stop words from input.
 * Note: null values from input array are preserved unless adding null to stopWords explicitly.
 * @see [[http://en.wikipedia.org/wiki/Stop_words]]
 */
@Experimental
class StopWordsRemover(override val uid: String)
  extends Transformer with HasInputCol with HasOutputCol with DefaultParamsWritable {

  def this() = this(Identifiable.randomUID("stopWords"))

  /** @group setParam */
  def setInputCol(value: String): this.type = set(inputCol, value)

  /** @group setParam */
  def setOutputCol(value: String): this.type = set(outputCol, value)

  /**
   * the stop words set to be filtered out
   * Default: [[Array.empty]]
   * @group param
   */
  val stopWords: StringArrayParam = new StringArrayParam(this, "stopWords", "stop words")

  /** @group setParam */
  def setStopWords(value: Array[String]): this.type = set(stopWords, value)

  /** @group getParam */
  def getStopWords: Array[String] = $(stopWords)

  /**
   * whether to do a case sensitive comparison over the stop words
   * Default: false
   * @group param
   */
  val caseSensitive: BooleanParam = new BooleanParam(this, "caseSensitive",
    "whether to do case-sensitive comparison during filtering")

  /** @group setParam */
  def setCaseSensitive(value: Boolean): this.type = set(caseSensitive, value)

  /** @group getParam */
  def getCaseSensitive: Boolean = $(caseSensitive)

  setDefault(stopWords -> Array.empty[String], caseSensitive -> false)

  override def transform(dataset: DataFrame): DataFrame = {
    val stopWordsSet = if ($(stopWords).isEmpty) {
      StopWordsRemover.loadStopWords("english").toSet
    } else {
      $(stopWords).toSet
    }

    val outputSchema = transformSchema(dataset.schema)
    val t = if ($(caseSensitive)) {
      udf { terms: Seq[String] =>
        terms.filter(s => !stopWordsSet.contains(s))
      }
    } else {
      val toLower = (s: String) => if (s != null) s.toLowerCase else s
      val lowerStopWords = stopWordsSet.map(toLower(_)).toSet
      udf { terms: Seq[String] =>
        terms.filter(s => !lowerStopWords.contains(toLower(s)))
      }
    }

    val metadata = outputSchema($(outputCol)).metadata
    dataset.select(col("*"), t(col($(inputCol))).as($(outputCol), metadata))
  }

  override def transformSchema(schema: StructType): StructType = {
    validateParams()
    val inputType = schema($(inputCol)).dataType
    require(inputType.sameType(ArrayType(StringType)),
      s"Input type must be ArrayType(StringType) but got $inputType.")
    SchemaUtils.appendColumn(schema, $(outputCol), inputType, schema($(inputCol)).nullable)
  }

  override def copy(extra: ParamMap): StopWordsRemover = defaultCopy(extra)
}

@Since("1.6.0")
object StopWordsRemover extends DefaultParamsReadable[StopWordsRemover] {

  private val supportedLanguages = Set("danish", "dutch", "english", "finnish", "french", "german",
    "hungarian", "italian", "norwegian", "portuguese", "russian", "spanish", "swedish", "turkish")

  @Since("1.6.0")
  override def load(path: String): StopWordsRemover = super.load(path)

  /**
   * Load stop words for the language
   * Supported languages: danish, dutch, english, finnish, french, german, hungarian,
   * italian, norwegian, portuguese, russian, spanish, swedish, turkish
   * @see [[http://anoncvs.postgresql.org/cvsweb.cgi/pgsql/src/backend/snowball/stopwords/]]
   */
  def loadStopWords(language: String): Array[String] = {
    require(supportedLanguages.contains(language), s"$language is not in language list")
    val is = getClass.getResourceAsStream(s"/org/apache/spark/ml/feature/stopwords/$language.txt")
    scala.io.Source.fromInputStream(is)(scala.io.Codec.UTF8).getLines().toArray
  }
}
