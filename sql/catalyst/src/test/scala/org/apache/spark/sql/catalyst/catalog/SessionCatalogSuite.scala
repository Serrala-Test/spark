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

package org.apache.spark.sql.catalyst.catalog

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.{FunctionIdentifier, TableIdentifier}
import org.apache.spark.sql.catalyst.plans.logical.{Range, SubqueryAlias}


/**
 * Tests for [[SessionCatalog]] that assume that [[InMemoryCatalog]] is correctly implemented.
 *
 * Note: many of the methods here are very similar to the ones in [[CatalogTestCases]].
 * This is because [[SessionCatalog]] and [[ExternalCatalog]] share many similar method
 * signatures but do not extend a common parent. This is largely by design but
 * unfortunately leads to very similar test code in two places.
 */
class SessionCatalogSuite extends SparkFunSuite {
  private val utils = new CatalogTestUtils {
    override val tableInputFormat: String = "com.fruit.eyephone.CameraInputFormat"
    override val tableOutputFormat: String = "com.fruit.eyephone.CameraOutputFormat"
    override def newEmptyCatalog(): ExternalCatalog = new InMemoryCatalog
  }

  import utils._

  // --------------------------------------------------------------------------
  // Databases
  // --------------------------------------------------------------------------

  test("basic create and list databases") {
    val catalog = new SessionCatalog(newEmptyCatalog())
    catalog.createDatabase(newDb("default"), ignoreIfExists = true)
    assert(catalog.databaseExists("default"))
    assert(!catalog.databaseExists("testing"))
    assert(!catalog.databaseExists("testing2"))
    catalog.createDatabase(newDb("testing"), ignoreIfExists = false)
    assert(catalog.databaseExists("testing"))
    assert(catalog.listDatabases().toSet == Set("default", "testing"))
    catalog.createDatabase(newDb("testing2"), ignoreIfExists = false)
    assert(catalog.listDatabases().toSet == Set("default", "testing", "testing2"))
    assert(catalog.databaseExists("testing2"))
    assert(!catalog.databaseExists("does_not_exist"))
  }

  test("get database when a database exists") {
    val catalog = new SessionCatalog(newBasicCatalog())
    val db1 = catalog.getDatabase("db1")
    assert(db1.name == "db1")
    assert(db1.description.contains("db1"))
  }

  test("get database should throw exception when the database does not exist") {
    val catalog = new SessionCatalog(newBasicCatalog())
    intercept[AnalysisException] { catalog.getDatabase("db_that_does_not_exist") }
  }

  test("list databases without pattern") {
    val catalog = new SessionCatalog(newBasicCatalog())
    assert(catalog.listDatabases().toSet == Set("default", "db1", "db2"))
  }

  test("list databases with pattern") {
    val catalog = new SessionCatalog(newBasicCatalog())
    assert(catalog.listDatabases("db").toSet == Set.empty)
    assert(catalog.listDatabases("db*").toSet == Set("db1", "db2"))
    assert(catalog.listDatabases("*1").toSet == Set("db1"))
    assert(catalog.listDatabases("db2").toSet == Set("db2"))
  }

  test("drop database") {
    val catalog = new SessionCatalog(newBasicCatalog())
    catalog.dropDatabase("db1", ignoreIfNotExists = false, cascade = false)
    assert(catalog.listDatabases().toSet == Set("default", "db2"))
  }

  test("drop database when the database is not empty") {
    // Throw exception if there are functions left
    val externalCatalog1 = newBasicCatalog()
    val sessionCatalog1 = new SessionCatalog(externalCatalog1)
    externalCatalog1.dropTable("db2", "tbl1", ignoreIfNotExists = false)
    externalCatalog1.dropTable("db2", "tbl2", ignoreIfNotExists = false)
    intercept[AnalysisException] {
      sessionCatalog1.dropDatabase("db2", ignoreIfNotExists = false, cascade = false)
    }

    // Throw exception if there are tables left
    val externalCatalog2 = newBasicCatalog()
    val sessionCatalog2 = new SessionCatalog(externalCatalog2)
    externalCatalog2.dropFunction("db2", "func1")
    intercept[AnalysisException] {
      sessionCatalog2.dropDatabase("db2", ignoreIfNotExists = false, cascade = false)
    }

    // When cascade is true, it should drop them
    val externalCatalog3 = newBasicCatalog()
    val sessionCatalog3 = new SessionCatalog(externalCatalog3)
    externalCatalog3.dropDatabase("db2", ignoreIfNotExists = false, cascade = true)
    assert(sessionCatalog3.listDatabases().toSet == Set("default", "db1"))
  }

