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

import org.apache.hadoop.fs.Path

import org.apache.spark.annotation.Since
import org.apache.spark.internal.Logging
import org.apache.spark.ml.linalg._
import org.apache.spark.ml.param._
import org.apache.spark.ml.regression._
import org.apache.spark.ml.util._
import org.apache.spark.ml.util.Instrumentation.instrumented
import org.apache.spark.mllib.linalg.{Vector => OldVector}
import org.apache.spark.mllib.linalg.VectorImplicits._
import org.apache.spark.mllib.optimization.GradientDescent
import org.apache.spark.mllib.regression.{LabeledPoint => OldLabeledPoint}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Dataset, Row}
import org.apache.spark.sql.functions.col
import org.apache.spark.storage.StorageLevel

/**
 * Params for FMClassifier.
 */
private[classification] trait FMClassifierParams extends ProbabilisticClassifierParams
  with FactorizationMachinesParams {
}

/**
 * Factorization Machines learning algorithm for classification.
 * It supports normal gradient descent and AdamW solver.
 *
 * The implementation is based upon:
 * <a href="https://www.csie.ntu.edu.tw/~b97053/paper/Rendle2010FM.pdf">
 * S. Rendle. "Factorization machines" 2010</a>.
 *
 * FM is able to estimate interactions even in problems with huge sparsity
 * (like advertising and recommendation system).
 * FM formula is:
 * {{{
 *   y = w_0 + \sum\limits^n_{i-1} w_i x_i +
 *     \sum\limits^n_{i=1} \sum\limits^n_{j=i+1} \langle v_i, v_j \rangle x_i x_j
 * }}}
 * First two terms denote global bias and linear term (as same as linear regression),
 * and last term denotes pairwise interactions term. {{{v_i}}} describes the i-th variable
 * with k factors.
 *
 * FM classification model uses logistic loss which can be solved by gradient descent method, and
 * regularization terms like L2 are usually added to the loss function to prevent overfitting.
 *
 * @note Multiclass labels are not currently supported.
 */
