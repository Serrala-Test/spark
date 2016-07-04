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

import java.io.File
import java.sql.Timestamp
import java.util.{Locale, TimeZone}

import scala.util.Try

import org.apache.hadoop.hive.conf.HiveConf.ConfVars
import org.scalatest.BeforeAndAfter

import org.apache.spark.{SparkException, SparkFiles}
import org.apache.spark.sql.{AnalysisException, DataFrame, Row}
import org.apache.spark.sql.catalyst.expressions.Cast
import org.apache.spark.sql.catalyst.parser.ParseException
import org.apache.spark.sql.catalyst.plans.logical.Project
import org.apache.spark.sql.execution.joins.BroadcastNestedLoopJoinExec
import org.apache.spark.sql.hive._
import org.apache.spark.sql.hive.test.{TestHive, TestHiveContext}
import org.apache.spark.sql.hive.test.TestHive._
import org.apache.spark.sql.internal.SQLConf

case class TestData(a: Int, b: String)

/**
 * A set of test cases expressed in Hive QL that are not covered by the tests
 * included in the hive distribution.
 */
class HiveQuerySuite extends HiveComparisonTest with BeforeAndAfter {
  private val originalTimeZone = TimeZone.getDefault
  private val originalLocale = Locale.getDefault

  import org.apache.spark.sql.hive.test.TestHive.implicits._

  private val originalCrossJoinEnabled = TestHive.conf.crossJoinEnabled

  override def beforeAll() {
    super.beforeAll()
    TestHive.setCacheTables(true)
    // Timezone is fixed to America/Los_Angeles for those timezone sensitive tests (timestamp_*)
    TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
    // Add Locale setting
    Locale.setDefault(Locale.US)
    // Ensures that cross joins are enabled so that we can test them
    TestHive.setConf(SQLConf.CROSS_JOINS_ENABLED, true)
  }

  override def afterAll() {
    try {
      TestHive.setCacheTables(false)
      TimeZone.setDefault(originalTimeZone)
      Locale.setDefault(originalLocale)
      sql("DROP TEMPORARY FUNCTION IF EXISTS udtf_count2")
      TestHive.setConf(SQLConf.CROSS_JOINS_ENABLED, originalCrossJoinEnabled)
    } finally {
      super.afterAll()
    }
  }

  private def assertUnsupportedFeature(body: => Unit): Unit = {
    val e = intercept[ParseException] { body }
    assert(e.getMessage.toLowerCase.contains("operation not allowed"))
  }

  // Testing the Broadcast based join for cartesian join (cross join)
  // We assume that the Broadcast Join Threshold will works since the src is a small table
  private val spark_10484_1 = """
                                | SELECT a.key, b.key
                                | FROM src a LEFT JOIN src b WHERE a.key > b.key + 300
                                | ORDER BY b.key, a.key
                                | LIMIT 20
                              """.stripMargin
  private val spark_10484_2 = """
                                | SELECT a.key, b.key
                                | FROM src a RIGHT JOIN src b WHERE a.key > b.key + 300
                                | ORDER BY a.key, b.key
                                | LIMIT 20
                              """.stripMargin
  private val spark_10484_3 = """
                                | SELECT a.key, b.key
                                | FROM src a FULL OUTER JOIN src b WHERE a.key > b.key + 300
                                | ORDER BY a.key, b.key
                                | LIMIT 20
                              """.stripMargin
  private val spark_10484_4 = """
                                | SELECT a.key, b.key
                                | FROM src a JOIN src b WHERE a.key > b.key + 300
                                | ORDER BY a.key, b.key
                                | LIMIT 20
                              """.stripMargin

  createQueryTest("SPARK-10484 Optimize the Cartesian (Cross) Join with broadcast based JOIN #1",
    spark_10484_1)

  createQueryTest("SPARK-10484 Optimize the Cartesian (Cross) Join with broadcast based JOIN #2",
    spark_10484_2)

  createQueryTest("SPARK-10484 Optimize the Cartesian (Cross) Join with broadcast based JOIN #3",
    spark_10484_3)

  createQueryTest("SPARK-10484 Optimize the Cartesian (Cross) Join with broadcast based JOIN #4",
    spark_10484_4)

  test("SPARK-10484 Optimize the Cartesian (Cross) Join with broadcast based JOIN") {
    def assertBroadcastNestedLoopJoin(sqlText: String): Unit = {
      assert(sql(sqlText).queryExecution.sparkPlan.collect {
        case _: BroadcastNestedLoopJoinExec => 1
      }.nonEmpty)
    }

    assertBroadcastNestedLoopJoin(spark_10484_1)
    assertBroadcastNestedLoopJoin(spark_10484_2)
    assertBroadcastNestedLoopJoin(spark_10484_3)
    assertBroadcastNestedLoopJoin(spark_10484_4)
  }

  createQueryTest("insert table with generator with column name",
    """
      |  CREATE TABLE gen_tmp (key Int);
      |  INSERT OVERWRITE TABLE gen_tmp
      |    SELECT explode(array(1,2,3)) AS val FROM src LIMIT 3;
      |  SELECT key FROM gen_tmp ORDER BY key ASC;
    """.stripMargin)

  createQueryTest("insert table with generator with multiple column names",
    """
      |  CREATE TABLE gen_tmp (key Int, value String);
      |  INSERT OVERWRITE TABLE gen_tmp
      |    SELECT explode(map(key, value)) as (k1, k2) FROM src LIMIT 3;
      |  SELECT key, value FROM gen_tmp ORDER BY key, value ASC;
    """.stripMargin)

  createQueryTest("insert table with generator without column name",
    """
      |  CREATE TABLE gen_tmp (key Int);
      |  INSERT OVERWRITE TABLE gen_tmp
      |    SELECT explode(array(1,2,3)) FROM src LIMIT 3;
      |  SELECT key FROM gen_tmp ORDER BY key ASC;
    """.stripMargin)

  test("multiple generators in projection") {
    intercept[AnalysisException] {
      sql("SELECT explode(array(key, key)), explode(array(key, key)) FROM src").collect()
    }

    intercept[AnalysisException] {
      sql("SELECT explode(array(key, key)) as k1, explode(array(key, key)) FROM src").collect()
    }
  }

  createQueryTest("! operator",
    """
      |SELECT a FROM (
      |  SELECT 1 AS a UNION ALL SELECT 2 AS a) t
      |WHERE !(a>1)
    """.stripMargin)

  createQueryTest("constant object inspector for generic udf",
    """SELECT named_struct(
      lower("AA"), "10",
      repeat(lower("AA"), 3), "11",
      lower(repeat("AA", 3)), "12",
      printf("bb%d", 12), "13",
      repeat(printf("s%d", 14), 2), "14") FROM src LIMIT 1""")

  createQueryTest("NaN to Decimal",
    "SELECT CAST(CAST('NaN' AS DOUBLE) AS DECIMAL(1,1)) FROM src LIMIT 1")

  createQueryTest("constant null testing",
    """SELECT
      |IF(FALSE, CAST(NULL AS STRING), CAST(1 AS STRING)) AS COL1,
      |IF(TRUE, CAST(NULL AS STRING), CAST(1 AS STRING)) AS COL2,
      |IF(FALSE, CAST(NULL AS INT), CAST(1 AS INT)) AS COL3,
      |IF(TRUE, CAST(NULL AS INT), CAST(1 AS INT)) AS COL4,
      |IF(FALSE, CAST(NULL AS DOUBLE), CAST(1 AS DOUBLE)) AS COL5,
      |IF(TRUE, CAST(NULL AS DOUBLE), CAST(1 AS DOUBLE)) AS COL6,
      |IF(FALSE, CAST(NULL AS BOOLEAN), CAST(1 AS BOOLEAN)) AS COL7,
      |IF(TRUE, CAST(NULL AS BOOLEAN), CAST(1 AS BOOLEAN)) AS COL8,
      |IF(FALSE, CAST(NULL AS BIGINT), CAST(1 AS BIGINT)) AS COL9,
      |IF(TRUE, CAST(NULL AS BIGINT), CAST(1 AS BIGINT)) AS COL10,
      |IF(FALSE, CAST(NULL AS FLOAT), CAST(1 AS FLOAT)) AS COL11,
      |IF(TRUE, CAST(NULL AS FLOAT), CAST(1 AS FLOAT)) AS COL12,
      |IF(FALSE, CAST(NULL AS SMALLINT), CAST(1 AS SMALLINT)) AS COL13,
      |IF(TRUE, CAST(NULL AS SMALLINT), CAST(1 AS SMALLINT)) AS COL14,
      |IF(FALSE, CAST(NULL AS TINYINT), CAST(1 AS TINYINT)) AS COL15,
      |IF(TRUE, CAST(NULL AS TINYINT), CAST(1 AS TINYINT)) AS COL16,
      |IF(FALSE, CAST(NULL AS BINARY), CAST("1" AS BINARY)) AS COL17,
      |IF(TRUE, CAST(NULL AS BINARY), CAST("1" AS BINARY)) AS COL18,
      |IF(FALSE, CAST(NULL AS DATE), CAST("1970-01-01" AS DATE)) AS COL19,
      |IF(TRUE, CAST(NULL AS DATE), CAST("1970-01-01" AS DATE)) AS COL20,
      |IF(TRUE, CAST(NULL AS TIMESTAMP), CAST(1 AS TIMESTAMP)) AS COL21,
      |IF(FALSE, CAST(NULL AS DECIMAL), CAST(1 AS DECIMAL)) AS COL22,
      |IF(TRUE, CAST(NULL AS DECIMAL), CAST(1 AS DECIMAL)) AS COL23
      |FROM src LIMIT 1""".stripMargin)

  test("constant null testing timestamp") {
    val r1 = sql("SELECT IF(FALSE, CAST(NULL AS TIMESTAMP), CAST(1 AS TIMESTAMP)) AS COL20")
      .collect().head
    assert(new Timestamp(1000) == r1.getTimestamp(0))
  }

  createQueryTest("constant array",
  """
    |SELECT sort_array(
    |  sort_array(
    |    array("hadoop distributed file system",
    |          "enterprise databases", "hadoop map-reduce")))
    |FROM src LIMIT 1;
  """.stripMargin)

  createQueryTest("null case",
    "SELECT case when(true) then 1 else null end FROM src LIMIT 1")

  createQueryTest("single case",
    """SELECT case when true then 1 else 2 end FROM src LIMIT 1""")

  createQueryTest("double case",
    """SELECT case when 1 = 2 then 1 when 2 = 2 then 3 else 2 end FROM src LIMIT 1""")

