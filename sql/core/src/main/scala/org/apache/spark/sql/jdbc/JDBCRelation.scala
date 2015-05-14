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

package org.apache.spark.sql.jdbc

import java.sql.DatabaseMetaData
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.Properties

import scala.collection.mutable.ArrayBuffer

import org.apache.spark.Partition
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.jdbc
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.catalyst.expressions.Row
import org.apache.spark.sql.SaveMode
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types.StructType
import org.apache.spark.util.Utils

/**
 * Data corresponding to one partition of a JDBCRDD.
 */
private[sql] case class JDBCPartition(whereClause: String, idx: Int) extends Partition {
  override def index: Int = idx
}

/**
 * Instructions on how to partition the table among workers.
 */
private[sql] case class JDBCPartitioningInfo(
    column: String,
    lowerBound: Long,
    upperBound: Long,
    numPartitions: Int)

private[sql] object JDBCRelation {
  /**
   * Given a partitioning schematic (a column of integral type, a number of
   * partitions, and upper and lower bounds on the column's value), generate
   * WHERE clauses for each partition so that each row in the table appears
   * exactly once.  The parameters minValue and maxValue are advisory in that
   * incorrect values may cause the partitioning to be poor, but no data
   * will fail to be represented.
   */
  def columnPartition(partitioning: JDBCPartitioningInfo): Array[Partition] = {
    if (partitioning == null) return Array[Partition](JDBCPartition(null, 0))

    val numPartitions = partitioning.numPartitions
    val column = partitioning.column
    if (numPartitions == 1) return Array[Partition](JDBCPartition(null, 0))
    // Overflow and silliness can happen if you subtract then divide.
    // Here we get a little roundoff, but that's (hopefully) OK.
    val stride: Long = (partitioning.upperBound / numPartitions 
                      - partitioning.lowerBound / numPartitions)
    var i: Int = 0
    var currentValue: Long = partitioning.lowerBound
    var ans = new ArrayBuffer[Partition]()
    while (i < numPartitions) {
      val lowerBound = if (i != 0) s"$column >= $currentValue" else null
      currentValue += stride
      val upperBound = if (i != numPartitions - 1) s"$column < $currentValue" else null
      val whereClause =
        if (upperBound == null) {
          lowerBound
        } else if (lowerBound == null) {
          upperBound
        } else {
          s"$lowerBound AND $upperBound"
        }
      ans += JDBCPartition(whereClause, i)
      i = i + 1
    }
    ans.toArray
  }
}

