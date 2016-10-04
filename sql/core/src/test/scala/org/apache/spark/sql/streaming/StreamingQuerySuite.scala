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

import java.util.concurrent.atomic.AtomicInteger

import org.scalactic.TolerantNumerics
import org.scalatest.concurrent.Eventually._
import org.scalatest.BeforeAndAfter
import org.scalatest.concurrent.PatienceConfiguration.Timeout

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.types.{IntegerType, StructField, StructType}
import org.apache.spark.SparkException
import org.apache.spark.sql.execution.streaming._
import org.apache.spark.util.Utils


class StreamingQuerySuite extends StreamTest with BeforeAndAfter {

  import AwaitTerminationTester._
  import testImplicits._

  // To make === between double tolerate inexact values
  implicit val doubleEquality = TolerantNumerics.tolerantDoubleEquality(0.01)

  after {
    sqlContext.streams.active.foreach(_.stop())
  }

  test("names unique across active queries, ids unique across all started queries") {
    val inputData = MemoryStream[Int]
    val mapped = inputData.toDS().map { 6 / _}

    def startQuery(queryName: String): StreamingQuery = {
      val metadataRoot = Utils.createTempDir(namePrefix = "streaming.checkpoint").getCanonicalPath
      val writer = mapped.writeStream
      writer
        .queryName(queryName)
        .format("memory")
        .option("checkpointLocation", metadataRoot)
        .start()
    }

    val q1 = startQuery("q1")
    assert(q1.name === "q1")

    // Verify that another query with same name cannot be started
    val e1 = intercept[IllegalArgumentException] {
      startQuery("q1")
    }
    Seq("q1", "already active").foreach { s => assert(e1.getMessage.contains(s)) }

    // Verify q1 was unaffected by the above exception and stop it
    assert(q1.isActive)
    q1.stop()

    // Verify another query can be started with name q1, but will have different id
    val q2 = startQuery("q1")
    assert(q2.name === "q1")
    assert(q2.id !== q1.id)
    q2.stop()
  }

  testQuietly("lifecycle states and awaitTermination") {
    val inputData = MemoryStream[Int]
    val mapped = inputData.toDS().map { 6 / _}

    testStream(mapped)(
      AssertOnQuery(_.isActive === true),
      AssertOnQuery(_.exception.isEmpty),
      AddData(inputData, 1, 2),
      CheckAnswer(6, 3),
      TestAwaitTermination(ExpectBlocked),
      TestAwaitTermination(ExpectBlocked, timeoutMs = 2000),
      TestAwaitTermination(ExpectNotBlocked, timeoutMs = 10, expectedReturnValue = false),
      StopStream,
      AssertOnQuery(_.isActive === false),
      AssertOnQuery(_.exception.isEmpty),
      TestAwaitTermination(ExpectNotBlocked),
      TestAwaitTermination(ExpectNotBlocked, timeoutMs = 2000, expectedReturnValue = true),
      TestAwaitTermination(ExpectNotBlocked, timeoutMs = 10, expectedReturnValue = true),
      StartStream(),
      AssertOnQuery(_.isActive === true),
      AddData(inputData, 0),
      ExpectFailure[SparkException],
      AssertOnQuery(_.isActive === false),
      TestAwaitTermination(ExpectException[SparkException]),
      TestAwaitTermination(ExpectException[SparkException], timeoutMs = 2000),
      TestAwaitTermination(ExpectException[SparkException], timeoutMs = 10),
      AssertOnQuery(
        q =>
          q.exception.get.startOffset.get === q.committedOffsets.toCompositeOffset(Seq(inputData)),
        "incorrect start offset on exception")
    )
  }

