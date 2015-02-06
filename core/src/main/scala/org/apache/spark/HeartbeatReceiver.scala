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

import scala.concurrent.duration._
import scala.collection.mutable

import akka.actor.{Actor, Cancellable}

import org.apache.spark.executor.TaskMetrics
import org.apache.spark.storage.BlockManagerId
import org.apache.spark.scheduler.{SlaveLost, TaskScheduler}
import org.apache.spark.util.ActorLogReceive

/**
 * A heartbeat from executors to the driver. This is a shared message used by several internal
 * components to convey liveness or execution information for in-progress tasks. It will also 
 * expire the hosts that have not heartbeated for more than spark.driver.executorTimeoutMs.
 */
private[spark] case class Heartbeat(
    executorId: String,
    taskMetrics: Array[(Long, TaskMetrics)], // taskId -> TaskMetrics
    blockManagerId: BlockManagerId)

private[spark] case object ExpireDeadHosts 
    
private[spark] case class HeartbeatResponse(reregisterBlockManager: Boolean)

/**
 * Lives in the driver to receive heartbeats from executors..
 */
private[spark] class HeartbeatReceiver(sc: SparkContext, scheduler: TaskScheduler)
  extends Actor with ActorLogReceive with Logging {

  val executorLastSeen = new mutable.HashMap[String, Long]
  
  val executorTimeout = sc.conf.getLong("spark.driver.executorTimeoutMs", 
    sc.conf.getLong("spark.storage.blockManagerSlaveTimeoutMs", 120 * 1000))
  
  val checkTimeoutInterval = sc.conf.getLong("spark.driver.executorTimeoutIntervalMs",
    sc.conf.getLong("spark.storage.blockManagerTimeoutIntervalMs", 60000))
  
  var timeoutCheckingTask: Cancellable = null
  
  override def preStart(): Unit = {
    import context.dispatcher
    timeoutCheckingTask = context.system.scheduler.schedule(0.seconds,
      checkTimeoutInterval.milliseconds, self, ExpireDeadHosts)
    super.preStart
  }
  
  override def receiveWithLogging = {
    case Heartbeat(executorId, taskMetrics, blockManagerId) =>
      val response = HeartbeatResponse(
        !scheduler.executorHeartbeatReceived(executorId, taskMetrics, blockManagerId))
      heartbeatReceived(executorId)
      sender ! response
    case ExpireDeadHosts =>
      expireDeadHosts()
  }
  
  private def heartbeatReceived(executorId: String) = {
    executorLastSeen(executorId) = System.currentTimeMillis()
  }
  
  private def expireDeadHosts(): Unit = {
    logTrace("Checking for hosts with no recent heart beats in HeartbeatReceiver.")
    val now = System.currentTimeMillis()
    val minSeenTime = now - executorTimeout
    for ((executorId, lastSeenMs) <- executorLastSeen) {
      if (lastSeenMs < minSeenTime) {
        logWarning("Removing Executor " + executorId + " with no recent heartbeats: "
          + (now - lastSeenMs) + " ms exceeds " + executorTimeout + "ms")
        scheduler.executorLost(executorId, SlaveLost())
        sc.killExecutor(executorId)
        executorLastSeen.remove(executorId)
      }
    }
  }
  
  override def postStop(): Unit = {
    if (timeoutCheckingTask != null) {
      timeoutCheckingTask.cancel()
    }
    super.postStop
  }
}
