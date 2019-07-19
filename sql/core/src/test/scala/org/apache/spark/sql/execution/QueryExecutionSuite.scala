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

import scala.io.Source

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.plans.QueryPlan
import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, OneRowRelation}
import org.apache.spark.sql.catalyst.trees.TreeNodeTag
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSQLContext

case class QueryExecutionTestRecord(
    c0: Int, c1: Int, c2: Int, c3: Int, c4: Int,
    c5: Int, c6: Int, c7: Int, c8: Int, c9: Int,
    c10: Int, c11: Int, c12: Int, c13: Int, c14: Int,
    c15: Int, c16: Int, c17: Int, c18: Int, c19: Int,
    c20: Int, c21: Int, c22: Int, c23: Int, c24: Int,
    c25: Int, c26: Int)

class QueryExecutionSuite extends SharedSQLContext {
  import testImplicits._

  def checkDumpedPlans(path: String, expected: Int): Unit = {
    assert(Source.fromFile(path).getLines.toList
      .takeWhile(_ != "== Whole Stage Codegen ==") == List(
      "== Parsed Logical Plan ==",
      s"Range (0, $expected, step=1, splits=Some(2))",
      "",
      "== Analyzed Logical Plan ==",
      "id: bigint",
      s"Range (0, $expected, step=1, splits=Some(2))",
      "",
      "== Optimized Logical Plan ==",
      s"Range (0, $expected, step=1, splits=Some(2))",
      "",
      "== Physical Plan ==",
      s"*(1) Range (0, $expected, step=1, splits=2)",
      ""))
  }
  test("dumping query execution info to a file") {
    withTempDir { dir =>
      val path = dir.getCanonicalPath + "/plans.txt"
      val df = spark.range(0, 10)
      df.queryExecution.debug.toFile(path)

      checkDumpedPlans(path, expected = 10)
    }
  }

  test("dumping query execution info to an existing file") {
    withTempDir { dir =>
      val path = dir.getCanonicalPath + "/plans.txt"
      val df = spark.range(0, 10)
      df.queryExecution.debug.toFile(path)

      val df2 = spark.range(0, 1)
      df2.queryExecution.debug.toFile(path)
      checkDumpedPlans(path, expected = 1)
    }
  }

  test("dumping query execution info to non-existing folder") {
    withTempDir { dir =>
      val path = dir.getCanonicalPath + "/newfolder/plans.txt"
      val df = spark.range(0, 100)
      df.queryExecution.debug.toFile(path)
      checkDumpedPlans(path, expected = 100)
    }
  }

  test("dumping query execution info by invalid path") {
    val path = "1234567890://plans.txt"
    val exception = intercept[IllegalArgumentException] {
      spark.range(0, 100).queryExecution.debug.toFile(path)
    }

    assert(exception.getMessage.contains("Illegal character in scheme name"))
  }

  test("limit number of fields by sql config") {
    def relationPlans: String = {
      val ds = spark.createDataset(Seq(QueryExecutionTestRecord(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26)))
      ds.queryExecution.toString
    }
    withSQLConf(SQLConf.MAX_TO_STRING_FIELDS.key -> "26") {
      assert(relationPlans.contains("more fields"))
    }
    withSQLConf(SQLConf.MAX_TO_STRING_FIELDS.key -> "27") {
      assert(!relationPlans.contains("more fields"))
    }
  }

  test("check maximum fields restriction") {
    withTempDir { dir =>
      val path = dir.getCanonicalPath + "/plans.txt"
      val ds = spark.createDataset(Seq(QueryExecutionTestRecord(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26)))
      ds.queryExecution.debug.toFile(path)
      val localRelations = Source.fromFile(path).getLines().filter(_.contains("LocalRelation"))

      assert(!localRelations.exists(_.contains("more fields")))
    }
  }

  test("toString() exception/error handling") {
    spark.experimental.extraStrategies = Seq[SparkStrategy]((_: LogicalPlan) => Nil)

    def qe: QueryExecution = new QueryExecution(spark, OneRowRelation())

    // Nothing!
    assert(qe.toString.contains("OneRowRelation"))

    // Throw an AnalysisException - this should be captured.
    spark.experimental.extraStrategies = Seq[SparkStrategy](
      (_: LogicalPlan) => throw new AnalysisException("exception"))
    assert(qe.toString.contains("org.apache.spark.sql.AnalysisException"))

    // Throw an Error - this should not be captured.
    spark.experimental.extraStrategies = Seq[SparkStrategy](
      (_: LogicalPlan) => throw new Error("error"))
    val error = intercept[Error](qe.toString)
    assert(error.getMessage.contains("error"))

    spark.experimental.extraStrategies = Nil
  }

  test("SPARK-28346: clone the query plan between analyzer, optimizer and planner") {
    val tag1 = new TreeNodeTag[String]("a")
    val tag2 = new TreeNodeTag[String]("b")
    val tag3 = new TreeNodeTag[String]("c")
    val tag4 = new TreeNodeTag[String]("d")

    def assertNoTag(tag: TreeNodeTag[String], plans: QueryPlan[_]*): Unit = {
      plans.foreach { plan =>
        assert(plan.getTagValue(tag).isEmpty)
      }
    }

    val df = spark.range(10)
    val analyzedPlan = df.queryExecution.analyzed
    val optimizedPlan = df.queryExecution.optimizedPlan
    val physicalPlan = df.queryExecution.sparkPlan
    val finalPlan = df.queryExecution.executedPlan

    analyzedPlan.setTagValue(tag1, "v")
    assertNoTag(tag1, optimizedPlan, physicalPlan, finalPlan)

    optimizedPlan.setTagValue(tag2, "v")
    assertNoTag(tag2, analyzedPlan, physicalPlan, finalPlan)

    physicalPlan.setTagValue(tag3, "v")
    assertNoTag(tag3, analyzedPlan, optimizedPlan, finalPlan)

    finalPlan.setTagValue(tag4, "v")
    assertNoTag(tag4, analyzedPlan, optimizedPlan, physicalPlan)
  }
}