  test("drop database when the database does not exist") {
    val catalog = new SessionCatalog(newBasicCatalog())
    intercept[AnalysisException] {
      catalog.dropDatabase("db_that_does_not_exist", ignoreIfNotExists = false, cascade = false)
    }
    catalog.dropDatabase("db_that_does_not_exist", ignoreIfNotExists = true, cascade = false)
  }

  test("alter database") {
    val catalog = new SessionCatalog(newBasicCatalog())
    val db1 = catalog.getDatabase("db1")
    // Note: alter properties here because Hive does not support altering other fields
    catalog.alterDatabase(db1.copy(properties = Map("k" -> "v3", "good" -> "true")))
    val newDb1 = catalog.getDatabase("db1")
    assert(db1.properties.isEmpty)
    assert(newDb1.properties.size == 2)
    assert(newDb1.properties.get("k") == Some("v3"))
    assert(newDb1.properties.get("good") == Some("true"))
  }

  test("alter database should throw exception when the database does not exist") {
    val catalog = new SessionCatalog(newBasicCatalog())
    intercept[AnalysisException] {
      catalog.alterDatabase(newDb("does_not_exist"))
    }
  }

  // --------------------------------------------------------------------------
  // Tables
  // --------------------------------------------------------------------------

  test("create temporary table") {
    val catalog = new SessionCatalog(newBasicCatalog())
    val tempTable1 = Range(1, 10, 1, 10, Seq())
    val tempTable2 = Range(1, 20, 2, 10, Seq())
    catalog.createTempTable("tbl1", tempTable1, ignoreIfExists = false)
    catalog.createTempTable("tbl2", tempTable2, ignoreIfExists = false)
    assert(catalog.getTempTable("tbl1") == Some(tempTable1))
    assert(catalog.getTempTable("tbl2") == Some(tempTable2))
    assert(catalog.getTempTable("tbl3") == None)
    // Temporary table already exists
    intercept[AnalysisException] {
      catalog.createTempTable("tbl1", tempTable1, ignoreIfExists = false)
    }
    // Temporary table already exists but we override it
    catalog.createTempTable("tbl1", tempTable2, ignoreIfExists = true)
    assert(catalog.getTempTable("tbl1") == Some(tempTable2))
  }

  test("drop table") {
    val externalCatalog = newBasicCatalog()
    val sessionCatalog = new SessionCatalog(externalCatalog)
    assert(externalCatalog.listTables("db2").toSet == Set("tbl1", "tbl2"))
    sessionCatalog.dropTable("db2", TableIdentifier("tbl1"), ignoreIfNotExists = false)
    assert(externalCatalog.listTables("db2").toSet == Set("tbl2"))
    sessionCatalog.dropTable(
      "db_not_read", TableIdentifier("tbl2", Some("db2")), ignoreIfNotExists = false)
    assert(externalCatalog.listTables("db2").isEmpty)
  }

  test("drop table when database/table does not exist") {
    val catalog = new SessionCatalog(newBasicCatalog())
    // Should always throw exception when the database does not exist
    intercept[AnalysisException] {
      catalog.dropTable("unknown_db", TableIdentifier("unknown_table"), ignoreIfNotExists = false)
    }
    intercept[AnalysisException] {
      catalog.dropTable("unknown_db", TableIdentifier("unknown_table"), ignoreIfNotExists = true)
    }

    // Should throw exception when the table does not exist, if ignoreIfNotExists is false
    intercept[AnalysisException] {
      catalog.dropTable("db2", TableIdentifier("unknown_table"), ignoreIfNotExists = false)
    }
    catalog.dropTable("db2", TableIdentifier("unknown_table"), ignoreIfNotExists = true)
  }

