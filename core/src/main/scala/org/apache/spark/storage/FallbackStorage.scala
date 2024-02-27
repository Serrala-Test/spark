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

package org.apache.spark.storage

import java.io.DataInputStream
import java.nio.ByteBuffer
import java.util.concurrent.ThreadPoolExecutor

import scala.concurrent.Future
import scala.reflect.ClassTag

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}

import org.apache.spark.{SparkConf, SparkException}
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config.{STORAGE_DECOMMISSION_FALLBACK_STORAGE_CLEANUP, STORAGE_DECOMMISSION_FALLBACK_STORAGE_PATH, STORAGE_FALLBACK_STORAGE_NUM_THREADS_FOR_SHUFFLE_READ}
import org.apache.spark.network.buffer.{ManagedBuffer, NioManagedBuffer}
import org.apache.spark.network.shuffle.BlockFetchingListener
import org.apache.spark.network.util.JavaUtils
import org.apache.spark.rpc.{RpcAddress, RpcEndpointRef, RpcTimeout}
import org.apache.spark.shuffle.{IndexShuffleBlockResolver, ShuffleBlockInfo}
import org.apache.spark.shuffle.IndexShuffleBlockResolver.NOOP_REDUCE_ID
import org.apache.spark.storage.ShuffleBlockFetcherIterator.FetchBlockInfo
import org.apache.spark.util.{ThreadUtils, Utils}

/**
 * A fallback storage used by storage decommissioners.
 */
private[storage] class FallbackStorage(conf: SparkConf) extends Logging {
  require(conf.contains("spark.app.id"))
  require(conf.get(STORAGE_DECOMMISSION_FALLBACK_STORAGE_PATH).isDefined)

  private val fallbackPath = new Path(conf.get(STORAGE_DECOMMISSION_FALLBACK_STORAGE_PATH).get)
  private val hadoopConf = SparkHadoopUtil.get.newConfiguration(conf)
  private val fallbackFileSystem = FileSystem.get(fallbackPath.toUri, hadoopConf)
  private val appId = conf.getAppId

  // Visible for testing
  def copy(
      shuffleBlockInfo: ShuffleBlockInfo,
      bm: BlockManager): Unit = {
    val shuffleId = shuffleBlockInfo.shuffleId
    val mapId = shuffleBlockInfo.mapId

    bm.migratableResolver match {
      case r: IndexShuffleBlockResolver =>
        val indexFile = r.getIndexFile(shuffleId, mapId)

        if (indexFile.exists()) {
          val hash = JavaUtils.nonNegativeHash(indexFile.getName)
          fallbackFileSystem.copyFromLocalFile(
            new Path(Utils.resolveURI(indexFile.getAbsolutePath)),
            new Path(fallbackPath, s"$appId/$shuffleId/$hash/${indexFile.getName}"))

          val dataFile = r.getDataFile(shuffleId, mapId)
          if (dataFile.exists()) {
            val hash = JavaUtils.nonNegativeHash(dataFile.getName)
            fallbackFileSystem.copyFromLocalFile(
              new Path(Utils.resolveURI(dataFile.getAbsolutePath)),
              new Path(fallbackPath, s"$appId/$shuffleId/$hash/${dataFile.getName}"))
          }

          // Report block statuses
          val reduceId = NOOP_REDUCE_ID
          val indexBlockId = ShuffleIndexBlockId(shuffleId, mapId, reduceId)
          FallbackStorage.reportBlockStatus(bm, indexBlockId, indexFile.length)
          if (dataFile.exists) {
            val dataBlockId = ShuffleDataBlockId(shuffleId, mapId, reduceId)
            FallbackStorage.reportBlockStatus(bm, dataBlockId, dataFile.length)
          }
        }
      case r =>
        logWarning(s"Unsupported Resolver: ${r.getClass.getName}")
    }
  }

  def exists(shuffleId: Int, filename: String): Boolean = {
    val hash = JavaUtils.nonNegativeHash(filename)
    fallbackFileSystem.exists(new Path(fallbackPath, s"$appId/$shuffleId/$hash/$filename"))
  }

  private val fetchThreadPool: Option[ThreadPoolExecutor] = {
    val numShuffleThreads = FallbackStorage.getNumReadThreads(conf)
    if (numShuffleThreads > 0) {
      logInfo(s"FallbackStorage created thread pool using  ${numShuffleThreads} thread(s)")
      Some(ThreadUtils.newDaemonCachedThreadPool(
        "FetchFromFallbackStorage-threadPool", numShuffleThreads))
    } else {
      logInfo("FallbackStorage thread pool not created")
      None
    }
  }

  def fetchBlocks(
      blockManager: BlockManager,
      blocks: collection.Seq[FetchBlockInfo],
      address: BlockManagerId,
      listener: BlockFetchingListener): Unit = {
    fetchThreadPool match {
      case Some(p) if !p.isShutdown =>
        blocks.foreach(block =>
          p.submit(new Runnable {
            override def run(): Unit = {
              fetchShuffleBlocks(block, blockManager, listener)
            }
          })
        )
      case _ =>
        logInfo(s" fetchThreadPool does not exists for $address or shutdown")
        blocks.foreach(block => fetchShuffleBlocks(block, blockManager, listener))
    }
  }

  private def fetchShuffleBlocks(
              block: FetchBlockInfo,
              blockManager: BlockManager,
              listener: BlockFetchingListener): Unit = {
    try {
      // First try in local to check if its already cached in local disk,
      // if not then fallback to external storage done internally by getLocalBlockData.
      var buffer = blockManager.getLocalBlockData(block.blockId)
      if (buffer == null) {
        // For test
        buffer = FallbackStorage.read(blockManager.conf, block.blockId)
      }
      listener.onBlockFetchSuccess(block.blockId.name, buffer)
    } catch {
      case e: Exception =>
        listener.onBlockFetchFailure(block.blockId.name, e)
    }
  }
}

