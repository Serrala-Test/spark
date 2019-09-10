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

package org.apache.spark.sql.execution.datasources.v2

import java.util
import java.util.Collections

import scala.collection.JavaConverters._

import org.scalatest.BeforeAndAfter

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalog.v2.{Catalogs, Identifier, NamespaceChange, TableChange}
import org.apache.spark.sql.catalyst.analysis.{NamespaceAlreadyExistsException, NoSuchNamespaceException, NoSuchTableException, TableAlreadyExistsException}
import org.apache.spark.sql.catalyst.parser.CatalystSqlParser
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.{DoubleType, IntegerType, LongType, StringType, StructField, StructType, TimestampType}
import org.apache.spark.sql.util.CaseInsensitiveStringMap

class V2SessionCatalogBaseSuite extends SparkFunSuite with SharedSparkSession with BeforeAndAfter {

  val emptyProps: util.Map[String, String] = Collections.emptyMap[String, String]
  val schema: StructType = new StructType()
      .add("id", IntegerType)
      .add("data", StringType)

  val testNs: Array[String] = Array("db")
  val defaultNs: Array[String] = Array("default")
  val testIdent: Identifier = Identifier.of(testNs, "test_table")

  def newCatalog(): V2SessionCatalog = {
    val newCatalog = new V2SessionCatalog(spark.sessionState.catalog, spark.sessionState.conf)
    newCatalog.initialize("test", CaseInsensitiveStringMap.empty())
    newCatalog
  }
}

class V2SessionCatalogTableSuite extends V2SessionCatalogBaseSuite {

