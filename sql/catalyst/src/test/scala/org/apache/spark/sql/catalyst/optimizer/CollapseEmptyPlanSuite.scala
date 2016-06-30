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

import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.dsl.expressions._
import org.apache.spark.sql.catalyst.dsl.plans._
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.catalyst.plans.logical.{LocalRelation, LogicalPlan}
import org.apache.spark.sql.catalyst.rules.RuleExecutor

class CollapseEmptyPlanSuite extends PlanTest {
  object Optimize extends RuleExecutor[LogicalPlan] {
    val batches =
      Batch("CollapseEmptyPlan", Once,
        CombineUnions,
        ReplaceDistinctWithAggregate,
        ReplaceExceptWithAntiJoin,
        ReplaceIntersectWithSemiJoin,
        PushDownPredicate,
        PruneFilters,
        CollapseEmptyPlan) :: Nil
  }

  object OptimizeWithoutCollapseEmptyPlan extends RuleExecutor[LogicalPlan] {
    val batches =
      Batch("CollapseEmptyPlan", Once,
        CombineUnions,
        ReplaceDistinctWithAggregate,
        ReplaceExceptWithAntiJoin,
        ReplaceIntersectWithSemiJoin,
        PushDownPredicate,
        PruneFilters) :: Nil
  }

  val testRelation1 = LocalRelation.fromExternalRows(Seq('a.int), data = Seq(Row(1)))
  val testRelation2 = LocalRelation.fromExternalRows(Seq('b.int), data = Seq(Row(1)))
  val testRelation3 = LocalRelation.fromExternalRows(Seq('c.int), data = Seq(Row(1)))

  test("Binary(or Higher) Logical Plans - Collapse empty union") {
    val query = testRelation1
      .where(false)
      .union(testRelation2.where(false))

    val optimized = Optimize.execute(query.analyze)
    val correctAnswer = LocalRelation('a.int)

    comparePlans(optimized, correctAnswer)
  }

  test("Binary Logical Plans - Collapse joins") {
    // Testcases are tuples of (left predicate, right predicate, joinType, correct answer)
    // Note that `None` is used to compare with OptimizeWithoutCollapseEmptyPlan.
    val testcases = Seq(
      (true, true, Inner, None),
      (true, true, LeftOuter, None),
      (true, true, RightOuter, None),
      (true, true, FullOuter, None),
      (true, true, LeftAnti, None),
      (true, true, LeftSemi, None),

      (true, false, Inner, Some(LocalRelation('a.int, 'b.int))),
      (true, false, LeftOuter, None),
      (true, false, RightOuter, Some(LocalRelation('a.int, 'b.int))),
      (true, false, FullOuter, None),
      (true, false, LeftAnti, None),
      (true, false, LeftSemi, None),

      (false, true, Inner, Some(LocalRelation('a.int, 'b.int))),
      (false, true, LeftOuter, Some(LocalRelation('a.int, 'b.int))),
      (false, true, RightOuter, None),
      (false, true, FullOuter, None),
      (false, true, LeftAnti, Some(LocalRelation('a.int))),
      (false, true, LeftSemi, Some(LocalRelation('a.int))),

      (false, false, Inner, Some(LocalRelation('a.int, 'b.int))),
      (false, false, LeftOuter, Some(LocalRelation('a.int, 'b.int))),
      (false, false, RightOuter, Some(LocalRelation('a.int, 'b.int))),
      (false, false, FullOuter, Some(LocalRelation('a.int, 'b.int))),
      (false, false, LeftAnti, Some(LocalRelation('a.int))),
      (false, false, LeftSemi, Some(LocalRelation('a.int)))
    )

    testcases.foreach { case (left, right, jt, answer) =>
      val query = testRelation1
        .where(left)
        .join(testRelation2.where(right), joinType = jt, condition = Some('a.attr == 'b.attr))
      val optimized = Optimize.execute(query.analyze)
      val correctAnswer = answer.getOrElse(OptimizeWithoutCollapseEmptyPlan.execute(query.analyze))
      comparePlans(optimized, correctAnswer)
    }
  }

  test("Unary Logical Plans - Collapse one empty local relation") {
    val query = testRelation1
      .where(false)
      .select('a)
      .groupBy('a)('a)
      .where('a > 1)
      .orderBy('a.asc)

    val optimized = Optimize.execute(query.analyze)
    val correctAnswer = LocalRelation('a.int)

    comparePlans(optimized, correctAnswer)
  }

  test("Unary Logical Plans - Don't collapse one non-empty local relation") {
    val query = testRelation1
      .where(true)
      .groupBy('a)('a)
      .where('a > 1)
      .orderBy('a.asc)
      .select('a)

    val optimized = Optimize.execute(query.analyze)
    val correctAnswer = testRelation1
      .where('a > 1)
      .groupBy('a)('a)
      .orderBy('a.asc)
      .select('a)

    comparePlans(optimized, correctAnswer.analyze)
  }

  test("Unary Logical Plans - Collapse non-aggregating expressions on empty plan") {
    val query = testRelation1
      .where(false)
      .groupBy('a)('a)

    val optimized = Optimize.execute(query.analyze)
    val correctAnswer = LocalRelation('a.int).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("Unary Logical Plans - Collapse non-aggregating complex expressions on empty plan") {
    val query = testRelation1
      .where(false)
      .groupBy('a)('a, ('a + 1).as('x))

    val optimized = Optimize.execute(query.analyze)
    val correctAnswer = LocalRelation('a.int, 'x.int).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("Unary Logical Plans - Don't collapse aggregating expressions on empty plan") {
    val query = testRelation1
      .where(false)
      .groupBy('a)(count('a))

    val optimized = Optimize.execute(query.analyze)
    val correctAnswer = LocalRelation('a.int).groupBy('a)(count('a)).analyze

    comparePlans(optimized, correctAnswer)
  }
}
