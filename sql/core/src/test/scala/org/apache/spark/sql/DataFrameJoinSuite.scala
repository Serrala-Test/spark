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

import org.apache.spark.sql.execution.joins.BroadcastHashJoin
import org.apache.spark.sql.functions._
import org.apache.spark.sql.test.SharedSQLContext

class DataFrameJoinSuite extends QueryTest with SharedSQLContext {
  import testImplicits._

  test("join - join using") {
    val df = Seq(1, 2, 3).map(i => (i, i.toString)).toDF("int", "str")
    val df2 = Seq(1, 2, 3).map(i => (i, (i + 1).toString)).toDF("int", "str")

    checkAnswer(
      df.join(df2, "int"),
      Row(1, "1", "2") :: Row(2, "2", "3") :: Row(3, "3", "4") :: Nil)
  }

  test("join - join using multiple columns") {
    val df = Seq(1, 2, 3).map(i => (i, i + 1, i.toString)).toDF("int", "int2", "str")
    val df2 = Seq(1, 2, 3).map(i => (i, i + 1, (i + 1).toString)).toDF("int", "int2", "str")

    checkAnswer(
      df.join(df2, Seq("int", "int2")),
      Row(1, 2, "1", "2") :: Row(2, 3, "2", "3") :: Row(3, 4, "3", "4") :: Nil)
  }

  test("join - join using multiple columns and specifying join type") {
    val df = Seq(1, 2, 3).map(i => (i, i + 1, i.toString)).toDF("int", "int2", "str")
    val df2 = Seq(1, 2, 3).map(i => (i, i + 1, (i + 1).toString)).toDF("int", "int2", "str")

    checkAnswer(
      df.join(df2, Seq("int", "str"), "left"),
      Row(1, 2, "1", null) :: Row(2, 3, "2", null) :: Row(3, 4, "3", null) :: Nil)

    checkAnswer(
      df.join(df2, Seq("int", "str"), "right"),
      Row(null, null, null, 2) :: Row(null, null, null, 3) :: Row(null, null, null, 4) :: Nil)
  }

  test("join - join using self join") {
    val df = Seq(1, 2, 3).map(i => (i, i.toString)).toDF("int", "str")

    // self join
    checkAnswer(
      df.join(df, "int"),
      Row(1, "1", "1") :: Row(2, "2", "2") :: Row(3, "3", "3") :: Nil)
  }

  test("join - self join") {
    val df1 = testData.select(testData("key")).as('df1)
    val df2 = testData.select(testData("key")).as('df2)

    checkAnswer(
      df1.join(df2, $"df1.key" === $"df2.key"),
      sql("SELECT a.key, b.key FROM testData a JOIN testData b ON a.key = b.key")
        .collect().toSeq)
  }

  test("join - using aliases after self join") {
    val df = Seq(1, 2, 3).map(i => (i, i.toString)).toDF("int", "str")
    checkAnswer(
      df.as('x).join(df.as('y), $"x.str" === $"y.str").groupBy("x.str").count(),
      Row("1", 1) :: Row("2", 1) :: Row("3", 1) :: Nil)

    checkAnswer(
      df.as('x).join(df.as('y), $"x.str" === $"y.str").groupBy("y.str").count(),
      Row("1", 1) :: Row("2", 1) :: Row("3", 1) :: Nil)
  }

  test("[SPARK-6231] join - self join auto resolve ambiguity") {
    val df = Seq((1, "1"), (2, "2")).toDF("key", "value")
    checkAnswer(
      df.join(df, df("key") === df("key")),
      Row(1, "1", 1, "1") :: Row(2, "2", 2, "2") :: Nil)

    checkAnswer(
      df.join(df.filter($"value" === "2"), df("key") === df("key")),
      Row(2, "2", 2, "2") :: Nil)

    checkAnswer(
      df.join(df, df("key") === df("key") && df("value") === 1),
      Row(1, "1", 1, "1") :: Nil)

    val left = df.groupBy("key").agg(count("*"))
    val right = df.groupBy("key").agg(sum("key"))
    checkAnswer(
      left.join(right, left("key") === right("key")),
      Row(1, 1, 1, 1) :: Row(2, 1, 2, 2) :: Nil)
  }

  test("[SPARK-10838] self join - conflicting attributes in condition - incorrect result 1") {
    val df1 = Seq((1, 3), (2, 1)).toDF("keyCol1", "keyCol2")
    val df2 = Seq((1, 4), (2, 1)).toDF("keyCol1", "keyCol3")

    val df3 = df1.join(df2, df1("keyCol1") === df2("keyCol1")).select(df1("keyCol1"))

    checkAnswer(
      df3.join(df1, df1("keyCol2") === df3("keyCol1")),
      Row(1, 2, 1) :: Nil)
  }

  test("[SPARK-10838] self join - conflicting attributes in condition - incorrect result 2") {
    val df1 = Seq((1, 3), (2, 1)).toDF("keyCol1", "keyCol2")
    val df2 = Seq((1, 4), (2, 1)).toDF("keyCol1", "keyCol3")

    val df3 = df1.join(df2, df1("keyCol1") === df2("keyCol1")).select(df1("keyCol1"), $"keyCol3")

    checkAnswer(
      df3.join(df1, df3("keyCol3") === df1("keyCol1") && df1("keyCol1") === df3("keyCol3")),
      Row(2, 1, 1, 3) :: Nil)
  }

  test("[SPARK-10838] self join - conflicting attributes in condition - exception") {
    val df1 = Seq((1, 3), (2, 1)).toDF("keyCol1", "keyCol2")
    val df2 = Seq((1, 4), (2, 1)).toDF("keyCol1", "keyCol3")

    val df3 = df1.join(df2, df1("keyCol1") === df2("keyCol1")).select(df1("keyCol1"), $"keyCol3")
    val df4 = df2.as("df4")

    checkAnswer(
      df3.join(df4, df3("keyCol3") === df4("keyCol1") && df3("keyCol3") === df4("keyCol1")),
      Row(2, 1, 1, 4) :: Nil)
  }

  test("broadcast join hint") {
    val df1 = Seq((1, "1"), (2, "2")).toDF("key", "value")
    val df2 = Seq((1, "1"), (2, "2")).toDF("key", "value")

    // equijoin - should be converted into broadcast join
    val plan1 = df1.join(broadcast(df2), "key").queryExecution.executedPlan
    assert(plan1.collect { case p: BroadcastHashJoin => p }.size === 1)

    // no join key -- should not be a broadcast join
    val plan2 = df1.join(broadcast(df2)).queryExecution.executedPlan
    assert(plan2.collect { case p: BroadcastHashJoin => p }.size === 0)

    // planner should not crash without a join
    broadcast(df1).queryExecution.executedPlan
  }
}
