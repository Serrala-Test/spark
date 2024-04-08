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

import scala.collection.immutable.Seq
import scala.jdk.CollectionConverters.MapHasAsJava

import org.apache.spark.SparkException
import org.apache.spark.sql.catalyst.{ExtendedAnalysisException, InternalRow}
import org.apache.spark.sql.catalyst.analysis.{CollationKey, RewriteGroupByCollation}
import org.apache.spark.sql.catalyst.expressions.{BindReferences, UnsafeProjection}
// import org.apache.spark.sql.catalyst.expressions.codegen.CodegenContext
import org.apache.spark.sql.catalyst.expressions.{Alias, AttributeReference}
import org.apache.spark.sql.catalyst.plans.logical.{Aggregate, LocalRelation}
import org.apache.spark.sql.catalyst.util.CollationFactory
import org.apache.spark.sql.connector.{DatasourceV2SQLBase, FakeV2ProviderWithCustomSchema}
import org.apache.spark.sql.connector.catalog.{Identifier, InMemoryTable}
import org.apache.spark.sql.connector.catalog.CatalogV2Implicits.CatalogHelper
import org.apache.spark.sql.connector.catalog.CatalogV2Util.withDefaultOwnership
import org.apache.spark.sql.errors.DataTypeErrors.toSQLType
import org.apache.spark.sql.execution.adaptive.AdaptiveSparkPlanHelper
import org.apache.spark.sql.execution.aggregate.{HashAggregateExec, ObjectHashAggregateExec}
import org.apache.spark.sql.execution.joins.{BroadcastHashJoinExec, SortMergeJoinExec}
import org.apache.spark.sql.internal.SqlApiConf
import org.apache.spark.sql.types.{MapType, StringType, StructField, StructType}
import org.apache.spark.unsafe.types.UTF8String

class CollationSuite extends DatasourceV2SQLBase with AdaptiveSparkPlanHelper {
  protected val v2Source = classOf[FakeV2ProviderWithCustomSchema].getName

  private val collationPreservingSources = Seq("parquet")
  private val collationNonPreservingSources = Seq("orc", "csv", "json", "text")
  private val allFileBasedDataSources = collationPreservingSources ++  collationNonPreservingSources

  test("collate returns proper type") {
    Seq("utf8_binary", "utf8_binary_lcase", "unicode", "unicode_ci").foreach { collationName =>
      checkAnswer(sql(s"select 'aaa' collate $collationName"), Row("aaa"))
      val collationId = CollationFactory.collationNameToId(collationName)
      assert(sql(s"select 'aaa' collate $collationName").schema(0).dataType
        == StringType(collationId))
    }
  }

  test("collation name is case insensitive") {
    Seq("uTf8_BiNaRy", "uTf8_BiNaRy_Lcase", "uNicOde", "UNICODE_ci").foreach { collationName =>
      checkAnswer(sql(s"select 'aaa' collate $collationName"), Row("aaa"))
      val collationId = CollationFactory.collationNameToId(collationName)
      assert(sql(s"select 'aaa' collate $collationName").schema(0).dataType
        == StringType(collationId))
    }
  }

  test("collation expression returns name of collation") {
    Seq("utf8_binary", "utf8_binary_lcase", "unicode", "unicode_ci").foreach { collationName =>
      checkAnswer(
        sql(s"select collation('aaa' collate $collationName)"), Row(collationName.toUpperCase()))
    }
  }

  test("collate function syntax") {
    assert(sql(s"select collate('aaa', 'utf8_binary')").schema(0).dataType == StringType(0))
    assert(sql(s"select collate('aaa', 'utf8_binary_lcase')").schema(0).dataType == StringType(1))
  }

  test("collate function syntax invalid arg count") {
    Seq("'aaa','a','b'", "'aaa'", "", "'aaa'").foreach(args => {
      val paramCount = if (args == "") 0 else args.split(',').length.toString
      checkError(
        exception = intercept[AnalysisException] {
          sql(s"select collate($args)")
        },
        errorClass = "WRONG_NUM_ARGS.WITHOUT_SUGGESTION",
        sqlState = "42605",
        parameters = Map(
          "functionName" -> "`collate`",
          "expectedNum" -> "2",
          "actualNum" -> paramCount.toString,
          "docroot" -> "https://spark.apache.org/docs/latest"),
        context = ExpectedContext(fragment = s"collate($args)", start = 7, stop = 15 + args.length)
      )
    })
  }

  test("collate function invalid collation data type") {
    checkError(
      exception = intercept[AnalysisException](sql("select collate('abc', 123)")),
      errorClass = "UNEXPECTED_INPUT_TYPE",
      sqlState = "42K09",
      Map(
        "functionName" -> "`collate`",
        "paramIndex" -> "first",
        "inputSql" -> "\"123\"",
        "inputType" -> "\"INT\"",
        "requiredType" -> "\"STRING\""),
      context = ExpectedContext(fragment = s"collate('abc', 123)", start = 7, stop = 25)
    )
  }

  test("NULL as collation name") {
    checkError(
      exception = intercept[AnalysisException] {
        sql("select collate('abc', cast(null as string))") },
      errorClass = "DATATYPE_MISMATCH.UNEXPECTED_NULL",
      sqlState = "42K09",
      Map("exprName" -> "`collation`", "sqlExpr" -> "\"CAST(NULL AS STRING)\""),
      context = ExpectedContext(
        fragment = s"collate('abc', cast(null as string))", start = 7, stop = 42)
    )
  }

