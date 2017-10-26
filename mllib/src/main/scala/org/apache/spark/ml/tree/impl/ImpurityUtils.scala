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

package org.apache.spark.ml.tree.impl

import org.apache.spark.mllib.tree.impurity._
import org.apache.spark.mllib.tree.model.ImpurityStats

/** Helper methods for impurity-related calculations during node split decisions. */
private[impl] object ImpurityUtils {

  private[impl] def getImpurityAggregator(metadata: DecisionTreeMetadata): ImpurityAggregator = {
    metadata.impurity match {
      case Gini => new GiniAggregator(metadata.numClasses)
      case Entropy => new EntropyAggregator(metadata.numClasses)
      case Variance => new VarianceAggregator()
      case _ => throw new IllegalArgumentException(s"Bad impurity parameter: ${metadata.impurity}")
    }
  }

  private[impl] def getImpurityCalculator(
    impurityAggregator: ImpurityAggregator): ImpurityCalculator = {
    val statsSize = impurityAggregator.statsSize
    val stats = new Array[Double](statsSize)
    impurityAggregator.getCalculator(stats, 0)
  }

  /**
   * Calculate the impurity statistics for a given (feature, split) based upon left/right
   * aggregates.
   *
   * @param parentCalc Optional: an ImpurityCalculator containing the impurity stats
   *                                 of the node currently being split.
   * @param leftImpurityCalculator left node aggregates for this (feature, split)
   * @param rightImpurityCalculator right node aggregate for this (feature, split)
   * @param metadata learning and dataset metadata for DecisionTree
   * @return Impurity statistics for this (feature, split)
   */
  private[impl] def calculateImpurityStats(
      parentCalc: Option[ImpurityCalculator],
      leftImpurityCalculator: ImpurityCalculator,
      rightImpurityCalculator: ImpurityCalculator,
      metadata: DecisionTreeMetadata): ImpurityStats = {

    val parentImpurityCalculator
      = parentCalc.getOrElse(leftImpurityCalculator.copy.add(rightImpurityCalculator))
    val impurity: Double = parentImpurityCalculator.calculate()

    val leftCount = leftImpurityCalculator.count
    val rightCount = rightImpurityCalculator.count

    val totalCount = leftCount + rightCount

    // If left child or right child doesn't satisfy minimum instances per node,
    // then this split is invalid, return invalid information gain stats.
    if ((leftCount < metadata.minInstancesPerNode) ||
      (rightCount < metadata.minInstancesPerNode)) {
      return ImpurityStats.getInvalidImpurityStats(parentImpurityCalculator)
    }

    val leftImpurity = leftImpurityCalculator.calculate() // Note: This equals 0 if count = 0
    val rightImpurity = rightImpurityCalculator.calculate()

    val leftWeight = leftCount / totalCount.toDouble
    val rightWeight = rightCount / totalCount.toDouble

    val gain = impurity - leftWeight * leftImpurity - rightWeight * rightImpurity
    // If information gain doesn't satisfy minimum information gain,
    // then this split is invalid, return invalid information gain stats.
    if (gain < metadata.minInfoGain) {
      return ImpurityStats.getInvalidImpurityStats(parentImpurityCalculator)
    }

    // If information gain is non-positive but doesn't violate the minimum info gain constraint,
    // return a stats object with correct values but valid = false to indicate that we should not
    // split.
    if (gain <= 0) {
      return new ImpurityStats(gain, impurity, parentImpurityCalculator, leftImpurityCalculator,
        rightImpurityCalculator, valid = false)
    }


    new ImpurityStats(gain, impurity, parentImpurityCalculator,
      leftImpurityCalculator, rightImpurityCalculator)
  }

  /**
   * Given an impurity aggregator containing label statistics for a given (node, feature, bin),
   * returns the corresponding "centroid", used to order bins while computing best splits.
   *
   * @param metadata learning and dataset metadata for DecisionTree
   */
  private[impl] def getCentroid(
      metadata: DecisionTreeMetadata,
      binStats: ImpurityCalculator): Double = {

    if (binStats.count != 0) {
      if (metadata.isMulticlass) {
        // multiclass classification
        // For categorical features in multiclass classification,
        // the bins are ordered by the impurity of their corresponding labels.
        binStats.calculate()
      } else if (metadata.isClassification) {
        // binary classification
        // For categorical features in binary classification,
        // the bins are ordered by the count of class 1.
        binStats.stats(1)
      } else {
        // regression
        // For categorical features in regression and binary classification,
        // the bins are ordered by the prediction.
        binStats.predict
      }
    } else {
      Double.MaxValue
    }
  }
}
