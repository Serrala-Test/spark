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

package org.apache.spark.deploy.rest

import java.io.{DataOutputStream, File}
import java.net.InetSocketAddress
import javax.servlet.http.{HttpServlet, HttpServletResponse, HttpServletRequest}

import scala.io.Source

import akka.actor.ActorRef
import com.google.common.base.Charsets
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.{ServletHolder, ServletContextHandler}
import org.eclipse.jetty.util.thread.QueuedThreadPool

import org.apache.spark.{Logging, SparkConf, SPARK_VERSION => sparkVersion}
import org.apache.spark.util.{AkkaUtils, Utils}
import org.apache.spark.deploy.{Command, DeployMessages, DriverDescription}
import org.apache.spark.deploy.ClientArguments._
import org.apache.spark.deploy.master.Master

/**
 * A server that responds to requests submitted by the [[StandaloneRestClient]].
 * This is intended to be embedded in the standalone Master and used in cluster mode only.
 */
private[spark] class StandaloneRestServer(
    master: Master,
    host: String,
    requestedPort: Int)
  extends Logging {

  private var _server: Option[Server] = None

  /** Start the server and return the bound port. */
  def start(): Int = {
    val (server, boundPort) = Utils.startServiceOnPort[Server](requestedPort, doStart, master.conf)
    _server = Some(server)
    logInfo(s"Started REST server for submitting applications on port $boundPort")
    boundPort
  }

  /**
   * Set up the mapping from contexts to the appropriate servlets:
   *   (1) submit requests should be directed to /create
   *   (2) kill requests should be directed to /kill
   *   (3) status requests should be directed to /status
   * Return a 2-tuple of the started server and the bound port.
   */
  private def doStart(startPort: Int): (Server, Int) = {
    val server = new Server(new InetSocketAddress(host, requestedPort))
    val threadPool = new QueuedThreadPool
    threadPool.setDaemon(true)
    server.setThreadPool(threadPool)
    val mainHandler = new ServletContextHandler
    mainHandler.setContextPath("/submissions")
    mainHandler.addServlet(new ServletHolder(new KillRequestServlet(master)), "/kill/*")
    mainHandler.addServlet(new ServletHolder(new StatusRequestServlet(master)), "/status/*")
    mainHandler.addServlet(new ServletHolder(new SubmitRequestServlet(master)), "/create")
    server.setHandler(mainHandler)
    server.start()
    val boundPort = server.getConnectors()(0).getLocalPort
    (server, boundPort)
  }

  def stop(): Unit = {
    _server.foreach(_.stop())
  }
}

/**
 * An abstract servlet for handling requests passed to the [[StandaloneRestServer]].
 */
private[spark] abstract class StandaloneRestServlet(master: Master)
  extends HttpServlet with Logging {

  protected val conf: SparkConf = master.conf
  protected val masterActor: ActorRef = master.self
  protected val masterUrl: String = master.masterUrl
  protected val askTimeout = AkkaUtils.askTimeout(conf)

  /**
   * Serialize the given response message to JSON and send it through the response servlet.
   * This validates the response before sending it to ensure it is properly constructed.
   */
  protected def handleResponse(
      responseMessage: SubmitRestProtocolResponse,
      responseServlet: HttpServletResponse): Unit = {
    try {
      val message = validateResponse(responseMessage)
      responseServlet.setContentType("application/json")
      responseServlet.setCharacterEncoding("utf-8")
      responseServlet.setStatus(HttpServletResponse.SC_OK)
      val content = message.toJson.getBytes(Charsets.UTF_8)
      val out = new DataOutputStream(responseServlet.getOutputStream)
      out.write(content)
      out.close()
    } catch {
      case e: Exception =>
        logError("Exception encountered when handling response.", e)
    }
  }

  /** Return a human readable String representation of the exception. */
  protected def formatException(e: Exception): String = {
    val stackTraceString = e.getStackTrace.map { "\t" + _ }.mkString("\n")
    s"$e\n$stackTraceString"
  }

  /** Construct an error message to signal the fact that an exception has been thrown. */
  protected def handleError(message: String): ErrorResponse = {
    val e = new ErrorResponse
    e.serverSparkVersion = sparkVersion
    e.message = message
    e
  }

  /**
   * Validate the response message to ensure that it is correctly constructed.
   * If it is, simply return the response as is. Otherwise, return an error response
   * to propagate the exception back to the client.
   */
  private def validateResponse(response: SubmitRestProtocolResponse): SubmitRestProtocolResponse = {
    try {
      response.validate()
      response
    } catch {
      case e: Exception =>
        handleError("Internal server error: " + formatException(e))
    }
  }
}

