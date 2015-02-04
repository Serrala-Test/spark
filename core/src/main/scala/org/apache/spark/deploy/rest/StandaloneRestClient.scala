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

import java.io.{FileNotFoundException, DataOutputStream}
import java.net.{HttpURLConnection, URL}

import scala.io.Source

import com.google.common.base.Charsets

import org.apache.spark.{Logging, SparkException, SPARK_VERSION => sparkVersion}
import org.apache.spark.deploy.SparkSubmitArguments

/**
 * A client that submits applications to the standalone Master using a REST protocol.
 * This client is intended to communicate with the [[StandaloneRestServer]] and is
 * currently used for cluster mode only.
 *
 * The specific request sent to the server depends on the action as follows:
 *   (1) submit - POST to /submissions/create
 *   (2) kill - POST /submissions/kill/[submissionId]
 *   (3) status - GET /submissions/status/[submissionId]
 *
 * In the case of (1), parameters are posted in the HTTP body in the form of JSON fields.
 * Otherwise, the URL fully specifies the intended action of the client.
 *
 * Additionally, the base URL includes the version of the protocol. For instance:
 * http://1.2.3.4:6066/v1/submissions/create. Since the protocol is expected to be stable
 * across Spark versions, existing fields cannot be added or removed. In the rare event that
 * backward compatibility is broken, Spark must introduce a new protocol version (e.g. v2).
 * The client and the server must communicate on the same version of the protocol.
 */