  test("collate function invalid input data type") {
    checkError(
      exception = intercept[ExtendedAnalysisException] { sql(s"select collate(1, 'UTF8_BINARY')") },
      errorClass = "DATATYPE_MISMATCH.UNEXPECTED_INPUT_TYPE",
      sqlState = "42K09",
      parameters = Map(
        "sqlExpr" -> "\"collate(1)\"",
        "paramIndex" -> "first",
        "inputSql" -> "\"1\"",
        "inputType" -> "\"INT\"",
        "requiredType" -> "\"STRING_ANY_COLLATION\""),
      context = ExpectedContext(
        fragment = s"collate(1, 'UTF8_BINARY')", start = 7, stop = 31))
  }

  test("collation expression returns default collation") {
    checkAnswer(sql(s"select collation('aaa')"), Row("UTF8_BINARY"))
  }

  test("invalid collation name throws exception") {
    checkError(
      exception = intercept[SparkException] { sql("select 'aaa' collate UTF8_BS") },
      errorClass = "COLLATION_INVALID_NAME",
      sqlState = "42704",
      parameters = Map("proposal" -> "UTF8_BINARY", "collationName" -> "UTF8_BS"))
  }

  test("disable bucketing on collated string column") {
    def createTable(bucketColumns: String*): Unit = {
      val tableName = "test_partition_tbl"
      withTable(tableName) {
        sql(
          s"""
             |CREATE TABLE $tableName
             |(id INT, c1 STRING COLLATE UNICODE, c2 string)
             |USING parquet
             |CLUSTERED BY (${bucketColumns.mkString(",")})
             |INTO 4 BUCKETS""".stripMargin
        )
      }
    }
    // should work fine on default collated columns
    createTable("id")
    createTable("c2")
    createTable("id", "c2")

    Seq(Seq("c1"), Seq("c1", "id"), Seq("c1", "c2")).foreach { bucketColumns =>
      checkError(
        exception = intercept[AnalysisException] {
          createTable(bucketColumns: _*)
        },
        errorClass = "INVALID_BUCKET_COLUMN_DATA_TYPE",
        parameters = Map("type" -> "\"STRING COLLATE UNICODE\"")
      );
    }
  }

  test("equality check respects collation") {
    Seq(
      ("utf8_binary", "aaa", "AAA", false),
      ("utf8_binary", "aaa", "aaa", true),
      ("utf8_binary_lcase", "aaa", "aaa", true),
      ("utf8_binary_lcase", "aaa", "AAA", true),
      ("utf8_binary_lcase", "aaa", "bbb", false),
      ("unicode", "aaa", "aaa", true),
      ("unicode", "aaa", "AAA", false),
      ("unicode_CI", "aaa", "aaa", true),
      ("unicode_CI", "aaa", "AAA", true),
      ("unicode_CI", "aaa", "bbb", false)
    ).foreach {
      case (collationName, left, right, expected) =>
        checkAnswer(
          sql(s"select '$left' collate $collationName = '$right' collate $collationName"),
          Row(expected))
        checkAnswer(
          sql(s"select collate('$left', '$collationName') = collate('$right', '$collationName')"),
          Row(expected))
    }
  }

  test("comparisons respect collation") {
    Seq(
      ("utf8_binary", "AAA", "aaa", true),
      ("utf8_binary", "aaa", "aaa", false),
      ("utf8_binary", "aaa", "BBB", false),
      ("utf8_binary_lcase", "aaa", "aaa", false),
      ("utf8_binary_lcase", "AAA", "aaa", false),
      ("utf8_binary_lcase", "aaa", "bbb", true),
      ("unicode", "aaa", "aaa", false),
      ("unicode", "aaa", "AAA", true),
      ("unicode", "aaa", "BBB", true),
      ("unicode_CI", "aaa", "aaa", false),
      ("unicode_CI", "aaa", "AAA", false),
      ("unicode_CI", "aaa", "bbb", true)
    ).foreach {
      case (collationName, left, right, expected) =>
        checkAnswer(
          sql(s"select '$left' collate $collationName < '$right' collate $collationName"),
          Row(expected))
        checkAnswer(
          sql(s"select collate('$left', '$collationName') < collate('$right', '$collationName')"),
          Row(expected))
    }
  }

