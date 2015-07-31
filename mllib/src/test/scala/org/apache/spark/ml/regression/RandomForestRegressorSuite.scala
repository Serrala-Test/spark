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

package org.apache.spark.ml.regression

import org.apache.spark.SparkFunSuite
import org.apache.spark.ml.impl.TreeTests
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.tree.{EnsembleTestHelper, RandomForest => OldRandomForest}
import org.apache.spark.mllib.tree.configuration.{Algo => OldAlgo}
import org.apache.spark.mllib.util.MLlibTestSparkContext
import org.apache.spark.mllib.util.TestingUtils._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame

/**
 * Test suite for [[RandomForestRegressor]].
 */
class RandomForestRegressorSuite extends SparkFunSuite with MLlibTestSparkContext {

  import RandomForestRegressorSuite.compareAPIs

  private var orderedLabeledPoints50_1000: RDD[LabeledPoint] = _

  override def beforeAll() {
    super.beforeAll()
    orderedLabeledPoints50_1000 =
      sc.parallelize(EnsembleTestHelper.generateOrderedLabeledPoints(numFeatures = 50, 1000))
  }

  /////////////////////////////////////////////////////////////////////////////
  // Tests calling train()
  /////////////////////////////////////////////////////////////////////////////

  def regressionTestWithContinuousFeatures(rf: RandomForestRegressor) {
    val categoricalFeaturesInfo = Map.empty[Int, Int]
    val newRF = rf
      .setImpurity("variance")
      .setMaxDepth(2)
      .setMaxBins(10)
      .setNumTrees(1)
      .setFeatureSubsetStrategy("auto")
      .setSeed(123)
    compareAPIs(orderedLabeledPoints50_1000, newRF, categoricalFeaturesInfo)
  }

  test("Regression with continuous features:" +
    " comparing DecisionTree vs. RandomForest(numTrees = 1)") {
    val rf = new RandomForestRegressor()
    regressionTestWithContinuousFeatures(rf)
  }

  test("Regression with continuous features and node Id cache :" +
    " comparing DecisionTree vs. RandomForest(numTrees = 1)") {
    val rf = new RandomForestRegressor()
      .setCacheNodeIds(true)
    regressionTestWithContinuousFeatures(rf)
  }

  /////////////////////////////////////////////////////////////////////////////
  // Tests of feature importance
  /////////////////////////////////////////////////////////////////////////////
  test("Regression feature imprtance with toy data") {
    val newRF = new RandomForestRegressor()
      .setImpurity("variance")
      .setMaxDepth(2)
      .setMaxBins(10)
      .setNumTrees(100)
      .setFeatureSubsetStrategy("auto")
      .setSeed(123)

    /* Verify results using SKLearn:

       from sklearn.ensemble import RandomForestRegressor, RandomForestClassifier
       X = np.array([
               [1, 0, 0, 0, 1],
               [0, 0, 0, 1, 0],
               [0, 0, 1, 0, 1],
               [1, 0, 0, 0, 0],
               [1, 1, 1, 0, 0]
           ])
       y = np.array([
               0,
               1,
               1,
               0,
               1
           ])
       regressor = RandomForestRegressor(random_state=0, n_estimators=100, max_depth=2).fit(X,y)
       importances = regressor.feature_importances_
       std = np.std([tree.feature_importances_ for tree in forest.estimators_], axis=0)
       indices = np.argsort(importances)[::-1]
       print("Feature importance:")
       for f in range(5):
       print("%d. feature %d (%f)" % (f + 1, indices[f], importances[indices[f]]))

       Feature importance:
       1. feature 2 (0.330000)
       2. feature 0 (0.304583)
       3. feature 3 (0.119167)
       4. feature 1 (0.111389)
       5. feature 4 (0.044861)
     */
    val data: RDD[LabeledPoint] = sc.parallelize(Seq(
      new LabeledPoint(0, Vectors.dense(1, 0, 0, 0, 1)),
      new LabeledPoint(1, Vectors.dense(0, 0, 0, 1, 0)),
      new LabeledPoint(1, Vectors.dense(0, 0, 1, 0, 1)),
      new LabeledPoint(0, Vectors.dense(1, 0, 0, 0, 0)),
      new LabeledPoint(1, Vectors.dense(1, 1, 1, 0, 0))
    ))
    val categoricalFeatures = Map.empty[Int, Int]
    val df: DataFrame = TreeTests.setMetadata(data, categoricalFeatures, numClasses = 0)

    val result =  {
      val (idx, importance) = newRF.fit(df).featureImportances.unzip
      Vectors.sparse(5, idx.toArray, importance.toArray)
    }
    val expected = Vectors.dense(0.304583, 0.111389, 0.33, 0.119167, 0.044861)

    println(newRF.fit(df).featureImportances)
    assert(result ~== expected absTol 0.02)
  }

  /////////////////////////////////////////////////////////////////////////////
  // Tests of model save/load
  /////////////////////////////////////////////////////////////////////////////

  // TODO: Reinstate test once save/load are implemented  SPARK-6725
  /*
  test("model save/load") {
    val tempDir = Utils.createTempDir()
    val path = tempDir.toURI.toString

    val trees = Range(0, 3).map(_ => OldDecisionTreeSuite.createModel(OldAlgo.Regression)).toArray
    val oldModel = new OldRandomForestModel(OldAlgo.Regression, trees)
    val newModel = RandomForestRegressionModel.fromOld(oldModel)

    // Save model, load it back, and compare.
    try {
      newModel.save(sc, path)
      val sameNewModel = RandomForestRegressionModel.load(sc, path)
      TreeTests.checkEqual(newModel, sameNewModel)
    } finally {
      Utils.deleteRecursively(tempDir)
    }
  }
  */
}

private object RandomForestRegressorSuite extends SparkFunSuite {

  /**
   * Train 2 models on the given dataset, one using the old API and one using the new API.
   * Convert the old model to the new format, compare them, and fail if they are not exactly equal.
   */
  def compareAPIs(
      data: RDD[LabeledPoint],
      rf: RandomForestRegressor,
      categoricalFeatures: Map[Int, Int]): Unit = {
    val oldStrategy =
      rf.getOldStrategy(categoricalFeatures, numClasses = 0, OldAlgo.Regression, rf.getOldImpurity)
    val oldModel = OldRandomForest.trainRegressor(
      data, oldStrategy, rf.getNumTrees, rf.getFeatureSubsetStrategy, rf.getSeed.toInt)
    val newData: DataFrame = TreeTests.setMetadata(data, categoricalFeatures, numClasses = 0)
    val newModel = rf.fit(newData)
    // Use parent from newTree since this is not checked anyways.
    val oldModelAsNew = RandomForestRegressionModel.fromOld(
      oldModel, newModel.parent.asInstanceOf[RandomForestRegressor], categoricalFeatures)
    TreeTests.checkEqual(oldModelAsNew, newModel)
  }
}
