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
package org.apache.spark.sql.execution.ui

import java.util.Date
import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConverters._
import scala.collection.mutable.HashMap

import org.apache.spark.{JobExecutionStatus, SparkConf}
import org.apache.spark.internal.Logging
import org.apache.spark.scheduler._
import org.apache.spark.sql.execution.SQLExecution
import org.apache.spark.sql.execution.metric._
import org.apache.spark.sql.internal.StaticSQLConf._
import org.apache.spark.status.LiveEntity
import org.apache.spark.status.config._
import org.apache.spark.ui.SparkUI
import org.apache.spark.util.kvstore.KVStore

private[sql] class SQLAppStatusListener(
    conf: SparkConf,
    kvstore: KVStore,
    live: Boolean,
    ui: Option[SparkUI] = None)
  extends SparkListener with Logging {

  // How often to flush intermediate state of a live execution to the store. When replaying logs,
  // never flush (only do the very last write).
  private val liveUpdatePeriodNs = if (live) conf.get(LIVE_ENTITY_UPDATE_PERIOD) else -1L

  private val liveExecutions = new HashMap[Long, LiveExecutionData]()
  private val stageMetrics = new HashMap[Int, LiveStageMetrics]()

  private var uiInitialized = false

  override def onJobStart(event: SparkListenerJobStart): Unit = {
    val executionIdString = event.properties.getProperty(SQLExecution.EXECUTION_ID_KEY)
    if (executionIdString == null) {
      // This is not a job created by SQL
      return
    }

    val executionId = executionIdString.toLong
    val jobId = event.jobId
    val exec = getOrCreateExecution(executionId)

    // Record the accumulator IDs for the stages of this job, so that the code that keeps
    // track of the metrics knows which accumulators to look at.
    val accumIds = exec.metrics.map(_.accumulatorId).sorted.toList
    event.stageIds.foreach { id =>
      stageMetrics.put(id, new LiveStageMetrics(id, 0, accumIds.toArray, new ConcurrentHashMap()))
    }

    exec.jobs = exec.jobs + (jobId -> JobExecutionStatus.RUNNING)
    exec.stages = event.stageIds.toSet
    update(exec)
  }

  override def onStageSubmitted(event: SparkListenerStageSubmitted): Unit = {
    if (!isSQLStage(event.stageInfo.stageId)) {
      return
    }

    // Reset the metrics tracking object for the new attempt.
    stageMetrics.get(event.stageInfo.stageId).foreach { metrics =>
      metrics.taskMetrics.clear()
      metrics.attemptId = event.stageInfo.attemptId
    }
  }

  override def onJobEnd(event: SparkListenerJobEnd): Unit = {
    liveExecutions.values.foreach { exec =>
      if (exec.jobs.contains(event.jobId)) {
        val result = event.jobResult match {
          case JobSucceeded => JobExecutionStatus.SUCCEEDED
          case _ => JobExecutionStatus.FAILED
        }
        exec.jobs = exec.jobs + (event.jobId -> result)
        exec.endEvents += 1
        update(exec)
      }
    }
  }

  override def onExecutorMetricsUpdate(event: SparkListenerExecutorMetricsUpdate): Unit = {
    event.accumUpdates.foreach { case (taskId, stageId, attemptId, accumUpdates) =>
      updateStageMetrics(stageId, attemptId, taskId, accumUpdates, false)
    }
  }

  override def onTaskEnd(event: SparkListenerTaskEnd): Unit = {
    if (!isSQLStage(event.stageId)) {
      return
    }

    val info = event.taskInfo
    // SPARK-20342. If processing events from a live application, use the task metrics info to
    // work around a race in the DAGScheduler. The metrics info does not contain accumulator info
    // when reading event logs in the SHS, so we have to rely on the accumulator in that case.
    val accums = if (live && event.taskMetrics != null) {
      event.taskMetrics.externalAccums.flatMap { a =>
        // This call may fail if the accumulator is gc'ed, so account for that.
        try {
          Some(a.toInfo(Some(a.value), None))
        } catch {
          case _: IllegalAccessError => None
        }
      }
    } else {
      info.accumulables
    }
    updateStageMetrics(event.stageId, event.stageAttemptId, info.taskId, accums,
      info.successful)
  }

  def executionMetrics(executionId: Long): Map[Long, String] = synchronized {
    liveExecutions.get(executionId).map { exec =>
      if (exec.metricsValues != null) {
        exec.metricsValues
      } else {
        aggregateMetrics(exec)
      }
    }.getOrElse {
      throw new NoSuchElementException(s"execution $executionId not found")
    }
  }

  private def aggregateMetrics(exec: LiveExecutionData): Map[Long, String] = synchronized {
    val metricIds = exec.metrics.map(_.accumulatorId).sorted
    val metricTypes = exec.metrics.map { m => (m.accumulatorId, m.metricType) }.toMap
    val metrics = exec.stages.toSeq
      .flatMap(stageMetrics.get)
      .flatMap(_.taskMetrics.values().asScala)
      .flatMap { metrics => metrics.ids.zip(metrics.values) }

    (metrics ++ exec.driverAccumUpdates.toSeq)
      .filter { case (id, _) => metricIds.contains(id) }
      .groupBy(_._1)
      .map { case (id, values) =>
        id -> SQLMetrics.stringValue(metricTypes(id), values.map(_._2).toSeq)
      }
  }

  private def updateStageMetrics(
      stageId: Int,
      attemptId: Int,
      taskId: Long,
      accumUpdates: Seq[AccumulableInfo],
      succeeded: Boolean): Unit = {
    stageMetrics.get(stageId).foreach { metrics =>
      if (metrics.attemptId != attemptId || metrics.accumulatorIds.isEmpty) {
        return
      }

      val oldTaskMetrics = metrics.taskMetrics.get(taskId)
      if (oldTaskMetrics != null && oldTaskMetrics.succeeded) {
        return
      }

      val updates = accumUpdates
        .filter { acc => acc.update.isDefined && metrics.accumulatorIds.contains(acc.id) }
        .sortBy(_.id)

      if (updates.isEmpty) {
        return
      }

      val ids = new Array[Long](updates.size)
      val values = new Array[Long](updates.size)
      updates.zipWithIndex.foreach { case (acc, idx) =>
        ids(idx) = acc.id
        // In a live application, accumulators have Long values, but when reading from event
        // logs, they have String values. For now, assume all accumulators are Long and covert
        // accordingly.
        values(idx) = acc.update.get match {
          case s: String => s.toLong
          case l: Long => l
          case o => throw new IllegalArgumentException(s"Unexpected: $o")
        }
      }

      metrics.taskMetrics.put(taskId, new LiveTaskMetrics(ids, values, succeeded))
    }
  }

  private def onExecutionStart(event: SparkListenerSQLExecutionStart): Unit = {
    // Install the SQL tab in a live app if it hasn't been initialized yet.
    if (!uiInitialized) {
      ui.foreach { _ui =>
        new SQLTab(new SQLAppStatusStore(kvstore, Some(this)), _ui)
      }
      uiInitialized = true
    }

    val SparkListenerSQLExecutionStart(executionId, description, details,
      physicalPlanDescription, sparkPlanInfo, time) = event

    def toStoredNodes(nodes: Seq[SparkPlanGraphNode]): Seq[SparkPlanGraphNodeWrapper] = {
      nodes.map {
        case cluster: SparkPlanGraphCluster =>
          val storedCluster = new SparkPlanGraphClusterWrapper(
            cluster.id,
            cluster.name,
            cluster.desc,
            toStoredNodes(cluster.nodes),
            cluster.metrics)
          new SparkPlanGraphNodeWrapper(null, storedCluster)

        case node =>
          new SparkPlanGraphNodeWrapper(node, null)
      }
    }

    val planGraph = SparkPlanGraph(sparkPlanInfo)
    val sqlPlanMetrics = planGraph.allNodes.flatMap { node =>
      node.metrics.map { metric => (metric.accumulatorId, metric) }
    }.toMap.values.toList

    val graphToStore = new SparkPlanGraphWrapper(
      executionId,
      toStoredNodes(planGraph.nodes),
      planGraph.edges)
    kvstore.write(graphToStore)

    val exec = getOrCreateExecution(executionId)
    exec.description = description
    exec.details = details
    exec.physicalPlanDescription = physicalPlanDescription
    exec.metrics = sqlPlanMetrics
    exec.submissionTime = time
    update(exec)
  }

  private def onExecutionEnd(event: SparkListenerSQLExecutionEnd): Unit = {
    val SparkListenerSQLExecutionEnd(executionId, time) = event
    liveExecutions.get(executionId).foreach { exec =>
      synchronized {
        exec.metricsValues = aggregateMetrics(exec)

        // Remove stale LiveStageMetrics objects for stages that are not active anymore.
        val activeStages = liveExecutions.values.flatMap { other =>
          if (other != exec) other.stages else Nil
        }.toSet
        stageMetrics.retain { case (id, _) => activeStages.contains(id) }

        exec.completionTime = Some(new Date(time))
        exec.endEvents += 1

        update(exec)
      }
    }
  }

  private def onDriverAccumUpdates(event: SparkListenerDriverAccumUpdates): Unit = {
    val SparkListenerDriverAccumUpdates(executionId, accumUpdates) = event
    liveExecutions.get(executionId).foreach { exec =>
      exec.driverAccumUpdates = accumUpdates.toMap
      update(exec)
    }
  }

  override def onOtherEvent(event: SparkListenerEvent): Unit = event match {
    case e: SparkListenerSQLExecutionStart => onExecutionStart(e)
    case e: SparkListenerSQLExecutionEnd => onExecutionEnd(e)
    case e: SparkListenerDriverAccumUpdates => onDriverAccumUpdates(e)
    case _ => // Ignore
  }

  private def getOrCreateExecution(executionId: Long): LiveExecutionData = {
    liveExecutions.getOrElseUpdate(executionId, new LiveExecutionData(executionId))
  }

  private def update(exec: LiveExecutionData): Unit = {
    val now = System.nanoTime()
    if (exec.endEvents >= exec.jobs.size + 1) {
      liveExecutions.remove(exec.executionId)
      exec.write(kvstore, now)
    } else if (liveUpdatePeriodNs >= 0) {
      if (now - exec.lastWriteTime > liveUpdatePeriodNs) {
        exec.write(kvstore, now)
      }
    }
  }

  private def isSQLStage(stageId: Int): Boolean = {
    liveExecutions.values.exists { exec =>
      exec.stages.contains(stageId)
    }
  }

}

private class LiveExecutionData(val executionId: Long) extends LiveEntity {

  var description: String = null
  var details: String = null
  var physicalPlanDescription: String = null
  var metrics = Seq[SQLPlanMetric]()
  var submissionTime = -1L
  var completionTime: Option[Date] = None

  var jobs = Map[Int, JobExecutionStatus]()
  var stages = Set[Int]()
  var driverAccumUpdates = Map[Long, Long]()

  var metricsValues: Map[Long, String] = null

  // Just in case job end and execution end arrive out of order, keep track of how many
  // end events arrived so that the listener can stop tracking the execution.
  var endEvents = 0

  override protected def doUpdate(): Any = {
    new SQLExecutionUIData(
      executionId,
      description,
      details,
      physicalPlanDescription,
      metrics,
      submissionTime,
      completionTime,
      jobs,
      stages,
      metricsValues)
  }

}

private class LiveStageMetrics(
    val stageId: Int,
    var attemptId: Int,
    val accumulatorIds: Array[Long],
    val taskMetrics: ConcurrentHashMap[Long, LiveTaskMetrics])

private[sql] class LiveTaskMetrics(
    val ids: Array[Long],
    val values: Array[Long],
    val succeeded: Boolean)
