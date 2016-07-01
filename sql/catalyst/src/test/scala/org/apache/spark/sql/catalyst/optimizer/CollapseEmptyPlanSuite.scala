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
import org.apache.spark.sql.catalyst.plans.{FullOuter, Inner, LeftAnti, PlanTest}
import org.apache.spark.sql.catalyst.plans.logical.{LocalRelation, LogicalPlan}
import org.apache.spark.sql.catalyst.rules.RuleExecutor

class CollapseEmptyPlanSuite extends PlanTest {
  object Optimize extends RuleExecutor[LogicalPlan] {
    val batches =
      Batch("CollapseEmptyPlan", Once,
        PushDownPredicate,
        PruneFilters,
        CollapseEmptyPlan) :: Nil
  }

  val testRelation1 = LocalRelation.fromExternalRows(Seq('a.int), data = Seq(Row(1)))
  val testRelation2 = LocalRelation.fromExternalRows(Seq('b.int), data = Seq(Row(1)))

  test("Collapse one empty local relation") {
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

  test("Don't collapse one non-empty local relation") {
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

  test("Collapse one non-empty and one empty local relations INNER join") {
    val query = testRelation1
      .where(true)
      .join(testRelation2.where(false), joinType = Inner, condition = Some('a.attr == 'b.attr))

    val optimized = Optimize.execute(query.analyze)
    val correctAnswer = LocalRelation('a.int, 'b.int).analyze

    comparePlans(optimized, correctAnswer.analyze)
  }

  test("Collapse two empty local relations join") {
    val query = testRelation1
      .where(false)
      .join(testRelation2.where(false), joinType = FullOuter, condition = Some('a.attr == 'b.attr))

    val optimized = Optimize.execute(query.analyze)
    val correctAnswer = LocalRelation('a.int, 'b.int)

    comparePlans(optimized, correctAnswer)
  }

  test("Don't collapse one non-empty and one empty local relations LeftAnti join") {
    val query = testRelation1
      .where(true)
      .join(testRelation2.where(false), joinType = LeftAnti, condition = Some('a.attr == 'b.attr))

    val optimized = Optimize.execute(query.analyze)
    val correctAnswer = testRelation1
      .join(LocalRelation('b.int), joinType = LeftAnti, condition = Some('a.attr == 'b.attr))

    comparePlans(optimized, correctAnswer.analyze)
  }

  test("Don't collapse two non-empty local relations") {
    val query = testRelation1
      .join(testRelation2, condition = Some('a.attr == 'b.attr))

    val optimized = Optimize.execute(query.analyze)
    val correctAnswer = testRelation1
      .join(testRelation2, condition = Some('a.attr == 'b.attr))

    comparePlans(optimized, correctAnswer.analyze)
  }

  test("Collapse non-aggregating expressions on empty plan") {
    val query = testRelation1
      .where(false)
      .groupBy('a)('a)

    val optimized = Optimize.execute(query.analyze)
    val correctAnswer = LocalRelation('a.int).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("Don't collapse aggregating expressions on empty plan") {
    val query = testRelation1
      .where(false)
      .groupBy('a)(count('a))

    val optimized = Optimize.execute(query.analyze)
    val correctAnswer = LocalRelation('a.int).groupBy('a)(count('a)).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("Collapse intersections on one non-empty and one empty local relations") {
    val query = testRelation1
      .where(true)
      .intersect(testRelation2.where(false))

    val optimized = Optimize.execute(query.analyze)
    val correctAnswer = LocalRelation('a.int).analyze

    comparePlans(optimized, correctAnswer.analyze)
  }

  test("Don't collapse intersections on non-empty local relations") {
    val query = testRelation1
      .intersect(testRelation2)

    val optimized = Optimize.execute(query.analyze)
    val correctAnswer = query.analyze

    comparePlans(optimized, correctAnswer.analyze)
  }
}