/**
 * A servlet for handling kill requests passed to the [[StandaloneRestServer]].
 */
private[spark] class KillRequestServlet(master: Master) extends StandaloneRestServlet(master) {

  /**
   * If a submission ID is specified in the URL, have the Master kill the corresponding
   * driver and return an appropriate response to the client. Otherwise, return error.
   */
  protected override def doPost(
      request: HttpServletRequest,
      response: HttpServletResponse): Unit = {
    try {
      val submissionId = request.getPathInfo.stripPrefix("/")
      val responseMessage =
        if (submissionId.nonEmpty) {
          handleKill(submissionId)
        } else {
          handleError("Submission ID is missing in kill request")
        }
      handleResponse(responseMessage, response)
    } catch {
      case e: Exception =>
        logError("Exception encountered when handling kill request", e)
    }
  }

  private def handleKill(submissionId: String): KillSubmissionResponse = {
    val response = AkkaUtils.askWithReply[DeployMessages.KillDriverResponse](
      DeployMessages.RequestKillDriver(submissionId), masterActor, askTimeout)
    val k = new KillSubmissionResponse
    k.serverSparkVersion = sparkVersion
    k.message = response.message
    k.submissionId = submissionId
    k.success = response.success.toString
    k
  }
}

/**
 * A servlet for handling status requests passed to the [[StandaloneRestServer]].
 */
private[spark] class StatusRequestServlet(master: Master) extends StandaloneRestServlet(master) {

  /**
   * If a submission ID is specified in the URL, request the status of the corresponding
   * driver from the Master and include it in the response. Otherwise, return error.
   */
  protected override def doGet(
      request: HttpServletRequest,
      response: HttpServletResponse): Unit = {
    try {
      val submissionId = request.getPathInfo.stripPrefix("/")
      val responseMessage =
        if (submissionId.nonEmpty) {
          handleStatus(submissionId)
        } else {
          handleError("Submission ID is missing in status request")
        }
      handleResponse(responseMessage, response)
    } catch {
      case e: Exception =>
        logError("Exception encountered when handling status request", e)
    }
  }

  private def handleStatus(submissionId: String): SubmissionStatusResponse = {
    val response = AkkaUtils.askWithReply[DeployMessages.DriverStatusResponse](
      DeployMessages.RequestDriverStatus(submissionId), masterActor, askTimeout)
    val message = response.exception.map { s"Exception from the cluster:\n" + formatException(_) }
    val d = new SubmissionStatusResponse
    d.serverSparkVersion = sparkVersion
    d.submissionId = submissionId
    d.success = response.found.toString
    d.driverState = response.state.map(_.toString).orNull
    d.workerId = response.workerId.orNull
    d.workerHostPort = response.workerHostPort.orNull
    d.message = message.orNull
    d
  }
}

/**
 * A servlet for handling submit requests passed to the [[StandaloneRestServer]].
 */
