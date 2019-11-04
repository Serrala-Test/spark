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

import scala.util.Random

import breeze.linalg.{axpy => brzAxpy, norm => brzNorm, Vector => BV}
import breeze.numerics.{sqrt => brzSqrt}
import org.apache.hadoop.fs.Path

import org.apache.spark.annotation.Since
import org.apache.spark.internal.Logging
import org.apache.spark.ml.{PredictionModel, Predictor, PredictorParams}
import org.apache.spark.ml.linalg._
import org.apache.spark.ml.linalg.BLAS._
import org.apache.spark.ml.param._
import org.apache.spark.ml.param.shared._
import org.apache.spark.ml.util._
import org.apache.spark.ml.util.Instrumentation.instrumented
import org.apache.spark.mllib.{linalg => OldLinalg}
import org.apache.spark.mllib.linalg.{Vector => OldVector, Vectors => OldVectors}
import org.apache.spark.mllib.linalg.VectorImplicits._
import org.apache.spark.mllib.optimization.{Gradient, GradientDescent, SquaredL2Updater, Updater}
import org.apache.spark.mllib.util.MLUtils
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Dataset, Row}
import org.apache.spark.sql.functions.col
import org.apache.spark.storage.StorageLevel

/**
 * Params for Factorization Machines
 */
private[ml] trait FactorizationMachinesParams
  extends PredictorParams
  with HasMaxIter with HasStepSize with HasTol with HasSolver {

  /**
   * Param for dimensionality of the factors (&gt;= 0)
   * @group param
   */
  @Since("3.0.0")
  final val numFactors: IntParam = new IntParam(this, "numFactors",
    "Dimensionality of the factor vectors, " +
      "which are used to get pairwise interactions between variables",
    ParamValidators.gt(0))

  /** @group getParam */
  @Since("3.0.0")
  final def getNumFactors: Int = $(numFactors)

  /**
   * Param for whether to fit global bias term
   * @group param
   */
  @Since("3.0.0")
  final val fitBias: BooleanParam = new BooleanParam(this, "fitBias",
    "whether to fit global bias term")

  /** @group getParam */
  @Since("3.0.0")
  final def getFitBias: Boolean = $(fitBias)

  /**
   * Param for whether to fit linear term (aka 1-way term)
   * @group param
   */
  @Since("3.0.0")
  final val fitLinear: BooleanParam = new BooleanParam(this, "fitLinear",
    "whether to fit linear term (aka 1-way term)")

  /** @group getParam */
  @Since("3.0.0")
  final def getFitLinear: Boolean = $(fitLinear)

  /**
   * Param for L2 regularization parameter (&gt;= 0)
   * @group param
   */
  @Since("3.0.0")
  final val regParam: DoubleParam = new DoubleParam(this, "regParam",
    "the magnitude of L2-regularization", ParamValidators.gtEq(0))

  /** @group getParam */
  @Since("3.0.0")
  final def getRegParam: Double = $(regParam)

  /**
   * Param for mini-batch fraction, must be in range (0, 1]
   * @group param
   */
  @Since("3.0.0")
  final val miniBatchFraction: DoubleParam = new DoubleParam(this, "miniBatchFraction",
    "fraction of the input data set that should be used for one iteration of gradient descent",
    ParamValidators.inRange(0, 1, false, true))

  /** @group getParam */
  @Since("3.0.0")
  final def getMiniBatchFraction: Double = $(miniBatchFraction)

  /**
   * Param for standard deviation of initial coefficients
   * @group param
   */
  @Since("3.0.0")
  final val initStd: DoubleParam = new DoubleParam(this, "initStd",
    "standard deviation of initial coefficients", ParamValidators.gt(0))

  /** @group getParam */
  @Since("3.0.0")
  final def getInitStd: Double = $(initStd)

  /**
   * The solver algorithm for optimization.
   * Supported options: "gd", "adamW".
   * Default: "adamW"
   *
   * @group param
   */
  @Since("3.0.0")
  final override val solver: Param[String] = new Param[String](this, "solver",
    "The solver algorithm for optimization. Supported options: " +
      s"${FactorizationMachines.supportedSolvers.mkString(", ")}. (Default adamW)",
    ParamValidators.inArray[String](FactorizationMachines.supportedSolvers))
}

