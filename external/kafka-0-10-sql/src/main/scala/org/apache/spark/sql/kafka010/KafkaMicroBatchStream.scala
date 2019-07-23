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

package org.apache.spark.sql.kafka010

import java.{util => ju}
import java.io._
import java.nio.charset.StandardCharsets

import org.apache.commons.io.IOUtils
import org.apache.kafka.clients.consumer.ConsumerConfig

import org.apache.spark.SparkEnv
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config.Network.NETWORK_TIMEOUT
import org.apache.spark.scheduler.ExecutorCacheTaskLocation
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.UnsafeRow
import org.apache.spark.sql.execution.streaming.{HDFSMetadataLog, SerializedOffset}
import org.apache.spark.sql.execution.streaming.sources.RateControlMicroBatchStream
import org.apache.spark.sql.kafka010.KafkaSourceProvider.{INSTRUCTION_FOR_FAIL_ON_DATA_LOSS_FALSE, INSTRUCTION_FOR_FAIL_ON_DATA_LOSS_TRUE}
import org.apache.spark.sql.sources.v2.reader._
import org.apache.spark.sql.sources.v2.reader.streaming.{MicroBatchStream, Offset}
import org.apache.spark.sql.util.CaseInsensitiveStringMap
import org.apache.spark.util.UninterruptibleThread

/**
 * A [[MicroBatchStream]] that reads data from Kafka.
 *
 * The [[KafkaSourceOffset]] is the custom [[Offset]] defined for this source that contains
 * a map of TopicPartition -> offset. Note that this offset is 1 + (available offset). For
 * example if the last record in a Kafka topic "t", partition 2 is offset 5, then
 * KafkaSourceOffset will contain TopicPartition("t", 2) -> 6. This is done keep it consistent
 * with the semantics of `KafkaConsumer.position()`.
 *
 * Zero data lost is not guaranteed when topics are deleted. If zero data lost is critical, the user
 * must make sure all messages in a topic have been processed when deleting a topic.
 *
 * There is a known issue caused by KAFKA-1894: the query using Kafka maybe cannot be stopped.
 * To avoid this issue, you should make sure stopping the query before stopping the Kafka brokers
 * and not use wrong broker addresses.
 */