  import org.apache.spark.sql.catalog.v2.CatalogV2Implicits._

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val catalog = newCatalog()
    catalog.createNamespace(Array("db"), emptyProps)
    catalog.createNamespace(Array("db2"), emptyProps)
    catalog.createNamespace(Array("ns"), emptyProps)
    catalog.createNamespace(Array("ns2"), emptyProps)
  }

  override protected def afterAll(): Unit = {
    val catalog = newCatalog()
    catalog.dropNamespace(Array("db"))
    catalog.dropNamespace(Array("db2"))
    catalog.dropNamespace(Array("ns"))
    catalog.dropNamespace(Array("ns2"))
    super.afterAll()
  }

  after {
    newCatalog().dropTable(testIdent)
    newCatalog().dropTable(testIdentNew)
  }

  private val testIdentNew = Identifier.of(testNs, "test_table_new")

  test("listTables") {
    val catalog = newCatalog()
    val ident1 = Identifier.of(Array("ns"), "test_table_1")
    val ident2 = Identifier.of(Array("ns"), "test_table_2")
    val ident3 = Identifier.of(Array("ns2"), "test_table_1")

    assert(catalog.listTables(Array("ns")).isEmpty)

    catalog.createTable(ident1, schema, Array.empty, emptyProps)

    assert(catalog.listTables(Array("ns")).toSet == Set(ident1))
    assert(catalog.listTables(Array("ns2")).isEmpty)

    catalog.createTable(ident3, schema, Array.empty, emptyProps)
    catalog.createTable(ident2, schema, Array.empty, emptyProps)

    assert(catalog.listTables(Array("ns")).toSet == Set(ident1, ident2))
    assert(catalog.listTables(Array("ns2")).toSet == Set(ident3))

    catalog.dropTable(ident1)

    assert(catalog.listTables(Array("ns")).toSet == Set(ident2))

    catalog.dropTable(ident2)

    assert(catalog.listTables(Array("ns")).isEmpty)
    assert(catalog.listTables(Array("ns2")).toSet == Set(ident3))

    catalog.dropTable(ident3)
  }

  test("createTable") {
    val catalog = newCatalog()

    assert(!catalog.tableExists(testIdent))

    val table = catalog.createTable(testIdent, schema, Array.empty, emptyProps)

    val parsed = CatalystSqlParser.parseMultipartIdentifier(table.name)
    assert(parsed == Seq("db", "test_table"))
    assert(table.schema == schema)
    assert(table.properties.asScala == Map())

    assert(catalog.tableExists(testIdent))
  }

  test("createTable: with properties") {
    val catalog = newCatalog()

    val properties = new util.HashMap[String, String]()
    properties.put("property", "value")

    assert(!catalog.tableExists(testIdent))

    val table = catalog.createTable(testIdent, schema, Array.empty, properties)

    val parsed = CatalystSqlParser.parseMultipartIdentifier(table.name)
    assert(parsed == Seq("db", "test_table"))
    assert(table.schema == schema)
    assert(table.properties == properties)

    assert(catalog.tableExists(testIdent))
  }

  test("createTable: table already exists") {
    val catalog = newCatalog()

    assert(!catalog.tableExists(testIdent))

    val table = catalog.createTable(testIdent, schema, Array.empty, emptyProps)

    val exc = intercept[TableAlreadyExistsException] {
      catalog.createTable(testIdent, schema, Array.empty, emptyProps)
    }

    assert(exc.message.contains(table.name()))
    assert(exc.message.contains("already exists"))

    assert(catalog.tableExists(testIdent))
  }

  test("tableExists") {
    val catalog = newCatalog()

    assert(!catalog.tableExists(testIdent))

    catalog.createTable(testIdent, schema, Array.empty, emptyProps)

    assert(catalog.tableExists(testIdent))

    catalog.dropTable(testIdent)

    assert(!catalog.tableExists(testIdent))
  }

  test("loadTable") {
    val catalog = newCatalog()

    val table = catalog.createTable(testIdent, schema, Array.empty, emptyProps)
    val loaded = catalog.loadTable(testIdent)

    assert(table.name == loaded.name)
    assert(table.schema == loaded.schema)
    assert(table.properties == loaded.properties)
  }

  test("loadTable: table does not exist") {
    val catalog = newCatalog()

    val exc = intercept[NoSuchTableException] {
      catalog.loadTable(testIdent)
    }

    assert(exc.message.contains(testIdent.quoted))
    assert(exc.message.contains("not found"))
  }

  test("invalidateTable") {
    val catalog = newCatalog()

    val table = catalog.createTable(testIdent, schema, Array.empty, emptyProps)
    catalog.invalidateTable(testIdent)

    val loaded = catalog.loadTable(testIdent)

    assert(table.name == loaded.name)
    assert(table.schema == loaded.schema)
    assert(table.properties == loaded.properties)
  }

  test("invalidateTable: table does not exist") {
    val catalog = newCatalog()

    assert(catalog.tableExists(testIdent) === false)

    catalog.invalidateTable(testIdent)
  }

  test("alterTable: add property") {
    val catalog = newCatalog()

    val table = catalog.createTable(testIdent, schema, Array.empty, emptyProps)

    assert(table.properties.asScala == Map())

    val updated = catalog.alterTable(testIdent, TableChange.setProperty("prop-1", "1"))
    assert(updated.properties.asScala == Map("prop-1" -> "1"))

    val loaded = catalog.loadTable(testIdent)
    assert(loaded.properties.asScala == Map("prop-1" -> "1"))

    assert(table.properties.asScala == Map())
  }

  test("alterTable: add property to existing") {
    val catalog = newCatalog()

    val properties = new util.HashMap[String, String]()
    properties.put("prop-1", "1")

    val table = catalog.createTable(testIdent, schema, Array.empty, properties)

    assert(table.properties.asScala == Map("prop-1" -> "1"))

    val updated = catalog.alterTable(testIdent, TableChange.setProperty("prop-2", "2"))
    assert(updated.properties.asScala == Map("prop-1" -> "1", "prop-2" -> "2"))

    val loaded = catalog.loadTable(testIdent)
    assert(loaded.properties.asScala == Map("prop-1" -> "1", "prop-2" -> "2"))

    assert(table.properties.asScala == Map("prop-1" -> "1"))
  }

  test("alterTable: remove existing property") {
    val catalog = newCatalog()

    val properties = new util.HashMap[String, String]()
    properties.put("prop-1", "1")

    val table = catalog.createTable(testIdent, schema, Array.empty, properties)

    assert(table.properties.asScala == Map("prop-1" -> "1"))

    val updated = catalog.alterTable(testIdent, TableChange.removeProperty("prop-1"))
    assert(updated.properties.asScala == Map())

    val loaded = catalog.loadTable(testIdent)
    assert(loaded.properties.asScala == Map())

    assert(table.properties.asScala == Map("prop-1" -> "1"))
  }

  test("alterTable: remove missing property") {
    val catalog = newCatalog()

    val table = catalog.createTable(testIdent, schema, Array.empty, emptyProps)

    assert(table.properties.asScala == Map())

    val updated = catalog.alterTable(testIdent, TableChange.removeProperty("prop-1"))
    assert(updated.properties.asScala == Map())

    val loaded = catalog.loadTable(testIdent)
    assert(loaded.properties.asScala == Map())

    assert(table.properties.asScala == Map())
  }

  test("alterTable: add top-level column") {
    val catalog = newCatalog()

    val table = catalog.createTable(testIdent, schema, Array.empty, emptyProps)

    assert(table.schema == schema)

    val updated = catalog.alterTable(testIdent, TableChange.addColumn(Array("ts"), TimestampType))

    assert(updated.schema == schema.add("ts", TimestampType))
  }

  test("alterTable: add required column") {
    val catalog = newCatalog()

    val table = catalog.createTable(testIdent, schema, Array.empty, emptyProps)

    assert(table.schema == schema)

    val updated = catalog.alterTable(testIdent,
      TableChange.addColumn(Array("ts"), TimestampType, false))

    assert(updated.schema == schema.add("ts", TimestampType, nullable = false))
  }

  test("alterTable: add column with comment") {
    val catalog = newCatalog()

    val table = catalog.createTable(testIdent, schema, Array.empty, emptyProps)

    assert(table.schema == schema)

    val updated = catalog.alterTable(testIdent,
      TableChange.addColumn(Array("ts"), TimestampType, false, "comment text"))

    val field = StructField("ts", TimestampType, nullable = false).withComment("comment text")
    assert(updated.schema == schema.add(field))
  }

  test("alterTable: add nested column") {
    val catalog = newCatalog()

    val pointStruct = new StructType().add("x", DoubleType).add("y", DoubleType)
    val tableSchema = schema.add("point", pointStruct)

    val table = catalog.createTable(testIdent, tableSchema, Array.empty, emptyProps)

    assert(table.schema == tableSchema)

    val updated = catalog.alterTable(testIdent,
      TableChange.addColumn(Array("point", "z"), DoubleType))

    val expectedSchema = schema.add("point", pointStruct.add("z", DoubleType))

    assert(updated.schema == expectedSchema)
  }

  test("alterTable: add column to primitive field fails") {
    val catalog = newCatalog()

    val table = catalog.createTable(testIdent, schema, Array.empty, emptyProps)

    assert(table.schema == schema)

    val exc = intercept[IllegalArgumentException] {
      catalog.alterTable(testIdent, TableChange.addColumn(Array("data", "ts"), TimestampType))
    }

    assert(exc.getMessage.contains("Not a struct"))
    assert(exc.getMessage.contains("data"))

    // the table has not changed
    assert(catalog.loadTable(testIdent).schema == schema)
  }

  test("alterTable: add field to missing column fails") {
    val catalog = newCatalog()

    val table = catalog.createTable(testIdent, schema, Array.empty, emptyProps)

    assert(table.schema == schema)

    val exc = intercept[IllegalArgumentException] {
      catalog.alterTable(testIdent,
        TableChange.addColumn(Array("missing_col", "new_field"), StringType))
    }

    assert(exc.getMessage.contains("missing_col"))
    assert(exc.getMessage.contains("Cannot find"))
  }

  test("alterTable: update column data type") {
    val catalog = newCatalog()

    val table = catalog.createTable(testIdent, schema, Array.empty, emptyProps)

    assert(table.schema == schema)

    val updated = catalog.alterTable(testIdent, TableChange.updateColumnType(Array("id"), LongType))

    val expectedSchema = new StructType().add("id", LongType).add("data", StringType)
    assert(updated.schema == expectedSchema)
  }

  test("alterTable: update column data type and nullability") {
    val catalog = newCatalog()

    val originalSchema = new StructType()
        .add("id", IntegerType, nullable = false)
        .add("data", StringType)
    val table = catalog.createTable(testIdent, originalSchema, Array.empty, emptyProps)

    assert(table.schema == originalSchema)

    val updated = catalog.alterTable(testIdent,
      TableChange.updateColumnType(Array("id"), LongType, true))

    val expectedSchema = new StructType().add("id", LongType).add("data", StringType)
    assert(updated.schema == expectedSchema)
  }

  test("alterTable: update optional column to required fails") {
    val catalog = newCatalog()

    val table = catalog.createTable(testIdent, schema, Array.empty, emptyProps)

    assert(table.schema == schema)

    val exc = intercept[IllegalArgumentException] {
      catalog.alterTable(testIdent, TableChange.updateColumnType(Array("id"), LongType, false))
    }

    assert(exc.getMessage.contains("Cannot change optional column to required"))
    assert(exc.getMessage.contains("id"))
  }

  test("alterTable: update missing column fails") {
    val catalog = newCatalog()

    val table = catalog.createTable(testIdent, schema, Array.empty, emptyProps)

    assert(table.schema == schema)

    val exc = intercept[IllegalArgumentException] {
      catalog.alterTable(testIdent,
        TableChange.updateColumnType(Array("missing_col"), LongType))
    }

    assert(exc.getMessage.contains("missing_col"))
    assert(exc.getMessage.contains("Cannot find"))
  }

  test("alterTable: add comment") {
    val catalog = newCatalog()

    val table = catalog.createTable(testIdent, schema, Array.empty, emptyProps)

    assert(table.schema == schema)

    val updated = catalog.alterTable(testIdent,
      TableChange.updateColumnComment(Array("id"), "comment text"))

    val expectedSchema = new StructType()
        .add("id", IntegerType, nullable = true, "comment text")
        .add("data", StringType)
    assert(updated.schema == expectedSchema)
  }

  test("alterTable: replace comment") {
    val catalog = newCatalog()

    val table = catalog.createTable(testIdent, schema, Array.empty, emptyProps)

    assert(table.schema == schema)

    catalog.alterTable(testIdent, TableChange.updateColumnComment(Array("id"), "comment text"))

    val expectedSchema = new StructType()
        .add("id", IntegerType, nullable = true, "replacement comment")
        .add("data", StringType)

    val updated = catalog.alterTable(testIdent,
      TableChange.updateColumnComment(Array("id"), "replacement comment"))

    assert(updated.schema == expectedSchema)
  }

  test("alterTable: add comment to missing column fails") {
    val catalog = newCatalog()

    val table = catalog.createTable(testIdent, schema, Array.empty, emptyProps)

    assert(table.schema == schema)

    val exc = intercept[IllegalArgumentException] {
      catalog.alterTable(testIdent,
        TableChange.updateColumnComment(Array("missing_col"), "comment"))
    }

    assert(exc.getMessage.contains("missing_col"))
    assert(exc.getMessage.contains("Cannot find"))
  }

  test("alterTable: rename top-level column") {
    val catalog = newCatalog()

    val table = catalog.createTable(testIdent, schema, Array.empty, emptyProps)

    assert(table.schema == schema)

    val updated = catalog.alterTable(testIdent, TableChange.renameColumn(Array("id"), "some_id"))

    val expectedSchema = new StructType().add("some_id", IntegerType).add("data", StringType)

    assert(updated.schema == expectedSchema)
  }

  test("alterTable: rename nested column") {
    val catalog = newCatalog()

    val pointStruct = new StructType().add("x", DoubleType).add("y", DoubleType)
    val tableSchema = schema.add("point", pointStruct)

    val table = catalog.createTable(testIdent, tableSchema, Array.empty, emptyProps)

    assert(table.schema == tableSchema)

    val updated = catalog.alterTable(testIdent,
      TableChange.renameColumn(Array("point", "x"), "first"))

    val newPointStruct = new StructType().add("first", DoubleType).add("y", DoubleType)
    val expectedSchema = schema.add("point", newPointStruct)

    assert(updated.schema == expectedSchema)
  }

  test("alterTable: rename struct column") {
    val catalog = newCatalog()

    val pointStruct = new StructType().add("x", DoubleType).add("y", DoubleType)
    val tableSchema = schema.add("point", pointStruct)

    val table = catalog.createTable(testIdent, tableSchema, Array.empty, emptyProps)

    assert(table.schema == tableSchema)

    val updated = catalog.alterTable(testIdent,
      TableChange.renameColumn(Array("point"), "p"))

    val newPointStruct = new StructType().add("x", DoubleType).add("y", DoubleType)
    val expectedSchema = schema.add("p", newPointStruct)

    assert(updated.schema == expectedSchema)
  }

  test("alterTable: rename missing column fails") {
    val catalog = newCatalog()

    val table = catalog.createTable(testIdent, schema, Array.empty, emptyProps)

    assert(table.schema == schema)

    val exc = intercept[IllegalArgumentException] {
      catalog.alterTable(testIdent,
        TableChange.renameColumn(Array("missing_col"), "new_name"))
    }

    assert(exc.getMessage.contains("missing_col"))
    assert(exc.getMessage.contains("Cannot find"))
  }

  test("alterTable: multiple changes") {
    val catalog = newCatalog()

    val pointStruct = new StructType().add("x", DoubleType).add("y", DoubleType)
    val tableSchema = schema.add("point", pointStruct)

    val table = catalog.createTable(testIdent, tableSchema, Array.empty, emptyProps)

    assert(table.schema == tableSchema)

    val updated = catalog.alterTable(testIdent,
      TableChange.renameColumn(Array("point", "x"), "first"),
      TableChange.renameColumn(Array("point", "y"), "second"))

    val newPointStruct = new StructType().add("first", DoubleType).add("second", DoubleType)
    val expectedSchema = schema.add("point", newPointStruct)

    assert(updated.schema == expectedSchema)
  }

  test("alterTable: delete top-level column") {
    val catalog = newCatalog()

    val table = catalog.createTable(testIdent, schema, Array.empty, emptyProps)

    assert(table.schema == schema)

    val updated = catalog.alterTable(testIdent,
      TableChange.deleteColumn(Array("id")))

    val expectedSchema = new StructType().add("data", StringType)
    assert(updated.schema == expectedSchema)
  }

  test("alterTable: delete nested column") {
    val catalog = newCatalog()

    val pointStruct = new StructType().add("x", DoubleType).add("y", DoubleType)
    val tableSchema = schema.add("point", pointStruct)

    val table = catalog.createTable(testIdent, tableSchema, Array.empty, emptyProps)

    assert(table.schema == tableSchema)

    val updated = catalog.alterTable(testIdent,
      TableChange.deleteColumn(Array("point", "y")))

    val newPointStruct = new StructType().add("x", DoubleType)
    val expectedSchema = schema.add("point", newPointStruct)

    assert(updated.schema == expectedSchema)
  }

  test("alterTable: delete missing column fails") {
    val catalog = newCatalog()

    val table = catalog.createTable(testIdent, schema, Array.empty, emptyProps)

    assert(table.schema == schema)

    val exc = intercept[IllegalArgumentException] {
      catalog.alterTable(testIdent, TableChange.deleteColumn(Array("missing_col")))
    }

    assert(exc.getMessage.contains("missing_col"))
    assert(exc.getMessage.contains("Cannot find"))
  }

  test("alterTable: delete missing nested column fails") {
    val catalog = newCatalog()

    val pointStruct = new StructType().add("x", DoubleType).add("y", DoubleType)
    val tableSchema = schema.add("point", pointStruct)

    val table = catalog.createTable(testIdent, tableSchema, Array.empty, emptyProps)

    assert(table.schema == tableSchema)

    val exc = intercept[IllegalArgumentException] {
      catalog.alterTable(testIdent, TableChange.deleteColumn(Array("point", "z")))
    }

    assert(exc.getMessage.contains("z"))
    assert(exc.getMessage.contains("Cannot find"))
  }

  test("alterTable: table does not exist") {
    val catalog = newCatalog()

    val exc = intercept[NoSuchTableException] {
      catalog.alterTable(testIdent, TableChange.setProperty("prop", "val"))
    }

    assert(exc.message.contains(testIdent.quoted))
    assert(exc.message.contains("not found"))
  }

  test("dropTable") {
    val catalog = newCatalog()

    assert(!catalog.tableExists(testIdent))

    catalog.createTable(testIdent, schema, Array.empty, emptyProps)

    assert(catalog.tableExists(testIdent))

    val wasDropped = catalog.dropTable(testIdent)

    assert(wasDropped)
    assert(!catalog.tableExists(testIdent))
  }

  test("dropTable: table does not exist") {
    val catalog = newCatalog()

    assert(!catalog.tableExists(testIdent))

    val wasDropped = catalog.dropTable(testIdent)

    assert(!wasDropped)
    assert(!catalog.tableExists(testIdent))
  }

  test("renameTable") {
    val catalog = newCatalog()

    assert(!catalog.tableExists(testIdent))
    assert(!catalog.tableExists(testIdentNew))

    catalog.createTable(testIdent, schema, Array.empty, emptyProps)

    assert(catalog.tableExists(testIdent))
    catalog.renameTable(testIdent, testIdentNew)

    assert(!catalog.tableExists(testIdent))
    assert(catalog.tableExists(testIdentNew))
  }

  test("renameTable: fail if table does not exist") {
    val catalog = newCatalog()

    val exc = intercept[NoSuchTableException] {
      catalog.renameTable(testIdent, testIdentNew)
    }

    assert(exc.message.contains(testIdent.quoted))
    assert(exc.message.contains("not found"))
  }

  test("renameTable: fail if new table name already exists") {
    val catalog = newCatalog()

    assert(!catalog.tableExists(testIdent))
    assert(!catalog.tableExists(testIdentNew))

    catalog.createTable(testIdent, schema, Array.empty, emptyProps)
    catalog.createTable(testIdentNew, schema, Array.empty, emptyProps)

    assert(catalog.tableExists(testIdent))
    assert(catalog.tableExists(testIdentNew))

    val exc = intercept[TableAlreadyExistsException] {
      catalog.renameTable(testIdent, testIdentNew)
    }

    assert(exc.message.contains(testIdentNew.quoted))
    assert(exc.message.contains("already exists"))
  }

  test("renameTable: fail if db does not match for old and new table names") {
    val catalog = newCatalog()
    val testIdentNewOtherDb = Identifier.of(Array("db2"), "test_table_new")

    assert(!catalog.tableExists(testIdent))
    assert(!catalog.tableExists(testIdentNewOtherDb))

    catalog.createTable(testIdent, schema, Array.empty, emptyProps)

    assert(catalog.tableExists(testIdent))

    val exc = intercept[AnalysisException] {
      catalog.renameTable(testIdent, testIdentNewOtherDb)
    }

    assert(exc.message.contains(testIdent.namespace.quoted))
    assert(exc.message.contains(testIdentNewOtherDb.namespace.quoted))
    assert(exc.message.contains("RENAME TABLE source and destination databases do not match"))
  }
}

