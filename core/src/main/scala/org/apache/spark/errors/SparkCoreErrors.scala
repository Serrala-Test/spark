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

package org.apache.spark.errors

import java.io.{EOFException, File, IOException}
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeoutException

import org.apache.hadoop.fs.Path

import org.apache.spark.{SparkException, TaskNotSerializableException}
import org.apache.spark.scheduler.{BarrierJobRunWithDynamicAllocationException, BarrierJobSlotsNumberCheckFailed, BarrierJobUnsupportedRDDChainException}
import org.apache.spark.shuffle.{FetchFailedException, ShuffleManager}
import org.apache.spark.storage.{BlockId, BlockManagerId, BlockNotFoundException, BlockSavedOnDecommissionedBlockManagerException, RDDBlockId, UnrecognizedBlockId}
import org.apache.spark.util.ReturnStatementInClosureException

/**
 * Object for grouping error messages from (most) exceptions thrown during query execution.
 */
object SparkCoreErrors {
  def rddBlockNotFoundError(blockId: BlockId, id: Int): Throwable = {
    new Exception(s"Could not compute split, block $blockId of RDD $id not found")
  }

  def blockHaveBeenRemovedError(string: String): Throwable = {
    new SparkException(s"Attempted to use $string after its blocks have been removed!")
  }

  def histogramOnEmptyRDDOrContainingInfinityOrNaNError(): Throwable = {
    new UnsupportedOperationException(
      "Histogram on either an empty RDD or RDD containing +/-infinity or NaN")
  }

  def emptyRDDError(): Throwable = {
    new UnsupportedOperationException("empty RDD")
  }

  def pathNotSupportedError(path: String): Throwable = {
    new IOException(s"Path: ${path} is a directory, which is not supported by the " +
      "record reader when `mapreduce.input.fileinputformat.input.dir.recursive` is false.")
  }

  def checkpointRDDBlockIdNotFoundError(rddBlockId: RDDBlockId): Throwable = {
    new SparkException(
      s"""
         |Checkpoint block $rddBlockId not found! Either the executor
         |that originally checkpointed this partition is no longer alive, or the original RDD is
         |unpersisted. If this problem persists, you may consider using `rdd.checkpoint()`
         |instead, which is slower than local checkpointing but more fault-tolerant.
       """.stripMargin.replaceAll("\n", " "))
  }

  def cannotUseMapSideCombiningWithArrayKeyError(): Throwable = {
    new SparkException("Cannot use map-side combining with array keys.")
  }

  def hashPartitionerCannotPartitionArrayKeyError(): Throwable = {
    new SparkException("HashPartitioner cannot partition array keys.")
  }

  def reduceByKeyLocallyNotSupportArrayKeysError(): Throwable = {
    new SparkException("reduceByKeyLocally() does not support array keys")
  }

  def noSuchElementException(): Throwable = {
    new NoSuchElementException()
  }

  def rddLacksSparkContextError(): Throwable = {
    new SparkException("This RDD lacks a SparkContext. It could happen in the following cases: " +
      "\n(1) RDD transformations and actions are NOT invoked by the driver, but inside of other " +
      "transformations; for example, rdd1.map(x => rdd2.values.count() * x) is invalid " +
      "because the values transformation and count action cannot be performed inside of the " +
      "rdd1.map transformation. For more information, see SPARK-5063.\n(2) When a Spark " +
      "Streaming job recovers from checkpoint, this exception will be hit if a reference to " +
      "an RDD not defined by the streaming job is used in DStream operations. For more " +
      "information, See SPARK-13758.")
  }

  def cannotChangeStorageLevelError(): Throwable = {
    new UnsupportedOperationException(
      "Cannot change storage level of an RDD after it was already assigned a level")
  }

  def canOnlyZipRDDsWithSamePartitionSizeError(): Throwable = {
    new SparkException("Can only zip RDDs with same number of elements in each partition")
  }

  def emptyCollectionError(): Throwable = {
    new UnsupportedOperationException("empty collection")
  }

  def countByValueApproxNotSupportArraysError(): Throwable = {
    new SparkException("countByValueApprox() does not support arrays")
  }

