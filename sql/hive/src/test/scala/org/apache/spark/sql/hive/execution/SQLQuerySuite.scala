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

package org.apache.spark.sql.hive.execution

import org.apache.spark.sql.QueryTest

import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.errors.TreeNodeException
import org.apache.spark.sql.catalyst.plans.logical.{Project, LogicalPlan}
import org.apache.spark.sql.hive.HiveQl
import org.apache.spark.sql.hive.test.TestHive._

case class Nested1(f1: Nested2)
case class Nested2(f2: Nested3)
case class Nested3(f3: Int)

case class A1(x: Int)
case class A2(a: A3, k:Int)
case class A3(x: String)
case class A4(x: String)

/**
 * A collection of hive query tests where we generate the answers ourselves instead of depending on
 * Hive to generate them (in contrast to HiveQuerySuite).  Often this is because the query is
 * valid, but Hive currently cannot execute it.
 */
class SQLQuerySuite extends QueryTest {
  test("CTAS with serde") {
    sql("CREATE TABLE ctas1 AS SELECT key k, value FROM src ORDER BY k, value").collect
    sql(
      """CREATE TABLE ctas2
        | ROW FORMAT SERDE "org.apache.hadoop.hive.serde2.columnar.ColumnarSerDe"
        | WITH SERDEPROPERTIES("serde_p1"="p1","serde_p2"="p2")
        | STORED AS RCFile
        | TBLPROPERTIES("tbl_p1"="p11", "tbl_p2"="p22")
        | AS
        |   SELECT key, value
        |   FROM src
        |   ORDER BY key, value""".stripMargin).collect
    sql(
      """CREATE TABLE ctas3
        | ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' LINES TERMINATED BY '\012'
        | STORED AS textfile AS
        |   SELECT key, value
        |   FROM src
        |   ORDER BY key, value""".stripMargin).collect

    // the table schema may like (key: integer, value: string)
    sql(
      """CREATE TABLE IF NOT EXISTS ctas4 AS
        | SELECT 1 AS key, value FROM src LIMIT 1""".stripMargin).collect
    // expect the string => integer for field key cause the table ctas4 already existed.
    sql(
      """CREATE TABLE IF NOT EXISTS ctas4 AS
        | SELECT key, value FROM src ORDER BY key, value""".stripMargin).collect

    checkAnswer(
      sql("SELECT k, value FROM ctas1 ORDER BY k, value"),
      sql("SELECT key, value FROM src ORDER BY key, value").collect().toSeq)
    checkAnswer(
      sql("SELECT key, value FROM ctas2 ORDER BY key, value"),
      sql(
        """
          SELECT key, value
          FROM src
          ORDER BY key, value""").collect().toSeq)
    checkAnswer(
      sql("SELECT key, value FROM ctas3 ORDER BY key, value"),
      sql(
        """
          SELECT key, value
          FROM src
          ORDER BY key, value""").collect().toSeq)
    checkAnswer(
      sql("SELECT key, value FROM ctas4 ORDER BY key, value"),
      sql("SELECT CAST(key AS int) k, value FROM src ORDER BY k, value").collect().toSeq)

    checkExistence(sql("DESC EXTENDED ctas2"), true,
      "name:key", "type:string", "name:value", "ctas2",
      "org.apache.hadoop.hive.ql.io.RCFileInputFormat",
      "org.apache.hadoop.hive.ql.io.RCFileOutputFormat",
      "org.apache.hadoop.hive.serde2.columnar.ColumnarSerDe",
      "serde_p1=p1", "serde_p2=p2", "tbl_p1=p11", "tbl_p2=p22","MANAGED_TABLE"
    )
  }

  test("ordering not in select") {
    checkAnswer(
      sql("SELECT key FROM src ORDER BY value"),
      sql("SELECT key FROM (SELECT key, value FROM src ORDER BY value) a").collect().toSeq)
  }

  test("ordering not in agg") {
    checkAnswer(
      sql("SELECT key FROM src GROUP BY key, value ORDER BY value"),
      sql("""
        SELECT key
        FROM (
          SELECT key, value
          FROM src
          GROUP BY key, value
          ORDER BY value) a""").collect().toSeq)
  }

  test("double nested data") {
    sparkContext.parallelize(Nested1(Nested2(Nested3(1))) :: Nil).registerTempTable("nested")
    checkAnswer(
      sql("SELECT f1.f2.f3 FROM nested"),
      1)
  }

  test("test ambiguousReferences resolved as hive") {
    sparkContext.parallelize(A1(1) :: Nil).registerTempTable("t1")
    sparkContext.parallelize(A2(A3("test"), 1) :: Nil).registerTempTable("t2")
    checkAnswer(
      sql("SELECT a.x FROM t1 a JOIN t2 b ON a.x = b.k"),
      1)
  }

  test("test ambiguousReferences exception thrown") {
    sparkContext.parallelize(A3("a") :: Nil).registerTempTable("t3")
    sparkContext.parallelize(A4("b") :: Nil).registerTempTable("t4")
    intercept[TreeNodeException[Project]] {
      checkAnswer(
        sql("SELECT x FROM t3 a JOIN t4 b"),
        "a")
    }
  }

  test("test particular table alias") {
    checkAnswer(
      sql("SELECT key.value, COUNT(1) FROM src key JOIN src b GROUP BY key.value"),
      sql("SELECT a.value, COUNT(1) FROM src a JOIN src b GROUP BY a.value").collect().toSeq)
  }

  test("test CTAS") {
    checkAnswer(sql("CREATE TABLE test_ctas_123 AS SELECT key, value FROM src"), Seq.empty[Row])
    checkAnswer(
      sql("SELECT key, value FROM test_ctas_123 ORDER BY key"), 
      sql("SELECT key, value FROM src ORDER BY key").collect().toSeq)
  }

  test("SPARK-3708 Backticks aren't handled correctly is aliases") {
    checkAnswer(
      sql("SELECT k FROM (SELECT `key` AS `k` FROM src) a"),
      sql("SELECT `key` FROM src").collect().toSeq)
  }

  test("SPARK-3834 Backticks not correctly handled in subquery aliases") {
    checkAnswer(
      sql("SELECT a.key FROM (SELECT key FROM src) `a`"),
      sql("SELECT `key` FROM src").collect().toSeq)
  }

  test("SPARK-3814 Support Bitwise & operator") {
    checkAnswer(
      sql("SELECT case when 1&1=1 then 1 else 0 end FROM src"),
      sql("SELECT 1 FROM src").collect().toSeq)
  }

  test("SPARK-3814 Support Bitwise | operator") {
    checkAnswer(
      sql("SELECT case when 1|0=1 then 1 else 0 end FROM src"),
      sql("SELECT 1 FROM src").collect().toSeq)
  }

  test("SPARK-3814 Support Bitwise ^ operator") {
    checkAnswer(
      sql("SELECT case when 1^0=1 then 1 else 0 end FROM src"),
      sql("SELECT 1 FROM src").collect().toSeq)
  }

  test("SPARK-3814 Support Bitwise ~ operator") {
    checkAnswer(
      sql("SELECT case when ~1=-2 then 1 else 0 end FROM src"),
      sql("SELECT 1 FROM src").collect().toSeq)
  }
}
