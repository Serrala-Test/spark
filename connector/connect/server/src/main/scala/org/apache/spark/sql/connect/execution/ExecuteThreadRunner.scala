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

package org.apache.spark.sql.connect.execution

import scala.util.control.NonFatal

import com.google.protobuf.Message
import org.apache.commons.lang3.StringUtils

import org.apache.spark.SparkSQLException
import org.apache.spark.connect.proto
import org.apache.spark.internal.Logging
import org.apache.spark.sql.connect.common.ProtoUtils
import org.apache.spark.sql.connect.planner.SparkConnectPlanner
import org.apache.spark.sql.connect.service.ExecuteHolder
import org.apache.spark.sql.connect.utils.ErrorUtils
import org.apache.spark.util.Utils

/**
 * This class launches the actual execution in an execution thread. The execution pushes the
 * responses to a ExecuteResponseObserver in executeHolder. ExecuteResponseObserver holds the
 * responses that can be consumed by the RPC thread.
 */
private[connect] class ExecuteThreadRunner(executeHolder: ExecuteHolder) extends Logging {

  // The newly created thread will inherit all InheritableThreadLocals used by Spark,
  // e.g. SparkContext.localProperties. If considering implementing a threadpool,
  // forwarding of thread locals needs to be taken into account.
  private var executionThread: Thread = new ExecutionThread()

  private var interrupted: Boolean = false

  /** Launches the execution in a background thread, returns immediately. */
  def start(): Unit = {
    executionThread.start()
  }

  /** Joins the background execution thread after it is finished. */
  def join(): Unit = {
    executionThread.join()
  }

  private def execute(): Unit = {
    // Outer execute handles errors.
    try {
      try {
        executeInternal()
      } catch {
        // Need to catch throwable instead of NonFatal, because e.g. InterruptedException is fatal.
        case e: Throwable =>
          logDebug(s"Exception in execute: $e")
          // Always cancel all remaining execution after error.
          executeHolder.sessionHolder.session.sparkContext.cancelJobsWithTag(executeHolder.jobTag)
          // Rely on an internal interrupted flag, because Thread.interrupted() could be cleared,
          // and different exceptions like InterruptedException, ClosedByInterruptException etc.
          // could be thrown.
          if (interrupted) {
            // Turn the interrupt into OPERATION_CANCELLED error.
            throw new SparkSQLException("OPERATION_CANCELLED", Map.empty)
          } else {
            // Rethrown the original error.
            throw e
          }
      } finally {
        executeHolder.sessionHolder.session.sparkContext.removeJobTag(executeHolder.jobTag)
      }
    } catch {
      ErrorUtils.handleError(
        "execute",
        executeHolder.responseObserver,
        executeHolder.sessionHolder.userId,
        executeHolder.sessionHolder.sessionId)
    }
  }

  // Inner executeInternal is wrapped by execute() for error handling.
  private def executeInternal() = {
    // synchronized - check if already got interrupted while starting.
    synchronized {
      if (interrupted) {
        throw new InterruptedException()
      }
    }

    // `withSession` ensures that session-specific artifacts (such as JARs and class files) are
    // available during processing.
    executeHolder.sessionHolder.withSession { session =>
      val debugString = requestString(executeHolder.request)

      // Set tag for query cancellation
      session.sparkContext.addJobTag(executeHolder.jobTag)
      session.sparkContext.setJobDescription(
        s"Spark Connect - ${StringUtils.abbreviate(debugString, 128)}")
      session.sparkContext.setInterruptOnCancel(true)

      // Add debug information to the query execution so that the jobs are traceable.
      session.sparkContext.setLocalProperty(
        "callSite.short",
        s"Spark Connect - ${StringUtils.abbreviate(debugString, 128)}")
      session.sparkContext.setLocalProperty(
        "callSite.long",
        StringUtils.abbreviate(debugString, 2048))

      executeHolder.request.getPlan.getOpTypeCase match {
        case proto.Plan.OpTypeCase.COMMAND => handleCommand(executeHolder.request)
        case proto.Plan.OpTypeCase.ROOT => handlePlan(executeHolder.request)
        case _ =>
          throw new UnsupportedOperationException(
            s"${executeHolder.request.getPlan.getOpTypeCase} not supported.")
      }
    }
  }

  def interrupt(): Unit = {
    synchronized {
      interrupted = true
      executionThread.interrupt()
    }
  }

  private def handlePlan(request: proto.ExecutePlanRequest): Unit = {
    val responseObserver = executeHolder.responseObserver

    val execution = new SparkConnectPlanExecution(executeHolder)
    execution.handlePlan(responseObserver)
  }

  private def handleCommand(request: proto.ExecutePlanRequest): Unit = {
    val responseObserver = executeHolder.responseObserver

    val command = request.getPlan.getCommand
    val planner = new SparkConnectPlanner(executeHolder.sessionHolder)
    planner.process(
      command = command,
      userId = request.getUserContext.getUserId,
      sessionId = request.getSessionId,
      responseObserver = responseObserver)
    responseObserver.onCompleted()
  }

  private def requestString(request: Message) = {
    try {
      Utils.redact(
        executeHolder.sessionHolder.session.sessionState.conf.stringRedactionPattern,
        ProtoUtils.abbreviate(request).toString)
    } catch {
      case NonFatal(e) =>
        logWarning("Fail to extract debug information", e)
        "UNKNOWN"
    }
  }

  private class ExecutionThread extends Thread {
    override def run(): Unit = {
      execute()
    }
  }
}
