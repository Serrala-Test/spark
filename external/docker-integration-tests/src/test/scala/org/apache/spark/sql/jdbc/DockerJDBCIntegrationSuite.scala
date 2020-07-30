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

import java.net.ServerSocket
import java.sql.{Connection, DriverManager}
import java.util.Properties

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import com.spotify.docker.client._
import com.spotify.docker.client.exceptions.ImageNotFoundException
import com.spotify.docker.client.messages.{ContainerConfig, HostConfig, PortBinding}
import org.scalatest.concurrent.Eventually
import org.scalatest.time.SpanSugar._

import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.util.DockerUtils

abstract class DatabaseOnDocker {
  /**
   * The docker image to be pulled.
   */
  val imageName: String

  /**
   * Environment variables to set inside of the Docker container while launching it.
   */
  val env: Map[String, String]

  /**
   * Wheather or not to use ipc mode for shared memory when starting docker image
   */
  val usesIpc: Boolean

  /**
   * The container-internal JDBC port that the database listens on.
   */
  val jdbcPort: Int

  /**
   * Parameter whether the container should run privileged.
   */
  val privileged: Boolean = false

  /**
   * Return a JDBC URL that connects to the database running at the given IP address and port.
   */
  def getJdbcUrl(ip: String, port: Int): String

  /**
   * Return the JDBC properties needed for the connection.
   */
  def getJdbcProperties(): Properties = new Properties()

  /**
   * Optional entry point when container starts
   *
   * Startup process is a parameter of entry point. This may or may not be considered during
   * startup. Prefer entry point to startup process when you need a command always to be executed or
   * you want to change the initialization order.
   */
  def getEntryPoint: Option[String] = None

  /**
   * Optional process to run when container starts
   */
  def getStartupProcessName: Option[String] = None

  /**
   * Optional step before container starts
   */
  def beforeContainerStart(
      hostConfigBuilder: HostConfig.Builder,
      containerConfigBuilder: ContainerConfig.Builder): Unit = {}
}

abstract class DockerJDBCIntegrationSuite extends SharedSparkSession with Eventually {

  protected val dockerIp = DockerUtils.getDockerIp()
  val db: DatabaseOnDocker
  val connectionTimeout = timeout(2.minutes)

  private var docker: DockerClient = _
  protected var externalPort: Int = _
  private var containerId: String = _
  protected var jdbcUrl: String = _

  override def beforeAll() {
    super.beforeAll()
    try {
      docker = DefaultDockerClient.fromEnv.build()
      // Check that Docker is actually up
      try {
        docker.ping()
      } catch {
        case NonFatal(e) =>
          log.error("Exception while connecting to Docker. Check whether Docker is running.")
          throw e
      }
      // Ensure that the Docker image is installed:
      try {
        docker.inspectImage(db.imageName)
      } catch {
        case e: ImageNotFoundException =>
          log.warn(s"Docker image ${db.imageName} not found; pulling image from registry")
          docker.pull(db.imageName)
      }
      // Configure networking (necessary for boot2docker / Docker Machine)
      externalPort = {
        val sock = new ServerSocket(0)
        val port = sock.getLocalPort
        sock.close()
        port
      }
      val hostConfigBuilder = HostConfig.builder()
        .privileged(db.privileged)
        .networkMode("bridge")
        .ipcMode(if (db.usesIpc) "host" else "")
        .portBindings(
          Map(s"${db.jdbcPort}/tcp" -> List(PortBinding.of(dockerIp, externalPort)).asJava).asJava)
      // Create the database container:
      val containerConfigBuilder = ContainerConfig.builder()
        .image(db.imageName)
        .networkDisabled(false)
        .env(db.env.map { case (k, v) => s"$k=$v" }.toSeq.asJava)
        .exposedPorts(s"${db.jdbcPort}/tcp")
      if (db.getEntryPoint.isDefined) {
        containerConfigBuilder.entrypoint(db.getEntryPoint.get)
      }
      if (db.getStartupProcessName.isDefined) {
        containerConfigBuilder.cmd(db.getStartupProcessName.get)
      }
      db.beforeContainerStart(hostConfigBuilder, containerConfigBuilder)
      containerConfigBuilder.hostConfig(hostConfigBuilder.build())
      val config = containerConfigBuilder.build()
      // Create the database container:
      containerId = docker.createContainer(config).id
      // Start the container and wait until the database can accept JDBC connections:
      docker.startContainer(containerId)
      jdbcUrl = db.getJdbcUrl(dockerIp, externalPort)
      var conn: Connection = null
      eventually(connectionTimeout, interval(1.second)) {
        conn = getConnection()
      }
      // Run any setup queries:
      try {
        dataPreparation(conn)
      } finally {
        conn.close()
      }
    } catch {
      case NonFatal(e) =>
        try {
          afterAll()
        } finally {
          throw e
        }
    }
  }

  override def afterAll() {
    try {
      if (docker != null) {
        try {
          if (containerId != null) {
            docker.killContainer(containerId)
            docker.removeContainer(containerId)
          }
        } catch {
          case NonFatal(e) =>
            logWarning(s"Could not stop container $containerId", e)
        } finally {
          docker.close()
        }
      }
    } finally {
      super.afterAll()
    }
  }

  /**
   * Return the JDBC connection.
   */
  def getConnection(): Connection = {
    DriverManager.getConnection(jdbcUrl, db.getJdbcProperties())
  }

  /**
   * Prepare databases and tables for testing.
   */
  def dataPreparation(connection: Connection): Unit
}
