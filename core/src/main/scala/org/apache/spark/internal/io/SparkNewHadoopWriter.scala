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

package org.apache.spark.internal.io

import java.text.SimpleDateFormat
import java.util.{Date, Locale}

import scala.reflect.ClassTag
import scala.util.DynamicVariable

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.mapred.{JobConf, JobID}
import org.apache.hadoop.mapreduce._
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl

import org.apache.spark.{SparkConf, SparkContext, SparkException, TaskContext}
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.executor.OutputMetrics
import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.util.{SerializableConfiguration, Utils}

/**
 * A helper object that saves an RDD using a Hadoop OutputFormat
 * (from the newer mapreduce API, not the old mapred API).
 */
private[spark]
object SparkNewHadoopWriter extends Logging {

  /** A shared job description for all the write tasks. */
  private class WriteJobDescription[K, V](
      val jobTrackerId: String,
      val serializableHadoopConf: SerializableConfiguration,
      val outputFormat: Class[_ <: OutputFormat[K, V]])
    extends Serializable {
  }

  /**
   * Basic work flow of this command is:
   * 1. Driver side setup, including output committer initialization and data source specific
   *    preparation work for the write job to be issued.
   * 2. Issues a write job consists of one or more executor side tasks, each of which writes all
   *    rows within an RDD partition.
   * 3. If no exception is thrown in a task, commits that task, otherwise aborts that task;  If any
   *    exception is thrown during task commitment, also aborts that task.
   * 4. If all tasks are committed, commit the job, otherwise aborts the job;  If any exception is
   *    thrown during job commitment, also aborts the job.
   */
  def write[K, V: ClassTag](
      sparkContext: SparkContext,
      rdd: RDD[(K, V)],
      committer: HadoopMapReduceCommitProtocol,
      stageId: Int,
      hadoopConf: Configuration): Unit = {
    val conf = new SerializableConfiguration(hadoopConf)

    // Set up a job.
    val jobTrackerId = SparkNewHadoopWriterUtils.createJobTrackerID(new Date())
    val jobAttemptId = new TaskAttemptID(jobTrackerId, stageId, TaskType.MAP, 0, 0)
    val jobContext = new TaskAttemptContextImpl(conf.value, jobAttemptId)
    val format = jobContext.getOutputFormatClass

    if (SparkNewHadoopWriterUtils.isOutputSpecValidationEnabled(rdd.conf)) {
      // FileOutputFormat ignores the filesystem parameter
      val jobFormat = format.newInstance
      jobFormat.checkOutputSpecs(jobContext)
    }

    committer.setupJob(jobContext)

    // When speculation is on and output committer class name contains "Direct", we should warn
    // users that they may loss data if they are using a direct output committer.
    if (SparkNewHadoopWriterUtils.isSpeculationEnabled(rdd.conf) && committer.isDirectOutput) {
      val warningMessage =
        s"$committer may be an output committer that writes data directly to " +
          "the final location. Because speculation is enabled, this output committer may " +
          "cause data loss (see the case in SPARK-10063). If possible, please use an output " +
          "committer that does not have this behavior (e.g. FileOutputCommitter)."
      logWarning(warningMessage)
    }

    // Generate shared job description.
    val description = new WriteJobDescription[K, V](
      jobTrackerId = jobTrackerId,
      serializableHadoopConf = conf,
      outputFormat = format.asInstanceOf[Class[OutputFormat[K, V]]])

    // Try to write all RDD partitions as a Hadoop OutputFormat.
    try {
      sparkContext.runJob(rdd, (context: TaskContext, iter: Iterator[(K, V)]) => {
        executeTask(
          context = context,
          description = description,
          sparkStageId = context.stageId,
          sparkPartitionId = context.partitionId,
          sparkAttemptNumber = context.attemptNumber,
          committer = committer,
          iterator = iter)
      })

      committer.commitJob(jobContext, Seq.empty)
      logInfo(s"Job ${jobContext.getJobID} committed.")
    } catch { case cause: Throwable =>
      logError(s"Aborting job ${jobContext.getJobID}.", cause)
      committer.abortJob(jobContext)
      throw new SparkException("Job aborted.", cause)
    }
  }

