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

package org.apache.spark.mllib.evaluation

import org.apache.spark.SparkFunSuite
import org.apache.spark.mllib.feature.RandomProjection
import org.apache.spark.mllib.linalg.distributed.{BlockMatrix, CoordinateMatrix, MatrixEntry}
import org.apache.spark.mllib.util.MLlibTestSparkContext
import java.io._


/**
 * some tests with pairwise distances
 */
class PairwiseDistancesSuite extends SparkFunSuite with MLlibTestSparkContext {

  test("compute the pairwise distances accordingly") {
    // build the test matrix

    val testMatrixSize = 100

    val values = 0 until testMatrixSize
    val data = values.flatMap(x => {
      values.map { y =>
        MatrixEntry(x, y, x)
      }
    }).toList

    // make the matrix distributed
    val rdd = sc.parallelize(data)
    val coordMat: CoordinateMatrix = new CoordinateMatrix(rdd, testMatrixSize, testMatrixSize)
    val matA: BlockMatrix = coordMat.toBlockMatrix().cache()

    val origDimension = matA.numCols().toInt
    val origRows = matA.numRows().toInt
    // scalastyle:off println
    println(s"dataset rows: $origRows")
    println(s"dataset dimension: $origDimension")
    matA.validate()
    val fold = 0 until 5
    val dimensions = 5 until 16

    // lets print the results for further processing (graphs)
    val path = s"${new File(".").getCanonicalPath()}/../../../results_pairwise_spark_$testMatrixSize.csv"
    val pw = new PrintWriter(new File(path))

    // the label of the columns
    pw.write("x,y_orig,y_reduced,y_error\r\n")
    dimensions.foreach { dimension =>
      val error = fold.map { item =>
        evaluatePairwiseDistances(matA, dimension)
      }
      val meanError = error.map(_.error).sum / error.length
      val origDistance = error.map(_.original).sum / error.length

      val reducedDistance = error.map(_.reduced).sum / error.length
      pw.write(s"$dimension,$origDistance,$reducedDistance,$meanError\r\n")
    }
    pw.close
    assert(true == true)
  }

  case class SumDistances(original: Double, reduced: Double, error: Double)
  /**
   *
   * @param dataset
   * @param intrinsicDimension
   * @return
   */
  private def evaluatePairwiseDistances(dataset: BlockMatrix, intrinsicDimension: Int = 5) = {

    val distancesOriginal = PairwiseDistances.calculatePairwiseDistances(dataset)

    val rp = new RandomProjection(intrinsicDimension)
    val reduced = dataset.multiply(rp.computeRPMatrix(sc, dataset.numCols().toInt))
    val distancesReduced = PairwiseDistances.calculatePairwiseDistances(reduced)

    // compare the reductions
    val zipped = distancesOriginal.zip(distancesReduced)
    val accumulateDistances = zipped.foldLeft(SumDistances(0, 0, 0))((counter, item) => {
      require(item._1.key == item._2.key, s"'${item._1.key}' must be equal to '${item._2.key}'")
      val origDistance = item._1.similarity
      val reducedDistance = item._2.similarity
      val error = if (origDistance > reducedDistance) {
        origDistance - reducedDistance
      } else {
        reducedDistance - origDistance
      }
      //println(s"#${item._1.key} orig: $origDistance")
      SumDistances(
        counter.original + origDistance,
        counter.reduced + reducedDistance,
        counter.error + error)
    })
    SumDistances(
      accumulateDistances.original / zipped.length,
      accumulateDistances.reduced / zipped.length,
      accumulateDistances.error / zipped.length)
  }
}