  def checkpointDirectoryHasNotBeenSetInSparkContextError(): Throwable = {
    new SparkException("Checkpoint directory has not been set in the SparkContext")
  }

  def invalidCheckpointFileError(path: Path): Throwable = {
    new SparkException(s"Invalid checkpoint file: $path")
  }

  def failToCreateCheckpointPathError(checkpointDirPath: Path): Throwable = {
    new SparkException(s"Failed to create checkpoint path $checkpointDirPath")
  }

  def checkpointRDDHasDifferentNumberOfPartitionsFromOriginalRDDError(
      originalRDDId: Int,
      originalRDDLength: Int,
      newRDDId: Int,
      newRDDLength: Int): Throwable = {
    new SparkException(
      s"""
         |Checkpoint RDD has a different number of partitions from original RDD. Original
         |RDD [ID: $originalRDDId, num of partitions: $originalRDDLength];
         |Checkpoint RDD [ID: $newRDDId, num of partitions: $newRDDLength].
       """.stripMargin.replaceAll("\n", " "))
  }

  def checkpointFailedToSaveError(task: Int, path: Path): Throwable = {
    new IOException("Checkpoint failed: failed to save output of task: " +
      s"$task and final output path does not exist: $path")
  }

  def mustSpecifyCheckpointDirError(): Throwable = {
    new SparkException("Checkpoint dir must be specified.")
  }

  def endOfIteratorError(): Throwable = {
    new NoSuchElementException("End of iterator")
  }

  def medianHeapIsEmptyError(): Throwable = {
    new NoSuchElementException("MedianHeap is empty.")
  }

  def indexOutOfBoundsError(): Throwable = {
    new IndexOutOfBoundsException
  }

  def unsupportedOperationError(message: String): Throwable = {
    new UnsupportedOperationException(message)
  }

  def bufferMoreThanArrayMaxElementsError(arrayMax: Int): Throwable = {
    new UnsupportedOperationException(s"Can't grow buffer past $arrayMax elements")
  }

  def shouldNotReachHereError(): Throwable = {
    new RuntimeException("Should never reach here.")
  }

  def bufferSizeExceedsMaximumArraySizeError(size: Long): Throwable = {
    new UnsupportedOperationException(
      s"cannot call toArray because buffer size ($size bytes) exceeds maximum array size")
  }

  def rootDirDoesNotExistError(rootDir: String): Throwable = {
    new RuntimeException(s"${rootDir} does not exist." +
      s" Please create this dir in order to persist driver logs")
  }

  def cloneFunctionIsNotImplementedError(): Throwable = {
    new UnsupportedOperationException("clone() is not implemented.")
  }

  def accumulatorNotRegisteredError(): Throwable = {
    new UnsupportedOperationException("Accumulator must be registered before send to executor")
  }

  def cannotMergeError(left: String, right: String): Throwable = {
    new UnsupportedOperationException(s"Cannot merge $left with $right")
  }

  def taskNotSerializableError(ex: Exception): Throwable = {
    new SparkException("Task not serializable", ex)
  }

  def returnStatementInClosureError(): Throwable = {
    new ReturnStatementInClosureException
  }

  def cannotResolveToMultipleFilesError(base: String, resolved: String): Throwable = {
    new SparkException(s"$base resolves ambiguously to multiple files: $resolved")
  }

  def keyIsNullError(): Throwable = {
    new NullPointerException("key must not be null")
  }

  def endOfStreamError(): Throwable = {
    new NoSuchElementException("End of stream")
  }

  def endOfFileError(): Throwable = {
    new EOFException("End of file before fully reading buffer")
  }

  def cannotWriteBufferToOutputSteamError(): Throwable = {
    new IOException("Could not fully write buffer to output stream")
  }

  def executorAlreadyShutdownError(): Throwable = {
    new RejectedExecutionException("Executor already shutdown")
  }

  def exceptionThrownInAwaitResultError(t: Throwable): Throwable = {
    new SparkException("Exception thrown in awaitResult: ", t)
  }

  def failToCreateTempDirectoryError(root: String, maxAttempts: Int): Throwable = {
    new IOException("Failed to create a temp directory (under " + root + ") after " +
      maxAttempts + " attempts!")
  }