class V2SessionCatalogNamespaceSuite extends V2SessionCatalogBaseSuite {

  import org.apache.spark.sql.catalog.v2.CatalogV2Implicits._

  def checkMetadata(
      expected: scala.collection.Map[String, String],
      actual: scala.collection.Map[String, String]): Unit = {
    // remove location and comment that are automatically added by HMS unless they are expected
    val toRemove = V2SessionCatalog.RESERVED_PROPERTIES.filter(expected.contains)
    assert(expected -- toRemove === actual)
  }

  test("listNamespaces: basic behavior") {
    val catalog = newCatalog()
    catalog.createNamespace(testNs, Map("property" -> "value").asJava)

    assert(catalog.listNamespaces() === Array(testNs, defaultNs))
    assert(catalog.listNamespaces(Array()) === Array(testNs, defaultNs))
    assert(catalog.listNamespaces(testNs) === Array())

    catalog.dropNamespace(testNs)
  }

  test("listNamespaces: fail if missing namespace") {
    val catalog = newCatalog()

    assert(catalog.namespaceExists(testNs) === false)

    val exc = intercept[NoSuchNamespaceException] {
      assert(catalog.listNamespaces(testNs) === Array())
    }

    assert(exc.getMessage.contains(testNs.quoted))
    assert(catalog.namespaceExists(testNs) === false)
  }

