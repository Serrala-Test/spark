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

package org.apache.spark.sql.execution.ui

import org.apache.spark.{SparkConf, SparkContext, SparkFunSuite}
import org.apache.spark.LocalSparkContext.withSpark
import org.apache.spark.internal.config
import org.apache.spark.sql.{Column, SparkSession}
import org.apache.spark.sql.functions._

class SQLListenerMemorySuite extends SparkFunSuite {

  test("SPARK-22471 - _stageIdToStageMetrics grows too large on long executions") {
    val conf = new SparkConf()
      .setMaster("local[*]")
      .setAppName("MemoryLeakTest")
      /* Don't retry the tasks to run this test quickly */
      .set(config.MAX_TASK_FAILURES, 1)
      .set("spark.ui.retainedStages", "50")
    withSpark(new SparkContext(conf)) { sc =>
      SparkSession.sqlListener.set(null)
      val spark = new SparkSession(sc)
      import spark.implicits._

      val sample = List(
        (1, 10),
        (2, 20),
        (3, 30)
      ).toDF("id", "value")

      /* Some complex computation with many stages. */
      val joins = 1 to 100
      val summedCol: Column = joins
        .map(j => col(s"value$j"))
        .reduce(_ + _)
      val res = joins
        .map { j =>
          sample.select($"id", $"value" * j as s"value$j")
        }
        .reduce(_.join(_, "id"))
        .select($"id", summedCol as "value")
        .groupBy("id")
        .agg(sum($"value") as "value")
        .orderBy("id")
      res.collect()

      sc.listenerBus.waitUntilEmpty(10000)
      assert(spark.sharedState.listener.stageIdToStageMetrics.size <= 50)
    }
  }
}