  testQuietly("query statuses") {
    val inputData = MemoryStream[Int]

    // This is make the sure the execution plan ends with a node (filter) that supports
    // the numOutputRows metric.
    spark.conf.set("spark.sql.codegen.wholeStage", false)
    val mapped = inputData.toDS().map(6 / _).where("value > 0")

    testStream(mapped)(
      AssertOnQuery(q => q.queryStatus.name === q.name),
      AssertOnQuery(q => q.queryStatus.id === q.id),
      AssertOnQuery(_.queryStatus.timestamp <= System.currentTimeMillis),
      AssertOnQuery(_.queryStatus.inputRate === 0.0),
      AssertOnQuery(_.queryStatus.processingRate === 0.0),
      AssertOnQuery(_.queryStatus.outputRate === 0.0),
      AssertOnQuery(_.queryStatus.sourceStatuses.length === 1),
      AssertOnQuery(_.queryStatus.sourceStatuses(0).description.contains("Memory")),
      AssertOnQuery(_.queryStatus.sourceStatuses(0).offsetDesc === None),
      AssertOnQuery(_.queryStatus.sourceStatuses(0).inputRate === 0.0),
      AssertOnQuery(_.queryStatus.sourceStatuses(0).processingRate === 0.0),
      AssertOnQuery(_.queryStatus.sinkStatus.description.contains("Memory")),
      AssertOnQuery(_.queryStatus.sinkStatus.offsetDesc === CompositeOffset(None :: Nil).toString),
      AssertOnQuery(_.queryStatus.sinkStatus.outputRate === 0.0),
      AssertOnQuery(_.sourceStatuses(0).description.contains("Memory")),
      AssertOnQuery(_.sourceStatuses(0).offsetDesc === None),
      AssertOnQuery(_.sourceStatuses(0).inputRate === 0.0),
      AssertOnQuery(_.sourceStatuses(0).processingRate === 0.0),
      AssertOnQuery(_.sinkStatus.description.contains("Memory")),
      AssertOnQuery(_.sinkStatus.offsetDesc === new CompositeOffset(None :: Nil).toString),
      AssertOnQuery(_.sinkStatus.outputRate === 0.0),

      AddData(inputData, 1, 2),
      CheckAnswer(6, 3),
      AssertOnQuery(_.queryStatus.timestamp <= System.currentTimeMillis),
      AssertOnQuery(_.queryStatus.inputRate >= 0.0),
      AssertOnQuery(_.queryStatus.processingRate >= 0.0),
      AssertOnQuery(_.queryStatus.outputRate >= 0.0),
      AssertOnQuery(_.queryStatus.sourceStatuses.length === 1),
      AssertOnQuery(_.queryStatus.sourceStatuses(0).description.contains("Memory")),
      AssertOnQuery(_.queryStatus.sourceStatuses(0).offsetDesc === Some(LongOffset(0).toString)),
      AssertOnQuery(_.queryStatus.sourceStatuses(0).inputRate >= 0.0),
      AssertOnQuery(_.queryStatus.sourceStatuses(0).processingRate >= 0.0),
      AssertOnQuery(_.queryStatus.sinkStatus.description.contains("Memory")),
      AssertOnQuery(_.queryStatus.sinkStatus.offsetDesc ===
        CompositeOffset.fill(LongOffset(0)).toString),
      AssertOnQuery(_.queryStatus.sinkStatus.outputRate >= 0.0),
      AssertOnQuery(_.sourceStatuses(0).offsetDesc === Some(LongOffset(0).toString)),
      AssertOnQuery(_.sourceStatuses(0).inputRate >= 0.0),
      AssertOnQuery(_.sourceStatuses(0).processingRate >= 0.0),
      AssertOnQuery(_.sinkStatus.offsetDesc === CompositeOffset.fill(LongOffset(0)).toString),
      AssertOnQuery(_.sinkStatus.outputRate >= 0.0),

      AddData(inputData, 1, 2),
      CheckAnswer(6, 3, 6, 3),
      AssertOnQuery(_.queryStatus.sourceStatuses(0).offsetDesc === Some(LongOffset(1).toString)),
      AssertOnQuery(_.queryStatus.sinkStatus.offsetDesc ===
        CompositeOffset.fill(LongOffset(1)).toString),
      AssertOnQuery(_.sourceStatuses(0).offsetDesc === Some(LongOffset(1).toString)),
      AssertOnQuery(_.sinkStatus.offsetDesc === CompositeOffset.fill(LongOffset(1)).toString),

      StopStream,
      AssertOnQuery(_.queryStatus.inputRate === 0.0),
      AssertOnQuery(_.queryStatus.processingRate === 0.0),
      AssertOnQuery(_.queryStatus.outputRate === 0.0),
      AssertOnQuery(_.queryStatus.sourceStatuses.length === 1),
      AssertOnQuery(_.queryStatus.sourceStatuses(0).offsetDesc === Some(LongOffset(1).toString)),
      AssertOnQuery(_.queryStatus.sourceStatuses(0).inputRate === 0.0),
      AssertOnQuery(_.queryStatus.sourceStatuses(0).processingRate === 0.0),
      AssertOnQuery(_.queryStatus.sinkStatus.offsetDesc ===
        CompositeOffset.fill(LongOffset(1)).toString),
      AssertOnQuery(_.queryStatus.sinkStatus.outputRate === 0.0),
      AssertOnQuery(_.sourceStatuses(0).offsetDesc === Some(LongOffset(1).toString)),
      AssertOnQuery(_.sourceStatuses(0).inputRate === 0.0),
      AssertOnQuery(_.sourceStatuses(0).processingRate === 0.0),
      AssertOnQuery(_.sinkStatus.offsetDesc === CompositeOffset.fill(LongOffset(1)).toString),
      AssertOnQuery(_.sinkStatus.outputRate === 0.0),

      StartStream(),
      AddData(inputData, 0),
      ExpectFailure[SparkException],
      AssertOnQuery(_.queryStatus.inputRate === 0.0),
      AssertOnQuery(_.queryStatus.processingRate === 0.0),
      AssertOnQuery(_.queryStatus.outputRate === 0.0),
      AssertOnQuery(_.queryStatus.sourceStatuses.length === 1),
      AssertOnQuery(_.queryStatus.sourceStatuses(0).offsetDesc === Some(LongOffset(2).toString)),
      AssertOnQuery(_.queryStatus.sourceStatuses(0).inputRate === 0.0),
      AssertOnQuery(_.queryStatus.sourceStatuses(0).processingRate === 0.0),
      AssertOnQuery(_.queryStatus.sinkStatus.offsetDesc ===
        CompositeOffset.fill(LongOffset(1)).toString),
      AssertOnQuery(_.queryStatus.sinkStatus.outputRate === 0.0),
      AssertOnQuery(_.sourceStatuses(0).offsetDesc === Some(LongOffset(2).toString)),
      AssertOnQuery(_.sourceStatuses(0).inputRate === 0.0),
      AssertOnQuery(_.sourceStatuses(0).processingRate === 0.0),
      AssertOnQuery(_.sinkStatus.offsetDesc === CompositeOffset.fill(LongOffset(1)).toString),
      AssertOnQuery(_.sinkStatus.outputRate === 0.0)
    )
  }

