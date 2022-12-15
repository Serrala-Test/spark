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

package org.apache.spark.sql.connector

import org.apache.spark.sql.{AnalysisException, DataFrame, QueryTest, Row}
import org.apache.spark.sql.connector.catalog.{MetadataColumn, SupportsMetadataColumns, Table, TableCapability}
import org.apache.spark.sql.connector.expressions.Transform
import org.apache.spark.sql.functions.struct
import org.apache.spark.sql.internal.SQLConf.V2_SESSION_CATALOG_IMPLEMENTATION
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.{DataType, IntegerType, StringType, StructType}

class MetadataColumnSuite extends DatasourceV2SQLBase {
  import testImplicits._

  private val tbl = "testcat.t"

  private def prepareTable(): Unit = {
    sql(s"CREATE TABLE $tbl (id bigint, data string) PARTITIONED BY (bucket(4, id), id)")
    sql(s"INSERT INTO $tbl VALUES (1, 'a'), (2, 'b'), (3, 'c')")
  }

  test("SPARK-31255: Project a metadata column") {
    withTable(tbl) {
      prepareTable()
      val sqlQuery = sql(s"SELECT id, data, index, _partition FROM $tbl")
      val dfQuery = spark.table(tbl).select("id", "data", "index", "_partition")

      Seq(sqlQuery, dfQuery).foreach { query =>
        checkAnswer(query, Seq(Row(1, "a", 0, "3/1"), Row(2, "b", 0, "0/2"), Row(3, "c", 0, "1/3")))
      }
    }
  }

  test("SPARK-31255: Projects data column when metadata column has the same name") {
    withTable(tbl) {
      sql(s"CREATE TABLE $tbl (index bigint, data string) PARTITIONED BY (bucket(4, index), index)")
      sql(s"INSERT INTO $tbl VALUES (3, 'c'), (2, 'b'), (1, 'a')")

      val sqlQuery = sql(s"SELECT index, data, _partition FROM $tbl")
      val dfQuery = spark.table(tbl).select("index", "data", "_partition")

      Seq(sqlQuery, dfQuery).foreach { query =>
        checkAnswer(query, Seq(Row(3, "c", "1/3"), Row(2, "b", "0/2"), Row(1, "a", "3/1")))
      }
    }
  }

  test("SPARK-31255: * expansion does not include metadata columns") {
    withTable(tbl) {
      prepareTable()
      val sqlQuery = sql(s"SELECT * FROM $tbl")
      val dfQuery = spark.table(tbl)

      Seq(sqlQuery, dfQuery).foreach { query =>
        checkAnswer(query, Seq(Row(1, "a"), Row(2, "b"), Row(3, "c")))
      }
    }
  }

  test("SPARK-31255: metadata column should only be produced when necessary") {
    withTable(tbl) {
      prepareTable()
      val sqlQuery = sql(s"SELECT * FROM $tbl WHERE index = 0")
      val dfQuery = spark.table(tbl).filter("index = 0")

      Seq(sqlQuery, dfQuery).foreach { query =>
        assert(query.schema.fieldNames.toSeq == Seq("id", "data"))
      }
    }
  }

  test("SPARK-34547: metadata columns are resolved last") {
    withTable(tbl) {
      prepareTable()
      withTempView("v") {
        sql(s"CREATE TEMPORARY VIEW v AS SELECT * FROM " +
          s"VALUES (1, -1), (2, -2), (3, -3) AS v(id, index)")

        val sqlQuery = sql(s"SELECT $tbl.id, v.id, data, index, $tbl.index, v.index " +
          s"FROM $tbl JOIN v WHERE $tbl.id = v.id")
        val tableDf = spark.table(tbl)
        val viewDf = spark.table("v")
        val dfQuery = tableDf.join(viewDf, tableDf.col("id") === viewDf.col("id"))
          .select(s"$tbl.id", "v.id", "data", "index", s"$tbl.index", "v.index")

        Seq(sqlQuery, dfQuery).foreach { query =>
          checkAnswer(query,
            Seq(
              Row(1, 1, "a", -1, 0, -1),
              Row(2, 2, "b", -2, 0, -2),
              Row(3, 3, "c", -3, 0, -3)
            )
          )
        }
      }
    }
  }

  test("SPARK-34555: Resolve DataFrame metadata column") {
    withTable(tbl) {
      prepareTable()
      val table = spark.table(tbl)
      val dfQuery = table.select(
        table.col("id"),
        table.col("data"),
        table.col("index"),
        table.col("_partition")
      )

      checkAnswer(
        dfQuery,
        Seq(Row(1, "a", 0, "3/1"), Row(2, "b", 0, "0/2"), Row(3, "c", 0, "1/3"))
      )
    }
  }

