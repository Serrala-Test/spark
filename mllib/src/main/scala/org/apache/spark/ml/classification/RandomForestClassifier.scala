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

import scala.collection.mutable

import org.apache.spark.SparkContext
import org.apache.spark.annotation.AlphaComponent
import org.apache.spark.ml.impl.estimator.{PredictionModel, Predictor}
import org.apache.spark.ml.impl.tree._
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.ml.tree.{DecisionTreeModel, TreeEnsembleModel}
import org.apache.spark.ml.util.MetadataUtils
import org.apache.spark.mllib.linalg.Vector
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.tree.{RandomForest => OldRandomForest}
import org.apache.spark.mllib.tree.configuration.{Algo => OldAlgo, Strategy => OldStrategy}
import org.apache.spark.mllib.tree.model.{RandomForestModel => OldRandomForestModel}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame


/**
 * :: AlphaComponent ::
 *
 * [[http://en.wikipedia.org/wiki/Random_forest  Random Forest]] learning algorithm for
 * classification.
 * It supports both binary and multiclass labels, as well as both continuous and categorical
 * features.
 */
@AlphaComponent
final class RandomForestClassifier
  extends Predictor[Vector, RandomForestClassifier, RandomForestClassificationModel]
  with RandomForestParams with TreeClassifierParams {

  // Override parameter setters from parent trait for Java API compatibility.

  // Parameters from TreeClassifierParams:

  override def setMaxDepth(value: Int): this.type = super.setMaxDepth(value)

  override def setMaxBins(value: Int): this.type = super.setMaxBins(value)

  override def setMinInstancesPerNode(value: Int): this.type =
    super.setMinInstancesPerNode(value)

  override def setMinInfoGain(value: Double): this.type = super.setMinInfoGain(value)

  override def setMaxMemoryInMB(value: Int): this.type = super.setMaxMemoryInMB(value)

  override def setCacheNodeIds(value: Boolean): this.type = super.setCacheNodeIds(value)

  override def setCheckpointInterval(value: Int): this.type = super.setCheckpointInterval(value)

  override def setImpurity(value: String): this.type = super.setImpurity(value)

  // Parameters from TreeEnsembleParams:

  override def setSubsamplingRate(value: Double): this.type = super.setSubsamplingRate(value)

  override def setSeed(value: Long): this.type = super.setSeed(value)

  // Parameters from RandomForestParams:

  override def setNumTrees(value: Int): this.type = super.setNumTrees(value)

  override def setFeaturesPerNode(value: String): this.type = super.setFeaturesPerNode(value)

  override protected def train(
      dataset: DataFrame,
      paramMap: ParamMap): RandomForestClassificationModel = {
    val categoricalFeatures: Map[Int, Int] =
      MetadataUtils.getCategoricalFeatures(dataset.schema(paramMap(featuresCol)))
    val numClasses: Int = MetadataUtils.getNumClasses(dataset.schema(paramMap(labelCol))) match {
      case Some(n: Int) => n
      case None => throw new IllegalArgumentException("RandomForestClassifier was given input" +
        s" with invalid label column, without the number of classes specified.")
      // TODO: Automatically index labels.
    }
    val oldDataset: RDD[LabeledPoint] = extractLabeledPoints(dataset, paramMap)
    val strategy = getOldStrategy(categoricalFeatures, numClasses)
    val oldModel = OldRandomForest.trainClassifier(
      oldDataset, strategy, getNumTrees, getFeaturesPerNodeStr, getSeed.toInt)
    RandomForestClassificationModel.fromOld(oldModel, this, paramMap, categoricalFeatures)
  }

  /** (private[ml]) Create a Strategy instance to use with the old API. */
  override private[ml] def getOldStrategy(
      categoricalFeatures: Map[Int, Int],
      numClasses: Int): OldStrategy = {
    super.getOldStrategy(categoricalFeatures, numClasses, OldAlgo.Classification, getOldImpurity,
      getSubsamplingRate)
  }
}

object RandomForestClassifier {
  /** Accessor for supported impurity settings */
  final val supportedImpurities: Array[String] = TreeClassifierParams.supportedImpurities

  /** Accessor for supported featuresPerNode settings */
  final val supportedFeaturesPerNode: Array[String] = RandomForestParams.supportedFeaturesPerNode
}

/**
 * :: AlphaComponent ::
 *
 * [[http://en.wikipedia.org/wiki/Random_forest  Random Forest]] model for classification.
 * It supports both binary and multiclass labels, as well as both continuous and categorical
 * features.
 * @param trees  Decision trees in the ensemble.
 */
@AlphaComponent
final class RandomForestClassificationModel private[ml] (
    override val parent: DecisionTreeClassifier,
    override val fittingParamMap: ParamMap,
    val trees: Array[DecisionTreeClassificationModel])
  extends PredictionModel[Vector, RandomForestClassificationModel]
  with TreeEnsembleModel with Serializable {

  require(numTrees > 0, "RandomForestClassificationModel requires at least 1 tree.")

  override def getTrees: Array[DecisionTreeModel] = trees.asInstanceOf[Array[DecisionTreeModel]]

  // Note: We may add support for weights (based on tree performance) later on.
  override lazy val getTreeWeights: Array[Double] = Array.fill[Double](numTrees)(1.0)

  override def predict(features: Vector): Double = {
    // Classifies using majority votes.
    // Ignore the weights since all are 1.0 for now.
    val votes = mutable.Map.empty[Int, Double]
    trees.view.foreach { tree =>
      val prediction = tree.predict(features).toInt
      votes(prediction) = votes.getOrElse(prediction, 0.0) + 1.0 // 1.0 = weight
    }
    votes.maxBy(_._2)._1
  }

  override def toString: String = {
    s"RandomForestClassificationModel with $numTrees trees"
  }

  override def save(sc: SparkContext, path: String): Unit = {
    this.toOld.save(sc, path)
  }

  override protected def formatVersion: String = OldRandomForestModel.formatVersion

  /** Convert to a model in the old API */
  private[ml] def toOld: OldRandomForestModel = {
    new OldRandomForestModel(OldAlgo.Classification, trees.map(_.toOld))
  }
}

object RandomForestClassificationModel
  extends Loader[RandomForestClassificationModel] {

  override def load(sc: SparkContext, path: String): RandomForestClassificationModel = {
    RandomForestClassificationModel.fromOld(OldRandomForestModel.load(sc, path))
  }

  private[ml] def fromOld(oldModel: OldRandomForestModel): RandomForestClassificationModel = {
    require(oldModel.algo == OldAlgo.Classification,
      s"Cannot convert non-classification RandomForestModel (old API) to" +
        s" RandomForestClassificationModel (new API).  Algo is: ${oldModel.algo}")
    new RandomForestClassificationModel(oldModel.trees.map(DecisionTreeClassificationModel.fromOld))
  }
}
