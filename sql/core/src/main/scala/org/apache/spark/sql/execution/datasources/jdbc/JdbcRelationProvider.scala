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

package org.apache.spark.sql.execution.datasources.jdbc

import java.util.Properties

import scala.collection.JavaConverters._

import org.apache.spark.sql.{DataFrame, SaveMode, SQLContext}
import org.apache.spark.sql.execution.datasources.jdbc.JdbcUtils._
import org.apache.spark.sql.sources.{BaseRelation, CreatableRelationProvider, DataSourceRegister, RelationProvider}

class JdbcRelationProvider extends CreatableRelationProvider
  with RelationProvider with DataSourceRegister {

  override def shortName(): String = "jdbc"

  override def createRelation(
      sqlContext: SQLContext,
      parameters: Map[String, String]): BaseRelation = {
    val jdbcOptions = new JDBCOptions(parameters)
    val partitionColumn = jdbcOptions.partitionColumn
    val lowerBound = jdbcOptions.lowerBound
    val upperBound = jdbcOptions.upperBound
    val numPartitions = jdbcOptions.numPartitions

    val partitionInfo = if (partitionColumn == null) {
      null
    } else {
      JDBCPartitioningInfo(
        partitionColumn, lowerBound.toLong, upperBound.toLong, numPartitions.toInt)
    }
    val parts = JDBCRelation.columnPartition(partitionInfo)
    val properties = new Properties() // Additional properties that we will pass to getConnection
    parameters.foreach(kv => properties.setProperty(kv._1, kv._2))
    JDBCRelation(jdbcOptions.url, jdbcOptions.table, parts, properties)(sqlContext.sparkSession)
  }

  /**
   * Saves the content of the [[DataFrame]] to an external database table.
   */
  override def createRelation(
      sqlContext: SQLContext,
      mode: SaveMode,
      parameters: Map[String, String],
      df: DataFrame): BaseRelation = {
    val options = new JDBCOptions(parameters)
    val url = options.url
    val table = options.table
    val createTableOptions = options.createTableOptions
    val isTruncate = options.isTruncate
    val props = new Properties()
    props.putAll(parameters.asJava)

    val conn = JdbcUtils.createConnectionFactory(url, props)()
    try {
      val tableExists = JdbcUtils.tableExists(conn, url, table)
      if (tableExists) {
        mode match {
          case SaveMode.Overwrite =>
            if (isTruncate && isCascadingTruncateTable(url).contains(false)) {
              // In this case, we should truncate table and then load.
              truncateTable(conn, table)
              saveTable(df, url, table, props)
            } else {
              // Otherwise, do not truncate but just drop.
              dropTable(conn, table)
              createTable(df, url, table, createTableOptions, conn)
              saveTable(df, url, table, props)
            }
          case SaveMode.Append =>
            saveTable(df, url, table, props)

          case SaveMode.ErrorIfExists =>
            sys.error(s"Table $table already exists.")

          case SaveMode.Ignore => // Just ignore this case.
        }
      } else {
        mode match {
          case SaveMode.Overwrite | SaveMode.Append | SaveMode.ErrorIfExists =>
            createTable(df, url, table, createTableOptions, conn)
            saveTable(df, url, table, props)

          case SaveMode.Ignore => // Just ignore this case.
        }
      }
    } finally {
      conn.close()
    }

    createRelation(sqlContext, parameters)
  }
}