  test("drop temp table") {
    val externalCatalog = newBasicCatalog()
    val sessionCatalog = new SessionCatalog(externalCatalog)
    val tempTable = Range(1, 10, 2, 10, Seq())
    sessionCatalog.createTempTable("tbl1", tempTable, ignoreIfExists = false)
    assert(sessionCatalog.getTempTable("tbl1") == Some(tempTable))
    assert(externalCatalog.listTables("db2").toSet == Set("tbl1", "tbl2"))
    // If database is not specified, temp table should be dropped first
    sessionCatalog.dropTable("db_not_read", TableIdentifier("tbl1"), ignoreIfNotExists = false)
    assert(sessionCatalog.getTempTable("tbl1") == None)
    assert(externalCatalog.listTables("db2").toSet == Set("tbl1", "tbl2"))
    // If database is specified, temp tables are never dropped
    sessionCatalog.createTempTable("tbl1", tempTable, ignoreIfExists = false)
    sessionCatalog.dropTable(
      "db_not_read", TableIdentifier("tbl1", Some("db2")), ignoreIfNotExists = false)
    assert(sessionCatalog.getTempTable("tbl1") == Some(tempTable))
    assert(externalCatalog.listTables("db2").toSet == Set("tbl2"))
  }

  test("rename table") {
    val externalCatalog = newBasicCatalog()
    val sessionCatalog = new SessionCatalog(externalCatalog)
    assert(externalCatalog.listTables("db2").toSet == Set("tbl1", "tbl2"))
    sessionCatalog.renameTable("db2", TableIdentifier("tbl1"), TableIdentifier("tblone"))
    assert(externalCatalog.listTables("db2").toSet == Set("tblone", "tbl2"))
    sessionCatalog.renameTable(
      "db_not_read", TableIdentifier("tbl2", Some("db2")), TableIdentifier("tbltwo", Some("db2")))
    assert(externalCatalog.listTables("db2").toSet == Set("tblone", "tbltwo"))
  }

  test("rename table when database/table does not exist") {
    val catalog = new SessionCatalog(newBasicCatalog())
    intercept[AnalysisException] {
      catalog.renameTable(
        "unknown_db", TableIdentifier("unknown_table"), TableIdentifier("unknown_table"))
    }
    intercept[AnalysisException] {
      catalog.renameTable(
        "db2", TableIdentifier("unknown_table"), TableIdentifier("unknown_table"))
    }
    // Renaming "db2.tblone" to "db1.tblones" should fail because databases don't match
    intercept[AnalysisException] {
      catalog.renameTable(
        "db_not_read", TableIdentifier("tblone", Some("db2")), TableIdentifier("x", Some("db1")))
    }
  }

  test("rename temp table") {
    val externalCatalog = newBasicCatalog()
    val sessionCatalog = new SessionCatalog(externalCatalog)
    val tempTable = Range(1, 10, 2, 10, Seq())
    sessionCatalog.createTempTable("tbl1", tempTable, ignoreIfExists = false)
    assert(sessionCatalog.getTempTable("tbl1") == Some(tempTable))
    assert(externalCatalog.listTables("db2").toSet == Set("tbl1", "tbl2"))
    // If database is not specified, temp table should be renamed first
    sessionCatalog.renameTable("db_not_read", TableIdentifier("tbl1"), TableIdentifier("tbl3"))
    assert(sessionCatalog.getTempTable("tbl1") == None)
    assert(sessionCatalog.getTempTable("tbl3") == Some(tempTable))
    assert(externalCatalog.listTables("db2").toSet == Set("tbl1", "tbl2"))
    // If database is specified, temp tables are never renamed
    sessionCatalog.renameTable(
      "db_not_read", TableIdentifier("tbl2", Some("db2")), TableIdentifier("tbl4", Some("db2")))
    assert(sessionCatalog.getTempTable("tbl3") == Some(tempTable))
    assert(sessionCatalog.getTempTable("tbl4") == None)
    assert(externalCatalog.listTables("db2").toSet == Set("tbl1", "tbl4"))
  }

  test("alter table") {
    val externalCatalog = newBasicCatalog()
    val sessionCatalog = new SessionCatalog(externalCatalog)
    val tbl1 = externalCatalog.getTable("db2", "tbl1")
    sessionCatalog.alterTable("db2", tbl1.copy(properties = Map("toh" -> "frem")))
    val newTbl1 = externalCatalog.getTable("db2", "tbl1")
    assert(!tbl1.properties.contains("toh"))
    assert(newTbl1.properties.size == tbl1.properties.size + 1)
    assert(newTbl1.properties.get("toh") == Some("frem"))
  }

  test("alter table when database/table does not exist") {
    val catalog = new SessionCatalog(newBasicCatalog())
    intercept[AnalysisException] {
      catalog.alterTable("unknown_db", newTable("tbl1", "unknown_db"))
    }
    intercept[AnalysisException] {
      catalog.alterTable("db2", newTable("unknown_table", "db2"))
    }
  }