  createQueryTest("case else null",
    """SELECT case when 1 = 2 then 1 when 2 = 2 then 3 else null end FROM src LIMIT 1""")

  createQueryTest("having no references",
    "SELECT key FROM src GROUP BY key HAVING COUNT(*) > 1")

  createQueryTest("no from clause",
    "SELECT 1, +1, -1")

  createQueryTest("boolean = number",
    """
      |SELECT
      |  1 = true, 1L = true, 1Y = true, true = 1, true = 1L, true = 1Y,
      |  0 = true, 0L = true, 0Y = true, true = 0, true = 0L, true = 0Y,
      |  1 = false, 1L = false, 1Y = false, false = 1, false = 1L, false = 1Y,
      |  0 = false, 0L = false, 0Y = false, false = 0, false = 0L, false = 0Y,
      |  2 = true, 2L = true, 2Y = true, true = 2, true = 2L, true = 2Y,
      |  2 = false, 2L = false, 2Y = false, false = 2, false = 2L, false = 2Y
      |FROM src LIMIT 1
    """.stripMargin)

  test("CREATE TABLE AS runs once") {
    sql("CREATE TABLE foo AS SELECT 1 FROM src LIMIT 1").collect()
    assert(sql("SELECT COUNT(*) FROM foo").collect().head.getLong(0) === 1,
      "Incorrect number of rows in created table")
  }

  createQueryTest("between",
    "SELECT * FROM src WHERE key Between 1 and 2")

  createQueryTest("div",
    "SELECT 1 DIV 2, 1 div 2, 1 dIv 2, 100 DIV 51, 100 DIV 49 FROM src LIMIT 1")

  // Jdk version leads to different query output for double, so not use createQueryTest here
  test("division") {
    val res = sql("SELECT 2 / 1, 1 / 2, 1 / 3, 1 / COUNT(*) FROM src LIMIT 1").collect().head
    Seq(2.0, 0.5, 0.3333333333333333, 0.002).zip(res.toSeq).foreach( x =>
      assert(x._1 == x._2.asInstanceOf[Double]))
  }

  createQueryTest("modulus",
    "SELECT 11 % 10, IF((101.1 % 100.0) BETWEEN 1.01 AND 1.11, \"true\", \"false\"), " +
      "(101 / 2) % 10 FROM src LIMIT 1")

  test("Query expressed in HiveQL") {
    sql("FROM src SELECT key").collect()
  }

  test("Query with constant folding the CAST") {
    sql("SELECT CAST(CAST('123' AS binary) AS binary) FROM src LIMIT 1").collect()
  }

  createQueryTest("Constant Folding Optimization for AVG_SUM_COUNT",
    "SELECT AVG(0), SUM(0), COUNT(null), COUNT(value) FROM src GROUP BY key")

  createQueryTest("Cast Timestamp to Timestamp in UDF",
    """
      | SELECT DATEDIFF(CAST(value AS timestamp), CAST('2002-03-21 00:00:00' AS timestamp))
      | FROM src LIMIT 1
    """.stripMargin)

  createQueryTest("Date comparison test 1",
    """
      | SELECT
      | CAST(CAST('1970-01-01 22:00:00' AS timestamp) AS date) ==
      | CAST(CAST('1970-01-01 23:00:00' AS timestamp) AS date)
      | FROM src LIMIT 1
    """.stripMargin)

  createQueryTest("Simple Average",
    "SELECT AVG(key) FROM src")

  createQueryTest("Simple Average + 1",
    "SELECT AVG(key) + 1.0 FROM src")

  createQueryTest("Simple Average + 1 with group",
    "SELECT AVG(key) + 1.0, value FROM src group by value")

  createQueryTest("string literal",
    "SELECT 'test' FROM src")

  createQueryTest("Escape sequences",
    """SELECT key, '\\\t\\' FROM src WHERE key = 86""")

  createQueryTest("IgnoreExplain",
    """EXPLAIN SELECT key FROM src""")

  createQueryTest("trivial join where clause",
    "SELECT * FROM src a JOIN src b WHERE a.key = b.key")

  createQueryTest("trivial join ON clause",
    "SELECT * FROM src a JOIN src b ON a.key = b.key")

  createQueryTest("small.cartesian",
    "SELECT a.key, b.key FROM (SELECT key FROM src WHERE key < 1) a JOIN " +
      "(SELECT key FROM src WHERE key = 2) b")

  createQueryTest("length.udf",
    "SELECT length(\"test\") FROM src LIMIT 1")

  createQueryTest("partitioned table scan",
    "SELECT ds, hr, key, value FROM srcpart")

  createQueryTest("create table as",
    """
      |CREATE TABLE createdtable AS SELECT * FROM src;
      |SELECT * FROM createdtable
    """.stripMargin)

  createQueryTest("create table as with db name",
    """
      |CREATE DATABASE IF NOT EXISTS testdb;
      |CREATE TABLE testdb.createdtable AS SELECT * FROM default.src;
      |SELECT * FROM testdb.createdtable;
      |DROP DATABASE IF EXISTS testdb CASCADE
    """.stripMargin)

  createQueryTest("create table as with db name within backticks",
    """
      |CREATE DATABASE IF NOT EXISTS testdb;
      |CREATE TABLE `testdb`.`createdtable` AS SELECT * FROM default.src;
      |SELECT * FROM testdb.createdtable;
      |DROP DATABASE IF EXISTS testdb CASCADE
    """.stripMargin)

  createQueryTest("insert table with db name",
    """
      |CREATE DATABASE IF NOT EXISTS testdb;
      |CREATE TABLE testdb.createdtable like default.src;
      |INSERT INTO TABLE testdb.createdtable SELECT * FROM default.src;
      |SELECT * FROM testdb.createdtable;
      |DROP DATABASE IF EXISTS testdb CASCADE
    """.stripMargin)

  createQueryTest("insert into and insert overwrite",
    """
      |CREATE TABLE createdtable like src;
      |INSERT INTO TABLE createdtable SELECT * FROM src;
      |INSERT INTO TABLE createdtable SELECT * FROM src1;
      |SELECT * FROM createdtable;
      |INSERT OVERWRITE TABLE createdtable SELECT * FROM src WHERE key = 86;
      |SELECT * FROM createdtable;
    """.stripMargin)

  test("SPARK-7270: consider dynamic partition when comparing table output") {
    sql(s"CREATE TABLE test_partition (a STRING) PARTITIONED BY (b BIGINT, c STRING)")
    sql(s"CREATE TABLE ptest (a STRING, b BIGINT, c STRING)")

    val analyzedPlan = sql(
      """
        |INSERT OVERWRITE table test_partition PARTITION (b=1, c)
        |SELECT 'a', 'c' from ptest
      """.stripMargin).queryExecution.analyzed

    assertResult(false, "Incorrect cast detected\n" + analyzedPlan) {
      var hasCast = false
      analyzedPlan.collect {
        case p: Project => p.transformExpressionsUp { case c: Cast => hasCast = true; c }
      }
      hasCast
    }
  }

  createQueryTest("transform",
    "SELECT TRANSFORM (key) USING 'cat' AS (tKey) FROM src")

  createQueryTest("schema-less transform",
    """
      |SELECT TRANSFORM (key, value) USING 'cat' FROM src;
      |SELECT TRANSFORM (*) USING 'cat' FROM src;
    """.stripMargin)

  val delimiter = "'\t'"

  createQueryTest("transform with custom field delimiter",
    s"""
      |SELECT TRANSFORM (key) ROW FORMAT DELIMITED FIELDS TERMINATED BY ${delimiter}
      |USING 'cat' AS (tKey) ROW FORMAT DELIMITED FIELDS TERMINATED BY ${delimiter} FROM src;
    """.stripMargin.replaceAll("\n", " "))

  createQueryTest("transform with custom field delimiter2",
    s"""
      |SELECT TRANSFORM (key, value) ROW FORMAT DELIMITED FIELDS TERMINATED BY ${delimiter}
      |USING 'cat' ROW FORMAT DELIMITED FIELDS TERMINATED BY ${delimiter} FROM src;
    """.stripMargin.replaceAll("\n", " "))

  createQueryTest("transform with custom field delimiter3",
    s"""
      |SELECT TRANSFORM (*) ROW FORMAT DELIMITED FIELDS TERMINATED BY ${delimiter}
      |USING 'cat' ROW FORMAT DELIMITED FIELDS TERMINATED BY ${delimiter} FROM src;
    """.stripMargin.replaceAll("\n", " "))

  createQueryTest("transform with SerDe",
    """
      |SELECT TRANSFORM (key, value) ROW FORMAT SERDE
      |'org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe'
      |USING 'cat' AS (tKey, tValue) ROW FORMAT SERDE
      |'org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe' FROM src;
    """.stripMargin.replaceAll(System.lineSeparator(), " "))

  test("transform with SerDe2") {

    sql("CREATE TABLE small_src(key INT, value STRING)")
    sql("INSERT OVERWRITE TABLE small_src SELECT key, value FROM src LIMIT 10")

    val expected = sql("SELECT key FROM small_src").collect().head
    val res = sql(
      """
        |SELECT TRANSFORM (key) ROW FORMAT SERDE
        |'org.apache.hadoop.hive.serde2.avro.AvroSerDe'
        |WITH SERDEPROPERTIES ('avro.schema.literal'='{"namespace":
        |"testing.hive.avro.serde","name": "src","type": "record","fields":
        |[{"name":"key","type":"int"}]}') USING 'cat' AS (tKey INT) ROW FORMAT SERDE
        |'org.apache.hadoop.hive.serde2.avro.AvroSerDe' WITH SERDEPROPERTIES
        |('avro.schema.literal'='{"namespace": "testing.hive.avro.serde","name":
        |"src","type": "record","fields": [{"name":"key","type":"int"}]}')
        |FROM small_src
      """.stripMargin.replaceAll(System.lineSeparator(), " ")).collect().head

    assert(expected(0) === res(0))
  }

  createQueryTest("transform with SerDe3",
    """
      |SELECT TRANSFORM (*) ROW FORMAT SERDE
      |'org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe' WITH SERDEPROPERTIES
      |('serialization.last.column.takes.rest'='true') USING 'cat' AS (tKey, tValue)
      |ROW FORMAT SERDE 'org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe'
      |WITH SERDEPROPERTIES ('serialization.last.column.takes.rest'='true') FROM src;
    """.stripMargin.replaceAll(System.lineSeparator(), " "))

