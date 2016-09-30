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

import scala.collection.mutable

import org.scalactic.TolerantNumerics
import org.scalatest.BeforeAndAfter
import org.scalatest.PrivateMethodTester._
import org.scalatest.concurrent.AsyncAssertions.Waiter
import org.scalatest.concurrent.Eventually._
import org.scalatest.concurrent.PatienceConfiguration.Timeout

import org.apache.spark.SparkException
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.execution.streaming._
import org.apache.spark.sql.functions._
import org.apache.spark.util.{JsonProtocol, ManualClock}


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
    spark.sparkContext.listenerBus.waitUntilEmpty(10000)
  }

  test("single listener, check statuses") {
    val listener = new QueryStatusCollector
    val input = MemoryStream[Int]

    // This is to make sure that
    // - Query takes non-zero time to compute
    // - Exec plan ends with a node (filter) that supports the numOutputRows metric
    spark.conf.set("spark.sql.codegen.wholeStage", false)
    val df = input.toDS.map { x => Thread.sleep(10); x }.toDF("value").where("value != 0")

    withListenerAdded(listener) {
      testStream(df)(
        StartStream(),
        AssertOnQuery("Incorrect query status in onQueryStarted") { query =>
          val status = listener.startStatus
          assert(status != null)
          assert(status.name === query.name)
          assert(status.id === query.id)
          assert(status.sourceStatuses.size === 1)
          assert(status.sourceStatuses(0).description.contains("Memory"))

          // The source and sink offsets must be None as this must be called before the
          // batches have started
          assert(status.sourceStatuses(0).offsetDesc === None)
          assert(status.sinkStatus.offsetDesc === CompositeOffset(None :: Nil).toString)
          assert(status.sinkStatus.outputRate === 0.0)

          // The source and sink rates must be None as this must be called before the batches
          // have started
          assert(status.sourceStatuses(0).inputRate === 0.0)
          assert(status.sourceStatuses(0).processingRate === 0.0)

          // No progress events or termination events
          assert(listener.terminationStatus === null)
          true
        },
        AddDataMemory(input, Seq(1, 2, 3)),
        CheckAnswer(1, 2, 3),
        AssertOnQuery("Incorrect query status in onQueryProgress") { query =>
          eventually(Timeout(streamingTimeout)) {
            assert(listener.lastTriggerStatus.nonEmpty)
          }
          // Check the correctness of data in the latest query info reported by onQueryProgress
          val status = listener.lastTriggerStatus.get
          assert(status != null)
          assert(status.name === query.name)
          assert(status.id === query.id)
          assert(status.sourceStatuses(0).offsetDesc === Some(LongOffset(0).toString))
          assert(status.sourceStatuses(0).inputRate >= 0.0) // flaky if checked for ==
          assert(status.sourceStatuses(0).processingRate > 0.0)
          assert(status.sinkStatus.offsetDesc === CompositeOffset.fill(LongOffset(0)).toString)
          assert(status.sinkStatus.outputRate !== 0.0)

          // No termination events
          assert(listener.terminationStatus === null)
          true
        },
        StopStream,
        AssertOnQuery("Incorrect query status in onQueryTerminated") { query =>
          eventually(Timeout(streamingTimeout)) {
            val status = listener.terminationStatus
            assert(status != null)
            assert(status.name === query.name)
            assert(status.id === query.id)
            assert(status.sourceStatuses(0).offsetDesc === Some(LongOffset(0).toString))
            assert(status.sourceStatuses(0).inputRate === 0.0)
            assert(status.sourceStatuses(0).processingRate === 0.0)
            assert(status.sinkStatus.offsetDesc === CompositeOffset.fill(LongOffset(0)).toString)
            assert(status.sinkStatus.outputRate === 0.0)
            assert(listener.terminationException === None)
          }
          listener.checkAsyncErrors()
          true
        }
      )
    }
  }

  test("single listener, check trigger infos") {
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
          assert(status.triggerInfo("triggerId") == "0")
          assert(status.triggerInfo("isActive") === "false")

          assert(status.triggerInfo("timestamp.triggerStart") === "0")
          assert(status.triggerInfo("timestamp.afterGetOffset") === "100")
          assert(status.triggerInfo("timestamp.afterGetBatch") === "300")
          assert(status.triggerInfo("timestamp.triggerFinish") === "600")

          assert(status.triggerInfo("latency.getOffset") === "100")
          assert(status.triggerInfo("latency.getBatch") === "200")
          assert(status.triggerInfo("latency.offsetLogWrite") === "0")
          assert(status.triggerInfo("latency.fullTrigger") === "600")

          assert(status.triggerInfo("numRows.input.total") === "2")
          assert(status.triggerInfo("numRows.output") === "1")
          assert(status.triggerInfo("numRows.state.aggregation1.total") === "1")
          assert(status.triggerInfo("numRows.state.aggregation1.updated") === "1")

          assert(status.sourceStatuses.size === 1)
          assert(status.sourceStatuses(0).triggerInfo("triggerId") === "0")
          assert(status.sourceStatuses(0).triggerInfo("latency.sourceGetOffset") === "100")
          assert(status.sourceStatuses(0).triggerInfo("numRows.input.source") === "2")
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
    new StreamingQueryInfo(
      "name", 1, 123, 1.0, 2.0, 3.0, Some(345),
      Seq(
        new SourceStatus("source1", Some(LongOffset(0).toString), 0.0, 0.0, Map.empty),
        new SourceStatus("source2", Some(LongOffset(1).toString), 1.0, 2.0, Map("a" -> "b"))),
      new SinkStatus("sink", CompositeOffset(None :: None :: Nil).toString, 2.0),
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
        i.triggerInfo("isActive").toBoolean == false &&
          i.triggerInfo("isDataAvailable").toBoolean == true
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
