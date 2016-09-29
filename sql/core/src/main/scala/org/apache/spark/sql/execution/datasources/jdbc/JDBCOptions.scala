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

import java.sql.{Connection, DriverManager}
import java.util.Properties

/**
 * Options for the JDBC data source.
 */
class JDBCOptions(
    @transient private val parameters: Map[String, String])
  extends Serializable {

  import JDBCOptions._

  def this(url: String, table: String, parameters: Map[String, String]) = {
    this(parameters ++ Map(
      JDBCOptions.JDBC_URL -> url,
      JDBCOptions.JDBC_TABLE_NAME -> table))
  }

  val asConnectionProperties: Properties = {
    val properties = new Properties()
    // We should avoid to pass the options into properties. See SPARK-17776.
    parameters.filterKeys(!jdbcOptionNames.contains(_))
      .foreach { case (k, v) => properties.setProperty(k, v) }
    properties
  }

  // ------------------------------------------------------------
  // Required parameters
  // ------------------------------------------------------------
  require(parameters.isDefinedAt(JDBC_URL), s"Option '$JDBC_URL' is required.")
  require(parameters.isDefinedAt(JDBC_TABLE_NAME), s"Option '$JDBC_TABLE_NAME' is required.")
  // a JDBC URL
  val url = parameters(JDBC_URL)
  // name of table
  val table = parameters(JDBC_TABLE_NAME)

  // ------------------------------------------------------------
  // Optional parameters
  // ------------------------------------------------------------
  val driverClass = {
    val userSpecifiedDriverClass = parameters.get(JDBC_DRIVER_CLASS)
    userSpecifiedDriverClass.foreach(DriverRegistry.register)

    // Performing this part of the logic on the driver guards against the corner-case where the
    // driver returned for a URL is different on the driver and executors due to classpath
    // differences.
    userSpecifiedDriverClass.getOrElse {
      DriverManager.getDriver(url).getClass.getCanonicalName
    }
  }

  // ------------------------------------------------------------
  // Optional parameters only for reading
  // ------------------------------------------------------------
  // the column used to partition
  val partitionColumn = parameters.getOrElse(JDBC_PARTITION_COLUMN, null)
  // the lower bound of partition column
  val lowerBound = parameters.getOrElse(JDBC_LOWER_BOUND, null)
  // the upper bound of the partition column
  val upperBound = parameters.getOrElse(JDBC_UPPER_BOUND, null)
  // the number of partitions
  val numPartitions = parameters.getOrElse(JDBC_NUM_PARTITIONS, null)
  require(partitionColumn == null ||
    (lowerBound != null && upperBound != null && numPartitions != null),
    s"If '$JDBC_PARTITION_COLUMN' is specified then '$JDBC_LOWER_BOUND', '$JDBC_UPPER_BOUND'," +
      s" and '$JDBC_NUM_PARTITIONS' are required.")
  val fetchSize = {
    val size = parameters.getOrElse(JDBC_BATCH_FETCH_SIZE, "0").toInt
    require(size >= 0,
      s"Invalid value `${size.toString}` for parameter " +
        s"`$JDBC_BATCH_FETCH_SIZE`. The minimum value is 0. When the value is 0, " +
        "the JDBC driver ignores the value and does the estimates.")
    size
  }

  // ------------------------------------------------------------
  // Optional parameters only for writing
  // ------------------------------------------------------------
  // if to truncate the table from the JDBC database
  val isTruncate = parameters.getOrElse(JDBC_TRUNCATE, "false").toBoolean
  // the create table option , which can be table_options or partition_options.
  // E.g., "CREATE TABLE t (name string) ENGINE=InnoDB DEFAULT CHARSET=utf8"
  // TODO: to reuse the existing partition parameters for those partition specific options
  val createTableOptions = parameters.getOrElse(JDBC_CREATE_TABLE_OPTIONS, "")
  val batchSize = {
    val size = parameters.getOrElse(JDBC_BATCH_INSERT_SIZE, "1000").toInt
    require(size >= 1,
      s"Invalid value `${size.toString}` for parameter " +
        s"`$JDBC_BATCH_INSERT_SIZE`. The minimum value is 1.")
    size
  }
  val isolationLevel =
    parameters.getOrElse(JDBC_TXN_ISOLATION_LEVEL, "READ_UNCOMMITTED") match {
      case "NONE" => Connection.TRANSACTION_NONE
      case "READ_UNCOMMITTED" => Connection.TRANSACTION_READ_UNCOMMITTED
      case "READ_COMMITTED" => Connection.TRANSACTION_READ_COMMITTED
      case "REPEATABLE_READ" => Connection.TRANSACTION_REPEATABLE_READ
      case "SERIALIZABLE" => Connection.TRANSACTION_SERIALIZABLE
    }
}

object JDBCOptions {
  val JDBC_URL = "url"
  val JDBC_TABLE_NAME = "dbtable"
  val JDBC_DRIVER_CLASS = "driver"
  val JDBC_PARTITION_COLUMN = "partitionColumn"
  val JDBC_LOWER_BOUND = "lowerBound"
  val JDBC_UPPER_BOUND = "upperBound"
  val JDBC_NUM_PARTITIONS = "numPartitions"
  val JDBC_BATCH_FETCH_SIZE = "fetchsize"
  val JDBC_TRUNCATE = "truncate"
  val JDBC_CREATE_TABLE_OPTIONS = "createTableOptions"
  val JDBC_BATCH_INSERT_SIZE = "batchsize"
  val JDBC_TXN_ISOLATION_LEVEL = "isolationLevel"

  private val jdbcOptionNames = Seq(
    JDBC_URL,
    JDBC_TABLE_NAME,
    JDBC_DRIVER_CLASS,
    JDBC_PARTITION_COLUMN,
    JDBC_LOWER_BOUND,
    JDBC_UPPER_BOUND,
    JDBC_NUM_PARTITIONS,
    JDBC_BATCH_FETCH_SIZE,
    JDBC_TRUNCATE,
    JDBC_CREATE_TABLE_OPTIONS,
    JDBC_BATCH_INSERT_SIZE,
    JDBC_TXN_ISOLATION_LEVEL)
}