  test("checkCollation throws exception for incompatible collationIds") {
    val left: String = "abc" // collate with 'UNICODE_CI'
    val leftCollationName: String = "UNICODE_CI";
    var right: String = null // collate with 'UNICODE'
    val rightCollationName: String = "UNICODE";
    // contains
    right = left.substring(1, 2);
    checkError(
      exception = intercept[ExtendedAnalysisException] {
        spark.sql(s"SELECT contains(collate('$left', '$leftCollationName')," +
          s"collate('$right', '$rightCollationName'))")
      },
      errorClass = "DATATYPE_MISMATCH.COLLATION_MISMATCH",
      sqlState = "42K09",
      parameters = Map(
        "collationNameLeft" -> s"$leftCollationName",
        "collationNameRight" -> s"$rightCollationName",
        "sqlExpr" -> "\"contains(collate(abc), collate(b))\""
      ),
      context = ExpectedContext(fragment =
        s"contains(collate('abc', 'UNICODE_CI'),collate('b', 'UNICODE'))",
        start = 7, stop = 68)
    )
    // startsWith
    right = left.substring(0, 1);
    checkError(
      exception = intercept[ExtendedAnalysisException] {
        spark.sql(s"SELECT startsWith(collate('$left', '$leftCollationName')," +
          s"collate('$right', '$rightCollationName'))")
      },
      errorClass = "DATATYPE_MISMATCH.COLLATION_MISMATCH",
      sqlState = "42K09",
      parameters = Map(
        "collationNameLeft" -> s"$leftCollationName",
        "collationNameRight" -> s"$rightCollationName",
        "sqlExpr" -> "\"startswith(collate(abc), collate(a))\""
      ),
      context = ExpectedContext(fragment =
        s"startsWith(collate('abc', 'UNICODE_CI'),collate('a', 'UNICODE'))",
        start = 7, stop = 70)
    )
    // endsWith
    right = left.substring(2, 3);
    checkError(
      exception = intercept[ExtendedAnalysisException] {
        spark.sql(s"SELECT endsWith(collate('$left', '$leftCollationName')," +
          s"collate('$right', '$rightCollationName'))")
      },
      errorClass = "DATATYPE_MISMATCH.COLLATION_MISMATCH",
      sqlState = "42K09",
      parameters = Map(
        "collationNameLeft" -> s"$leftCollationName",
        "collationNameRight" -> s"$rightCollationName",
        "sqlExpr" -> "\"endswith(collate(abc), collate(c))\""
      ),
      context = ExpectedContext(fragment =
        s"endsWith(collate('abc', 'UNICODE_CI'),collate('c', 'UNICODE'))",
        start = 7, stop = 68)
    )
  }

  case class CollationTestCase[R](left: String, right: String, collation: String, expectedResult: R)

  test("Support contains string expression with Collation") {
    // Supported collations
    val checks = Seq(
      CollationTestCase("", "", "UTF8_BINARY", true),
      CollationTestCase("c", "", "UTF8_BINARY", true),
      CollationTestCase("", "c", "UTF8_BINARY", false),
      CollationTestCase("abcde", "c", "UTF8_BINARY", true),
      CollationTestCase("abcde", "C", "UTF8_BINARY", false),
      CollationTestCase("abcde", "bcd", "UTF8_BINARY", true),
      CollationTestCase("abcde", "BCD", "UTF8_BINARY", false),
      CollationTestCase("abcde", "fgh", "UTF8_BINARY", false),
      CollationTestCase("abcde", "FGH", "UTF8_BINARY", false),
      CollationTestCase("", "", "UNICODE", true),
      CollationTestCase("c", "", "UNICODE", true),
      CollationTestCase("", "c", "UNICODE", false),
      CollationTestCase("abcde", "c", "UNICODE", true),
      CollationTestCase("abcde", "C", "UNICODE", false),
      CollationTestCase("abcde", "bcd", "UNICODE", true),
      CollationTestCase("abcde", "BCD", "UNICODE", false),
      CollationTestCase("abcde", "fgh", "UNICODE", false),
      CollationTestCase("abcde", "FGH", "UNICODE", false),
      CollationTestCase("", "", "UTF8_BINARY_LCASE", true),
      CollationTestCase("c", "", "UTF8_BINARY_LCASE", true),
      CollationTestCase("", "c", "UTF8_BINARY_LCASE", false),
      CollationTestCase("abcde", "c", "UTF8_BINARY_LCASE", true),
      CollationTestCase("abcde", "C", "UTF8_BINARY_LCASE", true),
      CollationTestCase("abcde", "bcd", "UTF8_BINARY_LCASE", true),
      CollationTestCase("abcde", "BCD", "UTF8_BINARY_LCASE", true),
      CollationTestCase("abcde", "fgh", "UTF8_BINARY_LCASE", false),
      CollationTestCase("abcde", "FGH", "UTF8_BINARY_LCASE", false),
      CollationTestCase("", "", "UNICODE_CI", true),
      CollationTestCase("c", "", "UNICODE_CI", true),
      CollationTestCase("", "c", "UNICODE_CI", false),
      CollationTestCase("abcde", "c", "UNICODE_CI", true),
      CollationTestCase("abcde", "C", "UNICODE_CI", true),
      CollationTestCase("abcde", "bcd", "UNICODE_CI", true),
      CollationTestCase("abcde", "BCD", "UNICODE_CI", true),
      CollationTestCase("abcde", "fgh", "UNICODE_CI", false),
      CollationTestCase("abcde", "FGH", "UNICODE_CI", false)
    )
    checks.foreach(testCase => {
      checkAnswer(sql(s"SELECT contains(collate('${testCase.left}', '${testCase.collation}')," +
        s"collate('${testCase.right}', '${testCase.collation}'))"), Row(testCase.expectedResult))
    })
  }

