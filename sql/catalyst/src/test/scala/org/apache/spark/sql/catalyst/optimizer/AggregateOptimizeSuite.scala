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
import org.apache.spark.sql.catalyst.expressions.Literal
import org.apache.spark.sql.catalyst.plans.PlanTest
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules.RuleExecutor
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.internal.SQLConf.{CASE_SENSITIVE, GROUP_BY_ORDINAL, GROUPING_WITH_UNION}
import org.apache.spark.sql.types.IntegerType

class AggregateOptimizeSuite extends PlanTest {
  override val conf = new SQLConf().copy(CASE_SENSITIVE -> false, GROUP_BY_ORDINAL -> false)
  val catalog = new SessionCatalog(new InMemoryCatalog, EmptyFunctionRegistry, conf)
  val analyzer = new Analyzer(catalog, conf)

  object Optimize extends RuleExecutor[LogicalPlan] {
    val batches = Batch("Aggregate", FixedPoint(100),
      FoldablePropagation,
      RemoveLiteralFromGroupExpressions,
      RemoveRepetitionFromGroupExpressions) :: Nil
  }

  val testRelation = LocalRelation('a.int, 'b.int, 'c.int)

  test("remove literals in grouping expression") {
    val query = testRelation.groupBy('a, Literal("1"), Literal(1) + Literal(2))(sum('b))
    val optimized = Optimize.execute(analyzer.execute(query))
    val correctAnswer = testRelation.groupBy('a)(sum('b)).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("do not remove all grouping expressions if they are all literals") {
    val query = testRelation.groupBy(Literal("1"), Literal(1) + Literal(2))(sum('b))
    val optimized = Optimize.execute(analyzer.execute(query))
    val correctAnswer = analyzer.execute(testRelation.groupBy(Literal(0))(sum('b)))

    comparePlans(optimized, correctAnswer)
  }

  test("Remove aliased literals") {
    val query = testRelation.select('a, 'b, Literal(1).as('y)).groupBy('a, 'y)(sum('b))
    val optimized = Optimize.execute(analyzer.execute(query))
    val correctAnswer = testRelation.select('a, 'b, Literal(1).as('y)).groupBy('a)(sum('b)).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("remove repetition in grouping expression") {
    val input = LocalRelation('a.int, 'b.int, 'c.int)
    val query = input.groupBy('a + 1, 'b + 2, Literal(1) + 'A, Literal(2) + 'B)(sum('c))
    val optimized = Optimize.execute(analyzer.execute(query))
    val correctAnswer = input.groupBy('a + 1, 'b + 2)(sum('c)).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("split aggregate with expand operator") {
    withSQLConf(GROUPING_WITH_UNION.key -> "true") {
      val a = 'a.int
      val b = 'b.int
      val c = 'c.int
      val nulInt = Literal(null, IntegerType)
      val gid = 'spark_grouping_id.int.withNullability(false)

      val query = GroupingSets(Seq(Seq(), Seq(a), Seq(a, b)), Seq(a, b), testRelation,
        Seq(a, b, count(c).as("count(c)")))

      val optimized1 = SplitAggregateWithExpand(analyzer.execute(query))
      val correctAnswer1 = Union(
        Seq(
          Aggregate(Seq(a, b, gid), Seq(a, b, count(c).as("count(c)")),
            Expand(Seq(Seq(a, b, c, nulInt, nulInt, 3)),
              Seq(a, b, c, a, b, gid),
              Project(Seq(a, b, c, a.as("a"), b.as("b")), testRelation)
            )
          ),
          Aggregate(Seq(a, b, gid), Seq(a, b, count(c).as("count(c)")),
            Expand(Seq(Seq(a, b, c, a, nulInt, 1)),
              Seq(a, b, c, a, b, gid),
              Project(Seq(a, b, c, a.as("a"), b.as("b")), testRelation)
            )
          ),
          Aggregate(Seq(a, b, gid), Seq(a, b, count(c).as("count(c)")),
            Expand(Seq(Seq(a, b, c, a, b, 0)),
              Seq(a, b, c, a, b, gid),
              Project(Seq(a, b, c, a.as("a"), b.as("b")), testRelation)
            )
          )
        )
      )
      comparePlans(optimized1, correctAnswer1, false)

      withSQLConf(GROUPING_EXPAND_PROJECTIONS.key -> "2") {
        val optimized2 = SplitAggregateWithExpand(analyzer.execute(query))
        val correctAnswer2 = Union(
          Seq(
            Aggregate(Seq(a, b, gid), Seq(a, b, count(c).as("count(c)")),
              Expand(Seq(Seq(a, b, c, nulInt, nulInt, 3), Seq(a, b, c, a, nulInt, 1)),
                Seq(a, b, c, a, b, gid),
                Project(Seq(a, b, c, a.as("a"), b.as("b")), testRelation)
              )
            ),
            Aggregate(Seq(a, b, gid), Seq(a, b, count(c).as("count(c)")),
              Expand(Seq(Seq(a, b, c, a, b, 0)),
                Seq(a, b, c, a, b, gid),
                Project(Seq(a, b, c, a.as("a"), b.as("b")), testRelation)
              )
            )
          )
        )
        comparePlans(optimized2, correctAnswer2, false)
      }
    }
  }
}