  // This tests whether row stats are correctly associated with streaming sources when
  // streaming plan also has batch sources
  test("calculating input rows with mixed batch and streaming sources") {

    // A streaming source that returns a pre-determined dataframe for a trigger
    // Creates multiple leaves for a streaming source
    val streamingTriggerDF = spark.createDataset(1 to 5).toDF.union(
      spark.createDataset(6 to 10).toDF)
    val streamingSource = new Source() {
      override def schema: StructType = StructType(Seq(StructField("value", IntegerType)))
      override def getOffset: Option[Offset] = Some(LongOffset(0))
      override def getBatch(start: Option[Offset], end: Offset): DataFrame = streamingTriggerDF
      override def stop(): Unit = {}
    }
    val streamingInputDF = StreamingExecutionRelation(streamingSource).toDF("value")
    val staticInputDF = spark.createDataFrame(Seq(1 -> "1", 2 -> "2")).toDF("value", "anotherValue")

    // streaming trigger input has 10 items, static input has 2 items, input row calculation for
    // the source should return 10
    spark.conf.set("spark.sql.codegen.wholeStage", false)
    testStream(streamingInputDF.join(staticInputDF, "value"))(
      StartStream(),
      AssertOnQuery { q =>
        eventually(Timeout(streamingTimeout)) {
          assert(q.queryStatus.sinkStatus.offsetDesc
            === CompositeOffset.fill(LongOffset(0)).toString)
        }
        val numInputRows = StreamExecution.getNumInputRowsFromTrigger(
          q.lastExecution.executedPlan,
          q.lastExecution.logical,
          Map(streamingSource -> streamingTriggerDF))
        assert(numInputRows === Map(streamingSource -> 10)) // streaming data has 10 items
        true
      }
    )
  }