private[ml] trait FactorizationMachines extends FactorizationMachinesParams {

  private[ml] def initCoefficients(numFeatures: Int): OldVector = {
    val initialCoefficients =
      OldVectors.dense(
        Array.fill($(numFactors) * numFeatures)(Random.nextGaussian() * $(initStd)) ++
        (if ($(fitLinear)) new Array[Double](numFeatures) else Array.emptyDoubleArray) ++
        (if ($(fitBias)) new Array[Double](1) else Array.emptyDoubleArray))
    initialCoefficients
  }

  private[ml] def _train(
      data: RDD[(Double, OldVector)],
      numFeatures: Int,
      loss: String
    ): Vector = {

    // initialize coefficients
    val initialCoefficients = initCoefficients(numFeatures)
    val coefficientsSize = initialCoefficients.size

    // optimize coefficients with gradient descent
    val gradient = FactorizationMachines.parseLoss(
      loss, $(numFactors), $(fitBias), $(fitLinear), numFeatures)

    val updater = FactorizationMachines.parseSolver($(solver), coefficientsSize)

    val optimizer = new GradientDescent(gradient, updater)
      .setStepSize($(stepSize))
      .setNumIterations($(maxIter))
      .setRegParam($(regParam))
      .setMiniBatchFraction($(miniBatchFraction))
      .setConvergenceTol($(tol))
    val coefficients = optimizer.optimize(data, initialCoefficients)
    coefficients.asML
  }
}

private[ml] object FactorizationMachines {

  /** String name for "gd". */
  val GD = "gd"

  /** String name for "adamW". */
  val AdamW = "adamW"

  /** Set of solvers that FactorizationMachines supports. */
  val supportedSolvers = Array(GD, AdamW)

  /** String name for "logisticLoss". */
  val LogisticLoss = "logisticLoss"

  /** String name for "squaredError". */
  val SquaredError = "squaredError"

  /** Set of loss function names that FactorizationMachines supports. */
  val supportedRegressorLosses = Array(SquaredError)
  val supportedClassifierLosses = Array(LogisticLoss)
  val supportedLosses = supportedRegressorLosses ++ supportedClassifierLosses

  def parseSolver(solver: String, coefficientsSize: Int): Updater = {
    solver match {
      case GD => new SquaredL2Updater()
      case AdamW => new AdamWUpdater(coefficientsSize)
    }
  }

  def parseLoss(
      lossFunc: String,
      numFactors: Int,
      fitBias: Boolean,
      fitLinear: Boolean,
      numFeatures: Int): BaseFactorizationMachinesGradient = {
    lossFunc match {
      case LogisticLoss =>
        new LogisticFactorizationMachinesGradient(numFactors, fitBias, fitLinear, numFeatures)
      case SquaredError =>
        new MSEFactorizationMachinesGradient(numFactors, fitBias, fitLinear, numFeatures)
      case _ => throw new IllegalArgumentException(s"loss function type $lossFunc is invalidation")
    }
  }
}

/**
 * Params for FMRegressor
 */
private[regression] trait FMRegressorParams extends FactorizationMachinesParams {
}

/**
 * Factorization Machines learning algorithm for regression.
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
 * FM regression model uses MSE loss which can be solved by gradient descent method, and
 * regularization terms like L2 are usually added to the loss function to prevent overfitting.
 */
@Since("3.0.0")
class FMRegressor @Since("3.0.0") (
    @Since("3.0.0") override val uid: String)
  extends Predictor[Vector, FMRegressor, FMRegressorModel]
  with FactorizationMachines with FMRegressorParams with DefaultParamsWritable with Logging {

  @Since("3.0.0")
  def this() = this(Identifiable.randomUID("fmr"))

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
  def setMiniBatchFraction(value: Double): this.type = set(miniBatchFraction, value)
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
  setDefault(solver -> FactorizationMachines.AdamW)

  override protected def train(dataset: Dataset[_]): FMRegressorModel = instrumented { instr =>
    val data: RDD[(Double, OldVector)] =
      dataset.select(col($(labelCol)), col($(featuresCol))).rdd.map {
        case Row(label: Double, features: Vector) =>
          (label, features)
      }
    data.persist(StorageLevel.MEMORY_AND_DISK)

    instr.logPipelineStage(this)
    instr.logDataset(dataset)
    instr.logParams(this, numFactors, fitBias, fitLinear, regParam,
      miniBatchFraction, initStd, maxIter, stepSize, tol, solver)

    val numFeatures = data.first()._2.size
    instr.logNumFeatures(numFeatures)

    val coefficients = _train(data, numFeatures, FactorizationMachines.SquaredError)

    val model = copyValues(new FMRegressorModel(uid, coefficients, numFeatures))
    model
  }

  @Since("3.0.0")
  override def copy(extra: ParamMap): FMRegressor = defaultCopy(extra)
}