  test("get table") {
    val externalCatalog = newBasicCatalog()
    val sessionCatalog = new SessionCatalog(externalCatalog)
    assert(sessionCatalog.getTable("db2", TableIdentifier("tbl1"))
      == externalCatalog.getTable("db2", "tbl1"))
    assert(sessionCatalog.getTable("db_not_read", TableIdentifier("tbl1", Some("db2")))
      == externalCatalog.getTable("db2", "tbl1"))
  }

  test("get table when database/table does not exist") {
    val catalog = new SessionCatalog(newBasicCatalog())
    intercept[AnalysisException] {
      catalog.getTable("unknown_db", TableIdentifier("unknown_table"))
    }
    intercept[AnalysisException] {
      catalog.getTable("db2", TableIdentifier("unknown_table"))
    }
  }

  test("lookup table relation") {
    val externalCatalog = newBasicCatalog()
    val sessionCatalog = new SessionCatalog(externalCatalog)
    val tempTable1 = Range(1, 10, 1, 10, Seq())
    sessionCatalog.createTempTable("tbl1", tempTable1, ignoreIfExists = false)
    val metastoreTable1 = externalCatalog.getTable("db2", "tbl1")
    // If we explicitly specify the database, we'll look up the relation in that database
    assert(sessionCatalog.lookupRelation("db_not_read", TableIdentifier("tbl1", Some("db2")))
      == SubqueryAlias("tbl1", CatalogRelation("db2", metastoreTable1)))
    // Otherwise, we'll first look up a temporary table with the same name
    assert(sessionCatalog.lookupRelation("db2", TableIdentifier("tbl1"))
      == SubqueryAlias("tbl1", tempTable1))
    // Then, if that does not exist, look up the relation in the current database
    sessionCatalog.dropTable("db_not_read", TableIdentifier("tbl1"), ignoreIfNotExists = false)
    assert(sessionCatalog.lookupRelation("db2", TableIdentifier("tbl1"))
      == SubqueryAlias("tbl1", CatalogRelation("db2", metastoreTable1)))
  }

  test("lookup table relation with alias") {
    val externalCatalog = newBasicCatalog()
    val sessionCatalog = new SessionCatalog(externalCatalog)
    val alias = "monster"
    val table1 = externalCatalog.getTable("db2", "tbl1")
    val withoutAlias = SubqueryAlias("tbl1", CatalogRelation("db2", table1))
    val withAlias =
      SubqueryAlias(alias,
        SubqueryAlias("tbl1",
          CatalogRelation("db2", table1, Some(alias))))
    assert(sessionCatalog.lookupRelation(
      "db_not_read", TableIdentifier("tbl1", Some("db2")), alias = None) == withoutAlias)
    assert(sessionCatalog.lookupRelation(
      "db_not_read", TableIdentifier("tbl1", Some("db2")), alias = Some(alias)) == withAlias)
  }

  test("list tables without pattern") {
    val catalog = new SessionCatalog(newBasicCatalog())
    val tempTable = Range(1, 10, 2, 10, Seq())
    catalog.createTempTable("tbl1", tempTable, ignoreIfExists = false)
    catalog.createTempTable("tbl4", tempTable, ignoreIfExists = false)
    assert(catalog.listTables("db1").toSet ==
      Set(TableIdentifier("tbl1"), TableIdentifier("tbl4")))
    assert(catalog.listTables("db2").toSet ==
      Set(TableIdentifier("tbl1"),
        TableIdentifier("tbl4"),
        TableIdentifier("tbl1", Some("db2")),
        TableIdentifier("tbl2", Some("db2"))))
    intercept[AnalysisException] { catalog.listTables("unknown_db") }
  }

  test("list tables with pattern") {
    val catalog = new SessionCatalog(newBasicCatalog())
    val tempTable = Range(1, 10, 2, 10, Seq())
    catalog.createTempTable("tbl1", tempTable, ignoreIfExists = false)
    catalog.createTempTable("tbl4", tempTable, ignoreIfExists = false)
    assert(catalog.listTables("db1", "*").toSet ==
      Set(TableIdentifier("tbl1"), TableIdentifier("tbl4")))
    assert(catalog.listTables("db2", "*").toSet ==
      Set(TableIdentifier("tbl1"),
        TableIdentifier("tbl4"),
        TableIdentifier("tbl1", Some("db2")),
        TableIdentifier("tbl2", Some("db2"))))
    assert(catalog.listTables("db2", "tbl*").toSet ==
      Set(TableIdentifier("tbl1"),
        TableIdentifier("tbl4"),
        TableIdentifier("tbl1", Some("db2")),
        TableIdentifier("tbl2", Some("db2"))))
    assert(catalog.listTables("db2", "*1").toSet ==
      Set(TableIdentifier("tbl1"), TableIdentifier("tbl1", Some("db2"))))
    intercept[AnalysisException] { catalog.listTables("unknown_db") }
  }

