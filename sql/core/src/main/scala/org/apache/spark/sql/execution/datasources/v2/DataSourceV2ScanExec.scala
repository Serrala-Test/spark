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

package org.apache.spark.sql.execution.datasources.v2

import scala.collection.JavaConverters._

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.physical
import org.apache.spark.sql.catalyst.plans.physical.SinglePartition
import org.apache.spark.sql.execution.{ColumnarBatchScan, LeafExecNode, WholeStageCodegenExec}
import org.apache.spark.sql.execution.streaming.continuous._
import org.apache.spark.sql.sources.v2.{DataFormat, DataSourceV2}
import org.apache.spark.sql.sources.v2.reader._
import org.apache.spark.sql.sources.v2.reader.streaming.ContinuousReader

/**
 * Physical plan node for scanning data from a data source.
 */
case class DataSourceV2ScanExec(
    output: Seq[AttributeReference],
    @transient source: DataSourceV2,
    @transient options: Map[String, String],
    @transient reader: DataSourceReader)
  extends LeafExecNode with DataSourceV2StringFormat with ColumnarBatchScan {

  override def simpleString: String = "ScanV2 " + metadataString

  // TODO: unify the equal/hashCode implementation for all data source v2 query plans.
  override def equals(other: Any): Boolean = other match {
    case other: DataSourceV2ScanExec =>
      output == other.output && reader.getClass == other.reader.getClass && options == other.options
    case _ => false
  }

  override def hashCode(): Int = {
    Seq(output, source, options).hashCode()
  }

  override def outputPartitioning: physical.Partitioning = {
    if (readerFactories.length == 1) {
      SinglePartition
    } else {
      reader match {
        case s: SupportsReportPartitioning =>
          new DataSourcePartitioning(
            s.outputPartitioning(), AttributeMap(output.map(a => a -> a.name)))

        case _ => super.outputPartitioning
      }
    }
  }

  private lazy val readerFactories: Seq[DataReaderFactory] = {
    reader.createDataReaderFactories().asScala
  }

  private lazy val inputRDD: RDD[InternalRow] = reader match {
    case _: ContinuousReader =>
      EpochCoordinatorRef.get(
          sparkContext.getLocalProperty(ContinuousExecution.EPOCH_COORDINATOR_ID_KEY),
          sparkContext.env)
        .askSync[Unit](SetReaderPartitions(readerFactories.size))
      if (supportsBatch) {
        throw new IllegalArgumentException(
          "continuous stream reader does not support columnar read yet.")
      }
      new ContinuousDataSourceRDD(
        sparkContext,
        sqlContext,
        readerFactories.map(_.asInstanceOf[ContinuousDataReaderFactory]),
        reader.readSchema())
    case _ =>
      new DataSourceRDD(sparkContext, readerFactories, reader.readSchema())
  }

  override def inputRDDs(): Seq[RDD[InternalRow]] = Seq(inputRDD)

  override val supportsBatch: Boolean = {
    val formats = readerFactories.map(_.dataFormat()).distinct
    if (formats.length > 1) {
      throw new IllegalArgumentException("Currently Spark requires all the data reader " +
        "factories returned by the DataSourceReader output same data format.")
    }

    formats.nonEmpty && formats.head == DataFormat.COLUMNAR_BATCH
  }

  override protected def needsUnsafeRowConversion: Boolean = false

  override protected def doExecute(): RDD[InternalRow] = {
    if (supportsBatch) {
      WholeStageCodegenExec(this)(codegenStageId = 0).execute()
    } else {
      val numOutputRows = longMetric("numOutputRows")
      inputRDD.map { r =>
        numOutputRows += 1
        r
      }
    }
  }
}