@Since("3.0.0")
object FMRegressor extends DefaultParamsReadable[FMRegressor] {

  @Since("3.0.0")
  override def load(path: String): FMRegressor = super.load(path)
}

/**
 * Model produced by [[FMRegressor]].
 */
@Since("3.0.0")
class FMRegressorModel (
    @Since("3.0.0") override val uid: String,
    @Since("3.0.0") val coefficients: Vector,
    @Since("3.0.0") override val numFeatures: Int)
  extends PredictionModel[Vector, FMRegressorModel]
  with FMRegressorParams with MLWritable {

  /**
   * Returns Factorization Machines coefficients
   * coefficients concat from 2-way coefficients, 1-way coefficients, global bias
   * index 0 ~ numFeatures*numFactors is 2-way coefficients,
   *   [i * numFactors + f] denotes i-th feature and f-th factor
   * Following indices are 1-way coefficients and global bias.
   */
  @transient private lazy val oldCoefficients: OldVector = coefficients

  @transient private lazy val gradient = FactorizationMachines.parseLoss(
    FactorizationMachines.SquaredError, $(numFactors), $(fitBias), $(fitLinear), numFeatures)

  override def predict(features: Vector): Double = {
    val rawPrediction = gradient.getRawPrediction(features, oldCoefficients)
    gradient.getPrediction(rawPrediction)
  }

  @Since("3.0.0")
  override def copy(extra: ParamMap): FMRegressorModel = {
    copyValues(new FMRegressorModel(
      uid, coefficients, numFeatures), extra)
  }

  @Since("3.0.0")
  override def write: MLWriter =
    new FMRegressorModel.FMRegressorModelWriter(this)

  override def toString: String = {
    s"FMRegressorModel: " +
      s"uid = ${super.toString}, numFeatures = $numFeatures, " +
      s"numFactors = ${$(numFactors)}, fitLinear = ${$(fitLinear)}, fitBias = ${$(fitBias)}"
  }
}

@Since("3.0.0")
object FMRegressorModel extends MLReadable[FMRegressorModel] {

  @Since("3.0.0")
  override def read: MLReader[FMRegressorModel] = new FMRegressorModelReader

  @Since("3.0.0")
  override def load(path: String): FMRegressorModel = super.load(path)

  /** [[MLWriter]] instance for [[FMRegressorModel]] */
  private[FMRegressorModel] class FMRegressorModelWriter(
      instance: FMRegressorModel) extends MLWriter with Logging {

    private case class Data(
        numFeatures: Int,
        coefficients: Vector)

    override protected def saveImpl(path: String): Unit = {
      DefaultParamsWriter.saveMetadata(instance, path, sc)
      val data = Data(instance.numFeatures, instance.coefficients)
      val dataPath = new Path(path, "data").toString
      sparkSession.createDataFrame(Seq(data)).repartition(1).write.parquet(dataPath)
    }
  }

  private class FMRegressorModelReader extends MLReader[FMRegressorModel] {

    private val className = classOf[FMRegressorModel].getName

    override def load(path: String): FMRegressorModel = {
      val metadata = DefaultParamsReader.loadMetadata(path, sc, className)
      val dataPath = new Path(path, "data").toString
      val data = sparkSession.read.format("parquet").load(dataPath)

      val Row(numFeatures: Int, coefficients: Vector) = data
        .select("numFeatures", "coefficients").head()
      val model = new FMRegressorModel(
        metadata.uid, coefficients, numFeatures)
      metadata.getAndSetParams(model)
      model
    }
  }
}

