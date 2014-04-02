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

package org.apache.spark.mllib.linalg.rdd

import org.scalatest.FunSuite

import org.apache.spark.mllib.util.LocalSparkContext
import org.apache.spark.mllib.linalg.Vectors

class CoordinateRDDMatrixSuite extends FunSuite with LocalSparkContext {

  val m = 5
  val n = 4
  var mat: CoordinateRDDMatrix = _

  override def beforeAll() {
    super.beforeAll()
    val entries = sc.parallelize(Seq(
      (0, 0, 1.0),
      (0, 1, 2.0),
      (1, 1, 3.0),
      (1, 2, 4.0),
      (2, 2, 5.0),
      (2, 3, 6.0),
      (3, 0, 7.0),
      (3, 3, 8.0),
      (4, 1, 9.0)), 3).map { case (i, j, value) =>
      RDDMatrixEntry(i, j, value)
    }
    mat = new CoordinateRDDMatrix(entries)
  }

  test("size") {
    assert(mat.numRows() === m)
    assert(mat.numCols() === n)
  }

  test("toIndexedRowRDDMatrix") {
    val indexedRows = mat
      .toIndexedRowRDDMatrix()
      .rows
      .map(row => (row.index, row.vector))
      .collect()
      .sortBy(_._1)
      .toSeq
    assert(indexedRows === Seq(
      (0, Vectors.dense(1.0, 2.0, 0.0, 0.0)),
      (1, Vectors.dense(0.0, 3.0, 4.0, 0.0)),
      (2, Vectors.dense(0.0, 0.0, 5.0, 6.0)),
      (3, Vectors.dense(7.0, 0.0, 0.0, 8.0)),
      (4, Vectors.dense(0.0, 9.0, 0.0, 0.0))
    ))
  }
}
