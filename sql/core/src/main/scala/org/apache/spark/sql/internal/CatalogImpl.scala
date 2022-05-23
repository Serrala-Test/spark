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

package org.apache.spark.sql.internal

import scala.reflect.runtime.universe.TypeTag
import scala.util.control.NonFatal

import org.apache.spark.sql._
import org.apache.spark.sql.catalog.{Catalog, Column, Database, Function, Table}
import org.apache.spark.sql.catalyst.{DefinedByConstructorParams, FunctionIdentifier, TableIdentifier}
import org.apache.spark.sql.catalyst.analysis.{ResolvedTable, ResolvedView, UnresolvedDBObjectName, UnresolvedNamespace, UnresolvedTable, UnresolvedTableOrView}
import org.apache.spark.sql.catalyst.catalog._
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.catalyst.plans.logical.{CreateTable, LocalRelation, RecoverPartitions, ShowTables, SubqueryAlias, TableSpec, View}
import org.apache.spark.sql.catalyst.util.CharVarcharUtils
import org.apache.spark.sql.connector.catalog.TableCatalog
import org.apache.spark.sql.errors.QueryCompilationErrors
import org.apache.spark.sql.execution.datasources.DataSource
import org.apache.spark.sql.types.StructType
import org.apache.spark.storage.StorageLevel


/**
 * Internal implementation of the user-facing `Catalog`.
 */
class CatalogImpl(sparkSession: SparkSession) extends Catalog {

  private def sessionCatalog: SessionCatalog = sparkSession.sessionState.catalog

  private def requireDatabaseExists(dbName: String): Unit = {
    if (!sessionCatalog.databaseExists(dbName)) {
      throw QueryCompilationErrors.databaseDoesNotExistError(dbName)
    }
  }

  private def requireTableExists(dbName: String, tableName: String): Unit = {
    if (!sessionCatalog.tableExists(TableIdentifier(tableName, Some(dbName)))) {
      throw QueryCompilationErrors.tableDoesNotExistInDatabaseError(tableName, dbName)
    }
  }

  /**
   * Returns the current default database in this session.
   */
  override def currentDatabase: String = sessionCatalog.getCurrentDatabase

  /**
   * Sets the current default database in this session.
   */
  @throws[AnalysisException]("database does not exist")
  override def setCurrentDatabase(dbName: String): Unit = {
    requireDatabaseExists(dbName)
    sessionCatalog.setCurrentDatabase(dbName)
  }

  /**
   * Returns a list of databases available across all sessions.
   */
  override def listDatabases(): Dataset[Database] = {
    val databases = sessionCatalog.listDatabases().map(makeDatabase)
    CatalogImpl.makeDataset(databases, sparkSession)
  }

  private def makeDatabase(dbName: String): Database = {
    val metadata = sessionCatalog.getDatabaseMetadata(dbName)
    new Database(
      name = metadata.name,
      description = metadata.description,
      locationUri = CatalogUtils.URIToString(metadata.locationUri))
  }

  /**
   * Returns a list of tables in the current database.
   * This includes all temporary tables.
   */
  override def listTables(): Dataset[Table] = {
    listTables(currentDatabase)
  }

  /**
   * Returns a list of tables in the specified database.
   * This includes all temporary tables.
   */
  @throws[AnalysisException]("database does not exist")
  override def listTables(dbName: String): Dataset[Table] = {
    if (sessionCatalog.databaseExists(dbName)) {
      val tables = sessionCatalog.listTables(dbName).map(makeTable)
      CatalogImpl.makeDataset(tables, sparkSession)
    } else {
      val ident = sparkSession.sessionState.sqlParser.parseMultipartIdentifier(dbName)
      val plan = ShowTables(UnresolvedNamespace(ident), None)
      val ret = sparkSession.sessionState.executePlan(plan).toRdd.collect()
      val tables = ret
        .map(row => ident ++ Seq(row.getString(1)))
        .map(makeTable)
      CatalogImpl.makeDataset(tables, sparkSession)
    }
  }

