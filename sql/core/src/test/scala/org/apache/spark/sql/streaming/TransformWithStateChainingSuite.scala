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

import java.sql.Timestamp
import java.time.{Instant, LocalDateTime, ZoneId}

import org.apache.spark.{SparkRuntimeException, SparkThrowable}
import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.ExtendedAnalysisException
import org.apache.spark.sql.execution.streaming.{MemoryStream, StreamExecution}
import org.apache.spark.sql.execution.streaming.state.RocksDBStateStoreProvider
import org.apache.spark.sql.functions.window
import org.apache.spark.sql.internal.SQLConf

case class InputEventRow(
    key: String,
    eventTime: Timestamp,
    event: String)

case class OutputRow(
    key: String,
    outputEventTime: Timestamp,
    count: Int)

class TestStatefulProcessor
  extends StatefulProcessor[String, InputEventRow, OutputRow] {
  override def init(outputMode: OutputMode, timeMode: TimeMode): Unit = {}

  override def handleInputRows(
      key: String,
      inputRows: Iterator[InputEventRow],
      timerValues: TimerValues,
      expiredTimerInfo: ExpiredTimerInfo): Iterator[OutputRow] = {
    if (inputRows.isEmpty) {
      Iterator.empty
    } else {
      var minEventTime = inputRows.next().eventTime
      var count = 1
      inputRows.foreach { row =>
        if (row.eventTime.before(minEventTime)) {
          minEventTime = row.eventTime
        }
        count += 1
      }
      Iterator.single(OutputRow(key, minEventTime, count))
    }
  }
}

class InputCountStatefulProcessor[T]
  extends StatefulProcessor[String, T, Int] {
  override def init(outputMode: OutputMode, timeMode: TimeMode): Unit = {}

  override def handleInputRows(
      key: String,
      inputRows: Iterator[T],
      timerValues: TimerValues,
      expiredTimerInfo: ExpiredTimerInfo): Iterator[Int] = {
    Iterator.single(inputRows.size)
  }
}

/**
 * Emits output row with timestamp older than current watermark for batchId > 0.
 */
class StatefulProcessorEmittingRowsOlderThanWatermark
  extends StatefulProcessor[String, InputEventRow, OutputRow] {
  override def init(outputMode: OutputMode, timeMode: TimeMode): Unit = {}

  override def handleInputRows(
      key: String,
      inputRows: Iterator[InputEventRow],
      timerValues: TimerValues,
      expiredTimerInfo: ExpiredTimerInfo): Iterator[OutputRow] = {
    if (timerValues.getCurrentWatermarkInMs() > 0) {
      Iterator.single(
        OutputRow(
          key,
          Timestamp.from(Instant.ofEpochMilli(timerValues.getCurrentWatermarkInMs() - 1)),
          inputRows.size))
    } else {
      var minEventTime = inputRows.next().eventTime
      var count = 1
      inputRows.foreach { row =>
        if (row.eventTime.before(minEventTime)) {
          minEventTime = row.eventTime
        }
        count += 1
      }
      Iterator.single(OutputRow(key, minEventTime, count))
    }
  }
}

case class Window(
    start: Timestamp,
    end: Timestamp)

case class AggEventRow(
    window: Window,
    count: Long)

class TransformWithStateChainingSuite extends StreamTest {
  import testImplicits._

  test("watermark is propagated correctly for next stateful operator" +
    " after transformWithState") {
    withSQLConf(SQLConf.STATE_STORE_PROVIDER_CLASS.key ->
      classOf[RocksDBStateStoreProvider].getName) {
      val inputData = MemoryStream[InputEventRow]

      val result = inputData.toDS()
        .withWatermark("eventTime", "1 minute")
        .groupByKey(x => x.key)
        .transformWithState[OutputRow](
          new TestStatefulProcessor(),
          "outputEventTime",
          OutputMode.Append())
        .groupBy(window($"outputEventTime", "1 minute"))
        .count()
        .as[AggEventRow]

      testStream(result, OutputMode.Append())(
        AddData(inputData, InputEventRow("k1", timestamp("2024-01-01 00:00:00"), "e1")),
        // watermark should be 1 minute behind `2024-01-01 00:00:00`, nothing is
        // emitted as all records have timestamp > epoch
        CheckNewAnswer(),
        Execute("assertWatermarkEquals") { q =>
          assertWatermarkEquals(q, timestamp("2023-12-31 23:59:00"))
        },
        AddData(inputData, InputEventRow("k1", timestamp("2024-02-01 00:00:00"), "e1")),
        // global watermark should now be 1 minute behind  `2024-02-01 00:00:00`.
        CheckNewAnswer(AggEventRow(
          Window(timestamp("2024-01-01 00:00:00"), timestamp("2024-01-01 00:01:00")), 1)
        ),
        Execute("assertWatermarkEquals") { q =>
          assertWatermarkEquals(q, timestamp("2024-01-31 23:59:00"))
        },
        AddData(inputData, InputEventRow("k1", timestamp("2024-02-02 00:00:00"), "e1")),
        CheckNewAnswer(AggEventRow(
          Window(timestamp("2024-02-01 00:00:00"), timestamp("2024-02-01 00:01:00")), 1)
        )
      )
    }
  }