private[storage] class NoopRpcEndpointRef(conf: SparkConf) extends RpcEndpointRef(conf) {
  // scalastyle:off executioncontextglobal
  import scala.concurrent.ExecutionContext.Implicits.global
  // scalastyle:on executioncontextglobal
  override def address: RpcAddress = null
  override def name: String = "fallback"
  override def send(message: Any): Unit = {}
  override def ask[T: ClassTag](message: Any, timeout: RpcTimeout): Future[T] = {
    Future{true.asInstanceOf[T]}
  }
}

private[spark] object FallbackStorage extends Logging {
  /** We use one block manager id as a place holder. */
  val FALLBACK_BLOCK_MANAGER_ID: BlockManagerId = BlockManagerId("fallback", "remote", 7337)

  // There should be only one fallback storage thread pool per executor.
  var fallbackStorage: Option[FallbackStorage] = None
  def getFallbackStorage(conf: SparkConf): Option[FallbackStorage] = this.synchronized {
    if (conf != null && conf.get(STORAGE_DECOMMISSION_FALLBACK_STORAGE_PATH).isDefined) {
      if (fallbackStorage.isDefined) {
        val fallbackPath = conf.get(STORAGE_DECOMMISSION_FALLBACK_STORAGE_PATH).get
        if (fallbackPath.equals(fallbackStorage.get.fallbackPath.toString)) {
          logDebug(s"FallbackStorage defined with path $fallbackPath")
          fallbackStorage
        } else {
          // for unit test.
          Some(new FallbackStorage(conf))
        }
      } else {
        fallbackStorage = Some(new FallbackStorage(conf))
        logInfo(s"Created FallbackStorage $fallbackStorage")
        fallbackStorage
      }
    } else {
      None
    }
  }

  def getNumReadThreads(conf: SparkConf): Int = {
    val numShuffleThreads =
      if (conf == null) None else conf.get(STORAGE_FALLBACK_STORAGE_NUM_THREADS_FOR_SHUFFLE_READ)
    if (numShuffleThreads.isDefined) numShuffleThreads.get else -1
  }

  /** Register the fallback block manager and its RPC endpoint. */
  def registerBlockManagerIfNeeded(master: BlockManagerMaster, conf: SparkConf): Unit = {
    if (conf.get(STORAGE_DECOMMISSION_FALLBACK_STORAGE_PATH).isDefined) {
      master.registerBlockManager(
        FALLBACK_BLOCK_MANAGER_ID, Array.empty[String], 0, 0, new NoopRpcEndpointRef(conf))
    }
  }

  /** Clean up the generated fallback location for this app. */
  def cleanUp(conf: SparkConf, hadoopConf: Configuration): Unit = {
    if (conf.get(STORAGE_DECOMMISSION_FALLBACK_STORAGE_PATH).isDefined &&
        conf.get(STORAGE_DECOMMISSION_FALLBACK_STORAGE_CLEANUP) &&
        conf.contains("spark.app.id")) {
      val fallbackPath =
        new Path(conf.get(STORAGE_DECOMMISSION_FALLBACK_STORAGE_PATH).get, conf.getAppId)
      val fallbackUri = fallbackPath.toUri
      val fallbackFileSystem = FileSystem.get(fallbackUri, hadoopConf)
      // The fallback directory for this app may not be created yet.
      if (fallbackFileSystem.exists(fallbackPath)) {
        if (fallbackFileSystem.delete(fallbackPath, true)) {
          logInfo(s"Succeed to clean up: $fallbackUri")
        } else {
          // Clean-up can fail due to the permission issues.
          logWarning(s"Failed to clean up: $fallbackUri")
        }
      }
    }
  }

  def stopThreadPool(conf: SparkConf): Unit = {
    logInfo(s" Stopping thread pool")
    if (getFallbackStorage(conf).isDefined &&
      getFallbackStorage(conf).get.fetchThreadPool.isDefined) {
      getFallbackStorage(conf).get.fetchThreadPool.foreach(ThreadUtils.shutdown(_))
    }
  }

  /** Report block status to block manager master and map output tracker master. */
  private def reportBlockStatus(blockManager: BlockManager, blockId: BlockId, dataLength: Long) = {
    assert(blockManager.master != null)
    blockManager.master.updateBlockInfo(
      FALLBACK_BLOCK_MANAGER_ID, blockId, StorageLevel.DISK_ONLY, memSize = 0, dataLength)
  }

  /**
   * Read a ManagedBuffer.
   */
  def read(conf: SparkConf, blockId: BlockId): ManagedBuffer = {
    logInfo(s"Read $blockId")
    val fallbackPath = new Path(conf.get(STORAGE_DECOMMISSION_FALLBACK_STORAGE_PATH).get)
    val hadoopConf = SparkHadoopUtil.get.newConfiguration(conf)
    val fallbackFileSystem = FileSystem.get(fallbackPath.toUri, hadoopConf)
    val appId = conf.getAppId

    val (shuffleId, mapId, startReduceId, endReduceId) = blockId match {
      case id: ShuffleBlockId =>
        (id.shuffleId, id.mapId, id.reduceId, id.reduceId + 1)
      case batchId: ShuffleBlockBatchId =>
        (batchId.shuffleId, batchId.mapId, batchId.startReduceId, batchId.endReduceId)
      case _ =>
        throw SparkException.internalError(
          s"unexpected shuffle block id format: $blockId", category = "STORAGE")
    }

    val name = ShuffleIndexBlockId(shuffleId, mapId, NOOP_REDUCE_ID).name
    val hash = JavaUtils.nonNegativeHash(name)
    val indexFile = new Path(fallbackPath, s"$appId/$shuffleId/$hash/$name")
    val start = startReduceId * 8L
    val end = endReduceId * 8L
    Utils.tryWithResource(fallbackFileSystem.open(indexFile)) { inputStream =>
      Utils.tryWithResource(new DataInputStream(inputStream)) { index =>
        index.skip(start)
        val offset = index.readLong()
        index.skip(end - (start + 8L))
        val nextOffset = index.readLong()
        val name = ShuffleDataBlockId(shuffleId, mapId, NOOP_REDUCE_ID).name
        val hash = JavaUtils.nonNegativeHash(name)
        val dataFile = new Path(fallbackPath, s"$appId/$shuffleId/$hash/$name")
        val size = nextOffset - offset
        logDebug(s"To byte array $size")
        val array = new Array[Byte](size.toInt)
        val startTimeNs = System.nanoTime()
        Utils.tryWithResource(fallbackFileSystem.open(dataFile)) { f =>
          f.seek(offset)
          f.readFully(array)
          logDebug(s"Took ${(System.nanoTime() - startTimeNs) / (1000 * 1000)}ms")
        }
        new NioManagedBuffer(ByteBuffer.wrap(array))
      }
    }
  }
}
