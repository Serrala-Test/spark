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

package org.apache.spark.memory

import scala.collection.mutable

import org.apache.spark.SparkConf
import org.apache.spark.internal.Logging
import org.apache.spark.storage.BlockId
import org.apache.spark.storage.memory.MemoryStore
import org.apache.spark.unsafe.Platform
import org.apache.spark.unsafe.array.ByteArrayMethods
import org.apache.spark.unsafe.memory.MemoryAllocator


/**
 * An abstract memory manager that enforces how memory is shared between execution and storage.
 *
 * In this context, execution memory refers to that used for computation in shuffles, joins,
 * sorts and aggregations, while storage memory refers to that used for caching and propagating
 * internal data across the cluster. There exists one MemoryManager per JVM.
 */
private[spark] abstract class MemoryManager(
    conf: SparkConf,
    numCores: Int,
    val totalHeapMemory: Long,
    val totalOffHeapMemory: Long) extends Logging {

  // -- Methods related to memory allocation policies and bookkeeping ------------------------------

  // Access to all of these fields is guarded by synchronizing on `this`:

  protected var freeHeapMemory: Long = totalHeapMemory
  protected var freeOffHeapMemory: Long = totalOffHeapMemory

  protected var heapStorageMemoryUsed: Long = 0L
  protected var offHeapStorageMemoryUsed: Long = 0L

  protected var heapExecutionMemoryUsedForTask = new mutable.HashMap[Long, Long]()
  protected var offHeapExecutionMemoryUsedForTask = new mutable.HashMap[Long, Long]()

  final def heapExecutionMemoryUsed: Long = synchronized {
    heapExecutionMemoryUsedForTask.values.sum
  }

  final def offHeapExecutionMemoryUsed: Long = synchronized {
    offHeapExecutionMemoryUsedForTask.values.sum
  }

  protected val maxHeapExecutionMemory: Long
  protected val maxOffHeapExecutionMemory: Long
  val maxHeapStorageMemory: Long
  protected val maxOffHeapStorageMemory: Long
  protected val unevictableHeapStorageMemory: Long
  protected val unevictableOffHeapStorageMemory: Long

  private def assertInvariants(): Unit = {
    assert(freeHeapMemory >= 0)
    assert(freeHeapMemory <= totalHeapMemory)
    assert(freeOffHeapMemory >= 0)
    assert(freeOffHeapMemory <= totalOffHeapMemory,
      s"freeOffHeapMemory=$freeOffHeapMemory > totalHeapMemory=$totalHeapMemory")
    assert(
      heapStorageMemoryUsed + heapExecutionMemoryUsed
        == totalHeapMemory - freeHeapMemory, "heap memory was not conserved")
    assert(
      offHeapStorageMemoryUsed + offHeapExecutionMemoryUsed
        == totalOffHeapMemory - freeOffHeapMemory, "off-heap memory was not conserved")
  }

  // Initial invariants:
  assert(maxHeapExecutionMemory <= totalHeapMemory, s"$maxHeapExecutionMemory, $totalHeapMemory")
  assert(maxHeapStorageMemory <= totalHeapMemory)
  assert(maxOffHeapExecutionMemory <= totalOffHeapMemory)
  assert(maxOffHeapStorageMemory <= totalOffHeapMemory)
  assert(unevictableHeapStorageMemory <= maxHeapStorageMemory)
  assert(unevictableOffHeapStorageMemory <= maxOffHeapStorageMemory)
  assertInvariants()

  private var _memoryStore: MemoryStore = _
  def memoryStore: MemoryStore = {
    if (_memoryStore == null) {
      throw new IllegalStateException("memory store not initialized yet")
    }
    _memoryStore
  }

  /**
   * Set the [[MemoryStore]] used by this manager to evict cached blocks.
   * This must be set after construction due to initialization ordering constraints.
   */
  final def setMemoryStore(store: MemoryStore): Unit = synchronized {
    _memoryStore = store
  }

  /**
   * Acquire N bytes of memory to cache the given block, evicting existing ones if necessary.
   *
   * @return whether all N bytes were successfully granted.
   */
  def acquireStorageMemory(
      blockId: BlockId,
      numBytes: Long,
      memoryMode: MemoryMode,
      maxBytesToAttemptToFreeViaEviction: Long = Long.MaxValue): Boolean = synchronized {
    assert(numBytes >= 0)
    assertInvariants()

    val maxStorageMemory = memoryMode match {
      case MemoryMode.ON_HEAP => maxHeapStorageMemory
      case MemoryMode.OFF_HEAP => maxOffHeapStorageMemory
    }

    if (numBytes > maxStorageMemory) {
      return false
    }

    def freeMemory = memoryMode match {
      case MemoryMode.ON_HEAP =>
        math.min(freeHeapMemory, maxHeapStorageMemory - heapStorageMemoryUsed)
      case MemoryMode.OFF_HEAP =>
        math.min(freeOffHeapMemory, maxOffHeapStorageMemory - offHeapStorageMemoryUsed)
    }

    def acquire(size: Long) = memoryMode match {
      case MemoryMode.ON_HEAP =>
        freeHeapMemory -= size
        heapStorageMemoryUsed += size
      case MemoryMode.OFF_HEAP =>
        freeOffHeapMemory -= size
        offHeapStorageMemoryUsed += size
    }

    // First, attempt to fulfill as much of the request as possible using free memory
    val freeMemoryAcquired = {
      val acquired = math.min(numBytes, freeMemory)
      acquire(acquired)
      acquired
    }
    assertInvariants()
    // Next, acquire the remaining memory by evicting blocks
    var success: Boolean = false
    try {
      val extraMemoryNeeded = numBytes - freeMemoryAcquired
      // TODO: Once we support off-heap caching, this will need to change:
      if (extraMemoryNeeded > 0
          && maxBytesToAttemptToFreeViaEviction > 0
          && memoryMode == MemoryMode.ON_HEAP) {
        memoryStore.evictBlocksToFreeSpace(
          Some(blockId),
          math.min(extraMemoryNeeded, maxBytesToAttemptToFreeViaEviction))
      }
      // NOTE: If the memory store evicts blocks, then those evictions will synchronously call
      // back into this StorageMemoryPool in order to free memory, so the free memory counters
      // should have been updated.
      success = extraMemoryNeeded == 0 || freeMemory >= extraMemoryNeeded
      if (extraMemoryNeeded > 0 && freeMemory >= extraMemoryNeeded) {
        acquire(extraMemoryNeeded)
      }
    } finally {
      if (!success) {
        releaseStorageMemory(freeMemoryAcquired, memoryMode)
      }
    }
    assertInvariants()
    success
  }

  /**
   * Acquire N bytes of memory to unroll the given block, evicting existing ones if necessary.
   *
   * This extra method allows subclasses to differentiate behavior between acquiring storage
   * memory and acquiring unroll memory. For instance, the memory management model in Spark
   * 1.5 and before places a limit on the amount of space that can be freed from unrolling.
   *
   * @return whether all N bytes were successfully granted.
   */
  def acquireUnrollMemory(blockId: BlockId, numBytes: Long, memoryMode: MemoryMode): Boolean

  /**
   * Try to acquire up to `numBytes` of execution memory for the current task and return the
   * number of bytes obtained, or 0 if none can be allocated.
   *
   * This call may block until there is enough free memory in some situations, to make sure each
   * task has a chance to ramp up to at least 1 / 2N of the total memory pool (where N is the # of
   * active tasks) before it is forced to spill. This can happen if the number of tasks increase
   * but an older task had a lot of memory already.
   *
   * Because N varies dynamically, we keep track of the set of active tasks and redo the
   * calculations of 1 / 2N and 1 / N in waiting tasks whenever this set changes. This is all don
   * by synchronizing access to mutable state and using wait() and notifyAll() to signal changes to
   * callers. Prior to Spark 1.6, this arbitration of memory across tasks was performed by the
   * ShuffleMemoryManager.
   *
   * @return the number of bytes granted to the task.
   */
  private[memory]
  def acquireExecutionMemory(
      numBytes: Long,
      taskAttemptId: Long,
      memoryMode: MemoryMode): Long = synchronized {
    require(numBytes > 0, s"invalid number of bytes requested: $numBytes")
    assertInvariants()

    val poolName = memoryMode match {
      case MemoryMode.ON_HEAP => "execution memory"
      case MemoryMode.OFF_HEAP => "off-heap execution memory"
    }

    val executionMemoryForTask = memoryMode match {
      case MemoryMode.ON_HEAP => heapExecutionMemoryUsedForTask
      case MemoryMode.OFF_HEAP => offHeapExecutionMemoryUsedForTask
    }

    val totalMemory = memoryMode match {
      case MemoryMode.ON_HEAP => totalHeapMemory
      case MemoryMode.OFF_HEAP => totalOffHeapMemory
    }

    def memoryFree = memoryMode match {
      case MemoryMode.ON_HEAP =>
        math.min(freeHeapMemory, maxHeapExecutionMemory - heapExecutionMemoryUsed)
      case MemoryMode.OFF_HEAP =>
        math.min(freeOffHeapMemory, maxOffHeapExecutionMemory - offHeapExecutionMemoryUsed)
    }

    def maxExecutionMemory = memoryMode match {
      case MemoryMode.ON_HEAP => maxHeapExecutionMemory
      case MemoryMode.OFF_HEAP => maxOffHeapExecutionMemory
    }

    def totalExecutionMemoryUsed = executionMemoryForTask.values.sum

    def unevictableStorageMemory = memoryMode match {
      case MemoryMode.ON_HEAP =>
        math.min(heapStorageMemoryUsed, unevictableHeapStorageMemory)
      case MemoryMode.OFF_HEAP =>
        math.min(offHeapStorageMemoryUsed, unevictableOffHeapStorageMemory)
    }

    def evictableStorageMemory = memoryMode match {
      case MemoryMode.ON_HEAP =>
        math.max(0, heapStorageMemoryUsed - unevictableHeapStorageMemory)
      case MemoryMode.OFF_HEAP =>
        math.max(0, offHeapStorageMemoryUsed - unevictableOffHeapStorageMemory)
    }

    // Add this task to the taskMemory map just so we can keep an accurate count of the number
    // of active tasks, to let other tasks ramp down their memory in calls to `acquireMemory`
    if (!executionMemoryForTask.contains(taskAttemptId)) {
      executionMemoryForTask(taskAttemptId) = 0L
      // This will later cause waiting tasks to wake up and check numTasks again
      notifyAll()
    }

    // Keep looping until we're either sure that we don't want to grant this request (because this
    // task would have more than 1 / numActiveTasks of the memory) or we have enough free
    // memory to give it (we always let each task get at least 1 / (2 * numActiveTasks)).
    // TODO: simplify this to limit each task to its own slot
    while (true) {
      val numActiveTasks = executionMemoryForTask.keys.size
      val curMem = executionMemoryForTask(taskAttemptId)

      // If there is not enough free memory, see whether we can reclaim some by evicting cached
      // blocks:
      if (numBytes > memoryFree && numBytes - memoryFree <= evictableStorageMemory) {
        val extraMemoryNeeded = numBytes - memoryFree
        val spaceFreedByEviction = {
          // Once we support off-heap caching, this will need to change:
          if (memoryMode == MemoryMode.ON_HEAP) {
            memoryStore.evictBlocksToFreeSpace(None, extraMemoryNeeded)
          } else {
            0
          }
        }
        // When a block is released, BlockManager.dropFromMemory() calls releaseMemory()
        // so we do not need to update the memory bookkeeping structures here.
        if (spaceFreedByEviction >= extraMemoryNeeded) {
          assert(numBytes <= memoryFree)
        }
      }

      // Maximum amount of execution memory that can be used.
      // This is used to compute the upper bound of how much memory each task can occupy.
      val maxPoolSize = math.min(maxExecutionMemory, totalMemory - unevictableStorageMemory)
      val maxMemoryPerTask = maxPoolSize / numActiveTasks
      val minMemoryPerTask = totalExecutionMemoryUsed / (2 * numActiveTasks)

      // How much we can grant this task; keep its share within 0 <= X <= 1 / numActiveTasks
      val maxToGrant = math.min(numBytes, math.max(0, maxMemoryPerTask - curMem))
      // Only give it as much memory as is free, which might be none if it reached 1 / numTasks
      val toGrant = math.min(maxToGrant, memoryFree)

      // We want to let each task get at least 1 / (2 * numActiveTasks) before blocking;
      // if we can't give it this much now, wait for other tasks to free up memory
      // (this happens if older tasks allocated lots of memory before N grew)
      if (toGrant < numBytes && curMem + toGrant < minMemoryPerTask) {
        logInfo(s"TID $taskAttemptId waiting for at least 1/2N of $poolName to be free")
        wait()
      } else {
        memoryMode match {
          case MemoryMode.ON_HEAP => freeHeapMemory -= toGrant
          case MemoryMode.OFF_HEAP => freeOffHeapMemory -= toGrant
        }
        executionMemoryForTask(taskAttemptId) += toGrant
        assertInvariants()
        return toGrant
      }
    }
    sys.error("internal error: unreachable state")
  }

  /**
   * Release numBytes of execution memory belonging to the given task.
   */
  private[memory]
  def releaseExecutionMemory(
      numBytes: Long,
      taskAttemptId: Long,
      memoryMode: MemoryMode): Unit = synchronized {
    assertInvariants()
    memoryMode match {
      case MemoryMode.ON_HEAP =>
        val curMem = heapExecutionMemoryUsedForTask.getOrElse(taskAttemptId, 0L)
        require(numBytes <= curMem,
          s"Release called on $numBytes bytes but task only has $curMem bytes " +
            s"of execution memory")
        if (heapExecutionMemoryUsedForTask.contains(taskAttemptId)) {
          heapExecutionMemoryUsedForTask(taskAttemptId) -= numBytes
          if (heapExecutionMemoryUsedForTask(taskAttemptId) <= 0) {
            heapExecutionMemoryUsedForTask.remove(taskAttemptId)
          }
        }
        freeHeapMemory += numBytes
      case MemoryMode.OFF_HEAP =>
        val curMem = offHeapExecutionMemoryUsedForTask.getOrElse(taskAttemptId, 0L)
        require(numBytes <= curMem,
          s"Release called on $numBytes bytes but task only has $curMem bytes " +
            s"of off-heap execution memory")
        if (offHeapExecutionMemoryUsedForTask.contains(taskAttemptId)) {
          offHeapExecutionMemoryUsedForTask(taskAttemptId) -= numBytes
          if (offHeapExecutionMemoryUsedForTask(taskAttemptId) <= 0) {
            offHeapExecutionMemoryUsedForTask.remove(taskAttemptId)
          }
        }
        freeOffHeapMemory += numBytes
    }
    notifyAll() // Notify waiters in acquireExecutionMemory() that memory has been freed
    assertInvariants()
  }

  /**
   * Release all memory for the given task and mark it as inactive (e.g. when a task ends).
   *
   * @return the number of bytes freed.
   */
  private[memory] def releaseAllExecutionMemoryForTask(taskAttemptId: Long): Long = synchronized {
    assertInvariants()
    val heapUsage = heapExecutionMemoryUsedForTask.getOrElse(taskAttemptId, 0L)
    val offHeapUsage = offHeapExecutionMemoryUsedForTask.getOrElse(taskAttemptId, 0L)
    releaseExecutionMemory(heapUsage, taskAttemptId, MemoryMode.ON_HEAP)
    releaseExecutionMemory(offHeapUsage, taskAttemptId, MemoryMode.OFF_HEAP)
    assertInvariants()
    heapUsage + offHeapUsage
  }

  /**
   * Release N bytes of storage memory.
   */
  def releaseStorageMemory(numBytes: Long, memoryMode: MemoryMode): Unit = synchronized {
    assertInvariants()
    memoryMode match {
      case MemoryMode.ON_HEAP =>
        require(numBytes <= heapStorageMemoryUsed,
          s"Attempted to release $numBytes bytes of storage " +
            s"memory when we only have $heapStorageMemoryUsed bytes")
        heapStorageMemoryUsed -= numBytes
        freeHeapMemory += numBytes
      case MemoryMode.OFF_HEAP =>
        require(numBytes <= offHeapStorageMemoryUsed,
          s"Attempted to release $numBytes bytes of off-heap storage " +
            s"memory when we only have $offHeapStorageMemoryUsed bytes")
        offHeapStorageMemoryUsed -= numBytes
        freeOffHeapMemory += numBytes
    }
    assertInvariants()
  }

  /**
   * Release all storage memory acquired.
   */
  final def releaseAllStorageMemory(): Unit = synchronized {
    assertInvariants()
    freeHeapMemory += heapStorageMemoryUsed
    heapStorageMemoryUsed = 0
    freeOffHeapMemory += offHeapStorageMemoryUsed
    offHeapStorageMemoryUsed = 0
    assertInvariants()
  }

  /**
   * Release N bytes of unroll memory.
   */
  final def releaseUnrollMemory(numBytes: Long, memoryMode: MemoryMode): Unit = synchronized {
    releaseStorageMemory(numBytes, memoryMode)
  }

  /**
   * Execution memory currently in use, in bytes.
   */
  final def executionMemoryUsed: Long = synchronized {
    heapExecutionMemoryUsed + offHeapStorageMemoryUsed
  }

  /**
   * Storage memory currently in use, in bytes.
   */
  final def storageMemoryUsed: Long = synchronized {
    heapStorageMemoryUsed + offHeapStorageMemoryUsed
  }

  /**
   * Returns the execution memory consumption, in bytes, for the given task.
   */
  private[memory] def getExecutionMemoryUsageForTask(taskAttemptId: Long): Long = synchronized {
    val heapUsage = heapExecutionMemoryUsedForTask.getOrElse(taskAttemptId, 0L)
    val offHeapUsage = offHeapExecutionMemoryUsedForTask.getOrElse(taskAttemptId, 0L)
    heapUsage + offHeapUsage
  }

  // -- Fields related to Tungsten managed memory -------------------------------------------------

  /**
   * Tracks whether Tungsten memory will be allocated on the JVM heap or off-heap using
   * sun.misc.Unsafe.
   */
  final val tungstenMemoryMode: MemoryMode = {
    if (conf.getBoolean("spark.memory.offHeap.enabled", false)) {
      require(conf.getSizeAsBytes("spark.memory.offHeap.size", 0) > 0,
        "spark.memory.offHeap.size must be > 0 when spark.memory.offHeap.enabled == true")
      require(Platform.unaligned(),
        "No support for unaligned Unsafe. Set spark.memory.offHeap.enabled to false.")
      MemoryMode.OFF_HEAP
    } else {
      MemoryMode.ON_HEAP
    }
  }

  /**
   * The default page size, in bytes.
   *
   * If user didn't explicitly set "spark.buffer.pageSize", we figure out the default value
   * by looking at the number of cores available to the process, and the total amount of memory,
   * and then divide it by a factor of safety.
   */
  val pageSizeBytes: Long = {
    val minPageSize = 1L * 1024 * 1024   // 1MB
    val maxPageSize = 64L * minPageSize  // 64MB
    val cores = if (numCores > 0) numCores else Runtime.getRuntime.availableProcessors()
    // Because of rounding to next power of 2, we may have safetyFactor as 8 in worst case
    val safetyFactor = 16
    val maxTungstenMemory: Long = tungstenMemoryMode match {
      case MemoryMode.ON_HEAP => maxHeapExecutionMemory
      case MemoryMode.OFF_HEAP => maxOffHeapExecutionMemory
    }
    val size = ByteArrayMethods.nextPowerOf2(maxTungstenMemory / cores / safetyFactor)
    val default = math.min(maxPageSize, math.max(minPageSize, size))
    conf.getSizeAsBytes("spark.buffer.pageSize", default)
  }

  /**
   * Allocates memory for use by Unsafe/Tungsten code.
   */
  private[memory] final val tungstenMemoryAllocator: MemoryAllocator = {
    tungstenMemoryMode match {
      case MemoryMode.ON_HEAP => MemoryAllocator.HEAP
      case MemoryMode.OFF_HEAP => MemoryAllocator.UNSAFE
    }
  }
}
