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

package org.apache.spark.sql.execution.datasources

import java.io.FileNotFoundException

import scala.collection.mutable

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs._
import org.apache.hadoop.mapred.{FileInputFormat, JobConf}

import org.apache.spark.internal.Logging
import org.apache.spark.metrics.source.HiveCatalogMetrics
import org.apache.spark.sql.execution.streaming.FileStreamSink
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types.StructType
import org.apache.spark.util.SerializableConfiguration


/**
 * A [[FileIndex]] that generates the list of files to process by recursively listing all the
 * files present in `paths`.
 *
 * @param rootPathsSpecified the list of root table paths to scan (some of which might be
 *                           filtered out later)
 * @param parameters as set of options to control discovery
 * @param partitionSchema an optional partition schema that will be use to provide types for the
 *                        discovered partitions
 */
class InMemoryFileIndex(
    sparkSession: SparkSession,
    rootPathsSpecified: Seq[Path],
    parameters: Map[String, String],
    partitionSchema: Option[StructType],
    fileStatusCache: FileStatusCache = NoopCache)
  extends PartitioningAwareFileIndex(
    sparkSession, parameters, partitionSchema, fileStatusCache) {

  // Filter out streaming metadata dirs or files such as "/.../_spark_metadata" (the metadata dir)
  // or "/.../_spark_metadata/0" (a file in the metadata dir). `rootPathsSpecified` might contain
  // such streaming metadata dir or files, e.g. when after globbing "basePath/*" where "basePath"
  // is the output of a streaming query.
  override val rootPaths =
    rootPathsSpecified.filterNot(FileStreamSink.ancestorIsMetadataDirectory(_, hadoopConf))

  @volatile private var cachedLeafFiles: mutable.LinkedHashMap[Path, FileStatus] = _
  @volatile private var cachedLeafDirToChildrenFiles: Map[Path, Array[FileStatus]] = _
  @volatile private var cachedPartitionSpec: PartitionSpec = _

  refresh0()

  override def partitionSpec(): PartitionSpec = {
    if (cachedPartitionSpec == null) {
      cachedPartitionSpec = inferPartitioning()
    }
    logTrace(s"Partition spec: $cachedPartitionSpec")
    cachedPartitionSpec
  }

  override protected def leafFiles: mutable.LinkedHashMap[Path, FileStatus] = {
    cachedLeafFiles
  }

  override protected def leafDirToChildrenFiles: Map[Path, Array[FileStatus]] = {
    cachedLeafDirToChildrenFiles
  }

  override def refresh(): Unit = {
    fileStatusCache.invalidateAll()
    refresh0()
  }

  private def refresh0(): Unit = {
    val files = listLeafFiles(rootPaths)
    cachedLeafFiles =
      new mutable.LinkedHashMap[Path, FileStatus]() ++= files.map(f => f.getPath -> f)
    cachedLeafDirToChildrenFiles = files.toArray.groupBy(_.getPath.getParent)
    cachedPartitionSpec = null
  }

  override def equals(other: Any): Boolean = other match {
    case hdfs: InMemoryFileIndex => rootPaths.toSet == hdfs.rootPaths.toSet
    case _ => false
  }

  override def hashCode(): Int = rootPaths.toSet.hashCode()

  /**
   * List leaf files of given paths. This method will submit a Spark job to do parallel
   * listing whenever there is a path having more files than the parallel partition discovery
   * discovery threshold.
   *
   * This is publicly visible for testing.
   */
  def listLeafFiles(paths: Seq[Path]): mutable.LinkedHashSet[FileStatus] = {
    val output = mutable.LinkedHashSet[FileStatus]()
    val pathsToFetch = mutable.ArrayBuffer[Path]()
    for (path <- paths) {
      fileStatusCache.getLeafFiles(path) match {
        case Some(files) =>
          HiveCatalogMetrics.incrementFileCacheHits(files.length)
          output ++= files
        case None =>
          pathsToFetch += path
      }
    }
    val filter = FileInputFormat.getInputPathFilter(new JobConf(hadoopConf, this.getClass))
    val discovered = InMemoryFileIndex.bulkListLeafFiles(
      pathsToFetch, hadoopConf, filter, sparkSession)
    discovered.foreach { case (path, leafFiles) =>
      HiveCatalogMetrics.incrementFilesDiscovered(leafFiles.size)
      fileStatusCache.putLeafFiles(path, leafFiles.toArray)
      output ++= leafFiles
    }
    output
  }
}

