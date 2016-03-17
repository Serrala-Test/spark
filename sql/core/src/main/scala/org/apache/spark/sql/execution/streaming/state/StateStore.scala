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

import java.util.{Timer, TimerTask}

import scala.collection.mutable
import scala.util.control.NonFatal

import com.esotericsoftware.kryo.io.{Output, Input}
import com.esotericsoftware.kryo.{Kryo, KryoSerializable}

import org.apache.spark.sql.catalyst.expressions.UnsafeRow
import org.apache.spark.sql.types.StructType
import org.apache.spark.{Logging, SparkEnv}

/** Unique identifier for a [[StateStore]] */
case class StateStoreId(operatorId: Long, partitionId: Int)

/**
 * Base trait for a versioned key-value store used for streaming aggregations
 */
trait StateStore {

  /** Unique identifier of the store */
  def id: StateStoreId

  /** Version of the data in this store before committing updates. */
  def version: Long

  /**
   * Update the value of a key using the value generated by the update function.
   * This can be called only after prepareForUpdates() has been called in the same thread.
   */
  def update(key: UnsafeRow, updateFunc: Option[UnsafeRow] => UnsafeRow): Unit

  /**
   * Remove keys that match the following condition.
   * This can be called only after prepareForUpdates() has been called in the current thread.
   */
  def remove(condition: UnsafeRow => Boolean): Unit

  /**
   * Commit all the updates that have been made to the store.
   * This can be called only after prepareForUpdates() has been called in the current thread.
   */
  def commit(): Long

  /** Cancel all the updates that have been made to the store. */
  def cancel(): Unit

  /**
   * Iterator of store data after a set of updates have been committed.
   * This can be called only after commitUpdates() has been called in the current thread.
   */
  def iterator(): Iterator[(UnsafeRow, UnsafeRow)]

  /**
   * Iterator of the updates that have been committed.
   * This can be called only after commitUpdates() has been called in the current thread.
   */
  def updates(): Iterator[StoreUpdate]

  /**
   * Whether all updates have been committed
   */
  def hasCommitted: Boolean
}


trait StateStoreProvider {

  /** Get the store with the existing version. */
  def getStore(version: Long): StateStore

  /** Optional method for providers to allow for background management */
  def manage(): Unit = { }
}

sealed trait StoreUpdate
case class ValueAdded(key: UnsafeRow, value: UnsafeRow) extends StoreUpdate
case class ValueUpdated(key: UnsafeRow, value: UnsafeRow) extends StoreUpdate
case class KeyRemoved(key: UnsafeRow) extends StoreUpdate


/**
 * Companion object to [[StateStore]] that provides helper methods to create and retrive stores
 * by their unique ids.
 */
private[state] object StateStore extends Logging {

  private val MANAGEMENT_TASK_INTERVAL_SECS = 60

  private val loadedProviders = new mutable.HashMap[StateStoreId, StateStoreProvider]()
  private val managementTimer = new Timer("StateStore Timer", true)
  @volatile private var managementTask: TimerTask = null

  /** Get or create a store associated with the id. */
  def get(
    storeId: StateStoreId,
    directory: String,
    keySchema: StructType,
    valueSchema: StructType,
    version: Long): StateStore = {
    require(version >= 0)
    val storeProvider = loadedProviders.synchronized {
      startIfNeeded()
      val provider = loadedProviders.getOrElseUpdate(
        storeId, new HDFSBackedStateStoreProvider(storeId, directory, keySchema, valueSchema))
      reportActiveInstance(storeId)
      provider
    }
    storeProvider.getStore(version)
  }

  def remove(storeId: StateStoreId): Unit = loadedProviders.synchronized {
    loadedProviders.remove(storeId)
  }

  def isLoaded(storeId: StateStoreId): Boolean = loadedProviders.synchronized {
    loadedProviders.contains(storeId)
  }

  /** Unload and stop all state store provider */
  def stop(): Unit = loadedProviders.synchronized {
    loadedProviders.clear()
    if (managementTask != null) {
      managementTask.cancel()
      managementTask = null
      logInfo("StateStore stopped")
    }
  }

  private def startIfNeeded(): Unit = loadedProviders.synchronized {
    if (managementTask == null) {
      managementTask = new TimerTask {
        override def run(): Unit = { manageAll() }
      }
      val periodMs = Option(SparkEnv.get).map(_.conf) match {
        case Some(conf) =>
          conf.getTimeAsMs(
            "spark.sql.streaming.stateStore.managementInterval",
            s"${MANAGEMENT_TASK_INTERVAL_SECS}s")
        case None =>
          MANAGEMENT_TASK_INTERVAL_SECS * 1000
      }
      managementTimer.schedule(managementTask, periodMs, periodMs)
      logInfo("StateStore started")
    }
  }

  /**
   * Execute background management task in all the loaded store providers if they are still
   * the active instances according to the coordinator.
   */
  private def manageAll(): Unit = {
    loadedProviders.synchronized { loadedProviders.toSeq }.foreach { case (id, provider) =>
      try {
        if (verifyIfInstanceActive(id)) {
          provider.manage()
        } else {
          remove(id)
          logInfo(s"Unloaded $provider")
        }
      } catch {
        case NonFatal(e) =>
          logWarning(s"Error managing $provider")
      }
    }
  }

  private def reportActiveInstance(storeId: StateStoreId): Unit = {
    try {
      val host = SparkEnv.get.blockManager.blockManagerId.host
      val executorId = SparkEnv.get.blockManager.blockManagerId.executorId
      StateStoreCoordinator.ask(ReportActiveInstance(storeId, host, executorId))
    } catch {
      case NonFatal(e) =>
        logWarning(s"Error reporting active instance of $storeId")
    }
  }

  private def verifyIfInstanceActive(storeId: StateStoreId): Boolean = {
    try {
      val executorId = SparkEnv.get.blockManager.blockManagerId.executorId
      StateStoreCoordinator.ask(VerifyIfInstanceActive(storeId, executorId)).getOrElse(false)
    } catch {
      case NonFatal(e) =>
        logWarning(s"Error verifying active instance of $storeId")
        false
    }
  }
}

