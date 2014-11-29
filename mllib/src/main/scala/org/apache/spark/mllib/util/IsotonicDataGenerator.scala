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

package org.apache.spark.mllib.util

import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.regression.WeightedLabeledPointConversions._
import org.apache.spark.mllib.regression.{LabeledPoint, WeightedLabeledPoint}

import scala.collection.JavaConversions._

object IsotonicDataGenerator {

  /**
   * Return a Java List of ordered labeled points
   * @param labels list of labels for the data points
   * @return Java List of input.
   */
  def generateIsotonicInputAsList(labels: Array[Double]): java.util.List[WeightedLabeledPoint] = {
    seqAsJavaList(generateIsotonicInput(wrapDoubleArray(labels):_*))
  }

  /**
   * Return an ordered sequence of labeled data points with default weights
   * @param labels list of labels for the data points
   * @return sequence of data points
   */
  def generateIsotonicInput(labels: Double*): Seq[WeightedLabeledPoint] = {
    labels.zip(1 to labels.size)
      .map(point => labeledPointToWeightedLabeledPoint(LabeledPoint(point._1, Vectors.dense(point._2))))
  }

  /**
   * Return an ordered sequence of labeled weighted data points
   * @param labels list of labels for the data points
   * @param weights list of weights for the data points
   * @return sequence of data points
   */
  def generateWeightedIsotonicInput(labels: Seq[Double], weights: Seq[Double]): Seq[WeightedLabeledPoint] = {
    labels.zip(1 to labels.size).zip(weights)
      .map(point => WeightedLabeledPoint(point._1._1, Vectors.dense(point._1._2), point._2))
  }
}