  // --------------------------------------------------------------------------
  // Partitions
  // --------------------------------------------------------------------------

  test("basic create and list partitions") {
    val externalCatalog = newEmptyCatalog()
    val sessionCatalog = new SessionCatalog(externalCatalog)
    sessionCatalog.createDatabase(newDb("mydb"), ignoreIfExists = false)
    sessionCatalog.createTable("mydb", newTable("tbl", "mydb"), ignoreIfExists = false)
    sessionCatalog.createPartitions(
      "mydb", TableIdentifier("tbl"), Seq(part1, part2), ignoreIfExists = false)
    assert(catalogPartitionsEqual(externalCatalog, "mydb", "tbl", Seq(part1, part2)))
  }

  test("create partitions when database/table does not exist") {
    val catalog = new SessionCatalog(newBasicCatalog())
    intercept[AnalysisException] {
      catalog.createPartitions(
        "does_not_exist", TableIdentifier("tbl1"), Seq(), ignoreIfExists = false)
    }
    intercept[AnalysisException] {
      catalog.createPartitions(
        "db2", TableIdentifier("does_not_exist"), Seq(), ignoreIfExists = false)
    }
  }

  test("create partitions that already exist") {
    val catalog = new SessionCatalog(newBasicCatalog())
    intercept[AnalysisException] {
      catalog.createPartitions(
        "db2", TableIdentifier("tbl2"), Seq(part1), ignoreIfExists = false)
    }
    catalog.createPartitions("db2", TableIdentifier("tbl2"), Seq(part1), ignoreIfExists = true)
  }

  test("drop partitions") {
    val externalCatalog1 = newBasicCatalog()
    val sessionCatalog1 = new SessionCatalog(externalCatalog1)
    assert(catalogPartitionsEqual(externalCatalog1, "db2", "tbl2", Seq(part1, part2)))
    sessionCatalog1.dropPartitions(
      "db2", TableIdentifier("tbl2"), Seq(part1.spec), ignoreIfNotExists = false)
    assert(catalogPartitionsEqual(externalCatalog1, "db2", "tbl2", Seq(part2)))
    val externalCatalog2 = newBasicCatalog()
    val sessionCatalog2 = new SessionCatalog(externalCatalog2)
    assert(catalogPartitionsEqual(externalCatalog2, "db2", "tbl2", Seq(part1, part2)))
    sessionCatalog2.dropPartitions(
      "db2", TableIdentifier("tbl2"), Seq(part1.spec, part2.spec), ignoreIfNotExists = false)
    assert(externalCatalog2.listPartitions("db2", "tbl2").isEmpty)
  }

  test("drop partitions when database/table does not exist") {
    val catalog = new SessionCatalog(newBasicCatalog())
    intercept[AnalysisException] {
      catalog.dropPartitions(
        "does_not_exist", TableIdentifier("tbl1"), Seq(), ignoreIfNotExists = false)
    }
    intercept[AnalysisException] {
      catalog.dropPartitions(
        "db2", TableIdentifier("does_not_exist"), Seq(), ignoreIfNotExists = false)
    }
  }

  test("drop partitions that do not exist") {
    val catalog = new SessionCatalog(newBasicCatalog())
    intercept[AnalysisException] {
      catalog.dropPartitions(
        "db2", TableIdentifier("tbl2"), Seq(part3.spec), ignoreIfNotExists = false)
    }
    catalog.dropPartitions(
      "db2", TableIdentifier("tbl2"), Seq(part3.spec), ignoreIfNotExists = true)
  }

  test("get partition") {
    val externalCatalog = newBasicCatalog()
    val sessionCatalog = new SessionCatalog(externalCatalog)
    assert(sessionCatalog.getPartition(
      "db2", TableIdentifier("tbl2"), part1.spec).spec == part1.spec)
    assert(sessionCatalog.getPartition(
      "db2", TableIdentifier("tbl2"), part2.spec).spec == part2.spec)
    intercept[AnalysisException] {
      sessionCatalog.getPartition("db2", TableIdentifier("tbl1"), part3.spec)
    }
  }

