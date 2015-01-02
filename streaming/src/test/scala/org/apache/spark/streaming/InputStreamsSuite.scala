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

package org.apache.spark.streaming

import java.io.{File, BufferedWriter, OutputStreamWriter}
import java.net.{InetSocketAddress, SocketException, ServerSocket}
import java.util.concurrent.{Executors, TimeUnit, ArrayBlockingQueue}
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable.{SynchronizedBuffer, ArrayBuffer, SynchronizedQueue}
import scala.language.postfixOps

import com.google.common.base.Charsets
import com.google.common.io.Files
import org.scalatest.BeforeAndAfter

import org.apache.spark.Logging
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.util.ManualClock
import org.apache.spark.util.Utils
import org.apache.spark.streaming.receiver.{ActorHelper, Receiver}
import org.apache.spark.rdd.RDD
import org.apache.hadoop.io.{Text, LongWritable}
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat
import org.apache.hadoop.fs.Path

class InputStreamsSuite extends TestSuiteBase with BeforeAndAfter {

  test("socket input stream") {
    // Start the server
    val testServer = new TestServer()
    testServer.start()

    // Set up the streaming context and input streams
    val ssc = new StreamingContext(conf, batchDuration)
    val waiter = new StreamingTestWaiter(ssc)
    val networkStream = ssc.socketTextStream(
      "localhost", testServer.port, StorageLevel.MEMORY_AND_DISK)
    val outputBuffer = new ArrayBuffer[Seq[String]] with SynchronizedBuffer[Seq[String]]
    val outputStream = new TestOutputStream(networkStream, outputBuffer)
    outputStream.register()
    ssc.start()

    // Feed data to the server to send to the network receiver
    val clock = ssc.scheduler.clock.asInstanceOf[ManualClock]
    val input = Seq(1, 2, 3, 4, 5)
    val expectedOutput: Seq[Seq[String]] = input.map(i => Seq(i.toString))
    for (i <- 0 until input.size) {
      testServer.send(input(i).toString + "\n")
      Thread.sleep(500)  // This call is to allow time for the testServer to send the data to Spark
      clock.addToTime(batchDuration.milliseconds)
    }
    waiter.waitForTotalBatchesCompleted(input.size, timeout = Durations.seconds(10))
    logInfo("Stopping server")
    testServer.stop()
    logInfo("Stopping context")
    ssc.stop()

    verifyOutput(outputBuffer, expectedOutput, useSet = false)
  }


  test("file input stream - newFilesOnly = true") {
    testFileStream(newFilesOnly = true)
  }

  test("file input stream - newFilesOnly = false") {
    testFileStream(newFilesOnly = false)
  }

  test("multi-thread receiver") {
    // set up the test receiver
    val numThreads = 10
    val numRecordsPerThread = 1000
    val numTotalRecords = numThreads * numRecordsPerThread
    val testReceiver = new MultiThreadTestReceiver(numThreads, numRecordsPerThread)
    MultiThreadTestReceiver.haveAllThreadsFinished = false

    // set up the network stream using the test receiver
    val ssc = new StreamingContext(conf, batchDuration)
    val networkStream = ssc.receiverStream[Int](testReceiver)
    val countStream = networkStream.count
    val outputBuffer = new ArrayBuffer[Seq[Long]] with SynchronizedBuffer[Seq[Long]]
    val outputStream = new TestOutputStream(countStream, outputBuffer)
    def output = outputBuffer.flatMap(x => x)
    outputStream.register()
    ssc.start()

    // Let the data from the receiver be received
    val clock = ssc.scheduler.clock.asInstanceOf[ManualClock]
    val startTime = System.currentTimeMillis()
    while((!MultiThreadTestReceiver.haveAllThreadsFinished || output.sum < numTotalRecords) &&
      System.currentTimeMillis() - startTime < 5000) {
      Thread.sleep(100)
      clock.addToTime(batchDuration.milliseconds)
    }
    Thread.sleep(1000)
    logInfo("Stopping context")
    ssc.stop()

    // Verify whether data received was as expected
    logInfo("--------------------------------")
    logInfo("output.size = " + outputBuffer.size)
    logInfo("output")
    outputBuffer.foreach(x => logInfo("[" + x.mkString(",") + "]"))
    logInfo("--------------------------------")
    assert(output.sum === numTotalRecords)
  }

