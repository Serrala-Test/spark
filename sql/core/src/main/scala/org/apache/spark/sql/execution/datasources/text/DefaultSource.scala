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

package org.apache.spark.sql.execution.datasources.text

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileStatus, Path}
import org.apache.hadoop.io.{NullWritable, Text}
import org.apache.hadoop.mapreduce._
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl

import org.apache.spark.sql.{AnalysisException, Row, SparkSession, SQLContext}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.UnsafeRow
import org.apache.spark.sql.catalyst.expressions.codegen.{BufferHolder, UnsafeRowWriter}
import org.apache.spark.sql.execution.datasources._
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types.{StringType, StructType}
import org.apache.spark.util.SerializableConfiguration

/**
 * A data source for reading text files.
 */
class DefaultSource extends FileFormat with DataSourceRegister {

  override def shortName(): String = "text"

  override def toString: String = "Text"

  private def verifySchema(schema: StructType): Unit = {
    if (schema.size != 1) {
      throw new AnalysisException(
        s"Text data source supports only a single column, and you have ${schema.size} columns.")
    }
    val tpe = schema(0).dataType
    if (tpe != StringType) {
      throw new AnalysisException(
        s"Text data source supports only a string column, but you have ${tpe.simpleString}.")
    }
  }

  override def inferSchema(
      sparkSession: SparkSession,
      options: Map[String, String],
      files: Seq[FileStatus]): Option[StructType] = Some(new StructType().add("value", StringType))

  override def prepareWrite(
      sparkSession: SparkSession,
      job: Job,
      options: Map[String, String],
      dataSchema: StructType): OutputWriterFactory = {
    verifySchema(dataSchema)

    val conf = job.getConfiguration
    val compressionCodec = options.get("compression").map(CompressionCodecs.getCodecClassName)
    compressionCodec.foreach { codec =>
      CompressionCodecs.setCodecConfiguration(conf, codec)
    }

    new OutputWriterFactory {
      override def newInstance(
          path: String,
          bucketId: Option[Int],
          dataSchema: StructType,
          context: TaskAttemptContext): OutputWriter = {
        if (bucketId.isDefined) {
          throw new AnalysisException("Text doesn't support bucketing")
        }
        new TextOutputWriter(path, dataSchema, context)
      }
    }
  }

  override def buildReader(
      sparkSession: SparkSession,
      dataSchema: StructType,
      partitionSchema: StructType,
      requiredSchema: StructType,
      filters: Seq[Filter],
      options: Map[String, String],
      hadoopConf: Configuration): PartitionedFile => Iterator[InternalRow] = {
    val broadcastedHadoopConf =
      sparkSession.sparkContext.broadcast(new SerializableConfiguration(hadoopConf))

    (file: PartitionedFile) => {
      val unsafeRow = new UnsafeRow(1)
      val bufferHolder = new BufferHolder(unsafeRow)
      val unsafeRowWriter = new UnsafeRowWriter(bufferHolder, 1)

      new HadoopFileLinesReader(file, broadcastedHadoopConf.value.value).map { line =>
        // Writes to an UnsafeRow directly
        bufferHolder.reset()
        unsafeRowWriter.write(0, line.getBytes, 0, line.getLength)
        unsafeRow.setTotalSize(bufferHolder.totalSize())
        unsafeRow
      }
    }
  }

  override def buildWriter(
      sqlContext: SQLContext,
      dataSchema: StructType,
      options: Map[String, String]): OutputWriterFactory = {
    verifySchema(dataSchema)
    new TextOutputWriterFactory(
      sqlContext.conf,
      dataSchema,
      sqlContext.sparkContext.hadoopConfiguration,
      options)
  }
}

