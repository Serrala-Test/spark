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

import scala.collection.mutable

import org.apache.hadoop.fs.Path
import org.apache.hadoop.mapreduce.TaskAttemptContext

import org.apache.spark.internal.io.FileCommitProtocol
import org.apache.spark.internal.io.FileCommitProtocol.TaskCommitMessage
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.catalog.CatalogTypes.TablePartitionSpec
import org.apache.spark.sql.catalyst.catalog.ExternalCatalogUtils
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.connector.write.{DataWriter, WriterCommitMessage}
import org.apache.spark.sql.execution.datasources.FileFormatWriter.ConcurrentOutputWriterSpec
import org.apache.spark.sql.types.StringType
import org.apache.spark.util.SerializableConfiguration

/**
 * Abstract class for writing out data in a single Spark task.
 * Exceptions thrown by the implementation of this trait will automatically trigger task aborts.
 */
abstract class FileFormatDataWriter(
    description: WriteJobDescription,
    taskAttemptContext: TaskAttemptContext,
    committer: FileCommitProtocol) extends DataWriter[InternalRow] {
  /**
   * Max number of files a single task writes out due to file size. In most cases the number of
   * files written should be very small. This is just a safe guard to protect some really bad
   * settings, e.g. maxRecordsPerFile = 1.
   */
  protected val MAX_FILE_COUNTER: Int = 1000 * 1000
  protected val updatedPartitions: mutable.Set[String] = mutable.Set[String]()
  protected var currentWriter: OutputWriter = _
  protected var currentPath: String = _

  /** Trackers for computing various statistics on the data as it's being written out. */
  protected val statsTrackers: Seq[WriteTaskStatsTracker] =
    description.statsTrackers.map(_.newTaskInstance())

  protected def releaseResources(): Unit = {
    if (currentWriter != null) {
      try {
        currentWriter.close()
        statsTrackers.foreach(_.closeFile(currentPath))
      } finally {
        currentWriter = null
      }
    }
  }

  /** Writes a record */
  def write(record: InternalRow): Unit

  /**
   * Returns the summary of relative information which
   * includes the list of partition strings written out. The list of partitions is sent back
   * to the driver and used to update the catalog. Other information will be sent back to the
   * driver too and used to e.g. update the metrics in UI.
   */
  override def commit(): WriteTaskResult = {
    releaseResources()
    val summary = ExecutedWriteSummary(
      updatedPartitions = updatedPartitions.toSet,
      stats = statsTrackers.map(_.getFinalStats()))
    WriteTaskResult(committer.commitTask(taskAttemptContext), summary)
  }

  def abort(): Unit = {
    try {
      releaseResources()
    } finally {
      committer.abortTask(taskAttemptContext)
    }
  }

  override def close(): Unit = {}
}

/** FileFormatWriteTask for empty partitions */
class EmptyDirectoryDataWriter(
    description: WriteJobDescription,
    taskAttemptContext: TaskAttemptContext,
    committer: FileCommitProtocol
) extends FileFormatDataWriter(description, taskAttemptContext, committer) {
  override def write(record: InternalRow): Unit = {}
}

/** Writes data to a single directory (used for non-dynamic-partition writes). */
class SingleDirectoryDataWriter(
    description: WriteJobDescription,
    taskAttemptContext: TaskAttemptContext,
    committer: FileCommitProtocol)
  extends FileFormatDataWriter(description, taskAttemptContext, committer) {
  private var fileCounter: Int = _
  private var recordsInFile: Long = _
  // Initialize currentWriter and statsTrackers
  newOutputWriter()

  private def newOutputWriter(): Unit = {
    recordsInFile = 0
    releaseResources()

    val ext = description.outputWriterFactory.getFileExtension(taskAttemptContext)
    currentPath = committer.newTaskTempFile(
      taskAttemptContext,
      None,
      f"-c$fileCounter%03d" + ext)

    currentWriter = description.outputWriterFactory.newInstance(
      path = currentPath,
      dataSchema = description.dataColumns.toStructType,
      context = taskAttemptContext)

    statsTrackers.foreach(_.newFile(currentPath))
  }

  override def write(record: InternalRow): Unit = {
    if (description.maxRecordsPerFile > 0 && recordsInFile >= description.maxRecordsPerFile) {
      fileCounter += 1
      assert(fileCounter < MAX_FILE_COUNTER,
        s"File counter $fileCounter is beyond max value $MAX_FILE_COUNTER")

      newOutputWriter()
    }

    currentWriter.write(record)
    statsTrackers.foreach(_.newRow(record))
    recordsInFile += 1
  }
}