  /**
   * Returns a Table for the given table/view or temporary view.
   *
   * Note that this function requires the table already exists in the Catalog.
   *
   * If the table metadata retrieval failed due to any reason (e.g., table serde class
   * is not accessible or the table type is not accepted by Spark SQL), this function
   * still returns the corresponding Table without the description and tableType)
   */
  private def makeTable(tableIdent: TableIdentifier): Table = {
    val metadata = try {
      Some(sessionCatalog.getTempViewOrPermanentTableMetadata(tableIdent))
    } catch {
      case NonFatal(_) => None
    }
    val isTemp = sessionCatalog.isTempView(tableIdent)
    val qualifier =
      Array(metadata.map(_.identifier.database).getOrElse(tableIdent.database).orNull)
    new Table(
      name = tableIdent.table,
      qualifier = qualifier,
      description = metadata.map(_.comment.orNull).orNull,
      tableType = if (isTemp) "TEMPORARY" else metadata.map(_.tableType.name).orNull,
      isTemporary = isTemp)
  }

  private def makeTable(ident: Seq[String]): Table = {
    val plan = UnresolvedTableOrView(ident, "Catalog.listTables", true)
    val node = sparkSession.sessionState.executePlan(plan).analyzed
    node match {
      case t: ResolvedTable =>
        val isExternal = t.table.properties().getOrDefault(
          TableCatalog.PROP_EXTERNAL, "false").equals("true")
        new Table(
          name = t.identifier.name(),
          qualifier = t.identifier.namespace(),
          description = t.table.properties().get("comment"),
          tableType =
            if (isExternal) CatalogTableType.EXTERNAL.name
            else CatalogTableType.MANAGED.name,
          isTemporary = false)
      case v: ResolvedView =>
        new Table(
          name = v.identifier.name(),
          qualifier = v.identifier.namespace(),
          description = "",
          tableType = if (v.isTemp) "TEMPORARY" else "VIEW",
          isTemporary = v.isTemp)
      case _ => throw QueryCompilationErrors.tableOrViewNotFound(ident)
    }
  }

  /**
   * Returns a list of functions registered in the current database.
   * This includes all temporary functions
   */
  override def listFunctions(): Dataset[Function] = {
    listFunctions(currentDatabase)
  }

  /**
   * Returns a list of functions registered in the specified database.
   * This includes all temporary functions
   */
  @throws[AnalysisException]("database does not exist")
  override def listFunctions(dbName: String): Dataset[Function] = {
    requireDatabaseExists(dbName)
    val functions = sessionCatalog.listFunctions(dbName).map { case (functIdent, _) =>
      makeFunction(functIdent)
    }
    CatalogImpl.makeDataset(functions, sparkSession)
  }

  private def makeFunction(funcIdent: FunctionIdentifier): Function = {
    val metadata = sessionCatalog.lookupFunctionInfo(funcIdent)
    new Function(
      name = metadata.getName,
      database = metadata.getDb,
      description = null, // for now, this is always undefined
      className = metadata.getClassName,
      isTemporary = metadata.getDb == null)
  }

  /**
   * Returns a list of columns for the given table/view or temporary view.
   */
  @throws[AnalysisException]("table does not exist")
  override def listColumns(tableName: String): Dataset[Column] = {
    val tableIdent = sparkSession.sessionState.sqlParser.parseTableIdentifier(tableName)
    listColumns(tableIdent)
  }

  /**
   * Returns a list of columns for the given table/view or temporary view in the specified database.
   */
  @throws[AnalysisException]("database or table does not exist")
  override def listColumns(dbName: String, tableName: String): Dataset[Column] = {
    requireTableExists(dbName, tableName)
    listColumns(TableIdentifier(tableName, Some(dbName)))
  }

  private def listColumns(tableIdentifier: TableIdentifier): Dataset[Column] = {
    val tableMetadata = sessionCatalog.getTempViewOrPermanentTableMetadata(tableIdentifier)

    val partitionColumnNames = tableMetadata.partitionColumnNames.toSet
    val bucketColumnNames = tableMetadata.bucketSpec.map(_.bucketColumnNames).getOrElse(Nil).toSet
    val columns = tableMetadata.schema.map { c =>
      new Column(
        name = c.name,
        description = c.getComment().orNull,
        dataType = CharVarcharUtils.getRawType(c.metadata).getOrElse(c.dataType).catalogString,
        nullable = c.nullable,
        isPartition = partitionColumnNames.contains(c.name),
        isBucket = bucketColumnNames.contains(c.name))
    }
    CatalogImpl.makeDataset(columns, sparkSession)
  }

  /**
   * Gets the database with the specified name. This throws an `AnalysisException` when no
   * `Database` can be found.
   */
  override def getDatabase(dbName: String): Database = {
    makeDatabase(dbName)
  }

