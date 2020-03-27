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

package org.apache.spark.streaming.kinesis

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Random

import com.amazonaws.services.kinesis.model.Record
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import org.scalatest.Matchers._
import org.scalatest.concurrent.Eventually

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.network.util.JavaUtils
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.{StorageLevel, StreamBlockId}
import org.apache.spark.streaming.{LocalStreamingContext, _}
import org.apache.spark.streaming.dstream.ReceiverInputDStream
import org.apache.spark.streaming.kinesis.KinesisInitialPositions.Latest
import org.apache.spark.streaming.kinesis.KinesisReadConfigurations._
import org.apache.spark.streaming.kinesis.KinesisTestUtils._
import org.apache.spark.streaming.receiver.BlockManagerBasedStoreResult
import org.apache.spark.streaming.scheduler.ReceivedBlockInfo
import org.apache.spark.util.Utils

abstract class KinesisStreamTests(aggregateTestData: Boolean) extends KinesisFunSuite
  with LocalStreamingContext with Eventually with BeforeAndAfter with BeforeAndAfterAll {

  // This is the name that KCL will use to save metadata to DynamoDB
  private val appName = s"KinesisStreamSuite-${math.abs(Random.nextLong())}"
  private val batchDuration = Seconds(1)

  // Dummy parameters for API testing
  private val dummyEndpointUrl = defaultEndpointUrl
  private val dummyRegionName = KinesisTestUtils.getRegionNameByEndpoint(dummyEndpointUrl)
  private val dummyAWSAccessKey = "dummyAccessKey"
  private val dummyAWSSecretKey = "dummySecretKey"

  private var testUtils: KinesisTestUtils = null
  private var sc: SparkContext = null

  override def beforeAll(): Unit = {
    runIfTestsEnabled("Prepare KinesisTestUtils") {
      testUtils = new KPLBasedKinesisTestUtils()
      testUtils.createStream()
    }
  }

  override def afterAll(): Unit = {
    try {
      if (testUtils != null) {
        // Delete the Kinesis stream as well as the DynamoDB table generated by
        // Kinesis Client Library when consuming the stream
        testUtils.deleteStream()
        testUtils.deleteDynamoDBTable(appName)
      }
    } finally {
      super.afterAll()
    }
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    val conf = new SparkConf()
      .setMaster("local[4]")
      .setAppName("KinesisStreamSuite") // Setting Spark app name to Kinesis app name
    sc = new SparkContext(conf)
    ssc = new StreamingContext(sc, batchDuration)
  }

  override def afterEach(): Unit = {
    try {
      if (testUtils != null) {
        testUtils.deleteDynamoDBTable(appName)
      }
    } finally {
      super.afterEach()
    }
  }

  test("RDD generation") {
    val inputStream = KinesisInputDStream.builder.
      streamingContext(ssc).
      checkpointAppName(appName).
      streamName("dummyStream").
      endpointUrl(dummyEndpointUrl).
      regionName(dummyRegionName).initialPosition(new Latest()).
      checkpointInterval(Seconds(2)).
      storageLevel(StorageLevel.MEMORY_AND_DISK_2).
      kinesisCredentials(BasicCredentials(dummyAWSAccessKey, dummyAWSSecretKey)).
      build()
    assert(inputStream.isInstanceOf[KinesisInputDStream[Array[Byte]]])

    val kinesisStream = inputStream.asInstanceOf[KinesisInputDStream[Array[Byte]]]
    val time = Time(1000)

    // Generate block info data for testing
    val seqNumRanges1 = SequenceNumberRanges(
      SequenceNumberRange("fakeStream", "fakeShardId", "xxx", "yyy", 67))
    val blockId1 = StreamBlockId(kinesisStream.id, 123)
    val blockInfo1 = ReceivedBlockInfo(
      0, None, Some(seqNumRanges1), new BlockManagerBasedStoreResult(blockId1, None))

    val seqNumRanges2 = SequenceNumberRanges(
      SequenceNumberRange("fakeStream", "fakeShardId", "aaa", "bbb", 89))
    val blockId2 = StreamBlockId(kinesisStream.id, 345)
    val blockInfo2 = ReceivedBlockInfo(
      0, None, Some(seqNumRanges2), new BlockManagerBasedStoreResult(blockId2, None))

    // Verify that the generated KinesisBackedBlockRDD has the all the right information
    val blockInfos = Seq(blockInfo1, blockInfo2)
    val nonEmptyRDD = kinesisStream.createBlockRDD(time, blockInfos)
    nonEmptyRDD shouldBe a [KinesisBackedBlockRDD[_]]
    val kinesisRDD = nonEmptyRDD.asInstanceOf[KinesisBackedBlockRDD[_]]
    assert(kinesisRDD.regionName === dummyRegionName)
    assert(kinesisRDD.endpointUrl === dummyEndpointUrl)
    assert(kinesisRDD.kinesisReadConfigs.retryTimeoutMs === batchDuration.milliseconds)
    assert(kinesisRDD.kinesisCreds === BasicCredentials(
      awsAccessKeyId = dummyAWSAccessKey,
      awsSecretKey = dummyAWSSecretKey))
    assert(nonEmptyRDD.partitions.size === blockInfos.size)
    nonEmptyRDD.partitions.foreach { _ shouldBe a [KinesisBackedBlockRDDPartition] }
    val partitions = nonEmptyRDD.partitions.map {
      _.asInstanceOf[KinesisBackedBlockRDDPartition] }.toSeq
    assert(partitions.map { _.seqNumberRanges } === Seq(seqNumRanges1, seqNumRanges2))
    assert(partitions.map { _.blockId } === Seq(blockId1, blockId2))
    assert(partitions.forall { _.isBlockIdValid })

    // Verify that KinesisBackedBlockRDD is generated even when there are no blocks
    val emptyRDD = kinesisStream.createBlockRDD(time, Seq.empty)
    // Verify it's KinesisBackedBlockRDD[_] rather than KinesisBackedBlockRDD[Array[Byte]], because
    // the type parameter will be erased at runtime
    emptyRDD shouldBe a [KinesisBackedBlockRDD[_]]
    emptyRDD.partitions shouldBe empty

    // Verify that the KinesisBackedBlockRDD has isBlockValid = false when blocks are invalid
    blockInfos.foreach { _.setBlockIdInvalid() }
    kinesisStream.createBlockRDD(time, blockInfos).partitions.foreach { partition =>
      assert(partition.asInstanceOf[KinesisBackedBlockRDDPartition].isBlockIdValid === false)
    }
  }


  /**
   * Test the stream by sending data to a Kinesis stream and receiving from it.
   * This test is not run by default as it requires AWS credentials that the test
   * environment may not have. Even if there is AWS credentials available, the user
   * may not want to run these tests to avoid the Kinesis costs. To enable this test,
   * you must have AWS credentials available through the default AWS provider chain,
   * and you have to set the system environment variable RUN_KINESIS_TESTS=1 .
   */
  testIfEnabled("basic operation") {
    val stream = KinesisInputDStream.builder.streamingContext(ssc)
      .checkpointAppName(appName)
      .streamName(testUtils.streamName)
      .endpointUrl(testUtils.endpointUrl)
      .regionName(testUtils.regionName)
      .initialPosition(new Latest())
      .checkpointInterval(Seconds(10))
      .storageLevel(StorageLevel.MEMORY_ONLY)
      .build()

    val collected = new mutable.HashSet[Int]
    stream.map { bytes => new String(bytes).toInt }.foreachRDD { rdd =>
      collected.synchronized {
        collected ++= rdd.collect()
        logInfo("Collected = " + collected.mkString(", "))
      }
    }
    ssc.start()

    val testData = 1 to 10
    eventually(timeout(2.minutes), interval(10.seconds)) {
      testUtils.pushData(testData, aggregateTestData)
      collected.synchronized {
        assert(collected === testData.toSet, "\nData received does not match data sent")
      }
    }
    ssc.stop(stopSparkContext = false)
  }

  testIfEnabled("custom message handling") {
    def addFive(r: Record): Int = JavaUtils.bytesToString(r.getData).toInt + 5

    val stream = KinesisInputDStream.builder.streamingContext(ssc)
      .checkpointAppName(appName)
      .streamName(testUtils.streamName)
      .endpointUrl(testUtils.endpointUrl)
      .regionName(testUtils.regionName)
      .initialPosition(new Latest())
      .checkpointInterval(Seconds(10))
      .storageLevel(StorageLevel.MEMORY_ONLY)
      .buildWithMessageHandler(addFive)

    stream shouldBe a [ReceiverInputDStream[_]]

    val collected = new mutable.HashSet[Int]
    stream.foreachRDD { rdd =>
      collected.synchronized {
        collected ++= rdd.collect()
        logInfo("Collected = " + collected.mkString(", "))
      }
    }
    ssc.start()

    val testData = 1 to 10
    eventually(timeout(2.minutes), interval(10.seconds)) {
      testUtils.pushData(testData, aggregateTestData)
      val modData = testData.map(_ + 5)
      collected.synchronized {
        assert(collected === modData.toSet, "\nData received does not match data sent")
      }
    }
    ssc.stop(stopSparkContext = false)
  }

  test("Kinesis read with custom configurations") {
    try {
      ssc.sc.conf.set(RETRY_WAIT_TIME_KEY, "2000ms")
      ssc.sc.conf.set(RETRY_MAX_ATTEMPTS_KEY, "5")

      val kinesisStream = KinesisInputDStream.builder.streamingContext(ssc)
      .checkpointAppName(appName)
      .streamName("dummyStream")
      .endpointUrl(dummyEndpointUrl)
      .regionName(dummyRegionName)
      .initialPosition(new Latest())
      .checkpointInterval(Seconds(10))
      .storageLevel(StorageLevel.MEMORY_ONLY)
      .build()
      .asInstanceOf[KinesisInputDStream[Array[Byte]]]

      val time = Time(1000)
      // Generate block info data for testing
      val seqNumRanges1 = SequenceNumberRanges(
        SequenceNumberRange("fakeStream", "fakeShardId", "xxx", "yyy", 67))
      val blockId1 = StreamBlockId(kinesisStream.id, 123)
      val blockInfo1 = ReceivedBlockInfo(
        0, None, Some(seqNumRanges1), new BlockManagerBasedStoreResult(blockId1, None))

      val seqNumRanges2 = SequenceNumberRanges(
        SequenceNumberRange("fakeStream", "fakeShardId", "aaa", "bbb", 89))
      val blockId2 = StreamBlockId(kinesisStream.id, 345)
      val blockInfo2 = ReceivedBlockInfo(
        0, None, Some(seqNumRanges2), new BlockManagerBasedStoreResult(blockId2, None))

      // Verify that the generated KinesisBackedBlockRDD has the all the right information
      val blockInfos = Seq(blockInfo1, blockInfo2)

      val kinesisRDD =
        kinesisStream.createBlockRDD(time, blockInfos).asInstanceOf[KinesisBackedBlockRDD[_]]

      assert(kinesisRDD.kinesisReadConfigs.retryWaitTimeMs === 2000)
      assert(kinesisRDD.kinesisReadConfigs.maxRetries === 5)
      assert(kinesisRDD.kinesisReadConfigs.retryTimeoutMs === batchDuration.milliseconds)
    } finally {
      ssc.sc.conf.remove(RETRY_WAIT_TIME_KEY)
      ssc.sc.conf.remove(RETRY_MAX_ATTEMPTS_KEY)
      ssc.stop(stopSparkContext = false)
    }
  }

  testIfEnabled("split and merge shards in a stream") {
    // Since this test tries to split and merge shards in a stream, we create another
    // temporary stream and then remove it when finished.
    val localAppName = s"KinesisStreamSuite-${math.abs(Random.nextLong())}"
    val localTestUtils = new KPLBasedKinesisTestUtils(1)
    localTestUtils.createStream()
    try {
      val stream = KinesisInputDStream.builder.streamingContext(ssc)
        .checkpointAppName(localAppName)
        .streamName(localTestUtils.streamName)
        .endpointUrl(localTestUtils.endpointUrl)
        .regionName(localTestUtils.regionName)
        .initialPosition(new Latest())
        .checkpointInterval(Seconds(10))
        .storageLevel(StorageLevel.MEMORY_ONLY)
        .build()

      val collected = new mutable.HashSet[Int]
      stream.map { bytes => new String(bytes).toInt }.foreachRDD { rdd =>
        collected.synchronized {
          collected ++= rdd.collect()
          logInfo("Collected = " + collected.mkString(", "))
        }
      }
      ssc.start()

      val testData1 = 1 to 10
      val testData2 = 11 to 20
      val testData3 = 21 to 30

      eventually(timeout(1.minute), interval(10.seconds)) {
        localTestUtils.pushData(testData1, aggregateTestData)
        collected.synchronized {
          assert(collected === testData1.toSet, "\nData received does not match data sent")
        }
      }

      val shardToSplit = localTestUtils.getShards().head
      localTestUtils.splitShard(shardToSplit.getShardId)
      val (splitOpenShards, splitCloseShards) = localTestUtils.getShards().partition { shard =>
        shard.getSequenceNumberRange.getEndingSequenceNumber == null
      }

      // We should have one closed shard and two open shards
      assert(splitCloseShards.size == 1)
      assert(splitOpenShards.size == 2)

      eventually(timeout(1.minute), interval(10.seconds)) {
        localTestUtils.pushData(testData2, aggregateTestData)
        collected.synchronized {
          assert(collected === (testData1 ++ testData2).toSet,
            "\nData received does not match data sent after splitting a shard")
        }
      }

      val Seq(shardToMerge, adjShard) = splitOpenShards
      localTestUtils.mergeShard(shardToMerge.getShardId, adjShard.getShardId)
      val (mergedOpenShards, mergedCloseShards) = localTestUtils.getShards().partition { shard =>
        shard.getSequenceNumberRange.getEndingSequenceNumber == null
      }

      // We should have three closed shards and one open shard
      assert(mergedCloseShards.size == 3)
      assert(mergedOpenShards.size == 1)

      eventually(timeout(1.minute), interval(10.seconds)) {
        localTestUtils.pushData(testData3, aggregateTestData)
        collected.synchronized {
          assert(collected === (testData1 ++ testData2 ++ testData3).toSet,
            "\nData received does not match data sent after merging shards")
        }
      }
    } finally {
      ssc.stop(stopSparkContext = false)
      localTestUtils.deleteStream()
      localTestUtils.deleteDynamoDBTable(localAppName)
    }
  }

  testIfEnabled("failure recovery") {
    val sparkConf = new SparkConf().setMaster("local[4]").setAppName(this.getClass.getSimpleName)
    val checkpointDir = Utils.createTempDir().getAbsolutePath

    ssc = new StreamingContext(sc, Milliseconds(1000))
    ssc.checkpoint(checkpointDir)

    val collectedData = new mutable.HashMap[Time, (Array[SequenceNumberRanges], Seq[Int])]

    val kinesisStream = KinesisInputDStream.builder.streamingContext(ssc)
      .checkpointAppName(appName)
      .streamName(testUtils.streamName)
      .endpointUrl(testUtils.endpointUrl)
      .regionName(testUtils.regionName)
      .initialPosition(new Latest())
      .checkpointInterval(Seconds(10))
      .storageLevel(StorageLevel.MEMORY_ONLY)
      .build()

    // Verify that the generated RDDs are KinesisBackedBlockRDDs, and collect the data in each batch
    kinesisStream.foreachRDD((rdd: RDD[Array[Byte]], time: Time) => {
      val kRdd = rdd.asInstanceOf[KinesisBackedBlockRDD[Array[Byte]]]
      val data = rdd.map { bytes => new String(bytes).toInt }.collect().toSeq
      collectedData.synchronized {
        collectedData(time) = (kRdd.arrayOfseqNumberRanges, data)
      }
    })

    ssc.remember(Minutes(60)) // remember all the batches so that they are all saved in checkpoint
    ssc.start()

    def numBatchesWithData: Int =
      collectedData.synchronized { collectedData.count(_._2._2.nonEmpty) }

    def isCheckpointPresent: Boolean = Checkpoint.getCheckpointFiles(checkpointDir).nonEmpty

    // Run until there are at least 10 batches with some data in them
    // If this times out because numBatchesWithData is empty, then its likely that foreachRDD
    // function failed with exceptions, and nothing got added to `collectedData`
    eventually(timeout(2.minutes), interval(1.second)) {
      testUtils.pushData(1 to 5, aggregateTestData)
      assert(isCheckpointPresent && numBatchesWithData > 10)
    }
    ssc.stop(stopSparkContext = true)  // stop the SparkContext so that the blocks are not reused

    // Restart the context from checkpoint and verify whether the
    logInfo("Restarting from checkpoint")
    ssc = new StreamingContext(checkpointDir)
    ssc.start()
    val recoveredKinesisStream = ssc.graph.getInputStreams().head

    // Verify that the recomputed RDDs are KinesisBackedBlockRDDs with the same sequence ranges
    // and return the same data
    collectedData.synchronized {
      val times = collectedData.keySet
      times.foreach { time =>
        val (arrayOfSeqNumRanges, data) = collectedData(time)
        val rdd = recoveredKinesisStream.getOrCompute(time).get.asInstanceOf[RDD[Array[Byte]]]
        rdd shouldBe a[KinesisBackedBlockRDD[_]]

        // Verify the recovered sequence ranges
        val kRdd = rdd.asInstanceOf[KinesisBackedBlockRDD[Array[Byte]]]
        assert(kRdd.arrayOfseqNumberRanges.size === arrayOfSeqNumRanges.size)
        arrayOfSeqNumRanges.zip(kRdd.arrayOfseqNumberRanges).foreach { case (expected, found) =>
          assert(expected.ranges.toSeq === found.ranges.toSeq)
        }

        // Verify the recovered data
        assert(rdd.map { bytes => new String(bytes).toInt }.collect().toSeq === data)
      }
    }
    ssc.stop()
  }
}

class WithAggregationKinesisStreamSuite extends KinesisStreamTests(aggregateTestData = true)

class WithoutAggregationKinesisStreamSuite extends KinesisStreamTests(aggregateTestData = false)
