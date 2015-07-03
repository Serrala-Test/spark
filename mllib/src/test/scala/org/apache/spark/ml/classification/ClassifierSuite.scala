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

package org.apache.spark.ml.classification

import org.apache.spark.SparkFunSuite
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.mllib.linalg.{Vector, Vectors}

final class TestClassificationModel(
  override val numClasses: Int)
    extends ClassificationModel[Vector, TestClassificationModel] {
  override val uid = null
  override def copy(extra: org.apache.spark.ml.param.ParamMap):
      TestClassificationModel = {
    defaultCopy(extra)
  }

  override def predictRaw(input: Vector) = {
    input
  }
  def friendlyPredict(input: Vector) = {
    predict(input)
  }
}


class ClassifierSuite extends SparkFunSuite {

  test("test thresholding") {
    val threshold = Array(0.5, 0.2)
    val testModel = (new TestClassificationModel(2)).setThresholds(threshold)
    assert(testModel.friendlyPredict(Vectors.dense(Array(1.0, 1.0))) == 1.0)
    assert(testModel.friendlyPredict(Vectors.dense(Array(1.0, 0.2))) == 0.0)
  }

  test("test thresholding not required") {
    val testModel = new TestClassificationModel(2)
    assert(testModel.friendlyPredict(Vectors.dense(Array(1.0, 2.0))) == 1.0)
  }
}
