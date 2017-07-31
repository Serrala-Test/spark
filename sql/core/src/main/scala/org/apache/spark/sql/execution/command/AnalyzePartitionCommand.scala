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

package org.apache.spark.sql.execution.command

import org.apache.spark.sql.{AnalysisException, Column, Row, SparkSession}
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.analysis.{NoSuchPartitionException, UnresolvedAttribute}
import org.apache.spark.sql.catalyst.catalog.{CatalogTable, CatalogTableType}
import org.apache.spark.sql.catalyst.catalog.CatalogTypes.TablePartitionSpec
import org.apache.spark.sql.catalyst.expressions.{And, EqualTo, Literal}

/**
 * Analyzes a given set of partitions to generate per-partition statistics, which will be used in
 * query optimizations.
 *
 * When `partitionSpec` is empty, statistics for all partitions are collected and stored in
 * Metastore.
 *
 * When `partitionSpec` mentions only some of the partition columns, all partitions with
 * matching values for specified columns are processed.
 *
 * If `partitionSpec` mentions unknown partition column, an `AnalysisException` is raised.
 *
 * By default, total number of rows and total size in bytes is calculated. When `noscan`
 * is `false`, only total size in bytes is computed.
 */
case class AnalyzePartitionCommand(
    tableIdent: TableIdentifier,
    partitionSpec: Map[String, Option[String]],
    noscan: Boolean = true) extends RunnableCommand {

  private def validatePartitionSpec(table: CatalogTable): Option[TablePartitionSpec] = {
    val partitionColumnNames = table.partitionColumnNames.toSet
    val invalidColumnNames = partitionSpec.keys.filterNot(partitionColumnNames.contains(_))
    if (invalidColumnNames.nonEmpty) {
      val tableId = table.identifier
      throw new AnalysisException(s"Partition specification for table '${tableId.table}' " +
        s"in database '${tableId.database.get}' refers to unknown partition column(s): " +
        invalidColumnNames.mkString(","))
    }

    val filteredSpec = partitionSpec.filter(_._2.isDefined)
    if (filteredSpec.isEmpty) {
      None
    } else {
      Some(filteredSpec.mapValues(_.get))
    }
  }

  override def run(sparkSession: SparkSession): Seq[Row] = {
    val sessionState = sparkSession.sessionState
    val db = tableIdent.database.getOrElse(sessionState.catalog.getCurrentDatabase)
    val tableIdentWithDB = TableIdentifier(tableIdent.table, Some(db))
    val tableMeta = sessionState.catalog.getTableMetadata(tableIdentWithDB)
    if (tableMeta.tableType == CatalogTableType.VIEW) {
      throw new AnalysisException("ANALYZE TABLE is not supported on views.")
    }

    val partitionValueSpec = validatePartitionSpec(tableMeta)

    val partitions = sessionState.catalog.listPartitions(tableMeta.identifier, partitionValueSpec)

    if (partitions.isEmpty) {
      if (partitionValueSpec.isDefined) {
        throw new NoSuchPartitionException(db, tableIdent.table, partitionValueSpec.get)
      } else {
        // the user requested to analyze all partitions for a table which has no partitions
        // return normally, since there is nothing to do
        return Seq.empty[Row]
      }
    }

    // Compute statistics for individual partitions
    val rowCounts: Map[TablePartitionSpec, BigInt] =
      if (noscan) {
        Map.empty
      } else {
        calculateRowCountsPerPartition(sparkSession, tableMeta, partitionValueSpec)
      }

    // Update the metastore if newly computed statistics are different from those
    // recorded in the metastore.
    val newPartitions = partitions.flatMap { p =>
      val newTotalSize = CommandUtils.calculateLocationSize(
        sessionState, tableMeta.identifier, p.storage.locationUri)
      val newRowCount = rowCounts.get(p.spec)
      val newStats = CommandUtils.compareAndGetNewStats(tableMeta.stats, newTotalSize, newRowCount)
      newStats.map(_ => p.copy(stats = newStats))
    }

    if (newPartitions.nonEmpty) {
      sessionState.catalog.alterPartitions(tableMeta.identifier, newPartitions)
    }

    Seq.empty[Row]
  }

  private def calculateRowCountsPerPartition(
      sparkSession: SparkSession,
      tableMeta: CatalogTable,
      partitionValueSpec: Option[TablePartitionSpec]): Map[TablePartitionSpec, BigInt] = {
    val filter = if (partitionValueSpec.isDefined) {
      val filters = partitionValueSpec.get.map {
        case (columnName, value) => EqualTo(UnresolvedAttribute(columnName), Literal(value))
      }
      filters.reduce(And)
    } else {
      Literal.TrueLiteral
    }

    val tableDf = sparkSession.table(tableMeta.identifier)
    val partitionColumns = tableMeta.partitionColumnNames.map(Column(_))

    val df = tableDf.filter(Column(filter)).groupBy(partitionColumns: _*).count()

    df.collect().map { r =>
      val partitionColumnValues = partitionColumns.indices.map(r.get(_).toString)
      val spec = tableMeta.partitionColumnNames.zip(partitionColumnValues).toMap
      val count = BigInt(r.getLong(partitionColumns.size))
      (spec, count)
    }.toMap
  }
}