  test("passing eventTime column to transformWithState fails if" +
    " no watermark is defined") {
    withSQLConf(SQLConf.STATE_STORE_PROVIDER_CLASS.key ->
      classOf[RocksDBStateStoreProvider].getName) {
      val inputData = MemoryStream[InputEventRow]

      val ex = intercept[AnalysisException] {
        inputData.toDS()
        .groupByKey(x => x.key)
        .transformWithState[OutputRow](
          new TestStatefulProcessor(),
          "outputEventTime",
          OutputMode.Append())
      }

      checkError(ex, "CANNOT_ASSIGN_EVENT_TIME_COLUMN_WITHOUT_WATERMARK")
    }
  }

  test("missing eventTime column to transformWithState fails the query if" +
    " another stateful operator is added") {
    withSQLConf(SQLConf.STATE_STORE_PROVIDER_CLASS.key ->
      classOf[RocksDBStateStoreProvider].getName) {
      val inputData = MemoryStream[InputEventRow]

      val result = inputData.toDS()
        .withWatermark("eventTime", "1 minute")
        .groupByKey(x => x.key)
        .transformWithState[OutputRow](
          new TestStatefulProcessor(),
          TimeMode.None(),
          OutputMode.Append())
        .groupBy(window($"outputEventTime", "1 minute"))
        .count()

      val ex = intercept[ExtendedAnalysisException] {
        testStream(result, OutputMode.Append())(
          StartStream()
        )
      }
      assert(ex.getMessage.contains("there are streaming aggregations on" +
        " streaming DataFrames/DataSets without watermark"))
    }
  }

  test("chaining multiple transformWithState operators") {
    withSQLConf(SQLConf.STATE_STORE_PROVIDER_CLASS.key ->
      classOf[RocksDBStateStoreProvider].getName) {
      val inputData = MemoryStream[InputEventRow]

      val result = inputData.toDS()
        .withWatermark("eventTime", "1 minute")
        .groupByKey(x => x.key)
        .transformWithState[OutputRow](
          new TestStatefulProcessor(),
          "outputEventTime",
          OutputMode.Append())
        .groupByKey(x => x.key)
        .transformWithState(
          new InputCountStatefulProcessor[OutputRow](),
          TimeMode.None(),
          OutputMode.Append()
        )

      testStream(result, OutputMode.Append())(
        AddData(inputData, InputEventRow("k1", timestamp("2024-01-01 00:00:00"), "e1")),
        CheckNewAnswer(1),
        Execute("assertWatermarkEquals") { q =>
          assertWatermarkEquals(q, timestamp("2023-12-31 23:59:00"))
        },
        AddData(inputData, InputEventRow("k1", timestamp("2024-02-01 00:00:00"), "e1")),
        CheckNewAnswer(1),
        Execute("assertWatermarkEquals") { q =>
          assertWatermarkEquals(q, timestamp("2024-01-31 23:59:00"))
        },
        AddData(inputData, InputEventRow("k1", timestamp("2024-02-02 00:00:00"), "e1")),
        CheckNewAnswer(1)
      )
    }
  }

  test("dropDuplicateWithWatermark after transformWithState operator" +
    " fails if watermark column is not provided") {
    withSQLConf(SQLConf.STATE_STORE_PROVIDER_CLASS.key ->
      classOf[RocksDBStateStoreProvider].getName) {
      val inputData = MemoryStream[InputEventRow]
      val result = inputData.toDS()
        .withWatermark("eventTime", "1 minute")
        .groupByKey(x => x.key)
        .transformWithState[OutputRow](
          new TestStatefulProcessor(),
          TimeMode.None(),
          OutputMode.Append())
        .dropDuplicatesWithinWatermark()

      val ex = intercept[ExtendedAnalysisException] {
        testStream(result, OutputMode.Append())(
          StartStream()
        )
      }
      assert(ex.getMessage.contains("dropDuplicatesWithinWatermark is not supported on" +
        " streaming DataFrames/DataSets without watermark"))
    }
  }