/**
 * Factorization Machines base gradient class
 * Implementing the raw FM formula, include raw prediction and raw gradient,
 * then inherit the base class to implement special gradient class(like logloss, mse).
 *
 * Factorization Machines raw formula:
 * {{{
 *   y_{fm} = w_0 + \sum\limits^n_{i-1} w_i x_i +
 *     \sum\limits^n_{i=1} \sum\limits^n_{j=i+1} \langle v_i, v_j \rangle x_i x_j
 * }}}
 * the pairwise interactions (2-way term) can be reformulated:
 * {{{
 *   \sum\limits^n_{i=1} \sum\limits^n_{j=i+1} \langle v_i, v_j \rangle x_i x_j
 *   = \frac{1}{2}\sum\limits^k_{f=1}
 *   \left(\left( \sum\limits^n_{i=1}v_{i,f}x_i \right)^2 -
 *     \sum\limits^n_{i=1}v_{i,f}^2x_i^2 \right)
 * }}}
 * and the gradients are:
 * {{{
 *   \frac{\partial}{\partial\theta}y_{fm} = \left\{
 *   \begin{align}
 *   &1, & if\ \theta\ is\ w_0 \\
 *   &x_i, & if\ \theta\ is\ w_i \\
 *   &x_i{\sum}^n_{j=1}v_{j,f}x_j - v_{i,f}x_i^2, & if\ \theta\ is\ v_{i,j} \\
 *   \end{align}
 *   \right.
 * }}}
 *
 * Factorization Machines formula with prediction task:
 * {{{
 *   \hat{y} = p\left( y_{fm} \right)
 * }}}
 * p is the prediction function, for binary classification task is sigmoid.
 * The loss funcation gradient formula:
 * {{{
 *   \frac{\partial}{\partial\theta} l\left( \hat{y},y \right) =
 *   \frac{\partial}{\partial\theta} l\left( p\left( y_{fm} \right),y \right) =
 *   \frac{\partial l}{\partial \hat{y}} \cdot
 *   \frac{\partial \hat{y}}{\partial y_{fm}} \cdot
 *   \frac{\partial y_{fm}}{\partial\theta}
 * }}}
 * Last term is same for all task, so be implemented in base gradient class.
 * last term named rawGradient in following code, and first two term named multiplier.
 */
private[ml] abstract class BaseFactorizationMachinesGradient(
    numFactors: Int,
    fitBias: Boolean,
    fitLinear: Boolean,
    numFeatures: Int) extends Gradient {

  override def compute(
      data: OldVector,
      label: Double,
      weights: OldVector,
      cumGradient: OldVector): Double = {
    val rawPrediction = getRawPrediction(data, weights)
    val rawGradient = getRawGradient(data, weights)
    val multiplier = getMultiplier(rawPrediction, label)
    axpy(multiplier, rawGradient, cumGradient)
    val loss = getLoss(rawPrediction, label)
    loss
  }

  def getPrediction(rawPrediction: Double): Double

  protected def getMultiplier(rawPrediction: Double, label: Double): Double

  protected def getLoss(rawPrediction: Double, label: Double): Double

  private val sumVX = Array.fill(numFactors)(0.0)

  def getRawPrediction(data: OldVector, weights: OldVector): Double = {
    var rawPrediction = 0.0
    val vWeightsSize = numFeatures * numFactors

    if (fitBias) rawPrediction += weights(weights.size - 1)
    if (fitLinear) {
      data.foreachActive { case (index, value) =>
        rawPrediction += weights(vWeightsSize + index) * value
      }
    }
    (0 until numFactors).foreach { f =>
      var sumSquare = 0.0
      var sum = 0.0
      data.foreachActive { case (index, value) =>
        val vx = weights(index * numFactors + f) * value
        sumSquare += vx * vx
        sum += vx
      }
      sumVX(f) = sum
      rawPrediction += 0.5 * (sum * sum - sumSquare)
    }

    rawPrediction
  }

  private def getRawGradient(data: OldVector, weights: OldVector): OldVector = {
    data match {
      // Usually Factorization Machines is used, there will be a lot of sparse features.
      // So need to optimize the gradient descent of sparse vector.
      case data: OldLinalg.SparseVector =>
        val gardSize = data.indices.length * numFactors +
          (if (fitLinear) data.indices.length else 0) +
          (if (fitBias) 1 else 0)
        val gradIndex = Array.fill(gardSize)(0)
        val gradValue = Array.fill(gardSize)(0.0)
        var gradI = 0
        val vWeightsSize = numFeatures * numFactors

        data.foreachActive { case (index, value) =>
          (0 until numFactors).foreach { f =>
            gradIndex(gradI) = index * numFactors + f
            gradValue(gradI) = value * sumVX(f) - weights(index * numFactors + f) * value * value
            gradI += 1
          }
        }
        if (fitLinear) {
          data.foreachActive { case (index, value) =>
            gradIndex(gradI) = vWeightsSize + index
            gradValue(gradI) = value
            gradI += 1
          }
        }
        if (fitBias) {
          gradIndex(gradI) = weights.size - 1
          gradValue(gradI) = 1.0
        }

        OldVectors.sparse(weights.size, gradIndex, gradValue)
      case data: OldLinalg.DenseVector =>
        val gradient = Array.fill(weights.size)(0.0)
        val vWeightsSize = numFeatures * numFactors

        if (fitBias) gradient(weights.size - 1) += 1.0
        if (fitLinear) {
          data.foreachActive { case (index, value) =>
            gradient(vWeightsSize + index) += value
          }
        }
        (0 until numFactors).foreach { f =>
          data.foreachActive { case (index, value) =>
            gradient(index * numFactors + f) +=
              value * sumVX(f) - weights(index * numFactors + f) * value * value
          }
        }

        OldVectors.dense(gradient)
    }
  }
}