class TextOutputWriter(path: String, dataSchema: StructType, context: TaskAttemptContext)
  extends OutputWriter {

  private[this] val buffer = new Text()

  private val recordWriter: RecordWriter[NullWritable, Text] = {
    new TextOutputFormat[NullWritable, Text]() {
      override def getDefaultWorkFile(context: TaskAttemptContext, extension: String): Path = {
        val configuration = context.getConfiguration
        val uniqueWriteJobId = configuration.get("spark.sql.sources.writeJobUUID")
        val taskAttemptId = context.getTaskAttemptID
        val split = taskAttemptId.getTaskID.getId
        new Path(path, f"part-r-$split%05d-$uniqueWriteJobId.txt$extension")
      }
    }.getRecordWriter(context)
  }

  override def write(row: Row): Unit = throw new UnsupportedOperationException("call writeInternal")

  override protected[sql] def writeInternal(row: InternalRow): Unit = {
    val utf8string = row.getUTF8String(0)
    buffer.set(utf8string.getBytes)
    recordWriter.write(NullWritable.get(), buffer)
  }

  override def close(): Unit = {
    recordWriter.close(context)
  }
}

/**
 * A factory for generating [[OutputWriter]]s for writing text files. This implemented is different
 * from the [[TextOutputWriter]] as this does not use any [[OutputCommitter]]. It simply
 * writes the data to the path used to generate the output writer. Callers of this factory
 * has to ensure which files are to be considered as committed.
 */
private[sql] class TextOutputWriterFactory(
    sqlConf: SQLConf,
    dataSchema: StructType,
    hadoopConf: Configuration,
    options: Map[String, String]) extends OutputWriterFactory {

  private val serializableConf = {
    val conf = Job.getInstance(hadoopConf).getConfiguration
    val compressionCodec = options.get("compression").map(CompressionCodecs.getCodecClassName)
    compressionCodec.foreach { codec =>
      CompressionCodecs.setCodecConfiguration(conf, codec)
    }
    new SerializableConfiguration(conf)
  }

  /**
   * Returns a [[OutputWriter]] that writes data to the give path without using an
   * [[OutputCommitter]].
   */
  override private[sql] def newWriter(path: String): OutputWriter = new OutputWriter {

    private val hadoopTaskAttempId = new TaskAttemptID(new TaskID(new JobID, TaskType.MAP, 0), 0)
    private val hadoopAttemptContext =
      new TaskAttemptContextImpl(serializableConf.value, hadoopTaskAttempId)

    // Instance of RecordWriter that does not use OutputCommitter
    private val recordWriter = createNoCommitterRecordWriter(path, hadoopAttemptContext)

    private[this] val buffer = new Text()

    override def write(row: Row): Unit = {
      throw new UnsupportedOperationException("call writeInternal")
    }

    protected[sql] override def writeInternal(row: InternalRow): Unit = {
      val utf8string = row.getUTF8String(0)
      buffer.set(utf8string.getBytes)
      recordWriter.write(NullWritable.get(), buffer)
    }

    override def close(): Unit = recordWriter.close(hadoopAttemptContext)
  }

  /** Create a [[RecordWriter]] that writes the given path without using an [[OutputCommitter]]. */
  private def createNoCommitterRecordWriter(
      path: String,
      hadoopAttemptContext: TaskAttemptContext): RecordWriter[NullWritable, Text] = {
    // Custom TextOutputFormat that disable use of committer and writes to the given path
    val outputFormat = new TextOutputFormat[NullWritable, Text]() {
      override def getOutputCommitter(c: TaskAttemptContext): OutputCommitter = { null }
      override def getDefaultWorkFile(c: TaskAttemptContext, ext: String): Path = { new Path(path) }
    }
    outputFormat.getRecordWriter(hadoopAttemptContext)
  }

  /** Disable the use of the older API. */
  def newInstance(
      path: String,
      bucketId: Option[Int],
      dataSchema: StructType,
      context: TaskAttemptContext): OutputWriter = {
    throw new UnsupportedOperationException(
      "this version of newInstance is not supported for " +
        classOf[TextOutputWriterFactory].getSimpleName)
  }
}