  test("dropDuplicateWithWatermark after transformWithState operator") {
    withSQLConf(SQLConf.STATE_STORE_PROVIDER_CLASS.key ->
      classOf[RocksDBStateStoreProvider].getName) {
      val inputData = MemoryStream[InputEventRow]
      val result = inputData.toDS()
        .withWatermark("eventTime", "1 minute")
        .groupByKey(x => x.key)
        .transformWithState[OutputRow](
          new TestStatefulProcessor(),
          "outputEventTime",
          OutputMode.Append())
        .dropDuplicatesWithinWatermark()

      testStream(result, OutputMode.Append())(
        AddData(inputData, InputEventRow("k1", timestamp("2024-02-01 00:00:00"), "e1"),
          InputEventRow("k1", timestamp("2024-02-01 00:00:00"), "e1")),
        CheckNewAnswer(OutputRow("k1", timestamp("2024-02-01 00:00:00"), 2)),
        Execute("assertWatermarkEquals") { q =>
          assertWatermarkEquals(q, timestamp("2024-01-31 23:59:00"))
        }
      )
    }
  }

  test("query fails if the output dataset does not contain specified eventTimeColumn") {
    withSQLConf(SQLConf.STATE_STORE_PROVIDER_CLASS.key ->
      classOf[RocksDBStateStoreProvider].getName) {
      val inputData = MemoryStream[InputEventRow]

      val ex = intercept[ExtendedAnalysisException] {
        val result = inputData.toDS()
          .withWatermark("eventTime", "1 minute")
          .groupByKey(x => x.key)
          .transformWithState[OutputRow](
            new TestStatefulProcessor(),
            "missingEventTimeColumn",
            OutputMode.Append())

        testStream(result, OutputMode.Append())(
          StartStream()
        )
      }

      checkError(ex, "UNRESOLVED_COLUMN.WITH_SUGGESTION",
        parameters = Map(
          "objectName" -> "`missingEventTimeColumn`",
          "proposal" -> "`outputEventTime`, `count`, `key`"))
    }
  }

  test("query fails if the output dataset contains rows older than current watermark") {
    withSQLConf(SQLConf.STATE_STORE_PROVIDER_CLASS.key ->
      classOf[RocksDBStateStoreProvider].getName) {
      val inputData = MemoryStream[InputEventRow]
      val result = inputData.toDS()
        .withWatermark("eventTime", "1 minute")
        .groupByKey(x => x.key)
        .transformWithState[OutputRow](
          new StatefulProcessorEmittingRowsOlderThanWatermark(),
          "outputEventTime",
          OutputMode.Append())

      testStream(result, OutputMode.Append())(
        AddData(inputData, InputEventRow("k1", timestamp("2024-02-01 00:00:00"), "e1")),
        CheckNewAnswer(OutputRow("k1", timestamp("2024-02-01 00:00:00"), 1)),
        // this batch would fail now
        AddData(inputData, InputEventRow("k1", timestamp("2024-02-02 00:00:00"), "e1")),
        ExpectFailure[SparkRuntimeException] { ex =>
          checkError(ex.asInstanceOf[SparkThrowable],
            "EMITTING_ROWS_OLDER_THAN_WATERMARK_NOT_ALLOWED",
            parameters = Map("currentWatermark" -> "1706774340000",
              "emittedRowEventTime" -> "1706774339999000"))
        }
      )
    }
  }

  private def timestamp(str: String): Timestamp = {
    Timestamp.valueOf(str)
  }

  private def assertWatermarkEquals(q: StreamExecution, watermark: Timestamp): Unit = {
    val queryWatermark = getQueryWatermark(q)
    assert(queryWatermark.isDefined)
    assert(queryWatermark.get === watermark)
  }

  private def getQueryWatermark(q: StreamExecution): Option[Timestamp] = {
    import scala.jdk.CollectionConverters._
    val eventTimeMap = q.lastProgress.eventTime.asScala
    val queryWatermark = eventTimeMap.get("watermark")
    queryWatermark.map { v =>
      val instant = Instant.parse(v)
      val local = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
      Timestamp.valueOf(local)
    }
  }
}