  test("loadNamespaceMetadata: fail missing namespace") {
    val catalog = newCatalog()

    val exc = intercept[NoSuchNamespaceException] {
      catalog.loadNamespaceMetadata(testNs)
    }

    assert(exc.getMessage.contains(testNs.quoted))
  }

  test("loadNamespaceMetadata: non-empty metadata") {
    val catalog = newCatalog()

    assert(catalog.namespaceExists(testNs) === false)

    catalog.createNamespace(testNs, Map("property" -> "value").asJava)

    val metadata = catalog.loadNamespaceMetadata(testNs)

    assert(catalog.namespaceExists(testNs) === true)
    checkMetadata(metadata.asScala, Map("property" -> "value"))

    catalog.dropNamespace(testNs)
  }

  test("loadNamespaceMetadata: empty metadata") {
    val catalog = newCatalog()

    assert(catalog.namespaceExists(testNs) === false)

    catalog.createNamespace(testNs, emptyProps)

    val metadata = catalog.loadNamespaceMetadata(testNs)

    assert(catalog.namespaceExists(testNs) === true)
    checkMetadata(metadata.asScala, emptyProps.asScala)

    catalog.dropNamespace(testNs)
  }

  test("createNamespace: basic behavior") {
    val catalog = newCatalog()
    val expectedPath = sqlContext.sessionState.catalog.getDefaultDBPath(testNs(0)).toString

    catalog.createNamespace(testNs, Map("property" -> "value").asJava)

    assert(expectedPath === spark.catalog.getDatabase(testNs(0)).locationUri.toString)

    assert(catalog.namespaceExists(testNs) === true)
    val metadata = catalog.loadNamespaceMetadata(testNs).asScala
    checkMetadata(metadata, Map("property" -> "value"))
    assert(expectedPath === metadata("location"))

    catalog.dropNamespace(testNs)
  }

