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

package org.apache.spark.scheduler

import com.codahale.metrics.{Gauge,MetricRegistry}

import org.apache.spark.SparkContext
import org.apache.spark.metrics.source.Source

private[spark] class DAGSchedulerSource(val dagScheduler: DAGScheduler, sc: SparkContext)
    extends Source {
  override val metricRegistry = new MetricRegistry()
  override val sourceName = "DAGScheduler"

  metricRegistry.register(MetricRegistry.name("stage", "failedStages"), new Gauge[Int] {
    override def getValue: Int = dagScheduler.failedStages.size
  })

  metricRegistry.register(MetricRegistry.name("stage", "runningStages"), new Gauge[Int] {
    override def getValue: Int = dagScheduler.runningStages.size
  })

  metricRegistry.register(MetricRegistry.name("stage", "waitingStages"), new Gauge[Int] {
    override def getValue: Int = dagScheduler.waitingStages.size
  })

  metricRegistry.register(MetricRegistry.name("job", "allJobs"), new Gauge[Int] {
    override def getValue: Int = dagScheduler.numTotalJobs
  })

  metricRegistry.register(MetricRegistry.name("job", "activeJobs"), new Gauge[Int] {
    override def getValue: Int = dagScheduler.activeJobs.size
  })
}
