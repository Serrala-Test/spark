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

package org.apache.spark.sql.execution.datasources.csv

import java.io.CharArrayWriter
import java.nio.charset.{Charset, StandardCharsets}

import com.univocity.parsers.csv.CsvWriter
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileStatus, Path}
import org.apache.hadoop.io.{LongWritable, NullWritable, Text}
import org.apache.hadoop.mapred.TextInputFormat
import org.apache.hadoop.mapreduce.{Job, RecordWriter, TaskAttemptContext}
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat

import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.execution.command.CreateDataSourceTableUtils
import org.apache.spark.sql.execution.datasources._
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types._
import org.apache.spark.util.SerializableConfiguration

/**
 * Provides access to CSV data from pure SQL statements.
 */
class CSVFileFormat extends TextBasedFileFormat with DataSourceRegister {

  override def shortName(): String = "csv"

  override def toString: String = "CSV"

  override def hashCode(): Int = getClass.hashCode()

  override def equals(other: Any): Boolean = other.isInstanceOf[CSVFileFormat]

  override def inferSchema(
      sparkSession: SparkSession,
      options: Map[String, String],
      files: Seq[FileStatus]): Option[StructType] = {
    val csvOptions = new CSVOptions(options)

    // TODO: Move filtering.
    val paths = files.filterNot { status =>
      val name = status.getPath.getName
      name.startsWith("_") || name.startsWith(".")
    }.map(_.getPath.toString)
    val rdd = createBaseRdd(sparkSession, csvOptions, paths)
    val schema = if (csvOptions.inferSchemaFlag) {
      CSVInferSchema.infer(rdd, csvOptions)
    } else {
      // By default fields are assumed to be StringType
      val filteredRdd = rdd.mapPartitions(CSVUtils.filterCommentAndEmpty(_, csvOptions))
      val firstLine = filteredRdd.first()
      val firstRow = UnivocityParser.tokenizeLine(firstLine, csvOptions)
      val header = if (csvOptions.headerFlag) {
        firstRow.zipWithIndex.map { case (value, index) =>
          if (value == null || value.isEmpty || value == csvOptions.nullValue) {
            s"_c$index"
          } else {
            value
          }
        }
      } else {
        firstRow.zipWithIndex.map { case (value, index) => s"_c$index" }
      }
      val schemaFields = header.map { fieldName =>
        StructField(fieldName.toString, StringType, nullable = true)
      }
      StructType(schemaFields)
    }
    Some(schema)
  }

  override def prepareWrite(
      sparkSession: SparkSession,
      job: Job,
      options: Map[String, String],
      dataSchema: StructType): OutputWriterFactory = {
    verifySchema(dataSchema)
    val conf = job.getConfiguration
    val csvOptions = new CSVOptions(options)
    csvOptions.compressionCodec.foreach { codec =>
      CompressionCodecs.setCodecConfiguration(conf, codec)
    }

    new CSVOutputWriterFactory(csvOptions)
  }

  private def verifySchema(schema: StructType): Unit = {
    schema.foreach { field =>
      field.dataType match {
        case _: ArrayType | _: MapType | _: StructType =>
          throw new UnsupportedOperationException(
            s"CSV data source does not support ${field.dataType.simpleString} data type.")
        case _ =>
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
      hadoopConf: Configuration): (PartitionedFile) => Iterator[InternalRow] = {
    val csvOptions = new CSVOptions(options)
    val headers = requiredSchema.fields.map(_.name)

    val broadcastedHadoopConf =
      sparkSession.sparkContext.broadcast(new SerializableConfiguration(hadoopConf))

    (file: PartitionedFile) => {
      val lines = {
        val conf = broadcastedHadoopConf.value.value
        new HadoopFileLinesReader(file, conf).map { line =>
          new String(line.getBytes, 0, line.getLength, csvOptions.charset)
        }
      }

      // TODO What if the first partitioned file consists of only comments and empty lines?
      val shouldDropHeader = csvOptions.headerFlag && file.start == 0
      UnivocityParser.parseCsv(
        lines,
        dataSchema,
        requiredSchema,
        headers,
        shouldDropHeader,
        csvOptions)
    }
  }

  private def createBaseRdd(
      sparkSession: SparkSession,
      options: CSVOptions,
      inputPaths: Seq[String]): RDD[String] = {
    val location = inputPaths.mkString(",")
    if (Charset.forName(options.charset) == StandardCharsets.UTF_8) {
      sparkSession.sparkContext.textFile(location)
    } else {
      val charset = options.charset
      sparkSession.sparkContext
        .hadoopFile[LongWritable, Text, TextInputFormat](location)
        .mapPartitions(_.map(pair => new String(pair._2.getBytes, 0, pair._2.getLength, charset)))
    }
  }
}

private[sql] class CSVOutputWriterFactory(options: CSVOptions) extends OutputWriterFactory {
  override def newInstance(
      path: String,
      bucketId: Option[Int],
      dataSchema: StructType,
      context: TaskAttemptContext): OutputWriter = {
    if (bucketId.isDefined) sys.error("csv doesn't support bucketing")
    new CsvOutputWriter(path, dataSchema, context, options)
  }
}

private[sql] class CsvOutputWriter(
    path: String,
    dataSchema: StructType,
    context: TaskAttemptContext,
    options: CSVOptions) extends OutputWriter with Logging {

  // create the Generator without separator inserted between 2 records
  private[this] val result = new Text()

  private val recordWriter: RecordWriter[NullWritable, Text] = {
    new TextOutputFormat[NullWritable, Text]() {
      override def getDefaultWorkFile(context: TaskAttemptContext, extension: String): Path = {
        val configuration = context.getConfiguration
        val uniqueWriteJobId = configuration.get(CreateDataSourceTableUtils.DATASOURCE_WRITEJOBUUID)
        val taskAttemptId = context.getTaskAttemptID
        val split = taskAttemptId.getTaskID.getId
        new Path(path, f"part-r-$split%05d-$uniqueWriteJobId.csv$extension")
      }
    }.getRecordWriter(context)
  }

  private val writerSettings = UnivocityGenerator.getSettings(options)
  private val headers = dataSchema.fieldNames
  writerSettings.setHeaders(headers: _*)

  private[this] val writer = new CharArrayWriter()
  private[this] val csvWriter = new CsvWriter(writer, writerSettings)
  private[this] var writeHeader = options.headerFlag

  private val FLUSH_BATCH_SIZE = 1024L
  private var records: Long = 0L

  override def write(row: Row): Unit = throw new UnsupportedOperationException("call writeInternal")

  override protected[sql] def writeInternal(row: InternalRow): Unit = {
    UnivocityGenerator(dataSchema, csvWriter, headers, writeHeader, options)(row)
    records += 1
    if (records % FLUSH_BATCH_SIZE == 0) {
      flush()
    }
    writeHeader = false
  }

  private def flush(): Unit = {
    csvWriter.flush()
    result.set(writer.toString.stripLineEnd)
    writer.reset()
    recordWriter.write(NullWritable.get(), result)
  }

  override def close(): Unit = {
    flush()
    csvWriter.close()
    recordWriter.close(context)
  }
}