@Since("3.0.0")
class FMClassifier @Since("3.0.0") (
  @Since("3.0.0") override val uid: String)
  extends ProbabilisticClassifier[Vector, FMClassifier, FMClassifierModel]
  with FMClassifierParams with DefaultParamsWritable with Logging {

  import org.apache.spark.ml.regression.BaseFactorizationMachinesGradient.{LogisticLoss, parseLoss}
  import org.apache.spark.ml.regression.FMRegressor.initCoefficients

  @Since("3.0.0")
  def this() = this(Identifiable.randomUID("fmc"))

  /**
   * Set the dimensionality of the factors.
   * Default is 8.
   *
   * @group setParam
   */
  @Since("3.0.0")
  def setNumFactors(value: Int): this.type = set(numFactors, value)
  setDefault(numFactors -> 8)

  /**
   * Set whether to fit global bias term.
   * Default is true.
   *
   * @group setParam
   */
  @Since("3.0.0")
  def setFitBias(value: Boolean): this.type = set(fitBias, value)
  setDefault(fitBias -> true)

  /**
   * Set whether to fit linear term.
   * Default is true.
   *
   * @group setParam
   */
  @Since("3.0.0")
  def setFitLinear(value: Boolean): this.type = set(fitLinear, value)
  setDefault(fitLinear -> true)

  /**
   * Set the L2 regularization parameter.
   * Default is 0.0.
   *
   * @group setParam
   */
  @Since("3.0.0")
  def setRegParam(value: Double): this.type = set(regParam, value)
  setDefault(regParam -> 0.0)

  /**
   * Set the mini-batch fraction parameter.
   * Default is 1.0.
   *
   * @group setParam
   */
  @Since("3.0.0")
  def setMiniBatchFraction(value: Double): this.type = {
    require(value > 0 && value <= 1.0,
      s"Fraction for mini-batch SGD must be in range (0, 1] but got $value")
    set(miniBatchFraction, value)
  }
  setDefault(miniBatchFraction -> 1.0)

  /**
   * Set the standard deviation of initial coefficients.
   * Default is 0.01.
   *
   * @group setParam
   */
  @Since("3.0.0")
  def setInitStd(value: Double): this.type = set(initStd, value)
  setDefault(initStd -> 0.01)

  /**
   * Set the maximum number of iterations.
   * Default is 100.
   *
   * @group setParam
   */
  @Since("3.0.0")
  def setMaxIter(value: Int): this.type = set(maxIter, value)
  setDefault(maxIter -> 100)

  /**
   * Set the initial step size for the first step (like learning rate).
   * Default is 1.0.
   *
   * @group setParam
   */
  @Since("3.0.0")
  def setStepSize(value: Double): this.type = set(stepSize, value)
  setDefault(stepSize -> 1.0)

  /**
   * Set the convergence tolerance of iterations.
   * Default is 1E-6.
   *
   * @group setParam
   */
  @Since("3.0.0")
  def setTol(value: Double): this.type = set(tol, value)
  setDefault(tol -> 1E-6)

  /**
   * Set the solver algorithm used for optimization.
   * Default is adamW.
   *
   * @group setParam
   */
  @Since("3.0.0")
  def setSolver(value: String): this.type = set(solver, value)
  setDefault(solver -> AdamW)

  override protected[spark] def train(dataset: Dataset[_]): FMClassifierModel = {
    val handlePersistence = dataset.rdd.getStorageLevel == StorageLevel.NONE
    train(dataset, handlePersistence)
  }

  protected[spark] def train(
      dataset: Dataset[_],
      handlePersistence: Boolean): FMClassifierModel = instrumented { instr =>
    val instances: RDD[OldLabeledPoint] =
      dataset.select(col($(labelCol)), col($(featuresCol))).rdd.map {
        case Row(label: Double, features: Vector) =>
          require(label == 0 || label == 1, s"FMClassifier was given" +
            s" dataset with invalid label $label.  Labels must be in {0,1}; note that" +
            s" FMClassifier currently only supports binary classification.")
          OldLabeledPoint(label, features)
      }

    if (handlePersistence) instances.persist(StorageLevel.MEMORY_AND_DISK)

    val numClasses = 2
    if (isDefined(thresholds)) {
      require($(thresholds).length == numClasses, this.getClass.getSimpleName +
        ".train() called with non-matching numClasses and thresholds.length." +
        s" numClasses=$numClasses, but thresholds has length ${$(thresholds).length}")
    }

    instr.logPipelineStage(this)
    instr.logDataset(dataset)
    instr.logParams(this, numFactors, fitBias, fitLinear, regParam,
      miniBatchFraction, initStd, maxIter, stepSize, tol, solver)
    instr.logNumClasses(numClasses)

    val numFeatures = instances.first().features.size
    instr.logNumFeatures(numFeatures)

    val data = instances.map{ case OldLabeledPoint(label, features) => (label, features) }

    // initialize coefficients
    val (initialCoefficients, coefficientsSize) = initCoefficients(
      numFeatures, $(numFactors), $(fitBias), $(fitLinear), $(initStd))

    // optimize coefficients with gradient descent
    val gradient = parseLoss(
      LogisticLoss, $(numFactors), $(fitBias), $(fitLinear), numFeatures)

    val updater = parseSolver($(solver), coefficientsSize)

    val optimizer = new GradientDescent(gradient, updater)
      .setStepSize($(stepSize))
      .setNumIterations($(maxIter))
      .setRegParam($(regParam))
      .setMiniBatchFraction($(miniBatchFraction))
      .setConvergenceTol($(tol))
    val coefficients = optimizer.optimize(data, initialCoefficients)

    if (handlePersistence) instances.unpersist()

    val model = copyValues(new FMClassifierModel(uid, coefficients.asML, numFeatures, numClasses))
    model
  }

  @Since("3.0.0")
  override def copy(extra: ParamMap): FMClassifier = defaultCopy(extra)
}

@Since("3.0.0")
object FMClassifier extends DefaultParamsReadable[FMClassifier] {

  @Since("3.0.0")
  override def load(path: String): FMClassifier = super.load(path)
}

/**
 * Model produced by [[FMClassifier]]
 */