  test("Support startsWith string expression with Collation") {
    // Supported collations
    val checks = Seq(
      CollationTestCase("abcde", "abc", "UTF8_BINARY", true),
      CollationTestCase("abcde", "ABC", "UTF8_BINARY", false),
      CollationTestCase("abcde", "abc", "UNICODE", true),
      CollationTestCase("abcde", "ABC", "UNICODE", false),
      CollationTestCase("abcde", "ABC", "UTF8_BINARY_LCASE", true),
      CollationTestCase("abcde", "bcd", "UTF8_BINARY_LCASE", false),
      CollationTestCase("abcde", "ABC", "UNICODE_CI", true),
      CollationTestCase("abcde", "bcd", "UNICODE_CI", false)
    )
    checks.foreach(testCase => {
      checkAnswer(sql(s"SELECT startswith(collate('${testCase.left}', '${testCase.collation}')," +
        s"collate('${testCase.right}', '${testCase.collation}'))"), Row(testCase.expectedResult))
    })
  }

  test("Support endsWith string expression with Collation") {
    // Supported collations
    val checks = Seq(
      CollationTestCase("abcde", "cde", "UTF8_BINARY", true),
      CollationTestCase("abcde", "CDE", "UTF8_BINARY", false),
      CollationTestCase("abcde", "cde", "UNICODE", true),
      CollationTestCase("abcde", "CDE", "UNICODE", false),
      CollationTestCase("abcde", "CDE", "UTF8_BINARY_LCASE", true),
      CollationTestCase("abcde", "bcd", "UTF8_BINARY_LCASE", false),
      CollationTestCase("abcde", "CDE", "UNICODE_CI", true),
      CollationTestCase("abcde", "bcd", "UNICODE_CI", false)
    )
    checks.foreach(testCase => {
      checkAnswer(sql(s"SELECT endswith(collate('${testCase.left}', '${testCase.collation}')," +
        s"collate('${testCase.right}', '${testCase.collation}'))"), Row(testCase.expectedResult))
    })
  }

  test("aggregates count respects collation") {
    Seq(
      ("unicode_ci", Seq("AA", "aa"), Seq(Row(2, "aa"))),
    ).foreach {
      case (collationName: String, input: Seq[String], expected: Seq[Row]) =>
        spark.conf.set("spark.sql.codegen.wholeStage", "false")
        checkAnswer(sql(
          s"""
          with t as (
          select collate(col1, '$collationName') as c
          from
          values ${input.map(s => s"('$s')").mkString(", ")}
        )
        SELECT COUNT(*), c FROM t GROUP BY c
        """), expected)
    }
  }

  test("hash agg is also used for non binary collations") {
    val tableNameNonBinary = "T_NON_BINARY"
    val tableNameBinary = "T_BINARY"
    withTable(tableNameNonBinary) {
      withTable(tableNameBinary) {
        sql(s"CREATE TABLE $tableNameNonBinary (c STRING COLLATE UTF8_BINARY_LCASE) USING PARQUET")
        sql(s"INSERT INTO $tableNameNonBinary VALUES ('aaa')")
        sql(s"CREATE TABLE $tableNameBinary (c STRING COLLATE UTF8_BINARY) USING PARQUET")
        sql(s"INSERT INTO $tableNameBinary VALUES ('aaa')")

        val dfNonBinary = sql(s"SELECT COUNT(*), c FROM $tableNameNonBinary GROUP BY c")
        assert(collectFirst(dfNonBinary.queryExecution.executedPlan) {
          case _: HashAggregateExec | _: ObjectHashAggregateExec => ()
        }.nonEmpty)

        val dfBinary = sql(s"SELECT COUNT(*), c FROM $tableNameBinary GROUP BY c")
        assert(collectFirst(dfBinary.queryExecution.executedPlan) {
          case _: HashAggregateExec | _: ObjectHashAggregateExec => ()
        }.nonEmpty)
      }
    }
  }

  test("text writing to parquet with collation enclosed with backticks") {
    withTempPath{ path =>
      sql(s"select 'a' COLLATE `UNICODE`").write.parquet(path.getAbsolutePath)

      checkAnswer(
        spark.read.parquet(path.getAbsolutePath),
        Row("a"))
    }
  }

  test("create table with collation") {
    val tableName = "dummy_tbl"
    val collationName = "UTF8_BINARY_LCASE"
    val collationId = CollationFactory.collationNameToId(collationName)

    allFileBasedDataSources.foreach { format =>
      withTable(tableName) {
        sql(
        s"""
           |CREATE TABLE $tableName (
           |  c1 STRING COLLATE $collationName
           |)
           |USING $format
           |""".stripMargin)

        sql(s"INSERT INTO $tableName VALUES ('aaa')")
        sql(s"INSERT INTO $tableName VALUES ('AAA')")

        checkAnswer(sql(s"SELECT DISTINCT COLLATION(c1) FROM $tableName"), Seq(Row(collationName)))
        assert(sql(s"select c1 FROM $tableName").schema.head.dataType == StringType(collationId))
      }
    }
  }

  test("write collated data to different data sources with dataframe api") {
    val collationName = "UNICODE_CI"

    allFileBasedDataSources.foreach { format =>
      withTempPath { path =>
        val df = sql(s"SELECT c COLLATE $collationName AS c FROM VALUES ('aaa') AS data(c)")
        df.write.format(format).save(path.getAbsolutePath)

        val readback = spark.read.format(format).load(path.getAbsolutePath)
        val readbackCollation = if (collationPreservingSources.contains(format)) {
          collationName
        } else {
          "UTF8_BINARY"
        }

        checkAnswer(readback, Row("aaa"))
        checkAnswer(
          readback.selectExpr(s"collation(${readback.columns.head})"),
          Row(readbackCollation))
      }
    }
  }