  createQueryTest("transform with SerDe4",
    """
      |SELECT TRANSFORM (*) ROW FORMAT SERDE
      |'org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe' WITH SERDEPROPERTIES
      |('serialization.last.column.takes.rest'='true') USING 'cat' ROW FORMAT SERDE
      |'org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe' WITH SERDEPROPERTIES
      |('serialization.last.column.takes.rest'='true') FROM src;
    """.stripMargin.replaceAll(System.lineSeparator(), " "))

  createQueryTest("LIKE",
    "SELECT * FROM src WHERE value LIKE '%1%'")

  createQueryTest("DISTINCT",
    "SELECT DISTINCT key, value FROM src")

  createQueryTest("empty aggregate input",
    "SELECT SUM(key) FROM (SELECT * FROM src LIMIT 0) a")

  createQueryTest("lateral view1",
    "SELECT tbl.* FROM src LATERAL VIEW explode(array(1,2)) tbl as a")

  createQueryTest("lateral view2",
    "SELECT * FROM src LATERAL VIEW explode(array(1,2)) tbl")

  createQueryTest("lateral view3",
    "FROM src SELECT key, D.* lateral view explode(array(key+3, key+4)) D as CX")

  // scalastyle:off
  createQueryTest("lateral view4",
    """
      |create table src_lv1 (key string, value string);
      |create table src_lv2 (key string, value string);
      |
      |FROM src
      |insert overwrite table src_lv1 SELECT key, D.* lateral view explode(array(key+3, key+4)) D as CX
      |insert overwrite table src_lv2 SELECT key, D.* lateral view explode(array(key+3, key+4)) D as CX
    """.stripMargin)
  // scalastyle:on

  createQueryTest("lateral view5",
    "FROM src SELECT explode(array(key+3, key+4))")

  createQueryTest("lateral view6",
    "SELECT * FROM src LATERAL VIEW explode(map(key+3,key+4)) D as k, v")

  createQueryTest("Specify the udtf output",
    "SELECT d FROM (SELECT explode(array(1,1)) d FROM src LIMIT 1) t")

  createQueryTest("SPARK-9034 Reflect field names defined in GenericUDTF #1",
    "SELECT col FROM (SELECT explode(array(key,value)) FROM src LIMIT 1) t")

  createQueryTest("SPARK-9034 Reflect field names defined in GenericUDTF #2",
    "SELECT key,value FROM (SELECT explode(map(key,value)) FROM src LIMIT 1) t")

  test("sampling") {
    sql("SELECT * FROM src TABLESAMPLE(0.1 PERCENT) s")
    sql("SELECT * FROM src TABLESAMPLE(100 PERCENT) s")
  }

  test("DataFrame toString") {
    sql("SHOW TABLES").toString
    sql("SELECT * FROM src").toString
  }

  createQueryTest("case statements with key #1",
    "SELECT (CASE 1 WHEN 2 THEN 3 END) FROM src where key < 15")

  createQueryTest("case statements with key #2",
    "SELECT (CASE key WHEN 2 THEN 3 ELSE 0 END) FROM src WHERE key < 15")

  createQueryTest("case statements with key #3",
    "SELECT (CASE key WHEN 2 THEN 3 WHEN NULL THEN 4 END) FROM src WHERE key < 15")

  createQueryTest("case statements with key #4",
    "SELECT (CASE key WHEN 2 THEN 3 WHEN NULL THEN 4 ELSE 0 END) FROM src WHERE key < 15")

  createQueryTest("case statements WITHOUT key #1",
    "SELECT (CASE WHEN key > 2 THEN 3 END) FROM src WHERE key < 15")

  createQueryTest("case statements WITHOUT key #2",
    "SELECT (CASE WHEN key > 2 THEN 3 ELSE 4 END) FROM src WHERE key < 15")

  createQueryTest("case statements WITHOUT key #3",
    "SELECT (CASE WHEN key > 2 THEN 3 WHEN 2 > key THEN 2 END) FROM src WHERE key < 15")

  createQueryTest("case statements WITHOUT key #4",
    "SELECT (CASE WHEN key > 2 THEN 3 WHEN 2 > key THEN 2 ELSE 0 END) FROM src WHERE key < 15")

  // Jdk version leads to different query output for double, so not use createQueryTest here
  test("timestamp cast #1") {
    val res = sql("SELECT CAST(CAST(1 AS TIMESTAMP) AS DOUBLE) FROM src LIMIT 1").collect().head
    assert(1 == res.getDouble(0))
  }

  createQueryTest("timestamp cast #2",
    "SELECT CAST(CAST(1.2 AS TIMESTAMP) AS DOUBLE) FROM src LIMIT 1")

  test("timestamp cast #3") {
    val res = sql("SELECT CAST(CAST(1200 AS TIMESTAMP) AS INT) FROM src LIMIT 1").collect().head
    assert(1200 == res.getInt(0))
  }

  createQueryTest("timestamp cast #4",
    "SELECT CAST(CAST(1.2 AS TIMESTAMP) AS DOUBLE) FROM src LIMIT 1")

  test("timestamp cast #5") {
    val res = sql("SELECT CAST(CAST(-1 AS TIMESTAMP) AS DOUBLE) FROM src LIMIT 1").collect().head
    assert(-1 == res.get(0))
  }

  createQueryTest("timestamp cast #6",
    "SELECT CAST(CAST(-1.2 AS TIMESTAMP) AS DOUBLE) FROM src LIMIT 1")

  test("timestamp cast #7") {
    val res = sql("SELECT CAST(CAST(-1200 AS TIMESTAMP) AS INT) FROM src LIMIT 1").collect().head
    assert(-1200 == res.getInt(0))
  }

  createQueryTest("timestamp cast #8",
    "SELECT CAST(CAST(-1.2 AS TIMESTAMP) AS DOUBLE) FROM src LIMIT 1")

  createQueryTest("select null from table",
    "SELECT null FROM src LIMIT 1")

  createQueryTest("CTE feature #1",
    "with q1 as (select key from src) select * from q1 where key = 5")

  createQueryTest("CTE feature #2",
    """with q1 as (select * from src where key= 5),
      |q2 as (select * from src s2 where key = 4)
      |select value from q1 union all select value from q2
    """.stripMargin)

  createQueryTest("CTE feature #3",
    """with q1 as (select key from src)
      |from q1
      |select * where key = 4
    """.stripMargin)

  // test get_json_object again Hive, because the HiveCompatibilitySuite cannot handle result
  // with newline in it.
  createQueryTest("get_json_object #1",
    "SELECT get_json_object(src_json.json, '$') FROM src_json")

  createQueryTest("get_json_object #2",
    "SELECT get_json_object(src_json.json, '$.owner'), get_json_object(src_json.json, '$.store')" +
      " FROM src_json")

  createQueryTest("get_json_object #3",
    "SELECT get_json_object(src_json.json, '$.store.bicycle'), " +
      "get_json_object(src_json.json, '$.store.book') FROM src_json")

  createQueryTest("get_json_object #4",
    "SELECT get_json_object(src_json.json, '$.store.book[0]'), " +
      "get_json_object(src_json.json, '$.store.book[*]') FROM src_json")

  createQueryTest("get_json_object #5",
    "SELECT get_json_object(src_json.json, '$.store.book[0].category'), " +
      "get_json_object(src_json.json, '$.store.book[*].category'), " +
      "get_json_object(src_json.json, '$.store.book[*].isbn'), " +
      "get_json_object(src_json.json, '$.store.book[*].reader') FROM src_json")

  createQueryTest("get_json_object #6",
    "SELECT get_json_object(src_json.json, '$.store.book[*].reader[0].age'), " +
      "get_json_object(src_json.json, '$.store.book[*].reader[*].age') FROM src_json")

  createQueryTest("get_json_object #7",
    "SELECT get_json_object(src_json.json, '$.store.basket[0][1]'), " +
      "get_json_object(src_json.json, '$.store.basket[*]'), " +
      // Hive returns wrong result with [*][0], so this expression is change to make test pass
      "get_json_object(src_json.json, '$.store.basket[0][0]'), " +
      "get_json_object(src_json.json, '$.store.basket[0][*]'), " +
      "get_json_object(src_json.json, '$.store.basket[*][*]'), " +
      "get_json_object(src_json.json, '$.store.basket[0][2].b'), " +
      "get_json_object(src_json.json, '$.store.basket[0][*].b') FROM src_json")

  createQueryTest("get_json_object #8",
    "SELECT get_json_object(src_json.json, '$.non_exist_key'), " +
      "get_json_object(src_json.json, '$..no_recursive'), " +
      "get_json_object(src_json.json, '$.store.book[10]'), " +
      "get_json_object(src_json.json, '$.store.book[0].non_exist_key'), " +
      "get_json_object(src_json.json, '$.store.basket[*].non_exist_key'), " +
      "get_json_object(src_json.json, '$.store.basket[0][*].non_exist_key') FROM src_json")

  createQueryTest("get_json_object #9",
    "SELECT get_json_object(src_json.json, '$.zip code') FROM src_json")

  createQueryTest("get_json_object #10",
    "SELECT get_json_object(src_json.json, '$.fb:testid') FROM src_json")

  test("predicates contains an empty AttributeSet() references") {
    sql(
      """
        |SELECT a FROM (
        |  SELECT 1 AS a FROM src LIMIT 1 ) t
        |WHERE abs(20141202) is not null
      """.stripMargin).collect()
  }

  test("implement identity function using case statement") {
    val actual = sql("SELECT (CASE key WHEN key THEN key END) FROM src")
      .rdd
      .map { case Row(i: Int) => i }
      .collect()
      .toSet

    val expected = sql("SELECT key FROM src")
      .rdd
      .map { case Row(i: Int) => i }
      .collect()
      .toSet

    assert(actual === expected)
  }

  // TODO: adopt this test when Spark SQL has the functionality / framework to report errors.
  // See https://github.com/apache/spark/pull/1055#issuecomment-45820167 for a discussion.
  ignore("non-boolean conditions in a CaseWhen are illegal") {
    intercept[Exception] {
      sql("SELECT (CASE WHEN key > 2 THEN 3 WHEN 1 THEN 2 ELSE 0 END) FROM src").collect()
    }
  }

  createQueryTest("case sensitivity when query Hive table",
    "SELECT srcalias.KEY, SRCALIAS.value FROM sRc SrCAlias WHERE SrCAlias.kEy < 15")

  test("case sensitivity: created temporary view") {
    val testData =
      TestHive.sparkContext.parallelize(
        TestData(1, "str1") ::
        TestData(2, "str2") :: Nil)
    testData.toDF().createOrReplaceTempView("REGisteredTABle")

    assertResult(Array(Row(2, "str2"))) {
      sql("SELECT tablealias.A, TABLEALIAS.b FROM reGisteredTABle TableAlias " +
        "WHERE TableAliaS.a > 1").collect()
    }
  }

