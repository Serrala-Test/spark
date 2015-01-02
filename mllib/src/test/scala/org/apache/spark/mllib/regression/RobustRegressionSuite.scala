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

package org.apache.spark.mllib.regression

import scala.util.Random

import org.scalatest.FunSuite

import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.util.{LocalClusterSparkContext, LinearDataGenerator,
  LocalSparkContext}

class RobustRegressionSuite extends FunSuite with LocalSparkContext {

  def validatePrediction(predictions: Seq[Double], input: Seq[LabeledPoint]) {
    val numOffPredictions = predictions.zip(input).count { case (prediction, expected) =>
      // A prediction is off if the prediction is more than 0.5 away from expected value.
      math.abs(prediction - expected.label) > 0.5
    }
    // At least 80% of the predictions should be on.
    assert(numOffPredictions < input.length / 5)
  }

  // Test if we can correctly learn Y = 3 + 10*X1 + 10*X2
  test("Huber Robust regression") {
    val testRDD = sc.parallelize(LinearDataGenerator.generateLinearInput(
      3.0, Array(10.0, 10.0), 100, 42), 2).cache()
    val linReg = new HuberRobustRegressionWithSGD().setIntercept(true)
    linReg.optimizer.setNumIterations(1000).setStepSize(1.0)

    val model = linReg.run(testRDD)
    assert(model.intercept >= 2.5 && model.intercept <= 3.5)

    val weights = model.weights
    assert(weights.size === 2)
    assert(weights(0) >= 9.0 && weights(0) <= 11.0)
    assert(weights(1) >= 9.0 && weights(1) <= 11.0)

    val validationData = LinearDataGenerator.generateLinearInput(
      3.0, Array(10.0, 10.0), 100, 17)
    val validationRDD = sc.parallelize(validationData, 2).cache()

    // Test prediction on RDD.
    validatePrediction(model.predict(validationRDD.map(_.features)).collect(), validationData)

    // Test prediction on Array.
    validatePrediction(validationData.map(row => model.predict(row.features)), validationData)
  }

  // Test if we can correctly learn Y = 10*X1 + 10*X2
  test("Huber Robust regression without intercept") {
    val testRDD = sc.parallelize(LinearDataGenerator.generateLinearInput(
      0.0, Array(10.0, 10.0), 100, 42), 2).cache()
    val linReg = new HuberRobustRegressionWithSGD().setIntercept(false)
    linReg.optimizer.setNumIterations(1000).setStepSize(1.0)

    val model = linReg.run(testRDD)

    assert(model.intercept === 0.0)

    val weights = model.weights
    assert(weights.size === 2)
    assert(weights(0) >= 9.0 && weights(0) <= 11.0)
    assert(weights(1) >= 9.0 && weights(1) <= 11.0)

    val validationData = LinearDataGenerator.generateLinearInput(
      0.0, Array(10.0, 10.0), 100, 17)
    val validationRDD = sc.parallelize(validationData, 2).cache()

    // Test prediction on RDD.
    validatePrediction(model.predict(validationRDD.map(_.features)).collect(), validationData)

    // Test prediction on Array.
    validatePrediction(validationData.map(row => model.predict(row.features)), validationData)
  }

  // Test if we can correctly learn Y = 10*X1 + 10*X10000
  test("sparse Huber Robust regression without intercept") {
    val denseRDD = sc.parallelize(
      LinearDataGenerator.generateLinearInput(0.0, Array(10.0, 10.0), 100, 42), 2)
    val sparseRDD = denseRDD.map { case LabeledPoint(label, v) =>
      val sv = Vectors.sparse(10000, Seq((0, v(0)), (9999, v(1))))
      LabeledPoint(label, sv)
    }.cache()
    val linReg = new HuberRobustRegressionWithSGD().setIntercept(false)
    linReg.optimizer.setNumIterations(1000).setStepSize(1.0)

    val model = linReg.run(sparseRDD)

    assert(model.intercept === 0.0)

    val weights = model.weights
    assert(weights.size === 10000)
    assert(weights(0) >= 9.0 && weights(0) <= 11.0)
    assert(weights(9999) >= 9.0 && weights(9999) <= 11.0)

    val validationData = LinearDataGenerator.generateLinearInput(0.0, Array(10.0, 10.0), 100, 17)
    val sparseValidationData = validationData.map { case LabeledPoint(label, v) =>
      val sv = Vectors.sparse(10000, Seq((0, v(0)), (9999, v(1))))
      LabeledPoint(label, sv)
    }
    val sparseValidationRDD = sc.parallelize(sparseValidationData, 2)

      // Test prediction on RDD.
    validatePrediction(
      model.predict(sparseValidationRDD.map(_.features)).collect(), sparseValidationData)

    // Test prediction on Array.
    validatePrediction(
      sparseValidationData.map(row => model.predict(row.features)), sparseValidationData)
  }