  test("add collated column with alter table") {
    val tableName = "alter_column_tbl"
    val defaultCollation = "UTF8_BINARY"
    val collationName = "UTF8_BINARY_LCASE"
    val collationId = CollationFactory.collationNameToId(collationName)

    withTable(tableName) {
      sql(
        s"""
           |CREATE TABLE $tableName (c1 STRING)
           |USING PARQUET
           |""".stripMargin)

      sql(s"INSERT INTO $tableName VALUES ('aaa')")
      sql(s"INSERT INTO $tableName VALUES ('AAA')")

      checkAnswer(sql(s"SELECT DISTINCT COLLATION(c1) FROM $tableName"),
        Seq(Row(defaultCollation)))

      sql(
        s"""
           |ALTER TABLE $tableName
           |ADD COLUMN c2 STRING COLLATE $collationName
           |""".stripMargin)

      sql(s"INSERT INTO $tableName VALUES ('aaa', 'aaa')")
      sql(s"INSERT INTO $tableName VALUES ('AAA', 'AAA')")

      checkAnswer(sql(s"SELECT DISTINCT COLLATION(c2) FROM $tableName"),
        Seq(Row(collationName)))
      assert(sql(s"select c2 FROM $tableName").schema.head.dataType == StringType(collationId))
    }
  }

  test("create v2 table with collation column") {
    val tableName = "testcat.table_name"
    val collationName = "UTF8_BINARY_LCASE"
    val collationId = CollationFactory.collationNameToId(collationName)

    withTable(tableName) {
      sql(
        s"""
           |CREATE TABLE $tableName (c1 string COLLATE $collationName)
           |USING $v2Source
           |""".stripMargin)

      val testCatalog = catalog("testcat").asTableCatalog
      val table = testCatalog.loadTable(Identifier.of(Array(), "table_name"))

      assert(table.name == tableName)
      assert(table.partitioning.isEmpty)
      assert(table.properties == withDefaultOwnership(Map("provider" -> v2Source)).asJava)
      assert(table.columns().head.dataType() == StringType(collationId))

      val rdd = spark.sparkContext.parallelize(table.asInstanceOf[InMemoryTable].rows)
      checkAnswer(spark.internalCreateDataFrame(rdd, table.schema), Seq.empty)

      sql(s"INSERT INTO $tableName VALUES ('a'), ('A')")

      checkAnswer(sql(s"SELECT DISTINCT COLLATION(c1) FROM $tableName"),
        Seq(Row(collationName)))
      assert(sql(s"select c1 FROM $tableName").schema.head.dataType == StringType(collationId))
    }
  }

  test("disable partition on collated string column") {
    def createTable(partitionColumns: String*): Unit = {
      val tableName = "test_partition_tbl"
      withTable(tableName) {
        sql(
          s"""
             |CREATE TABLE $tableName
             |(id INT, c1 STRING COLLATE UNICODE, c2 string)
             |USING parquet
             |PARTITIONED BY (${partitionColumns.mkString(",")})
             |""".stripMargin)
      }
    }

    // should work fine on non collated columns
    createTable("id")
    createTable("c2")
    createTable("id", "c2")

    Seq(Seq("c1"), Seq("c1", "id"), Seq("c1", "c2")).foreach { partitionColumns =>
      checkError(
        exception = intercept[AnalysisException] {
          createTable(partitionColumns: _*)
        },
        errorClass = "INVALID_PARTITION_COLUMN_DATA_TYPE",
        parameters = Map("type" -> "\"STRING COLLATE UNICODE\"")
      );
    }
  }

  test("shuffle respects collation") {
    val in = (('a' to 'z') ++ ('A' to 'Z')).map(_.toString * 3).map(Row.apply(_))

    val schema = StructType(StructField(
      "col",
      StringType(CollationFactory.collationNameToId("UTF8_BINARY_LCASE"))) :: Nil)
    val df = spark.createDataFrame(sparkContext.parallelize(in), schema)

    df.repartition(10, df.col("col")).foreachPartition(
      (rowIterator: Iterator[Row]) => {
        val partitionData = rowIterator.map(r => r.getString(0)).toArray
        partitionData.foreach(s => {
          // assert that both lower and upper case of the string are present in the same partition.
          assert(partitionData.contains(s.toLowerCase()))
          assert(partitionData.contains(s.toUpperCase()))
        })
    })
  }

  test("hash based joins not allowed for non-binary collated strings") {
    val in = (('a' to 'z') ++ ('A' to 'Z')).map(_.toString * 3).map(e => Row.apply(e, e))

    val schema = StructType(StructField(
      "col_non_binary",
      StringType(CollationFactory.collationNameToId("UTF8_BINARY_LCASE"))) ::
      StructField("col_binary", StringType) :: Nil)
    val df1 = spark.createDataFrame(sparkContext.parallelize(in), schema)

    // Binary collations are allowed to use hash join.
    assert(collectFirst(
      df1.hint("broadcast").join(df1, df1("col_binary") === df1("col_binary"))
        .queryExecution.executedPlan) {
      case _: BroadcastHashJoinExec => ()
    }.nonEmpty)

    // Even with hint broadcast, hash join is not used for non-binary collated strings.
    assert(collectFirst(
      df1.hint("broadcast").join(df1, df1("col_non_binary") === df1("col_non_binary"))
        .queryExecution.executedPlan) {
      case _: BroadcastHashJoinExec => ()
    }.isEmpty)

    // Instead they will default to sort merge join.
    assert(collectFirst(
      df1.hint("broadcast").join(df1, df1("col_non_binary") === df1("col_non_binary"))
        .queryExecution.executedPlan) {
      case _: SortMergeJoinExec => ()
    }.nonEmpty)
  }