  test("SPARK-34923: propagate metadata columns through Project") {
    withTable(tbl) {
      prepareTable()
      checkAnswer(
        spark.table(tbl).select("id", "data").select("index", "_partition"),
        Seq(Row(0, "3/1"), Row(0, "0/2"), Row(0, "1/3"))
      )
    }
  }

  test("SPARK-34923: do not propagate metadata columns through View") {
    val view = "view"
    withTable(tbl) {
      withTempView(view) {
        prepareTable()
        sql(s"CACHE TABLE $view AS SELECT * FROM $tbl")
        assertThrows[AnalysisException] {
          sql(s"SELECT index, _partition FROM $view")
        }
      }
    }
  }

  test("SPARK-34923: propagate metadata columns through Filter") {
    withTable(tbl) {
      prepareTable()
      val sqlQuery = sql(s"SELECT id, data, index, _partition FROM $tbl WHERE id > 1")
      val dfQuery = spark.table(tbl).where("id > 1").select("id", "data", "index", "_partition")

      Seq(sqlQuery, dfQuery).foreach { query =>
        checkAnswer(query, Seq(Row(2, "b", 0, "0/2"), Row(3, "c", 0, "1/3")))
      }
    }
  }

  test("SPARK-34923: propagate metadata columns through Sort") {
    withTable(tbl) {
      prepareTable()
      val sqlQuery = sql(s"SELECT id, data, index, _partition FROM $tbl ORDER BY id")
      val dfQuery = spark.table(tbl).orderBy("id").select("id", "data", "index", "_partition")

      Seq(sqlQuery, dfQuery).foreach { query =>
        checkAnswer(query, Seq(Row(1, "a", 0, "3/1"), Row(2, "b", 0, "0/2"), Row(3, "c", 0, "1/3")))
      }
    }
  }

  test("SPARK-34923: propagate metadata columns through RepartitionBy") {
    withTable(tbl) {
      prepareTable()
      val sqlQuery = sql(
        s"SELECT /*+ REPARTITION_BY_RANGE(3, id) */ id, data, index, _partition FROM $tbl")
      val dfQuery = spark.table(tbl).repartitionByRange(3, $"id")
        .select("id", "data", "index", "_partition")

      Seq(sqlQuery, dfQuery).foreach { query =>
        checkAnswer(query, Seq(Row(1, "a", 0, "3/1"), Row(2, "b", 0, "0/2"), Row(3, "c", 0, "1/3")))
      }
    }
  }

  test("SPARK-34923: propagate metadata columns through SubqueryAlias if child is leaf node") {
    val sbq = "sbq"
    withTable(tbl) {
      prepareTable()
      val sqlQuery = sql(
        s"SELECT $sbq.id, $sbq.data, $sbq.index, $sbq._partition FROM $tbl $sbq")
      val dfQuery = spark.table(tbl).as(sbq).select(
        s"$sbq.id", s"$sbq.data", s"$sbq.index", s"$sbq._partition")

      Seq(sqlQuery, dfQuery).foreach { query =>
        checkAnswer(query, Seq(Row(1, "a", 0, "3/1"), Row(2, "b", 0, "0/2"), Row(3, "c", 0, "1/3")))
      }

      assertThrows[AnalysisException] {
        sql(s"SELECT $sbq.index FROM (SELECT id FROM $tbl) $sbq")
      }
      assertThrows[AnalysisException] {
        spark.table(tbl).select($"id").as(sbq).select(s"$sbq.index")
      }
    }
  }

  test("SPARK-40149: select outer join metadata columns with DataFrame API") {
    val df1 = Seq(1 -> "a").toDF("k", "v").as("left")
    val df2 = Seq(1 -> "b").toDF("k", "v").as("right")
    val dfQuery = df1.join(df2, "k", "outer")
      .withColumn("left_all", struct($"left.*"))
      .withColumn("right_all", struct($"right.*"))
    checkAnswer(dfQuery, Row(1, "a", "b", Row(1, "a"), Row(1, "b")))
  }

  test("SPARK-40429: Only set KeyGroupedPartitioning when the referenced column is in the output") {
    withTable(tbl) {
      sql(s"CREATE TABLE $tbl (id bigint, data string) PARTITIONED BY (id)")
      sql(s"INSERT INTO $tbl VALUES (1, 'a'), (2, 'b'), (3, 'c')")
      checkAnswer(
        spark.table(tbl).select("index", "_partition"),
        Seq(Row(0, "3"), Row(0, "2"), Row(0, "1"))
      )

      checkAnswer(
        spark.table(tbl).select("id", "index", "_partition"),
        Seq(Row(3, 0, "3"), Row(2, 0, "2"), Row(1, 0, "1"))
      )
    }
  }

  test("SPARK-41498: Metadata column is propagated through union") {
    withTable(tbl) {
      prepareTable()
      val df = spark.table(tbl)
      val dfQuery = df.union(df).select("id", "data", "index", "_partition")
      val expectedAnswer = Seq(Row(1, "a", 0, "3/1"), Row(2, "b", 0, "0/2"), Row(3, "c", 0, "1/3"))
      checkAnswer(dfQuery, expectedAnswer ++ expectedAnswer)
    }
  }