  /**
   * Gets the table or view with the specified name. This table can be a temporary view or a
   * table/view. This throws an `AnalysisException` when no `Table` can be found.
   */
  override def getTable(tableName: String): Table = {
    val tableIdent = sparkSession.sessionState.sqlParser.parseTableIdentifier(tableName)
    getTable(tableIdent.database.orNull, tableIdent.table)
  }

  /**
   * Gets the table or view with the specified name in the specified database. This throws an
   * `AnalysisException` when no `Table` can be found.
   */
  override def getTable(dbName: String, tableName: String): Table = {
    if (tableExists(dbName, tableName)) {
      makeTable(TableIdentifier(tableName, Option(dbName)))
    } else {
      throw QueryCompilationErrors.tableOrViewNotFoundInDatabaseError(tableName, dbName)
    }
  }

  /**
   * Gets the function with the specified name. This function can be a temporary function or a
   * function. This throws an `AnalysisException` when no `Function` can be found.
   */
  override def getFunction(functionName: String): Function = {
    val functionIdent = sparkSession.sessionState.sqlParser.parseFunctionIdentifier(functionName)
    getFunction(functionIdent.database.orNull, functionIdent.funcName)
  }

  /**
   * Gets the function with the specified name. This returns `None` when no `Function` can be
   * found.
   */
  override def getFunction(dbName: String, functionName: String): Function = {
    makeFunction(FunctionIdentifier(functionName, Option(dbName)))
  }

  /**
   * Checks if the database with the specified name exists.
   */
  override def databaseExists(dbName: String): Boolean = {
    sessionCatalog.databaseExists(dbName)
  }

  /**
   * Checks if the table or view with the specified name exists. This can either be a temporary
   * view or a table/view.
   */
  override def tableExists(tableName: String): Boolean = {
    val tableIdent = sparkSession.sessionState.sqlParser.parseTableIdentifier(tableName)
    tableExists(tableIdent.database.orNull, tableIdent.table)
  }

  /**
   * Checks if the table or view with the specified name exists in the specified database.
   */
  override def tableExists(dbName: String, tableName: String): Boolean = {
    val tableIdent = TableIdentifier(tableName, Option(dbName))
    sessionCatalog.isTempView(tableIdent) || sessionCatalog.tableExists(tableIdent)
  }

  /**
   * Checks if the function with the specified name exists. This can either be a temporary function
   * or a function.
   */
  override def functionExists(functionName: String): Boolean = {
    val functionIdent = sparkSession.sessionState.sqlParser.parseFunctionIdentifier(functionName)
    functionExists(functionIdent.database.orNull, functionIdent.funcName)
  }

  /**
   * Checks if the function with the specified name exists in the specified database.
   */
  override def functionExists(dbName: String, functionName: String): Boolean = {
    sessionCatalog.functionExists(FunctionIdentifier(functionName, Option(dbName)))
  }

  /**
   * Creates a table from the given path and returns the corresponding DataFrame.
   * It will use the default data source configured by spark.sql.sources.default.
   *
   * @group ddl_ops
   * @since 2.2.0
   */
  override def createTable(tableName: String, path: String): DataFrame = {
    val dataSourceName = sparkSession.sessionState.conf.defaultDataSourceName
    createTable(tableName, path, dataSourceName)
  }

  /**
   * Creates a table from the given path and returns the corresponding
   * DataFrame.
   *
   * @group ddl_ops
   * @since 2.2.0
   */
  override def createTable(tableName: String, path: String, source: String): DataFrame = {
    createTable(tableName, source, Map("path" -> path))
  }

  /**
   * (Scala-specific)
   * Creates a table based on the dataset in a data source and a set of options.
   * Then, returns the corresponding DataFrame.
   *
   * @group ddl_ops
   * @since 2.2.0
   */
  override def createTable(
      tableName: String,
      source: String,
      options: Map[String, String]): DataFrame = {
    createTable(tableName, source, new StructType, options)
  }

  /**
   * (Scala-specific)
   * Creates a table based on the dataset in a data source and a set of options.
   * Then, returns the corresponding DataFrame.
   *
   * @group ddl_ops
   * @since 3.1.0
   */
  override def createTable(
      tableName: String,
      source: String,
      description: String,
      options: Map[String, String]): DataFrame = {
    createTable(tableName, source, new StructType, description, options)
  }

  /**
   * (Scala-specific)
   * Creates a table based on the dataset in a data source, a schema and a set of options.
   * Then, returns the corresponding DataFrame.
   *
   * @group ddl_ops
   * @since 2.2.0
   */
  override def createTable(
      tableName: String,
      source: String,
      schema: StructType,
      options: Map[String, String]): DataFrame = {
    createTable(
      tableName = tableName,
      source = source,
      schema = schema,
      description = "",
      options = options
    )
  }

