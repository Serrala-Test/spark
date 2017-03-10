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

package org.apache.spark

import java.util.Properties
import javax.annotation.concurrent.GuardedBy

import scala.collection.mutable.ArrayBuffer

import org.apache.spark.executor.TaskMetrics
import org.apache.spark.internal.Logging
import org.apache.spark.memory.TaskMemoryManager
import org.apache.spark.metrics.MetricsSystem
import org.apache.spark.metrics.source.Source
import org.apache.spark.shuffle.FetchFailedException
import org.apache.spark.util._

/**
 * A [[TaskContext]] implementation.
 *
 * A small note on thread safety. The interrupted, completed, failed and fetchFailed fields are
 * volatile, this makes sure that updates are always visible across threads. We synchronize on the
 * context instance when it is marked as completed (or failed) and the relevant callback are
 * invoked. We also synchronize on the context instance when a callback is added. This ensures
 * that we cannot add a callback in one thread, while we are invoking those callbacks in another
 * thread. Other methods are not thread safe.
 */
private[spark] class TaskContextImpl(
    val stageId: Int,
    val partitionId: Int,
    override val taskAttemptId: Long,
    override val attemptNumber: Int,
    override val taskMemoryManager: TaskMemoryManager,
    localProperties: Properties,
    @transient private val metricsSystem: MetricsSystem,
    // The default value is only used in tests.
    override val taskMetrics: TaskMetrics = TaskMetrics.empty)
  extends TaskContext
  with Logging {

  /** List of callback functions to execute when the task completes. */
  @transient private val onCompleteCallbacks = new ArrayBuffer[TaskCompletionListener]

  /** List of callback functions to execute when the task fails. */
  @transient private val onFailureCallbacks = new ArrayBuffer[TaskFailureListener]

  // Whether the corresponding task has been killed.
  @volatile private var interrupted: Boolean = false

  // Whether the task has completed.
  @volatile private var completed: Boolean = false

  // Whether the task has failed.
  @volatile private var failed: Boolean = false

  // Throwable that caused the task to fail
  private var failure: Throwable = _

  // If there was a fetch failure in the task, we store it here, to make sure user-code doesn't
  // hide the exception.  See SPARK-19276
  @volatile private var _fetchFailedException: Option[FetchFailedException] = None

  @GuardedBy("this")
  override def addTaskCompletionListener(listener: TaskCompletionListener): this.type = {
    synchronized {
      if (completed) {
        listener.onTaskCompletion(this)
      }
      // Always add the listener because it is legal to call them multiple times.
      onCompleteCallbacks += listener
    }
    this
  }

  @GuardedBy("this")
  override def addTaskFailureListener(listener: TaskFailureListener): this.type = {
    synchronized {
      if (failed) {
        listener.onTaskFailure(this, failure)
      } else {
        onFailureCallbacks += listener
      }
    }
    this
  }

  /** Marks the task as failed and triggers the failure listeners. */
  @GuardedBy("this")
  private[spark] def markTaskFailed(error: Throwable): Unit = synchronized {
    if (failed) return
    failure = error
    failed = true
    invokeListeners(onFailureCallbacks, "TaskFailureListener", Option(error)) {
      _.onTaskFailure(this, error)
    }
  }

  /** Marks the task as completed and triggers the completion listeners. */
  @GuardedBy("this")
  private[spark] def markTaskCompleted(): Unit = synchronized {
    completed = true
    invokeListeners(onCompleteCallbacks, "TaskCompletionListener", None) {
      _.onTaskCompletion(this)
    }
  }

  private def invokeListeners[T](
      listeners: Seq[T],
      name: String,
      error: Option[Throwable])(
      callback: T => Unit): Unit = {
    val errorMsgs = new ArrayBuffer[String](2)
    // Process callbacks in the reverse order of registration
    listeners.reverse.foreach { listener =>
      try {
        callback(listener)
      } catch {
        case e: Throwable =>
          errorMsgs += e.getMessage
          logError(s"Error in $name", e)
      }
    }
    if (errorMsgs.nonEmpty) {
      throw new TaskCompletionListenerException(errorMsgs, error)
    }
  }

  /** Marks the task for interruption, i.e. cancellation. */
  private[spark] def markInterrupted(): Unit = {
    interrupted = true
  }

  override def isCompleted(): Boolean = completed

  override def isRunningLocally(): Boolean = false

  override def isInterrupted(): Boolean = interrupted

  override def getLocalProperty(key: String): String = localProperties.getProperty(key)

  override def getMetricsSources(sourceName: String): Seq[Source] =
    metricsSystem.getSourcesByName(sourceName)

  private[spark] override def registerAccumulator(a: AccumulatorV2[_, _]): Unit = {
    taskMetrics.registerAccumulator(a)
  }

  private[spark] override def setFetchFailed(fetchFailed: FetchFailedException): Unit = {
    this._fetchFailedException = Option(fetchFailed)
  }

  private[spark] def fetchFailed: Option[FetchFailedException] = _fetchFailedException

}
