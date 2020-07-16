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

package org.apache.spark.sql.catalyst.optimizer

import org.apache.spark.sql.catalyst.analysis.{Analyzer, EmptyFunctionRegistry}
import org.apache.spark.sql.catalyst.catalog.{InMemoryCatalog, SessionCatalog}
import org.apache.spark.sql.catalyst.dsl.expressions._
import org.apache.spark.sql.catalyst.dsl.plans._
import org.apache.spark.sql.catalyst.expressions.{EqualTo, Literal}
import org.apache.spark.sql.catalyst.plans.PlanTest
import org.apache.spark.sql.catalyst.plans.logical.{Aggregate, LocalRelation, LogicalPlan, Project}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.internal.SQLConf.{CASE_SENSITIVE, GROUP_BY_ORDINAL}
import org.apache.spark.sql.types.{IntegerType, StringType}

class ProjectFilterInAggregatesSuite extends PlanTest {
  override val conf = new SQLConf().copy(CASE_SENSITIVE -> false, GROUP_BY_ORDINAL -> false)
  val catalog = new SessionCatalog(new InMemoryCatalog, EmptyFunctionRegistry, conf)
  val analyzer = new Analyzer(catalog, conf)

  val nullInt = Literal(null, IntegerType)
  val nullString = Literal(null, StringType)
  val testRelation = LocalRelation('a.string, 'b.string, 'c.string, 'd.string, 'e.int)

  private def checkGenerate(generate: LogicalPlan): Unit = generate match {
    case Aggregate(_, _, _: Project) =>
    case _ => fail(s"Plan is not generated:\n$generate")
  }

  test("single distinct group with filter") {
    val input = testRelation
      .groupBy('a)(countDistinct(Some(EqualTo('d, Literal(""))), 'e))
      .analyze
    checkGenerate(ProjectFilterInAggregates(input))
  }

  test("at least one distinct group with filter") {
    val input = testRelation
      .groupBy('a)(countDistinct(Some(EqualTo('d, Literal(""))), 'e), countDistinct('d))
      .analyze
    checkGenerate(ProjectFilterInAggregates(input))
  }

}