  /**
   * (Scala-specific)
   * Creates a table based on the dataset in a data source, a schema and a set of options.
   * Then, returns the corresponding DataFrame.
   *
   * @group ddl_ops
   * @since 3.1.0
   */
  override def createTable(
      tableName: String,
      source: String,
      schema: StructType,
      description: String,
      options: Map[String, String]): DataFrame = {
    val ident = sparkSession.sessionState.sqlParser.parseMultipartIdentifier(tableName)
    val storage = DataSource.buildStorageFormatFromOptions(options)
    val tableType = if (storage.locationUri.isDefined) {
      CatalogTableType.EXTERNAL
    } else {
      CatalogTableType.MANAGED
    }
    val location = if (storage.locationUri.isDefined) {
      val locationStr = storage.locationUri.get.toString
      Some(locationStr)
    } else {
      None
    }

    val tableSpec = TableSpec(
      properties = Map(),
      provider = Some(source),
      options = options,
      location = location,
      comment = { if (description.isEmpty) None else Some(description) },
      serde = None,
      external = tableType == CatalogTableType.EXTERNAL)

    val plan = CreateTable(
      name = UnresolvedDBObjectName(ident, isNamespace = false),
      tableSchema = schema,
      partitioning = Seq(),
      tableSpec = tableSpec,
      ignoreIfExists = false)

    sparkSession.sessionState.executePlan(plan).toRdd
    sparkSession.table(tableName)
  }

  /**
   * Drops the local temporary view with the given view name in the catalog.
   * If the view has been cached/persisted before, it's also unpersisted.
   *
   * @param viewName the identifier of the temporary view to be dropped.
   * @group ddl_ops
   * @since 2.0.0
   */
  override def dropTempView(viewName: String): Boolean = {
    sparkSession.sessionState.catalog.getTempView(viewName).exists { viewDef =>
      uncacheView(viewDef)
      sessionCatalog.dropTempView(viewName)
    }
  }

  /**
   * Drops the global temporary view with the given view name in the catalog.
   * If the view has been cached/persisted before, it's also unpersisted.
   *
   * @param viewName the identifier of the global temporary view to be dropped.
   * @group ddl_ops
   * @since 2.1.0
   */
  override def dropGlobalTempView(viewName: String): Boolean = {
    sparkSession.sessionState.catalog.getGlobalTempView(viewName).exists { viewDef =>
      uncacheView(viewDef)
      sessionCatalog.dropGlobalTempView(viewName)
    }
  }

  private def uncacheView(viewDef: View): Unit = {
    try {
      // If view text is defined, it means we are not storing analyzed logical plan for the view
      // and instead its behavior follows that of a permanent view (see SPARK-33142 for more
      // details). Therefore, when uncaching the view we should also do in a cascade fashion, the
      // same way as how a permanent view is handled. This also avoids a potential issue where a
      // dependent view becomes invalid because of the above while its data is still cached.
      val viewText = viewDef.desc.viewText
      val plan = sparkSession.sessionState.executePlan(viewDef)
      sparkSession.sharedState.cacheManager.uncacheQuery(
        sparkSession, plan.analyzed, cascade = viewText.isDefined)
    } catch {
      case NonFatal(_) => // ignore
    }
  }

  /**
   * Recovers all the partitions in the directory of a table and update the catalog.
   * Only works with a partitioned table, and not a temporary view.
   *
   * @param tableName is either a qualified or unqualified name that designates a table.
   *                  If no database identifier is provided, it refers to a table in the
   *                  current database.
   * @group ddl_ops
   * @since 2.1.1
   */
  override def recoverPartitions(tableName: String): Unit = {
    val multiPartIdent = sparkSession.sessionState.sqlParser.parseMultipartIdentifier(tableName)
    sparkSession.sessionState.executePlan(
      RecoverPartitions(
        UnresolvedTable(multiPartIdent, "recoverPartitions()", None))).toRdd
  }

  /**
   * Returns true if the table or view is currently cached in-memory.
   *
   * @group cachemgmt
   * @since 2.0.0
   */
  override def isCached(tableName: String): Boolean = {
    sparkSession.sharedState.cacheManager.lookupCachedData(sparkSession.table(tableName)).nonEmpty
  }

