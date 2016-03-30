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

package org.apache.spark.sql.execution.datasources

import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeReference}
import org.apache.spark.sql.catalyst.plans.logical
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.execution.command.RunnableCommand
import org.apache.spark.sql.types._

/**
 * Returned for the "DESCRIBE [EXTENDED] [dbName.]tableName" command.
 *
 * @param table The table to be described.
 * @param isExtended True if "DESCRIBE EXTENDED" is used. Otherwise, false.
 *                   It is effective only when the table is a Hive table.
 */
case class DescribeCommand(
    table: TableIdentifier,
    isExtended: Boolean)
  extends LogicalPlan with logical.Command {

  override def children: Seq[LogicalPlan] = Seq.empty

  override val output: Seq[Attribute] = Seq(
    // Column names are based on Hive.
    AttributeReference("col_name", StringType, nullable = false,
      new MetadataBuilder().putString("comment", "name of the column").build())(),
    AttributeReference("data_type", StringType, nullable = false,
      new MetadataBuilder().putString("comment", "data type of the column").build())(),
    AttributeReference("comment", StringType, nullable = true,
      new MetadataBuilder().putString("comment", "comment of the column").build())()
  )
}

/**
  * Used to represent the operation of create table using a data source.
 *
  * @param allowExisting If it is true, we will do nothing when the table already exists.
  *                      If it is false, an exception will be thrown
  */
case class CreateTableUsing(
    tableIdent: TableIdentifier,
    userSpecifiedSchema: Option[StructType],
    provider: String,
    temporary: Boolean,
    options: Map[String, String],
    allowExisting: Boolean,
    managedIfNoPath: Boolean) extends LogicalPlan with logical.Command {

  override def output: Seq[Attribute] = Seq.empty
  override def children: Seq[LogicalPlan] = Seq.empty
}

/**
 * A node used to support CTAS statements and saveAsTable for the data source API.
 * This node is a [[logical.UnaryNode]] instead of a [[logical.Command]] because we want the
 * analyzer can analyze the logical plan that will be used to populate the table.
 * So, [[PreWriteCheck]] can detect cases that are not allowed.
 */
case class CreateTableUsingAsSelect(
    tableIdent: TableIdentifier,
    provider: String,
    temporary: Boolean,
    partitionColumns: Array[String],
    bucketSpec: Option[BucketSpec],
    mode: SaveMode,
    options: Map[String, String],
    child: LogicalPlan) extends logical.UnaryNode {
  override def output: Seq[Attribute] = Seq.empty[Attribute]
}

case class CreateTempTableUsing(
    tableIdent: TableIdentifier,
    userSpecifiedSchema: Option[StructType],
    provider: String,
    options: Map[String, String]) extends RunnableCommand {

  if (tableIdent.database.isDefined) {
    throw new AnalysisException(
      s"Temporary table '$tableIdent' should not have specified a database")
  }

  def run(sqlContext: SQLContext): Seq[Row] = {
    val dataSource = DataSource(
      sqlContext,
      userSpecifiedSchema = userSpecifiedSchema,
      className = provider,
      options = options)
    sqlContext.sessionState.catalog.createTempTable(
      tableIdent.table,
      Dataset.ofRows(sqlContext, LogicalRelation(dataSource.resolveRelation())).logicalPlan,
      overrideIfExists = true)

    Seq.empty[Row]
  }
}

case class CreateTempTableUsingAsSelect(
    tableIdent: TableIdentifier,
    provider: String,
    partitionColumns: Array[String],
    mode: SaveMode,
    options: Map[String, String],
    query: LogicalPlan) extends RunnableCommand {

  if (tableIdent.database.isDefined) {
    throw new AnalysisException(
      s"Temporary table '$tableIdent' should not have specified a database")
  }

  override def run(sqlContext: SQLContext): Seq[Row] = {
    val df = Dataset.ofRows(sqlContext, query)
    val dataSource = DataSource(
      sqlContext,
      className = provider,
      partitionColumns = partitionColumns,
      bucketSpec = None,
      options = options)
    val result = dataSource.write(mode, df)
    sqlContext.sessionState.catalog.createTempTable(
      tableIdent.table,
      Dataset.ofRows(sqlContext, LogicalRelation(result)).logicalPlan,
      overrideIfExists = true)

    Seq.empty[Row]
  }
}

case class RefreshTable(tableIdent: TableIdentifier)
  extends RunnableCommand {

  override def run(sqlContext: SQLContext): Seq[Row] = {
    // Refresh the given table's metadata first.
    sqlContext.sessionState.catalog.refreshTable(tableIdent)

    // If this table is cached as a InMemoryColumnarRelation, drop the original
    // cached version and make the new version cached lazily.
    val logicalPlan = sqlContext.sessionState.catalog.lookupRelation(tableIdent)
    // Use lookupCachedData directly since RefreshTable also takes databaseName.
    val isCached = sqlContext.cacheManager.lookupCachedData(logicalPlan).nonEmpty
    if (isCached) {
      // Create a data frame to represent the table.
      // TODO: Use uncacheTable once it supports database name.
      val df = Dataset.ofRows(sqlContext, logicalPlan)
      // Uncache the logicalPlan.
      sqlContext.cacheManager.tryUncacheQuery(df, blocking = true)
      // Cache it again.
      sqlContext.cacheManager.cacheQuery(df, Some(tableIdent.table))
    }

    Seq.empty[Row]
  }
}

/**
 * Builds a map in which keys are case insensitive
 */
class CaseInsensitiveMap(map: Map[String, String]) extends Map[String, String]
  with Serializable {

  val baseMap = map.map(kv => kv.copy(_1 = kv._1.toLowerCase))

  override def get(k: String): Option[String] = baseMap.get(k.toLowerCase)

  override def + [B1 >: String](kv: (String, B1)): Map[String, B1] =
    baseMap + kv.copy(_1 = kv._1.toLowerCase)

  override def iterator: Iterator[(String, String)] = baseMap.iterator

  override def -(key: String): Map[String, String] = baseMap - key.toLowerCase
}