  test("createNamespace: initialize location") {
    val catalog = newCatalog()
    val expectedPath = "file:/tmp/db.db"

    catalog.createNamespace(testNs, Map("location" -> expectedPath).asJava)

    assert(expectedPath === spark.catalog.getDatabase(testNs(0)).locationUri.toString)

    assert(catalog.namespaceExists(testNs) === true)
    val metadata = catalog.loadNamespaceMetadata(testNs).asScala
    checkMetadata(metadata, Map.empty)
    assert(expectedPath === metadata("location"))

    catalog.dropNamespace(testNs)
  }

  test("createNamespace: fail if namespace already exists") {
    val catalog = newCatalog()

    catalog.createNamespace(testNs, Map("property" -> "value").asJava)

    val exc = intercept[NamespaceAlreadyExistsException] {
      catalog.createNamespace(testNs, Map("property" -> "value2").asJava)
    }

    assert(exc.getMessage.contains(testNs.quoted))
    assert(catalog.namespaceExists(testNs) === true)
    checkMetadata(catalog.loadNamespaceMetadata(testNs).asScala, Map("property" -> "value"))

    catalog.dropNamespace(testNs)
  }

  test("createNamespace: fail nested namespace") {
    val catalog = newCatalog()

    // ensure the parent exists
    catalog.createNamespace(Array("db"), emptyProps)

    val exc = intercept[IllegalArgumentException] {
      catalog.createNamespace(Array("db", "nested"), emptyProps)
    }

    assert(exc.getMessage.contains("Invalid namespace name: db.nested"))

    catalog.dropNamespace(Array("db"))
  }