  test("Generated column expressions using collations - errors out") {
    checkError(
      exception = intercept[AnalysisException] {
        sql(
          s"""
             |CREATE TABLE testcat.test_table(
             |  c1 STRING COLLATE UNICODE,
             |  c2 STRING COLLATE UNICODE GENERATED ALWAYS AS (SUBSTRING(c1, 0, 1))
             |)
             |USING $v2Source
             |""".stripMargin)
      },
      errorClass = "UNSUPPORTED_EXPRESSION_GENERATED_COLUMN",
      parameters = Map(
        "fieldName" -> "c2",
        "expressionStr" -> "SUBSTRING(c1, 0, 1)",
        "reason" ->
          "generation expression cannot contain non-binary orderable collated string type"))

    checkError(
      exception = intercept[AnalysisException] {
        sql(
          s"""
             |CREATE TABLE testcat.test_table(
             |  c1 STRING COLLATE UNICODE,
             |  c2 STRING COLLATE UNICODE GENERATED ALWAYS AS (LOWER(c1))
             |)
             |USING $v2Source
             |""".stripMargin)
      },
      errorClass = "UNSUPPORTED_EXPRESSION_GENERATED_COLUMN",
      parameters = Map(
        "fieldName" -> "c2",
        "expressionStr" -> "LOWER(c1)",
        "reason" ->
          "generation expression cannot contain non-binary orderable collated string type"))

    checkError(
      exception = intercept[AnalysisException] {
        sql(
          s"""
             |CREATE TABLE testcat.test_table(
             |  struct1 STRUCT<a: STRING COLLATE UNICODE>,
             |  c2 STRING COLLATE UNICODE GENERATED ALWAYS AS (UCASE(struct1.a))
             |)
             |USING $v2Source
             |""".stripMargin)
      },
      errorClass = "UNSUPPORTED_EXPRESSION_GENERATED_COLUMN",
      parameters = Map(
        "fieldName" -> "c2",
        "expressionStr" -> "UCASE(struct1.a)",
        "reason" ->
          "generation expression cannot contain non-binary orderable collated string type"))
  }

  test("SPARK-47431: Default collation set to UNICODE, literal test") {
    withSQLConf(SqlApiConf.DEFAULT_COLLATION -> "UNICODE") {
      checkAnswer(sql(s"SELECT collation('aa')"), Seq(Row("UNICODE")))
    }
  }

  test("SPARK-47431: Default collation set to UNICODE, column type test") {
    withTable("t") {
      withSQLConf(SqlApiConf.DEFAULT_COLLATION -> "UNICODE") {
        sql(s"CREATE TABLE t(c1 STRING) USING PARQUET")
        sql(s"INSERT INTO t VALUES ('a')")
        checkAnswer(sql(s"SELECT collation(c1) FROM t"), Seq(Row("UNICODE")))
      }
    }
  }

  test("SPARK-47431: Create table with UTF8_BINARY, make sure collation persists on read") {
    withTable("t") {
      withSQLConf(SqlApiConf.DEFAULT_COLLATION -> "UTF8_BINARY") {
        sql("CREATE TABLE t(c1 STRING) USING PARQUET")
        sql("INSERT INTO t VALUES ('a')")
        checkAnswer(sql("SELECT collation(c1) FROM t"), Seq(Row("UTF8_BINARY")))
      }
      withSQLConf(SqlApiConf.DEFAULT_COLLATION -> "UNICODE") {
        checkAnswer(sql("SELECT collation(c1) FROM t"), Seq(Row("UTF8_BINARY")))
      }
    }
  }

  test("Create dataframe with non utf8 binary collation") {
    val schema = StructType(Seq(StructField("Name", StringType("UNICODE_CI"))))
    val data = Seq(Row("Alice"), Row("Bob"), Row("bob"))
    val df = spark.createDataFrame(sparkContext.parallelize(data), schema)

    checkAnswer(
      df.groupBy("name").count(),
      Seq(Row("Alice", 1), Row("Bob", 2))
    )
  }