  test("queue input stream - oneAtATime = true") {
    // Set up the streaming context and input streams
    val ssc = new StreamingContext(conf, batchDuration)
    val waiter = new StreamingTestWaiter(ssc)
    val queue = new SynchronizedQueue[RDD[String]]()
    val queueStream = ssc.queueStream(queue, oneAtATime = true)
    val outputBuffer = new ArrayBuffer[Seq[String]] with SynchronizedBuffer[Seq[String]]
    val outputStream = new TestOutputStream(queueStream, outputBuffer)
    def output = outputBuffer.filter(_.size > 0)
    outputStream.register()
    ssc.start()

    // Setup data queued into the stream
    val clock = ssc.scheduler.clock.asInstanceOf[ManualClock]
    val input = Seq("1", "2", "3", "4", "5")
    val expectedOutput = input.map(Seq(_))
    val inputIterator = input.toIterator
    for (i <- 0 until input.size) {
      // Enqueue more than 1 item per tick but they should dequeue one at a time
      inputIterator.take(2).foreach(i => queue += ssc.sparkContext.makeRDD(Seq(i)))
      clock.addToTime(batchDuration.milliseconds)
    }
    waiter.waitForTotalBatchesCompleted(input.size, timeout = Durations.seconds(10))

    logInfo("Stopping context")
    ssc.stop()

    verifyOutput(outputBuffer, expectedOutput, useSet = false)
  }

  test("queue input stream - oneAtATime = false") {
    // Set up the streaming context and input streams
    val ssc = new StreamingContext(conf, batchDuration)
    val waiter = new StreamingTestWaiter(ssc)
    val queue = new SynchronizedQueue[RDD[String]]()
    val queueStream = ssc.queueStream(queue, oneAtATime = false)
    val outputBuffer = new ArrayBuffer[Seq[String]] with SynchronizedBuffer[Seq[String]]
    val outputStream = new TestOutputStream(queueStream, outputBuffer)
    def output = outputBuffer.filter(_.size > 0)
    outputStream.register()
    ssc.start()

    // Setup data queued into the stream
    val clock = ssc.scheduler.clock.asInstanceOf[ManualClock]
    val input = Seq("1", "2", "3", "4", "5")
    val expectedOutput = Seq(Seq("1", "2", "3"), Seq("4", "5"))

    // Enqueue the first 3 items (one by one), they should be merged in the next batch
    val inputIterator = input.toIterator
    inputIterator.take(3).foreach(i => queue += ssc.sparkContext.makeRDD(Seq(i)))
    clock.addToTime(batchDuration.milliseconds)
    waiter.waitForTotalBatchesCompleted(1, timeout = Durations.seconds(10))

    // Enqueue the remaining items (again one by one), merged in the final batch
    inputIterator.foreach(i => queue += ssc.sparkContext.makeRDD(Seq(i)))
    clock.addToTime(batchDuration.milliseconds)
    waiter.waitForTotalBatchesCompleted(2, timeout = Durations.seconds(10))
    logInfo("Stopping context")
    ssc.stop()

    verifyOutput(outputBuffer, expectedOutput, useSet = false)
  }

