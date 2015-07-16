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
import scala.language.postfixOps
import scala.util.{Try, Random}

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.InitialPositionInStream
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileSystem
import org.scalatest.concurrent.Eventually
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfter, FunSuite}

import org.apache.spark.{SparkFunSuite, SparkContext, SparkConf}
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming._
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.util.Utils

class KinesisStreamSuite extends KinesisFunSuite
  with Eventually with BeforeAndAfter with BeforeAndAfterAll {

  private val endpointUrl = "https://kinesis.us-west-2.amazonaws.com"
  private val kinesisAppName = s"KinesisStreamSuite-${math.abs(Random.nextLong())}"
  private val testUtils = new KinesisTestUtils(endpointUrl)

  private var ssc: StreamingContext = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    testUtils.createStream()
  }

  override def afterAll(): Unit = {
    testUtils.deleteStream()
  }

  before {
    testUtils.deleteDynamoDBTable(kinesisAppName)
  }

  after {
    if (ssc != null) {
      ssc.stop()
      ssc = null
    }
    testUtils.deleteDynamoDBTable(kinesisAppName)
  }

  testOrIgnore("basic operation") {
    val conf = new SparkConf()
      .setMaster("local[4]")
      .setAppName("KinesisStreamSuite") // Setting Spark app name to Kinesis app name
    ssc = new StreamingContext(conf, Milliseconds(1000))
    val credentials = KinesisTestUtils.getAWSCredentials()
    val stream = KinesisUtils.createStream(ssc, kinesisAppName, testUtils.streamName,
      testUtils.endpointUrl, testUtils.regionName, InitialPositionInStream.LATEST,
      Seconds(10), StorageLevel.MEMORY_ONLY,
      credentials.getAWSAccessKeyId, credentials.getAWSSecretKey)

    val collected = new mutable.HashSet[Int] with mutable.SynchronizedSet[Int]
    stream.map { bytes => new String(bytes).toInt }.foreachRDD { rdd =>
      collected ++= rdd.collect()
      logInfo("Collected = " + rdd.collect().toSeq.mkString(", "))
    }
    ssc.start()

    val testData = 1 to 10
    eventually(timeout(120 seconds), interval(10 second)) {
      testUtils.pushData(testData)
      assert(collected === testData.toSet, "\nData received does not match data sent")
    }
    ssc.stop()
  }
}