  test("get partition when database/table does not exist") {
    val catalog = new SessionCatalog(newBasicCatalog())
    intercept[AnalysisException] {
      catalog.getPartition("does_not_exist", TableIdentifier("tbl1"), part1.spec)
    }
    intercept[AnalysisException] {
      catalog.getPartition("db2", TableIdentifier("does_not_exist"), part1.spec)
    }
  }

  test("rename partitions") {
    val catalog = new SessionCatalog(newBasicCatalog())
    val newPart1 = part1.copy(spec = Map("a" -> "100", "b" -> "101"))
    val newPart2 = part2.copy(spec = Map("a" -> "200", "b" -> "201"))
    val newSpecs = Seq(newPart1.spec, newPart2.spec)
    catalog.renamePartitions("db2", TableIdentifier("tbl2"), Seq(part1.spec, part2.spec), newSpecs)
    assert(catalog.getPartition(
      "db2", TableIdentifier("tbl2"), newPart1.spec).spec === newPart1.spec)
    assert(catalog.getPartition(
      "db2", TableIdentifier("tbl2"), newPart2.spec).spec === newPart2.spec)
    // The old partitions should no longer exist
    intercept[AnalysisException] {
      catalog.getPartition("db2", TableIdentifier("tbl2"), part1.spec)
    }
    intercept[AnalysisException] {
      catalog.getPartition("db2", TableIdentifier("tbl2"), part2.spec)
    }
  }

  test("rename partitions when database/table does not exist") {
    val catalog = new SessionCatalog(newBasicCatalog())
    intercept[AnalysisException] {
      catalog.renamePartitions(
        "does_not_exist", TableIdentifier("tbl1"), Seq(part1.spec), Seq(part2.spec))
    }
    intercept[AnalysisException] {
      catalog.renamePartitions(
        "db2", TableIdentifier("does_not_exist"), Seq(part1.spec), Seq(part2.spec))
    }
  }

  test("alter partitions") {
    val catalog = new SessionCatalog(newBasicCatalog())
    val newLocation = newUriForDatabase()
    // alter but keep spec the same
    val oldPart1 = catalog.getPartition("db2", TableIdentifier("tbl2"), part1.spec)
    val oldPart2 = catalog.getPartition("db2", TableIdentifier("tbl2"), part2.spec)
    catalog.alterPartitions("db2", TableIdentifier("tbl2"), Seq(
      oldPart1.copy(storage = storageFormat.copy(locationUri = Some(newLocation))),
      oldPart2.copy(storage = storageFormat.copy(locationUri = Some(newLocation)))))
    val newPart1 = catalog.getPartition("db2", TableIdentifier("tbl2"), part1.spec)
    val newPart2 = catalog.getPartition("db2", TableIdentifier("tbl2"), part2.spec)
    assert(newPart1.storage.locationUri == Some(newLocation))
    assert(newPart2.storage.locationUri == Some(newLocation))
    assert(oldPart1.storage.locationUri != Some(newLocation))
    assert(oldPart2.storage.locationUri != Some(newLocation))
    // alter but change spec, should fail because new partition specs do not exist yet
    val badPart1 = part1.copy(spec = Map("a" -> "v1", "b" -> "v2"))
    val badPart2 = part2.copy(spec = Map("a" -> "v3", "b" -> "v4"))
    intercept[AnalysisException] {
      catalog.alterPartitions("db2", TableIdentifier("tbl2"), Seq(badPart1, badPart2))
    }
  }

  test("alter partitions when database/table does not exist") {
    val catalog = new SessionCatalog(newBasicCatalog())
    intercept[AnalysisException] {
      catalog.alterPartitions("does_not_exist", TableIdentifier("tbl1"), Seq(part1))
    }
    intercept[AnalysisException] {
      catalog.alterPartitions("db2", TableIdentifier("does_not_exist"), Seq(part1))
    }
  }

  // --------------------------------------------------------------------------
  // Functions
  // --------------------------------------------------------------------------

  test("basic create and list functions") {
    val externalCatalog = newEmptyCatalog()
    val sessionCatalog = new SessionCatalog(externalCatalog)
    sessionCatalog.createDatabase(newDb("mydb"), ignoreIfExists = false)
    sessionCatalog.createFunction("mydb", newFunc("myfunc"))
    assert(externalCatalog.listFunctions("mydb", "*").toSet == Set("myfunc"))
  }

  test("create function when database does not exist") {
    val catalog = new SessionCatalog(newBasicCatalog())
    intercept[AnalysisException] {
      catalog.createFunction("does_not_exist", newFunc())
    }
  }