  /** Write a RDD partition out in a single Spark task. */
  private def executeTask[K, V: ClassTag](
      context: TaskContext,
      description: WriteJobDescription[K, V],
      sparkStageId: Int,
      sparkPartitionId: Int,
      sparkAttemptNumber: Int,
      committer: FileCommitProtocol,
      iterator: Iterator[(K, V)]): Unit = {
    val conf = description.serializableHadoopConf.value

    // Set up a task.
    val attemptId = new TaskAttemptID(description.jobTrackerId, sparkStageId, TaskType.REDUCE,
      sparkPartitionId, sparkAttemptNumber)
    val taskContext = new TaskAttemptContextImpl(conf, attemptId)
    committer.setupTask(taskContext)

    val outputMetricsAndBytesWrittenCallback: Option[(OutputMetrics, () => Long)] =
      SparkNewHadoopWriterUtils.initHadoopOutputMetrics(context)

    // Initiate the writer.
    val taskFormat = description.outputFormat.newInstance
    val writer = taskFormat.getRecordWriter(taskContext)
      .asInstanceOf[RecordWriter[K, V]]
    require(writer != null, "Unable to obtain RecordWriter")
    var recordsWritten = 0L

    // Write all rows in RDD partition.
    try {
      Utils.tryWithSafeFinallyAndFailureCallbacks {
        while (iterator.hasNext) {
          val pair = iterator.next()
          writer.write(pair._1, pair._2)

          // Update bytes written metric every few records
          SparkNewHadoopWriterUtils.maybeUpdateOutputMetrics(
            outputMetricsAndBytesWrittenCallback, recordsWritten)
          recordsWritten += 1
        }

        committer.commitTask(taskContext)
      }(catchBlock = {
        committer.abortTask(taskContext)
        logError(s"Task ${taskContext.getTaskAttemptID} aborted.")
      }, finallyBlock = writer.close(taskContext))
    } catch {
      case t: Throwable =>
        throw new SparkException("Task failed while writing rows", t)
    }

    outputMetricsAndBytesWrittenCallback.foreach { case (om, callback) =>
      om.setBytesWritten(callback())
      om.setRecordsWritten(recordsWritten)
    }
  }
}

private[spark]
object SparkNewHadoopWriterUtils {
  def createJobID(time: Date, id: Int): JobID = {
    val jobtrackerID = createJobTrackerID(time)
    new JobID(jobtrackerID, id)
  }

  def createJobTrackerID(time: Date): String = {
    new SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(time)
  }

  def createPathFromString(path: String, conf: JobConf): Path = {
    if (path == null) {
      throw new IllegalArgumentException("Output path is null")
    }
    val outputPath = new Path(path)
    val fs = outputPath.getFileSystem(conf)
    if (fs == null) {
      throw new IllegalArgumentException("Incorrectly formatted output path")
    }
    outputPath.makeQualified(fs.getUri, fs.getWorkingDirectory)
  }

  // Note: this needs to be a function instead of a 'val' so that the disableOutputSpecValidation
  // setting can take effect:
  def isOutputSpecValidationEnabled(conf: SparkConf): Boolean = {
    val validationDisabled = disableOutputSpecValidation.value
    val enabledInConf = conf.getBoolean("spark.hadoop.validateOutputSpecs", true)
    enabledInConf && !validationDisabled
  }

  def isSpeculationEnabled(conf: SparkConf): Boolean = {
    conf.getBoolean("spark.speculation", false)
  }

  // TODO: these don't seem like the right abstractions.
  // We should abstract the duplicate code in a less awkward way.

  // return type: (output metrics, bytes written callback), defined only if the latter is defined
  def initHadoopOutputMetrics(
      context: TaskContext): Option[(OutputMetrics, () => Long)] = {
    val bytesWrittenCallback = SparkHadoopUtil.get.getFSBytesWrittenOnThreadCallback()
    bytesWrittenCallback.map { b =>
      (context.taskMetrics().outputMetrics, b)
    }
  }

  def maybeUpdateOutputMetrics(
      outputMetricsAndBytesWrittenCallback: Option[(OutputMetrics, () => Long)],
      recordsWritten: Long): Unit = {
    if (recordsWritten % RECORDS_BETWEEN_BYTES_WRITTEN_METRIC_UPDATES == 0) {
      outputMetricsAndBytesWrittenCallback.foreach { case (om, callback) =>
        om.setBytesWritten(callback())
        om.setRecordsWritten(recordsWritten)
      }
    }
  }

  val RECORDS_BETWEEN_BYTES_WRITTEN_METRIC_UPDATES = 256

  /**
   * Allows for the `spark.hadoop.validateOutputSpecs` checks to be disabled on a case-by-case
   * basis; see SPARK-4835 for more details.
   */
  val disableOutputSpecValidation: DynamicVariable[Boolean] = new DynamicVariable[Boolean](false)
}
