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

// scalastyle:off println
package org.apache.spark.examples.streaming

import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

import com.google.common.io.Files

import org.apache.spark.{Accumulator, SparkConf, SparkException}
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.{Duration, Seconds, StreamingContext, Time}
import org.apache.spark.util.{IntParam, ThreadUtils}

object RecoverableWordsCounter {

  @volatile private var instance: Accumulator[Long] = null

  def init(ssc: StreamingContext): Unit = synchronized {
    if (instance == null) {
      // NOTICE:
      //  "StreamingContext.accumulator" is recoverable from checkpoint
      //  "SparkContext.accumlator" is NOT
      instance = ssc.getOrCreateRecoverableAccumulator(0L, "WordsCounter")
    }
  }

  def getInstance(): Accumulator[Long] = if (instance == null){
    throw new SparkException("Please init WordsCounter first")
  } else instance
}

object RecoverableAccumulator {

  def createContext(ip: String, port: Int, checkpointDirectory: String)
  : StreamingContext = {

    // If you do not see this printed, that means the StreamingContext has been loaded
    // from the new checkpoint
    println("Creating new context")
    val sparkConf = new SparkConf().setAppName("RecoverableAccumulator")

    // Create the context with a 1 second batch size
    val ssc = new StreamingContext(sparkConf, Seconds(1))
    ssc.checkpoint(checkpointDirectory)

    // Create a socket stream on target ip:port and count the
    // words in input stream of \n delimited text (eg. generated by 'nc')
    val lines = ssc.socketTextStream(ip, port)
    val words = lines.flatMap(_.split(" "))
    words.foreachRDD((rdd: RDD[String], time: Time) => {
      val counter = RecoverableWordsCounter.getInstance()
      rdd.foreach(word => counter += 1)
      val output = "Counts at time " + time + ": " + counter.value + " word(s)"
      println(output)
    })
    ssc
  }

  def main(args: Array[String]) {
    if (args.length != 3) {
      System.err.println("You arguments were " + args.mkString("[", ", ", "]"))
      System.err.println(
        """
          |Usage: RecoverableAccumulator <hostname> <port> <checkpoint-directory>.
          |     <hostname> and <port> describe the TCP server that Spark
          |     Streaming would connect to receive data. <checkpoint-directory> directory to
          |     HDFS-compatible file system which checkpoint data
          |
          |In local mode, <master> should be 'local[n]' with n > 1
          |<checkpoint-directory> must be absolute path
        """.stripMargin
      )
      System.exit(1)
    }
    val Array(ip, IntParam(port), checkpointDirectory) = args
    val ssc = StreamingContext.getOrCreate(checkpointDirectory,
      () => {
        createContext(ip, port, checkpointDirectory)
      })

    // Create Accumulator out of StreamingContext creation method to make sure that accumulator
    // is initialized when recovering from Checkpoint
    RecoverableWordsCounter.init(ssc)

    ssc.start()
    ssc.awaitTermination()
  }
}
// scalastyle:on println
