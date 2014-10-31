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

package org.apache.spark.streaming.scheduler

import java.nio.ByteBuffer

import scala.collection.mutable
import scala.language.implicitConversions

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path

import org.apache.spark.{Logging, SparkConf}
import org.apache.spark.streaming.Time
import org.apache.spark.streaming.util.{Clock, WriteAheadLogManager}
import org.apache.spark.util.Utils

/** Trait representing any event in the ReceivedBlockTracker that updates its state. */
private[streaming] sealed trait ReceivedBlockTrackerLogEvent

private[streaming] case class BlockAdditionEvent(receivedBlockInfo: ReceivedBlockInfo)
  extends ReceivedBlockTrackerLogEvent
private[streaming] case class BatchAllocationEvent(time: Time, allocatedBlocks: AllocatedBlocks)
  extends ReceivedBlockTrackerLogEvent
private[streaming] case class BatchCleanupEvent(times: Seq[Time])
  extends ReceivedBlockTrackerLogEvent


/** Class representing the blocks of all the streams allocated to a batch */
case class AllocatedBlocks(streamIdToAllocatedBlocks: Map[Int, Seq[ReceivedBlockInfo]]) {
  def getBlockForStream(streamId: Int) = streamIdToAllocatedBlocks(streamId)
}

/**
 * Class that keep track of all the received blocks, and allocate them to batches
 * when required. All actions taken by this class can be saved to a write ahead log
 * (if a checkpoint directory has been provided), so that the state of the tracker
 * (received blocks and block-to-batch allocations) can be recovered after driver failure.
 */
