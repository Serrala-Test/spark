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

package org.apache.spark.deploy

import net.liftweb.json.JsonDSL._

import org.apache.spark.deploy.DeployMessages.{MasterStateResponse, WorkerStateResponse}
import org.apache.spark.deploy.master.{ApplicationInfo, WorkerInfo}
import org.apache.spark.deploy.worker.ExecutorRunner
import org.apache.spark.scheduler.{SchedulingMode, StageInfo, TaskInfo}
import org.apache.spark.storage.RDDInfo
import org.apache.spark.ui.jobs.{StageTable}
import org.apache.spark.util.Utils


private[spark] object JsonProtocol {
 def writeWorkerInfo(obj: WorkerInfo) = {
   ("id" -> obj.id) ~
   ("host" -> obj.host) ~
   ("port" -> obj.port) ~
   ("webuiaddress" -> obj.webUiAddress) ~
   ("cores" -> obj.cores) ~
   ("coresused" -> obj.coresUsed) ~
   ("memory" -> obj.memory) ~
   ("memoryused" -> obj.memoryUsed) ~
   ("state" -> obj.state.toString)
 }

  def writeApplicationInfo(obj: ApplicationInfo) = {
    ("starttime" -> obj.startTime) ~
    ("id" -> obj.id) ~
    ("name" -> obj.desc.name) ~
    ("appuiurl" -> obj.appUiUrl) ~
    ("cores" -> obj.desc.maxCores) ~
    ("user" ->  obj.desc.user) ~
    ("memoryperslave" -> obj.desc.memoryPerSlave) ~
    ("submitdate" -> obj.submitDate.toString) ~
    ("state" -> obj.state.toString) ~
    ("duration" -> obj.duration)
  }

  def writeStageInfo(obj: StageInfo) = {
    ("jobid" -> obj.jobId) ~
    ("stageid" -> obj.stageId) ~
    ("name" -> obj.name) ~
    ("description" -> obj.description) ~
    ("startedTasks" -> obj.startedTasks) ~
    ("completedTasks" -> obj.completedTasks) ~
    ("failedTasks" -> obj.failedTasks) ~
    ("numTasks" -> obj.numTasks) ~
    ("poolName" -> obj.poolName) ~
    ("submissiontime" -> obj.submissionTime.getOrElse(System.currentTimeMillis())) ~
    ("completiontime" -> obj.completionTime.getOrElse(System.currentTimeMillis())) ~
    ("shuffleRead" -> obj.shuffleRead) ~
    ("shuffleWrite" -> obj.shuffleWrite) ~
    ("numStages" -> obj.numStages)
  }

  def writeStagesInfo(obj: Seq[Seq[StageInfo]]) = {
    ("activeStages" -> obj(0).toList.map(writeStageInfo)) ~
    ("completedStages" -> obj(1).toList.map(writeStageInfo)) ~
    ("failedStages" -> obj(2).toList.map(writeStageInfo))
  }

  def writeApplicationDescription(obj: ApplicationDescription) = {
    ("name" -> obj.name) ~
    ("cores" -> obj.maxCores) ~
    ("memoryperslave" -> obj.memoryPerSlave) ~
    ("user" -> obj.user)
  }

  def writeExecutorRunner(obj: ExecutorRunner) = {
    ("id" -> obj.execId) ~
    ("memory" -> obj.memory) ~
    ("appid" -> obj.appId) ~
    ("appdesc" -> writeApplicationDescription(obj.appDesc))
  }

  def writeMasterState(obj: MasterStateResponse) = {
    ("url" -> obj.uri) ~
    ("workers" -> obj.workers.toList.map(writeWorkerInfo)) ~
    ("cores" -> obj.workers.map(_.cores).sum) ~
    ("coresused" -> obj.workers.map(_.coresUsed).sum) ~
    ("memory" -> obj.workers.map(_.memory).sum) ~
    ("memoryused" -> obj.workers.map(_.memoryUsed).sum) ~
    ("activeapps" -> obj.activeApps.toList.map(writeApplicationInfo)) ~
    ("completedapps" -> obj.completedApps.toList.map(writeApplicationInfo)) ~
    ("status" -> obj.status.toString)
  }

  def writeWorkerState(obj: WorkerStateResponse) = {
    ("id" -> obj.workerId) ~
    ("masterurl" -> obj.masterUrl) ~
    ("masterwebuiurl" -> obj.masterWebUiUrl) ~
    ("cores" -> obj.cores) ~
    ("coresused" -> obj.coresUsed) ~
    ("memory" -> obj.memory) ~
    ("memoryused" -> obj.memoryUsed) ~
    ("executors" -> obj.executors.toList.map(writeExecutorRunner)) ~
    ("finishedexecutors" -> obj.finishedExecutors.toList.map(writeExecutorRunner))
  }

  def writeExecInfo(kv: Seq[String]) = {
    ("execId" -> kv(0)) ~
    ("hostPort" -> kv(1)) ~
    ("rddBlocks" -> kv(2)) ~
    ("memUsed" -> kv(3).toLong) ~
    ("maxMem" -> kv(4).toLong) ~
    ("diskUsed" -> kv(5).toLong) ~
    ("activeTasks" -> kv(6)) ~
    ("failedTasks" -> kv(7)) ~
    ("completedTasks" -> kv(8)) ~
    ("totalTasks" -> kv(9)) ~
    ("totalDuration" -> kv(10).toLong) ~
    ("totalShuffleRead" -> kv(11).toLong) ~
    ("totalShuffleWrite" -> kv(12).toLong)
  }

  def writeExecutorsInfo(memUsed: String, maxMem: String, diskUsed: String,
                             execInfo: Seq[Seq[String]]) = {
    ("memUsed" -> memUsed) ~
    ("maxMem" -> maxMem) ~
    ("diskUsed" -> diskUsed) ~
    ("execInfo" -> execInfo.toList.map(writeExecInfo))
  }

  def writeRDDInfo(rdd: RDDInfo) = {
    ("name" -> rdd.name) ~
    ("storageLevel" -> rdd.storageLevel.description) ~
    ("numCachedPartitions" -> rdd.numCachedPartitions) ~
    ("numPartitions" -> rdd.numPartitions) ~
    ("memSize" -> Utils.bytesToString(rdd.memSize)) ~
    ("diskSize" -> Utils.bytesToString(rdd.diskSize))
  }

  def writeStorageInfo(storageInfo: Seq[RDDInfo]) = {
    ("storageInfo" -> storageInfo.toList.map(writeRDDInfo)) ~
    ("meta" -> "Storage Info")
  }
}
