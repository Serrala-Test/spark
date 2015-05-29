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

package org.apache.spark.mllib.recommendation

import org.apache.spark.mllib.linalg.{DenseMatrix, Vectors}
import org.scalatest.FunSuite

import org.apache.spark.mllib.util.MLlibTestSparkContext
import org.apache.spark.mllib.util.TestingUtils._
import org.apache.spark.rdd.RDD
import org.apache.spark.util.Utils

class MatrixFactorizationModelSuite extends FunSuite with MLlibTestSparkContext {

  val rank = 2
  var userFeatures: RDD[(Int, Array[Double])] = _
  var prodFeatures: RDD[(Int, Array[Double])] = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    userFeatures = sc.parallelize(Seq((0, Array(1.0, 2.0)), (1, Array(3.0, 4.0))))
    prodFeatures = sc.parallelize(Seq((2, Array(5.0, 6.0))))
  }

  test("constructor") {
    val model = new MatrixFactorizationModel(rank, userFeatures, prodFeatures)
    assert(model.predict(0, 2) ~== 17.0 relTol 1e-14)

    intercept[IllegalArgumentException] {
      new MatrixFactorizationModel(1, userFeatures, prodFeatures)
    }

    val userFeatures1 = sc.parallelize(Seq((0, Array(1.0)), (1, Array(3.0))))
    intercept[IllegalArgumentException] {
      new MatrixFactorizationModel(rank, userFeatures1, prodFeatures)
    }

    val prodFeatures1 = sc.parallelize(Seq((2, Array(5.0))))
    intercept[IllegalArgumentException] {
      new MatrixFactorizationModel(rank, userFeatures, prodFeatures1)
    }
  }

  test("save/load") {
    val model = new MatrixFactorizationModel(rank, userFeatures, prodFeatures)
    val tempDir = Utils.createTempDir()
    val path = tempDir.toURI.toString
    def collect(features: RDD[(Int, Array[Double])]): Set[(Int, Seq[Double])] = {
      features.mapValues(_.toSeq).collect().toSet
    }
    try {
      model.save(sc, path)
      val newModel = MatrixFactorizationModel.load(sc, path)
      assert(newModel.rank === rank)
      assert(collect(newModel.userFeatures) === collect(userFeatures))
      assert(collect(newModel.productFeatures) === collect(prodFeatures))
    } finally {
      Utils.deleteRecursively(tempDir)
    }
  }

  test("batch predict API recommendProductsForUsers") {
    val model = new MatrixFactorizationModel(rank, userFeatures, prodFeatures)
    val topK = 10
    val recommendations = model.recommendProductsForUsers(topK).collectAsMap()

    assert(recommendations(0)(0).rating ~== 17.0 relTol 1e-14)
    assert(recommendations(1)(0).rating ~== 39.0 relTol 1e-14)
  }

  test("batch predict API recommendUsersForProducts") {
    val model = new MatrixFactorizationModel(rank, userFeatures, prodFeatures)
    val topK = 10
    val recommendations = model.recommendUsersForProducts(topK).collectAsMap()

    assert(recommendations(2)(0).user == 1)
    assert(recommendations(2)(0).rating ~== 39.0 relTol 1e-14)
    assert(recommendations(2)(1).user == 0)
    assert(recommendations(2)(1).rating ~== 17.0 relTol 1e-14)
  }

  test("batch similar users/products") {
    val n = 3

    val userFeatures = sc.parallelize(Seq(
      (0, Array(0.0, 3.0, 6.0, 9.0)),
      (1, Array(1.0, 4.0, 7.0, 0.0)),
      (2, Array(2.0, 5.0, 8.0, 1.0))
    ), 2)

    val model = new MatrixFactorizationModel(4, userFeatures, userFeatures)

    val topk = 2

    val similarUsers = model.similarUsers(topk)

    val similarProducts = model.similarProducts(topk)

    assert(similarUsers.numRows() == n)
    assert(similarUsers.entries.count() == n * topk)

    assert(similarProducts.numRows() == n)
    assert(similarProducts.entries.count() == n * topk)

    val similarEntriesUsers = similarUsers.entries.collect()
    val similarEntriesProducts = similarProducts.entries.collect()

    val colMags = Vectors.dense(Math.sqrt(126), Math.sqrt(66), Math.sqrt(94))

    val expected =
      new DenseMatrix(3, 3,
        Array(126.0, 54.0, 72.0, 54.0, 66.0, 78.0, 72.0, 78.0, 94.0))

    for (i <- 0 until n; j <- 0 until n) expected(i, j) /= (colMags(i) * colMags(j))

    similarEntriesUsers.foreach { entry =>
      val row = entry.i.toInt
      val col = entry.j.toInt
      assert(entry.value ~== expected(row, col) relTol 1e-6)
    }

    similarEntriesProducts.foreach { entry =>
      val row = entry.i.toInt
      val col = entry.j.toInt
      assert(entry.value ~== expected(row, col) relTol 1e-6)
    }
  }
}
