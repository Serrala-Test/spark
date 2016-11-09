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

package org.apache.spark.sql.execution.streaming

import java.util.{LinkedHashMap => JLinkedHashMap}
import java.util.Map.Entry

import scala.collection.mutable

import org.json4s.NoTypeHints
import org.json4s.jackson.Serialization

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.execution.streaming.FileStreamSource.FileEntry
import org.apache.spark.sql.internal.SQLConf

class FileStreamSourceLog(
    metadataLogVersion: String,
    sparkSession: SparkSession,
    path: String)
  extends CompactibleFileStreamLog[FileEntry](metadataLogVersion, sparkSession, path) {

  import CompactibleFileStreamLog._

  // Configurations about metadata compaction
  protected override def compactInterval: Int = {
    val compactIntervalCfg = sparkSession.sessionState.conf.fileSourceLogCompactInterval

    // SPARK-18187: "compactInterval" can be set by user in the first time. In case it is changed,
    // we should check and update it:
    //
    // 1. If there is no '.compact' file, we can use user setting directly.
    // 2. If there are two or more '.compact' files, we use the interval of patch id suffix with
    // '.compact' as compactInterval.
    // 3. If there is just one '.compact', we can find the nearest value around 'compactIntervalCfg'
    // to make the 'latest compact batch id' satisfying 'modular arithmetic'.
    val compactibleBatchIds = getCompactBatchIds()
    if (compactibleBatchIds.length == 0) {
      compactIntervalCfg
    } else if (compactibleBatchIds.length >=2) {
      val latestBatchId = compactibleBatchIds(0)
      val penultimateBatchId = compactibleBatchIds(1)
      (latestBatchId - penultimateBatchId).toInt
    } else {
      // Find the nearest value around 'compactIntervalCfg' to make the 'latest compact batch id'
      // satisfying 'modular arithmetic'.
      val latestBatchId = compactibleBatchIds(0)
      for(i <- 0 until compactIntervalCfg) {
        if ((latestBatchId + 1) % (compactIntervalCfg + i) == 0) {
          return compactIntervalCfg + i
        }

        if ((latestBatchId + 1) % (compactIntervalCfg - i) == 0) {
          return compactIntervalCfg - i
        }
      }

      // This is unexpected in the code path, make compiler happy.
      compactIntervalCfg
    }
  }
  require(compactInterval > 0,
    s"Please set ${SQLConf.FILE_SOURCE_LOG_COMPACT_INTERVAL.key} (was $compactInterval) to a " +
      s"positive value.")

  protected override val fileCleanupDelayMs =
    sparkSession.sessionState.conf.fileSourceLogCleanupDelay

  protected override val isDeletingExpiredLog = sparkSession.sessionState.conf.fileSourceLogDeletion

  private implicit val formats = Serialization.formats(NoTypeHints)

  // A fixed size log entry cache to cache the file entries belong to the compaction batch. It is
  // used to avoid scanning the compacted log file to retrieve it's own batch data.
  private val cacheSize = compactInterval
  private val fileEntryCache = new JLinkedHashMap[Long, Array[FileEntry]] {
    override def removeEldestEntry(eldest: Entry[Long, Array[FileEntry]]): Boolean = {
      size() > cacheSize
    }
  }

  protected override def serializeData(data: FileEntry): String = {
    Serialization.write(data)
  }

  protected override def deserializeData(encodedString: String): FileEntry = {
    Serialization.read[FileEntry](encodedString)
  }

  def compactLogs(logs: Seq[FileEntry]): Seq[FileEntry] = {
    logs
  }

  override def add(batchId: Long, logs: Array[FileEntry]): Boolean = {
    if (super.add(batchId, logs)) {
      if (isCompactionBatch(batchId, compactInterval)) {
        fileEntryCache.put(batchId, logs)
      }
      true
    } else {
      false
    }
  }

  override def get(startId: Option[Long], endId: Option[Long]): Array[(Long, Array[FileEntry])] = {
    val startBatchId = startId.getOrElse(0L)
    val endBatchId = getLatest().map(_._1).getOrElse(0L)

    val (existedBatches, removedBatches) = (startBatchId to endBatchId).map { id =>
      if (isCompactionBatch(id, compactInterval) && fileEntryCache.containsKey(id)) {
        (id, Some(fileEntryCache.get(id)))
      } else {
        val logs = super.get(id).map(_.filter(_.batchId == id))
        (id, logs)
      }
    }.partition(_._2.isDefined)

    // The below code may only be happened when original metadata log file has been removed, so we
    // have to get the batch from latest compacted log file. This is quite time-consuming and may
    // not be happened in the current FileStreamSource code path, since we only fetch the
    // latest metadata log file.
    val searchKeys = removedBatches.map(_._1)
    val retrievedBatches = if (searchKeys.nonEmpty) {
      logWarning(s"Get batches from removed files, this is unexpected in the current code path!!!")
      val latestBatchId = getLatest().map(_._1).getOrElse(-1L)
      if (latestBatchId < 0) {
        Map.empty[Long, Option[Array[FileEntry]]]
      } else {
        val latestCompactedBatchId = getAllValidBatches(latestBatchId, compactInterval)(0)
        val allLogs = new mutable.HashMap[Long, mutable.ArrayBuffer[FileEntry]]

        super.get(latestCompactedBatchId).foreach { entries =>
          entries.foreach { e =>
            allLogs.put(e.batchId, allLogs.getOrElse(e.batchId, mutable.ArrayBuffer()) += e)
          }
        }

        searchKeys.map(id => id -> allLogs.get(id).map(_.toArray)).filter(_._2.isDefined).toMap
      }
    } else {
      Map.empty[Long, Option[Array[FileEntry]]]
    }

    (existedBatches ++ retrievedBatches).map(i => i._1 -> i._2.get).toArray.sortBy(_._1)
  }
}

object FileStreamSourceLog {
  val VERSION = "v1"
}
