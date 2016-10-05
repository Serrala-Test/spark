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

package org.apache.spark.sql.streaming

import java.io.File

import scala.collection.mutable

import org.scalactic.TolerantNumerics
import org.scalatest.BeforeAndAfter
import org.scalatest.PrivateMethodTester._
import org.scalatest.concurrent.AsyncAssertions.Waiter
import org.scalatest.concurrent.Eventually._
import org.scalatest.concurrent.PatienceConfiguration.Timeout

import org.apache.spark.SparkException
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.catalyst.util._
import org.apache.spark.sql.execution.streaming._
import org.apache.spark.sql.functions._
import org.apache.spark.util.{JsonProtocol, ManualClock, Utils}


class StreamingQueryListenerSuite extends StreamTest with BeforeAndAfter {

  import testImplicits._
  import StreamingQueryListener._

  // To make === between double tolerate inexact values
  implicit val doubleEquality = TolerantNumerics.tolerantDoubleEquality(0.01)

  after {
    spark.streams.active.foreach(_.stop())
    assert(spark.streams.active.isEmpty)
    assert(addedListeners.isEmpty)
    // Make sure we don't leak any events to the next test
  }

  test("single listener, check trigger statuses") {
    import StreamingQueryListenerSuite._
    clock = new ManualClock()

    /** Custom MemoryStream that waits for manual clock to reach a time */
    val inputData = new MemoryStream[Int](0, sqlContext) {
      // Wait for manual clock to be 100 first time there is data
      override def getOffset: Option[Offset] = {
        val offset = super.getOffset
        if (offset.nonEmpty) {
          clock.waitTillTime(100)
        }
        offset
      }

      // Wait for manual clock to be 300 first time there is data
      override def getBatch(start: Option[Offset], end: Offset): DataFrame = {
        clock.waitTillTime(300)
        super.getBatch(start, end)
      }
    }

    // This is to make sure that
    // - Query waits for manual clock to be 600 first time there is data
    // - Exec plan ends with a node (filter) that supports the numOutputRows metric
    spark.conf.set("spark.sql.codegen.wholeStage", false)
    val mapped = inputData.toDS().agg(count("*")).as[Long].coalesce(1).map { x =>
      clock.waitTillTime(600)
      x
    }.where("value != 100")

    val listener = new QueryStatusCollector
    withListenerAdded(listener) {
      testStream(mapped, OutputMode.Complete)(
        StartStream(triggerClock = clock),
        AddData(inputData, 1, 2),
        AdvanceManualClock(100),  // unblock getOffset, will block on getBatch
        AdvanceManualClock(200),  // unblock getBatch, will block on computation
        AdvanceManualClock(300),  // unblock computation
        AssertOnQuery("Incorrect trigger info") { query =>
          require(clock.getTimeMillis() === 600)
          eventually(Timeout(streamingTimeout)) {
            assert(listener.lastTriggerStatus.nonEmpty)
          }

          // Check the correctness of the trigger info of the first completed batch reported by
          // onQueryProgress
          val status = listener.lastTriggerStatus.get
          assert(status.triggerStatus("triggerId") == "0")
          assert(status.triggerStatus("isActive") === "false")

          assert(status.triggerStatus("timestamp.triggerStart") === "0")
          assert(status.triggerStatus("timestamp.afterGetOffset") === "100")
          assert(status.triggerStatus("timestamp.afterGetBatch") === "300")
          assert(status.triggerStatus("timestamp.triggerFinish") === "600")

          assert(status.triggerStatus("latency.getOffset") === "100")
          assert(status.triggerStatus("latency.getBatch") === "200")
          assert(status.triggerStatus("latency.offsetLogWrite") === "0")
          assert(status.triggerStatus("latency.fullTrigger") === "600")

          assert(status.triggerStatus("numRows.input.total") === "2")
          assert(status.triggerStatus("numRows.output") === "1")
          assert(status.triggerStatus("numRows.state.aggregation1.total") === "1")
          assert(status.triggerStatus("numRows.state.aggregation1.updated") === "1")

          assert(status.sourceStatuses.size === 1)
          assert(status.sourceStatuses(0).triggerStatus("triggerId") === "0")
          assert(status.sourceStatuses(0).triggerStatus("latency.sourceGetOffset") === "100")
          assert(status.sourceStatuses(0).triggerStatus("latency.sourceGetBatch") === "200")
          assert(status.sourceStatuses(0).triggerStatus("numRows.input.source") === "2")
          true
        },
        CheckAnswer(2)
      )
    }
  }

