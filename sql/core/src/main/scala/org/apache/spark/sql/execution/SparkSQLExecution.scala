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

import java.util.concurrent.atomic.AtomicLong

import org.apache.spark.SparkContext
import org.apache.spark.sql.{DataFrame, SQLContext}
import org.apache.spark.util.Utils

private[sql] object SparkSQLExecution {

  val EXECUTION_ID_KEY = "spark.sql.execution.id"

  private val _nextExecutionId = new AtomicLong(0)

  private def nextExecutionId: Long = _nextExecutionId.getAndIncrement

  /**
   * Wrap a DataFrame action to track all Spark jobs in the body so that we can connect them with
   * an execution.
   */
  def withNewExecution[T](sqlContext: SQLContext, df: DataFrame)(body: => T): T = {
    val sc = sqlContext.sparkContext
    val oldExecutionId = sc.getLocalProperty(EXECUTION_ID_KEY)
    try {
      if (oldExecutionId == null) {
        val executionId = SparkSQLExecution.nextExecutionId
        sc.setLocalProperty(EXECUTION_ID_KEY, executionId.toString)
        val callSite = Utils.getCallSite()
        sqlContext.listener.onExecutionStart(
          executionId, callSite.shortForm, callSite.longForm, df, System.currentTimeMillis())
        val r = body
        sqlContext.listener.onExecutionEnd(executionId, System.currentTimeMillis())
        r
      } else {
        // Don't support nested `withNewExecution`. This is an example of the nested
        // `withNewExecution`:
        //
        // class DataFrame {
        //   def foo: T = withNewExecution { something.createNewDataFrame().collect() }
        // }
        //
        // Note: `collect` will call withNewExecution
        // In this case, only the "executedPlan" for "collect" will be executed. The "executedPlan"
        // for the outer DataFrame won't be executed. So it's meaningless to create a new Execution
        // for the outer DataFrame. Even if we track it, since its "executedPlan" doesn't run,
        // all accumulator metrics will be 0. It will confuse people if we show them in Web UI.
        //
        // A real case is the `DataFrame.count` method.
        throw new IllegalArgumentException(s"$EXECUTION_ID_KEY is already set")
      }
    } finally {
      if (oldExecutionId == null) {
        sc.setLocalProperty(EXECUTION_ID_KEY, null)
      }
    }
  }

  def withExecutionId[T](sc: SparkContext, executionId: String)(body: => T): T = {
    val oldExecutionId = sc.getLocalProperty(SparkSQLExecution.EXECUTION_ID_KEY)
    try {
      sc.setLocalProperty(SparkSQLExecution.EXECUTION_ID_KEY, executionId)
      body
    } finally {
      sc.setLocalProperty(SparkSQLExecution.EXECUTION_ID_KEY, oldExecutionId)
    }
  }
}