  // Test if we can correctly learn Y = 3 + 10*X1 + 10*X2
  test("Biweight Robust regression") {
    val testRDD = sc.parallelize(LinearDataGenerator.generateLinearInput(
      3.0, Array(10.0, 10.0), 100, 42), 2).cache()
    val linReg = new BiweightRobustRegressionWithSGD().setIntercept(true)
    linReg.optimizer.setNumIterations(3000).setStepSize(1.0) //Default numIterations: 5000

    val model = linReg.run(testRDD)
    assert(model.intercept >= 2.5 && model.intercept <= 3.5)

    val weights = model.weights
    assert(weights.size === 2)
    assert(weights(0) >= 9.0 && weights(0) <= 11.0)
    assert(weights(1) >= 9.0 && weights(1) <= 11.0)

    val validationData = LinearDataGenerator.generateLinearInput(
      3.0, Array(10.0, 10.0), 100, 17)
    val validationRDD = sc.parallelize(validationData, 2).cache()

    // Test prediction on RDD.
    validatePrediction(model.predict(validationRDD.map(_.features)).collect(), validationData)

    // Test prediction on Array.
    validatePrediction(validationData.map(row => model.predict(row.features)), validationData)
  }

  // Test if we can correctly learn Y = 10*X1 + 10*X2
  test("Biweight Robust regression without intercept") {
    val testRDD = sc.parallelize(LinearDataGenerator.generateLinearInput(
      0.0, Array(10.0, 10.0), 100, 42), 2).cache()
    val linReg = new BiweightRobustRegressionWithSGD().setIntercept(false)
    linReg.optimizer.setNumIterations(4000).setStepSize(1.0) //Default numIterations: 5000

    val model = linReg.run(testRDD)

    assert(model.intercept === 0.0)

    val weights = model.weights
    assert(weights.size === 2)
    assert(weights(0) >= 9.0 && weights(0) <= 11.0)
    assert(weights(1) >= 9.0 && weights(1) <= 11.0)

    val validationData = LinearDataGenerator.generateLinearInput(
      0.0, Array(10.0, 10.0), 100, 17)
    val validationRDD = sc.parallelize(validationData, 2).cache()

    // Test prediction on RDD.
    validatePrediction(model.predict(validationRDD.map(_.features)).collect(), validationData)

    // Test prediction on Array.
    validatePrediction(validationData.map(row => model.predict(row.features)), validationData)
  }

  // Test if we can correctly learn Y = 10*X1 + 10*X10000
  test("sparse Biweight Robust regression without intercept") {
    val denseRDD = sc.parallelize(
      LinearDataGenerator.generateLinearInput(0.0, Array(10.0, 10.0), 100, 42), 2)
    val sparseRDD = denseRDD.map { case LabeledPoint(label, v) =>
      val sv = Vectors.sparse(10000, Seq((0, v(0)), (9999, v(1))))
      LabeledPoint(label, sv)
    }.cache()
    val linReg = new BiweightRobustRegressionWithSGD().setIntercept(false)
    linReg.optimizer.setNumIterations(4000).setStepSize(1.0) //Default numIterations: 5000

    val model = linReg.run(sparseRDD)

    assert(model.intercept === 0.0)

    val weights = model.weights
    assert(weights.size === 10000)
    assert(weights(0) >= 9.0 && weights(0) <= 11.0)
    assert(weights(9999) >= 9.0 && weights(9999) <= 11.0)


    val validationData = LinearDataGenerator.generateLinearInput(0.0, Array(10.0, 10.0), 100, 17)
    val sparseValidationData = validationData.map { case LabeledPoint(label, v) =>
      val sv = Vectors.sparse(10000, Seq((0, v(0)), (9999, v(1))))
      LabeledPoint(label, sv)
    }
    val sparseValidationRDD = sc.parallelize(sparseValidationData, 2)

    // Test prediction on RDD.
    validatePrediction(
      model.predict(sparseValidationRDD.map(_.features)).collect(), sparseValidationData)

    // Test prediction on Array.
    validatePrediction(
      sparseValidationData.map(row => model.predict(row.features)), sparseValidationData)
  }
}