  test("SPARK-41498: Metadata column is not propagated when children of Union " +
    "have metadata output of different size") {
    withTable(tbl) {
      prepareTable()
      withTempDir { dir =>
        spark.range(start = 10, end = 20).selectExpr("bigint(id) as id", "string(id) as data")
          .write.mode("overwrite").save(dir.getAbsolutePath)
        val df1 = spark.table(tbl)
        val df2 = spark.read.load(dir.getAbsolutePath)

        // Make sure one df contains a metadata column and the other does not
        assert(!df1.queryExecution.analyzed.metadataOutput.exists(_.name == "_metadata"))
        assert(df2.queryExecution.analyzed.metadataOutput.exists(_.name == "_metadata"))

        assert(df1.union(df2).queryExecution.analyzed.metadataOutput.isEmpty)
      }
    }
  }
}

class MetadataTestTable extends Table with SupportsMetadataColumns {

  override def name(): String = "fake"
  override def schema(): StructType = StructType(Nil)
  override def capabilities(): java.util.Set[TableCapability] =
    java.util.EnumSet.of(TableCapability.BATCH_READ)
  override def metadataColumns(): Array[MetadataColumn] =
    Array(
      new MetadataColumn {
        override def name: String = "index"
        override def dataType: DataType = IntegerType
        override def comment: String = ""
      }
    )
}

class TypeMismatchTable extends MetadataTestTable {

  override def metadataColumns(): Array[MetadataColumn] =
    Array(
      new MetadataColumn {
        override def name: String = "index"
        override def dataType: DataType = StringType
        override def comment: String =
          "Used to create a type mismatch with the metadata col in `MetadataTestTable`"
      }
    )
}

class NameMismatchTable extends MetadataTestTable {
  override def metadataColumns(): Array[MetadataColumn] =
    Array(
      new MetadataColumn {
        override def name: String = "wrongName"
        override def dataType: DataType = IntegerType
        override def comment: String =
          "Used to create a name mismatch with the metadata col in `MetadataTestTable`"
      })
}

class MetadataTestCatalog extends TestV2SessionCatalogBase[MetadataTestTable] {
  override protected def newTable(
    name: String,
    schema: StructType,
    partitions: Array[Transform],
    properties: java.util.Map[String, String]): MetadataTestTable = {
    new MetadataTestTable
  }
}

class MetadataTypeMismatchCatalog extends TestV2SessionCatalogBase[TypeMismatchTable] {
  override protected def newTable(
    name: String,
    schema: StructType,
    partitions: Array[Transform],
    properties: java.util.Map[String, String]): TypeMismatchTable = {
    new TypeMismatchTable
  }
}

class MetadataNameMismatchCatalog extends TestV2SessionCatalogBase[NameMismatchTable] {
  override protected def newTable(
    name: String,
    schema: StructType,
    partitions: Array[Transform],
    properties: java.util.Map[String, String]): NameMismatchTable = {
    new NameMismatchTable
  }
}

class MetadataColumnMismatchSuite extends QueryTest with SharedSparkSession {

  override protected def afterEach(): Unit = {
    super.afterEach()
    spark.sessionState.catalogManager.reset()
  }

  private def getDfWithCatalog(tblName: String, catalogName: String): DataFrame = {
    // Reset the catalog manager to set a new V2 session catalog, as once loaded
    // it doesn't check the config value anymore
    spark.sessionState.catalogManager.reset()
    var df: DataFrame = null
    withSQLConf(V2_SESSION_CATALOG_IMPLEMENTATION.key -> catalogName) {
      spark.range(10).write.saveAsTable(tblName)
      df = spark.table(tblName)
    }
    df
  }

  test("SPARK-41498: Metadata column is not propagated when children of Union " +
    "have a type mismatch in a metadata column") {
    withTable("tbl", "typeMismatchTbl") {
      val df = getDfWithCatalog("tbl", classOf[MetadataTestCatalog].getName)
      val typeMismatchDf =
        getDfWithCatalog("typeMismatchTbl", classOf[MetadataTypeMismatchCatalog].getName)
      assert(df.union(typeMismatchDf).queryExecution.analyzed.metadataOutput.isEmpty)
    }
  }

  test("SPARK-41498: Metadata column is not propagated when children of Union " +
    "have a name mismatch in a metadata column") {
    withTable("tbl", "nameMismatchTbl") {
      val df = getDfWithCatalog("tbl", classOf[MetadataTestCatalog].getName)
      val nameMismatchDf =
        getDfWithCatalog("nameMismatchTbl", classOf[MetadataNameMismatchCatalog].getName)
      assert(df.union(nameMismatchDf).queryExecution.analyzed.metadataOutput.isEmpty)
    }
  }
}
