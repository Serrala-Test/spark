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

import org.apache.spark.SparkContext
import org.apache.spark.annotation.AlphaComponent
import org.apache.spark.ml.impl.estimator.PredictionModel
import org.apache.spark.ml.impl.tree._
import org.apache.spark.mllib.linalg.Vector
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.tree.{DecisionTree => OldDecisionTree}
import org.apache.spark.mllib.tree.configuration.{Algo => OldAlgo, Strategy => OldStrategy}
import org.apache.spark.mllib.tree.model.{DecisionTreeModel => OldDecisionTreeModel}
import org.apache.spark.mllib.util.{Loader, Saveable}
import org.apache.spark.rdd.RDD

/*
   * @param categoricalFeatures  Map storing the arity of categorical features.
   *          E.g., an entry (j -> k) indicates that feature j is categorical
   *          with k categories indexed from 0: {0, 1, ..., k-1}.
   *          (default = empty, i.e., all features are numerical)
   * @param numClasses  Number of classes the label can take,
   *                    indexed from 0: {0, 1, ..., numClasses-1}.
   *                    (default = 2, i.e., binary classification)

 */

/**
 * :: AlphaComponent ::
 *
 * [[http://en.wikipedia.org/wiki/Decision_tree_learning Decision tree]] learning algorithm
 * for classification.
 * It supports both binary and multiclass labels, as well as both continuous and categorical
 * features.
 */
@AlphaComponent
class DecisionTreeClassifier
  extends TreeClassifier[DecisionTreeClassificationModel]
  with DecisionTreeParams[DecisionTreeClassifier]
  with TreeClassifierParams[DecisionTreeClassifier] {

  // Override parameter setters from parent trait for Java API compatibility.

  override def setMaxDepth(maxDepth: Int): DecisionTreeClassifier = super.setMaxDepth(maxDepth)

  override def setMaxBins(maxBins: Int): DecisionTreeClassifier = super.setMaxBins(maxBins)

  override def setMinInstancesPerNode(minInstancesPerNode: Int): DecisionTreeClassifier =
    super.setMinInstancesPerNode(minInstancesPerNode)

  override def setMinInfoGain(minInfoGain: Double): DecisionTreeClassifier =
    super.setMinInfoGain(minInfoGain)

  override def setMaxMemoryInMB(maxMemoryInMB: Int): DecisionTreeClassifier =
    super.setMaxMemoryInMB(maxMemoryInMB)

  override def setCacheNodeIds(cacheNodeIds: Boolean): DecisionTreeClassifier =
    super.setCacheNodeIds(cacheNodeIds)

  override def setCheckpointInterval(checkpointInterval: Int): DecisionTreeClassifier =
    super.setCheckpointInterval(checkpointInterval)

  override def setImpurity(impurity: String): DecisionTreeClassifier = super.setImpurity(impurity)

  override def run(
      input: RDD[LabeledPoint],
      categoricalFeatures: Map[Int, Int],
      numClasses: Int): DecisionTreeClassificationModel = {
    val strategy = getOldStrategy(categoricalFeatures, numClasses)
    val oldModel = OldDecisionTree.train(input, strategy)
    DecisionTreeClassificationModel.fromOld(oldModel)
  }

  /**
   * Create a Strategy instance to use with the old API.
   * TODO: Remove once we move implementation to new API.
   */
  override private[ml] def getOldStrategy(
      categoricalFeatures: Map[Int, Int],
      numClasses: Int): OldStrategy = {
    val strategy = super.getOldStrategy(categoricalFeatures, numClasses)
    strategy.algo = OldAlgo.Classification
    strategy.setImpurity(getOldImpurity)
    strategy
  }
}

object DecisionTreeClassifier {

  /** Accessor for supported impurities */
  final val supportedImpurities: Array[String] = TreeClassifierParams.supportedImpurities
}

/**
 * [[http://en.wikipedia.org/wiki/Decision_tree_learning Decision tree]] model for classification.
 * It supports both binary and multiclass labels, as well as both continuous and categorical
 * features.
 * @param rootNode  Root of the decision tree
 */
class DecisionTreeClassificationModel(override val rootNode: Node)
  extends PredictionModel[Vector, DecisionTreeClassificationModel]
  with DecisionTreeModel with Serializable {

  require(rootNode != null,
    "DecisionTreeModel given null rootNode, but it requires a non-null rootNode.")

  override protected def predict(features: Vector): Double = {
    rootNode.predict(features)
  }

  override protected def copy(): DecisionTreeClassificationModel = ???

  override def toString: String = {
    s"DecisionTreeClassificationModel of depth $depth with $numNodes nodes"
  }

  /*
  override def save(sc: SparkContext, path: String): Unit = {
    this.toOld.save(sc, path)
  }
  */

  //override protected def formatVersion: String = OldDecisionTreeModel.formatVersion

  /** Convert to a model in the old API */
  private[ml] def toOld: OldDecisionTreeModel = {
    new OldDecisionTreeModel(rootNode.toOld(1), OldAlgo.Classification)
  }
}

object DecisionTreeClassificationModel extends Loader[DecisionTreeClassificationModel] {

  override def load(sc: SparkContext, path: String): DecisionTreeClassificationModel = {
    DecisionTreeClassificationModel.fromOld(OldDecisionTreeModel.load(sc, path))
  }

  /** Convert a model from the old API */
  private[ml] def fromOld(oldModel: OldDecisionTreeModel): DecisionTreeClassificationModel = {
    require(oldModel.algo == OldAlgo.Classification,
      s"Cannot convert non-classification DecisionTreeModel (old API) to" +
        s" DecisionTreeClassificationModel (new API).  Algo is: ${oldModel.algo}")
    val rootNode = Node.fromOld(oldModel.topNode)
    new DecisionTreeClassificationModel(rootNode)
  }
}
