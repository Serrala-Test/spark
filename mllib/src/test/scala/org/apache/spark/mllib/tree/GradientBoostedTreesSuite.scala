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

package org.apache.spark.mllib.tree

import org.scalatest.FunSuite

import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.tree.configuration.Algo._
import org.apache.spark.mllib.tree.configuration.{BoostingStrategy, Strategy}
import org.apache.spark.mllib.tree.impurity.Variance
import org.apache.spark.mllib.tree.loss.{AbsoluteError, SquaredError, LogLoss}
import org.apache.spark.mllib.tree.model.GradientBoostedTreesModel
import org.apache.spark.mllib.util.MLlibTestSparkContext
import org.apache.spark.util.Utils


/**
 * Test suite for [[GradientBoostedTrees]].
 */
class GradientBoostedTreesSuite extends FunSuite with MLlibTestSparkContext {

  test("Regression with continuous features: SquaredError") {
    GradientBoostedTreesSuite.testCombinations.foreach {
      case (numIterations, learningRate, subsamplingRate) =>
        val rdd = sc.parallelize(GradientBoostedTreesSuite.data, 2)

        val treeStrategy = new Strategy(algo = Regression, impurity = Variance, maxDepth = 2,
          categoricalFeaturesInfo = Map.empty, subsamplingRate = subsamplingRate)
        val boostingStrategy =
          new BoostingStrategy(treeStrategy, SquaredError, numIterations, learningRate)

        val gbt = GradientBoostedTrees.train(rdd, boostingStrategy)

        assert(gbt.trees.size === numIterations)
        try {
          EnsembleTestHelper.validateRegressor(gbt, GradientBoostedTreesSuite.data, 0.06)
        } catch {
          case e: java.lang.AssertionError =>
            println(s"FAILED for numIterations=$numIterations, learningRate=$learningRate," +
              s" subsamplingRate=$subsamplingRate")
            throw e
        }

        val remappedInput = rdd.map(x => new LabeledPoint((x.label * 2) - 1, x.features))
        val dt = DecisionTree.train(remappedInput, treeStrategy)

        // Make sure trees are the same.
        assert(gbt.trees.head.toString == dt.toString)
    }
  }

  test("Regression with continuous features: Absolute Error") {
    GradientBoostedTreesSuite.testCombinations.foreach {
      case (numIterations, learningRate, subsamplingRate) =>
        val rdd = sc.parallelize(GradientBoostedTreesSuite.data, 2)

        val treeStrategy = new Strategy(algo = Regression, impurity = Variance, maxDepth = 2,
          categoricalFeaturesInfo = Map.empty, subsamplingRate = subsamplingRate)
        val boostingStrategy =
          new BoostingStrategy(treeStrategy, AbsoluteError, numIterations, learningRate)

        val gbt = GradientBoostedTrees.train(rdd, boostingStrategy)

        assert(gbt.trees.size === numIterations)
        try {
          EnsembleTestHelper.validateRegressor(gbt, GradientBoostedTreesSuite.data, 0.85, "mae")
        } catch {
          case e: java.lang.AssertionError =>
            println(s"FAILED for numIterations=$numIterations, learningRate=$learningRate," +
              s" subsamplingRate=$subsamplingRate")
            throw e
        }

        val remappedInput = rdd.map(x => new LabeledPoint((x.label * 2) - 1, x.features))
        val dt = DecisionTree.train(remappedInput, treeStrategy)

        // Make sure trees are the same.
        assert(gbt.trees.head.toString == dt.toString)
    }
  }

  test("Binary classification with continuous features: Log Loss") {
    GradientBoostedTreesSuite.testCombinations.foreach {
      case (numIterations, learningRate, subsamplingRate) =>
        val rdd = sc.parallelize(GradientBoostedTreesSuite.data, 2)

        val treeStrategy = new Strategy(algo = Classification, impurity = Variance, maxDepth = 2,
          numClasses = 2, categoricalFeaturesInfo = Map.empty,
          subsamplingRate = subsamplingRate)
        val boostingStrategy =
          new BoostingStrategy(treeStrategy, LogLoss, numIterations, learningRate)

        val gbt = GradientBoostedTrees.train(rdd, boostingStrategy)

        assert(gbt.trees.size === numIterations)
        try {
          EnsembleTestHelper.validateClassifier(gbt, GradientBoostedTreesSuite.data, 0.9)
        } catch {
          case e: java.lang.AssertionError =>
            println(s"FAILED for numIterations=$numIterations, learningRate=$learningRate," +
              s" subsamplingRate=$subsamplingRate")
            throw e
        }

        val remappedInput = rdd.map(x => new LabeledPoint((x.label * 2) - 1, x.features))
        val ensembleStrategy = treeStrategy.copy
        ensembleStrategy.algo = Regression
        ensembleStrategy.impurity = Variance
        val dt = DecisionTree.train(remappedInput, ensembleStrategy)

        // Make sure trees are the same.
        assert(gbt.trees.head.toString == dt.toString)
    }
  }

