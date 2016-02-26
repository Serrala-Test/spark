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

package org.apache.spark.sql

import org.apache.spark.sql.catalyst.ParserInterface
import org.apache.spark.sql.catalyst.analysis.{Analyzer, Catalog, FunctionRegistry, SimpleCatalog}
import org.apache.spark.sql.catalyst.optimizer.Optimizer
import org.apache.spark.sql.catalyst.rules.RuleExecutor
import org.apache.spark.sql.execution._
import org.apache.spark.sql.execution.datasources.{PreInsertCastAndRename, ResolveDataSource}
import org.apache.spark.sql.execution.exchange.EnsureRequirements
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.util.ExecutionListenerManager


/**
 * A class that holds all session-specific state in a given [[SQLContext]].
 */
private[sql] class SessionState(ctx: SQLContext) {

  // TODO: add comments everywhere

  val conf = new SQLConf

  val catalog: Catalog = new SimpleCatalog(conf)

  val listenerManager: ExecutionListenerManager = new ExecutionListenerManager

  val continuousQueryManager: ContinuousQueryManager = new ContinuousQueryManager(ctx)

  val functionRegistry: FunctionRegistry = FunctionRegistry.builtin.copy()

  val udf: UDFRegistration = new UDFRegistration(ctx)

  val analyzer: Analyzer = {
    new Analyzer(catalog, functionRegistry, conf) {
      override val extendedResolutionRules =
        python.ExtractPythonUDFs ::
          PreInsertCastAndRename ::
          (if (conf.runSQLOnFile) new ResolveDataSource(ctx) :: Nil else Nil)

      override val extendedCheckRules = Seq(datasources.PreWriteCheck(catalog))
    }
  }

  val optimizer: Optimizer = new SparkOptimizer(ctx)

  val sqlParser: ParserInterface = new SparkQl(conf)

  val planner: SparkPlanner = new SparkPlanner(ctx)

  /**
   * Prepares a planned SparkPlan for execution by inserting shuffle operations and internal
   * row format conversions as needed.
   */
  val prepareForExecution = new RuleExecutor[SparkPlan] {
    val batches = Seq(
      Batch("Subquery", Once, PlanSubqueries(ctx)),
      Batch("Add exchange", Once, EnsureRequirements(ctx)),
      Batch("Whole stage codegen", Once, CollapseCodegenStages(ctx))
    )
  }

}