private[kafka010] class KafkaMicroBatchStream(
    kafkaOffsetReader: KafkaOffsetReader,
    executorKafkaParams: ju.Map[String, Object],
    options: CaseInsensitiveStringMap,
    metadataPath: String,
    startingOffsets: KafkaOffsetRangeLimit,
    failOnDataLoss: Boolean) extends RateControlMicroBatchStream with Logging {

  private val pollTimeoutMs = options.getLong(
    KafkaSourceProvider.CONSUMER_POLL_TIMEOUT,
    SparkEnv.get.conf.get(NETWORK_TIMEOUT) * 1000L)

  private val maxOffsetsPerTrigger = Option(options.get(KafkaSourceProvider.MAX_OFFSET_PER_TRIGGER))
    .map(_.toLong)

  private var endPartitionOffsets: KafkaSourceOffset = _

  /**
   * Lazily initialize `initialPartitionOffsets` to make sure that `KafkaConsumer.poll` is only
   * called in StreamExecutionThread. Otherwise, interrupting a thread while running
   * `KafkaConsumer.poll` may hang forever (KAFKA-1894).
   */
  override def initialOffset(): Offset = {
    KafkaSourceOffset(getOrCreateInitialPartitionOffsets())
  }

  override def latestOffset(start: Offset): Offset = {
    val startPartitionOffsets = start.asInstanceOf[KafkaSourceOffset].partitionToOffsets
    val latestPartitionOffsets = kafkaOffsetReader.fetchLatestOffsets(Some(startPartitionOffsets))
    endPartitionOffsets = KafkaSourceOffset(maxOffsetsPerTrigger.map { maxOffsets =>
      rateLimit(maxOffsets, startPartitionOffsets, latestPartitionOffsets)
    }.getOrElse {
      latestPartitionOffsets
    })
    endPartitionOffsets
  }

  override def planInputPartitions(start: Offset, end: Offset): Array[InputPartition] = {
    val startPartitionOffsets = start.asInstanceOf[KafkaSourceOffset].partitionToOffsets
    val endPartitionOffsets = end.asInstanceOf[KafkaSourceOffset].partitionToOffsets

    val offsetRanges = kafkaOffsetReader.getOffsetRangesFromResolvedOffsets(
      startPartitionOffsets,
      endPartitionOffsets,
      reportDataLoss
    )

    // Reuse Kafka consumers only when all the offset ranges have distinct TopicPartitions,
    // that is, concurrent tasks will not read the same TopicPartitions.
    val reuseKafkaConsumer = offsetRanges.map(_.topicPartition).toSet.size == offsetRanges.size

    // Generate factories based on the offset ranges
    offsetRanges.map { range =>
      KafkaBatchInputPartition(
        range, executorKafkaParams, pollTimeoutMs, failOnDataLoss, reuseKafkaConsumer)
    }.toArray
  }

  override def createReaderFactory(): PartitionReaderFactory = {
    KafkaBatchReaderFactory
  }

  override def deserializeOffset(json: String): Offset = {
    KafkaSourceOffset(JsonUtils.partitionOffsets(json))
  }

  override def commit(end: Offset): Unit = {}

  override def stop(): Unit = {
    kafkaOffsetReader.close()
  }

  override def toString(): String = s"KafkaV2[$kafkaOffsetReader]"

  /**
   * Read initial partition offsets from the checkpoint, or decide the offsets and write them to
   * the checkpoint.
   */
  private def getOrCreateInitialPartitionOffsets(): PartitionOffsetMap = {
    // Make sure that `KafkaConsumer.poll` is only called in StreamExecutionThread.
    // Otherwise, interrupting a thread while running `KafkaConsumer.poll` may hang forever
    // (KAFKA-1894).
    assert(Thread.currentThread().isInstanceOf[UninterruptibleThread])

    // SparkSession is required for getting Hadoop configuration for writing to checkpoints
    assert(SparkSession.getActiveSession.nonEmpty)

    val metadataLog =
      new KafkaSourceInitialOffsetWriter(SparkSession.getActiveSession.get, metadataPath)
    metadataLog.get(0).getOrElse {
      val offsets = startingOffsets match {
        case EarliestOffsetRangeLimit =>
          KafkaSourceOffset(kafkaOffsetReader.fetchEarliestOffsets())
        case LatestOffsetRangeLimit =>
          KafkaSourceOffset(kafkaOffsetReader.fetchLatestOffsets(None))
        case SpecificOffsetRangeLimit(p) =>
          kafkaOffsetReader.fetchSpecificOffsets(p, reportDataLoss)
      }
      metadataLog.add(0, offsets)
      logInfo(s"Initial offsets: $offsets")
      offsets
    }.partitionToOffsets
  }

  /** Proportionally distribute limit number of offsets among topicpartitions */
  private def rateLimit(
      limit: Long,
      from: PartitionOffsetMap,
      until: PartitionOffsetMap): PartitionOffsetMap = {
    val fromNew = kafkaOffsetReader.fetchEarliestOffsets(until.keySet.diff(from.keySet).toSeq)
    val sizes = until.flatMap {
      case (tp, end) =>
        // If begin isn't defined, something's wrong, but let alert logic in getBatch handle it
        from.get(tp).orElse(fromNew.get(tp)).flatMap { begin =>
          val size = end - begin
          logDebug(s"rateLimit $tp size is $size")
          if (size > 0) Some(tp -> size) else None
        }
    }
    val total = sizes.values.sum.toDouble
    if (total < 1) {
      until
    } else {
      until.map {
        case (tp, end) =>
          tp -> sizes.get(tp).map { size =>
            val begin = from.getOrElse(tp, fromNew(tp))
            val prorate = limit * (size / total)
            // Don't completely starve small topicpartitions
            val prorateLong = (if (prorate < 1) Math.ceil(prorate) else Math.floor(prorate)).toLong
            // need to be careful of integer overflow
            // therefore added canary checks where to see if off variable could be overflowed
            // refer to [https://issues.apache.org/jira/browse/SPARK-26718]
            val off = if (prorateLong > Long.MaxValue - begin) {
              Long.MaxValue
            } else {
              begin + prorateLong
            }
            // Paranoia, make sure not to return an offset that's past end
            Math.min(end, off)
          }.getOrElse(end)
      }
    }
  }

  /**
   * If `failOnDataLoss` is true, this method will throw an `IllegalStateException`.
   * Otherwise, just log a warning.
   */
  private def reportDataLoss(message: String): Unit = {
    if (failOnDataLoss) {
      throw new IllegalStateException(message + s". $INSTRUCTION_FOR_FAIL_ON_DATA_LOSS_TRUE")
    } else {
      logWarning(message + s". $INSTRUCTION_FOR_FAIL_ON_DATA_LOSS_FALSE")
    }
  }

  /** A version of [[HDFSMetadataLog]] specialized for saving the initial offsets. */
  class KafkaSourceInitialOffsetWriter(sparkSession: SparkSession, metadataPath: String)
    extends HDFSMetadataLog[KafkaSourceOffset](sparkSession, metadataPath) {

    val VERSION = 1

    override def serialize(metadata: KafkaSourceOffset, out: OutputStream): Unit = {
      out.write(0) // A zero byte is written to support Spark 2.1.0 (SPARK-19517)
      val writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))
      writer.write("v" + VERSION + "\n")
      writer.write(metadata.json)
      writer.flush
    }

    override def deserialize(in: InputStream): KafkaSourceOffset = {
      in.read() // A zero byte is read to support Spark 2.1.0 (SPARK-19517)
      val content = IOUtils.toString(new InputStreamReader(in, StandardCharsets.UTF_8))
      // HDFSMetadataLog guarantees that it never creates a partial file.
      assert(content.length != 0)
      if (content(0) == 'v') {
        val indexOfNewLine = content.indexOf("\n")
        if (indexOfNewLine > 0) {
          validateVersion(content.substring(0, indexOfNewLine), VERSION)
          KafkaSourceOffset(SerializedOffset(content.substring(indexOfNewLine + 1)))
        } else {
          throw new IllegalStateException(
            s"Log file was malformed: failed to detect the log file version line.")
        }
      } else {
        // The log was generated by Spark 2.1.0
        KafkaSourceOffset(SerializedOffset(content))
      }
    }
  }
}