/**
 * Holds common logic for writing data with dynamic partition writes, meaning it can write to
 * multiple directories (partitions) or files (bucketing).
 */
abstract class BaseDynamicPartitionDataWriter(
    description: WriteJobDescription,
    taskAttemptContext: TaskAttemptContext,
    committer: FileCommitProtocol)
  extends FileFormatDataWriter(description, taskAttemptContext, committer) {

  /** Flag saying whether or not the data to be written out is partitioned. */
  protected val isPartitioned = description.partitionColumns.nonEmpty

  /** Flag saying whether or not the data to be written out is bucketed. */
  protected val isBucketed = description.bucketIdExpression.isDefined

  assert(isPartitioned || isBucketed,
    s"""DynamicPartitionWriteTask should be used for writing out data that's either
       |partitioned or bucketed. In this case neither is true.
       |WriteJobDescription: $description
       """.stripMargin)

  /** Number of records in current file. */
  protected var recordsInFile: Long = _

  /**
   * File counter for writing current partition or bucket. For same partition or bucket,
   * we may have more than one file, due to number of records limit per file.
   */
  protected var fileCounter: Int = _

  /** Extracts the partition values out of an input row. */
  protected lazy val getPartitionValues: InternalRow => UnsafeRow = {
    val proj = UnsafeProjection.create(description.partitionColumns, description.allColumns)
    row => proj(row)
  }

  /** Expression that given partition columns builds a path string like: col1=val/col2=val/... */
  private lazy val partitionPathExpression: Expression = Concat(
    description.partitionColumns.zipWithIndex.flatMap { case (c, i) =>
      val partitionName = ScalaUDF(
        ExternalCatalogUtils.getPartitionPathString _,
        StringType,
        Seq(Literal(c.name), Cast(c, StringType, Option(description.timeZoneId))))
      if (i == 0) Seq(partitionName) else Seq(Literal(Path.SEPARATOR), partitionName)
    })

  /**
   * Evaluates the `partitionPathExpression` above on a row of `partitionValues` and returns
   * the partition string.
   */
  private lazy val getPartitionPath: InternalRow => String = {
    val proj = UnsafeProjection.create(Seq(partitionPathExpression), description.partitionColumns)
    row => proj(row).getString(0)
  }

  /** Given an input row, returns the corresponding `bucketId` */
  protected lazy val getBucketId: InternalRow => Int = {
    val proj =
      UnsafeProjection.create(description.bucketIdExpression.toSeq, description.allColumns)
    row => proj(row).getInt(0)
  }

  /** Returns the data columns to be written given an input row */
  protected val getOutputRow =
    UnsafeProjection.create(description.dataColumns, description.allColumns)

  /**
   * Opens a new OutputWriter given a partition key and/or a bucket id.
   * If bucket id is specified, we will append it to the end of the file name, but before the
   * file extension, e.g. part-r-00009-ea518ad4-455a-4431-b471-d24e03814677-00002.gz.parquet
   *
   * @param partitionValues the partition which all tuples being written by this OutputWriter
   *                        belong to
   * @param bucketId the bucket which all tuples being written by this OutputWriter belong to
   * @param closeCurrentWriter close and release resource for current writer
   */
  protected def newOutputWriter(
      partitionValues: Option[InternalRow],
      bucketId: Option[Int],
      closeCurrentWriter: Boolean): Unit = {

    recordsInFile = 0
    if (closeCurrentWriter) {
      super[FileFormatDataWriter].releaseResources()
    }

    val partDir = partitionValues.map(getPartitionPath(_))
    partDir.foreach(updatedPartitions.add)

    val bucketIdStr = bucketId.map(BucketingUtils.bucketIdToString).getOrElse("")

    // This must be in a form that matches our bucketing format. See BucketingUtils.
    val ext = f"$bucketIdStr.c$fileCounter%03d" +
      description.outputWriterFactory.getFileExtension(taskAttemptContext)

    val customPath = partDir.flatMap { dir =>
      description.customPartitionLocations.get(PartitioningUtils.parsePathFragment(dir))
    }
    currentPath = if (customPath.isDefined) {
      committer.newTaskTempFileAbsPath(taskAttemptContext, customPath.get, ext)
    } else {
      committer.newTaskTempFile(taskAttemptContext, partDir, ext)
    }

    currentWriter = description.outputWriterFactory.newInstance(
      path = currentPath,
      dataSchema = description.dataColumns.toStructType,
      context = taskAttemptContext)

    statsTrackers.foreach(_.newFile(currentPath))
  }

  /**
   * Checks if number of records exceeding limit. Open a new OutputWriter if needed.
   *
   * @param partitionValues the partition which all tuples being written by this `OutputWriter`
   *                        belong to
   * @param bucketId the bucket which all tuples being written by this `OutputWriter` belong to
   */
  protected def checkRecordsInFile(
      partitionValues: Option[InternalRow],
      bucketId: Option[Int]): Unit = {
    if (description.maxRecordsPerFile > 0 &&
      recordsInFile >= description.maxRecordsPerFile) {
      // Exceeded the threshold in terms of the number of records per file.
      // Create a new file by increasing the file counter.
      fileCounter += 1
      assert(fileCounter < MAX_FILE_COUNTER,
        s"File counter $fileCounter is beyond max value $MAX_FILE_COUNTER")

      newOutputWriter(partitionValues, bucketId, closeCurrentWriter = true)
    }
  }

  /**
   * Writes the given record with current writer.
   *
   * @param record The record to write
   */
  protected def writeRecord(record: InternalRow): Unit = {
    val outputRow = getOutputRow(record)
    currentWriter.write(outputRow)
    statsTrackers.foreach(_.newRow(outputRow))
    recordsInFile += 1
  }
}

