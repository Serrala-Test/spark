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

package org.apache.spark.ml.param

trait HasRegParam extends Params {

  val regParam: DoubleParam = new DoubleParam(this, "regParam", "regularization parameter")

  def getRegParam: Double = {
    get(regParam)
  }
}

trait HasMaxIter extends Params {

  val maxIter: IntParam = new IntParam(this, "maxIter", "max number of iterations")

  def getMaxIter: Int = {
    get(maxIter)
  }
}

trait HasFeaturesCol extends Params {

  val featuresCol: Param[String] =
    new Param(this, "featuresCol", "features column name", "features")

  def getFeaturesCol: String = {
    get(featuresCol)
  }
}

trait HasLabelCol extends Params {

  val labelCol: Param[String] = new Param(this, "labelCol", "label column name", "label")

  def getLabelCol: String = {
    get(labelCol)
  }
}

trait HasScoreCol extends Params {

  val scoreCol: Param[String] = new Param(this, "scoreCol", "score column name", "score")

  def getScoreCol: String = {
    get(scoreCol)
  }
}

trait HasThreshold extends Params {

  val threshold: DoubleParam = new DoubleParam(this, "threshold", "threshold for prediction")

  def getThreshold: Double = {
    get(threshold)
  }
}

trait HasMetricName extends Params {

  val metricName: Param[String] = new Param(this, "metricName", "metric name for evaluation")

  def getMetricName: String = {
    get(metricName)
  }
}

trait HasInputCol extends Params {

  val inputCol: Param[String] = new Param(this, "inputCol", "input column name")

  def getInputCol: String = {
    get(inputCol)
  }
}

trait HasOutputCol extends Params {

  val outputCol: Param[String] = new Param(this, "outputCol", "output column name")

  def getOutputCol: String = {
    get(outputCol)
  }
}
