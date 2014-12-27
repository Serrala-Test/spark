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

package org.apache.spark.mllib.regression

import org.apache.spark.mllib.linalg.Vector
import org.apache.spark.rdd.RDD

/**
 * Regression model for Isotonic regression
 *
 * @param predictions Weights computed for every feature.
 * @param isotonic isotonic (increasing) or antitonic (decreasing) sequence
 */
class IsotonicRegressionModel(
    val predictions: Seq[WeightedLabeledPoint],
    val isotonic: Boolean)
  extends RegressionModel {

  override def predict(testData: RDD[Vector]): RDD[Double] =
    testData.map(predict)

  override def predict(testData: Vector): Double = {
    // Take the highest of data points smaller than our feature or data point with lowest feature
    (predictions.head +:
      predictions.filter(y => y.features.toArray.head <= testData.toArray.head)).last.label
  }
}

/**
 * Base representing algorithm for isotonic regression
 */
trait IsotonicRegressionAlgorithm
  extends Serializable {

  /**
   * Creates isotonic regression model with given parameters
   *
   * @param predictions labels estimated using isotonic regression algorithm.
   *                    Used for predictions on new data points.
   * @param isotonic isotonic (increasing) or antitonic (decreasing) sequence
   * @return isotonic regression model
   */
  protected def createModel(
      predictions: Seq[WeightedLabeledPoint],
      isotonic: Boolean): IsotonicRegressionModel

  /**
   * Run algorithm to obtain isotonic regression model
   *
   * @param input data
   * @param isotonic isotonic (increasing) or antitonic (decreasing) sequence
   * @return isotonic regression model
   */
  def run(
      input: RDD[WeightedLabeledPoint],
      isotonic: Boolean): IsotonicRegressionModel
}

/**
 * Parallel pool adjacent violators algorithm for monotone regression
 */
class PoolAdjacentViolators private [mllib]
  extends IsotonicRegressionAlgorithm {

  override def run(
      input: RDD[WeightedLabeledPoint],
      isotonic: Boolean): IsotonicRegressionModel = {
    createModel(
      parallelPoolAdjacentViolators(input, isotonic),
      isotonic)
  }

  override protected def createModel(
      predictions: Seq[WeightedLabeledPoint],
      isotonic: Boolean): IsotonicRegressionModel = {
    new IsotonicRegressionModel(predictions, isotonic)
  }

  /**
   * Performs a pool adjacent violators algorithm (PAVA)
   * Uses approach with single processing of data where violators in previously processed
   * data created by pooling are fixed immediatelly.
   * Uses optimization of discovering monotonicity violating sequences
   * Method in situ mutates input array
   *
   * @param in input data
   * @param isotonic asc or desc
   * @return result
   */
  private def poolAdjacentViolators(
      in: Array[WeightedLabeledPoint],
      isotonic: Boolean): Array[WeightedLabeledPoint] = {

    // Pools sub array within given bounds assigning weighted average value to all elements
    def pool(in: Array[WeightedLabeledPoint], start: Int, end: Int): Unit = {
      val poolSubArray = in.slice(start, end + 1)

      val weightedSum = poolSubArray.map(lp => lp.label * lp.weight).sum
      val weight = poolSubArray.map(_.weight).sum

      for(i <- start to end) {
        in(i) = WeightedLabeledPoint(weightedSum / weight, in(i).features, in(i).weight)
      }
    }

    var i = 0

    val monotonicityConstrainter: (Double, Double) => Boolean = (x, y) => if(isotonic) {
      x <= y
    } else {
      x >= y
    }

    while(i < in.length) {
      var j = i

      // Find monotonicity violating sequence, if any
      while(j < in.length - 1 && !monotonicityConstrainter(in(j).label, in(j + 1).label)) {
        j = j + 1
      }

      // If monotonicity was not violated, move to next data point
      if(i == j) {
        i = i + 1
      } else {
        // Otherwise pool the violating sequence
        // And check if pooling caused monotonicity violation in previously processed points
        while (i >= 0 && !monotonicityConstrainter(in(i).label, in(i + 1).label)) {
          pool(in, i, j)
          i = i - 1
        }

        i = j
      }
    }

    in
  }

  /**
   * Performs parallel pool adjacent violators algorithm
   * Calls Pool adjacent violators on each partition and then again on the result
   *
   * @param testData input
   * @param isotonic isotonic (increasing) or antitonic (decreasing) sequence
   * @return result
   */
  private def parallelPoolAdjacentViolators(
      testData: RDD[WeightedLabeledPoint],
      isotonic: Boolean): Seq[WeightedLabeledPoint] = {

    poolAdjacentViolators(
      testData
        .sortBy(_.features.toArray.head)
        .cache()
        .mapPartitions(it => poolAdjacentViolators(it.toArray, isotonic).toIterator)
        .collect(), isotonic)
  }
}

/**
 * Top-level methods for monotone regression (either isotonic or antitonic).
 */
object IsotonicRegression {

  /**
   * Train a monotone regression model given an RDD of (label, features, weight).
   * Currently only one dimensional algorithm is supported (features.length is one)
   * Label is the dependent y value
   * Weight of the data point is the number of measurements. Default is 1
   *
   * @param input RDD of (label, array of features, weight).
   *              Each point describes a row of the data
   *              matrix A as well as the corresponding right hand side label y
   *              and weight as number of measurements
   * @param isotonic isotonic (increasing) or antitonic (decreasing) sequence
   */
  def train(
      input: RDD[WeightedLabeledPoint],
      isotonic: Boolean = true): IsotonicRegressionModel = {
    new PoolAdjacentViolators().run(input, isotonic)
  }
}
