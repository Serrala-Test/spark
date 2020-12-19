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
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules._

class CombiningLimitsSuite extends PlanTest {

  object Optimize extends RuleExecutor[LogicalPlan] {
    val batches =
      Batch("Column Pruning", FixedPoint(100),
        ColumnPruning,
        RemoveNoopOperators) ::
      Batch("Eliminate Limit", FixedPoint(10),
        EliminateLimits) ::
      Batch("Constant Folding", FixedPoint(10),
        NullPropagation,
        ConstantFolding,
        BooleanSimplification,
        SimplifyConditionals) :: Nil
  }

  val testRelation = LocalRelation.fromExternalRows(
    Seq(Symbol("a").int, Symbol("b").int, Symbol("c").int),
    1.to(10).map(_ => Row(1, 2, 3))
  )
  val testRelation2 = LocalRelation.fromExternalRows(
    Seq(Symbol("x").int, Symbol("y").int, Symbol("z").int),
    Seq(Row(1, 2, 3), Row(2, 3, 4))
  )
  val testRelation3 = RelationWithoutMaxRows(Seq(Symbol("i").int))
  val testRelation4 = LongMaxRelation(Seq(Symbol("j").int))

  test("limits: combines two limits") {
    val originalQuery =
      testRelation
        .select('a)
        .limit(10)
        .limit(5)

    val optimized = Optimize.execute(originalQuery.analyze)
    val correctAnswer =
      testRelation
        .select('a)
        .limit(5).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("limits: combines three limits") {
    val originalQuery =
      testRelation
        .select('a)
        .limit(2)
        .limit(7)
        .limit(5)

    val optimized = Optimize.execute(originalQuery.analyze)
    val correctAnswer =
      testRelation
        .select('a)
        .limit(2).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("limits: combines two limits after ColumnPruning") {
    val originalQuery =
      testRelation
        .select('a)
        .limit(2)
        .select('a)
        .limit(5)

    val optimized = Optimize.execute(originalQuery.analyze)
    val correctAnswer =
      testRelation
        .select('a)
        .limit(2).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("SPARK-33442: Change Combine Limit to Eliminate limit using max row") {
    // test child max row <= limit.
    val query1 = testRelation.select().groupBy()(count(1)).limit(1).analyze
    val optimized1 = Optimize.execute(query1)
    val expected1 = testRelation.select().groupBy()(count(1)).analyze
    comparePlans(optimized1, expected1)

    // test child max row > limit.
    val query2 = testRelation.select().groupBy()(count(1)).limit(0).analyze
    val optimized2 = Optimize.execute(query2)
    comparePlans(optimized2, query2)

    // test child max row is none
    val query3 = testRelation.select(Symbol("a")).limit(1).analyze
    val optimized3 = Optimize.execute(query3)
    comparePlans(optimized3, query3)

    // test sort after limit
    val query4 = testRelation.select().groupBy()(count(1))
      .orderBy(count(1).asc).limit(1).analyze
    val optimized4 = Optimize.execute(query4)
    // the top project has been removed, so we need optimize expected too
    val expected4 = Optimize.execute(
      testRelation.select().groupBy()(count(1)).orderBy(count(1).asc).analyze)
    comparePlans(optimized4, expected4)
  }

  test("SPARK-33497: Eliminate Limit if LocalRelation max rows not larger than Limit") {
    checkPlanAndMaxRow(
      testRelation.select().limit(10),
      testRelation.select(),
      10
    )
  }

  test("SPARK-33497: Eliminate Limit if Range max rows not larger than Limit") {
    checkPlanAndMaxRow(
      Range(0, 100, 1, None).select().limit(200),
      Range(0, 100, 1, None).select(),
      100
    )
    checkPlanAndMaxRow(
      Range(-1, Long.MaxValue, 1, None).select().limit(1),
      Range(-1, Long.MaxValue, 1, None).select().limit(1),
      1
    )
  }

  test("SPARK-33497: Eliminate Limit if Sample max rows not larger than Limit") {
    checkPlanAndMaxRow(
      testRelation.select().sample(upperBound = 0.2, seed = 1).limit(10),
      testRelation.select().sample(upperBound = 0.2, seed = 1),
      10
    )
  }

  test("SPARK-33497: Eliminate Limit if Deduplicate max rows not larger than Limit") {
    checkPlanAndMaxRow(
      testRelation.deduplicate(Symbol("a")).limit(10),
      testRelation.deduplicate(Symbol("a")),
      10
    )
  }

  test("SPARK-33497: Eliminate Limit if Repartition max rows not larger than Limit") {
    checkPlanAndMaxRow(
      testRelation.repartition(2).limit(10),
      testRelation.repartition(2),
      10
    )
    checkPlanAndMaxRow(
      testRelation.distribute(Symbol("a"))(2).limit(10),
      testRelation.distribute(Symbol("a"))(2),
      10
    )
  }

  test("SPARK-33497: Eliminate Limit if Join max rows not larger than Limit") {
    Seq(Inner, FullOuter, LeftOuter, RightOuter).foreach { joinType =>
      checkPlanAndMaxRow(
        testRelation.join(testRelation2, joinType).limit(20),
        testRelation.join(testRelation2, joinType),
        20
      )
      checkPlanAndMaxRow(
        testRelation.join(testRelation2, joinType).limit(10),
        testRelation.join(testRelation2, joinType).limit(10),
        10
      )
      // without maxRow
      checkPlanAndMaxRow(
        testRelation.join(testRelation3, joinType).limit(100),
        testRelation.join(testRelation3, joinType).limit(100),
        100
      )
      // maxRow is not valid long
      checkPlanAndMaxRow(
        testRelation.join(testRelation4, joinType).limit(100),
        testRelation.join(testRelation4, joinType).limit(100),
        100
      )
    }

    Seq(LeftSemi, LeftAnti).foreach { joinType =>
      checkPlanAndMaxRow(
        testRelation.join(testRelation2, joinType).limit(5),
        testRelation.join(testRelation2.select(), joinType).limit(5),
        5
      )
      checkPlanAndMaxRow(
        testRelation.join(testRelation2, joinType).limit(10),
        testRelation.join(testRelation2.select(), joinType),
        10
      )
    }
  }

  test("SPARK-33497: Eliminate Limit if Window max rows not larger than Limit") {
    checkPlanAndMaxRow(
      testRelation.window(
        Seq(count(1).as("c")), Seq(Symbol("a")), Seq(Symbol("b").asc)).limit(20),
      testRelation.window(
        Seq(count(1).as("c")), Seq(Symbol("a")), Seq(Symbol("b").asc)),
      10
    )
  }

  private def checkPlanAndMaxRow(
      optimized: LogicalPlan, expected: LogicalPlan, expectedMaxRow: Long): Unit = {
    comparePlans(Optimize.execute(optimized.analyze), expected.analyze)
    assert(expected.maxRows.get == expectedMaxRow)
  }
}

case class RelationWithoutMaxRows(output: Seq[Attribute]) extends LeafNode {
  override def maxRows: Option[Long] = None
}

case class LongMaxRelation(output: Seq[Attribute]) extends LeafNode {
  override def maxRows: Option[Long] = Some(Long.MaxValue)
}