  test("createTable: fail if namespace does not exist") {
    val catalog = newCatalog()

    assert(catalog.namespaceExists(testNs) === false)

    val exc = intercept[NoSuchNamespaceException] {
      catalog.createTable(testIdent, schema, Array.empty, emptyProps)
    }

    assert(exc.getMessage.contains(testNs.quoted))
    assert(catalog.namespaceExists(testNs) === false)
  }

  test("dropNamespace: drop missing namespace") {
    val catalog = newCatalog()

    assert(catalog.namespaceExists(testNs) === false)

    val ret = catalog.dropNamespace(testNs)

    assert(ret === false)
  }

  test("dropNamespace: drop empty namespace") {
    val catalog = newCatalog()

    catalog.createNamespace(testNs, emptyProps)

    assert(catalog.namespaceExists(testNs) === true)

    val ret = catalog.dropNamespace(testNs)

    assert(ret === true)
    assert(catalog.namespaceExists(testNs) === false)
  }

  test("dropNamespace: fail if not empty") {
    val catalog = newCatalog()

    catalog.createNamespace(testNs, Map("property" -> "value").asJava)
    catalog.createTable(testIdent, schema, Array.empty, emptyProps)

    val exc = intercept[IllegalStateException] {
      catalog.dropNamespace(testNs)
    }

    assert(exc.getMessage.contains(testNs.quoted))
    assert(catalog.namespaceExists(testNs) === true)
    checkMetadata(catalog.loadNamespaceMetadata(testNs).asScala, Map("property" -> "value"))

    catalog.dropTable(testIdent)
    catalog.dropNamespace(testNs)
  }

