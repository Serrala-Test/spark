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

package org.apache.spark.sql.catalyst.analysis

import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.dsl.expressions._
import org.apache.spark.sql.catalyst.dsl.plans._
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.Inner
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.types._

class AnalysisSuite extends AnalysisTest {
  import org.apache.spark.sql.catalyst.analysis.TestRelations._

  test("union project *") {
    val plan = (1 to 100)
      .map(_ => testRelation)
      .fold[LogicalPlan](testRelation) { (a, b) =>
        a.select(UnresolvedStar(None)).select('a).union(b.select(UnresolvedStar(None)))
      }

    assertAnalysisSuccess(plan)
  }

  test("check project's resolved") {
    assert(Project(testRelation.output, testRelation).resolved)

    assert(!Project(Seq(UnresolvedAttribute("a")), testRelation).resolved)

    val explode = Explode(AttributeReference("a", IntegerType, nullable = true)())
    assert(!Project(Seq(Alias(explode, "explode")()), testRelation).resolved)

    assert(!Project(Seq(Alias(count(Literal(1)), "count")()), testRelation).resolved)
  }

  test("analyze project") {
    checkAnalysis(
      Project(Seq(UnresolvedAttribute("a")), testRelation),
      Project(testRelation.output, testRelation))

    checkAnalysis(
      Project(Seq(UnresolvedAttribute("TbL.a")),
        UnresolvedRelation(TableIdentifier("TaBlE"), Some("TbL"))),
      Project(testRelation.output, testRelation))

    assertAnalysisError(
      Project(Seq(UnresolvedAttribute("tBl.a")), UnresolvedRelation(
        TableIdentifier("TaBlE"), Some("TbL"))),
      Seq("cannot resolve"))

    checkAnalysis(
      Project(Seq(UnresolvedAttribute("TbL.a")), UnresolvedRelation(
        TableIdentifier("TaBlE"), Some("TbL"))),
      Project(testRelation.output, testRelation),
      caseSensitive = false)

    checkAnalysis(
      Project(Seq(UnresolvedAttribute("tBl.a")), UnresolvedRelation(
        TableIdentifier("TaBlE"), Some("TbL"))),
      Project(testRelation.output, testRelation),
      caseSensitive = false)
  }

  test("resolve sort references - filter/limit") {
    val a = testRelation2.output(0)
    val b = testRelation2.output(1)
    val c = testRelation2.output(2)

    // Case 1: one missing attribute is in the leaf node and another is in the unary node
    val plan1 = testRelation2
      .where('a > "str").select('a, 'b)
      .where('b > "str").select('a)
      .sortBy('b.asc, 'c.desc)
    val expected1 = testRelation2
      .where(a > "str").select(a, b, c)
      .where(b > "str").select(a, b, c)
      .sortBy(b.asc, c.desc)
      .select(a)
    checkAnalysis(plan1, expected1)

    // Case 2: all the missing attributes are in the leaf node
    val plan2 = testRelation2
      .where('a > "str").select('a)
      .where('a > "str").select('a)
      .sortBy('b.asc, 'c.desc)
    val expected2 = testRelation2
      .where(a > "str").select(a, b, c)
      .where(a > "str").select(a, b, c)
      .sortBy(b.asc, c.desc)
      .select(a)
    checkAnalysis(plan2, expected2)
  }

  test("resolve sort references - join") {
    val a = testRelation2.output(0)
    val b = testRelation2.output(1)
    val c = testRelation2.output(2)
    val h = testRelation3.output(3)

    // Case: join itself can resolve all the missing attributes
    val plan = testRelation2.join(testRelation3)
      .where('a > "str").select('a, 'b)
      .sortBy('c.desc, 'h.asc)
    val expected = testRelation2.join(testRelation3)
      .where(a > "str").select(a, b, c, h)
      .sortBy(c.desc, h.asc)
      .select(a, b)
    checkAnalysis(plan, expected)
  }

  test("resolve sort references - aggregate") {
    val a = testRelation2.output(0)
    val b = testRelation2.output(1)
    val c = testRelation2.output(2)
    val alias_a3 = count(a).as("a3")
    val alias_b = b.as("aggOrder")

    // Case 1: when the child of Sort is not Aggregate,
    //   the sort reference is handled by the rule ResolveSortReferences
    val plan1 = testRelation2
      .groupBy('a, 'c, 'b)('a, 'c, count('a).as("a3"))
      .select('a, 'c, 'a3)
      .orderBy('b.asc)

    val expected1 = testRelation2
      .groupBy(a, c, b)(a, c, alias_a3, b)
      .select(a, c, alias_a3.toAttribute, b)
      .orderBy(b.asc)
      .select(a, c, alias_a3.toAttribute)

    checkAnalysis(plan1, expected1)

    // Case 2: when the child of Sort is Aggregate,
    //   the sort reference is handled by the rule ResolveAggregateFunctions
    val plan2 = testRelation2
      .groupBy('a, 'c, 'b)('a, 'c, count('a).as("a3"))
      .orderBy('b.asc)

    val expected2 = testRelation2
      .groupBy(a, c, b)(a, c, alias_a3, alias_b)
      .orderBy(alias_b.toAttribute.asc)
      .select(a, c, alias_a3.toAttribute)

    checkAnalysis(plan2, expected2)
  }

  test("resolve relations") {
    assertAnalysisError(UnresolvedRelation(TableIdentifier("tAbLe"), None), Seq())
    checkAnalysis(UnresolvedRelation(TableIdentifier("TaBlE"), None), testRelation)
    checkAnalysis(
      UnresolvedRelation(TableIdentifier("tAbLe"), None), testRelation, caseSensitive = false)
    checkAnalysis(
      UnresolvedRelation(TableIdentifier("TaBlE"), None), testRelation, caseSensitive = false)
  }

  test("divide should be casted into fractional types") {
    val plan = caseInsensitiveAnalyzer.execute(
      testRelation2.select(
        'a / Literal(2) as 'div1,
        'a / 'b as 'div2,
        'a / 'c as 'div3,
        'a / 'd as 'div4,
        'e / 'e as 'div5))
    val pl = plan.asInstanceOf[Project].projectList

    assert(pl(0).dataType == DoubleType)
    assert(pl(1).dataType == DoubleType)
    assert(pl(2).dataType == DoubleType)
    // StringType will be promoted into Decimal(38, 18)
    assert(pl(3).dataType == DecimalType(38, 22))
    assert(pl(4).dataType == DoubleType)
  }

  test("pull out nondeterministic expressions from RepartitionByExpression") {
    val plan = RepartitionByExpression(Seq(Rand(33)), testRelation)
    val projected = Alias(Rand(33), "_nondeterministic")()
    val expected =
      Project(testRelation.output,
        RepartitionByExpression(Seq(projected.toAttribute),
          Project(testRelation.output :+ projected, testRelation)))
    checkAnalysis(plan, expected)
  }

  test("pull out nondeterministic expressions from Sort") {
    val plan = Sort(Seq(SortOrder(Rand(33), Ascending)), false, testRelation)
    val projected = Alias(Rand(33), "_nondeterministic")()
    val expected =
      Project(testRelation.output,
        Sort(Seq(SortOrder(projected.toAttribute, Ascending)), false,
          Project(testRelation.output :+ projected, testRelation)))
    checkAnalysis(plan, expected)
  }

  test("SPARK-9634: cleanup unnecessary Aliases in LogicalPlan") {
    val a = testRelation.output.head
    var plan = testRelation.select(((a + 1).as("a+1") + 2).as("col"))
    var expected = testRelation.select((a + 1 + 2).as("col"))
    checkAnalysis(plan, expected)

    plan = testRelation.groupBy(a.as("a1").as("a2"))((min(a).as("min_a") + 1).as("col"))
    expected = testRelation.groupBy(a)((min(a) + 1).as("col"))
    checkAnalysis(plan, expected)

    // CreateStruct is a special case that we should not trim Alias for it.
    plan = testRelation.select(CreateStruct(Seq(a, (a + 1).as("a+1"))).as("col"))
    checkAnalysis(plan, plan)
    plan = testRelation.select(CreateStructUnsafe(Seq(a, (a + 1).as("a+1"))).as("col"))
    checkAnalysis(plan, plan)
  }

  test("SPARK-10534: resolve attribute references in order by clause") {
    val a = testRelation2.output(0)
    val c = testRelation2.output(2)

    val plan = testRelation2.select('c).orderBy(Floor('a).asc)
    val expected = testRelation2.select(c, a).orderBy(Floor(a.cast(DoubleType)).asc).select(c)

    checkAnalysis(plan, expected)
  }

  test("self intersect should resolve duplicate expression IDs") {
    val plan = testRelation.intersect(testRelation)
    assertAnalysisSuccess(plan)
  }

  test("SPARK-8654: invalid CAST in NULL IN(...) expression") {
    val plan = Project(Alias(In(Literal(null), Seq(Literal(1), Literal(2))), "a")() :: Nil,
      LocalRelation()
    )
    assertAnalysisSuccess(plan)
  }

  test("SPARK-8654: different types in inlist but can be converted to a common type") {
    val plan = Project(Alias(In(Literal(null), Seq(Literal(1), Literal(1.2345))), "a")() :: Nil,
      LocalRelation()
    )
    assertAnalysisSuccess(plan)
  }

  test("SPARK-8654: check type compatibility error") {
    val plan = Project(Alias(In(Literal(null), Seq(Literal(true), Literal(1))), "a")() :: Nil,
      LocalRelation()
    )
    assertAnalysisError(plan, Seq("data type mismatch: Arguments must be same type"))
  }

  test("SPARK-11725: correctly handle null inputs for ScalaUDF") {
    val string = testRelation2.output(0)
    val double = testRelation2.output(2)
    val short = testRelation2.output(4)
    val nullResult = Literal.create(null, StringType)

    def checkUDF(udf: Expression, transformed: Expression): Unit = {
      checkAnalysis(
        Project(Alias(udf, "")() :: Nil, testRelation2),
        Project(Alias(transformed, "")() :: Nil, testRelation2)
      )
    }

    // non-primitive parameters do not need special null handling
    val udf1 = ScalaUDF((s: String) => "x", StringType, string :: Nil)
    val expected1 = udf1
    checkUDF(udf1, expected1)

    // only primitive parameter needs special null handling
    val udf2 = ScalaUDF((s: String, d: Double) => "x", StringType, string :: double :: Nil)
    val expected2 = If(IsNull(double), nullResult, udf2)
    checkUDF(udf2, expected2)

    // special null handling should apply to all primitive parameters
    val udf3 = ScalaUDF((s: Short, d: Double) => "x", StringType, short :: double :: Nil)
    val expected3 = If(
      IsNull(short) || IsNull(double),
      nullResult,
      udf3)
    checkUDF(udf3, expected3)

    // we can skip special null handling for primitive parameters that are not nullable
    // TODO: this is disabled for now as we can not completely trust `nullable`.
    val udf4 = ScalaUDF(
      (s: Short, d: Double) => "x",
      StringType,
      short :: double.withNullability(false) :: Nil)
    val expected4 = If(
      IsNull(short),
      nullResult,
      udf4)
    // checkUDF(udf4, expected4)
  }

  test("SPARK-11863 mixture of aliases and real columns in order by clause - tpcds 19,55,71") {
    val a = testRelation2.output(0)
    val c = testRelation2.output(2)
    val alias1 = a.as("a1")
    val alias2 = c.as("a2")
    val alias3 = count(a).as("a3")

    val plan = testRelation2
      .groupBy('a, 'c)('a.as("a1"), 'c.as("a2"), count('a).as("a3"))
      .orderBy('a1.asc, 'c.asc)

    val expected = testRelation2
      .groupBy(a, c)(alias1, alias2, alias3)
      .orderBy(alias1.toAttribute.asc, alias2.toAttribute.asc)
      .select(alias1.toAttribute, alias2.toAttribute, alias3.toAttribute)
    checkAnalysis(plan, expected)
  }

  test("Eliminate the unnecessary union") {
    val plan = Union(testRelation :: Nil)
    val expected = testRelation
    checkAnalysis(plan, expected)
  }

  test("SPARK-12102: Ignore nullablity when comparing two sides of case") {
    val relation = LocalRelation('a.struct('x.int), 'b.struct('x.int.withNullability(false)))
    val plan = relation.select(CaseWhen(Seq((Literal(true), 'a.attr)), 'b).as("val"))
    assertAnalysisSuccess(plan)
  }

  test("Keep attribute qualifiers after dedup") {
    val input = LocalRelation('key.int, 'value.string)

    val query =
      Project(Seq($"x.key", $"y.key"),
        Join(
          Project(Seq($"x.key"), SubqueryAlias("x", input)),
          Project(Seq($"y.key"), SubqueryAlias("y", input)),
          Inner, None))

    assertAnalysisSuccess(query)
  }
}