/**
 * Dynamic partition writer with single writer, meaning only one writer is opened at any time for
 * writing. The records to be written are required to be sorted on partition and/or bucket
 * column(s) before writing.
 */
class DynamicPartitionDataSingleWriter(
    description: WriteJobDescription,
    taskAttemptContext: TaskAttemptContext,
    committer: FileCommitProtocol)
  extends BaseDynamicPartitionDataWriter(description, taskAttemptContext, committer) {

  private var currentPartitionValues: Option[UnsafeRow] = None
  private var currentBucketId: Option[Int] = None

  override def write(record: InternalRow): Unit = {
    val nextPartitionValues = if (isPartitioned) Some(getPartitionValues(record)) else None
    val nextBucketId = if (isBucketed) Some(getBucketId(record)) else None

    if (currentPartitionValues != nextPartitionValues || currentBucketId != nextBucketId) {
      // See a new partition or bucket - write to a new partition dir (or a new bucket file).
      if (isPartitioned && currentPartitionValues != nextPartitionValues) {
        currentPartitionValues = Some(nextPartitionValues.get.copy())
        statsTrackers.foreach(_.newPartition(currentPartitionValues.get))
      }
      if (isBucketed) {
        currentBucketId = nextBucketId
      }

      fileCounter = 0
      newOutputWriter(currentPartitionValues, currentBucketId, true)
    } else {
      checkRecordsInFile(currentPartitionValues, currentBucketId)
    }
    writeRecord(record)
  }
}