  /**
   * Caches the specified table or view in-memory.
   *
   * @group cachemgmt
   * @since 2.0.0
   */
  override def cacheTable(tableName: String): Unit = {
    sparkSession.sharedState.cacheManager.cacheQuery(sparkSession.table(tableName), Some(tableName))
  }

  /**
   * Caches the specified table or view with the given storage level.
   *
   * @group cachemgmt
   * @since 2.3.0
   */
  override def cacheTable(tableName: String, storageLevel: StorageLevel): Unit = {
    sparkSession.sharedState.cacheManager.cacheQuery(
      sparkSession.table(tableName), Some(tableName), storageLevel)
  }

  /**
   * Removes the specified table or view from the in-memory cache.
   *
   * @group cachemgmt
   * @since 2.0.0
   */
  override def uncacheTable(tableName: String): Unit = {
    val tableIdent = sparkSession.sessionState.sqlParser.parseTableIdentifier(tableName)
    sessionCatalog.lookupTempView(tableIdent).map(uncacheView).getOrElse {
      sparkSession.sharedState.cacheManager.uncacheQuery(sparkSession.table(tableName),
        cascade = true)
    }
  }

  /**
   * Removes all cached tables or views from the in-memory cache.
   *
   * @group cachemgmt
   * @since 2.0.0
   */
  override def clearCache(): Unit = {
    sparkSession.sharedState.cacheManager.clearCache()
  }

  /**
   * Returns true if the [[Dataset]] is currently cached in-memory.
   *
   * @group cachemgmt
   * @since 2.0.0
   */
  protected[sql] def isCached(qName: Dataset[_]): Boolean = {
    sparkSession.sharedState.cacheManager.lookupCachedData(qName).nonEmpty
  }

  /**
   * The method fully refreshes a table or view with the given name including:
   *   1. The relation cache in the session catalog. The method removes table entry from the cache.
   *   2. The file indexes of all relations used by the given view.
   *   3. Table/View schema in the Hive Metastore if the SQL config
   *      `spark.sql.hive.caseSensitiveInferenceMode` is set to `INFER_AND_SAVE`.
   *   4. Cached data of the given table or view, and all its dependents that refer to it.
   *      Existing cached data will be cleared and the cache will be lazily filled when
   *      the next time the table/view or the dependents are accessed.
   *
   * The method does not do:
   *   - schema inference for file source tables
   *   - statistics update
   *
   * The method is supposed to be used in all cases when need to refresh table/view data
   * and meta-data.
   *
   * @group cachemgmt
   * @since 2.0.0
   */
  override def refreshTable(tableName: String): Unit = {
    val tableIdent = sparkSession.sessionState.sqlParser.parseTableIdentifier(tableName)
    val relation = sparkSession.table(tableIdent).queryExecution.analyzed

    relation.refresh()

    // Temporary and global temporary views are not supposed to be put into the relation cache
    // since they are tracked separately.
    if (!sessionCatalog.isTempView(tableIdent)) {
      sessionCatalog.invalidateCachedTable(tableIdent)
    }

    // Re-caches the logical plan of the relation.
    // Note this is a no-op for the relation itself if it's not cached, but will clear all
    // caches referencing this relation. If this relation is cached as an InMemoryRelation,
    // this will clear the relation cache and caches of all its dependents.
    relation match {
      case SubqueryAlias(_, relationPlan) =>
        sparkSession.sharedState.cacheManager.recacheByPlan(sparkSession, relationPlan)
      case _ =>
        throw QueryCompilationErrors.unexpectedTypeOfRelationError(relation, tableName)
    }
  }

  /**
   * Refreshes the cache entry and the associated metadata for all Dataset (if any), that contain
   * the given data source path. Path matching is by prefix, i.e. "/" would invalidate
   * everything that is cached.
   *
   * @group cachemgmt
   * @since 2.0.0
   */
  override def refreshByPath(resourcePath: String): Unit = {
    sparkSession.sharedState.cacheManager.recacheByPath(sparkSession, resourcePath)
  }
}


private[sql] object CatalogImpl {

  def makeDataset[T <: DefinedByConstructorParams: TypeTag](
      data: Seq[T],
      sparkSession: SparkSession): Dataset[T] = {
    val enc = ExpressionEncoder[T]()
    val toRow = enc.createSerializer()
    val encoded = data.map(d => toRow(d).copy())
    val plan = new LocalRelation(enc.schema.toAttributes, encoded)
    val queryExecution = sparkSession.sessionState.executePlan(plan)
    new Dataset[T](queryExecution, enc)
  }

}