@Since("3.0.0")
class FMClassifierModel (
  @Since("3.0.0") override val uid: String,
  @Since("3.0.0") val coefficients: Vector,
  @Since("3.0.0") override val numFeatures: Int,
  @Since("3.0.0") override val numClasses: Int)
  extends ProbabilisticClassificationModel[Vector, FMClassifierModel]
    with FMClassifierParams with MLWritable {

  import org.apache.spark.ml.regression.BaseFactorizationMachinesGradient.LogisticLoss

  /**
   * Returns Factorization Machines coefficients
   * coefficients concat from 2-way coefficients, 1-way coefficients, global bias
   * index 0 ~ numFeatures*numFactors is 2-way coefficients,
   *   [i * numFactors + f] denotes i-th feature and f-th factor
   * Following indices are 1-way coefficients and global bias.
   */
  private val oldCoefficients: OldVector = coefficients

  private lazy val gradient = BaseFactorizationMachinesGradient.parseLoss(
    LogisticLoss, $(numFactors), $(fitBias), $(fitLinear), numFeatures)

  override protected def predictRaw(features: Vector): Vector = {
    val rawPrediction: Double = gradient.getRawPrediction(features, oldCoefficients)
    Vectors.dense(Array(-rawPrediction, rawPrediction))
  }

  override protected def raw2probabilityInPlace(rawPrediction: Vector): Vector = {
    rawPrediction match {
      case dv: DenseVector =>
        dv.values(1) = gradient.getPrediction(dv.values(1))
        dv.values(0) = 1.0 - dv.values(1)
        dv
      case sv: SparseVector =>
        throw new RuntimeException("Unexpected error in FMClassifierModel:" +
          " raw2probabilityInPlace encountered SparseVector")
    }
  }

  @Since("3.0.0")
  override def copy(extra: ParamMap): FMClassifierModel = {
    copyValues(new FMClassifierModel(
      uid, coefficients, numFeatures, numClasses), extra)
  }

  @Since("3.0.0")
  override def write: MLWriter =
    new FMClassifierModel.FMClassifierModelWriter(this)

  override def toString: String = {
    s"FMClassifierModel: " +
      s"uid = ${super.toString}, numClasses = $numClasses, numFeatures = $numFeatures, " +
      s"numFactors = ${$(numFactors)}, fitLinear = ${$(fitLinear)}, fitBias = ${$(fitBias)}"
  }
}

@Since("3.0.0")
object FMClassifierModel extends MLReadable[FMClassifierModel] {

  @Since("3.0.0")
  override def read: MLReader[FMClassifierModel] = new FMClassifierModelReader

  @Since("3.0.0")
  override def load(path: String): FMClassifierModel = super.load(path)

  /** [[MLWriter]] instance for [[FMClassifierModel]] */
  private[FMClassifierModel] class FMClassifierModelWriter(
    instance: FMClassifierModel) extends MLWriter with Logging {

    private case class Data(
      numFeatures: Int,
      numClasses: Int,
      coefficients: OldVector)

    override protected def saveImpl(path: String): Unit = {
      DefaultParamsWriter.saveMetadata(instance, path, sc)
      val data = Data(instance.numFeatures, instance.numClasses, instance.coefficients)
      val dataPath = new Path(path, "data").toString
      sparkSession.createDataFrame(Seq(data)).repartition(1).write.parquet(dataPath)
    }
  }

  private class FMClassifierModelReader extends MLReader[FMClassifierModel] {

    private val className = classOf[FMClassifierModel].getName

    override def load(path: String): FMClassifierModel = {
      val metadata = DefaultParamsReader.loadMetadata(path, sc, className)
      val dataPath = new Path(path, "data").toString
      val data = sparkSession.read.format("parquet").load(dataPath)

      val Row(numFeatures: Int, numClasses: Int, coefficients: OldVector) = data
        .select("numFeatures", "numClasses", "coefficients").head()
      val model = new FMClassifierModel(
        metadata.uid, coefficients, numFeatures, numClasses)
      metadata.getAndSetParams(model)
      model
    }
  }
}