/**
 * Dynamic partition writer with concurrent writers, meaning multiple concurrent writers are opened
 * for writing.
 *
 * The process has the following steps:
 *  - Step 1: Maintain a map of output writers per each partition and/or bucket columns. Keep all
 *            writers opened and write rows one by one.
 *  - Step 2: If number of concurrent writers exceeds limit, sort rest of rows on partition and/or
 *            bucket column(s). Write rows one by one, and eagerly close the writer when finishing
 *            each partition and/or bucket.
 *
 * Caller is expected to call `writeWithIterator()` instead of `write()` to write records.
 */
class DynamicPartitionDataConcurrentWriter(
    description: WriteJobDescription,
    taskAttemptContext: TaskAttemptContext,
    committer: FileCommitProtocol,
    concurrentOutputWriterSpec: ConcurrentOutputWriterSpec)
  extends BaseDynamicPartitionDataWriter(description, taskAttemptContext, committer) {

  /** Wrapper class to index a unique concurrent output writer. */
  private case class WriterIndex(
    var partitionValues: Option[UnsafeRow],
    var bucketId: Option[Int])

  /** Wrapper class for status of a unique concurrent output writer. */
  private case class WriterStatus(
    var outputWriter: OutputWriter,
    var recordsInFile: Long,
    var fileCounter: Int,
    var latestFilePath: String)

  /**
   * State to indicate if we are falling back to sort-based writer.
   * Because we first try to use concurrent writers, its initial value is false.
   */
  private var sortBased: Boolean = false
  private val concurrentWriters = mutable.HashMap[WriterIndex, WriterStatus]()
  private val currentWriterId = WriterIndex(None, None)

  /**
   * Release resources for all concurrent output writers.
   */
  override protected def releaseResources(): Unit = {
    currentWriter = null
    concurrentWriters.values.foreach(status => {
      if (status.outputWriter != null) {
        try {
          status.outputWriter.close()
        } finally {
          status.outputWriter = null
        }
      }
    })
    concurrentWriters.clear()
  }

  override def write(record: InternalRow): Unit = {
    val nextPartitionValues = if (isPartitioned) Some(getPartitionValues(record)) else None
    val nextBucketId = if (isBucketed) Some(getBucketId(record)) else None

    if (currentWriterId.partitionValues != nextPartitionValues ||
      currentWriterId.bucketId != nextBucketId) {
      // See a new partition or bucket - write to a new partition dir (or a new bucket file).
      updateCurrentWriterStatusInMap()
      if (isBucketed) {
        currentWriterId.bucketId = nextBucketId
      }
      if (isPartitioned && currentWriterId.partitionValues != nextPartitionValues) {
        currentWriterId.partitionValues = Some(nextPartitionValues.get.copy())
        if (!concurrentWriters.contains(currentWriterId)) {
          statsTrackers.foreach(_.newPartition(currentWriterId.partitionValues.get))
        }
      }
      retrieveWriterInMap()
    }

    checkRecordsInFile(currentWriterId.partitionValues, currentWriterId.bucketId)
    writeRecord(record)
  }

  /**
   * Write iterator of records with concurrent writers.
   */
  def writeWithIterator(iterator: Iterator[InternalRow]): Unit = {
    while (iterator.hasNext && !sortBased) {
      write(iterator.next())
    }

    if (iterator.hasNext) {
      clearCurrentWriterStatus()
      val sorter = concurrentOutputWriterSpec.createSorter()
      val sortIterator = sorter.sort(iterator.asInstanceOf[Iterator[UnsafeRow]])
      while (sortIterator.hasNext) {
        write(sortIterator.next())
      }
    }
  }

  /**
   * Update current writer status when a new writer is needed for writing row.
   */
  private def updateCurrentWriterStatusInMap(): Unit = {
    if (currentWriterId.partitionValues.isDefined || currentWriterId.bucketId.isDefined) {
      if (!sortBased) {
        // Update writer status in concurrent writers map, because the writer is probably needed
        // again later for writing other rows.
        val status = concurrentWriters(currentWriterId)
        status.outputWriter = currentWriter
        status.recordsInFile = recordsInFile
        status.fileCounter = fileCounter
        status.latestFilePath = currentPath
      } else {
        // Remove writer status in concurrent writers map and release writer resource,
        // because the writer is not needed any more.
        concurrentWriters.remove(currentWriterId)
        super.releaseResources()
      }
    }
  }

  /**
   * Retrieve writer in map, or create a new writer if not exists.
   */
  private def retrieveWriterInMap(): Unit = {
    if (concurrentWriters.contains(currentWriterId)) {
      val status = concurrentWriters(currentWriterId)
      currentWriter = status.outputWriter
      recordsInFile = status.recordsInFile
      fileCounter = status.fileCounter
      currentPath = status.latestFilePath
    } else {
      fileCounter = 0
      newOutputWriter(
        currentWriterId.partitionValues,
        currentWriterId.bucketId,
        closeCurrentWriter = false)
      concurrentWriters.put(
        WriterIndex(currentWriterId.partitionValues, currentWriterId.bucketId),
        WriterStatus(currentWriter, recordsInFile, fileCounter, currentPath))
      if (concurrentWriters.size > concurrentOutputWriterSpec.maxWriters &&
        !sortBased) {
        // Fall back to sort-based sequential writer mode.
        sortBased = true
      }
    }
  }

  /**
   * Clear the current writer status in map.
   */
  private def clearCurrentWriterStatus(): Unit = {
    if (currentWriterId.partitionValues.isDefined || currentWriterId.bucketId.isDefined) {
      val status = concurrentWriters(currentWriterId)
      status.outputWriter = currentWriter
      status.recordsInFile = recordsInFile
      status.fileCounter = fileCounter
      status.latestFilePath = currentPath
    }
    currentWriterId.partitionValues = None
    currentWriterId.bucketId = None
    currentWriter = null
    recordsInFile = 0
    fileCounter = 0
    currentPath = null
  }
}

