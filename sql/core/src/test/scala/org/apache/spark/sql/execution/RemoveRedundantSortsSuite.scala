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

import org.apache.spark.sql.{DataFrame, QueryTest}
import org.apache.spark.sql.execution.adaptive.{AdaptiveSparkPlanHelper, DisableAdaptiveExecutionSuite, EnableAdaptiveExecutionSuite}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession


abstract class RemoveRedundantSortsSuiteBase
    extends QueryTest
    with SharedSparkSession
    with AdaptiveSparkPlanHelper {
  import testImplicits._

  private def checkNumSorts(df: DataFrame, count: Int): Unit = {
    val plan = df.queryExecution.executedPlan
    assert(collectWithSubqueries(plan) { case s: SortExec => s }.length == count)
  }

  private def checkSorts(query: String, enabledCount: Int, disabledCount: Int): Unit = {
    withSQLConf(SQLConf.REMOVE_REDUNDANT_SORTS_ENABLED.key -> "true") {
      val df = sql(query)
      checkNumSorts(df, enabledCount)
      val result = df.collect()
      withSQLConf(SQLConf.REMOVE_REDUNDANT_SORTS_ENABLED.key -> "false") {
        val df = sql(query)
        checkNumSorts(df, disabledCount)
        checkAnswer(df, result)
      }
    }
  }

  test("remove redundant sorts with limit") {
    withTempView("t") {
      spark.range(100).select('id as "key").createOrReplaceTempView("t")
      val query =
        """
          |SELECT key FROM
          | (SELECT key FROM t WHERE key > 10 ORDER BY key DESC LIMIT 10)
          |ORDER BY key DESC
          |""".stripMargin
      checkSorts(query, 0, 1)
    }
  }

  test("remove redundant sorts with broadcast hash join") {
    withTempView("t1", "t2") {
      spark.range(1000).select('id as "key").createOrReplaceTempView("t1")
      spark.range(1000).select('id as "key").createOrReplaceTempView("t2")
      val queryTemplate = """
        |SELECT t1.key FROM
        | (SELECT key FROM t1 WHERE key > 10 ORDER BY key DESC LIMIT 10) t1
        |%s
        | (SELECT key FROM t2 WHERE key > 50 ORDER BY key DESC LIMIT 100) t2
        |ON t1.key = t2.key
        |ORDER BY %s
      """.stripMargin

      val innerJoinAsc = queryTemplate.format("JOIN", "t2.key ASC")
      checkSorts(innerJoinAsc, 1, 1)

      val innerJoinDesc = queryTemplate.format("JOIN", "t2.key DESC")
      checkSorts(innerJoinDesc, 0, 1)

      val innerJoinDesc1 = queryTemplate.format("JOIN", "t1.key DESC")
      checkSorts(innerJoinDesc1, 1, 1)

      val leftOuterJoinDesc = queryTemplate.format("LEFT JOIN", "t1.key DESC")
      checkSorts(leftOuterJoinDesc, 0, 1)
    }
  }

  test("remove redundant sorts with sort merge join") {
    withSQLConf(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1") {
      withTempView("t1", "t2") {
        spark.range(1000).select('id as "key").createOrReplaceTempView("t1")
        spark.range(1000).select('id as "key").createOrReplaceTempView("t2")
        val query = """
          |SELECT t1.key FROM
          | (SELECT key FROM t1 WHERE key > 10 ORDER BY key DESC LIMIT 10) t1
          |JOIN
          | (SELECT key FROM t2 WHERE key > 50 ORDER BY key DESC LIMIT 100) t2
          |ON t1.key = t2.key
          |ORDER BY t1.key
        """.stripMargin

        val queryAsc = query + " ASC"
        checkSorts(queryAsc, 2, 3)

        // Top level sort should only be eliminated if it's order is descending with SMJ.
        val queryDesc = query + " DESC"
        checkSorts(queryDesc, 3, 3)
      }
    }
  }

  test("cached sorted data doesn't need to be re-sorted") {
    withSQLConf(SQLConf.REMOVE_REDUNDANT_SORTS_ENABLED.key -> "true") {
      val df = spark.range(1000).select('id as "key").sort('key.desc).cache()
      val resorted = df.sort('key.desc)
      val sortedAsc = df.sort('key.asc)
      checkNumSorts(df, 0)
      checkNumSorts(resorted, 0)
      checkNumSorts(sortedAsc, 1)
      val result = resorted.collect()
      withSQLConf(SQLConf.REMOVE_REDUNDANT_SORTS_ENABLED.key -> "false") {
        val resorted = df.sort('key.desc)
        checkNumSorts(resorted, 1)
        checkAnswer(resorted, result)
      }
    }
  }
}

class RemoveRedundantSortsSuite extends RemoveRedundantSortsSuiteBase
  with DisableAdaptiveExecutionSuite

class RemoveRedundantSortsSuiteAE extends RemoveRedundantSortsSuiteBase
  with EnableAdaptiveExecutionSuite
