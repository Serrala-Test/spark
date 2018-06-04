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

package org.apache.spark.ml.clustering

import org.apache.spark.{SparkException, SparkFunSuite}
import org.apache.spark.ml.util.DefaultReadWriteTest
import org.apache.spark.mllib.util.MLlibTestSparkContext
import org.apache.spark.sql.{DataFrame, Dataset, Row, SparkSession}
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.types._


class PowerIterationClusteringSuite extends SparkFunSuite
  with MLlibTestSparkContext with DefaultReadWriteTest {

  import testImplicits._

  @transient var data: Dataset[_] = _
  final val r1 = 1.0
  final val n1 = 10
  final val r2 = 4.0
  final val n2 = 40

  override def beforeAll(): Unit = {
    super.beforeAll()

    data = PowerIterationClusteringSuite.generatePICData(spark, r1, r2, n1, n2)
  }

  test("default parameters") {
    val pic = new PowerIterationClustering()

    assert(pic.getK === 2)
    assert(pic.getMaxIter === 20)
    assert(pic.getInitMode === "random")
    assert(pic.getSrcCol === "src")
    assert(pic.getDstCol === "dst")
    assert(!pic.isDefined(pic.weightCol))
  }

  test("parameter validation") {
    intercept[IllegalArgumentException] {
      new PowerIterationClustering().setK(1)
    }
    intercept[IllegalArgumentException] {
      new PowerIterationClustering().setInitMode("no_such_a_mode")
    }
    intercept[IllegalArgumentException] {
      new PowerIterationClustering().setSrcCol("")
    }
    intercept[IllegalArgumentException] {
      new PowerIterationClustering().setDstCol("")
    }
  }

  test("power iteration clustering") {
    val n = n1 + n2

    val result = new PowerIterationClustering()
      .setK(2)
      .setMaxIter(40)
      .setWeightCol("weight")
      .assignClusters(data).as[(Long, Int)].collect().toSet
    val expectedResult = (0 until n1).map(x => (x, 1)).toSet ++
      (n1 until n).map(x => (x, 0)).toSet
    assert(result === expectedResult)

    val result2 = new PowerIterationClustering()
      .setK(2)
      .setMaxIter(10)
      .setInitMode("degree")
      .setWeightCol("weight")
      .assignClusters(data).as[(Long, Int)].collect().toSet
    assert(result2 === expectedResult)
  }

  test("supported input types") {
    val pic = new PowerIterationClustering()
      .setK(2)
      .setMaxIter(1)
      .setWeightCol("weight")

    def runTest(srcType: DataType, dstType: DataType, weightType: DataType): Unit = {
      val typedData = data.select(
        col("src").cast(srcType).alias("src"),
        col("dst").cast(dstType).alias("dst"),
        col("weight").cast(weightType).alias("weight")
      )
      pic.assignClusters(typedData).collect()
    }

    for (srcType <- Seq(IntegerType, LongType)) {
      runTest(srcType, LongType, DoubleType)
    }
    for (dstType <- Seq(IntegerType, LongType)) {
      runTest(LongType, dstType, DoubleType)
    }
    for (weightType <- Seq(FloatType, DoubleType)) {
      runTest(LongType, LongType, weightType)
    }
  }

  test("invalid input: negative similarity") {
    val pic = new PowerIterationClustering()
      .setMaxIter(1)
      .setWeightCol("weight")
    val badData = spark.createDataFrame(Seq(
      (0, 1, -1.0),
      (1, 0, -1.0)
    )).toDF("src", "dst", "weight")
    val msg = intercept[SparkException] {
      pic.assignClusters(badData)
    }.getCause.getMessage
    assert(msg.contains("Similarity must be nonnegative"))
  }

  test("read/write") {
    val t = new PowerIterationClustering()
      .setK(4)
      .setMaxIter(100)
      .setInitMode("degree")
      .setSrcCol("src1")
      .setDstCol("dst1")
      .setWeightCol("weight")
    testDefaultReadWrite(t)
  }
}

object PowerIterationClusteringSuite {

  /** Generates a circle of points. */
  private def genCircle(r: Double, n: Int): Array[(Double, Double)] = {
    Array.tabulate(n) { i =>
      val theta = 2.0 * math.Pi * i / n
      (r * math.cos(theta), r * math.sin(theta))
    }
  }

  /** Computes Gaussian similarity. */
  private def sim(x: (Double, Double), y: (Double, Double)): Double = {
    val dist2 = (x._1 - y._1) * (x._1 - y._1) + (x._2 - y._2) * (x._2 - y._2)
    math.exp(-dist2 / 2.0)
  }

  def generatePICData(
      spark: SparkSession,
      r1: Double,
      r2: Double,
      n1: Int,
      n2: Int): DataFrame = {
    // Generate two circles following the example in the PIC paper.
    val n = n1 + n2
    val points = genCircle(r1, n1) ++ genCircle(r2, n2)

    val rows = (for (i <- 1 until n) yield {
      for (j <- 0 until i) yield {
        (i.toLong, j.toLong, sim(points(i), points(j)))
      }
    }).flatMap(_.iterator)

    spark.createDataFrame(rows).toDF("src", "dst", "weight")
  }

}