  def testFileStream(newFilesOnly: Boolean) {
    var ssc: StreamingContext = null
    val testDir: File = null
    try {
      val testDir = Utils.createTempDir()
      // Create a file that exists before the StreamingContext is created
      val existingFile = new File(testDir, "0")
      Files.write("0\n", existingFile, Charsets.UTF_8)
      assert(existingFile.setLastModified(10000))

      // Set up the streaming context and input streams
      ssc = new StreamingContext(conf, batchDuration)
      val clock = ssc.scheduler.clock.asInstanceOf[ManualClock]
      // This `setTime` call ensures that the clock is past the creation time of `existingFile`
      clock.setTime(10000 + 1000)
      val waiter = new StreamingTestWaiter(ssc)
      val fileStream = ssc.fileStream[LongWritable, Text, TextInputFormat](
        testDir.toString, (x: Path) => true, newFilesOnly = newFilesOnly).map(_._2.toString)
      val outputBuffer = new ArrayBuffer[Seq[String]] with SynchronizedBuffer[Seq[String]]
      val outputStream = new TestOutputStream(fileStream, outputBuffer)
      outputStream.register()
      ssc.start()

      // Over time, create files in the directory
      val input = Seq(1, 2, 3, 4, 5)
      input.foreach { i =>
        clock.addToTime(batchDuration.milliseconds)
        val file = new File(testDir, i.toString)
        Files.write(i + "\n", file, Charsets.UTF_8)
        assert(file.setLastModified(clock.currentTime()))
        logInfo("Created file " + file)
        waiter.waitForTotalBatchesCompleted(i, timeout = Durations.seconds(10))
      }

      // Verify that all the files have been read
      val expectedOutput = if (newFilesOnly) {
        input.map(_.toString).toSet
      } else {
        (Seq(0) ++ input).map(_.toString).toSet
      }
      assert(outputBuffer.flatten.toSet === expectedOutput)
    } finally {
      if (ssc != null) ssc.stop()
      if (testDir != null) Utils.deleteRecursively(testDir)
    }
  }
}


/** This is a server to test the network input stream */
class TestServer(portToBind: Int = 0) extends Logging {

  val queue = new ArrayBlockingQueue[String](100)

  val serverSocket = new ServerSocket(portToBind)

  val servingThread = new Thread() {
    override def run() {
      try {
        while(true) {
          logInfo("Accepting connections on port " + port)
          val clientSocket = serverSocket.accept()
          logInfo("New connection")
          try {
            clientSocket.setTcpNoDelay(true)
            val outputStream = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream))

            while(clientSocket.isConnected) {
              val msg = queue.poll(100, TimeUnit.MILLISECONDS)
              if (msg != null) {
                outputStream.write(msg)
                outputStream.flush()
                logInfo("Message '" + msg + "' sent")
              }
            }
          } catch {
            case e: SocketException => logError("TestServer error", e)
          } finally {
            logInfo("Connection closed")
            if (!clientSocket.isClosed) clientSocket.close()
          }
        }
      } catch {
        case ie: InterruptedException =>

      } finally {
        serverSocket.close()
      }
    }
  }

  def start() { servingThread.start() }

  def send(msg: String) { queue.put(msg) }

  def stop() { servingThread.interrupt() }

  def port = serverSocket.getLocalPort
}

/** This is a receiver to test multiple threads inserting data using block generator */
class MultiThreadTestReceiver(numThreads: Int, numRecordsPerThread: Int)
  extends Receiver[Int](StorageLevel.MEMORY_ONLY_SER) with Logging {
  lazy val executorPool = Executors.newFixedThreadPool(numThreads)
  lazy val finishCount = new AtomicInteger(0)

  def onStart() {
    (1 to numThreads).map(threadId => {
      val runnable = new Runnable {
        def run() {
          (1 to numRecordsPerThread).foreach(i =>
            store(threadId * numRecordsPerThread + i) )
          if (finishCount.incrementAndGet == numThreads) {
            MultiThreadTestReceiver.haveAllThreadsFinished = true
          }
          logInfo("Finished thread " + threadId)
        }
      }
      executorPool.submit(runnable)
    })
  }

  def onStop() {
    executorPool.shutdown()
  }
}

object MultiThreadTestReceiver {
  var haveAllThreadsFinished = false
}
