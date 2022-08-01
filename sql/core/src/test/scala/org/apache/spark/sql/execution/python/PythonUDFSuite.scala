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

package org.apache.spark.sql.execution.python

import org.apache.spark.sql.{IntegratedUDFTestUtils, QueryTest, RandomDataGenerator, Row}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.encoders.RowEncoder
import org.apache.spark.sql.functions.count
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.{DoubleType, IntegerType, StructField, StructType}

class PythonUDFSuite extends QueryTest with SharedSparkSession {
  import testImplicits._

  import IntegratedUDFTestUtils._

  val scalaTestUDF = TestScalaUDF(name = "scalaUDF")
  val pythonTestUDF = TestPythonUDF(name = "pyUDF")

  lazy val base = Seq(
    (Some(1), Some(1)), (Some(1), Some(2)), (Some(2), Some(1)),
    (Some(2), Some(2)), (Some(3), Some(1)), (Some(3), Some(2)),
    (None, Some(1)), (Some(3), None), (None, None)).toDF("a", "b")

  test("SPARK-28445: PythonUDF as grouping key and aggregate expressions") {
    assume(shouldTestPythonUDFs)
    val df1 = base.groupBy(scalaTestUDF(base("a") + 1))
      .agg(scalaTestUDF(base("a") + 1), scalaTestUDF(count(base("b"))))
    val df2 = base.groupBy(pythonTestUDF(base("a") + 1))
      .agg(pythonTestUDF(base("a") + 1), pythonTestUDF(count(base("b"))))
    checkAnswer(df1, df2)
  }

  test("SPARK-28445: PythonUDF as grouping key and used in aggregate expressions") {
    assume(shouldTestPythonUDFs)
    val df1 = base.groupBy(scalaTestUDF(base("a") + 1))
      .agg(scalaTestUDF(base("a") + 1) + 1, scalaTestUDF(count(base("b"))))
    val df2 = base.groupBy(pythonTestUDF(base("a") + 1))
      .agg(pythonTestUDF(base("a") + 1) + 1, pythonTestUDF(count(base("b"))))
    checkAnswer(df1, df2)
  }

  test("SPARK-28445: PythonUDF in aggregate expression has grouping key in its arguments") {
    assume(shouldTestPythonUDFs)
    val df1 = base.groupBy(scalaTestUDF(base("a") + 1))
      .agg(scalaTestUDF(scalaTestUDF(base("a") + 1)), scalaTestUDF(count(base("b"))))
    val df2 = base.groupBy(pythonTestUDF(base("a") + 1))
      .agg(pythonTestUDF(pythonTestUDF(base("a") + 1)), pythonTestUDF(count(base("b"))))
    checkAnswer(df1, df2)
  }

  test("SPARK-28445: PythonUDF over grouping key is argument to aggregate function") {
    assume(shouldTestPythonUDFs)
    val df1 = base.groupBy(scalaTestUDF(base("a") + 1))
      .agg(scalaTestUDF(scalaTestUDF(base("a") + 1)),
        scalaTestUDF(count(scalaTestUDF(base("a") + 1))))
    val df2 = base.groupBy(pythonTestUDF(base("a") + 1))
      .agg(pythonTestUDF(pythonTestUDF(base("a") + 1)),
        pythonTestUDF(count(pythonTestUDF(base("a") + 1))))
    checkAnswer(df1, df2)
  }

  test("SPARK-39962: Global aggregation of Pandas UDF should respect the column order") {
    assume(shouldTestPandasUDFs)
    val df = Seq[(java.lang.Integer, java.lang.Integer)]((1, null)).toDF("a", "b")

    val pandasTestUDF = TestGroupedAggPandasUDF(name = "pandas_udf")
    val reorderedDf = df.select("b", "a")
    val actual = reorderedDf.agg(
      pandasTestUDF(reorderedDf("a")), pandasTestUDF(reorderedDf("b")))
    val expected = df.agg(pandasTestUDF(df("a")), pandasTestUDF(df("b")))

    checkAnswer(actual, expected)
  }

  test("SPARK-34265: Instrument Python UDF execution using SQL Metrics") {
    assume(shouldTestPythonUDFs)
    val pythonSQLMetrics = List(
      "data sent to Python workers",
      "data returned from Python workers",
      "number of output rows")

    val df = base.groupBy(pythonTestUDF(base("a") + 1))
      .agg(pythonTestUDF(pythonTestUDF(base("a") + 1)))
    df.count()

    val statusStore = spark.sharedState.statusStore
    val lastExecId = statusStore.executionsList.last.executionId
    val executionMetrics = statusStore.execution(lastExecId).get.metrics.mkString
    for (metric <- pythonSQLMetrics) {
      assert(executionMetrics.contains(metric))
    }

  test("SPARK-39931: groupBatchAndProject") {
    assume(shouldTestPythonUDFs)

    def generateRows(schema: StructType, numRows: Int): Array[InternalRow] = {
      val generator = RandomDataGenerator.forType(schema, nullable = false).get
      val toRow = RowEncoder(schema).createSerializer()
      (1 to numRows).map(_ => toRow(generator().asInstanceOf[Row]).copy()).toArray
    }

    val inputSchema = StructType(Seq(
      StructField("id", IntegerType),
      StructField("flt", DoubleType)
    ))
    val inputAttributes = inputSchema.toAttributes

    val input = generateRows(inputSchema, 10).iterator

    val grouping = inputAttributes.slice(0, 1)
    val dedupSchema = inputAttributes

    val actual = PandasGroupUtils.groupBatchAndProject(
      input, grouping, inputAttributes, dedupSchema, 10000)
      .toList.map { it => it.toList }

    assert(actual === Seq())
  }
}
