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

import scala.collection.mutable

import org.apache.hadoop.fs.{FileStatus, Path}

import org.apache.spark.internal.LogKeys._
import org.apache.spark.internal.MDC
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.execution.datasources._
import org.apache.spark.sql.types.StructType


/**
 * A [[FileIndex]] that generates the list of files to processing by reading them from the
 * metadata log files generated by the [[FileStreamSink]].
 *
 * @param userSpecifiedSchema an optional user specified schema that will be use to provide
 *                            types for the discovered partitions
 */
class MetadataLogFileIndex(
    sparkSession: SparkSession,
    path: Path,
    parameters: Map[String, String],
    userSpecifiedSchema: Option[StructType])
  extends PartitioningAwareFileIndex(sparkSession, parameters, userSpecifiedSchema) {

  private val metadataDirectory = {
    val metadataDir = new Path(path, FileStreamSink.metadataDir)
    val fs = metadataDir.getFileSystem(sparkSession.sessionState.newHadoopConf())
    FileStreamSink.checkEscapedMetadataPath(fs, metadataDir, sparkSession.sessionState.conf)
    metadataDir
  }

  logInfo(log"Reading streaming file log from ${MDC(METADATA_DIRECTORY, metadataDirectory)}")
  private val metadataLog =
    new FileStreamSinkLog(FileStreamSinkLog.VERSION, sparkSession, metadataDirectory.toString)
  private val allFilesFromLog = metadataLog.allFiles().map(_.toFileStatus).filterNot(_.isDirectory)
  private var cachedPartitionSpec: PartitionSpec = _

  override protected val leafFiles: mutable.LinkedHashMap[Path, FileStatus] = {
    new mutable.LinkedHashMap ++= allFilesFromLog.map(f => f.getPath -> f)
  }

  override protected val leafDirToChildrenFiles: Map[Path, Array[FileStatus]] = {
    allFilesFromLog.groupBy(_.getPath.getParent)
  }

  override def rootPaths: Seq[Path] = path :: Nil

  override def refresh(): Unit = { }

  override def partitionSpec(): PartitionSpec = {
    if (cachedPartitionSpec == null) {
      cachedPartitionSpec = inferPartitioning()
    }
    cachedPartitionSpec
  }
}
