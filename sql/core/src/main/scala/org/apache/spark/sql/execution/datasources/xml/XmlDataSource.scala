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

package org.apache.spark.sql.execution.datasources.xml

import java.nio.charset.{Charset, StandardCharsets}

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileStatus, Path}
import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat

import org.apache.spark.TaskContext
import org.apache.spark.input.{PortableDataStream, StreamInputFormat}
import org.apache.spark.internal.Logging
import org.apache.spark.rdd.{BinaryFileRDD, RDD}
import org.apache.spark.sql.{Dataset, Encoders, SparkSession}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.util.FailureSafeParser
import org.apache.spark.sql.catalyst.xml.{StaxXmlParser, XmlInferSchema, XmlOptions}
import org.apache.spark.sql.execution.SQLExecution
import org.apache.spark.sql.execution.datasources._
import org.apache.spark.sql.execution.datasources.text.TextFileFormat
import org.apache.spark.sql.types.StructType

/**
 * Common functions for parsing XML files
 */
abstract class XmlDataSource extends Serializable {
  def isSplitable: Boolean

  /**
   * Parse a [[PartitionedFile]] into [[InternalRow]] instances.
   */
  def readFile(
      conf: Configuration,
      file: PartitionedFile,
      parser: StaxXmlParser,
      schema: StructType): Iterator[InternalRow]

  /**
   * Infers the schema from `inputPaths` files.
   */
  final def inferSchema(
      sparkSession: SparkSession,
      inputPaths: Seq[FileStatus],
      parsedOptions: XmlOptions): Option[StructType] = {
    if (inputPaths.nonEmpty) {
      Some(infer(sparkSession, inputPaths, parsedOptions))
    } else {
      None
    }
  }

  protected def infer(
      sparkSession: SparkSession,
      inputPaths: Seq[FileStatus],
      parsedOptions: XmlOptions): StructType
}

object XmlDataSource extends Logging {
  def apply(options: XmlOptions): XmlDataSource = {
    if (options.multiLine) {
      MultiLineXmlDataSource
    } else {
      TextInputXmlDataSource
    }
  }
}

object TextInputXmlDataSource extends XmlDataSource {
  override val isSplitable: Boolean = true

  override def readFile(
      conf: Configuration,
      file: PartitionedFile,
      parser: StaxXmlParser,
      schema: StructType): Iterator[InternalRow] = {
    val lines = {
      val linesReader = new HadoopFileLinesReader(file, None, conf)
      Option(TaskContext.get()).foreach(_.addTaskCompletionListener[Unit](_ => linesReader.close()))
      linesReader.map { line =>
        new String(line.getBytes, 0, line.getLength, parser.options.charset)
      }
    }

    val safeParser = new FailureSafeParser[String](
      input => parser.parse(input),
      parser.options.parseMode,
      schema,
      parser.options.columnNameOfCorruptRecord)

    lines.flatMap(safeParser.parse)
  }

  override def infer(
      sparkSession: SparkSession,
      inputPaths: Seq[FileStatus],
      parsedOptions: XmlOptions): StructType = {
    val xml = createBaseDataset(sparkSession, inputPaths, parsedOptions)
    inferFromDataset(xml, parsedOptions, sparkSession.sessionState.conf.caseSensitiveAnalysis)
  }

  /**
   * Infers the schema from `Dataset` that stores CSV string records.
   */
  def inferFromDataset(
      xml: Dataset[String],
      parsedOptions: XmlOptions,
      caseSensitive: Boolean): StructType = {
    XmlInferSchema.infer(xml.rdd, parsedOptions, caseSensitive)
  }

  private def createBaseDataset(
      sparkSession: SparkSession,
      inputPaths: Seq[FileStatus],
      options: XmlOptions): Dataset[String] = {
    val paths = inputPaths.map(_.getPath.toString)
    val df = sparkSession.baseRelationToDataFrame(
      DataSource.apply(
        sparkSession,
        paths = paths,
        className = classOf[TextFileFormat].getName,
        options = options.parameters ++ Map(DataSource.GLOB_PATHS_KEY -> "false")
      ).resolveRelation(checkFilesExist = false))
      .select("value").as[String](Encoders.STRING)

    if (Charset.forName(options.charset) == StandardCharsets.UTF_8) {
      df
    } else {
      val charset = options.charset
      sparkSession.createDataset(df.queryExecution.toRdd.map { row =>
        val bytes = row.getBinary(0)
        new String(bytes, 0, bytes.length, charset)
      })(Encoders.STRING)
    }
  }
}

object MultiLineXmlDataSource extends XmlDataSource {
  override val isSplitable: Boolean = false

  override def readFile(
      conf: Configuration,
      file: PartitionedFile,
      parser: StaxXmlParser,
      requiredSchema: StructType): Iterator[InternalRow] = {
    parser.parseStream(
      CodecStreams.createInputStreamWithCloseResource(conf, file.toPath),
      requiredSchema)
  }

  override def infer(
      sparkSession: SparkSession,
      inputPaths: Seq[FileStatus],
      parsedOptions: XmlOptions): StructType = {
    val xml = createBaseRdd(sparkSession, inputPaths, parsedOptions)

    val tokenRDD = xml.flatMap { portableDataStream =>
      StaxXmlParser.tokenizeStream(
        CodecStreams.createInputStreamWithCloseResource(
          portableDataStream.getConfiguration,
          new Path(portableDataStream.getPath())),
        parsedOptions)
    }
    SQLExecution.withSQLConfPropagated(sparkSession) {
      val schema = XmlInferSchema.infer(
        tokenRDD,
        parsedOptions,
        sparkSession.sessionState.conf.caseSensitiveAnalysis)
      schema
    }
  }

  private def createBaseRdd(
      sparkSession: SparkSession,
      inputPaths: Seq[FileStatus],
      options: XmlOptions): RDD[PortableDataStream] = {
    val paths = inputPaths.map(_.getPath)
    val name = paths.mkString(",")
    val job = Job.getInstance(sparkSession.sessionState.newHadoopConfWithOptions(
      options.parameters))
    FileInputFormat.setInputPaths(job, paths: _*)
    val conf = job.getConfiguration

    val rdd = new BinaryFileRDD(
      sparkSession.sparkContext,
      classOf[StreamInputFormat],
      classOf[String],
      classOf[PortableDataStream],
      conf,
      sparkSession.sparkContext.defaultMinPartitions)

    // Only returns `PortableDataStream`s without paths.
    rdd.setName(s"XMLFile: $name").values
  }
}
