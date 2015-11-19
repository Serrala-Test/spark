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

package org.apache.spark.ui.memory

import scala.collection.mutable.HashMap

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.executor.{TransportMetrics, ExecutorMetrics}
import org.apache.spark.scheduler._
import org.apache.spark.scheduler.cluster.ExecutorInfo
import org.apache.spark.ui.{SparkUITab, SparkUI}

private[ui] class MemoryTab(parent: SparkUI) extends SparkUITab(parent, "memory") {
  val memoryListener = parent.memoryListener
  val progressListener = parent.jobProgressListener
  attachPage(new MemoryPage(this))
  attachPage(new StageMemoryPage(this))
}

/**
 * :: DeveloperApi ::
 * A SparkListener that prepares information to be displayed on the MemoryTab
 */
@DeveloperApi
class MemoryListener extends SparkListener {
  type ExecutorId = String
  val activeExecutorIdToMem = new HashMap[ExecutorId, MemoryUIInfo]
  val removedExecutorIdToMem = new HashMap[ExecutorId, MemoryUIInfo]
  // latestExecIdToExecMetrics including all executors that is active and removed.
  // this may consume a lot of memory when executors are changing frequently, e.g. in dynamical
  // allocation mode.
  val latestExecIdToExecMetrics = new HashMap[ExecutorId, ExecutorMetrics]
  // activeStagesToMem a map maintains all executors memory information of each stage,
  // the Map type is [(stageId, attemptId), Seq[(executorId, MemoryUIInfo)]
  val activeStagesToMem = new HashMap[(Int, Int), HashMap[ExecutorId, MemoryUIInfo]]
  val completedStagesToMem = new HashMap[(Int, Int), HashMap[ExecutorId, MemoryUIInfo]]

  override def onExecutorMetricsUpdate(event: SparkListenerExecutorMetricsUpdate): Unit = {
    val executorId = event.execId
    val executorMetrics = event.executorMetrics
    val memoryInfo = activeExecutorIdToMem.getOrElseUpdate(executorId, new MemoryUIInfo)
    memoryInfo.updateExecutorMetrics(executorMetrics)
    activeStagesToMem.foreach { case (_, stageMemMetrics) =>
      if (stageMemMetrics.contains(executorId)) {
        stageMemMetrics.get(executorId).get.updateExecutorMetrics(executorMetrics)
      }
    }
    latestExecIdToExecMetrics.update(executorId, executorMetrics)
  }

  override def onExecutorAdded(event: SparkListenerExecutorAdded): Unit = {
    val executorId = event.executorId
    activeExecutorIdToMem.put(executorId, new MemoryUIInfo(event.executorInfo))
  }

  override def onExecutorRemoved(event: SparkListenerExecutorRemoved): Unit = {
    val executorId = event.executorId
    val info = activeExecutorIdToMem.remove(executorId)
    removedExecutorIdToMem.getOrElseUpdate(executorId, info.getOrElse(new MemoryUIInfo))
  }

  override def onStageSubmitted(event: SparkListenerStageSubmitted): Unit = {
    val stage = (event.stageInfo.stageId, event.stageInfo.attemptId)
    val memInfoMap = new HashMap[ExecutorId, MemoryUIInfo]
    activeExecutorIdToMem.foreach(idToMem => memInfoMap.update(idToMem._1, new MemoryUIInfo))
    activeStagesToMem.update(stage, memInfoMap)
  }

  override def onStageCompleted(event: SparkListenerStageCompleted): Unit = {
    val stage = (event.stageInfo.stageId, event.stageInfo.attemptId)
    activeStagesToMem.get(stage).map { memInfoMap =>
      activeExecutorIdToMem.foreach { case (executorId, _) =>
        val memInfo = memInfoMap.getOrElse(executorId, new MemoryUIInfo)
        latestExecIdToExecMetrics.get(executorId).foreach { prevExecutorMetrics =>
          memInfo.updateExecutorMetrics(prevExecutorMetrics)
        }
        memInfoMap.update(executorId, memInfo)
      }
      completedStagesToMem.put(stage, activeStagesToMem.remove(stage).get)
    }
  }
}

class MemoryUIInfo {
  var executorAddress: String = _
  var transportInfo: Option[TransportMemSize] = None

  def this(execInfo: ExecutorInfo) = {
    this()
    executorAddress = execInfo.executorHost
  }

  def updateExecutorMetrics(execMetrics: ExecutorMetrics): Unit = {
    transportInfo = transportInfo match {
      case Some(transportMemSize) => transportInfo
      case _ => Some(new TransportMemSize)
    }
    executorAddress = execMetrics.hostname
    transportInfo.get.updateTransport(execMetrics.transportMetrics)
  }
}

class TransportMemSize {
  var onHeapSize: Long = _
  var offHeapSize: Long = _
  var peakOnHeapSizeTime: MemTime = new MemTime()
  var peakOffHeapSizeTime: MemTime = new MemTime()

  def updateTransport(transportMetrics: TransportMetrics): Unit = {
    val updatedOnHeapSize = transportMetrics.onHeapSize
    val updatedOffHeapSize = transportMetrics.offHeapSize
    val updateTime: Long = transportMetrics.timeStamp
    onHeapSize = updatedOnHeapSize
    offHeapSize = updatedOffHeapSize
    if (updatedOnHeapSize >= peakOnHeapSizeTime.memorySize) {
      peakOnHeapSizeTime = MemTime(updatedOnHeapSize, updateTime)
    }
    if (updatedOffHeapSize >= peakOffHeapSizeTime.memorySize) {
      peakOffHeapSizeTime = MemTime(updatedOffHeapSize, updateTime)
    }
  }
}

case class MemTime(memorySize: Long = 0L, timeStamp: Long = System.currentTimeMillis)
