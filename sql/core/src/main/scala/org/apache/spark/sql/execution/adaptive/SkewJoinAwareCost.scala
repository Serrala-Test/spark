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

package org.apache.spark.sql.execution.adaptive

import org.apache.spark.sql.errors.QueryExecutionErrors
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.exchange.ShuffleExchangeLike
import org.apache.spark.sql.execution.joins.ShuffledJoin

/**
 * A skew join aware implementation of [[Cost]], which consider shuffle number and skew join number
 */
case class SkewJoinAwareCost(
    numShuffles: Int,
    numSkewJoins: Int) extends Cost {
  override def compare(that: Cost): Int = that match {
    case other: SkewJoinAwareCost =>
      if (numSkewJoins > other.numSkewJoins || numShuffles < other.numShuffles) {
        // If more skew joins are optimized or less shuffle nodes, it means the cost is lower
        -1
      } else if (numShuffles > other.numShuffles) {
        1
      } else {
        0
      }

    case _ =>
      throw QueryExecutionErrors.cannotCompareCostWithTargetCostError(that.toString)
  }
}

/**
 * A skew join aware implementation of [[CostEvaluator]], which counts the number of
 * [[ShuffleExchangeLike]] nodes and skew join nodes in the plan.
 */
case class SkewJoinAwareCostEvaluator(forceOptimizeSkewedJoin: Boolean) extends CostEvaluator {
  override def evaluateCost(plan: SparkPlan): Cost = {
    val shuffleNumber = plan.collect {
      case s: ShuffleExchangeLike => s
    }.size

    if (forceOptimizeSkewedJoin) {
      val skewJoinNumber = plan.collect {
        case j: ShuffledJoin if j.isSkewJoin => j
      }.size
      SkewJoinAwareCost(shuffleNumber, skewJoinNumber)
    } else {
      SimpleCost(shuffleNumber)
    }
  }
}
