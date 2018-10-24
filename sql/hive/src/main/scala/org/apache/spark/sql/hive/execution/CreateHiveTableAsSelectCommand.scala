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

import scala.util.control.NonFatal

import org.apache.spark.sql.{AnalysisException, Row, SaveMode, SparkSession}
import org.apache.spark.sql.catalyst.catalog.CatalogTable
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.command.{DataWritingCommand, DDLUtils}
import org.apache.spark.sql.execution.datasources.{HadoopFsRelation, InsertIntoHadoopFsRelationCommand, LogicalRelation}
import org.apache.spark.sql.hive.{HiveMetastoreCatalog, HiveSessionCatalog}


/**
 * Create table and insert the query result into it.
 *
 * @param tableDesc the Table Describe, which may contain serde, storage handler etc.
 * @param query the query whose result will be insert into the new relation
 * @param mode SaveMode
 */
case class CreateHiveTableAsSelectCommand(
    tableDesc: CatalogTable,
    query: LogicalPlan,
    outputColumnNames: Seq[String],
    mode: SaveMode)
  extends DataWritingCommand {

  private val tableIdentifier = tableDesc.identifier

  override def run(sparkSession: SparkSession, child: SparkPlan): Seq[Row] = {
    val catalog = sparkSession.sessionState.catalog
    val metastoreCatalog = catalog.asInstanceOf[HiveSessionCatalog].metastoreCatalog

    // Whether this table is convertible to data source relation.
    val isConvertible = metastoreCatalog.isConvertible(tableDesc)

    if (catalog.tableExists(tableIdentifier)) {
      assert(mode != SaveMode.Overwrite,
        s"Expect the table $tableIdentifier has been dropped when the save mode is Overwrite")

      if (mode == SaveMode.ErrorIfExists) {
        throw new AnalysisException(s"$tableIdentifier already exists.")
      }
      if (mode == SaveMode.Ignore) {
        // Since the table already exists and the save mode is Ignore, we will just return.
        return Seq.empty
      }

      if (!isConvertible) {
        InsertIntoHiveTable(
          tableDesc,
          Map.empty,
          query,
          overwrite = false,
          ifPartitionNotExists = false,
          outputColumnNames = outputColumnNames).run(sparkSession, child)
      } else {
        getHadoopFsRelationCommand(sparkSession, tableDesc, mode).run(sparkSession, child)
      }
    } else {
      // TODO ideally, we should get the output data ready first and then
      // add the relation into catalog, just in case of failure occurs while data
      // processing.
      assert(tableDesc.schema.isEmpty)
      catalog.createTable(
        tableDesc.copy(schema = outputColumns.toStructType), ignoreIfExists = false)

      try {
        // Read back the metadata of the table which was created just now.
        val createdTableMeta = catalog.getTableMetadata(tableDesc.identifier)
        if (!isConvertible) {
          // For CTAS, there is no static partition values to insert.
          val partition = createdTableMeta.partitionColumnNames.map(_ -> None).toMap
          InsertIntoHiveTable(
            createdTableMeta,
            partition,
            query,
            overwrite = true,
            ifPartitionNotExists = false,
            outputColumnNames = outputColumnNames).run(sparkSession, child)
        } else {
          getHadoopFsRelationCommand(sparkSession, createdTableMeta, SaveMode.Overwrite)
            .run(sparkSession, child)
        }
      } catch {
        case NonFatal(e) =>
          // drop the created table.
          catalog.dropTable(tableIdentifier, ignoreIfNotExists = true, purge = false)
          throw e
      }
    }

    Seq.empty[Row]
  }

  // Converts Hive table to data source one and returns an `InsertIntoHadoopFsRelationCommand`
  // used to write data into it.
  private def getHadoopFsRelationCommand(
      sparkSession: SparkSession,
      metastoreCatalog: HiveMetastoreCatalog,
      tableDesc: CatalogTable,
      mode: SaveMode): InsertIntoHadoopFsRelationCommand = {
    val hiveTable = DDLUtils.readHiveTable(tableDesc)
    val hadoopRelation = metastoreCatalog.convert(hiveTable) match {
        case LogicalRelation(t: HadoopFsRelation, _, _, _) => t
        case _ => throw new AnalysisException(s"$tableIdentifier should be converted to " +
          "HadoopFsRelation.")
    }
    InsertIntoHadoopFsRelationCommand(
      hadoopRelation.location.rootPaths.head,
      Map.empty, // We don't support to convert partitioned table.
      false,
      Seq.empty, // We don't support to convert partitioned table.
      hadoopRelation.bucketSpec,
      hadoopRelation.fileFormat,
      hadoopRelation.options,
      query,
      mode,
      Some(tableDesc),
      Some(hadoopRelation.location),
      query.output.map(_.name))
  }

  override def argString: String = {
    s"[Database:${tableDesc.database}}, " +
    s"TableName: ${tableDesc.identifier.table}, " +
    s"InsertIntoHiveTable]"
  }
}