object InMemoryFileIndex extends Logging {

  /** A serializable variant of HDFS's BlockLocation. */
  private case class SerializableBlockLocation(
      names: Array[String],
      hosts: Array[String],
      offset: Long,
      length: Long)

  /** A serializable variant of HDFS's FileStatus. */
  private case class SerializableFileStatus(
      path: String,
      length: Long,
      isDir: Boolean,
      blockReplication: Short,
      blockSize: Long,
      modificationTime: Long,
      accessTime: Long,
      blockLocations: Array[SerializableBlockLocation])

  /**
   * Lists a collection of paths recursively. Picks the listing strategy adaptively depending
   * on the number of paths to list.
   *
   * This may only be called on the driver.
   *
   * @return for each input path, the set of discovered files for the path
   */
  private def bulkListLeafFiles(
      paths: Seq[Path],
      hadoopConf: Configuration,
      filter: PathFilter,
      sparkSession: SparkSession): Seq[(Path, Seq[FileStatus])] = {

    // Short-circuits parallel listing when serial listing is likely to be faster.
    if (paths.size <= sparkSession.sessionState.conf.parallelPartitionDiscoveryThreshold) {
      return listLeafFiles(paths, hadoopConf, filter, Some(sparkSession))
    }

    logInfo(s"Listing leaf files and directories in parallel under: ${paths.mkString(", ")}")
    HiveCatalogMetrics.incrementParallelListingJobCount(1)

    val sparkContext = sparkSession.sparkContext
    val serializableConfiguration = new SerializableConfiguration(hadoopConf)
    val serializedPaths = paths.map(_.toString)
    val parallelPartitionDiscoveryParallelism =
      sparkSession.sessionState.conf.parallelPartitionDiscoveryParallelism

    // Set the number of parallelism to prevent following file listing from generating many tasks
    // in case of large #defaultParallelism.
    val numParallelism = Math.min(paths.size, parallelPartitionDiscoveryParallelism)

    val statusMap = sparkContext
      .parallelize(serializedPaths, numParallelism)
      .mapPartitions { pathStrings =>
        val hadoopConf = serializableConfiguration.value
        val paths = pathStrings.map(new Path(_)).toIndexedSeq
        listLeafFiles(paths, hadoopConf, filter, None).iterator
      }.map { case (path, statuses) =>
      val serializableStatuses = statuses.map { status =>
        // Turn FileStatus into SerializableFileStatus so we can send it back to the driver
        val blockLocations = status match {
          case f: LocatedFileStatus =>
            f.getBlockLocations.map { loc =>
              SerializableBlockLocation(
                loc.getNames,
                loc.getHosts,
                loc.getOffset,
                loc.getLength)
            }

          case _ =>
            Array.empty[SerializableBlockLocation]
        }

        SerializableFileStatus(
          status.getPath.toString,
          status.getLen,
          status.isDirectory,
          status.getReplication,
          status.getBlockSize,
          status.getModificationTime,
          status.getAccessTime,
          blockLocations)
      }
      (path.toString, serializableStatuses)
    }.collect()

    // turn SerializableFileStatus back to Status
    statusMap.map { case (path, serializableStatuses) =>
      val statuses = serializableStatuses.map { f =>
        val blockLocations = f.blockLocations.map { loc =>
          new BlockLocation(loc.names, loc.hosts, loc.offset, loc.length)
        }
        new LocatedFileStatus(
          new FileStatus(
            f.length, f.isDir, f.blockReplication, f.blockSize, f.modificationTime,
            new Path(f.path)),
          blockLocations)
      }
      (new Path(path), statuses)
    }
  }