  test("SPARK-5496: BoostingStrategy.defaultParams should recognize Classification") {
    for (algo <- Seq("classification", "Classification", "regression", "Regression")) {
      BoostingStrategy.defaultParams(algo)
    }
  }

  test("model save/load") {
    val tempDir = Utils.createTempDir()
    val path = tempDir.toURI.toString

    val trees = Range(0, 3).map(_ => DecisionTreeSuite.createModel(Regression)).toArray
    val treeWeights = Array(0.1, 0.3, 1.1)

    Array(Classification, Regression).foreach { algo =>
      val model = new GradientBoostedTreesModel(algo, trees, treeWeights)

      // Save model, load it back, and compare.
      try {
        model.save(sc, path)
        val sameModel = GradientBoostedTreesModel.load(sc, path)
        assert(model.algo == sameModel.algo)
        model.trees.zip(sameModel.trees).foreach { case (treeA, treeB) =>
          DecisionTreeSuite.checkEqual(treeA, treeB)
        }
        assert(model.treeWeights === sameModel.treeWeights)
      } finally {
        Utils.deleteRecursively(tempDir)
      }
    }
  }

  test("runWithValidation performs better on a validation dataset (Regression)") {
    // Set numIterations large enough so that it stops early.
    val numIterations = 20
    val trainRdd = sc.parallelize(GradientBoostedTreesSuite.trainData, 2)
    val validateRdd = sc.parallelize(GradientBoostedTreesSuite.validateData, 2)

    val treeStrategy = new Strategy(algo = Regression, impurity = Variance, maxDepth = 2,
      categoricalFeaturesInfo = Map.empty)
    Array(SquaredError, AbsoluteError).foreach { error =>
      val boostingStrategy =
        new BoostingStrategy(treeStrategy, error, numIterations, validationTol = 0.0)

      val gbtValidate = new GradientBoostedTrees(boostingStrategy).
        runWithValidation(trainRdd, validateRdd)
      assert(gbtValidate.numTrees !== numIterations)

      val gbt = GradientBoostedTrees.train(trainRdd, boostingStrategy)
      val errorWithoutValidation = error.computeError(gbt, validateRdd)
      val errorWithValidation = error.computeError(gbtValidate, validateRdd)
      assert(errorWithValidation < errorWithoutValidation)
    }
  }

  test("runWithValidation performs better on a validation dataset (Classification)") {
    // Set numIterations large enough so that it stops early.
    val numIterations = 20
    val trainRdd = sc.parallelize(GradientBoostedTreesSuite.trainData, 2)
    val validateRdd = sc.parallelize(GradientBoostedTreesSuite.validateData, 2)

    val treeStrategy = new Strategy(algo = Classification, impurity = Variance, maxDepth = 2,
      categoricalFeaturesInfo = Map.empty)
    val boostingStrategy =
      new BoostingStrategy(treeStrategy, LogLoss, numIterations, validationTol = 0.0)

    // Test that it stops early.
    val gbtValidate = new GradientBoostedTrees(boostingStrategy).
      runWithValidation(trainRdd, validateRdd)
    assert(gbtValidate.numTrees !== numIterations)

    // Remap labels to {-1, 1}
    val remappedInput = validateRdd.map(x => new LabeledPoint(2 * x.label - 1, x.features))

    // The error checked for internally in the GradientBoostedTrees is based on Regression.
    // Hence for the validation model, the Classification error need not be strictly less than
    // that done with validation.
    val gbtValidateRegressor = new GradientBoostedTreesModel(
      Regression, gbtValidate.trees, gbtValidate.treeWeights)
    val errorWithValidation = LogLoss.computeError(gbtValidateRegressor, remappedInput)

    val gbt = GradientBoostedTrees.train(trainRdd, boostingStrategy)
    val gbtRegressor = new GradientBoostedTreesModel(Regression, gbt.trees, gbt.treeWeights)
    val errorWithoutValidation = LogLoss.computeError(gbtRegressor, remappedInput)

    assert(errorWithValidation < errorWithoutValidation)
  }

}

private object GradientBoostedTreesSuite {

  // Combinations for estimators, learning rates and subsamplingRate
  val testCombinations = Array((10, 1.0, 1.0), (10, 0.1, 1.0), (10, 0.5, 0.75), (10, 0.1, 0.75))

  val data = EnsembleTestHelper.generateOrderedLabeledPoints(numFeatures = 10, 100)
  val trainData = EnsembleTestHelper.generateOrderedLabeledPoints(numFeatures = 20, 120)
  val validateData = EnsembleTestHelper.generateOrderedLabeledPoints(numFeatures = 20, 80)
}
