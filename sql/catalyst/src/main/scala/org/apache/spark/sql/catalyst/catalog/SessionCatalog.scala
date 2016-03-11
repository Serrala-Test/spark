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

import java.util.concurrent.ConcurrentHashMap

import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan


/**
 * An internal catalog that is used by a Spark Session. This internal catalog serves as a
 * proxy to the underlying metastore (e.g. Hive Metastore) and it also manages temporary
 * tables and functions of the Spark Session that it belongs to.
 */
abstract class SessionCatalog(catalog: ExternalCatalog) {
  import ExternalCatalog._

  private[this] val tempTables = new ConcurrentHashMap[String, LogicalPlan]

  private[this] val tempFunctions = new ConcurrentHashMap[String, CatalogFunction]

  // --------------------------------------------------------------------------
  // Databases
  // All methods in this category interact directly with the underlying catalog.
  // --------------------------------------------------------------------------

  def createDatabase(dbDefinition: CatalogDatabase, ignoreIfExists: Boolean): Unit

  def dropDatabase(db: String, ignoreIfNotExists: Boolean, cascade: Boolean): Unit

  def alterDatabase(dbDefinition: CatalogDatabase): Unit

  def getDatabase(db: String): CatalogDatabase

  def databaseExists(db: String): Boolean

  def listDatabases(): Seq[String]

  def listDatabases(pattern: String): Seq[String]

  // --------------------------------------------------------------------------
  // Tables
  // --------------------------------------------------------------------------

  // --------------------------------------------------------------------------
  // Tables: Methods for metastore tables.
  // Methods in this category are only used for metastore tables, which store
  // metadata in the underlying catalog.
  // --------------------------------------------------------------------------

  def createTable(db: String, tableDefinition: CatalogTable, ignoreIfExists: Boolean): Unit

  /**
   * Alters a table whose name matches the one specified in `tableDefinition`,
   * assuming the table exists.
   *
   * Note: If the underlying implementation does not support altering a certain field,
   * this becomes a no-op.
   */
  def alterTable(db: String, tableDefinition: CatalogTable): Unit

  /**
   * Retrieves the metadata of a table called `table` in the database `db`.
   */
  def getTable(db: String, table: String): CatalogTable

  // --------------------------------------------------------------------------
  // Tables: Methods for metastore tables or temp tables.
  // --------------------------------------------------------------------------

  /**
   * Creates a temporary table. If there is already a temporary table having the same name,
   * the table definition of that table will be updated to the new definition.
   */
  // TODO: Should we automatically overwrite the existing temp table?
  // Postgres and Hive will complain if a temp table is already defined.
  def createTempTable(tableIdent: TableIdentifier, tableDefinition: LogicalPlan): Unit

  def renameTable(
      specifiedDB: Option[String],
      currentDB: String,
      oldName: String,
      newName: String): Unit

  /**
   * Drops a table. If a database name is not provided, this method will drop the table with
   * the given name from the temporary table name space as well as the table
   * in the current database. If a database name is provided, this method only drops the table
   * with the given name from the given database.
   */
  // TODO: When a temp table and a table in the current db have the same name, should we
  // only drop the temp table when a database is not provided (Postgresql's semantic)?
  def dropTable(
      tableIdent: TableIdentifier,
      currentDB: String,
      ignoreIfNotExists: Boolean): Unit

  /**
   * Returns a [[LogicalPlan]] representing the requested table. This method is used
   * when we need to create a query plan for a given table.
   *
   * This method is different from `getTable`, which only returns the metadata of the table
   * in the form of [[CatalogTable]]. The [[LogicalPlan]] returned by this method contains
   * the metadata of the table in the form of [[CatalogTable]].
   */
  def lookupRelation(tableIdent: TableIdentifier, alias: Option[String] = None): LogicalPlan

  def listTables(specifiedDB: Option[String], currentDB: String): Seq[String]

  def listTables(specifiedDB: Option[String], currentDB: String, pattern: String): Seq[String]

  // --------------------------------------------------------------------------
  // Partitions
  // All methods in this category interact directly with the underlying catalog.
  // --------------------------------------------------------------------------
  // TODO: We need to figure out how these methods interact with our data source tables.
  // For data source tables, we do not store values of partitioning columns in the metastore.
  // For now, partition values of a data source table will be automatically discovered
  // when we load the table.

  def createPartitions(
      db: String,
      table: String,
      parts: Seq[CatalogTablePartition],
      ignoreIfExists: Boolean): Unit

  def dropPartitions(
      db: String,
      table: String,
      parts: Seq[TablePartitionSpec],
      ignoreIfNotExists: Boolean): Unit

  /**
   * Override the specs of one or many existing table partitions, assuming they exist.
   * This assumes index i of `specs` corresponds to index i of `newSpecs`.
   */
  def renamePartitions(
      db: String,
      table: String,
      specs: Seq[TablePartitionSpec],
      newSpecs: Seq[TablePartitionSpec]): Unit

  /**
   * Alter one or many table partitions whose specs that match those specified in `parts`,
   * assuming the partitions exist.
   *
   * Note: If the underlying implementation does not support altering a certain field,
   * this becomes a no-op.
   */
  def alterPartitions(
      db: String,
      table: String,
      parts: Seq[CatalogTablePartition]): Unit

  def getPartition(db: String, table: String, spec: TablePartitionSpec): CatalogTablePartition

  def listPartitions(db: String, table: String): Seq[CatalogTablePartition]

  // --------------------------------------------------------------------------
  // Functions
  // --------------------------------------------------------------------------

  // --------------------------------------------------------------------------
  // Functions: Methods for metastore functions (permanent UDFs).
  // --------------------------------------------------------------------------

  def createFunction(db: String, funcDefinition: CatalogFunction): Unit

  /**
   * Drops a permanent function with the given name from the given database.
   */
  def dropFunction(db: String, funcName: String): Unit

  // --------------------------------------------------------------------------
  // Functions: Methods for metastore functions (permanent UDFs) or temp functions.
  // --------------------------------------------------------------------------

  def createTempFunction(funcDefinition: CatalogFunction): Unit

  /**
   * Drops a temporary function with the given name.
   */
  // TODO: The reason that we distinguish dropFunction and dropTempFunction is that
  // Hive has DROP FUNCTION and DROP TEMPORARY FUNCTION. We may want to consolidate
  // dropFunction and dropTempFunction.
  def dropTempFunction(funcName: String): Unit

  def renameFunction(
      specifiedDB: Option[String],
      currentDB: String,
      oldName: String,
      newName: String): Unit

  /**
   * Alter a function whose name that matches the one specified in `funcDefinition`,
   * assuming the function exists.
   *
   * Note: If the underlying implementation does not support altering a certain field,
   * this becomes a no-op.
   */
  def alterFunction(
      specifiedDB: Option[String],
      currentDB: String,
      funcDefinition: CatalogFunction): Unit

  def getFunction(
      specifiedDB: Option[String],
      currentDB: String,
      funcName: String): CatalogFunction

  def listFunctions(
      specifiedDB: Option[String],
      currentDB: String,
      pattern: String): Seq[String]

}
