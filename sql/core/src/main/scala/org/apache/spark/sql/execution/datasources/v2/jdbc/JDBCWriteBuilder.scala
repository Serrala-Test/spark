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

package org.apache.spark.sql.execution.datasources.v2.jdbc

import java.sql.{Connection, DriverManager}

import org.apache.spark.internal.Logging
import org.apache.spark.sql.SaveMode
import org.apache.spark.sql.execution.datasources.jdbc.{JdbcOptionsInWrite, JdbcUtils}
import org.apache.spark.sql.sources.{AlwaysTrue$, Filter}
import org.apache.spark.sql.sources.v2.writer.{BatchWrite, SupportsOverwrite, WriteBuilder}
import org.apache.spark.sql.types.StructType

class JDBCWriteBuilder(options: JdbcOptionsInWrite,
                       userSchema: Option[StructType])
  extends SupportsOverwrite with Logging{
  // TODO : Check, The default mode is assumed as Append. Refer physical plans to
  // overwrite and append data i.e. OverwriteByExpressionExec and AppendDataExec
  // respectively(Truncate and overwrite are called explicitly)
  private var writeMode : SaveMode = SaveMode.Append
  private var isTruncate : Boolean = false
  private var fwPassedSchema : StructType = _
  private val conn : Connection = JdbcUtils.createConnectionFactory(options)()

  override def withQueryId(queryId: String): WriteBuilder = {
    logInfo("***dsv2-flows*** withQueryId called with queryId" + queryId)
    // TODO : Check, Possible for his object to handles multiple queries on same table.
    this
  }

  override def withInputDataSchema(schema: StructType): WriteBuilder = {
    logInfo("***dsv2-flows*** withInputDataSchema called with schema")
    logInfo("***dsv2-flows*** schema is " + schema.printTreeString())
    fwPassedSchema = schema
    this
  }

  override def buildForBatch : BatchWrite = {
    logInfo("***dsv2-flows*** buildForBatch called")
    writeMode match {
      case SaveMode.Overwrite =>
        processOverwrite()
      case SaveMode.Append =>
        processAppend()
    }
    new JDBCBatchWrite(options, fwPassedSchema)
  }

  override def overwrite(filters: Array[Filter]): WriteBuilder = {
    logInfo("***dsv2-flows*** overwrite called ")
    writeMode = SaveMode.Overwrite
    for(filter <- filters) logInfo(s"***dsv2-flows*** overwrite filter is $filter")
    this
  }

  override def truncate(): WriteBuilder = {
    logInfo("***dsv2-flows*** truncate called ")
    writeMode = SaveMode.Overwrite
    isTruncate = true
    this
  }

  def processOverwrite() : Unit = {
    logInfo("***dsv2-flows*** processOverwrite called")
    isTruncate match {
      case true =>
        logInfo("***dsv2-flows*** truncating Table")
        JdbcUtils.truncateTable(conn, options)

      case false =>
        logInfo("***dsv2-flows*** Dropping Table")
        // JdbcUtils.dropTable(conn, options.table, options)
        logInfo("***dsv2-flows*** Recreating table with passed schema")
        Utils.createTable(conn, fwPassedSchema)
    }
  }

  def processAppend() : Unit = {
    /* Append table logic : If we have reached this far, table exist and schema check is done.
     * So processappend does nothing here. just sends request to executors for data insert
     */
    logInfo("***dsv2-flows*** Append to table")
    // log schemas received.
    Utils.logSchema("userSchema", userSchema)
    Utils.logSchema("fwPassedSchema", Option(fwPassedSchema))
  }
}
