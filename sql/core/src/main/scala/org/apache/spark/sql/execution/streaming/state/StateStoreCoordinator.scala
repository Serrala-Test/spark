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

package org.apache.spark.sql.execution.streaming.state

import java.util.UUID

import scala.collection.mutable
import scala.util.Try

import org.apache.spark.{SparkConf, SparkEnv}
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.internal.Logging
import org.apache.spark.rpc.{RpcCallContext, RpcEndpointRef, RpcEnv, ThreadSafeRpcEndpoint}
import org.apache.spark.scheduler.ExecutorCacheTaskLocation
import org.apache.spark.sql.types.StructType
import org.apache.spark.util.RpcUtils

/** Trait representing all messages to [[StateStoreCoordinator]] */
private sealed trait StateStoreCoordinatorMessage extends Serializable

/** Classes representing messages */
private case class ReportActiveInstance(
    storeId: StateStoreProviderId,
    host: String,
    executorId: String)
  extends StateStoreCoordinatorMessage

private case class VerifyIfInstanceActive(storeId: StateStoreProviderId, executorId: String)
  extends StateStoreCoordinatorMessage

private case class GetLocation(storeId: StateStoreProviderId)
  extends StateStoreCoordinatorMessage

private case class ValidateSchema(
    storeProviderId: StateStoreProviderId,
    keySchema: StructType,
    valueSchema: StructType,
    checkEnabled: Boolean) extends StateStoreCoordinatorMessage

private case class DeactivateInstances(runId: UUID)
  extends StateStoreCoordinatorMessage

private object StopCoordinator
  extends StateStoreCoordinatorMessage

/** Helper object used to create reference to [[StateStoreCoordinator]]. */
object StateStoreCoordinatorRef extends Logging {

  private val endpointName = "StateStoreCoordinator"

  /**
   * Create a reference to a [[StateStoreCoordinator]]
   */
  def forDriver(env: SparkEnv): StateStoreCoordinatorRef = synchronized {
    try {

      val coordinator = new StateStoreCoordinator(env.conf, env.rpcEnv)
      val coordinatorRef = env.rpcEnv.setupEndpoint(endpointName, coordinator)
      logInfo("Registered StateStoreCoordinator endpoint")
      new StateStoreCoordinatorRef(coordinatorRef)
    } catch {
      case e: IllegalArgumentException =>
        val rpcEndpointRef = RpcUtils.makeDriverRef(endpointName, env.conf, env.rpcEnv)
        logDebug("Retrieved existing StateStoreCoordinator endpoint")
        new StateStoreCoordinatorRef(rpcEndpointRef)
    }
  }

  def forExecutor(env: SparkEnv): StateStoreCoordinatorRef = synchronized {
    val rpcEndpointRef = RpcUtils.makeDriverRef(endpointName, env.conf, env.rpcEnv)
    logDebug("Retrieved existing StateStoreCoordinator endpoint")
    new StateStoreCoordinatorRef(rpcEndpointRef)
  }
}

/**
 * Reference to a [[StateStoreCoordinator]] that can be used to coordinate instances of
 * [[StateStore]]s across all the executors, and get their locations for job scheduling.
 */
class StateStoreCoordinatorRef private(rpcEndpointRef: RpcEndpointRef) {
  private[sql] def reportActiveInstance(
      stateStoreProviderId: StateStoreProviderId,
      host: String,
      executorId: String): Unit = {
    rpcEndpointRef.send(ReportActiveInstance(stateStoreProviderId, host, executorId))
  }

  /** Verify whether the given executor has the active instance of a state store */
  private[sql] def verifyIfInstanceActive(
      stateStoreProviderId: StateStoreProviderId,
      executorId: String): Boolean = {
    rpcEndpointRef.askSync[Boolean](VerifyIfInstanceActive(stateStoreProviderId, executorId))
  }

  /** Get the location of the state store */
  private[sql] def getLocation(stateStoreProviderId: StateStoreProviderId): Option[String] = {
    rpcEndpointRef.askSync[Option[String]](GetLocation(stateStoreProviderId))
  }

  /** Deactivate instances related to a query */
  private[sql] def deactivateInstances(runId: UUID): Unit = {
    rpcEndpointRef.askSync[Boolean](DeactivateInstances(runId))
  }

  /** Validate state store operator's schema to see it's compatible with existing schema */
  private[sql] def validateSchema(
      storeProviderId: StateStoreProviderId,
      keySchema: StructType,
      valueSchema: StructType,
      checkEnabled: Boolean): Option[Exception] = {
    rpcEndpointRef.askSync[Option[Exception]](
      ValidateSchema(storeProviderId, keySchema, valueSchema, checkEnabled))
  }

  private[state] def stop(): Unit = {
    rpcEndpointRef.askSync[Boolean](StopCoordinator)
  }
}


/**
 * Class for coordinating instances of [[StateStore]]s loaded in executors across the cluster,
 * and get their locations for job scheduling.
 */
private class StateStoreCoordinator(conf: SparkConf, override val rpcEnv: RpcEnv)
    extends ThreadSafeRpcEndpoint with Logging {
  private val instances = new mutable.HashMap[StateStoreProviderId, ExecutorCacheTaskLocation]
  private val schemaValidated = new mutable.HashMap[StateStoreProviderId, Option[Throwable]]

  private lazy val hadoopConf = SparkHadoopUtil.get.newConfiguration(conf)

  override def receive: PartialFunction[Any, Unit] = {
    case ReportActiveInstance(id, host, executorId) =>
      logDebug(s"Reported state store $id is active at $executorId")
      instances.put(id, ExecutorCacheTaskLocation(host, executorId))
  }

  override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
    case VerifyIfInstanceActive(id, execId) =>
      val response = instances.get(id) match {
        case Some(location) => location.executorId == execId
        case None => false
      }
      logDebug(s"Verified that state store $id is active: $response")
      context.reply(response)

    case GetLocation(id) =>
      val executorId = instances.get(id).map(_.toString)
      logDebug(s"Got location of the state store $id: $executorId")
      context.reply(executorId)

    case DeactivateInstances(runId) =>
      val storeIdsToRemove =
        instances.keys.filter(_.queryRunId == runId).toSeq
      instances --= storeIdsToRemove
      logDebug(s"Deactivating instances related to checkpoint location $runId: " +
        storeIdsToRemove.mkString(", "))
      context.reply(true)

    case ValidateSchema(providerId, keySchema, valueSchema, checkEnabled) =>
      require(providerId.storeId.partitionId == -1, "Expect the normalized partition ID in" +
        " provider ID")

      val result = schemaValidated.getOrElseUpdate(providerId, {
        val checker = new StateSchemaCompatibilityChecker(providerId, hadoopConf)

        // regardless of configuration, we check compatibility to at least write schema file
        // if necessary
        val ret = Try(checker.check(keySchema, valueSchema)).toEither.fold(Some(_), _ => None)
        if (checkEnabled) {
          ret
        } else {
          None
        }
      })

      context.reply(result)

    case StopCoordinator =>
      stop() // Stop before replying to ensure that endpoint name has been deregistered
      logInfo("StateStoreCoordinator stopped")
      context.reply(true)
  }
}