  test("alterNamespace: basic behavior") {
    val catalog = newCatalog()

    catalog.createNamespace(testNs, Map("property" -> "value").asJava)

    catalog.alterNamespace(testNs, NamespaceChange.setProperty("property2", "value2"))
    checkMetadata(
      catalog.loadNamespaceMetadata(testNs).asScala,
      Map("property" -> "value", "property2" -> "value2"))

    catalog.alterNamespace(testNs,
      NamespaceChange.removeProperty("property2"),
      NamespaceChange.setProperty("property3", "value3"))
    checkMetadata(
      catalog.loadNamespaceMetadata(testNs).asScala,
      Map("property" -> "value", "property3" -> "value3"))

    catalog.alterNamespace(testNs, NamespaceChange.removeProperty("property3"))
    checkMetadata(
      catalog.loadNamespaceMetadata(testNs).asScala,
      Map("property" -> "value"))

    catalog.dropNamespace(testNs)
  }

  test("alterNamespace: update namespace location") {
    val catalog = newCatalog()
    val initialPath = sqlContext.sessionState.catalog.getDefaultDBPath(testNs(0)).toString
    val newPath = "file:/tmp/db.db"

    catalog.createNamespace(testNs, emptyProps)

    assert(initialPath === spark.catalog.getDatabase(testNs(0)).locationUri.toString)

    catalog.alterNamespace(testNs, NamespaceChange.setProperty("location", newPath))

    assert(newPath === spark.catalog.getDatabase(testNs(0)).locationUri.toString)

    catalog.dropNamespace(testNs)
  }

