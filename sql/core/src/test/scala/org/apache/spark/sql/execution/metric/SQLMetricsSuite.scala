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

package org.apache.spark.sql.execution.metric

import java.io.File

import scala.reflect.{classTag, ClassTag}
import scala.util.Random

import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.expressions.aggregate.{Final, Partial}
import org.apache.spark.sql.catalyst.plans.logical.LocalRelation
import org.apache.spark.sql.execution.{FilterExec, RangeExec, SparkPlan, WholeStageCodegenExec}
import org.apache.spark.sql.execution.adaptive.DisableAdaptiveExecutionSuite
import org.apache.spark.sql.execution.aggregate.HashAggregateExec
import org.apache.spark.sql.execution.exchange.ShuffleExchangeExec
import org.apache.spark.sql.functions._
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.util.{AccumulatorContext, JsonProtocol}

// Disable AQE because metric info is different with AQE on/off
class SQLMetricsSuite extends SharedSparkSession with SQLMetricsTestUtils
  with DisableAdaptiveExecutionSuite {
  import testImplicits._

  /**
   * Generates a `DataFrame` by filling randomly generated bytes for hash collision.
   */
  private def generateRandomBytesDF(numRows: Int = 65535): DataFrame = {
    val random = new Random()
    val manyBytes = (0 until numRows).map { _ =>
      val byteArrSize = random.nextInt(100)
      val bytes = new Array[Byte](byteArrSize)
      random.nextBytes(bytes)
      (bytes, random.nextInt(100))
    }
    manyBytes.toSeq.toDF("a", "b")
  }

  test("LocalTableScanExec computes metrics in collect and take") {
    val df1 = spark.createDataset(Seq(1, 2, 3))
    val logical = df1.queryExecution.logical
    require(logical.isInstanceOf[LocalRelation])
    df1.collect()
    val metrics1 = df1.queryExecution.executedPlan.collectLeaves().head.metrics
    assert(metrics1.contains("numOutputRows"))
    assert(metrics1("numOutputRows").value === 3)

    val df2 = spark.createDataset(Seq(1, 2, 3)).limit(2)
    df2.collect()
    val metrics2 = df2.queryExecution.executedPlan.collectLeaves().head.metrics
    assert(metrics2.contains("numOutputRows"))
    assert(metrics2("numOutputRows").value === 2)
  }

  test("Filter metrics") {
    // Assume the execution plan is
    // PhysicalRDD(nodeId = 1) -> Filter(nodeId = 0)
    Seq((0L, false), (1L, true)).foreach { case (nodeId, enableWholeStage) =>
      val df = person.filter('age < 25)
      testSparkPlanMetrics(df, 1, Map(
        nodeId -> (("Filter", Map(
          "number of output rows" -> 1L)))),
        enableWholeStage
      )
    }
  }

  test("WholeStageCodegen metrics") {
    // Assume the execution plan with node id is
    // WholeStageCodegen(nodeId = 0)
    //   Filter(nodeId = 1)
    //     Range(nodeId = 2)
    // TODO: update metrics in generated operators
    val ds = spark.range(10).filter('id < 5)
    testSparkPlanMetricsWithPredicates(ds.toDF(), 1, Map(
      0L -> (("WholeStageCodegen (1)", Map(
        "duration" -> {
          _.toString.matches(timingMetricPattern)
        })))), true)
  }

  test("Aggregate metrics") {
    // Assume the execution plan is
    // ... -> HashAggregate(nodeId = 2) -> Exchange(nodeId = 1)
    // -> HashAggregate(nodeId = 0)
    val df = testData2.groupBy().count() // 2 partitions
    val expected1 = Seq(
      Map("number of output rows" -> 2L,
        "avg hash probe bucket list iters" ->
          aggregateMetricsPattern),
      Map("number of output rows" -> 1L,
        "avg hash probe bucket list iters" ->
          aggregateMetricsPattern))
    val shuffleExpected1 = Map(
      "records read" -> 2L,
      "local blocks read" -> 2L,
      "remote blocks read" -> 0L,
      "shuffle records written" -> 2L)
    testSparkPlanMetrics(df, 1, Map(
      2L -> (("HashAggregate", expected1(0))),
      1L -> (("Exchange", shuffleExpected1)),
      0L -> (("HashAggregate", expected1(1))))
    )

    // 2 partitions and each partition contains 2 keys
    val df2 = testData2.groupBy('a).count()
    val expected2 = Seq(
      Map("number of output rows" -> 4L,
        "avg hash probe bucket list iters" ->
          aggregateMetricsPattern),
      Map("number of output rows" -> 3L,
        "avg hash probe bucket list iters" ->
          aggregateMetricsPattern))

    val shuffleExpected2 = Map(
      "records read" -> 4L,
      "local blocks read" -> 4L,
      "remote blocks read" -> 0L,
      "shuffle records written" -> 4L)
    testSparkPlanMetrics(df2, 1, Map(
      2L -> (("HashAggregate", expected2(0))),
      1L -> (("Exchange", shuffleExpected2)),
      0L -> (("HashAggregate", expected2(1))))
    )
  }

  test("Aggregate metrics: track avg probe") {
    // The executed plan looks like:
    // HashAggregate(keys=[a#61], functions=[count(1)], output=[a#61, count#71L])
    // +- Exchange hashpartitioning(a#61, 5)
    //    +- HashAggregate(keys=[a#61], functions=[partial_count(1)], output=[a#61, count#76L])
    //       +- Exchange RoundRobinPartitioning(1)
    //          +- LocalTableScan [a#61]
    //
    // Assume the execution plan with node id is:
    // Wholestage disabled:
    // HashAggregate(nodeId = 0)
    //   Exchange(nodeId = 1)
    //     HashAggregate(nodeId = 2)
    //       Exchange (nodeId = 3)
    //         LocalTableScan(nodeId = 4)
    //
    // Wholestage enabled:
    // WholeStageCodegen(nodeId = 0)
    //   HashAggregate(nodeId = 1)
    //     Exchange(nodeId = 2)
    //       WholeStageCodegen(nodeId = 3)
    //         HashAggregate(nodeId = 4)
    //           Exchange(nodeId = 5)
    //             LocalTableScan(nodeId = 6)
    Seq(true, false).foreach { enableWholeStage =>
      val df = generateRandomBytesDF().repartition(1).groupBy('a).count()
      val nodeIds = if (enableWholeStage) {
        Set(4L, 1L)
      } else {
        Set(2L, 0L)
      }
      val metrics = getSparkPlanMetrics(df, 1, nodeIds, enableWholeStage).get
      nodeIds.foreach { nodeId =>
        val probes = metrics(nodeId)._2("avg hash probe bucket list iters").toString
        if (!probes.contains("\n")) {
          // It's a single metrics value
          assert(probes.toDouble > 1.0)
        } else {
          val mainValue = probes.split("\n").apply(1).stripPrefix("(").stripSuffix(")")
          // Extract min, med, max from the string and strip off everthing else.
          val index = mainValue.indexOf(" (", 0)
          mainValue.slice(0, index).split(", ").foreach {
            probe => assert(probe.toDouble > 1.0)
          }
        }
      }
    }
  }

  test("ObjectHashAggregate metrics") {
    // Assume the execution plan is
    // ... -> ObjectHashAggregate(nodeId = 2) -> Exchange(nodeId = 1)
    // -> ObjectHashAggregate(nodeId = 0)
    val df = testData2.groupBy().agg(collect_set('a)) // 2 partitions
    testSparkPlanMetrics(df, 1, Map(
      2L -> (("ObjectHashAggregate", Map("number of output rows" -> 2L))),
      1L -> (("Exchange", Map(
        "shuffle records written" -> 2L,
        "records read" -> 2L,
        "local blocks read" -> 2L,
        "remote blocks read" -> 0L))),
      0L -> (("ObjectHashAggregate", Map("number of output rows" -> 1L))))
    )

    // 2 partitions and each partition contains 2 keys
    val df2 = testData2.groupBy('a).agg(collect_set('a))
    testSparkPlanMetrics(df2, 1, Map(
      2L -> (("ObjectHashAggregate", Map("number of output rows" -> 4L))),
      1L -> (("Exchange", Map(
        "shuffle records written" -> 4L,
        "records read" -> 4L,
        "local blocks read" -> 4L,
        "remote blocks read" -> 0L))),
      0L -> (("ObjectHashAggregate", Map("number of output rows" -> 3L))))
    )
  }

  test("Sort metrics") {
    // Assume the execution plan with node id is
    // Sort(nodeId = 0)
    //   Exchange(nodeId = 1)
    //     Project(nodeId = 2)
    //       LocalTableScan(nodeId = 3)
    // Because of SPARK-25267, ConvertToLocalRelation is disabled in the test cases of sql/core,
    // so Project here is not collapsed into LocalTableScan.
    val df = Seq(1, 3, 2).toDF("id").sort('id)
    testSparkPlanMetricsWithPredicates(df, 2, Map(
      0L -> (("Sort", Map(
        "sort time" -> {
          _.toString.matches(timingMetricPattern)
        },
        "peak memory" -> {
          _.toString.matches(sizeMetricPattern)
        },
        "spill size" -> {
          _.toString.matches(sizeMetricPattern)
        })))
    ))
  }

  test("SortMergeJoin metrics") {
    // Because SortMergeJoin may skip different rows if the number of partitions is different, this
    // test should use the deterministic number of partitions.
    val testDataForJoin = testData2.filter('a < 2) // TestData2(1, 1) :: TestData2(1, 2)
    testDataForJoin.createOrReplaceTempView("testDataForJoin")
    withTempView("testDataForJoin") {
      // Assume the execution plan is
      // ... -> SortMergeJoin(nodeId = 1) -> TungstenProject(nodeId = 0)
      val query = "SELECT * FROM testData2 JOIN testDataForJoin ON testData2.a = testDataForJoin.a"
      Seq((0L, 2L, false), (1L, 4L, true)).foreach { case (nodeId1, nodeId2, enableWholeStage) =>
        val df = spark.sql(query)
        testSparkPlanMetrics(df, 1, Map(
          nodeId1 -> (("SortMergeJoin", Map(
            // It's 4 because we only read 3 rows in the first partition and 1 row in the second one
            "number of output rows" -> 4L))),
          nodeId2 -> (("Exchange", Map(
            "records read" -> 4L,
            "local blocks read" -> 2L,
            "remote blocks read" -> 0L,
            "shuffle records written" -> 2L)))),
          enableWholeStage
        )
      }
    }
  }

  test("SortMergeJoin(outer) metrics") {
    // Because SortMergeJoin may skip different rows if the number of partitions is different,
    // this test should use the deterministic number of partitions.
    val testDataForJoin = testData2.filter('a < 2) // TestData2(1, 1) :: TestData2(1, 2)
    testDataForJoin.createOrReplaceTempView("testDataForJoin")
    withTempView("testDataForJoin") {
      // Assume the execution plan is
      // ... -> SortMergeJoin(nodeId = 1) -> TungstenProject(nodeId = 0)
      val leftJoinQuery = "SELECT * FROM testData2 left JOIN testDataForJoin ON " +
        "testData2.a = testDataForJoin.a"
      val rightJoinQuery = "SELECT * FROM testDataForJoin right JOIN testData2 ON " +
        "testData2.a = testDataForJoin.a"

      Seq((leftJoinQuery, false), (leftJoinQuery, true), (rightJoinQuery, false),
        (rightJoinQuery, true)).foreach { case (query, enableWholeStage) =>
        val df = spark.sql(query)
        testSparkPlanMetrics(df, 1, Map(
          0L -> (("SortMergeJoin", Map(
            // It's 8 because we read 6 rows in the left and 2 row in the right one
            "number of output rows" -> 8L)))),
          enableWholeStage
        )
      }
    }
  }

  test("BroadcastHashJoin metrics") {
    val df1 = Seq((1, "1"), (2, "2")).toDF("key", "value")
    val df2 = Seq((1, "1"), (2, "2"), (3, "3"), (4, "4")).toDF("key", "value")
    // Assume the execution plan is
    // ... -> BroadcastHashJoin(nodeId = 1) -> TungstenProject(nodeId = 0)
    Seq((1L, false), (2L, true)).foreach { case (nodeId, enableWholeStage) =>
      val df = df1.join(broadcast(df2), "key")
      testSparkPlanMetrics(df, 2, Map(
        nodeId -> (("BroadcastHashJoin", Map(
          "number of output rows" -> 2L)))),
        enableWholeStage
      )
    }
  }

  test("ShuffledHashJoin metrics") {
    withSQLConf(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "40",
      SQLConf.SHUFFLE_PARTITIONS.key -> "2",
      SQLConf.PREFER_SORTMERGEJOIN.key -> "false") {
      val df1 = Seq((1, "1"), (2, "2")).toDF("key", "value")
      val df2 = (1 to 10).map(i => (i, i.toString)).toSeq.toDF("key", "value")
      // Assume the execution plan is
      // Project(nodeId = 0)
      // +- ShuffledHashJoin(nodeId = 1)
      // :- Exchange(nodeId = 2)
      // :  +- Project(nodeId = 3)
      // :     +- LocalTableScan(nodeId = 4)
      // +- Exchange(nodeId = 5)
      // +- Project(nodeId = 6)
      // +- LocalTableScan(nodeId = 7)
      Seq((1L, 2L, 5L, false), (2L, 3L, 7L, true)).foreach {
        case (nodeId1, nodeId2, nodeId3, enableWholeStage) =>
          val df = df1.join(df2, "key")
          testSparkPlanMetrics(df, 1, Map(
            nodeId1 -> (("ShuffledHashJoin", Map(
              "number of output rows" -> 2L))),
            nodeId2 -> (("Exchange", Map(
              "shuffle records written" -> 2L,
              "records read" -> 2L))),
            nodeId3 -> (("Exchange", Map(
              "shuffle records written" -> 10L,
              "records read" -> 10L)))),
            enableWholeStage
          )
      }
    }
  }

  test("ShuffledHashJoin(left, outer) metrics") {
    val leftDf = Seq((1, "1"), (2, "2")).toDF("key", "value")
    val rightDf = (1 to 10).map(i => (i, i.toString)).toSeq.toDF("key2", "value")
    Seq((0L, "right_outer", leftDf, rightDf, 10L, false),
      (0L, "left_outer", rightDf, leftDf, 10L, false),
      (0L, "right_outer", leftDf, rightDf, 10L, true),
      (0L, "left_outer", rightDf, leftDf, 10L, true),
      (2L, "left_anti", rightDf, leftDf, 8L, true),
      (2L, "left_semi", rightDf, leftDf, 2L, true),
      (1L, "left_anti", rightDf, leftDf, 8L, false),
      (1L, "left_semi", rightDf, leftDf, 2L, false))
      .foreach { case (nodeId, joinType, leftDf, rightDf, rows, enableWholeStage) =>
        val df = leftDf.hint("shuffle_hash").join(
          rightDf.hint("shuffle_hash"), $"key" === $"key2", joinType)
        testSparkPlanMetrics(df, 1, Map(
          nodeId -> (("ShuffledHashJoin", Map(
            "number of output rows" -> rows)))),
          enableWholeStage
        )
      }
  }

  test("BroadcastHashJoin(outer) metrics") {
    val df1 = Seq((1, "a"), (1, "b"), (4, "c")).toDF("key", "value")
    val df2 = Seq((1, "a"), (1, "b"), (2, "c"), (3, "d")).toDF("key2", "value")
    Seq(("left_outer", 0L, 5L, false), ("right_outer", 0L, 6L, false), ("left_outer", 1L, 5L, true),
      ("right_outer", 1L, 6L, true)).foreach { case (joinType, nodeId, numRows, enableWholeStage) =>
      val df = df1.join(broadcast(df2), $"key" === $"key2", joinType)
      testSparkPlanMetrics(df, 2, Map(
        nodeId -> (("BroadcastHashJoin", Map(
          "number of output rows" -> numRows)))),
        enableWholeStage
      )
    }
  }

  test("BroadcastNestedLoopJoin metrics") {
    val testDataForJoin = testData2.filter('a < 2) // TestData2(1, 1) :: TestData2(1, 2)
    testDataForJoin.createOrReplaceTempView("testDataForJoin")
    withSQLConf(SQLConf.CROSS_JOINS_ENABLED.key -> "true") {
      withTempView("testDataForJoin") {
        // Assume the execution plan is
        // ... -> BroadcastNestedLoopJoin(nodeId = 1) -> TungstenProject(nodeId = 0)
        val leftQuery = "SELECT * FROM testData2 LEFT JOIN testDataForJoin ON " +
          "testData2.a * testDataForJoin.a != testData2.a + testDataForJoin.a"
        val rightQuery = "SELECT * FROM testData2 RIGHT JOIN testDataForJoin ON " +
          "testData2.a * testDataForJoin.a != testData2.a + testDataForJoin.a"
        Seq((leftQuery, false), (rightQuery, false), (leftQuery, true), (rightQuery, true))
          .foreach { case (query, enableWholeStage) =>
          val df = spark.sql(query)
          testSparkPlanMetrics(df, 2, Map(
            0L -> (("BroadcastNestedLoopJoin", Map(
              "number of output rows" -> 12L)))),
            enableWholeStage
          )
        }
      }
    }
  }

  test("BroadcastLeftSemiJoinHash metrics") {
    val df1 = Seq((1, "1"), (2, "2")).toDF("key", "value")
    val df2 = Seq((1, "1"), (2, "2"), (3, "3"), (4, "4")).toDF("key2", "value")
    // Assume the execution plan is
    // ... -> BroadcastHashJoin(nodeId = 1)
    Seq((1L, false), (2L, true)).foreach { case (nodeId, enableWholeStage) =>
      val df = df1.join(broadcast(df2), $"key" === $"key2", "leftsemi")
      testSparkPlanMetrics(df, 2, Map(
        nodeId -> (("BroadcastHashJoin", Map(
          "number of output rows" -> 2L)))),
        enableWholeStage
      )
    }
  }

  test("BroadcastLeftAntiJoinHash metrics") {
    val df1 = Seq((1, "1"), (2, "2")).toDF("key", "value")
    val df2 = Seq((1, "1"), (2, "2"), (3, "3"), (4, "4")).toDF("key2", "value")
    Seq((1L, false), (2L, true)).foreach { case (nodeId, enableWholeStage) =>
      val df = df2.join(broadcast(df1), $"key" === $"key2", "left_anti")
      testSparkPlanMetrics(df, 2, Map(
        nodeId -> (("BroadcastHashJoin", Map(
          "number of output rows" -> 2L)))),
        enableWholeStage
      )
    }
  }

  test("CartesianProduct metrics") {
    withSQLConf(SQLConf.CROSS_JOINS_ENABLED.key -> "true") {
      val testDataForJoin = testData2.filter('a < 2) // TestData2(1, 1) :: TestData2(1, 2)
      testDataForJoin.createOrReplaceTempView("testDataForJoin")
      withTempView("testDataForJoin") {
        // Assume the execution plan is
        // ... -> CartesianProduct(nodeId = 1) -> TungstenProject(nodeId = 0)
        val query = "SELECT * FROM testData2 JOIN testDataForJoin"
        Seq(true, false).foreach { enableWholeStage =>
          val df = spark.sql(query)
          testSparkPlanMetrics(df, 1, Map(
            0L -> (("CartesianProduct", Map("number of output rows" -> 12L)))),
            enableWholeStage
          )
        }
      }
    }
  }

  test("SortMergeJoin(left-anti) metrics") {
    val anti = testData2.filter("a > 2")
    withTempView("antiData") {
      anti.createOrReplaceTempView("antiData")
      val query = "SELECT * FROM testData2 ANTI JOIN antiData ON testData2.a = antiData.a"
      Seq(false, true).foreach { enableWholeStage =>
        val df = spark.sql(query)
        testSparkPlanMetrics(df, 1, Map(
          0L -> (("SortMergeJoin", Map("number of output rows" -> 4L)))),
          enableWholeStage
        )
      }
    }
  }

  test("save metrics") {
    withTempPath { file =>
      // person creates a temporary view. get the DF before listing previous execution IDs
      val data = person.select('name)
      val previousExecutionIds = currentExecutionIds()
      // Assume the execution plan is
      // PhysicalRDD(nodeId = 0)
      data.write.format("json").save(file.getAbsolutePath)
      sparkContext.listenerBus.waitUntilEmpty()
      val executionIds = currentExecutionIds().diff(previousExecutionIds)
      assert(executionIds.size === 1)
      val executionId = executionIds.head
      val jobs = statusStore.execution(executionId).get.jobs
      // Use "<=" because there is a race condition that we may miss some jobs
      // TODO Change "<=" to "=" once we fix the race condition that missing the JobStarted event.
      assert(jobs.size <= 1)
      val metricValues = statusStore.executionMetrics(executionId)
      // Because "save" will create a new DataFrame internally, we cannot get the real metric id.
      // However, we still can check the value.
      assert(metricValues.values.toSeq.exists(_ === "2"))
    }
  }

  test("metrics can be loaded by history server") {
    val metric = SQLMetrics.createMetric(sparkContext, "zanzibar")
    metric += 10L
    val metricInfo = metric.toInfo(Some(metric.value), None)
    metricInfo.update match {
      case Some(v: Long) => assert(v === 10L)
      case Some(v) => fail(s"metric value was not a Long: ${v.getClass.getName}")
      case _ => fail("metric update is missing")
    }
    assert(metricInfo.metadata === Some(AccumulatorContext.SQL_ACCUM_IDENTIFIER))
    // After serializing to JSON, the original value type is lost, but we can still
    // identify that it's a SQL metric from the metadata
    val metricInfoJson = JsonProtocol.accumulableInfoToJson(metricInfo)
    val metricInfoDeser = JsonProtocol.accumulableInfoFromJson(metricInfoJson)
    metricInfoDeser.update match {
      case Some(v: String) => assert(v.toLong === 10L)
      case Some(v) => fail(s"deserialized metric value was not a string: ${v.getClass.getName}")
      case _ => fail("deserialized metric update is missing")
    }
    assert(metricInfoDeser.metadata === Some(AccumulatorContext.SQL_ACCUM_IDENTIFIER))
  }

  test("range metrics") {
    val res1 = InputOutputMetricsHelper.run(
      spark.range(30).filter(x => x % 3 == 0).toDF()
    )
    assert(res1 === (30L, 0L, 30L) :: Nil)

    val res2 = InputOutputMetricsHelper.run(
      spark.range(150).repartition(4).filter(x => x < 10).toDF()
    )
    assert(res2 === (150L, 0L, 150L) :: (0L, 150L, 10L) :: Nil)

    // TODO: test file source V2 as well when its statistics is correctly computed.
    withSQLConf(SQLConf.USE_V1_SOURCE_LIST.key -> "parquet") {
      withTempDir { tempDir =>
        withTempView("pqS") {
          val dir = new File(tempDir, "pqS").getCanonicalPath

          spark.range(10).write.parquet(dir)
          spark.read.parquet(dir).createOrReplaceTempView("pqS")

          // The executed plan looks like:
          // Exchange RoundRobinPartitioning(2)
          // +- BroadcastNestedLoopJoin BuildLeft, Cross
          //   :- BroadcastExchange IdentityBroadcastMode
          //   :  +- Exchange RoundRobinPartitioning(3)
          //   :     +- *Range (0, 30, step=1, splits=2)
          //   +- *FileScan parquet [id#465L] Batched: true, Format: Parquet, Location: ...(ignored)
          val res3 = InputOutputMetricsHelper.run(
            spark.range(30).repartition(3).crossJoin(sql("select * from pqS")).repartition(2).toDF()
          )
          // The query above is executed in the following stages:
          //   1. range(30)                   => (30, 0, 30)
          //   2. sql("select * from pqS")    => (0, 30, 0)
          //   3. crossJoin(...) of 1. and 2. => (10, 0, 300)
          //   4. shuffle & return results    => (0, 300, 0)
          assert(res3 === (30L, 0L, 30L) :: (0L, 30L, 0L) :: (10L, 0L, 300L) :: (0L, 300L, 0L) ::
            Nil)
        }
      }
    }
  }

  test("SPARK-25278: output metrics are wrong for plans repeated in the query") {
    val name = "demo_view"
    withView(name) {
      sql(s"CREATE OR REPLACE VIEW $name AS VALUES 1,2")
      val view = spark.table(name)
      val union = view.union(view)
      testSparkPlanMetrics(union, 1, Map(
        0L -> ("Union" -> Map()),
        1L -> ("LocalTableScan" -> Map("number of output rows" -> 2L)),
        2L -> ("LocalTableScan" -> Map("number of output rows" -> 2L))))
    }
  }

  test("writing data out metrics: parquet") {
    testMetricsNonDynamicPartition("parquet", "t1")
  }

  test("writing data out metrics with dynamic partition: parquet") {
    testMetricsDynamicPartition("parquet", "parquet", "t1")
  }

  private def collectNodeWithinWholeStage[T <: SparkPlan : ClassTag](plan: SparkPlan): Seq[T] = {
    val stages = plan.collect {
      case w: WholeStageCodegenExec => w
    }
    assert(stages.length == 1, "The query plan should have one and only one whole-stage.")

    val cls = classTag[T].runtimeClass
    stages.head.collect {
      case n if n.getClass == cls => n.asInstanceOf[T]
    }
  }

  test("SPARK-25602: SparkPlan.getByteArrayRdd should not consume the input when not necessary") {
    def checkFilterAndRangeMetrics(
        df: DataFrame,
        filterNumOutputs: Int,
        rangeNumOutputs: Int): Unit = {
      val plan = df.queryExecution.executedPlan

      val filters = collectNodeWithinWholeStage[FilterExec](plan)
      assert(filters.length == 1, "The query plan should have one and only one Filter")
      assert(filters.head.metrics("numOutputRows").value == filterNumOutputs)

      val ranges = collectNodeWithinWholeStage[RangeExec](plan)
      assert(ranges.length == 1, "The query plan should have one and only one Range")
      assert(ranges.head.metrics("numOutputRows").value == rangeNumOutputs)
    }

    withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true") {
      val df = spark.range(0, 3000, 1, 2).toDF().filter('id % 3 === 0)
      df.collect()
      checkFilterAndRangeMetrics(df, filterNumOutputs = 1000, rangeNumOutputs = 3000)

      df.queryExecution.executedPlan.foreach(_.resetMetrics())
      // For each partition, we get 2 rows. Then the Filter should produce 2 rows per-partition,
      // and Range should produce 4 rows per-partition ([0, 1, 2, 3] and [15, 16, 17, 18]). Totally
      // Filter produces 4 rows, and Range produces 8 rows.
      df.queryExecution.toRdd.mapPartitions(_.take(2)).collect()
      checkFilterAndRangeMetrics(df, filterNumOutputs = 4, rangeNumOutputs = 8)

      // Top-most limit will call `CollectLimitExec.executeCollect`, which will only run the first
      // task, so totally the Filter produces 2 rows, and Range produces 4 rows ([0, 1, 2, 3]).
      val df2 = df.limit(2)
      df2.collect()
      checkFilterAndRangeMetrics(df2, filterNumOutputs = 2, rangeNumOutputs = 4)
    }
  }

  test("SPARK-25497: LIMIT within whole stage codegen should not consume all the inputs") {
    withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true") {
      // A special query that only has one partition, so there is no shuffle and the entire query
      // can be whole-stage-codegened.
      val df = spark.range(0, 1500, 1, 1).limit(10).groupBy('id).count().limit(1).filter('id >= 0)
      df.collect()
      val plan = df.queryExecution.executedPlan

      val ranges = collectNodeWithinWholeStage[RangeExec](plan)
      assert(ranges.length == 1, "The query plan should have one and only one Range")
      // The Range should only produce the first batch, i.e. 1000 rows.
      assert(ranges.head.metrics("numOutputRows").value == 1000)

      val aggs = collectNodeWithinWholeStage[HashAggregateExec](plan)
      assert(aggs.length == 2, "The query plan should have two and only two Aggregate")
      val partialAgg = aggs.filter(_.aggregateExpressions.head.mode == Partial).head
      // The partial aggregate should output 10 rows, because its input is 10 rows.
      assert(partialAgg.metrics("numOutputRows").value == 10)
      val finalAgg = aggs.filter(_.aggregateExpressions.head.mode == Final).head
      // The final aggregate should only produce 1 row, because the upstream limit only needs 1 row.
      assert(finalAgg.metrics("numOutputRows").value == 1)

      val filters = collectNodeWithinWholeStage[FilterExec](plan)
      assert(filters.length == 1, "The query plan should have one and only one Filter")
      // The final Filter should produce 1 rows, because the input is just one row.
      assert(filters.head.metrics("numOutputRows").value == 1)
    }
  }

  test("SPARK-26327: FileSourceScanExec metrics") {
    withTable("testDataForScan") {
      spark.range(10).selectExpr("id", "id % 3 as p")
        .write.partitionBy("p").saveAsTable("testDataForScan")
      // The execution plan only has 1 FileScan node.
      val df = spark.sql(
        "SELECT * FROM testDataForScan WHERE p = 1")
      testSparkPlanMetrics(df, 1, Map(
        0L -> (("Scan parquet default.testdataforscan", Map(
          "number of output rows" -> 3L,
          "number of files read" -> 2L))))
      )
    }
  }

  test("InMemoryTableScan shows the table name on UI if possible") {
    // Show table name on UI
    withView("inMemoryTable", "```a``b```") {
      sql("CREATE TEMPORARY VIEW inMemoryTable AS SELECT 1 AS c1")
      sql("CACHE TABLE inMemoryTable")
      testSparkPlanMetrics(spark.table("inMemoryTable"), 1,
        Map(1L -> (("Scan In-memory table `inMemoryTable`", Map.empty)))
      )

      sql("CREATE TEMPORARY VIEW ```a``b``` AS SELECT 2 AS c1")
      sql("CACHE TABLE ```a``b```")
      testSparkPlanMetrics(spark.table("```a``b```"), 1,
        Map(1L -> (("Scan In-memory table ```a``b```", Map.empty)))
      )
    }

    // Show InMemoryTableScan on UI
    testSparkPlanMetrics(spark.range(1).cache().select("id"), 1,
      Map(1L -> (("InMemoryTableScan", Map.empty)))
    )
  }

  test("SPARK-28332: SQLMetric merge should handle -1 properly") {
    def checkSparkPlanMetrics(plan: SparkPlan, expected: Map[String, Long]): Unit = {
      expected.foreach { case (metricName: String, metricValue: Long) =>
        assert(plan.metrics.contains(metricName), s"The query plan should have metric $metricName")
        val actualMetric = plan.metrics.get(metricName).get
        assert(actualMetric.value == metricValue,
          s"The query plan metric $metricName did not match, " +
            s"expected:$metricValue, actual:${actualMetric.value}")
      }
    }

    val df = testData.join(testData2.filter('b === 0), $"key" === $"a", "left_outer")
    df.collect()
    val plan = df.queryExecution.executedPlan

    val exchanges = plan.collect {
      case s: ShuffleExchangeExec => s
    }

    assert(exchanges.size == 2, "The query plan should have two shuffle exchanges")

    checkSparkPlanMetrics(exchanges(0), Map("dataSize" -> 3200, "shuffleRecordsWritten" -> 100))
    checkSparkPlanMetrics(exchanges(1), Map("dataSize" -> 0, "shuffleRecordsWritten" -> 0))
  }
}