  def isExplanation(result: DataFrame): Boolean = {
    val explanation = result.select('plan).collect().map { case Row(plan: String) => plan }
    explanation.head.startsWith("== Physical Plan ==")
  }

  test("SPARK-1704: Explain commands as a DataFrame") {
    sql("CREATE TABLE IF NOT EXISTS src (key INT, value STRING)")

    val df = sql("explain select key, count(value) from src group by key")
    assert(isExplanation(df))

    TestHive.reset()
  }

  test("SPARK-2180: HAVING support in GROUP BY clauses (positive)") {
    val fixture = List(("foo", 2), ("bar", 1), ("foo", 4), ("bar", 3))
      .zipWithIndex.map {case ((value, attr), key) => HavingRow(key, value, attr)}
    TestHive.sparkContext.parallelize(fixture).toDF().createOrReplaceTempView("having_test")
    val results =
      sql("SELECT value, max(attr) AS attr FROM having_test GROUP BY value HAVING attr > 3")
      .collect()
      .map(x => (x.getString(0), x.getInt(1)))

    assert(results === Array(("foo", 4)))
    TestHive.reset()
  }

  test("SPARK-2180: HAVING with non-boolean clause raises no exceptions") {
    sql("select key, count(*) c from src group by key having c").collect()
  }

  test("SPARK-2225: turn HAVING without GROUP BY into a simple filter") {
    assert(sql("select key from src having key > 490").collect().size < 100)
  }

  test("union/except/intersect") {
    assertResult(Array(Row(1), Row(1))) {
      sql("select 1 as a union all select 1 as a").collect()
    }
    assertResult(Array(Row(1))) {
      sql("select 1 as a union distinct select 1 as a").collect()
    }
    assertResult(Array(Row(1))) {
      sql("select 1 as a union select 1 as a").collect()
    }
    assertResult(Array()) {
      sql("select 1 as a except select 1 as a").collect()
    }
    assertResult(Array(Row(1))) {
      sql("select 1 as a intersect select 1 as a").collect()
    }
  }

  test("SPARK-5383 alias for udfs with multi output columns") {
    assert(
      sql("select stack(2, key, value, key, value) as (a, b) from src limit 5")
        .collect()
        .size == 5)

    assert(
      sql("select a, b from (select stack(2, key, value, key, value) as (a, b) from src) t limit 5")
        .collect()
        .size == 5)
  }

  test("SPARK-5367: resolve star expression in udf") {
    assert(sql("select concat(*) from src limit 5").collect().size == 5)
    assert(sql("select array(*) from src limit 5").collect().size == 5)
    assert(sql("select concat(key, *) from src limit 5").collect().size == 5)
    assert(sql("select array(key, *) from src limit 5").collect().size == 5)
  }

  test("Exactly once semantics for DDL and command statements") {
    val tableName = "test_exactly_once"
    val q0 = sql(s"CREATE TABLE $tableName(key INT, value STRING)")

    // If the table was not created, the following assertion would fail
    assert(Try(table(tableName)).isSuccess)

    // If the CREATE TABLE command got executed again, the following assertion would fail
    assert(Try(q0.count()).isSuccess)
  }

  test("DESCRIBE commands") {
    sql(s"CREATE TABLE test_describe_commands1 (key INT, value STRING) PARTITIONED BY (dt STRING)")

    sql(
      """FROM src INSERT OVERWRITE TABLE test_describe_commands1 PARTITION (dt='2008-06-08')
        |SELECT key, value
      """.stripMargin)

    // Describe a table
    assertResult(
      Array(
        Row("key", "int", null),
        Row("value", "string", null),
        Row("dt", "string", null),
        Row("# Partition Information", "", ""),
        Row("# col_name", "data_type", "comment"),
        Row("dt", "string", null))
    ) {
      sql("DESCRIBE test_describe_commands1")
        .select('col_name, 'data_type, 'comment)
        .collect()
    }

    // Describe a table with a fully qualified table name
    assertResult(
      Array(
        Row("key", "int", null),
        Row("value", "string", null),
        Row("dt", "string", null),
        Row("# Partition Information", "", ""),
        Row("# col_name", "data_type", "comment"),
        Row("dt", "string", null))
    ) {
      sql("DESCRIBE default.test_describe_commands1")
        .select('col_name, 'data_type, 'comment)
        .collect()
    }

    // Describe a temporary view.
    val testData =
      TestHive.sparkContext.parallelize(
        TestData(1, "str1") ::
        TestData(1, "str2") :: Nil)
    testData.toDF().createOrReplaceTempView("test_describe_commands2")

    assertResult(
      Array(
        Row("a", "int", ""),
        Row("b", "string", ""))
    ) {
      sql("DESCRIBE test_describe_commands2")
        .select('col_name, 'data_type, 'comment)
        .collect()
    }
  }

  test("SPARK-2263: Insert Map<K, V> values") {
    sql("CREATE TABLE m(value MAP<INT, STRING>)")
    sql("INSERT OVERWRITE TABLE m SELECT MAP(key, value) FROM src LIMIT 10")
    sql("SELECT * FROM m").collect().zip(sql("SELECT * FROM src LIMIT 10").collect()).foreach {
      case (Row(map: Map[_, _]), Row(key: Int, value: String)) =>
        assert(map.size === 1)
        assert(map.head === (key, value))
    }
  }

  test("ADD JAR command") {
    val testJar = TestHive.getHiveFile("data/files/TestSerDe.jar").getCanonicalPath
    sql("CREATE TABLE alter1(a INT, b INT)")
    intercept[Exception] {
      sql(
        """ALTER TABLE alter1 SET SERDE 'org.apache.hadoop.hive.serde2.TestSerDe'
          |WITH serdeproperties('s1'='9')
        """.stripMargin)
    }
    sql("DROP TABLE alter1")
  }

  test("ADD JAR command 2") {
    // this is a test case from mapjoin_addjar.q
    val testJar = TestHive.getHiveFile("hive-hcatalog-core-0.13.1.jar").getCanonicalPath
    val testData = TestHive.getHiveFile("data/files/sample.json").getCanonicalPath
    sql(s"ADD JAR $testJar")
    sql(
      """CREATE TABLE t1(a string, b string)
      |ROW FORMAT SERDE 'org.apache.hive.hcatalog.data.JsonSerDe'""".stripMargin)
    sql(s"""LOAD DATA LOCAL INPATH "$testData" INTO TABLE t1""")
    sql("select * from src join t1 on src.key = t1.a")
    sql("DROP TABLE t1")
    assert(sql("list jars").
      filter(_.getString(0).contains("hive-hcatalog-core-0.13.1.jar")).count() > 0)
    assert(sql("list jar").
      filter(_.getString(0).contains("hive-hcatalog-core-0.13.1.jar")).count() > 0)
    val testJar2 = TestHive.getHiveFile("TestUDTF.jar").getCanonicalPath
    sql(s"ADD JAR $testJar2")
    assert(sql(s"list jar $testJar").count() == 1)
  }

  test("CREATE TEMPORARY FUNCTION") {
    val funcJar = TestHive.getHiveFile("TestUDTF.jar").getCanonicalPath
    val jarURL = s"file://$funcJar"
    sql(s"ADD JAR $jarURL")
    sql(
      """CREATE TEMPORARY FUNCTION udtf_count2 AS
        |'org.apache.spark.sql.hive.execution.GenericUDTFCount2'
      """.stripMargin)
    assert(sql("DESCRIBE FUNCTION udtf_count2").count > 1)
    sql("DROP TEMPORARY FUNCTION udtf_count2")
  }

  test("ADD FILE command") {
    val testFile = TestHive.getHiveFile("data/files/v1.txt").getCanonicalFile
    sql(s"ADD FILE $testFile")

    val checkAddFileRDD = sparkContext.parallelize(1 to 2, 1).mapPartitions { _ =>
      Iterator.single(new File(SparkFiles.get("v1.txt")).canRead)
    }

    assert(checkAddFileRDD.first())
    assert(sql("list files").
      filter(_.getString(0).contains("data/files/v1.txt")).count() > 0)
    assert(sql("list file").
      filter(_.getString(0).contains("data/files/v1.txt")).count() > 0)
    assert(sql(s"list file $testFile").count() == 1)
  }

  createQueryTest("dynamic_partition",
    """
      |DROP TABLE IF EXISTS dynamic_part_table;
      |CREATE TABLE dynamic_part_table(intcol INT) PARTITIONED BY (partcol1 INT, partcol2 INT);
      |
      |SET hive.exec.dynamic.partition.mode=nonstrict;
      |
      |INSERT INTO TABLE dynamic_part_table PARTITION(partcol1, partcol2)
      |SELECT 1, 1, 1 FROM src WHERE key=150;
      |
      |INSERT INTO TABLE dynamic_part_table PARTITION(partcol1, partcol2)
      |SELECT 1, NULL, 1 FROM src WHERE key=150;
      |
      |INSERT INTO TABLE dynamic_part_table PARTITION(partcol1, partcol2)
      |SELECT 1, 1, NULL FROM src WHERE key=150;
      |
      |INSERT INTO TABLe dynamic_part_table PARTITION(partcol1, partcol2)
      |SELECT 1, NULL, NULL FROM src WHERE key=150;
      |
      |DROP TABLE IF EXISTS dynamic_part_table;
    """.stripMargin)

  ignore("Dynamic partition folder layout") {
    sql("DROP TABLE IF EXISTS dynamic_part_table")
    sql("CREATE TABLE dynamic_part_table(intcol INT) PARTITIONED BY (partcol1 INT, partcol2 INT)")
    sql("SET hive.exec.dynamic.partition.mode=nonstrict")

    val data = Map(
      Seq("1", "1") -> 1,
      Seq("1", "NULL") -> 2,
      Seq("NULL", "1") -> 3,
      Seq("NULL", "NULL") -> 4)

    data.foreach { case (parts, value) =>
      sql(
        s"""INSERT INTO TABLE dynamic_part_table PARTITION(partcol1, partcol2)
           |SELECT $value, ${parts.mkString(", ")} FROM src WHERE key=150
         """.stripMargin)

      val partFolder = Seq("partcol1", "partcol2")
        .zip(parts)
        .map { case (k, v) =>
          if (v == "NULL") {
            s"$k=${ConfVars.DEFAULTPARTITIONNAME.defaultStrVal}"
          } else {
            s"$k=$v"
          }
        }
        .mkString("/")

      // Loads partition data to a temporary table to verify contents
      val path = s"${sparkSession.warehousePath}/dynamic_part_table/$partFolder/part-00000"

      sql("DROP TABLE IF EXISTS dp_verify")
      sql("CREATE TABLE dp_verify(intcol INT)")
      sql(s"LOAD DATA LOCAL INPATH '$path' INTO TABLE dp_verify")

      assert(sql("SELECT * FROM dp_verify").collect() === Array(Row(value)))
    }
  }

  test("SPARK-5592: get java.net.URISyntaxException when dynamic partitioning") {
    sql("""
      |create table sc as select *
      |from (select '2011-01-11', '2011-01-11+14:18:26' from src tablesample (1 rows)
      |union all
      |select '2011-01-11', '2011-01-11+15:18:26' from src tablesample (1 rows)
      |union all
      |select '2011-01-11', '2011-01-11+16:18:26' from src tablesample (1 rows) ) s
    """.stripMargin)
    sql("create table sc_part (key string) partitioned by (ts string) stored as rcfile")
    sql("set hive.exec.dynamic.partition=true")
    sql("set hive.exec.dynamic.partition.mode=nonstrict")
    sql("insert overwrite table sc_part partition(ts) select * from sc")
    sql("drop table sc_part")
  }

  test("Partition spec validation") {
    sql("DROP TABLE IF EXISTS dp_test")
    sql("CREATE TABLE dp_test(key INT, value STRING) PARTITIONED BY (dp INT, sp INT)")
    sql("SET hive.exec.dynamic.partition.mode=strict")

    // Should throw when using strict dynamic partition mode without any static partition
    intercept[AnalysisException] {
      sql(
        """INSERT INTO TABLE dp_test PARTITION(dp)
          |SELECT key, value, key % 5 FROM src
        """.stripMargin)
    }

    sql("SET hive.exec.dynamic.partition.mode=nonstrict")

    // Should throw when a static partition appears after a dynamic partition
    intercept[AnalysisException] {
      sql(
        """INSERT INTO TABLE dp_test PARTITION(dp, sp = 1)
          |SELECT key, value, key % 5 FROM src
        """.stripMargin)
    }
  }

  test("SPARK-3414 regression: should store analyzed logical plan when creating a temporary view") {
    sparkContext.makeRDD(Seq.empty[LogEntry]).toDF().createOrReplaceTempView("rawLogs")
    sparkContext.makeRDD(Seq.empty[LogFile]).toDF().createOrReplaceTempView("logFiles")

    sql(
      """
      SELECT name, message
      FROM rawLogs
      JOIN (
        SELECT name
        FROM logFiles
      ) files
      ON rawLogs.filename = files.name
      """).createOrReplaceTempView("boom")

    // This should be successfully analyzed
    sql("SELECT * FROM boom").queryExecution.analyzed
  }

  test("SPARK-3810: PreprocessTableInsertion static partitioning support") {
    val analyzedPlan = {
      loadTestTable("srcpart")
      sql("DROP TABLE IF EXISTS withparts")
      sql("CREATE TABLE withparts LIKE srcpart")
      sql("INSERT INTO TABLE withparts PARTITION(ds='1', hr='2') SELECT key, value FROM src")
        .queryExecution.analyzed
      }

    assertResult(1, "Duplicated project detected\n" + analyzedPlan) {
      analyzedPlan.collect {
        case _: Project => ()
      }.size
    }
  }

  test("SPARK-3810: PreprocessTableInsertion dynamic partitioning support") {
    val analyzedPlan = {
      loadTestTable("srcpart")
      sql("DROP TABLE IF EXISTS withparts")
      sql("CREATE TABLE withparts LIKE srcpart")
      sql("SET hive.exec.dynamic.partition.mode=nonstrict")

      sql("CREATE TABLE IF NOT EXISTS withparts LIKE srcpart")
      sql("INSERT INTO TABLE withparts PARTITION(ds, hr) SELECT key, value, '1', '2' FROM src")
        .queryExecution.analyzed
    }

    assertResult(2, "Duplicated project detected\n" + analyzedPlan) {
      analyzedPlan.collect {
        case _: Project => ()
      }.size
    }
  }

  test("parse HQL set commands") {
    // Adapted from its SQL counterpart.
    val testKey = "spark.sql.key.usedfortestonly"
    val testVal = "val0,val_1,val2.3,my_table"

    sql(s"set $testKey=$testVal")
    assert(getConf(testKey, testVal + "_") == testVal)

    sql("set some.property=20")
    assert(getConf("some.property", "0") == "20")
    sql("set some.property = 40")
    assert(getConf("some.property", "0") == "40")

    sql(s"set $testKey=$testVal")
    assert(getConf(testKey, "0") == testVal)

    sql(s"set $testKey=")
    assert(getConf(testKey, "0") == "")
  }

  test("current_database with multiple sessions") {
    sql("create database a")
    sql("use a")
    val s2 = newSession()
    s2.sql("create database b")
    s2.sql("use b")

    assert(sql("select current_database()").first() === Row("a"))
    assert(s2.sql("select current_database()").first() === Row("b"))

    try {
      sql("create table test_a(key INT, value STRING)")
      s2.sql("create table test_b(key INT, value STRING)")

      sql("select * from test_a")
      intercept[AnalysisException] {
        sql("select * from test_b")
      }
      sql("select * from b.test_b")

      s2.sql("select * from test_b")
      intercept[AnalysisException] {
        s2.sql("select * from test_a")
      }
      s2.sql("select * from a.test_a")
    } finally {
      sql("DROP TABLE IF EXISTS test_a")
      s2.sql("DROP TABLE IF EXISTS test_b")
    }

  }

  test("use database") {
    val currentDatabase = sql("select current_database()").first().getString(0)

    sql("CREATE DATABASE hive_test_db")
    sql("USE hive_test_db")
    assert("hive_test_db" == sql("select current_database()").first().getString(0))

    intercept[AnalysisException] {
      sql("USE not_existing_db")
    }

    sql(s"USE $currentDatabase")
    assert(currentDatabase == sql("select current_database()").first().getString(0))
  }

  test("lookup hive UDF in another thread") {
    val e = intercept[AnalysisException] {
      range(1).selectExpr("not_a_udf()")
    }
    assert(e.getMessage.contains("Undefined function"))
    assert(e.getMessage.contains("not_a_udf"))
    var success = false
    val t = new Thread("test") {
      override def run(): Unit = {
        val e = intercept[AnalysisException] {
          range(1).selectExpr("not_a_udf()")
        }
        assert(e.getMessage.contains("Undefined function"))
        assert(e.getMessage.contains("not_a_udf"))
        success = true
      }
    }
    t.start()
    t.join()
    assert(success)
  }

  createQueryTest("select from thrift based table",
    "SELECT * from src_thrift")

  // Put tests that depend on specific Hive settings before these last two test,
  // since they modify /clear stuff.

  test("role management commands are not supported") {
    assertUnsupportedFeature { sql("CREATE ROLE my_role") }
    assertUnsupportedFeature { sql("DROP ROLE my_role") }
    assertUnsupportedFeature { sql("SHOW CURRENT ROLES") }
    assertUnsupportedFeature { sql("SHOW ROLES") }
    assertUnsupportedFeature { sql("SHOW GRANT") }
    assertUnsupportedFeature { sql("SHOW ROLE GRANT USER my_principal") }
    assertUnsupportedFeature { sql("SHOW PRINCIPALS my_role") }
    assertUnsupportedFeature { sql("SET ROLE my_role") }
    assertUnsupportedFeature { sql("GRANT my_role TO USER my_user") }
    assertUnsupportedFeature { sql("GRANT ALL ON my_table TO USER my_user") }
    assertUnsupportedFeature { sql("REVOKE my_role FROM USER my_user") }
    assertUnsupportedFeature { sql("REVOKE ALL ON my_table FROM USER my_user") }
  }

  test("import/export commands are not supported") {
    assertUnsupportedFeature { sql("IMPORT TABLE my_table FROM 'my_path'") }
    assertUnsupportedFeature { sql("EXPORT TABLE my_table TO 'my_path'") }
  }

  test("some show commands are not supported") {
    assertUnsupportedFeature { sql("SHOW COMPACTIONS") }
    assertUnsupportedFeature { sql("SHOW TRANSACTIONS") }
    assertUnsupportedFeature { sql("SHOW INDEXES ON my_table") }
    assertUnsupportedFeature { sql("SHOW LOCKS my_table") }
  }

  test("lock/unlock table and database commands are not supported") {
    assertUnsupportedFeature { sql("LOCK TABLE my_table SHARED") }
    assertUnsupportedFeature { sql("UNLOCK TABLE my_table") }
    assertUnsupportedFeature { sql("LOCK DATABASE my_db SHARED") }
    assertUnsupportedFeature { sql("UNLOCK DATABASE my_db") }
  }

  test("create/drop/alter index commands are not supported") {
    assertUnsupportedFeature {
      sql("CREATE INDEX my_index ON TABLE my_table(a) as 'COMPACT' WITH DEFERRED REBUILD")}
    assertUnsupportedFeature { sql("DROP INDEX my_index ON my_table") }
    assertUnsupportedFeature { sql("ALTER INDEX my_index ON my_table REBUILD")}
    assertUnsupportedFeature {
      sql("ALTER INDEX my_index ON my_table set IDXPROPERTIES (\"prop1\"=\"val1_new\")")}
  }

  test("create/drop macro commands are not supported") {
    assertUnsupportedFeature {
      sql("CREATE TEMPORARY MACRO SIGMOID (x DOUBLE) 1.0 / (1.0 + EXP(-x))")
    }
    assertUnsupportedFeature { sql("DROP TEMPORARY MACRO SIGMOID") }
  }

  // scalastyle:off
  createQueryTest("alter_rename_partition",
    """
      |-- Cleanup
      |DROP TABLE IF EXISTS alter_rename_partition_src;
      |DROP TABLE IF EXISTS alter_rename_partition;
      |SHOW TABLES;
      |
      |create table alter_rename_partition_src ( col1 string ) stored as textfile ;
      |load data local inpath '../../data/files/test.dat' overwrite into table alter_rename_partition_src ;
      |
      |create table alter_rename_partition ( col1 string ) partitioned by (pcol1 string , pcol2 string) stored as sequencefile;
      |
      |insert overwrite table alter_rename_partition partition (pCol1='old_part1:', pcol2='old_part2:') select col1 from alter_rename_partition_src ;
      |select * from alter_rename_partition where pcol1='old_part1:' and pcol2='old_part2:';
      |
      |alter table alter_rename_partition partition (pCol1='old_part1:', pcol2='old_part2:') rename to partition (pCol1='new_part1:', pcol2='new_part2:');
      |SHOW PARTITIONS alter_rename_partition;
      |select * from alter_rename_partition where pcol1='old_part1:' and pcol2='old_part2:';
      |select * from alter_rename_partition where pcol1='new_part1:' and pcol2='new_part2:';
      |
      |-- Cleanup
      |DROP TABLE IF EXISTS alter_rename_partition_src;
      |DROP TABLE IF EXISTS alter_rename_partition;
      |SHOW TABLES;
      |
      |-- With non-default Database
      |
      |CREATE DATABASE alter_rename_partition_db;
      |USE alter_rename_partition_db;
      |SHOW TABLES;
      |
      |CREATE TABLE alter_rename_partition_src (col1 STRING) STORED AS TEXTFILE ;
      |LOAD DATA LOCAL INPATH '../../data/files/test.dat' OVERWRITE INTO TABLE alter_rename_partition_src ;
      |
      |CREATE TABLE alter_rename_partition (col1 STRING) PARTITIONED BY (pcol1 STRING, pcol2 STRING) STORED AS SEQUENCEFILE;
      |
      |INSERT OVERWRITE TABLE alter_rename_partition PARTITION (pCol1='old_part1:', pcol2='old_part2:') SELECT col1 FROM alter_rename_partition_src ;
      |SELECT * FROM alter_rename_partition WHERE pcol1='old_part1:' AND pcol2='old_part2:';
      |
      |ALTER TABLE alter_rename_partition PARTITION (pCol1='old_part1:', pcol2='old_part2:') RENAME TO PARTITION (pCol1='new_part1:', pcol2='new_part2:');
      |SHOW PARTITIONS alter_rename_partition;
      |SELECT * FROM alter_rename_partition WHERE pcol1='old_part1:' and pcol2='old_part2:';
      |SELECT * FROM alter_rename_partition WHERE pcol1='new_part1:' and pcol2='new_part2:';
    """.stripMargin)

  createQueryTest("date_1",
    """
      |set hive.fetch.task.conversion=more;
      |
      |drop table if exists date_1;
      |
      |create table date_1 (d date);
      |
      |insert overwrite table date_1
      |  select cast('2011-01-01' as date) from src tablesample (1 rows);
      |
      |select * from date_1 limit 1;
      |select d, count(d) from date_1 group by d;
      |
      |insert overwrite table date_1
      |  select date '2011-01-01' from src tablesample (1 rows);
      |
      |select * from date_1 limit 1;
      |select d, count(d) from date_1 group by d;
      |
      |insert overwrite table date_1
      |  select cast(cast('2011-01-01 00:00:00' as timestamp) as date) from src tablesample (1 rows);
      |
      |select * from date_1 limit 1;
      |select d, count(d) from date_1 group by d;
      |
      |-- Valid casts
      |select
      |  cast('2012-01-01' as string),
      |  cast(d as string),
      |  cast(d as timestamp),
      |  cast(cast(d as timestamp) as date),
      |  cast(d as date)
      |from date_1 limit 1;
      |
      |-- Invalid casts.
      |select
      |  cast(d as boolean),
      |  cast(d as tinyint),
      |  cast(d as smallint),
      |  cast(d as int),
      |  cast(d as bigint),
      |  cast(d as float),
      |  cast(d as double)
      |from date_1 limit 1;
      |
      |-- These comparisons should all be true
      |select
      |  date '2011-01-01' = date '2011-01-01',
      |  unix_timestamp(date '2011-01-01') = unix_timestamp(date '2011-01-01'),
      |  unix_timestamp(date '2011-01-01') = unix_timestamp(cast(date '2011-01-01' as timestamp)),
      |  unix_timestamp(date '2011-01-01') = unix_timestamp(cast(cast('2011-01-01 12:13:14' as timestamp) as date)),
      |  unix_timestamp(date '2011-01-01') < unix_timestamp(cast('2011-01-01 00:00:01' as timestamp)),
      |  unix_timestamp(date '2011-01-01') = unix_timestamp(cast('2011-01-01 00:00:00' as timestamp)),
      |  unix_timestamp(date '2011-01-01') > unix_timestamp(cast('2010-12-31 23:59:59' as timestamp)),
      |  date '2011-01-01' = cast(timestamp('2011-01-01 23:24:25') as date),
      |  '2011-01-01' = cast(d as string),
      |  '2011-01-01' = cast(date '2011-01-01' as string)
      |from date_1 limit 1;
      |
      |select
      |  date('2001-01-28'),
      |  date('2001-02-28'),
      |  date('2001-03-28'),
      |  date('2001-04-28'),
      |  date('2001-05-28'),
      |  date('2001-06-28'),
      |  date('2001-07-28'),
      |  date('2001-08-28'),
      |  date('2001-09-28'),
      |  date('2001-10-28'),
      |  date('2001-11-28'),
      |  date('2001-12-28')
      |from date_1 limit 1;
      |
      |select
      |  unix_timestamp(date('2001-01-28')) = unix_timestamp(cast('2001-01-28 0:0:0' as timestamp)),
      |  unix_timestamp(date('2001-02-28')) = unix_timestamp(cast('2001-02-28 0:0:0' as timestamp)),
      |  unix_timestamp(date('2001-03-28')) = unix_timestamp(cast('2001-03-28 0:0:0' as timestamp)),
      |  unix_timestamp(date('2001-04-28')) = unix_timestamp(cast('2001-04-28 0:0:0' as timestamp)),
      |  unix_timestamp(date('2001-05-28')) = unix_timestamp(cast('2001-05-28 0:0:0' as timestamp)),
      |  unix_timestamp(date('2001-06-28')) = unix_timestamp(cast('2001-06-28 0:0:0' as timestamp)),
      |  unix_timestamp(date('2001-07-28')) = unix_timestamp(cast('2001-07-28 0:0:0' as timestamp)),
      |  unix_timestamp(date('2001-08-28')) = unix_timestamp(cast('2001-08-28 0:0:0' as timestamp)),
      |  unix_timestamp(date('2001-09-28')) = unix_timestamp(cast('2001-09-28 0:0:0' as timestamp)),
      |  unix_timestamp(date('2001-10-28')) = unix_timestamp(cast('2001-10-28 0:0:0' as timestamp)),
      |  unix_timestamp(date('2001-11-28')) = unix_timestamp(cast('2001-11-28 0:0:0' as timestamp)),
      |  unix_timestamp(date('2001-12-28')) = unix_timestamp(cast('2001-12-28 0:0:0' as timestamp))
      |from date_1 limit 1;
      |
      |drop table if exists date_1;
    """.stripMargin)

  createQueryTest("date_4",
    """
      |set hive.fetch.task.conversion=more;
      |
      |drop table if exists date_4;
      |
      |create table date_4 (d date);
      |alter table date_4 set serde 'org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe';
      |
      |-- Test date literal syntax
      |insert overwrite table date_4
      |  select date '2011-01-01' from src tablesample (1 rows);
      |select d, date '2011-01-01' from date_4 limit 1;
      |
      |drop table if exists date_4;
    """.stripMargin)

  createQueryTest("date_join1",
    """
      |drop table if exists date_join1;
      |
      |create table date_join1 (
      |  ORIGIN_CITY_NAME string,
      |  DEST_CITY_NAME string,
      |  FL_DATE date,
      |  ARR_DELAY float,
      |  FL_NUM int
      |);
      |
      |LOAD DATA LOCAL INPATH '../../data/files/flights_join.txt' OVERWRITE INTO TABLE date_join1;
      |
      |-- Note that there are 2 rows with date 2000-11-28, so we should expect 4 rows with that date in the join results
      |select t1.fl_num, t1.fl_date, t2.fl_num, t2.fl_date
      |  from date_join1 t1
      |  join date_join1 t2
      |  on (t1.fl_date = t2.fl_date);
      |
      |drop table if exists date_join1;
    """.stripMargin)

  createQueryTest("date_serde",
    """
      |drop table if exists date_serde_regex;
      |drop table if exists date_serde_lb;
      |drop table if exists date_serde_ls;
      |drop table if exists date_serde_c;
      |drop table if exists date_serde_lbc;
      |drop table if exists date_serde_orc;
      |
      |
      |--
      |-- RegexSerDe
      |--
      |create table date_serde_regex (
      |  ORIGIN_CITY_NAME string,
      |  DEST_CITY_NAME string,
      |  FL_DATE date,
      |  ARR_DELAY float,
      |  FL_NUM int
      |)
      |row format serde 'org.apache.hadoop.hive.serde2.RegexSerDe'
      |with serdeproperties (
      |  "input.regex" = "([^]*)([^]*)([^]*)([^]*)([0-9]*)"
      |)
      |stored as textfile;
      |
      |load data local inpath '../../data/files/flights_tiny.txt.1' overwrite into table date_serde_regex;
      |
      |select * from date_serde_regex;
      |select fl_date, count(*) from date_serde_regex group by fl_date;
      |
      |--
      |-- LazyBinary
      |--
      |create table date_serde_lb (
      |  c1 date,
      |  c2 int
      |);
      |alter table date_serde_lb set serde 'org.apache.hadoop.hive.serde2.lazybinary.LazyBinarySerDe';
      |
      |insert overwrite table date_serde_lb
      |  select fl_date, fl_num from date_serde_regex limit 1;
      |
      |select * from date_serde_lb;
      |select c1, sum(c2) from date_serde_lb group by c1;
      |
      |--
      |-- LazySimple
      |--
      |create table date_serde_ls (
      |  c1 date,
      |  c2 int
      |);
      |alter table date_serde_ls set serde 'org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe';
      |
      |insert overwrite table date_serde_ls
      |  select c1, c2 from date_serde_lb limit 1;
      |
      |select * from date_serde_ls;
      |select c1, sum(c2) from date_serde_ls group by c1;
      |
      |--
      |-- Columnar
      |--
      |create table date_serde_c (
      |  c1 date,
      |  c2 int
      |) stored as rcfile;
      |alter table date_serde_c set serde 'org.apache.hadoop.hive.serde2.columnar.ColumnarSerDe';
      |
      |insert overwrite table date_serde_c
      |  select c1, c2 from date_serde_ls limit 1;
      |
      |select * from date_serde_c;
      |select c1, sum(c2) from date_serde_c group by c1;
      |
      |--
      |-- LazyBinaryColumnar
      |--
      |create table date_serde_lbc (
      |  c1 date,
      |  c2 int
      |) stored as rcfile;
      |alter table date_serde_lbc set serde 'org.apache.hadoop.hive.serde2.columnar.LazyBinaryColumnarSerDe';
      |
      |insert overwrite table date_serde_lbc
      |  select c1, c2 from date_serde_c limit 1;
      |
      |select * from date_serde_lbc;
      |select c1, sum(c2) from date_serde_lbc group by c1;
      |
      |--
      |-- ORC
      |--
      |create table date_serde_orc (
      |  c1 date,
      |  c2 int
      |) stored as orc;
      |alter table date_serde_orc set serde 'org.apache.hadoop.hive.ql.io.orc.OrcSerde';
      |
      |insert overwrite table date_serde_orc
      |  select c1, c2 from date_serde_lbc limit 1;
      |
      |select * from date_serde_orc;
      |select c1, sum(c2) from date_serde_orc group by c1;
      |
      |
      |
      |drop table if exists date_serde_regex;
      |drop table if exists date_serde_lb;
      |drop table if exists date_serde_ls;
      |drop table if exists date_serde_c;
      |drop table if exists date_serde_lbc;
      |drop table if exists date_serde_orc;
    """.stripMargin)

  createQueryTest("insert_compressed",
    """
      |set hive.exec.compress.output=true;
      |
      |drop table if exists insert_compressed;
      |create table insert_compressed (key int, value string);
      |
      |insert overwrite table insert_compressed select * from src;
      |select count(*) from insert_compressed;
      |
      |insert into table insert_compressed select * from src;
      |select count(*) from insert_compressed;
      |
      |insert into table insert_compressed select * from src;
      |select count(*) from insert_compressed;
      |
      |drop table if exists insert_compressed;
    """.stripMargin)

  createQueryTest("lateral_view_cp",
    """
      |--HIVE 3226
      |drop table if exists array_valued_src;
      |create table array_valued_src (key string, value array<string>);
      |insert overwrite table array_valued_src select key, array(value) from src;
      |
      |-- replace sel(*) to sel(exprs) for reflecting CP result properly
      |explain select count(val) from (select a.key as key, b.value as array_val from src a join array_valued_src b on a.key=b.key) i lateral view explode (array_val) c as val;
      |select count(val) from (select a.key as key, b.value as array_val from src a join array_valued_src b on a.key=b.key) i lateral view explode (array_val) c as val;
    """.stripMargin)

  createQueryTest("leftsemijoin",
    """
      |drop table if exists sales;
      |drop table if exists things;
      |
      |set hive.input.format=org.apache.hadoop.hive.ql.io.HiveInputFormat;
      |
      |CREATE TABLE sales (name STRING, id INT)
      |ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t';
      |
      |CREATE TABLE things (id INT, name STRING) partitioned by (ds string)
      |ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t';
      |
      |load data local inpath '../../data/files/sales.txt' INTO TABLE sales;
      |load data local inpath '../../data/files/things.txt' INTO TABLE things partition(ds='2011-10-23');
      |load data local inpath '../../data/files/things2.txt' INTO TABLE things partition(ds='2011-10-24');
      |
      |SELECT name,id FROM sales ORDER BY name ASC, id ASC;
      |
      |SELECT id,name FROM things ORDER BY id ASC, name ASC;
      |
      |SELECT name,id FROM sales LEFT SEMI JOIN things ON (sales.id = things.id) ORDER BY name ASC, id ASC;
      |
      |drop table if exists sales;
      |drop table if exists things;
    """.stripMargin)

  createQueryTest("mapjoin_subquery2",
    """
      |drop table if exists x;
      |drop table if exists y;
      |drop table if exists z;
      |
      |CREATE TABLE x (name STRING, id INT)
      |ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t';
      |
      |CREATE TABLE y (id INT, name STRING)
      |ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t';
      |
      |CREATE TABLE z (id INT, name STRING)
      |ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t';
      |
      |load data local inpath '../../data/files/x.txt' INTO TABLE x;
      |load data local inpath '../../data/files/y.txt' INTO TABLE y;
      |load data local inpath '../../data/files/z.txt' INTO TABLE z;
      |
      |set hive.auto.convert.join=true;
      |set hive.auto.convert.join.noconditionaltask=true;
      |set hive.auto.convert.join.noconditionaltask.size=10000;
      |
      |-- Since the inputs are small, it should be automatically converted to mapjoin
      |
      |EXPLAIN
      |SELECT subq.key1, subq.value1, subq.key2, subq.value2, z.id, z.name
      |FROM
      |(SELECT x.id as key1, x.name as value1, y.id as key2, y.name as value2
      | FROM y JOIN x ON (x.id = y.id)) subq
      | JOIN z ON (subq.key1 = z.id);
      |
      |SELECT subq.key1, subq.value1, subq.key2, subq.value2, z.id, z.name
      |FROM
      |(SELECT x.id as key1, x.name as value1, y.id as key2, y.name as value2
      | FROM y JOIN x ON (x.id = y.id)) subq
      | JOIN z ON (subq.key1 = z.id);
      |
      |drop table if exists x;
      |drop table if exists y;
      |drop table if exists z;
    """.stripMargin)

  createQueryTest("nomore_ambiguous_table_col",
    """
      |-- was negative/ambiguous_table_col.q
      |
      |drop table if exists ambiguous;
      |create table ambiguous (key string, value string);
      |
      |FROM src key
      |INSERT OVERWRITE TABLE ambiguous SELECT key.key, key.value WHERE key.value < 'val_100';
      |
      |drop table if exists ambiguous;
    """.stripMargin)

  createQueryTest("partition_date",
    """
      |drop table if exists partition_date_1;
      |
      |create table partition_date_1 (key string, value string) partitioned by (dt date, region string);
      |
      |insert overwrite table partition_date_1 partition(dt='2000-01-01', region= '1')
      |  select * from src tablesample (10 rows);
      |insert overwrite table partition_date_1 partition(dt='2000-01-01', region= '2')
      |  select * from src tablesample (5 rows);
      |insert overwrite table partition_date_1 partition(dt='2013-12-10', region= '2020-20-20')
      |  select * from src tablesample (5 rows);
      |insert overwrite table partition_date_1 partition(dt='2013-08-08', region= '1')
      |  select * from src tablesample (20 rows);
      |insert overwrite table partition_date_1 partition(dt='2013-08-08', region= '10')
      |  select * from src tablesample (11 rows);
      |
      |
      |select distinct dt from partition_date_1;
      |select * from partition_date_1 where dt = '2000-01-01' and region = '2' order by key,value;
      |
      |-- 15
      |select count(*) from partition_date_1 where dt = date '2000-01-01';
      |-- 15.  Also try with string value in predicate
      |select count(*) from partition_date_1 where dt = '2000-01-01';
      |-- 5
      |select count(*) from partition_date_1 where dt = date '2000-01-01' and region = '2';
      |-- 11
      |select count(*) from partition_date_1 where dt = date '2013-08-08' and region = '10';
      |-- 30
      |select count(*) from partition_date_1 where region = '1';
      |-- 0
      |select count(*) from partition_date_1 where dt = date '2000-01-01' and region = '3';
      |-- 0
      |select count(*) from partition_date_1 where dt = date '1999-01-01';
      |
      |-- Try other comparison operations
      |
      |-- 20
      |select count(*) from partition_date_1 where dt > date '2000-01-01' and region = '1';
      |-- 10
      |select count(*) from partition_date_1 where dt < date '2000-01-02' and region = '1';
      |-- 20
      |select count(*) from partition_date_1 where dt >= date '2000-01-02' and region = '1';
      |-- 10
      |select count(*) from partition_date_1 where dt <= date '2000-01-01' and region = '1';
      |-- 20
      |select count(*) from partition_date_1 where dt <> date '2000-01-01' and region = '1';
      |-- 10
      |select count(*) from partition_date_1 where dt between date '1999-12-30' and date '2000-01-03' and region = '1';
      |
      |
      |-- Try a string key with date-like strings
      |
      |-- 5
      |select count(*) from partition_date_1 where region = '2020-20-20';
      |-- 5
      |select count(*) from partition_date_1 where region > '2010-01-01';
      |
      |drop table if exists partition_date_1;
    """.stripMargin)

  createQueryTest("partition_varchar1",
    """
      |drop table if exists partition_varchar_1;
      |
      |create table partition_varchar_1 (key string, value varchar(20)) partitioned by (dt varchar(10), region int);
      |
      |insert overwrite table partition_varchar_1 partition(dt='2000-01-01', region=1)
      |  select * from src tablesample (10 rows);
      |insert overwrite table partition_varchar_1 partition(dt='2000-01-01', region=2)
      |  select * from src tablesample (5 rows);
      |insert overwrite table partition_varchar_1 partition(dt='2013-08-08', region=1)
      |  select * from src tablesample (20 rows);
      |insert overwrite table partition_varchar_1 partition(dt='2013-08-08', region=10)
      |  select * from src tablesample (11 rows);
      |
      |select distinct dt from partition_varchar_1;
      |select * from partition_varchar_1 where dt = '2000-01-01' and region = 2 order by key,value;
      |
      |-- 15
      |select count(*) from partition_varchar_1 where dt = '2000-01-01';
      |-- 5
      |select count(*) from partition_varchar_1 where dt = '2000-01-01' and region = 2;
      |-- 11
      |select count(*) from partition_varchar_1 where dt = '2013-08-08' and region = 10;
      |-- 30
      |select count(*) from partition_varchar_1 where region = 1;
      |-- 0
      |select count(*) from partition_varchar_1 where dt = '2000-01-01' and region = 3;
      |-- 0
      |select count(*) from partition_varchar_1 where dt = '1999-01-01';
      |
      |-- Try other comparison operations
      |
      |-- 20
      |select count(*) from partition_varchar_1 where dt > '2000-01-01' and region = 1;
      |-- 10
      |select count(*) from partition_varchar_1 where dt < '2000-01-02' and region = 1;
      |-- 20
      |select count(*) from partition_varchar_1 where dt >= '2000-01-02' and region = 1;
      |-- 10
      |select count(*) from partition_varchar_1 where dt <= '2000-01-01' and region = 1;
      |-- 20
      |select count(*) from partition_varchar_1 where dt <> '2000-01-01' and region = 1;
      |
      |drop table if exists partition_varchar_1;
    """.stripMargin)

  createQueryTest("ppd_repeated_alias",
    """
      |drop table if exists pokes;
      |drop table if exists pokes2;
      |create table pokes (foo int, bar int, blah int);
      |create table pokes2 (foo int, bar int, blah int);
      |
      |-- Q1: predicate should not be pushed on the right side of a left outer join
      |explain
      |SELECT a.foo as foo1, b.foo as foo2, b.bar
      |FROM pokes a LEFT OUTER JOIN pokes2 b
      |ON a.foo=b.foo
      |WHERE b.bar=3;
      |
      |-- Q2: predicate should not be pushed on the right side of a left outer join
      |explain
      |SELECT * FROM
      |    (SELECT a.foo as foo1, b.foo as foo2, b.bar
      |    FROM pokes a LEFT OUTER JOIN pokes2 b
      |    ON a.foo=b.foo) a
      |WHERE a.bar=3;
      |
      |-- Q3: predicate should be pushed
      |explain
      |SELECT * FROM
      |    (SELECT a.foo as foo1, b.foo as foo2, a.bar
      |    FROM pokes a JOIN pokes2 b
      |    ON a.foo=b.foo) a
      |WHERE a.bar=3;
      |
      |-- Q4: here, the filter c.bar should be created under the first join but above the second
      |explain select c.foo, d.bar from (select c.foo, b.bar, c.blah from pokes c left outer join pokes b on c.foo=b.foo) c left outer join pokes d where d.foo=1 and c.bar=2;
      |
      |drop table if exists pokes;
      |drop table if exists pokes2;
    """.stripMargin)

  createQueryTest("push_or",
    """
      |drop table if exists push_or;
      |
      |create table push_or (key int, value string) partitioned by (ds string);
      |
      |insert overwrite table push_or partition (ds='2000-04-08') select * from src where key < 20 order by key;
      |insert overwrite table push_or partition (ds='2000-04-09') select * from src where key < 20 order by key;
      |
      |explain extended select key, value, ds from push_or where ds='2000-04-09' or key=5 order by key, ds;
      |select key, value, ds from push_or where ds='2000-04-09' or key=5 order by key, ds;
    """.stripMargin)

  createQueryTest("reducesink_dedup",
    """
      |DROP TABLE IF EXISTS part;
      |
      |-- data setup
      |CREATE TABLE part(
      |    p_partkey INT,
      |    p_name STRING,
      |    p_mfgr STRING,
      |    p_brand STRING,
      |    p_type STRING,
      |    p_size INT,
      |    p_container STRING,
      |    p_retailprice DOUBLE,
      |    p_comment STRING
      |);
      |
      |
      |select p_name
      |from (select p_name from part distribute by 1 sort by 1) p
      |distribute by 1 sort by 1
      |;
    """.stripMargin)

  createQueryTest("subquery_in",
    """
      |DROP TABLE IF EXISTS part;
      |
      |-- data setup
      |CREATE TABLE part(
      |    p_partkey INT,
      |    p_name STRING,
      |    p_mfgr STRING,
      |    p_brand STRING,
      |    p_type STRING,
      |    p_size INT,
      |    p_container STRING,
      |    p_retailprice DOUBLE,
      |    p_comment STRING
      |);
      |
      |LOAD DATA LOCAL INPATH '../../data/files/part_tiny.txt' overwrite into table part;
      |
      |DROP TABLE IF EXISTS lineitem;
      |CREATE TABLE lineitem (L_ORDERKEY      INT,
      |                                L_PARTKEY       INT,
      |                                L_SUPPKEY       INT,
      |                                L_LINENUMBER    INT,
      |                                L_QUANTITY      DOUBLE,
      |                                L_EXTENDEDPRICE DOUBLE,
      |                                L_DISCOUNT      DOUBLE,
      |                                L_TAX           DOUBLE,
      |                                L_RETURNFLAG    STRING,
      |                                L_LINESTATUS    STRING,
      |                                l_shipdate      STRING,
      |                                L_COMMITDATE    STRING,
      |                                L_RECEIPTDATE   STRING,
      |                                L_SHIPINSTRUCT  STRING,
      |                                L_SHIPMODE      STRING,
      |                                L_COMMENT       STRING)
      |ROW FORMAT DELIMITED
      |FIELDS TERMINATED BY '|';
      |
      |LOAD DATA LOCAL INPATH '../../data/files/lineitem.txt' OVERWRITE INTO TABLE lineitem;
      |
      |-- non agg, non corr
      |explain
      | select *
      |from src
      |where src.key in (select key from src s1 where s1.key > '9')
      |;
      |
      |select *
      |from src
      |where src.key in (select key from src s1 where s1.key > '9')
      |order by key
      |;
      |
      |-- non agg, corr
      |explain
      |select *
      |from src b
      |where b.key in
      |        (select a.key
      |         from src a
      |         where b.value = a.value and a.key > '9'
      |        )
      |;
      |
      |select *
      |from src b
      |where b.key in
      |        (select a.key
      |         from src a
      |         where b.value = a.value and a.key > '9'
      |        )
      |order by b.key
      |;
      |
      |-- agg, non corr
      |explain
      |select p_name, p_size
      |from
      |part where part.p_size in
      |	(select avg(p_size)
      |	 from (select p_size, rank() over(partition by p_mfgr order by p_size) as r from part) a
      |	 where r <= 2
      |	)
      |;
      |select p_name, p_size
      |from
      |part where part.p_size in
      |	(select avg(p_size)
      |	 from (select p_size, rank() over(partition by p_mfgr order by p_size) as r from part) a
      |	 where r <= 2
      |	)
      |order by p_name
      |;
      |
      |-- agg, corr
      |explain
      |select p_mfgr, p_name, p_size
      |from part b where b.p_size in
      |	(select min(p_size)
      |	 from (select p_mfgr, p_size, rank() over(partition by p_mfgr order by p_size) as r from part) a
      |	 where r <= 2 and b.p_mfgr = a.p_mfgr
      |	)
      |;
      |
      |select p_mfgr, p_name, p_size
      |from part b where b.p_size in
      |	(select min(p_size)
      |	 from (select p_mfgr, p_size, rank() over(partition by p_mfgr order by p_size) as r from part) a
      |	 where r <= 2 and b.p_mfgr = a.p_mfgr
      |	)
      |order by p_mfgr, p_name, p_size
      |;
      |
      |-- distinct, corr
      |explain
      |select *
      |from src b
      |where b.key in
      |        (select distinct a.key
      |         from src a
      |         where b.value = a.value and a.key > '9'
      |        )
      |;
      |
      |select *
      |from src b
      |where b.key in
      |        (select distinct a.key
      |         from src a
      |         where b.value = a.value and a.key > '9'
      |        )
      |order by b.key
      |;
      |
      |-- non agg, non corr, windowing
      |select p_mfgr, p_name, p_size
      |from part
      |where part.p_size in
      |  (select first_value(p_size) over(partition by p_mfgr order by p_size) from part)
      |order by p_mfgr, p_name, p_size
      |;
      |
      |-- non agg, non corr, with join in Parent Query
      |explain
      |select p.p_partkey, li.l_suppkey
      |from (select distinct l_partkey as p_partkey from lineitem) p join lineitem li on p.p_partkey = li.l_partkey
      |where li.l_linenumber = 1 and
      | li.l_orderkey in (select l_orderkey from lineitem where l_shipmode = 'AIR')
      |;
      |
      |select p.p_partkey, li.l_suppkey
      |from (select distinct l_partkey as p_partkey from lineitem) p join lineitem li on p.p_partkey = li.l_partkey
      |where li.l_linenumber = 1 and
      | li.l_orderkey in (select l_orderkey from lineitem where l_shipmode = 'AIR')
      |order by p.p_partkey, li.l_suppkey
      |;
      |
      |-- non agg, corr, with join in Parent Query
      |select p.p_partkey, li.l_suppkey
      |from (select distinct l_partkey as p_partkey from lineitem) p join lineitem li on p.p_partkey = li.l_partkey
      |where li.l_linenumber = 1 and
      | li.l_orderkey in (select l_orderkey from lineitem where l_shipmode = 'AIR' and l_linenumber = li.l_linenumber)
      |order by p.p_partkey, li.l_suppkey
      |;
    """.stripMargin)

  createQueryTest("subquery_notin_having",
    """
      |DROP TABLE IF EXISTS part;
      |
      |-- data setup
      |CREATE TABLE part(
      |    p_partkey INT,
      |    p_name STRING,
      |    p_mfgr STRING,
      |    p_brand STRING,
      |    p_type STRING,
      |    p_size INT,
      |    p_container STRING,
      |    p_retailprice DOUBLE,
      |    p_comment STRING
      |);
      |
      |LOAD DATA LOCAL INPATH '../../data/files/part_tiny.txt' overwrite into table part;
      |
      |
      |-- non agg, non corr
      |explain
      |select key, count(*)
      |from src
      |group by key
      |having key not in
      |  ( select key  from src s1
      |    where s1.key > '12'
      |  )
      |;
      |
      |-- non agg, corr
      |explain
      |select b.p_mfgr, min(p_retailprice)
      |from part b
      |group by b.p_mfgr
      |having b.p_mfgr not in
      |  (select p_mfgr
      |  from (select p_mfgr, min(p_retailprice) l, max(p_retailprice) r, avg(p_retailprice) a from part group by p_mfgr) a
      |  where min(p_retailprice) = l and r - l > 600
      |  )
      |;
      |
      |select b.p_mfgr, min(p_retailprice)
      |from part b
      |group by b.p_mfgr
      |having b.p_mfgr not in
      |  (select p_mfgr
      |  from (select p_mfgr, min(p_retailprice) l, max(p_retailprice) r, avg(p_retailprice) a from part group by p_mfgr) a
      |  where min(p_retailprice) = l and r - l > 600
      |  )
      |;
      |
      |-- agg, non corr
      |explain
      |select b.p_mfgr, min(p_retailprice)
      |from part b
      |group by b.p_mfgr
      |having b.p_mfgr not in
      |  (select p_mfgr
      |  from part a
      |  group by p_mfgr
      |  having max(p_retailprice) - min(p_retailprice) > 600
      |  )
      |;
      |
      |select b.p_mfgr, min(p_retailprice)
      |from part b
      |group by b.p_mfgr
      |having b.p_mfgr not in
      |  (select p_mfgr
      |  from part a
      |  group by p_mfgr
      |  having max(p_retailprice) - min(p_retailprice) > 600
      |  )
      |;
    """.stripMargin)
  // scalastyle:on
}

// for SPARK-2180 test
case class HavingRow(key: Int, value: String, attr: Int)

case class LogEntry(filename: String, message: String)
case class LogFile(name: String)
