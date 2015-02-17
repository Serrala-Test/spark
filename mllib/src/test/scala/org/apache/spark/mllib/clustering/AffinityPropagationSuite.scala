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

package org.apache.spark.mllib.clustering

import scala.collection.mutable

import org.scalatest.FunSuite

import org.apache.spark.graphx.{Edge, Graph}
import org.apache.spark.mllib.util.MLlibTestSparkContext
import org.apache.spark.mllib.util.TestingUtils._

class AffinityPropagationSuite extends FunSuite with MLlibTestSparkContext {

  import org.apache.spark.mllib.clustering.AffinityPropagation._

  test("affinity propagation") {
    /*
     We use the following graph to test AP.

     15-14 -13  12
     . \      /    
     4 . 3 . 2  
     |   |   |   
     5   0 . 1  10
     | \     .   |
     6   7 . 8 - 9 - 11
     */

    val similarities = Seq[(Long, Long, Double)]((0, 1, -8.2), (0, 3, -1.8), (1, 2, -0.4),
      (1, 8, -8.1), (2, 3, -9.2), (2, 12, -1.1), (3, 15, -0.8), (3, 4, -10.1), (4, 5, -0.7),
      (4, 15, -11.8), (5, 6, -0.7), (5, 7, -0.41), (7, 8, -8.1), (8, 9, -0.55), (9, 10, -1.8),
      (9, 11, -0.76), (13, 14, -0.15), (14, 15, -0.67))
    val model = new AffinityPropagation()
      .setMaxIterations(100)
      .run(sc.parallelize(similarities, 2))

    assert(model.getK() == 4)
    assert(model.findCluster(5).toSeq.sorted == Seq(4, 5, 6, 7))
    assert(model.findClusterID(14) == model.findClusterID(15))
  }

  test("normalize") {
    /*
     Test normalize() with the following graph:

     0 - 3
     | \ |
     1 - 2

     The similarity matrix (A) is

     0 1 1 1
     1 0 1 0
     1 1 0 1
     1 0 1 0

     D is diag(3, 2, 3, 2) and hence S is

       0 1/3 1/3 1/3
     1/2   0 1/2   0
     1/3 1/3   0 1/3
     1/2   0 1/2   0
     */
    val similarities = Seq[(Long, Long, Double)](
      (0, 1, 1.0), (1, 0, 1.0), (0, 2, 1.0), (2, 0, 1.0), (0, 3, 1.0), (3, 0, 1.0),
      (1, 2, 1.0), (2, 1, 1.0), (2, 3, 1.0), (3, 2, 1.0))
    val expected = Array(
      Array(0.0,     1.0/3.0, 1.0/3.0, 1.0/3.0),
      Array(1.0/2.0,     0.0, 1.0/2.0,     0.0),
      Array(1.0/3.0, 1.0/3.0,     0.0, 1.0/3.0),
      Array(1.0/2.0,     0.0, 1.0/2.0,     0.0))
    val s = constructGraph(sc.parallelize(similarities, 2), true, false)
    s.edges.collect().foreach { case Edge(i, j, x) =>
      assert(x(0) ~== expected(i.toInt)(j.toInt) absTol 1e-14)
    }
  }
}
