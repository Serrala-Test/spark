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

import java.sql.{DriverManager, Statement}

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.util.Try

import org.apache.hadoop.hive.conf.HiveConf.ConfVars
import org.apache.hive.service.cli.thrift.ThriftCLIService

import org.apache.spark.sql.test.SharedSparkSession

trait SharedThriftServer extends SharedSparkSession {

  private var hiveServer2: HiveThriftServer2 = _
  private var serverPort: Int = 0

  override def beforeAll(): Unit = {
    super.beforeAll()
    // Retries up to 3 times with different port numbers if the server fails to start
    (1 to 3).foldLeft(Try(startThriftServer(0))) { case (started, attempt) =>
      started.orElse(Try(startThriftServer(attempt)))
    }.recover {
      case cause: Throwable =>
        throw cause
    }.get
    logInfo("HiveThriftServer2 started successfully")
  }

  override def afterAll(): Unit = {
    try {
      hiveServer2.stop()
    } finally {
      super.afterAll()
    }
  }

  protected def withJdbcStatement(fs: (Statement => Unit)*): Unit = {
    val user = System.getProperty("user.name")
    require(serverPort != 0, "Failed to bind an actual port for HiveThriftServer2")
    val connections =
      fs.map { _ => DriverManager.getConnection(s"jdbc:hive2://localhost:$serverPort", user, "") }
    val statements = connections.map(_.createStatement())

    try {
      statements.zip(fs).foreach { case (s, f) => f(s) }
    } finally {
      statements.foreach(_.close())
      connections.foreach(_.close())
    }
  }

  private def startThriftServer(attempt: Int): Unit = {
    logInfo(s"Trying to start HiveThriftServer2:, attempt=$attempt")
    val sqlContext = spark.newSession().sqlContext
    sqlContext.setConf(ConfVars.HIVE_SERVER2_THRIFT_PORT.varname, 0.toString)
    hiveServer2 = HiveThriftServer2.startWithContext(sqlContext)
    hiveServer2.getServices.asScala.foreach {
      case t: ThriftCLIService if t.getPortNumber != 0 =>
        serverPort = t.getPortNumber
        logInfo(s"Started HiveThriftServer2: port=$serverPort, attempt=$attempt")
      case _ =>
    }

    // Wait for thrift server to be ready to serve the query, via executing simple query
    // till the query succeeds. See SPARK-30345 for more details.
    eventually(timeout(30.seconds), interval(1.seconds)) {
      withJdbcStatement { _.execute("SELECT 1") }
    }
  }
}