  test("create function that already exists") {
    val catalog = new SessionCatalog(newBasicCatalog())
    intercept[AnalysisException] {
      catalog.createFunction("db2", newFunc("func1"))
    }
  }

  test("create temp function") {
    val catalog = new SessionCatalog(newBasicCatalog())
    val tempFunc1 = newFunc("temp1")
    val tempFunc2 = newFunc("temp2")
    catalog.createTempFunction(tempFunc1, ignoreIfExists = false)
    catalog.createTempFunction(tempFunc2, ignoreIfExists = false)
    assert(catalog.getTempFunction("temp1") == Some(tempFunc1))
    assert(catalog.getTempFunction("temp2") == Some(tempFunc2))
    assert(catalog.getTempFunction("temp3") == None)
    // Temporary function already exists
    intercept[AnalysisException] {
      catalog.createTempFunction(tempFunc1, ignoreIfExists = false)
    }
    // Temporary function is overridden
    val tempFunc3 = tempFunc1.copy(className = "something else")
    catalog.createTempFunction(tempFunc3, ignoreIfExists = true)
    assert(catalog.getTempFunction("temp1") == Some(tempFunc3))
  }

  test("drop function") {
    val externalCatalog = newBasicCatalog()
    val sessionCatalog = new SessionCatalog(externalCatalog)
    assert(externalCatalog.listFunctions("db2", "*").toSet == Set("func1"))
    sessionCatalog.dropFunction("db2", FunctionIdentifier("func1"))
    assert(externalCatalog.listFunctions("db2", "*").isEmpty)
  }

  test("drop function when database does not exist") {
    val catalog = new SessionCatalog(newBasicCatalog())
    intercept[AnalysisException] {
      catalog.dropFunction("does_not_exist", FunctionIdentifier("something"))
    }
  }

  test("drop function that does not exist") {
    val catalog = new SessionCatalog(newBasicCatalog())
    intercept[AnalysisException] {
      catalog.dropFunction("db2", FunctionIdentifier("does_not_exist"))
    }
  }

  test("drop temp function") {
    val catalog = new SessionCatalog(newBasicCatalog())
    val tempFunc = newFunc("func1")
    catalog.createTempFunction(tempFunc, ignoreIfExists = false)
    assert(catalog.getTempFunction("func1") == Some(tempFunc))
    catalog.dropTempFunction("func1", ignoreIfNotExists = false)
    assert(catalog.getTempFunction("func1") == None)
    intercept[AnalysisException] {
      catalog.dropTempFunction("func1", ignoreIfNotExists = false)
    }
    catalog.dropTempFunction("func1", ignoreIfNotExists = true)
  }

  test("get function") {
    val catalog = new SessionCatalog(newBasicCatalog())
    assert(catalog.getFunction("db2", FunctionIdentifier("func1")) ==
      CatalogFunction(FunctionIdentifier("func1", Some("db2")), funcClass))
    intercept[AnalysisException] {
      catalog.getFunction("db2", FunctionIdentifier("does_not_exist"))
    }
  }

  test("get function when database does not exist") {
    val catalog = new SessionCatalog(newBasicCatalog())
    intercept[AnalysisException] {
      catalog.getFunction("does_not_exist", FunctionIdentifier("func1"))
    }
  }

  test("get temp function") {
    val externalCatalog = newBasicCatalog()
    val sessionCatalog = new SessionCatalog(externalCatalog)
    val metastoreFunc = externalCatalog.getFunction("db2", "func1")
    val tempFunc = newFunc("func1").copy(className = "something weird")
    sessionCatalog.createTempFunction(tempFunc, ignoreIfExists = false)
    // If a database is specified, we'll always return the function in that database
    assert(sessionCatalog.getFunction("db2", FunctionIdentifier("func1", Some("db2")))
      == metastoreFunc)
    // If no database is specified, we'll first return temporary functions
    assert(sessionCatalog.getFunction("db2", FunctionIdentifier("func1")) == tempFunc)
    // Then, if no such temporary function exist, check the current database
    sessionCatalog.dropTempFunction("func1", ignoreIfNotExists = false)
    assert(sessionCatalog.getFunction("db2", FunctionIdentifier("func1")) == metastoreFunc)
  }