  testQuietly("StreamExecution metadata garbage collection") {
    val inputData = MemoryStream[Int]
    val mapped = inputData.toDS().map(6 / _)

    // Run 3 batches, and then assert that only 1 metadata file is left at the end
    // since the first 2 should have been purged.
    testStream(mapped)(
      AddData(inputData, 1, 2),
      CheckAnswer(6, 3),
      AddData(inputData, 1, 2),
      CheckAnswer(6, 3, 6, 3),
      AddData(inputData, 4, 6),
      CheckAnswer(6, 3, 6, 3, 1, 1),

      AssertOnQuery("metadata log should contain only one file") { q =>
        val metadataLogDir = new java.io.File(q.offsetLog.metadataPath.toString)
        val logFileNames = metadataLogDir.listFiles().toSeq.map(_.getName())
        val toTest = logFileNames.filter(! _.endsWith(".crc"))  // Workaround for SPARK-17475
        assert(toTest.size == 1 && toTest.head == "2")
        true
      }
    )
  }

  /**
   * A [[StreamAction]] to test the behavior of `StreamingQuery.awaitTermination()`.
   *
   * @param expectedBehavior  Expected behavior (not blocked, blocked, or exception thrown)
   * @param timeoutMs         Timeout in milliseconds
   *                          When timeoutMs <= 0, awaitTermination() is tested (i.e. w/o timeout)
   *                          When timeoutMs > 0, awaitTermination(timeoutMs) is tested
   * @param expectedReturnValue Expected return value when awaitTermination(timeoutMs) is used
   */
  case class TestAwaitTermination(
      expectedBehavior: ExpectedBehavior,
      timeoutMs: Int = -1,
      expectedReturnValue: Boolean = false
    ) extends AssertOnQuery(
      TestAwaitTermination.assertOnQueryCondition(expectedBehavior, timeoutMs, expectedReturnValue),
      "Error testing awaitTermination behavior"
    ) {
    override def toString(): String = {
      s"TestAwaitTermination($expectedBehavior, timeoutMs = $timeoutMs, " +
        s"expectedReturnValue = $expectedReturnValue)"
    }
  }

  object TestAwaitTermination {

    /**
     * Tests the behavior of `StreamingQuery.awaitTermination`.
     *
     * @param expectedBehavior  Expected behavior (not blocked, blocked, or exception thrown)
     * @param timeoutMs         Timeout in milliseconds
     *                          When timeoutMs <= 0, awaitTermination() is tested (i.e. w/o timeout)
     *                          When timeoutMs > 0, awaitTermination(timeoutMs) is tested
     * @param expectedReturnValue Expected return value when awaitTermination(timeoutMs) is used
     */
    def assertOnQueryCondition(
        expectedBehavior: ExpectedBehavior,
        timeoutMs: Int,
        expectedReturnValue: Boolean
      )(q: StreamExecution): Boolean = {

      def awaitTermFunc(): Unit = {
        if (timeoutMs <= 0) {
          q.awaitTermination()
        } else {
          val returnedValue = q.awaitTermination(timeoutMs)
          assert(returnedValue === expectedReturnValue, "Returned value does not match expected")
        }
      }
      AwaitTerminationTester.test(expectedBehavior, awaitTermFunc)
      true // If the control reached here, then everything worked as expected
    }
  }
}
