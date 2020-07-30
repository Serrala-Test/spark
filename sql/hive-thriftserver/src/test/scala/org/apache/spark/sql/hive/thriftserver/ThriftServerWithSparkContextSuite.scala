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

package org.apache.spark.sql.hive.thriftserver

import java.sql.SQLException

import scala.collection.JavaConverters._

import org.apache.hive.service.cli.HiveSQLException

import org.apache.spark.sql.hive.HiveUtils
import org.apache.spark.sql.types.{ByteType, DecimalType, DoubleType, LongType, ShortType}

trait ThriftServerWithSparkContextSuite extends SharedThriftServer {

  test("the scratch dir will be deleted during server start but recreated with new operation") {
    assert(tempScratchDir.exists())
  }

  test("SPARK-29911: Uncache cached tables when session closed") {
    val cacheManager = spark.sharedState.cacheManager
    val globalTempDB = spark.sharedState.globalTempViewManager.database
    withJdbcStatement { statement =>
      statement.execute("CACHE TABLE tempTbl AS SELECT 1")
    }
    // the cached data of local temporary view should be uncached
    assert(cacheManager.isEmpty)
    try {
      withJdbcStatement { statement =>
        statement.execute("CREATE GLOBAL TEMP VIEW globalTempTbl AS SELECT 1, 2")
        statement.execute(s"CACHE TABLE $globalTempDB.globalTempTbl")
      }
      // the cached data of global temporary view shouldn't be uncached
      assert(!cacheManager.isEmpty)
    } finally {
      withJdbcStatement { statement =>
        statement.execute(s"UNCACHE TABLE IF EXISTS $globalTempDB.globalTempTbl")
      }
      assert(cacheManager.isEmpty)
    }
  }

  test("Full stack traces as error message for jdbc or thrift client") {
    val sql = "select date_sub(date'2011-11-11', '1.2')"
    val confOverlay = new java.util.HashMap[java.lang.String, java.lang.String]

    withSQLConf((HiveUtils.HIVE_THRIFT_SERVER_ASYNC.key, "false")) {
      withCLIServiceClient { client =>
        val sessionHandle = client.openSession(user, "")
        val e = intercept[HiveSQLException] {
          client.executeStatement(sessionHandle, sql, confOverlay)
        }
        assert(e.getMessage
          .contains("The second argument of 'date_sub' function needs to be an integer."))
        assert(!e.getMessage
          .contains("java.lang.NumberFormatException: invalid input syntax for type numeric: 1.2"))
      }
    }

    withSQLConf((HiveUtils.HIVE_THRIFT_SERVER_ASYNC.key, "true")) {
      withCLIServiceClient { client =>
        val sessionHandle = client.openSession(user, "")
        val opHandle = client.executeStatementAsync(sessionHandle, sql, confOverlay)
        var status = client.getOperationStatus(opHandle)
        while (!status.getState.isTerminal) {
          Thread.sleep(10)
          status = client.getOperationStatus(opHandle)
        }
        val e = status.getOperationException

        assert(e.getMessage
          .contains("The second argument of 'date_sub' function needs to be an integer."))
        assert(e.getMessage
          .contains("java.lang.NumberFormatException: invalid input syntax for type numeric: 1.2"))
      }
    }

    Seq("true", "false").foreach { value =>
      withSQLConf((HiveUtils.HIVE_THRIFT_SERVER_ASYNC.key, value)) {
        withJdbcStatement { statement =>
          val e = intercept[SQLException] {
            statement.executeQuery(sql)
          }
          assert(e.getMessage.contains(
            "The second argument of 'date_sub' function needs to be an integer."))
          assert(e.getMessage.contains(
            "java.lang.NumberFormatException: invalid input syntax for type numeric: 1.2"))
        }
      }
    }
  }

  test("SparkGetColumnsOperation") {
    val schemaName = "default"
    val tableName = "spark_get_col_operation"
    val decimalType = DecimalType(10, 2)
    val ddl =
      s"""
         |CREATE TABLE $schemaName.$tableName
         |  (
         |    a boolean comment '0',
         |    b int comment '1',
         |    c float comment '2',
         |    d ${decimalType.sql} comment '3',
         |    e array<long> comment '4',
         |    f array<string> comment '5',
         |    g map<smallint, tinyint> comment '6',
         |    h date comment '7',
         |    i timestamp comment '8',
         |    j struct<X: bigint,Y: double> comment '9'
         |  ) using parquet""".stripMargin

    withCLIServiceClient { client =>
      val sessionHandle = client.openSession(user, "")
      val confOverlay = new java.util.HashMap[java.lang.String, java.lang.String]
      val opHandle = client.executeStatement(sessionHandle, ddl, confOverlay)
      var status = client.getOperationStatus(opHandle)
      while (!status.getState.isTerminal) {
        Thread.sleep(10)
        status = client.getOperationStatus(opHandle)
      }
      val getCol = client.getColumns(sessionHandle, "", schemaName, tableName, null)
      val rowSet = client.fetchResults(getCol)
      val columns = rowSet.toTRowSet.getColumns
      assert(columns.get(0).getStringVal.getValues.asScala.forall(_.isEmpty),
        "catalog name mismatches")

      assert(columns.get(1).getStringVal.getValues.asScala.forall(_ == schemaName),
        "schema name mismatches")

      assert(columns.get(2).getStringVal.getValues.asScala.forall(_ == tableName),
        "table name mismatches")

      // column name
      columns.get(3).getStringVal.getValues.asScala.zipWithIndex.foreach {
        case (v, i) => assert(v === ('a' + i).toChar.toString, "column name mismatches")
      }

      val javaTypes = columns.get(4).getI32Val.getValues
      assert(javaTypes.get(3).intValue() === java.sql.Types.DECIMAL)
      assert(javaTypes.get(6).intValue() === java.sql.Types.JAVA_OBJECT)

      val typeNames = columns.get(5).getStringVal.getValues
      assert(typeNames.get(3) === decimalType.sql)

      val colSize = columns.get(6).getI32Val.getValues
      assert(colSize.get(3).intValue() === decimalType.defaultSize)
      assert(colSize.get(6).intValue() === ByteType.defaultSize + ShortType.defaultSize,
        "map column size mismatches")
      assert(colSize.get(9).intValue() === LongType.defaultSize + DoubleType.defaultSize,
        "struct column size mismatches")

      val decimalDigits = columns.get(8).getI32Val.getValues
      assert(decimalDigits.get(1).intValue() === 0)
      assert(decimalDigits.get(2).intValue() === 7)
      assert(decimalDigits.get(3).intValue() === decimalType.scale)
      assert(decimalDigits.get(8).intValue() === 9,
        "timestamp support 9 digits for second fraction")

      val positions = columns.get(16).getI32Val.getValues
      positions.asScala.zipWithIndex.foreach { case (pos, idx) =>
        assert(pos === idx, "the client columns disorder")
      }
    }

  }
}


class ThriftServerWithSparkContextInBinarySuite extends ThriftServerWithSparkContextSuite {
  override def mode: ServerMode.Value = ServerMode.binary
}

class ThriftServerWithSparkContextInHttpSuite extends ThriftServerWithSparkContextSuite {
  override def mode: ServerMode.Value = ServerMode.http
}