  test("adding and removing listener") {
    def isListenerActive(listener: QueryStatusCollector): Boolean = {
      listener.reset()
      testStream(MemoryStream[Int].toDS)(
        StartStream(),
        StopStream
      )
      listener.startStatus != null
    }

    try {
      val listener1 = new QueryStatusCollector
      val listener2 = new QueryStatusCollector

      spark.streams.addListener(listener1)
      assert(isListenerActive(listener1) === true)
      assert(isListenerActive(listener2) === false)
      spark.streams.addListener(listener2)
      assert(isListenerActive(listener1) === true)
      assert(isListenerActive(listener2) === true)
      spark.streams.removeListener(listener1)
      assert(isListenerActive(listener1) === false)
      assert(isListenerActive(listener2) === true)
    } finally {
      addedListeners.foreach(spark.streams.removeListener)
    }
  }

  test("event ordering") {
    val listener = new QueryStatusCollector
    withListenerAdded(listener) {
      for (i <- 1 to 100) {
        listener.reset()
        require(listener.startStatus === null)
        testStream(MemoryStream[Int].toDS)(
          StartStream(),
          Assert(listener.startStatus !== null, "onQueryStarted not called before query returned"),
          StopStream,
          Assert { listener.checkAsyncErrors() }
        )
      }
    }
  }

  testQuietly("exception should be reported in QueryTerminated") {
    val listener = new QueryStatusCollector
    withListenerAdded(listener) {
      val input = MemoryStream[Int]
      testStream(input.toDS.map(_ / 0))(
        StartStream(),
        AddData(input, 1),
        ExpectFailure[SparkException](),
        Assert {
          spark.sparkContext.listenerBus.waitUntilEmpty(10000)
          assert(listener.terminationStatus !== null)
          assert(listener.terminationException.isDefined)
          // Make sure that the exception message reported through listener
          // contains the actual exception and relevant stack trace
          assert(!listener.terminationException.get.contains("StreamingQueryException"))
          assert(listener.terminationException.get.contains("java.lang.ArithmeticException"))
          assert(listener.terminationException.get.contains("StreamingQueryListenerSuite"))
        }
      )
    }
  }

  test("QueryStarted serialization") {
    val queryStarted = new StreamingQueryListener.QueryStarted(testQueryInfo)
    val json = JsonProtocol.sparkEventToJson(queryStarted)
    val newQueryStarted = JsonProtocol.sparkEventFromJson(json)
      .asInstanceOf[StreamingQueryListener.QueryStarted]
    assertStreamingQueryInfoEquals(queryStarted.queryInfo, newQueryStarted.queryInfo)
  }

  test("QueryProgress serialization") {
    val queryProcess = new StreamingQueryListener.QueryProgress(testQueryInfo)
    val json = JsonProtocol.sparkEventToJson(queryProcess)
    val newQueryProcess = JsonProtocol.sparkEventFromJson(json)
      .asInstanceOf[StreamingQueryListener.QueryProgress]
    assertStreamingQueryInfoEquals(queryProcess.queryInfo, newQueryProcess.queryInfo)
  }

  test("QueryTerminated serialization") {
    val exception = new RuntimeException("exception")
    val queryQueryTerminated = new StreamingQueryListener.QueryTerminated(
    testQueryInfo,
      Some(exception.getMessage))
    val json =
      JsonProtocol.sparkEventToJson(queryQueryTerminated)
    val newQueryTerminated = JsonProtocol.sparkEventFromJson(json)
      .asInstanceOf[StreamingQueryListener.QueryTerminated]
    assertStreamingQueryInfoEquals(queryQueryTerminated.queryInfo, newQueryTerminated.queryInfo)
    assert(queryQueryTerminated.exception === newQueryTerminated.exception)
  }