/**
 * FM with logistic loss
 * prediction formula:
 * {{{
 *   \hat{y} = \sigmoid(y_{fm})
 * }}}
 * loss formula:
 * {{{
 *   - y * log(\hat{y}) - (1 - y) * log(1 - \hat{y})
 * }}}
 * multiplier formula:
 * {{{
 *   \frac{\partial l}{\partial \hat{y}} \cdot
 *   \frac{\partial \hat{y}}{\partial y_{fm}} =
 *   \hat{y} - y
 * }}}
 */
private[ml] class LogisticFactorizationMachinesGradient(
    numFactors: Int,
    fitBias: Boolean,
    fitLinear: Boolean,
    numFeatures: Int)
  extends BaseFactorizationMachinesGradient(
    numFactors: Int,
    fitBias: Boolean,
    fitLinear: Boolean,
    numFeatures: Int) with Logging {

  override def getPrediction(rawPrediction: Double): Double = {
    1.0 / (1.0 + math.exp(-rawPrediction))
  }

  override protected def getMultiplier(rawPrediction: Double, label: Double): Double = {
    getPrediction(rawPrediction) - label
  }

  override protected def getLoss(rawPrediction: Double, label: Double): Double = {
    if (label > 0) MLUtils.log1pExp(-rawPrediction)
    else MLUtils.log1pExp(rawPrediction)
  }
}

/**
 * FM with mse
 * prediction formula:
 * {{{
 *   \hat{y} = y_{fm}
 * }}}
 * loss formula:
 * {{{
 *   (\hat{y} - y) ^ 2
 * }}}
 * multiplier formula:
 * {{{
 *   \frac{\partial l}{\partial \hat{y}} \cdot
 *   \frac{\partial \hat{y}}{\partial y_{fm}} =
 *   2 * (\hat{y} - y)
 * }}}
 */
private[ml] class MSEFactorizationMachinesGradient(
    numFactors: Int,
    fitBias: Boolean,
    fitLinear: Boolean,
    numFeatures: Int)
  extends BaseFactorizationMachinesGradient(
    numFactors: Int,
    fitBias: Boolean,
    fitLinear: Boolean,
    numFeatures: Int) with Logging {

  override def getPrediction(rawPrediction: Double): Double = {
    rawPrediction
  }

  override protected def getMultiplier(rawPrediction: Double, label: Double): Double = {
    2 * (rawPrediction - label)
  }

  override protected def getLoss(rawPrediction: Double, label: Double): Double = {
    (rawPrediction - label) * (rawPrediction - label)
  }
}

private[ml] class AdamWUpdater(weightSize: Int) extends Updater with Logging {
  val beta1: Double = 0.9
  val beta2: Double = 0.999
  val epsilon: Double = 1e-8

  val m: BV[Double] = BV.zeros[Double](weightSize).toDenseVector
  val v: BV[Double] = BV.zeros[Double](weightSize).toDenseVector
  var beta1T: Double = 1.0
  var beta2T: Double = 1.0

  override def compute(
    weightsOld: OldVector,
    gradient: OldVector,
    stepSize: Double,
    iter: Int,
    regParam: Double
  ): (OldVector, Double) = {
    val w: BV[Double] = weightsOld.asBreeze.toDenseVector
    val lr = stepSize // learning rate
    if (stepSize > 0) {
      val g: BV[Double] = gradient.asBreeze.toDenseVector
      m *= beta1
      brzAxpy(1 - beta1, g, m)
      v *= beta2
      brzAxpy(1 - beta2, g * g, v)
      beta1T *= beta1
      beta2T *= beta2
      val mHat = m / (1 - beta1T)
      val vHat = v / (1 - beta2T)
      w -= lr * mHat / (brzSqrt(vHat) + epsilon) + regParam * w
    }
    val norm = brzNorm(w, 2.0)

    (Vectors.fromBreeze(w), 0.5 * regParam * norm * norm)
  }
}
