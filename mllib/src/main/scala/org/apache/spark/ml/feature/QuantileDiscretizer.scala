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

import scala.collection.mutable

import org.apache.spark.annotation.AlphaComponent
import org.apache.spark.ml._
import org.apache.spark.ml.attribute.NominalAttribute
import org.apache.spark.ml.param.shared.{HasInputCol, HasOutputCol}
import org.apache.spark.ml.param.{IntParam, _}
import org.apache.spark.ml.util.SchemaUtils
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types.{DoubleType, StructType}
import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.util.random.XORShiftRandom

/**
 * Params for [[QuantileDiscretizer]].
 */
private[feature] trait QuantileDiscretizerBase extends Params with HasInputCol with HasOutputCol {

  /**
   * Number of buckets to collect data points, which should be a positive integer.
   * @group param
   */
  val numBuckets = new IntParam(this, "numBuckets",
    "Number of buckets to collect data points, which should be a positive integer.",
    ParamValidators.gtEq(2))
  setDefault(numBuckets -> 2)

  /** @group getParam */
  def getNumBuckets: Int = getOrDefault(numBuckets)
}

/**
 * :: AlphaComponent ::
 * `QuantileDiscretizer` takes a column with continuous features and outputs a column with binned
 * categorical features.
 */
@AlphaComponent
final class QuantileDiscretizer extends Estimator[Bucketizer] with QuantileDiscretizerBase {

  /** @group setParam */
  def setNumBuckets(value: Int): this.type = set(numBuckets, value)

  /** @group setParam */
  def setInputCol(value: String): this.type = set(inputCol, value)

  /** @group setParam */
  def setOutputCol(value: String): this.type = set(outputCol, value)

  override def transformSchema(schema: StructType): StructType = {
    SchemaUtils.checkColumnType(schema, $(inputCol), DoubleType)
    val inputFields = schema.fields
    require(inputFields.forall(_.name != $(outputCol)),
      s"Output column ${$(outputCol)} already exists.")
    val attr = NominalAttribute.defaultAttr.withName($(outputCol))
    val outputFields = inputFields :+ attr.toStructField()
    StructType(outputFields)
  }

  override def fit(dataset: DataFrame): Bucketizer = {
    val input = dataset.select($(inputCol)).map { case Row(feature: Double) => feature }
    val samples = getSampledInput(input, $(numBuckets))
    val splits = findSplits(samples, $(numBuckets) - 1)
    val bucketizer = new Bucketizer(this).setBuckets(splits)
    copyValues(bucketizer)
  }

  /**
   * Sampling from the given dataset to collect quantile statistics.
   */
  private def getSampledInput(dataset: RDD[Double], numBins: Int): Array[Double] = {
    val totalSamples = dataset.count()
    assert(totalSamples > 0)
    val requiredSamples = math.max(numBins * numBins, 10000)
    val fraction = math.min(requiredSamples / dataset.count(), 1.0)
    dataset.sample(withReplacement = false, fraction, new XORShiftRandom().nextInt()).collect()
  }

  /**
   * Compute split points with respect to the sample distribution.
   */
  private def findSplits(samples: Array[Double], numSplits: Int): Array[Double] = {
    val valueCountMap = samples.foldLeft(Map.empty[Double, Int]) { (m, x) =>
      m + ((x, m.getOrElse(x, 0) + 1))
    }
    val valueCounts = valueCountMap.toSeq.sortBy(_._1).toArray
    val possibleSplits = valueCounts.length
    if (possibleSplits <= numSplits) {
      valueCounts.map(_._1)
    } else {
      val stride: Double = samples.length.toDouble / (numSplits + 1)
      val splitsBuilder = mutable.ArrayBuilder.make[Double]
      var index = 1
      // currentCount: sum of counts of values that have been visited
      var currentCount = valueCounts(0)._2
      // targetCount: target value for `currentCount`.
      // If `currentCount` is closest value to `targetCount`,
      // then current value is a split threshold.
      // After finding a split threshold, `targetCount` is added by stride.
      var targetCount = stride
      while (index < valueCounts.length) {
        val previousCount = currentCount
        currentCount += valueCounts(index)._2
        val previousGap = math.abs(previousCount - targetCount)
        val currentGap = math.abs(currentCount - targetCount)
        // If adding count of current value to currentCount
        // makes the gap between currentCount and targetCount smaller,
        // previous value is a split threshold.
        if (previousGap < currentGap) {
          splitsBuilder += valueCounts(index - 1)._1
          targetCount += stride
        }
        index += 1
      }
      splitsBuilder.result()
    }
  }
}