private[sql] class DefaultSource
  extends RelationProvider
  with SchemaRelationProvider
  with CreatableRelationProvider {
  /** Returns a new base relation with the given parameters. */
  override def createRelation(
      sqlContext: SQLContext,
      parameters: Map[String, String]): BaseRelation = {
    createRelation(sqlContext, parameters, null)
  }
  
  /** Returns a new base relation with the given parameters and schema. */
  override def createRelation(
      sqlContext: SQLContext,
      parameters: Map[String, String],
      userDefinedSchema: StructType): BaseRelation = {
    val url = parameters.getOrElse("url", sys.error("Option 'url' not specified"))
    val driver = parameters.getOrElse("driver", null)
    val table = parameters.getOrElse("dbtable", sys.error("Option 'dbtable' not specified"))
    val partitionColumn = parameters.getOrElse("partitionColumn", null)
    val lowerBound = parameters.getOrElse("lowerBound", null)
    val upperBound = parameters.getOrElse("upperBound", null)
    val numPartitions = parameters.getOrElse("numPartitions", null)

    if (driver != null) DriverRegistry.register(driver)

    if (partitionColumn != null
        && (lowerBound == null || upperBound == null || numPartitions == null)) {
      sys.error("Partitioning incompletely specified")
    }

    val partitionInfo = if (partitionColumn == null) {
      null
    } else {
      JDBCPartitioningInfo(
        partitionColumn,
        lowerBound.toLong,
        upperBound.toLong,
        numPartitions.toInt)
    }
    val parts = JDBCRelation.columnPartition(partitionInfo)
    val properties = new Properties() // Additional properties that we will pass to getConnection
    parameters.foreach(kv => properties.setProperty(kv._1, kv._2))
    val userDefinedSchemaOpt = if(userDefinedSchema == null) None else Some(userDefinedSchema)
    JDBCRelation(url, table, parts, properties, userDefinedSchemaOpt)(sqlContext)
  }
  
  /**
    * Creates a relation with the given parameters based on the contents of the given
    * DataFrame. The mode specifies the expected behavior of createRelation when
    * data already exists.
    */
  override def createRelation(
      sqlContext: SQLContext,
      mode: SaveMode,
      parameters: Map[String, String],
      data: DataFrame): BaseRelation = {
    val url = parameters.getOrElse("url", sys.error("Option 'url' not specified"))
    val driver = parameters.getOrElse("driver", null)
    val table = parameters.getOrElse("dbtable", sys.error("Option 'dbtable' not specified"))
    
    if (driver != null) DriverRegistry.register(driver)
    
    val properties = new Properties() // Additional properties that we will pass to getConnection
    parameters.foreach(kv => properties.setProperty(kv._1, kv._2))
    
    val conn = DriverManager.getConnection(url, properties)
    var doInsertion = false
    try {
      val dbm: DatabaseMetaData = conn.getMetaData();
      // check if table already exists
      val tables: ResultSet = dbm.getTables(null, null, table, null)
      var tableExists = tables.next()
      
      doInsertion = (mode, tableExists) match {
        case (SaveMode.ErrorIfExists, true) =>
          sys.error(s"Table $table already exists.")
          false
        case (SaveMode.Overwrite, true) => {
          val sql = s"DROP TABLE $table"
          conn.prepareStatement(sql).executeUpdate()
          tableExists = false
          true } 
        case (SaveMode.Append, _) | (SaveMode.Overwrite, _) | (SaveMode.ErrorIfExists, false) =>
          true
        case (SaveMode.Ignore, exists) =>
          !exists
      }      
      if(!tableExists) {
        val schema = JDBCWriteDetails.schemaString(data, url)
        val createSql = s"CREATE TABLE $table ($schema)"
        conn.prepareStatement(createSql).executeUpdate()
      }
    } finally {
      conn.close()
    }
   
    val relation = createRelation(sqlContext, parameters)
    if(doInsertion) relation match {
      case ir: InsertableRelation => {
        ir.insert(data, false)
      }
      case _ => sys.error(s"JDBC datasource does not support insert")
    }
    relation
  }
}

private[sql] case class JDBCRelation(
    url: String,
    table: String,
    parts: Array[Partition],
    properties: Properties = new Properties(),
    userDefinedSchema: Option[StructType])(@transient val sqlContext: SQLContext)
  extends BaseRelation
  with PrunedFilteredScan
  with InsertableRelation {

  override val needConversion: Boolean = false

  override val schema: StructType = 
    userDefinedSchema.getOrElse { JDBCRDD.resolveTable(url, table, properties) }

  override def buildScan(requiredColumns: Array[String], filters: Array[Filter]): RDD[Row] = {
    val driver: String = DriverRegistry.getDriverClassName(url)
    JDBCRDD.scanTable(
      sqlContext.sparkContext,
      schema,
      driver,
      url,
      properties,
      table,
      requiredColumns,
      filters,
      parts)
  }
  
  override def insert(data: DataFrame, overwrite: Boolean): Unit = {
    data.insertIntoJDBC(url, table, overwrite, properties)
  }  
  
  override def hashCode(): Int = 
    37 * (37 * (37 + url.hashCode) + table.hashCode) + schema.hashCode

  override def equals(other: Any): Boolean = other match {
    case that: JDBCRelation =>
      (this.url == that.url) && (this.table == that.table) && this.schema.sameType(that.schema)
    case _ => false
  }
}