  private def assertStreamingQueryInfoEquals(
      expected: StreamingQueryInfo,
      actual: StreamingQueryInfo): Unit = {
    assert(expected.name === actual.name)
    assert(expected.sourceStatuses.size === actual.sourceStatuses.size)
    expected.sourceStatuses.zip(actual.sourceStatuses).foreach {
      case (expectedSource, actualSource) =>
        assertSourceStatus(expectedSource, actualSource)
    }
    assertSinkStatus(expected.sinkStatus, actual.sinkStatus)
  }

  private def assertSourceStatus(expected: SourceStatus, actual: SourceStatus): Unit = {
    assert(expected.description === actual.description)
    assert(expected.offsetDesc === actual.offsetDesc)
  }

  private def assertSinkStatus(expected: SinkStatus, actual: SinkStatus): Unit = {
    assert(expected.description === actual.description)
    assert(expected.offsetDesc === actual.offsetDesc)
  }

  private def withListenerAdded(listener: StreamingQueryListener)(body: => Unit): Unit = {
    try {
      failAfter(streamingTimeout) {
        spark.streams.addListener(listener)
        body
      }
    } finally {
      spark.streams.removeListener(listener)
    }
  }

  private def addedListeners(): Array[StreamingQueryListener] = {
    val listenerBusMethod =
      PrivateMethod[StreamingQueryListenerBus]('listenerBus)
    val listenerBus = spark.streams invokePrivate listenerBusMethod()
    listenerBus.listeners.toArray.map(_.asInstanceOf[StreamingQueryListener])
  }

  private val testQueryInfo: StreamingQueryInfo = {
    StreamingQueryInfo(
      "name", 1, 123, 1.0, 2.0, 3.0, Some(345),
      Seq(
        SourceStatus("source1", Some(LongOffset(0).toString), 0.0, 0.0, Map.empty),
        SourceStatus("source2", Some(LongOffset(1).toString), 1.0, 2.0, Map("a" -> "b"))),
      SinkStatus("sink", CompositeOffset(None :: None :: Nil).toString, 2.0),
      Map("a" -> "b"))
  }

  class QueryStatusCollector extends StreamingQueryListener {
    // to catch errors in the async listener events
    @volatile private var asyncTestWaiter = new Waiter

    @volatile var startStatus: StreamingQueryInfo = null
    @volatile var terminationStatus: StreamingQueryInfo = null
    @volatile var terminationException: Option[String] = null

    private val progressStatuses = new mutable.ArrayBuffer[StreamingQueryInfo]

    /** Get the info of the last trigger that processed data */
    def lastTriggerStatus: Option[StreamingQueryInfo] = synchronized {
      progressStatuses.filter { i =>
        i.triggerStatus("isActive").toBoolean == false &&
          i.triggerStatus("isDataAvailable").toBoolean == true
      }.lastOption
    }

    def reset(): Unit = {
      startStatus = null
      terminationStatus = null
      progressStatuses.clear()
      asyncTestWaiter = new Waiter
    }

    def checkAsyncErrors(): Unit = {
      asyncTestWaiter.await(timeout(streamingTimeout))
    }


    override def onQueryStarted(queryStarted: QueryStarted): Unit = {
      asyncTestWaiter {
        startStatus = queryStarted.queryInfo
      }
    }

    override def onQueryProgress(queryProgress: QueryProgress): Unit = {
      asyncTestWaiter {
        assert(startStatus != null, "onQueryProgress called before onQueryStarted")
        synchronized { progressStatuses += queryProgress.queryInfo }
      }
    }

    override def onQueryTerminated(queryTerminated: QueryTerminated): Unit = {
      asyncTestWaiter {
        assert(startStatus != null, "onQueryTerminated called before onQueryStarted")
        terminationStatus = queryTerminated.queryInfo
        terminationException = queryTerminated.exception
      }
      asyncTestWaiter.dismiss()
    }
  }
}

object StreamingQueryListenerSuite {
  // Singleton reference to clock that does not get serialized in task closures
  @volatile var clock: ManualClock = null
}