  test("alterNamespace: update namespace comment") {
    val catalog = newCatalog()
    val newComment = "test db"

    catalog.createNamespace(testNs, emptyProps)

    assert(spark.catalog.getDatabase(testNs(0)).description.isEmpty)

    catalog.alterNamespace(testNs, NamespaceChange.setProperty("comment", newComment))

    assert(newComment === spark.catalog.getDatabase(testNs(0)).description)

    catalog.dropNamespace(testNs)
  }

  test("alterNamespace: fail if namespace doesn't exist") {
    val catalog = newCatalog()

    assert(catalog.namespaceExists(testNs) === false)

    val exc = intercept[NoSuchNamespaceException] {
      catalog.alterNamespace(testNs, NamespaceChange.setProperty("property", "value"))
    }

    assert(exc.getMessage.contains(testNs.quoted))
  }

  test("alterNamespace: fail to remove location") {
    val catalog = newCatalog()

    catalog.createNamespace(testNs, emptyProps)

    val exc = intercept[UnsupportedOperationException] {
      catalog.alterNamespace(testNs, NamespaceChange.removeProperty("location"))
    }

    assert(exc.getMessage.contains("Cannot remove reserved property: location"))

    catalog.dropNamespace(testNs)
  }

  test("alterNamespace: fail to remove comment") {
    val catalog = newCatalog()

    catalog.createNamespace(testNs, Map("comment" -> "test db").asJava)

    val exc = intercept[UnsupportedOperationException] {
      catalog.alterNamespace(testNs, NamespaceChange.removeProperty("comment"))
    }

    assert(exc.getMessage.contains("Cannot remove reserved property: comment"))

    catalog.dropNamespace(testNs)
  }
}