/** A shared job description for all the write tasks. */
class WriteJobDescription(
    val uuid: String, // prevent collision between different (appending) write jobs
    val serializableHadoopConf: SerializableConfiguration,
    val outputWriterFactory: OutputWriterFactory,
    val allColumns: Seq[Attribute],
    val dataColumns: Seq[Attribute],
    val partitionColumns: Seq[Attribute],
    val bucketIdExpression: Option[Expression],
    val path: String,
    val customPartitionLocations: Map[TablePartitionSpec, String],
    val maxRecordsPerFile: Long,
    val timeZoneId: String,
    val statsTrackers: Seq[WriteJobStatsTracker])
  extends Serializable {

  assert(AttributeSet(allColumns) == AttributeSet(partitionColumns ++ dataColumns),
    s"""
         |All columns: ${allColumns.mkString(", ")}
         |Partition columns: ${partitionColumns.mkString(", ")}
         |Data columns: ${dataColumns.mkString(", ")}
       """.stripMargin)
}

/** The result of a successful write task. */
case class WriteTaskResult(commitMsg: TaskCommitMessage, summary: ExecutedWriteSummary)
  extends WriterCommitMessage

/**
 * Wrapper class for the metrics of writing data out.
 *
 * @param updatedPartitions the partitions updated during writing data out. Only valid
 *                          for dynamic partition.
 * @param stats one `WriteTaskStats` object for every `WriteJobStatsTracker` that the job had.
 */
case class ExecutedWriteSummary(
    updatedPartitions: Set[String],
    stats: Seq[WriteTaskStats])
