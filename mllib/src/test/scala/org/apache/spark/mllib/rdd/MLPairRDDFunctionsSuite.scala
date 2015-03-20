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

package org.apache.spark.mllib.rdd

import org.scalatest.FunSuite

import org.apache.spark.mllib.util.MLlibTestSparkContext
import org.apache.spark.mllib.rdd.MLPairRDDFunctions._

class MLPairRDDFunctionsSuite extends FunSuite with MLlibTestSparkContext {
  test("topByKey") {
    val topMap = sc.parallelize(Array((1, 1), (1, 2), (3, 2), (3, 7), (3, 5), (5, 1), (5, 3)), 2)
      .topByKey(2)
      .collectAsMap()

    assert(topMap.size === 3)
    assert(topMap(1) === Array(2, 1))
    assert(topMap(3) === Array(7, 5))
    assert(topMap(5) === Array(3, 1))
  }
}