  test("Aggregation on complex containing collated strings") {
    val table = "table_agg"
    // array
    withTable(table) {
      sql(s"create table $table (a array<string collate utf8_binary_lcase>) using parquet")
      sql(s"insert into $table values (array('aaa')), (array('AAA'))")
      checkAnswer(sql(s"select distinct a from $table"), Seq(Row(Seq("aaa"))))
    }
    // map doesn't support aggregation
    withTable(table) {
      sql(s"create table $table (m map<string collate utf8_binary_lcase, string>) using parquet")
      val query = s"select distinct m from $table"
      checkError(
        exception = intercept[ExtendedAnalysisException](sql(query)),
        errorClass = "UNSUPPORTED_FEATURE.SET_OPERATION_ON_MAP_TYPE",
        parameters = Map(
          "colName" -> "`m`",
          "dataType" -> toSQLType(MapType(
            StringType(CollationFactory.collationNameToId("UTF8_BINARY_LCASE")),
            StringType))),
        context = ExpectedContext(query, 0, query.length - 1)
      )
    }
    // struct
    withTable(table) {
      sql(s"create table $table (s struct<fld:string collate utf8_binary_lcase>) using parquet")
      sql(s"insert into $table values (named_struct('fld', 'aaa')), (named_struct('fld', 'AAA'))")
      checkAnswer(sql(s"select s.fld from $table group by s"), Seq(Row("aaa")))
    }
  }

  test("Joins on complex types containing collated strings") {
    val tableLeft = "table_join_le"
    val tableRight = "table_join_ri"
    // array
    withTable(tableLeft, tableRight) {
      Seq(tableLeft, tableRight).map(tab =>
        sql(s"create table $tab (a array<string collate utf8_binary_lcase>) using parquet"))
      Seq((tableLeft, "array('aaa')"), (tableRight, "array('AAA')")).map{
        case (tab, data) => sql(s"insert into $tab values ($data)")
      }
      checkAnswer(sql(
        s"""
           |select $tableLeft.a from $tableLeft
           |join $tableRight on $tableLeft.a = $tableRight.a
           |""".stripMargin), Seq(Row(Seq("aaa"))))
    }
    // map doesn't support joins
    withTable(tableLeft, tableRight) {
      Seq(tableLeft, tableRight).map(tab =>
        sql(s"create table $tab (m map<string collate utf8_binary_lcase, string>) using parquet"))
      val query =
        s"select $tableLeft.m from $tableLeft join $tableRight on $tableLeft.m = $tableRight.m"
      val ctx = s"$tableLeft.m = $tableRight.m"
      checkError(
        exception = intercept[AnalysisException](sql(query)),
        errorClass = "DATATYPE_MISMATCH.INVALID_ORDERING_TYPE",
        parameters = Map(
          "functionName" -> "`=`",
          "dataType" -> toSQLType(MapType(
            StringType(CollationFactory.collationNameToId("UTF8_BINARY_LCASE")),
            StringType
          )),
          "sqlExpr" -> "\"(m = m)\""),
        context = ExpectedContext(ctx, query.length - ctx.length, query.length - 1))
    }
    // struct
    withTable(tableLeft, tableRight) {
      Seq(tableLeft, tableRight).map(tab =>
        sql(s"create table $tab (s struct<fld:string collate utf8_binary_lcase>) using parquet"))
      Seq(
        (tableLeft, "named_struct('fld', 'aaa')"),
        (tableRight, "named_struct('fld', 'AAA')")
      ).map {
        case (tab, data) => sql(s"insert into $tab values ($data)")
      }
      checkAnswer(sql(
        s"""
           |select $tableLeft.s.fld from $tableLeft
           |join $tableRight on $tableLeft.s = $tableRight.s
           |""".stripMargin), Seq(Row("aaa")))
    }
  }

  test("window aggregates should respect collation") {
    val t1 = "T_NON_BINARY"
    val t2 = "T_BINARY"

    withTable(t1, t2) {
      sql(s"CREATE TABLE $t1 (c STRING COLLATE UTF8_BINARY_LCASE, i int) USING PARQUET")
      sql(s"INSERT INTO $t1 VALUES ('aA', 2), ('Aa', 1), ('ab', 3), ('aa', 1)")

      sql(s"CREATE TABLE $t2 (c STRING, i int) USING PARQUET")
      // Same input but already normalized to lowercase.
      sql(s"INSERT INTO $t2 VALUES ('aa', 2), ('aa', 1), ('ab', 3), ('aa', 1)")

      val dfNonBinary =
        sql(s"SELECT lower(c), i, nth_value(i, 2) OVER (PARTITION BY c ORDER BY i) FROM $t1")
      val dfBinary =
        sql(s"SELECT c, i, nth_value(i, 2) OVER (PARTITION BY c ORDER BY i) FROM $t2")
      checkAnswer(dfNonBinary, dfBinary)
    }
  }

