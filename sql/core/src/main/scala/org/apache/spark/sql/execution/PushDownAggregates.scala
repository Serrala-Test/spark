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

package org.apache.spark.sql.execution

import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution.aggregate.BaseAggregateExec
import org.apache.spark.sql.execution.exchange.{REPARTITION, ShuffleExchangeExec}
import org.apache.spark.sql.internal.SQLConf

/**
 * Pushes (partial) aggregates bellow manually inserted repartition nodes
 * to reduce the amount of data exchanged.
 */
object PushDownAggregates extends Rule[SparkPlan] {
  def apply(plan: SparkPlan): SparkPlan = {
    if (conf.getConf(SQLConf.PUSH_DOWN_AGGREGATES_ENABLED)) {
      pushDownAggregates(plan)
    } else {
      plan
    }
  }

  private def pushDownAggregates(plan: SparkPlan): SparkPlan = {
    plan transform  {
      case UnaryExecNode(agg: BaseAggregateExec, UnaryExecNode(e: ShuffleExchangeExec, child))
        if child.outputPartitioning.satisfies(agg.requiredChildDistribution.head)
          && e.shuffleOrigin == REPARTITION =>
        e.copy(child = agg.withNewChildren(child :: Nil))
    }
  }
}
