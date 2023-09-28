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

import java.io.File
import java.sql.{DriverManager, ResultSet, Statement}
import java.util

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Try

import org.apache.hadoop.hive.conf.HiveConf.ConfVars
import org.apache.hadoop.hive.ql.metadata.Hive
import org.apache.hadoop.hive.ql.session.SessionState
import org.apache.hive.jdbc.HttpBasicAuthInterceptor
import org.apache.hive.service.auth.PlainSaslHelper
import org.apache.hive.service.cli.thrift.{ThriftCLIService, ThriftCLIServiceClient}
import org.apache.hive.service.rpc.thrift.TCLIService.Client
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.thrift.protocol.TBinaryProtocol
import org.apache.thrift.transport.{THttpClient, TSocket}

import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.util.Utils

trait SharedThriftServer extends SharedSparkSession {

  private var hiveServer2: HiveThriftServer2 = _
  private var serverPort: Int = 0

  protected val tempScratchDir: File = {
    val dir = Utils.createTempDir()
    dir.setWritable(true, false)
    Utils.createTempDir(dir.getAbsolutePath)
    dir
  }

  def mode: ServerMode.Value

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
      if (hiveServer2 != null) {
        hiveServer2.stop()
      }
    } finally {
      super.afterAll()
      SessionState.detachSession()
      Hive.closeCurrent()
    }
  }

  protected def jdbcUri: String = if (mode == ServerMode.http) {
    s"jdbc:hive2://localhost:$serverPort/default;transportMode=http;httpPath=cliservice"
  } else {
    s"jdbc:hive2://localhost:$serverPort/"
  }

  protected def user: String = System.getProperty("user.name")

  protected def withJdbcStatement(
      resultSetType: Int = ResultSet.TYPE_FORWARD_ONLY)(
      fs: (Statement => Unit)*): Unit = {
    require(serverPort != 0, "Failed to bind an actual port for HiveThriftServer2")
    val connections =
      fs.map { _ => DriverManager.getConnection(jdbcUri, user, "") }
    val statements = connections.map(_.createStatement(resultSetType, ResultSet.CONCUR_READ_ONLY))

    try {
      statements.zip(fs).foreach { case (s, f) => f(s) }
    } finally {
      statements.foreach(_.close())
      connections.foreach(_.close())
    }
  }

  protected def withCLIServiceClient(username: String = user)
      (f: ThriftCLIServiceClient => Unit): Unit = {
    require(serverPort != 0, "Failed to bind an actual port for HiveThriftServer2")
    val transport = mode match {
      case ServerMode.binary =>
        val rawTransport = new TSocket("localhost", serverPort)
        PlainSaslHelper.getPlainTransport(username, "anonymous", rawTransport)
      case ServerMode.http =>
        val interceptor = new HttpBasicAuthInterceptor(
          username,
          "anonymous",
          null, null, true, new util.HashMap[String, String]())
        new THttpClient(
          s"http://localhost:$serverPort/cliservice",
          HttpClientBuilder.create.addInterceptorFirst(interceptor).build())
    }

    val protocol = new TBinaryProtocol(transport)
    val client = new ThriftCLIServiceClient(new Client(protocol))

    transport.open()
    try f(client) finally transport.close()
  }

  private def startThriftServer(attempt: Int): Unit = {
    logInfo(s"Trying to start HiveThriftServer2: mode=$mode, attempt=$attempt")
    val sqlContext = spark.newSession().sqlContext
    // Set the HIVE_SERVER2_THRIFT_PORT and HIVE_SERVER2_THRIFT_HTTP_PORT to 0, so it could
    // randomly pick any free port to use.
    // It's much more robust than set a random port generated by ourselves ahead
    sqlContext.setConf(ConfVars.HIVE_SERVER2_THRIFT_PORT.varname, "0")
    sqlContext.setConf(ConfVars.HIVE_SERVER2_THRIFT_HTTP_PORT.varname, "0")
    sqlContext.setConf(ConfVars.HIVE_SERVER2_TRANSPORT_MODE.varname, mode.toString)
    sqlContext.setConf(ConfVars.SCRATCHDIR.varname, tempScratchDir.getAbsolutePath)
    sqlContext.setConf(ConfVars.HIVE_START_CLEANUP_SCRATCHDIR.varname, "true")

    try {
      hiveServer2 = HiveThriftServer2.startWithContext(sqlContext)
      hiveServer2.getServices.asScala.foreach {
        case t: ThriftCLIService =>
          serverPort = t.getPortNumber
          logInfo(s"Started HiveThriftServer2: mode=$mode, port=$serverPort, attempt=$attempt")
        case _ =>
      }

      // the scratch dir will be recreated after the probe sql `SELECT 1` executed, so we
      // check it here first.
      assert(!tempScratchDir.exists())

      // Wait for thrift server to be ready to serve the query, via executing simple query
      // till the query succeeds. See SPARK-30345 for more details.
      eventually(timeout(30.seconds), interval(1.seconds)) {
        withJdbcStatement() { _.execute("SELECT 1") }
      }
    } catch {
      case e: Exception =>
        logError("Error start hive server with Context ", e)
        if (hiveServer2 != null) {
          hiveServer2.stop()
          hiveServer2 = null
        }
        SessionState.detachSession()
        Hive.closeCurrent()
        throw e
    }
  }
}
