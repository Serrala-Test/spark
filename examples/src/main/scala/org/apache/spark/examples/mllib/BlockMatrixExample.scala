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

// scalastyle:off println
package org.apache.spark.examples.mllib

import org.apache.spark.{SparkConf, SparkContext}
// $example on$
import org.apache.spark.mllib.linalg.distributed.{BlockMatrix, CoordinateMatrix, MatrixEntry}
// $example off$
import org.apache.spark.rdd.RDD

object BlockMatrixExample {

  def main(args: Array[String]) {

    val conf = new SparkConf().setAppName("BlockMatrixExample").setMaster("local[*]")
    val sc = new SparkContext(conf)

    val me1 = MatrixEntry(0, 0, 1.2)
    val me2 = MatrixEntry(1, 0, 2.1)
    val me3 = MatrixEntry(6, 1, 3.7)

    // $example on$
    // an RDD of (i, j, v) matrix entries
    val entries: RDD[MatrixEntry] = sc.parallelize(Seq(me1, me2, me3))
    // Create a CoordinateMatrix from an RDD[MatrixEntry].
    val coordMat: CoordinateMatrix = new CoordinateMatrix(entries)
    // Transform the CoordinateMatrix to a BlockMatrix
    val matA: BlockMatrix = coordMat.toBlockMatrix().cache()

    // Validate whether the BlockMatrix is set up properly.
    // Throws an Exception when it is not valid.
    // Nothing happens if it is valid.
    matA.validate()

    // Calculate A^T A.
    val ata = matA.transpose.multiply(matA)
    // $example off$

    sc.stop()
  }
}
// scalastyle:on println
