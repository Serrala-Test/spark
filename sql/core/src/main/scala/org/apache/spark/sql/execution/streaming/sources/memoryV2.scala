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

package org.apache.spark.sql.execution.streaming.sources

import javax.annotation.concurrent.GuardedBy

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

import org.apache.spark.internal.Logging
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.plans.logical.{LeafNode, Statistics}
import org.apache.spark.sql.catalyst.plans.logical.statsEstimation.EstimationUtils
import org.apache.spark.sql.catalyst.streaming.InternalOutputModes.{Append, Complete, Update}
import org.apache.spark.sql.execution.streaming.{MemorySinkBase, Sink}
import org.apache.spark.sql.sources.v2.{DataSourceOptions, DataSourceV2, StreamWriteSupport}
import org.apache.spark.sql.sources.v2.writer._
import org.apache.spark.sql.sources.v2.writer.streaming.StreamWriter
import org.apache.spark.sql.streaming.OutputMode
import org.apache.spark.sql.types.StructType

/**
 * A sink that stores the results in memory. This [[Sink]] is primarily intended for use in unit
 * tests and does not provide durability.
 */
class MemorySinkV2 extends DataSourceV2 with StreamWriteSupport with MemorySinkBase with Logging {
  override def createStreamWriter(
      queryId: String,
      schema: StructType,
      mode: OutputMode,
      options: DataSourceOptions): StreamWriter = {
    new MemoryStreamWriter(this, mode, options)
  }

  private case class AddedData(batchId: Long, data: Array[Row])

  /** An order list of batches that have been written to this [[Sink]]. */
  @GuardedBy("this")
  private val batches = new ArrayBuffer[AddedData]()

  /** The number of rows in this MemorySink. */
  private var numRows = 0

  /** Returns all rows that are stored in this [[Sink]]. */
  def allData: Seq[Row] = synchronized {
    batches.flatMap(_.data)
  }

  def latestBatchId: Option[Long] = synchronized {
    batches.lastOption.map(_.batchId)
  }

  def latestBatchData: Seq[Row] = synchronized {
    batches.lastOption.toSeq.flatten(_.data)
  }

  def dataSinceBatch(sinceBatchId: Long): Seq[Row] = synchronized {
    batches.filter(_.batchId > sinceBatchId).flatMap(_.data)
  }

  def toDebugString: String = synchronized {
    batches.map { case AddedData(batchId, data) =>
      val dataStr = try data.mkString(" ") catch {
        case NonFatal(e) => "[Error converting to string]"
      }
      s"$batchId: $dataStr"
    }.mkString("\n")
  }

  def write(batchId: Long, outputMode: OutputMode, newRows: Array[Row], sinkCapacity: Option[Int])
  : Unit = {
    val notCommitted = synchronized {
      latestBatchId.isEmpty || batchId > latestBatchId.get
    }
    if (notCommitted) {
      logDebug(s"Committing batch $batchId to $this")
      outputMode match {
        case Append | Update =>
          synchronized {
            var rowsToAdd = newRows
            if (sinkCapacity.isDefined) {
              val rowsRemaining = sinkCapacity.get - numRows
              rowsToAdd = truncateRowsIfNeeded(rowsToAdd, rowsRemaining, batchId)
            }
            val rows = AddedData(batchId, rowsToAdd)
            batches += rows
            numRows += rowsToAdd.length
          }

        case Complete =>
          synchronized {
            var rowsToAdd = newRows
            if (sinkCapacity.isDefined) {
              rowsToAdd = truncateRowsIfNeeded(rowsToAdd, sinkCapacity.get, batchId)
            }
            val rows = AddedData(batchId, rowsToAdd)
            batches.clear()
            batches += rows
            numRows = rowsToAdd.length
          }

        case _ =>
          throw new IllegalArgumentException(
            s"Output mode $outputMode is not supported by MemorySinkV2")
      }
    } else {
      logDebug(s"Skipping already committed batch: $batchId")
    }
  }

  def clear(): Unit = synchronized {
    batches.clear()
    numRows = 0
  }

  def truncateRowsIfNeeded(rows: Array[Row], maxRows: Int, batchId: Long): Array[Row] = {
    if (rows.length > maxRows) {
      logWarning(s"Truncating batch $batchId to $maxRows rows")
      rows.take(maxRows)
    } else {
      rows
    }
  }

  override def toString(): String = "MemorySinkV2"
}

case class MemoryWriterCommitMessage(partition: Int, data: Seq[Row]) extends WriterCommitMessage {}

class MemoryWriter(
    sink: MemorySinkV2,
    batchId: Long,
    outputMode: OutputMode,
    options: DataSourceOptions)
  extends DataSourceWriter with Logging {

  val sinkCapacity: Option[Int] = MemorySinkBase.getMemorySinkCapacity(options)

  override def createWriterFactory: MemoryWriterFactory = MemoryWriterFactory(outputMode)

  def commit(messages: Array[WriterCommitMessage]): Unit = {
    val newRows = messages.flatMap {
      case message: MemoryWriterCommitMessage => message.data
    }
    sink.write(batchId, outputMode, newRows, sinkCapacity)
  }

  override def abort(messages: Array[WriterCommitMessage]): Unit = {
    // Don't accept any of the new input.
  }
}

class MemoryStreamWriter(
    val sink: MemorySinkV2,
    outputMode: OutputMode,
    options: DataSourceOptions)
  extends StreamWriter {

  val sinkCapacity: Option[Int] = MemorySinkBase.getMemorySinkCapacity(options)

  override def createWriterFactory: MemoryWriterFactory = MemoryWriterFactory(outputMode)

  override def commit(epochId: Long, messages: Array[WriterCommitMessage]): Unit = {
    val newRows = messages.flatMap {
      case message: MemoryWriterCommitMessage => message.data
    }
    sink.write(epochId, outputMode, newRows, sinkCapacity)
  }

  override def abort(epochId: Long, messages: Array[WriterCommitMessage]): Unit = {
    // Don't accept any of the new input.
  }
}

case class MemoryWriterFactory(outputMode: OutputMode) extends DataWriterFactory[Row] {
  override def createDataWriter(
      partitionId: Int,
      attemptNumber: Int,
      epochId: Long): DataWriter[Row] = {
    new MemoryDataWriter(partitionId, outputMode)
  }
}

class MemoryDataWriter(partition: Int, outputMode: OutputMode)
  extends DataWriter[Row] with Logging {

  private val data = mutable.Buffer[Row]()

  override def write(row: Row): Unit = {
    data.append(row)
  }

  override def commit(): MemoryWriterCommitMessage = {
    val msg = MemoryWriterCommitMessage(partition, data.clone())
    data.clear()
    msg
  }

  override def abort(): Unit = {}
}


/**
 * Used to query the data that has been written into a [[MemorySinkV2]].
 */
case class MemoryPlanV2(sink: MemorySinkV2, override val output: Seq[Attribute]) extends LeafNode {
  private val sizePerRow = EstimationUtils.getSizePerRow(output)

  override def computeStats(): Statistics = Statistics(sizePerRow * sink.allData.size)
}