private[streaming]
class ReceivedBlockTracker(
    conf: SparkConf, hadoopConf: Configuration, streamIds: Seq[Int], clock: Clock,
    checkpointDirOption: Option[String]) extends Logging {

  private type ReceivedBlockQueue = mutable.Queue[ReceivedBlockInfo]
  
  private val streamIdToUnallocatedBlockQueues = new mutable.HashMap[Int, ReceivedBlockQueue]
  private val timeToAllocatedBlocks = new mutable.HashMap[Time, AllocatedBlocks]

  private val logManagerRollingIntervalSecs = conf.getInt(
    "spark.streaming.receivedBlockTracker.writeAheadLog.rotationIntervalSecs", 60)
  private val logManagerOption = checkpointDirOption.map { checkpointDir =>
    new WriteAheadLogManager(
      ReceivedBlockTracker.checkpointDirToLogDir(checkpointDir),
      hadoopConf,
      rollingIntervalSecs = logManagerRollingIntervalSecs,
      callerName = "ReceivedBlockHandlerMaster",
      clock = clock
    )
  }

  // Recover block information from write ahead logs
  recoverFromWriteAheadLogs()

  /** Add received block. This event will get written to the write ahead log (if enabled). */
  def addBlock(receivedBlockInfo: ReceivedBlockInfo): Boolean = synchronized {
    try {
      writeToLog(BlockAdditionEvent(receivedBlockInfo))
      getReceivedBlockQueue(receivedBlockInfo.streamId) += receivedBlockInfo
      logDebug(s"Stream ${receivedBlockInfo.streamId} received " +
        s"block ${receivedBlockInfo.blockStoreResult.blockId}")
      true
    } catch {
      case e: Exception =>
        logError(s"Error adding block $receivedBlockInfo", e)
        false
    }
  }

  /**
   * Allocate all unallocated blocks to the given batch.
   * This event will get written to the write ahead log (if enabled).
   */
  def allocateBlocksToBatch(batchTime: Time): Unit = synchronized {
    val allocatedBlocks = AllocatedBlocks(streamIds.map { streamId =>
        (streamId, getReceivedBlockQueue(streamId).dequeueAll(x => true))
    }.toMap)
    writeToLog(BatchAllocationEvent(batchTime, allocatedBlocks))
    timeToAllocatedBlocks(batchTime) = allocatedBlocks
    allocatedBlocks
  }

  /** Get blocks that have been added but not yet allocated to any batch. */
  def getUnallocatedBlocks(streamId: Int): Seq[ReceivedBlockInfo] = synchronized {
    getReceivedBlockQueue(streamId).toSeq
  } 

  /** Get the blocks allocated to a batch, or allocate blocks to the batch and then get them. */
  def getBlocksOfBatch(batchTime: Time, streamId: Int): Seq[ReceivedBlockInfo] = synchronized {
    timeToAllocatedBlocks.get(batchTime).map { _.getBlockForStream(streamId) }.getOrElse(Seq.empty)
  }

  /** Check if any blocks are left to be allocated to batches. */
  def hasUnallocatedReceivedBlocks: Boolean = synchronized {
    !streamIdToUnallocatedBlockQueues.values.forall(_.isEmpty)
  }

  /** Clean up block information of old batches. */
  def cleanupOldBatches(cleanupThreshTime: Time): Unit = synchronized {
    assert(cleanupThreshTime.milliseconds < clock.currentTime())
    val timesToCleanup = timeToAllocatedBlocks.keys.filter { _ < cleanupThreshTime }.toSeq
    logInfo("Deleting batches " + timesToCleanup)
    writeToLog(BatchCleanupEvent(timesToCleanup))
    timeToAllocatedBlocks --= timesToCleanup
    logManagerOption.foreach(_.cleanupOldLogs(cleanupThreshTime.milliseconds))
    log
  }

  /** Stop the block tracker. */
  def stop() {
    logManagerOption.foreach { _.stop() }
  }


  /**
   * Recover all the tracker actions from the write ahead logs to recover the state (unallocated
   * and allocated block info) prior to failure.
   */
  private def recoverFromWriteAheadLogs(): Unit = synchronized {
    logInfo("Recovering from checkpoint")

    // Insert the recovered block information
    def insertAddedBlock(receivedBlockInfo: ReceivedBlockInfo) {
      logTrace(s"Recovery: Inserting added block $receivedBlockInfo")
      // println(s"Recovery: Inserting added block $receivedBlockInfo")
      getReceivedBlockQueue(receivedBlockInfo.streamId) += receivedBlockInfo
    }

    // Insert the recovered block-to-batch allocations and clear the queue of received blocks
    // (when the blocks were originally allocated to the batch, the queue must have been cleared).
    def insertAllocatedBatch(time: Time, allocatedBlocks: AllocatedBlocks) {
      logTrace(s"Recovery: Inserting allocated batch for time $time to " +
        s"${allocatedBlocks.streamIdToAllocatedBlocks}")
      // println(s"Recovery: Inserting allocated batch for time $time to " +
      // s"${allocatedBlocks.streamIdToAllocatedBlocks}")
      streamIdToUnallocatedBlockQueues.values.foreach { _.clear() }
      timeToAllocatedBlocks.put(time, allocatedBlocks)
    }

    // Cleanup the batch allocations
    def cleanupBatches(batchTimes: Seq[Time]) {
      logTrace(s"Recovery: Cleaning up batches $batchTimes")
      // println(s"Recovery: Cleaning up batches ${batchTimes}")
      timeToAllocatedBlocks --= batchTimes
    }

    logManagerOption.foreach { logManager =>
      logManager.readFromLog().foreach { byteBuffer =>
        logTrace("Recovering record " + byteBuffer)
        Utils.deserialize[ReceivedBlockTrackerLogEvent](byteBuffer.array) match {
          case BlockAdditionEvent(receivedBlockInfo) =>
            insertAddedBlock(receivedBlockInfo)
          case BatchAllocationEvent(time, allocatedBlocks) =>
            insertAllocatedBatch(time, allocatedBlocks)
          case BatchCleanupEvent(batchTimes) =>
            cleanupBatches(batchTimes)
        }
      }
    }
  }

  /** Write an update to the tracker to the write ahead log */
  private def writeToLog(record: ReceivedBlockTrackerLogEvent) {
    logDebug(s"Writing to log $record")
    logManagerOption.foreach { logManager =>
        logManager.writeToLog(ByteBuffer.wrap(Utils.serialize(record)))
    }
  }

  /** Get the queue of received blocks belonging to a particular stream */
  private def getReceivedBlockQueue(streamId: Int): ReceivedBlockQueue = {
    streamIdToUnallocatedBlockQueues.getOrElseUpdate(streamId, new ReceivedBlockQueue)
  }
}

private[streaming] object ReceivedBlockTracker {
  def checkpointDirToLogDir(checkpointDir: String): String = {
    new Path(checkpointDir, "receivedBlockMetadata").toString
  }
}
