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

package org.apache.spark.ml.linalg

import scala.beans.{BeanInfo, BeanProperty}

import org.apache.spark.{SparkException, SparkFunSuite}
import org.apache.spark.ml.linalg._
import org.apache.spark.mllib.util.MLlibTestSparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Row
import org.apache.spark.sql.types._

@BeanInfo
private[ml] case class MyMatrixPoint(
    @BeanProperty label: Double,
    @BeanProperty matrix: Matrix)

class MatrixUDTSuite extends SparkFunSuite with MLlibTestSparkContext {
  import testImplicits._

  test("preloaded MatrixUDT") {
    val dm1 = new DenseMatrix(2, 2, Array(0.9, 1.2, 2.3, 9.8))
    val dm2 = new DenseMatrix(3, 2, Array(0.0, 1.21, 2.3, 9.8, 9.0, 0.0))
    val dm3 = new DenseMatrix(0, 0, Array())
    val sm1 = dm1.toSparse
    val sm2 = dm2.toSparse
    val sm3 = dm3.toSparse

    val matrixDF = Seq(
      MyMatrixPoint(1.0, dm1),
      MyMatrixPoint(2.0, dm2),
      MyMatrixPoint(3.0, dm3),
      MyMatrixPoint(4.0, sm1),
      MyMatrixPoint(5.0, sm2),
      MyMatrixPoint(6.0, sm3)).toDF()

    val labels = matrixDF.select('label).as[Double].collect()
    assert(labels.size === 6)
    assert(labels.sorted === Array(1.0, 2.0, 3.0, 4.0, 5.0, 6.0))

    val matrices = matrixDF.select('matrix).rdd.map { case Row(m: Matrix) => m }.collect()
    assert(matrices.contains(dm1))
    assert(matrices.contains(dm2))
    assert(matrices.contains(dm3))
    assert(matrices.contains(sm1))
    assert(matrices.contains(sm2))
    assert(matrices.contains(sm3))
  }
}
