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

import java.util.Properties

import org.scalatest.time.SpanSugar._
import scala.collection.mutable
import scala.util.Random

import org.apache.spark.SparkContext
import org.apache.spark.sql.ForeachWriter
import org.apache.spark.sql.execution.streaming.continuous.ContinuousExecution
import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.sql.test.TestSparkSession

class ContinuousKafkaSuite extends KafkaSourceTest {
  import testImplicits._

  // We need more than the default local[2] to be able to schedule all partitions simultaneously.
  override protected def createSparkSession = new TestSparkSession(
    new SparkContext(
      "local[10]",
      "continuous-stream-test-sql-context",
      sparkConf.set("spark.sql.testkey", "true")))

  test("basic") {
    val topic = newTopic()
    testUtils.createTopic(topic, partitions = 5)
    testUtils.sendMessages(topic, Array("0"))
    require(testUtils.getLatestOffsets(Set(topic)).size === 5)

    val reader = spark
      .readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", testUtils.brokerAddress)
      .option("subscribe", topic)

    val kafka = reader.load()
      .selectExpr("CAST(value AS STRING)")
      .as[String]
      .map(_.toInt + 1)

    testStream(kafka, useV2Sink = true)(
      StartStream(Trigger.Continuous(100)),
      AddKafkaData(Set(topic), 1, 2, 3),
      CheckAnswer(2, 3, 4)
    )
  }

  test("add partitions") {
    val topic = newTopic()
    testUtils.createTopic(topic, partitions = 2)
    testUtils.sendMessages(topic, Array("0"))
    require(testUtils.getLatestOffsets(Set(topic)).size === 2)

    val reader = spark
      .readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", testUtils.brokerAddress)
      .option("subscribe", topic)
      .option("kafka.metadata.max.age.ms", "100")

    val kafka = reader.load()
      .selectExpr("CAST(value AS STRING)")
      .as[String]
      .map(_.toInt + 1)

    testStream(kafka, useV2Sink = true)(
      StartStream(Trigger.Continuous(1000)),
      AddKafkaData(Set(topic), scala.Range(0, 5): _*),
      CheckAnswer(1, 2, 3, 4, 5),
      Execute(_ => testUtils.addPartitions(topic, 5)),
      AddKafkaData(Set(topic), scala.Range(10, 20): _*),
      CheckAnswer(1, 2, 3, 4, 5, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20)
    )
  }
}

class ContinuousKafkaStressSuite extends KafkaSourceTest {
  import testImplicits._

  // We need more than the default local[2] to be able to schedule all partitions simultaneously.
  override protected def createSparkSession = new TestSparkSession(
    new SparkContext(
      "local[10]",
      "continuous-stream-test-sql-context",
      sparkConf.set("spark.sql.testkey", "true")))

  override def beforeAll(): Unit = {
    super.beforeAll()
    testUtils = new KafkaTestUtils {
      override def brokerConfiguration: Properties = {
        val props = super.brokerConfiguration
        // Try to make Kafka clean up messages as fast as possible. However, there is a hard-code
        // 30 seconds delay (kafka.log.LogManager.InitialTaskDelayMs) so this test should run at
        // least 30 seconds.
        props.put("log.cleaner.backoff.ms", "100")
        props.put("log.segment.bytes", "40")
        props.put("log.retention.bytes", "40")
        props.put("log.retention.check.interval.ms", "100")
        props.put("delete.retention.ms", "10")
        props.put("log.flush.scheduler.interval.ms", "10")
        props
      }
    }
    testUtils.setup()
  }

  override def afterAll(): Unit = {
    if (testUtils != null) {
      testUtils.teardown()
      testUtils = null
      super.afterAll()
    }
  }

  test("stress test for failOnDataLoss=false") {
    val reader = spark
      .readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", testUtils.brokerAddress)
      .option("kafka.metadata.max.age.ms", "1")
      .option("subscribePattern", "failOnDataLoss.*")
      .option("startingOffsets", "earliest")
      .option("failOnDataLoss", "false")
      .option("fetchOffset.retryIntervalMs", "3000")
    val kafka = reader.load()
      .selectExpr("CAST(key AS STRING)", "CAST(value AS STRING)")
      .as[(String, String)]
    val query = kafka.map(kv => kv._2.toInt).writeStream.foreach(new ForeachWriter[Int] {

      override def open(partitionId: Long, version: Long): Boolean = {
        true
      }

      override def process(value: Int): Unit = {
        // Slow down the processing speed so that messages may be aged out.
        Thread.sleep(Random.nextInt(500))
      }

      override def close(errorOrNull: Throwable): Unit = {
      }
    }).start()

    val testTime = 1.minutes
    val startTime = System.currentTimeMillis()
    // Track the current existing topics
    val topics = mutable.ArrayBuffer[String]()
    // Track topics that have been deleted
    val deletedTopics = mutable.Set[String]()
    while (System.currentTimeMillis() - testTime.toMillis < startTime) {
      Random.nextInt(10) match {
        case 0 => // Create a new topic
          val topic = newTopic()
          topics += topic
          // As pushing messages into Kafka updates Zookeeper asynchronously, there is a small
          // chance that a topic will be recreated after deletion due to the asynchronous update.
          // Hence, always overwrite to handle this race condition.
          testUtils.createTopic(topic, partitions = 1, overwrite = true)
          logInfo(s"Create topic $topic")
        case 1 if topics.nonEmpty => // Delete an existing topic
          val topic = topics.remove(Random.nextInt(topics.size))
          testUtils.deleteTopic(topic)
          logInfo(s"Delete topic $topic")
          deletedTopics += topic
        case 2 if deletedTopics.nonEmpty => // Recreate a topic that was deleted.
          val topic = deletedTopics.toSeq(Random.nextInt(deletedTopics.size))
          deletedTopics -= topic
          topics += topic
          // As pushing messages into Kafka updates Zookeeper asynchronously, there is a small
          // chance that a topic will be recreated after deletion due to the asynchronous update.
          // Hence, always overwrite to handle this race condition.
          testUtils.createTopic(topic, partitions = 1, overwrite = true)
          logInfo(s"Create topic $topic")
        case 3 =>
          Thread.sleep(1000)
        case _ => // Push random messages
          for (topic <- topics) {
            val size = Random.nextInt(10)
            for (_ <- 0 until size) {
              testUtils.sendMessages(topic, Array(Random.nextInt(10).toString))
            }
          }
      }
      // `failOnDataLoss` is `false`, we should not fail the query
      if (query.exception.nonEmpty) {
        throw query.exception.get
      }
    }

    query.stop()
    // `failOnDataLoss` is `false`, we should not fail the query
    if (query.exception.nonEmpty) {
      throw query.exception.get
    }
  }
}