  def failToDeleteFileError(
      destFilePath: String,
      sourceFilePath: String): Throwable = {
    new SparkException(s"Failed to delete $destFilePath while attempting to " +
      s"overwrite it with $sourceFilePath")
  }

  def fileExistAndNotMatchContentsError(destFile: File, url: String): Throwable = {
    new SparkException(s"File $destFile exists and does not match contents of $url")
  }

  def failToCreateDirectoryError(dest: String): Throwable = {
    new IOException(s"Failed to create directory $dest")
  }

  def failToGetTempDirectoryError(dir: String): Throwable = {
    new IOException(s"Failed to get a temp directory under [$dir].")
  }

  def yarnLocalDirsCannotBeEmptyError(): Throwable = {
    new Exception("Yarn Local dirs can't be empty")
  }

  def processExitedError(command: Seq[String], exitCode: Int): Throwable = {
    new SparkException(s"Process $command exited with code $exitCode")
  }

  def ioError(e: Throwable): Throwable = {
    new IOException(e)
  }

  def sourceMustBeAbsoluteError(): Throwable = {
    new IOException("Source must be absolute")
  }

  def destinationMustBeRelativeError(): Throwable = {
    new IOException("Destination must be relative")
  }

  def failToLoadSparkPropertiesError(filename: String, e: Throwable): Throwable = {
    new SparkException(s"Failed when loading Spark properties from $filename", e)
  }

  def failToStartServiceOnPortError(serviceString: String, startPort: Int): Throwable = {
    new SparkException(s"Failed to start service$serviceString on port $startPort")
  }

  def invalidMasterURLError(sparkUrl: String, e: Throwable = null): Throwable = {
    new SparkException(s"Invalid master URL: $sparkUrl", e)
  }

  def missingZeroArgumentConstructorOrSingleArgumentConstructorError(name: String): Throwable = {
    new SparkException(
      s"""$name did not have a zero-argument constructor or a
         |single-argument constructor that accepts SparkConf. Note: if the class is
         |defined inside of another Scala class, then its constructors may accept an
         |implicit parameter that references the enclosing class; in this case, you must
         |define the class as a top-level class in order to prevent this extra
         |parameter from breaking Spark's ability to find a valid constructor.
       """.stripMargin.replaceAll("\n", " "))
  }

  def askStandaloneSchedulerToShutDownExecutorsError(e: Exception): Throwable = {
    new SparkException("Error asking standalone scheduler to shut down executors", e)
  }

  def stopStandaloneSchedulerDriverEndpointError(e: Exception): Throwable = {
    new SparkException("Error stopping standalone scheduler's driver endpoint", e)
  }

  def noExecutorIdleError(id: String): Throwable = {
    new NoSuchElementException(id)
  }

  def barrierStageWithRDDChainPatternError(): Throwable = {
    new BarrierJobUnsupportedRDDChainException
  }

  def barrierStageWithDynamicAllocationError(): Throwable = {
    new BarrierJobRunWithDynamicAllocationException
  }

  def numPartitionsGreaterThanMaxNumConcurrentTasksError(
      numPartitions: Int,
      maxNumConcurrentTasks: Int): Throwable = {
    new BarrierJobSlotsNumberCheckFailed(numPartitions, maxNumConcurrentTasks)
  }

  def cannotRunSubmitMapStageOnZeroPartitionRDDError(): Throwable = {
    new SparkException("Can't run submitMapStage on RDD with 0 partitions")
  }

  def accessNonExistentAccumulatorError(id: Long): Throwable = {
    new SparkException(s"attempted to access non-existent accumulator $id")
  }

  def sendResubmittedTaskStatusForShuffleMapStagesOnlyError(): Throwable = {
    new SparkException("TaskSetManagers should only send Resubmitted task " +
      "statuses for tasks in ShuffleMapStages.")
  }

  def nonEmptyEventQueueAfterTimeoutError(timeoutMillis: Long): Throwable = {
    new TimeoutException(s"The event queue is not empty after $timeoutMillis ms.")
  }

  def durationCalledOnUnfinishedTaskError(): Throwable = {
    new UnsupportedOperationException("duration() called on unfinished task")
  }