  /**
   * Lists a single filesystem path recursively. If a SparkSession object is specified, this
   * function may launch Spark jobs to parallelize listing.
   *
   * If sessionOpt is None, this may be called on executors.
   *
   * @return all children of path that match the specified filter.
   */
  private def listLeafFiles(
      paths: Seq[Path],
      hadoopConf: Configuration,
      filter: PathFilter,
      sessionOpt: Option[SparkSession]): Seq[(Path, Seq[FileStatus])] = {
    logTrace(s"Listing ${paths.mkString(", ")}")
    if (paths.isEmpty) {
      Nil
    } else {
      val fs = paths.head.getFileSystem(hadoopConf)

      // [SPARK-17599] Prevent InMemoryFileIndex from failing if path doesn't exist
      // Note that statuses only include FileStatus for the files and dirs directly under path,
      // and does not include anything else recursively.
      val filteredStatuses = paths.flatMap { path =>
        try {
          val fStatuses = fs.listStatus(path)
          val filtered = fStatuses.filterNot(status => shouldFilterOut(status.getPath.getName))
          if (filtered.nonEmpty) {
            Some(path -> filtered)
          } else {
            None
          }
        } catch {
          case _: FileNotFoundException =>
            logWarning(s"The directory $paths was not found. Was it deleted very recently?")
            None
        }
      }

      val allLeafStatuses = {
        val (dirs, topLevelFiles) = filteredStatuses.flatMap { case (path, fStatuses) =>
          fStatuses.map { f => path -> f }
        }.partition { case (_, fStatus) => fStatus.isDirectory }
        val pathsToList = dirs.map { case (_, fStatus) => fStatus.getPath }
        val nestedFiles = if (pathsToList.nonEmpty) {
          sessionOpt match {
            case Some(session) =>
              bulkListLeafFiles(pathsToList, hadoopConf, filter, session)
            case _ =>
              listLeafFiles(pathsToList, hadoopConf, filter, sessionOpt)
          }
        } else Seq.empty[(Path, Seq[FileStatus])]
        val allFiles = topLevelFiles.groupBy { case (path, _) => path }
          .flatMap { case (path, pAndStatuses) =>
            val fStatuses = pAndStatuses.map { case (_, f) => f }
            val accepted = if (filter != null) {
              fStatuses.filter(f => filter.accept(f.getPath))
            } else {
              fStatuses
            }
            if (accepted.nonEmpty) {
              Some(path -> accepted)
            } else {
              None
            }
          }.toSeq
        nestedFiles ++ allFiles
      }

      allLeafStatuses.map { case (path, fStatuses) =>
        path -> fStatuses.map {
          case f: LocatedFileStatus =>
            f

          // NOTE:
          //
          // - Although S3/S3A/S3N file system can be quite slow for remote file metadata
          //   operations, calling `getFileBlockLocations` does no harm here since these file system
          //   implementations don't actually issue RPC for this method.
          //
          // - Here we are calling `getFileBlockLocations` in a sequential manner, but it should not
          //   be a big deal since we always use to `listLeafFilesInParallel` when the number of
          //   paths exceeds threshold.
          case f =>
            // The other constructor of LocatedFileStatus will call FileStatus.getPermission(),
            // which is very slow on some file system (RawLocalFileSystem, which is launch a
            // subprocess and parse the stdout).
            val locations = fs.getFileBlockLocations(f, 0, f.getLen)
            val lfs = new LocatedFileStatus(
              f.getLen, f.isDirectory, f.getReplication, f.getBlockSize,
              f.getModificationTime, 0, null, null, null, null, f.getPath, locations
            )
            if (f.isSymlink) {
              lfs.setSymlink(f.getSymlink)
            }
            lfs
        }
      }
    }
  }

  /** Checks if we should filter out this path name. */
  def shouldFilterOut(pathName: String): Boolean = {
    // We filter follow paths:
    // 1. everything that starts with _ and ., except _common_metadata and _metadata
    // because Parquet needs to find those metadata files from leaf files returned by this method.
    // We should refactor this logic to not mix metadata files with data files.
    // 2. everything that ends with `._COPYING_`, because this is a intermediate state of file. we
    // should skip this file in case of double reading.
    val exclude = (pathName.startsWith("_") && !pathName.contains("=")) ||
      pathName.startsWith(".") || pathName.endsWith("._COPYING_")
    val include = pathName.startsWith("_common_metadata") || pathName.startsWith("_metadata")
    exclude && !include
  }
}