private[spark] class SubmitRequestServlet(master: Master) extends StandaloneRestServlet(master) {

  /**
   * Submit an application to the Master with parameters specified in the request message.
   *
   * The request is assumed to be a [[SubmitRestProtocolRequest]] in the form of JSON.
   * If this is successful, return an appropriate response to the client indicating so.
   * Otherwise, return error instead.
   */
  protected override def doPost(
      request: HttpServletRequest,
      response: HttpServletResponse): Unit = {
    try {
      val requestMessageJson = Source.fromInputStream(request.getInputStream).mkString
      val requestMessage = SubmitRestProtocolMessage.fromJson(requestMessageJson)
        .asInstanceOf[SubmitRestProtocolRequest]
      val responseMessage = handleSubmit(requestMessage)
      response.setContentType("application/json")
      response.setCharacterEncoding("utf-8")
      response.setStatus(HttpServletResponse.SC_OK)
      val content = responseMessage.toJson.getBytes(Charsets.UTF_8)
      val out = new DataOutputStream(response.getOutputStream)
      out.write(content)
      out.close()
    } catch {
      case e: Exception => logError("Exception while handling request", e)
    }
  }

  private def handleSubmit(request: SubmitRestProtocolRequest): SubmitRestProtocolResponse = {
    // The response should have already been validated on the client.
    // In case this is not true, validate it ourselves to avoid potential NPEs.
    try {
      request.validate()
      request match {
        case submitRequest: CreateSubmissionRequest =>
          val driverDescription = buildDriverDescription(submitRequest)
          val response = AkkaUtils.askWithReply[DeployMessages.SubmitDriverResponse](
            DeployMessages.RequestSubmitDriver(driverDescription), masterActor, askTimeout)
          val submitResponse = new CreateSubmissionResponse
          submitResponse.serverSparkVersion = sparkVersion
          submitResponse.message = response.message
          submitResponse.success = response.success.toString
          submitResponse.submissionId = response.driverId.orNull
          submitResponse
        case unexpected => handleError(
          s"Received message of unexpected type ${Utils.getFormattedClassName(unexpected)}.")
      }
    } catch {
      case e: Exception => handleError(formatException(e))
    }
  }

  /**
   * Build a driver description from the fields specified in the submit request.
   *
   * This involves constructing a command that takes into account memory, java options,
   * classpath and other settings to launch the driver. This does not currently consider
   * fields used by python applications since python is not supported in standalone
   * cluster mode yet.
   */
  private def buildDriverDescription(request: CreateSubmissionRequest): DriverDescription = {
    // Required fields, including the main class because python is not yet supported
    val appName = request.appName
    val appResource = request.appResource
    val mainClass = request.mainClass
    if (mainClass == null) {
      throw new SubmitRestMissingFieldException("Main class must be set in submit request.")
    }

    // Optional fields
    val jars = Option(request.jars)
    val files = Option(request.files)
    val driverMemory = Option(request.driverMemory)
    val driverCores = Option(request.driverCores)
    val driverExtraJavaOptions = Option(request.driverExtraJavaOptions)
    val driverExtraClassPath = Option(request.driverExtraClassPath)
    val driverExtraLibraryPath = Option(request.driverExtraLibraryPath)
    val superviseDriver = Option(request.superviseDriver)
    val executorMemory = Option(request.executorMemory)
    val totalExecutorCores = Option(request.totalExecutorCores)
    val appArgs = request.appArgs
    val sparkProperties = request.sparkProperties
    val environmentVariables = request.environmentVariables

    // Translate all fields to the relevant Spark properties
    val conf = new SparkConf(false)
      .setAll(sparkProperties)
      .set("spark.master", masterUrl)
      .set("spark.app.name", appName)
    jars.foreach { j => conf.set("spark.jars", j) }
    files.foreach { f => conf.set("spark.files", f) }
    driverExtraJavaOptions.foreach { j => conf.set("spark.driver.extraJavaOptions", j) }
    driverExtraClassPath.foreach { cp => conf.set("spark.driver.extraClassPath", cp) }
    driverExtraLibraryPath.foreach { lp => conf.set("spark.driver.extraLibraryPath", lp) }
    executorMemory.foreach { m => conf.set("spark.executor.memory", m) }
    totalExecutorCores.foreach { c => conf.set("spark.cores.max", c) }

    // Construct driver description and submit it
    val extraClassPath = driverExtraClassPath.toSeq.flatMap(_.split(File.pathSeparator))
    val extraLibraryPath = driverExtraLibraryPath.toSeq.flatMap(_.split(File.pathSeparator))
    val extraJavaOpts = driverExtraJavaOptions.map(Utils.splitCommandString).getOrElse(Seq.empty)
    val sparkJavaOpts = Utils.sparkJavaOpts(conf)
    val javaOpts = sparkJavaOpts ++ extraJavaOpts
    val command = new Command(
      "org.apache.spark.deploy.worker.DriverWrapper",
      Seq("{{WORKER_URL}}", mainClass) ++ appArgs, // args to the DriverWrapper
      environmentVariables, extraClassPath, extraLibraryPath, javaOpts)
    val actualDriverMemory = driverMemory.map(Utils.memoryStringToMb).getOrElse(DEFAULT_MEMORY)
    val actualDriverCores = driverCores.map(_.toInt).getOrElse(DEFAULT_CORES)
    val actualSuperviseDriver = superviseDriver.map(_.toBoolean).getOrElse(DEFAULT_SUPERVISE)
    new DriverDescription(
      appResource, actualDriverMemory, actualDriverCores, actualSuperviseDriver, command)
  }
}