  test("rename function") {
    val catalog = new SessionCatalog(newBasicCatalog())
    val newName = "funcky"
    assert(catalog.getFunction("db2", FunctionIdentifier("func1")).className == funcClass)
    catalog.renameFunction("db2", FunctionIdentifier("func1"), FunctionIdentifier(newName))
    intercept[AnalysisException] { catalog.getFunction("db2", FunctionIdentifier("func1")) }
    assert(catalog.getFunction("db2", FunctionIdentifier(newName)).name.funcName == newName)
    assert(catalog.getFunction("db2", FunctionIdentifier(newName)).className == funcClass)
    intercept[AnalysisException] {
      catalog.renameFunction("db2", FunctionIdentifier("does_not_exist"), FunctionIdentifier("x"))
    }
  }

  test("rename function when database does not exist") {
    val catalog = new SessionCatalog(newBasicCatalog())
    intercept[AnalysisException] {
      catalog.renameFunction(
        "does_not_exist", FunctionIdentifier("func1"), FunctionIdentifier("func5"))
    }
  }

  test("rename temp function") {
    val externalCatalog = newBasicCatalog()
    val sessionCatalog = new SessionCatalog(externalCatalog)
    val tempFunc = newFunc("func1").copy(className = "something weird")
    sessionCatalog.createTempFunction(tempFunc, ignoreIfExists = false)
    // If a database is specified, we'll always rename the function in that database
    sessionCatalog.renameFunction(
      "db2", FunctionIdentifier("func1", Some("db2")), FunctionIdentifier("func3", Some("db2")))
    assert(sessionCatalog.getTempFunction("func1") == Some(tempFunc))
    assert(sessionCatalog.getTempFunction("func3") == None)
    assert(externalCatalog.listFunctions("db2", "*").toSet == Set("func3"))
    sessionCatalog.createFunction("db2", newFunc("func1", Some("db2")))
    // If no database is specified, we'll first rename temporary functions
    sessionCatalog.renameFunction("db2", FunctionIdentifier("func1"), FunctionIdentifier("func4"))
    assert(sessionCatalog.getTempFunction("func4") ==
      Some(tempFunc.copy(name = FunctionIdentifier("func4"))))
    assert(sessionCatalog.getTempFunction("func1") == None)
    assert(externalCatalog.listFunctions("db2", "*").toSet == Set("func1", "func3"))
    // Then, if no such temporary function exist, rename the function in the current database
    sessionCatalog.renameFunction("db2", FunctionIdentifier("func1"), FunctionIdentifier("func5"))
    assert(sessionCatalog.getTempFunction("func5") == None)
    assert(externalCatalog.listFunctions("db2", "*").toSet == Set("func3", "func5"))
  }

  test("alter function") {
    val catalog = new SessionCatalog(newBasicCatalog())
    assert(catalog.getFunction("db2", FunctionIdentifier("func1")).className == funcClass)
    catalog.alterFunction("db2", newFunc("func1").copy(className = "muhaha"))
    assert(catalog.getFunction("db2", FunctionIdentifier("func1")).className == "muhaha")
    intercept[AnalysisException] { catalog.alterFunction("db2", newFunc("funcky")) }
  }

  test("alter function when database does not exist") {
    val catalog = new SessionCatalog(newBasicCatalog())
    intercept[AnalysisException] {
      catalog.alterFunction("does_not_exist", newFunc())
    }
  }

  test("list functions") {
    val catalog = new SessionCatalog(newBasicCatalog())
    val tempFunc1 = newFunc("func1").copy(className = "march")
    val tempFunc2 = newFunc("yes_me").copy(className = "april")
    catalog.createFunction("db2", newFunc("func2"))
    catalog.createFunction("db2", newFunc("not_me"))
    catalog.createTempFunction(tempFunc1, ignoreIfExists = false)
    catalog.createTempFunction(tempFunc2, ignoreIfExists = false)
    assert(catalog.listFunctions("db1", "*").toSet ==
      Set(FunctionIdentifier("func1"), FunctionIdentifier("yes_me")))
    assert(catalog.listFunctions("db2", "*").toSet ==
      Set(FunctionIdentifier("func1"),
        FunctionIdentifier("yes_me"),
        FunctionIdentifier("func1", Some("db2")),
        FunctionIdentifier("func2", Some("db2")),
        FunctionIdentifier("not_me", Some("db2"))))
    assert(catalog.listFunctions("db2", "func*").toSet ==
      Set(FunctionIdentifier("func1"),
        FunctionIdentifier("func1", Some("db2")),
        FunctionIdentifier("func2", Some("db2"))))
  }

}