private[spark] class StandaloneRestClient extends Logging {
  import StandaloneRestClient._

  /**
   * Submit an application specified by the provided arguments.
   *
   * If the submission was successful, poll the status of the submission and report
   * it to the user. Otherwise, report the error message provided by the server.
   */
  def createSubmission(args: SparkSubmitArguments): SubmitRestProtocolResponse = {
    logInfo(s"Submitting a request to launch a driver in ${args.master}.")
    validateSubmitArgs(args)
    val master = args.master
    val url = getSubmitUrl(master)
    val request = constructSubmitRequest(args)
    val response = postJson(url, request.toJson)
    response match {
      case s: CreateSubmissionResponse => reportSubmissionStatus(master, s)
      case _ => // unexpected type, let upstream caller handle it
    }
    response
  }

  /** Request that the server kill the specified submission. */
  def killSubmission(master: String, submissionId: String): SubmitRestProtocolResponse = {
    logInfo(s"Submitting a request to kill submission $submissionId in $master.")
    validateMaster(master)
    post(getKillUrl(master, submissionId))
  }

  /** Request the status of a submission from the server. */
  def requestSubmissionStatus(master: String, submissionId: String): SubmitRestProtocolResponse = {
    logInfo(s"Submitting a request for the status of submission $submissionId in $master.")
    validateMaster(master)
    get(getStatusUrl(master, submissionId))
  }

  /** Send a GET request to the specified URL. */
  private def get(url: URL): SubmitRestProtocolResponse = {
    logDebug(s"Sending GET request to server at $url.")
    val conn = url.openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestMethod("GET")
    readResponse(conn)
  }

  /** Send a POST request to the specified URL. */
  private def post(url: URL): SubmitRestProtocolResponse = {
    logDebug(s"Sending POST request to server at $url.")
    val conn = url.openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestMethod("POST")
    readResponse(conn)
  }

  /** Send a POST request with the given JSON as the body to the specified URL. */
  private def postJson(url: URL, json: String): SubmitRestProtocolResponse = {
    logDebug(s"Sending POST request to server at $url:\n$json")
    val conn = url.openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestMethod("POST")
    conn.setRequestProperty("Content-Type", "application/json")
    conn.setRequestProperty("charset", "utf-8")
    conn.setDoOutput(true)
    val out = new DataOutputStream(conn.getOutputStream)
    out.write(json.getBytes(Charsets.UTF_8))
    out.close()
    readResponse(conn)
  }

  /**
   * Read the response from the given connection.
   *
   * The response is expected to represent a [[SubmitRestProtocolResponse]] in the form of JSON.
   * Additionally, this validates the response to ensure that it is properly constructed.
   * If the response represents an error, report the message from the server.
   */
  private def readResponse(connection: HttpURLConnection): SubmitRestProtocolResponse = {
    try {
      val responseJson = Source.fromInputStream(connection.getInputStream).mkString
      logDebug(s"Response from the REST server:\n$responseJson")
      val response = SubmitRestProtocolMessage.fromJson(responseJson)
      // The response should have already been validated on the server.
      // In case this is not true, validate it ourselves to avoid potential NPEs.
      try {
        response.validate()
      } catch {
        case e: SubmitRestProtocolException =>
          throw new SubmitRestProtocolException("Malformed response received from server", e)
      }
      // If the response is an error, log the message
      // Otherwise, simply return the response
      response match {
        case error: ErrorResponse =>
          logError(s"Server responded with error:\n${error.message}")
          error
        case response: SubmitRestProtocolResponse =>
          response
        case unexpected =>
          throw new SubmitRestProtocolException(
            s"Unexpected message received from server:\n$unexpected")
      }
    } catch {
      case e: FileNotFoundException =>
        throw new SparkException(s"Unable to connect to server ${connection.getURL}", e)
    }
  }

  /** Return the REST URL for creating a new submission. */
  private def getSubmitUrl(master: String): URL = {
    val baseUrl = getBaseUrl(master)
    new URL(s"$baseUrl/submissions/create")
  }

  /** Return the REST URL for killing an existing submission. */
  private def getKillUrl(master: String, submissionId: String): URL = {
    val baseUrl = getBaseUrl(master)
    new URL(s"$baseUrl/submissions/kill/$submissionId")
  }

  /** Return the REST URL for requesting the status of an existing submission. */
  private def getStatusUrl(master: String, submissionId: String): URL = {
    val baseUrl = getBaseUrl(master)
    new URL(s"$baseUrl/submissions/status/$submissionId")
  }

  /** Return the base URL for communicating with the server, including the protocol version. */
  private def getBaseUrl(master: String): String = {
    "http://" + master.stripPrefix("spark://").stripSuffix("/") + "/" + PROTOCOL_VERSION
  }

  /** Throw an exception if this is not standalone mode. */
  private def validateMaster(master: String): Unit = {
    if (!master.startsWith("spark://")) {
      throw new IllegalArgumentException("This REST client is only supported in standalone mode.")
    }
  }

  /** Throw an exception if this is not standalone cluster mode. */
  private def validateSubmitArgs(args: SparkSubmitArguments): Unit = {
    if (!args.isStandaloneCluster) {
      throw new IllegalArgumentException(
        "This REST client is only supported in standalone cluster mode.")
    }
  }

  /** Construct a message that captures the specified parameters for submitting an application. */
  private def constructSubmitRequest(args: SparkSubmitArguments): CreateSubmissionRequest = {
    val message = new CreateSubmissionRequest
    message.clientSparkVersion = sparkVersion
    message.appName = args.name
    message.appResource = args.primaryResource
    message.mainClass = args.mainClass
    message.jars = args.jars
    message.files = args.files
    message.driverMemory = args.driverMemory
    message.driverCores = args.driverCores
    message.driverExtraJavaOptions = args.driverExtraJavaOptions
    message.driverExtraClassPath = args.driverExtraClassPath
    message.driverExtraLibraryPath = args.driverExtraLibraryPath
    message.superviseDriver = args.supervise.toString
    message.executorMemory = args.executorMemory
    message.totalExecutorCores = args.totalExecutorCores
    args.childArgs.foreach(message.addAppArg)
    args.sparkProperties.foreach { case (k, v) => message.setSparkProperty(k, v) }
    sys.env.foreach { case (k, v) =>
      if (k.startsWith("SPARK_")) { message.setEnvironmentVariable(k, v) }
    }
    message.validate()
    message
  }

  /** Report the status of a newly created submission. */
  private def reportSubmissionStatus(master: String, submitResponse: CreateSubmissionResponse): Unit = {
    val submitSuccess = submitResponse.success.toBoolean
    if (submitSuccess) {
      val submissionId = submitResponse.submissionId
      if (submissionId != null) {
        logInfo(s"Driver successfully submitted as $submissionId. Polling driver state...")
        pollSubmissionStatus(master, submissionId)
      } else {
        logError("Application successfully submitted, but driver ID was not provided!")
      }
    } else {
      val failMessage = Option(submitResponse.message).map { ": " + _ }.getOrElse("")
      logError("Application submission failed" + failMessage)
    }
  }

  /**
   * Poll the status of the specified submission and log it.
   * This retries up to a fixed number of times before giving up.
   */
  private def pollSubmissionStatus(master: String, submissionId: String): Unit = {
    (1 to REPORT_DRIVER_STATUS_MAX_TRIES).foreach { _ =>
      val response = requestSubmissionStatus(master, submissionId)
      val statusResponse = response match {
        case s: SubmissionStatusResponse => s
        case _ => return // unexpected type, let upstream caller handle it
      }
      val statusSuccess = statusResponse.success.toBoolean
      if (statusSuccess) {
        val driverState = Option(statusResponse.driverState)
        val workerId = Option(statusResponse.workerId)
        val workerHostPort = Option(statusResponse.workerHostPort)
        val exception = Option(statusResponse.message)
        // Log driver state, if present
        driverState match {
          case Some(state) => logInfo(s"State of driver $submissionId is now $state.")
          case _ => logError(s"State of driver $submissionId was not found!")
        }
        // Log worker node, if present
        (workerId, workerHostPort) match {
          case (Some(id), Some(hp)) => logInfo(s"Driver is running on worker $id at $hp.")
          case _ =>
        }
        // Log exception stack trace, if present
        exception.foreach { e => logError(e) }
        return
      }
      Thread.sleep(REPORT_DRIVER_STATUS_INTERVAL)
    }
    logError(s"Error: Master did not recognize submission $submissionId.")
  }
}

private object StandaloneRestClient {
  val REPORT_DRIVER_STATUS_INTERVAL = 1000
  val REPORT_DRIVER_STATUS_MAX_TRIES = 10
  val PROTOCOL_VERSION = "v1"
}