  def unrecognizedSchedulerModePropertyError(
      schedulerModeProperty: String,
      schedulingModeConf: String): Throwable = {
    new SparkException(s"Unrecognized $schedulerModeProperty: $schedulingModeConf")
  }

  def failResourceOffersForBarrierStageError(errorMsg: String): Throwable = {
    new SparkException(errorMsg)
  }

  def markExecutorAsFailedError(errorMsg: String): Throwable = {
    new SparkException(errorMsg)
  }

  def clusterSchedulerError(message: String): Throwable = {
    new SparkException(s"Exiting due to error from cluster scheduler: $message")
  }

  def failToSerializeTaskError(e: Throwable): Throwable = {
    new TaskNotSerializableException(e)
  }

  def unrecognizedBlockIdError(name: String): Throwable = {
    new UnrecognizedBlockId(name)
  }

  def taskHasNotLockedBlockError(currentTaskAttemptId: Long, blockId: BlockId): Throwable = {
    new SparkException(s"Task $currentTaskAttemptId has not locked block $blockId for writing")
  }

  def blockDoesNotExistError(blockId: BlockId): Throwable = {
    new SparkException(s"Block $blockId does not exist")
  }

  def cannotSaveBlockOnDecommissionedExecutorError(blockId: BlockId): Throwable = {
    new BlockSavedOnDecommissionedBlockManagerException(blockId)
  }

  def waitingForReplicationToFinishError(e: Throwable): Throwable = {
    new SparkException("Error occurred while waiting for replication to finish", e)
  }

  def unableToRegisterWithExternalShuffleServerError(e: Throwable): Throwable = {
    new SparkException(s"Unable to register with external shuffle server due to : ${e.getMessage}",
      e)
  }

  def waitingForAsyncReregistrationError(e: Throwable): Throwable = {
    new SparkException("Error occurred while waiting for async. reregistration", e)
  }

  def unexpectedShuffleBlockWithUnsupportedResolverError(
      shuffleManager: ShuffleManager,
      blockId: BlockId): Throwable = {
    new SparkException(s"Unexpected shuffle block ${blockId} with unsupported shuffle " +
      s"resolver ${shuffleManager.shuffleBlockResolver}")
  }

  def failToStoreBlockOnBlockManagerError(
      blockManagerId: BlockManagerId,
      blockId: BlockId): Throwable = {
    new SparkException(s"Failure while trying to store block $blockId on $blockManagerId.")
  }

  def readLockedBlockNotFoundError(blockId: BlockId): Throwable = {
    new SparkException(s"Block $blockId was not found even though it's read-locked")
  }

  def failToGetBlockWithLockError(blockId: BlockId): Throwable = {
    new SparkException(s"get() failed for block $blockId even though we held a lock")
  }

  def blockNotFoundError(blockId: BlockId): Throwable = {
    new BlockNotFoundException(blockId.toString)
  }

  def interruptedError(): Throwable = {
    new InterruptedException()
  }

  def blockStatusQueryReturnedNullError(blockId: BlockId): Throwable = {
    new SparkException(s"BlockManager returned null for BlockStatus query: $blockId")
  }

  def unexpectedBlockManagerMasterEndpointResultError(): Throwable = {
    new SparkException("BlockManagerMasterEndpoint returned false, expected true.")
  }

  def failToCreateDirectoryError(path: String, maxAttempts: Int): Throwable = {
    new IOException(
      s"Failed to create directory ${path} with permission 770 after $maxAttempts attempts!")
  }

  def unsupportedOperationError(): Throwable = {
    new UnsupportedOperationException()
  }

  def noSuchElementError(): Throwable = {
    new NoSuchElementException()
  }

  def fetchFailedError(
      bmAddress: BlockManagerId,
      shuffleId: Int,
      mapId: Long,
      mapIndex: Int,
      reduceId: Int,
      message: String,
      cause: Throwable = null): Throwable = {
    new FetchFailedException(bmAddress, shuffleId, mapId, mapIndex, reduceId, message, cause)
  }

  def failToGetNonShuffleBlockError(blockId: BlockId, e: Throwable): Throwable = {
    new SparkException(s"Failed to get block $blockId, which is not a shuffle block", e)
  }
}
