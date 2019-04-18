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

package org.apache.spark.sql.execution.statsEstimation

import org.apache.spark.sql.catalyst.plans.logical.Statistics
import org.apache.spark.sql.execution.SparkPlan

/**
 * A trait to add statistics propagation to [[SparkPlan]].
 */
trait SparkPlanStats { self: SparkPlan =>

  private var _stats: Option[Statistics] = None

  def stats: Option[Statistics] = _stats

  def withStats(value: Statistics): SparkPlan = {
    _stats = Option(value)
    this
  }

  def rowCountStats: BigInt = {
    if (stats.isDefined && stats.get.rowCount.isDefined) {
      stats.get.rowCount.get
    } else {
      -1
    }
  }
}