  test("CollationKey generates correct collation key") {
    val testCases = Seq(
      ("", "UTF8_BINARY", UTF8String.fromString("")),
      ("aa", "UTF8_BINARY", UTF8String.fromString("aa")),
      ("AA", "UTF8_BINARY", UTF8String.fromString("AA")),
      ("aA", "UTF8_BINARY", UTF8String.fromString("aA")),
      ("", "UTF8_BINARY_LCASE", UTF8String.fromString("")),
      ("aa", "UTF8_BINARY_LCASE", UTF8String.fromString("aa")),
      ("AA", "UTF8_BINARY_LCASE", UTF8String.fromString("aa")),
      ("aA", "UTF8_BINARY_LCASE", UTF8String.fromString("aa")),
      ("", "UNICODE", UTF8String.fromBytes(Array[Byte](1, 1, 0))),
      ("aa", "UNICODE", UTF8String.fromBytes(Array[Byte](42, 42, 1, 6, 1, 6, 0))),
      ("AA", "UNICODE", UTF8String.fromBytes(Array[Byte](42, 42, 1, 6, 1, -36, -36, 0))),
      ("aA", "UNICODE", UTF8String.fromBytes(Array[Byte](42, 42, 1, 6, 1, -59, -36, 0))),
      ("", "UNICODE_CI", UTF8String.fromBytes(Array[Byte](1, 0))),
      ("aa", "UNICODE_CI", UTF8String.fromBytes(Array[Byte](42, 42, 1, 6, 0))),
      ("AA", "UNICODE_CI", UTF8String.fromBytes(Array[Byte](42, 42, 1, 6, 0))),
      ("aA", "UNICODE_CI", UTF8String.fromBytes(Array[Byte](42, 42, 1, 6, 0)))
    )
    for ((input, collation, expected) <- testCases) {
      val collationId: Int = CollationFactory.collationNameToId(collation)
      val attrRef: AttributeReference = AttributeReference("attr", StringType(collationId))()
      // generate CollationKey for the input string
      val collationKey: CollationKey = CollationKey(attrRef)
      val str: UTF8String = UTF8String.fromString(input)
      assert(collationKey.nullSafeEval(str) === expected)
    }
  }

  test("CollationKey generates correct collation key using codegen") {
    val testCases = Seq(
      ("", "UTF8_BINARY", ""),
      ("aa", "UTF8_BINARY", "6161"),
      ("AA", "UTF8_BINARY", "4141"),
      ("aA", "UTF8_BINARY", "4161"),
      ("", "UTF8_BINARY_LCASE", ""),
      ("aa", "UTF8_BINARY_LCASE", "6161"),
      ("AA", "UTF8_BINARY_LCASE", "6161"),
      ("aA", "UTF8_BINARY_LCASE", "6161"),
      ("", "UNICODE", "101"),
      ("aa", "UNICODE", "60106012a2a"),
      ("AA", "UNICODE", "dcdc0106012a2a"),
      ("aA", "UNICODE", "dcc50106012a2a"),
      ("", "UNICODE_CI", "1"),
      ("aa", "UNICODE_CI", "6012a2a"),
      ("AA", "UNICODE_CI", "6012a2a"),
      ("aA", "UNICODE_CI", "6012a2a"),
    )
    for ((input, collation, expected) <- testCases) {
      val collationId: Int = CollationFactory.collationNameToId(collation)
      val attrRef: AttributeReference = AttributeReference("attr", StringType(collationId))()
      // generate CollationKey for the input string
      val collationKey: CollationKey = CollationKey(attrRef)
      val str: UTF8String = UTF8String.fromString(input)
      val boundExpr = BindReferences.bindReference(collationKey, Seq(attrRef))
      val ev = UnsafeProjection.create(Array(boundExpr).toIndexedSeq)
      val strProj = ev.apply(InternalRow(str))
      assert(strProj.toString.split(',').last.startsWith(expected))
    }
  }

  test("RewriteGroupByCollation rule rewrites Aggregate logical plan") {
    val dataType = StringType(CollationFactory.collationNameToId("UNICODE_CI"))
    val attrRef = AttributeReference("attr", dataType)()
    val originalPlan = Aggregate(Seq(attrRef), Seq(attrRef), LocalRelation(attrRef))
    assert(originalPlan.groupingExpressions.size == 1)
    assert(originalPlan.groupingExpressions.head == attrRef)
    // plan level rewrite should put CollationKey in Aggregate logical plan
    val newPlan = RewriteGroupByCollation(originalPlan)
    val groupingExpressions = newPlan.asInstanceOf[Aggregate].groupingExpressions
    assert(groupingExpressions.size == 1) // only 1 alias should be present in groupingExpressions
    val groupingAlias = groupingExpressions.head.asInstanceOf[Alias]
    assert(groupingAlias.child.isInstanceOf[CollationKey]) // alias should be a CollationKey
    assert(groupingAlias.child.containsChild(attrRef)) // CollationKey should be for attrRef
  }

  test("RewriteGroupByCollation rule works in SQL query analysis") {
    spark.conf.set("spark.sql.codegen.wholeStage", value = false)
    val dataType = StringType(CollationFactory.collationNameToId("UNICODE_CI"))
    val schema = StructType(Seq(StructField("name", dataType)))
    val data = Seq(Row("AA"), Row("aa"), Row("BB"))
    val df = spark.createDataFrame(spark.sparkContext.parallelize(data), schema)
    df.createOrReplaceTempView("tempTable")
    val dfGroupBy = spark.sql("SELECT name, COUNT(*) FROM tempTable GROUP BY name")
    // get the logical plan for the spark SQL query
    val logicalPlan = dfGroupBy.queryExecution.analyzed
    val newPlan = RewriteGroupByCollation(logicalPlan)
    assert(newPlan.isInstanceOf[Aggregate])
    val groupingExpressions = newPlan.asInstanceOf[Aggregate].groupingExpressions
    assert(groupingExpressions.size == 1)
//    val groupingAlias = groupingExpressions.head.asInstanceOf[Alias]
//    assert(groupingAlias.isInstanceOf[Alias])
//    assert(groupingAlias.child.isInstanceOf[CollationKey])
    // get the query execution result
    checkAnswer(dfGroupBy, Seq(Row("AA", 2), Row("BB", 1)))
  }

}
