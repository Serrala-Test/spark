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

import breeze.linalg.{norm => brzNorm, DenseVector => BDV}
import breeze.optimize.{LBFGS => BreezeLBFGS, OWLQN => BreezeOWLQN}
import breeze.optimize.{CachedDiffFunction, DiffFunction}

import org.apache.spark.annotation.AlphaComponent
import org.apache.spark.ml.param._
import org.apache.spark.ml.param.shared._
import org.apache.spark.mllib.linalg._
import org.apache.spark.mllib.linalg.BLAS._
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.stat.MultivariateOnlineSummarizer
import org.apache.spark.mllib.util.MLUtils
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions._
import org.apache.spark.storage.StorageLevel
import org.apache.spark.{SparkException, Logging}

/**
 * Params for logistic regression.
 */
private[classification] trait LogisticRegressionParams extends ProbabilisticClassifierParams
  with HasRegParam with HasElasticNetParam with HasMaxIter with HasFitIntercept with HasTol
  with HasThreshold

/**
 * :: AlphaComponent ::
 *
 * Logistic regression.
 * Currently, this class only supports binary classification.
 */
@AlphaComponent
class LogisticRegression
  extends ProbabilisticClassifier[Vector, LogisticRegression, LogisticRegressionModel]
  with LogisticRegressionParams with Logging {

  /**
   * Set the regularization parameter.
   * Default is 0.0.
   * @group setParam
   */
  def setRegParam(value: Double): this.type = set(regParam, value)
  setDefault(regParam -> 0.0)

  /**
   * Set the ElasticNet mixing parameter.
   * For alpha = 0, the penalty is an L2 penalty. For alpha = 1, it is an L1 penalty.
   * For 0 < alpha < 1, the penalty is a combination of L1 and L2.
   * Default is 0.0 which is an L2 penalty.
   * @group setParam
   */
  def setElasticNetParam(value: Double): this.type = set(elasticNetParam, value)
  setDefault(elasticNetParam -> 0.0)

  /**
   * Set the maximal number of iterations.
   * Default is 100.
   * @group setParam
   */
  def setMaxIter(value: Int): this.type = set(maxIter, value)
  setDefault(maxIter -> 100)

  /**
   * Set the convergence tolerance of iterations.
   * Smaller value will lead to higher accuracy with the cost of more iterations.
   * Default is 1E-6.
   * @group setParam
   */
  def setTol(value: Double): this.type = set(tol, value)
  setDefault(tol -> 1E-6)

  /** @group setParam */
  def setFitIntercept(value: Boolean): this.type = set(fitIntercept, value)
  setDefault(fitIntercept -> true)

  /** @group setParam */
  def setThreshold(value: Double): this.type = set(threshold, value)
  setDefault(threshold -> 0.5)

  override protected def train(dataset: DataFrame): LogisticRegressionModel = {
    // Extract columns from data.  If dataset is persisted, do not persist oldDataset.
    val instances = extractLabeledPoints(dataset).map {
      case LabeledPoint(label: Double, features: Vector) => (label, features)
    }
    val handlePersistence = dataset.rdd.getStorageLevel == StorageLevel.NONE
    if (handlePersistence) instances.persist(StorageLevel.MEMORY_AND_DISK)

    val (summarizer, labelSummarizer) = instances.treeAggregate(
      (new MultivariateOnlineSummarizer, new MultiClassSummarizer))( {
        case ((summarizer: MultivariateOnlineSummarizer, labelSummarizer: MultiClassSummarizer),
        (label: Double, features: Vector)) =>
          (summarizer.add(features), labelSummarizer.add(label))
      }, {
        case ((summarizer1: MultivariateOnlineSummarizer, labelSummarizer1: MultiClassSummarizer),
        (summarizer2: MultivariateOnlineSummarizer, labelSummarizer2: MultiClassSummarizer)) =>
          (summarizer1.merge(summarizer2), labelSummarizer1.merge(labelSummarizer2))
      })

    val histogram = labelSummarizer.histogram
    val numInvalid = labelSummarizer.countInvalid
    val numClasses = histogram.length
    val numFeatures = summarizer.mean.size

    if (numInvalid != 0) {
      logError("Classification labels should be in {0 to " + (numClasses - 1) + "}. " +
        "Found " + numInvalid + " invalid labels.")
      throw new SparkException("Input validation failed.")
    }

    if (numClasses > 2) {
      logError("Currently, LogisticRegression with ElasticNet in ML package only supports " +
        "binary classification. Found " + numClasses + " in the input dataset.")
      throw new SparkException("Input validation failed.")
    }

    val featuresMean = summarizer.mean.toArray
    val featuresStd = summarizer.variance.toArray.map(math.sqrt)

    val regParamL1 = $(elasticNetParam) * $(regParam)
    val regParamL2 = (1.0 - $(elasticNetParam)) * $(regParam)

    val costFun = new LogisticCostFun(instances, numClasses, $(fitIntercept),
      featuresStd, featuresMean, regParamL2)

    val optimizer = if ($(elasticNetParam) == 0.0 || $(regParam) == 0.0) {
      new BreezeLBFGS[BDV[Double]]($(maxIter), 10, $(tol))
    } else {
      // Remove the L1 penalization on the intercept
      def regParamL1Fun = (index: Int) => {
        if (index == numFeatures) 0.0 else regParamL1
      }
      new BreezeOWLQN[Int, BDV[Double]]($(maxIter), 10, regParamL1Fun, $(tol))
    }

    val initialWeightsWithIntercept =
      Vectors.zeros(if ($(fitIntercept)) numFeatures + 1 else numFeatures)

    // TODO: Compute the initial intercept based on the histogram.
    if ($(fitIntercept)) initialWeightsWithIntercept.toArray(numFeatures) = 1.0

    val states = optimizer.iterations(new CachedDiffFunction(costFun),
      initialWeightsWithIntercept.toBreeze.toDenseVector)

    var state = states.next()
    val lossHistory = mutable.ArrayBuilder.make[Double]

    while (states.hasNext) {
      lossHistory += state.value
      state = states.next()
    }
    lossHistory += state.value

    // The weights are trained in the scaled space; we're converting them back to
    // the original space.
    val weightsWithIntercept = {
      val rawWeights = state.x.toArray.clone()
      var i = 0
      // Note that the intercept in scaled space and original space is the same;
      // as a result, no scaling is needed.
      while (i < numFeatures) {
        rawWeights(i) *= { if (featuresStd(i) != 0.0) 1.0 / featuresStd(i) else 0.0 }
        i += 1
      }
      Vectors.dense(rawWeights)
    }

    if (handlePersistence) instances.unpersist()

    val (weights, intercept) = if ($(fitIntercept)) {
      (Vectors.dense(weightsWithIntercept.toArray.slice(0, weightsWithIntercept.size - 1)),
        weightsWithIntercept(weightsWithIntercept.size - 1))
    } else {
      (weightsWithIntercept, 0.0)
    }

    new LogisticRegressionModel(this, weights.compressed, intercept)
  }
}

/**
 * :: AlphaComponent ::
 *
 * Model produced by [[LogisticRegression]].
 */
@AlphaComponent
class LogisticRegressionModel private[ml] (
    override val parent: LogisticRegression,
    val weights: Vector,
    val intercept: Double)
  extends ProbabilisticClassificationModel[Vector, LogisticRegressionModel]
  with LogisticRegressionParams {

  /** @group setParam */
  def setThreshold(value: Double): this.type = set(threshold, value)

  private val margin: Vector => Double = (features) => {
    BLAS.dot(features, weights) + intercept
  }

  private val score: Vector => Double = (features) => {
    val m = margin(features)
    1.0 / (1.0 + math.exp(-m))
  }

  override def transform(dataset: DataFrame): DataFrame = {
    // This is overridden (a) to be more efficient (avoiding re-computing values when creating
    // multiple output columns) and (b) to handle threshold, which the abstractions do not use.
    // TODO: We should abstract away the steps defined by UDFs below so that the abstractions
    // can call whichever UDFs are needed to create the output columns.

    // Check schema
    transformSchema(dataset.schema, logging = true)

    // Output selected columns only.
    // This is a bit complicated since it tries to avoid repeated computation.
    //   rawPrediction (-margin, margin)
    //   probability (1.0-score, score)
    //   prediction (max margin)
    var tmpData = dataset
    var numColsOutput = 0
    if ($(rawPredictionCol) != "") {
      val features2raw: Vector => Vector = (features) => predictRaw(features)
      tmpData = tmpData.withColumn($(rawPredictionCol),
        callUDF(features2raw, new VectorUDT, col($(featuresCol))))
      numColsOutput += 1
    }
    if ($(probabilityCol) != "") {
      if ($(rawPredictionCol) != "") {
        val raw2prob = udf { (rawPreds: Vector) =>
          val prob1 = 1.0 / (1.0 + math.exp(-rawPreds(1)))
          Vectors.dense(1.0 - prob1, prob1): Vector
        }
        tmpData = tmpData.withColumn($(probabilityCol), raw2prob(col($(rawPredictionCol))))
      } else {
        val features2prob = udf { (features: Vector) => predictProbabilities(features) : Vector }
        tmpData = tmpData.withColumn($(probabilityCol), features2prob(col($(featuresCol))))
      }
      numColsOutput += 1
    }
    if ($(predictionCol) != "") {
      val t = $(threshold)
      if ($(probabilityCol) != "") {
        val predict = udf { probs: Vector =>
          if (probs(1) > t) 1.0 else 0.0
        }
        tmpData = tmpData.withColumn($(predictionCol), predict(col($(probabilityCol))))
      } else if ($(rawPredictionCol) != "") {
        val predict = udf { rawPreds: Vector =>
          val prob1 = 1.0 / (1.0 + math.exp(-rawPreds(1)))
          if (prob1 > t) 1.0 else 0.0
        }
        tmpData = tmpData.withColumn($(predictionCol), predict(col($(rawPredictionCol))))
      } else {
        val predict = udf { features: Vector => this.predict(features) }
        tmpData = tmpData.withColumn($(predictionCol), predict(col($(featuresCol))))
      }
      numColsOutput += 1
    }
    if (numColsOutput == 0) {
      this.logWarning(s"$uid: LogisticRegressionModel.transform() was called as NOOP" +
        " since no output columns were set.")
    }
    tmpData
  }

  override val numClasses: Int = 2

  /**
   * Predict label for the given feature vector.
   * The behavior of this can be adjusted using [[threshold]].
   */
  override protected def predict(features: Vector): Double = {
    if (score(features) > getThreshold) 1 else 0
  }

  override protected def predictProbabilities(features: Vector): Vector = {
    val s = score(features)
    Vectors.dense(1.0 - s, s)
  }

  override protected def predictRaw(features: Vector): Vector = {
    val m = margin(features)
    Vectors.dense(0.0, m)
  }

  override def copy(extra: ParamMap): LogisticRegressionModel = {
    copyValues(new LogisticRegressionModel(parent, weights, intercept), extra)
  }
}

/**
 * MultiClassSummarizer computes the number of distinct labels and corresponding counts,
 * and validates the data to see if the labels used for k class multi-label classification
 * are in the range of {0, 1, ..., k - 1} in a online fashion.
 *
 * Two MultilabelSummarizer can be merged together to have a statistical summary of the
 * corresponding joint dataset.
 */
class MultiClassSummarizer private[ml] extends Serializable {
  private val distinctMap = new mutable.HashMap[Int, Long]
  private var totalInvalidCnt: Long = 0L

  /**
   * Add a new label into this MultilabelSummarizer, and update the distinct map.
   * @param label The label for this data point.
   * @return This MultilabelSummarizer
   */
  def add(label: Double): this.type = {
    if (label - label.toInt != 0.0 || label < 0) {
      totalInvalidCnt += 1
      this
    }
    else {
      val counts: Long = distinctMap.getOrElse(label.toInt, 0L)
      distinctMap.put(label.toInt, counts + 1)
      this
    }
  }

  /**
   * Merge another MultilabelSummarizer, and update the distinct map.
   * (Note that it will merge the smaller distinct map into the larger one using in-place
   * merging, so either `this` or `other` object will be modified and returned.)
   *
   * @param other The other MultilabelSummarizer to be merged.
   * @return Merged MultilabelSummarizer object.
   */
  def merge(other: MultiClassSummarizer): MultiClassSummarizer = {
    val (largeMap, smallMap) = if (this.distinctMap.size > other.distinctMap.size) {
      (this, other)
    } else {
      (other, this)
    }
    smallMap.distinctMap.foreach {
      case (key, value) =>
        val counts = largeMap.distinctMap.getOrElse(key, 0L)
        largeMap.distinctMap.put(key, counts + value)
    }
    largeMap.totalInvalidCnt += smallMap.totalInvalidCnt
    largeMap
  }

  def countInvalid: Long = totalInvalidCnt

  def numClasses: Int = distinctMap.keySet.max + 1

  def histogram: Array[Long] = {
    val result = Array.ofDim[Long](numClasses)
    var i = 0
    while (i < result.length) {
      result(i) = distinctMap.getOrElse(i, 0L)
      i += 1
    }
    result
  }
}

/**
 * :: DeveloperApi ::
 *
 * @param numClasses the number of possible outcomes for k classes classification problem in
 *                   Multinomial Logistic Regression. By default, it is binary logistic regression
 *                   so numClasses will be set to 2.
 */
private class LogisticAggregator(
    weights: Vector,
    numClasses: Int,
    fitIntercept: Boolean,
    featuresStd: Array[Double],
    featuresMean: Array[Double]) extends Serializable {

  private var totalCnt: Long = 0L
  private var lossSum = 0.0

  private val weightsArray = weights match {
    case dv: DenseVector => dv.values
    case _ =>
      throw new IllegalArgumentException(
        s"weights only supports dense vector but got type ${weights.getClass}.")
  }

  private val dim = if (fitIntercept) weightsArray.length - 1 else weightsArray.length

  private val gradientSumArray = Array.ofDim[Double](weightsArray.length)

  /**
   * Add a new training data to this LogisticAggregator, and update the loss and gradient
   * of the objective function.
   *
   * @param label The label for this data point.
   * @param data The features for one data point in dense/sparse vector format to be added
   *             into this aggregator.
   * @return This LogisticAggregator object.
   */
  def add(label: Double, data: Vector): this.type = {
    require(dim == data.size, s"Dimensions mismatch when adding new sample." +
      s" Expecting $dim but got ${data.size}.")

    val dataSize = data.size

    val localWeightsArray = weightsArray
    val localGradientSumArray = gradientSumArray

    numClasses match {
      case 2 =>
        /**
         * For Binary Logistic Regression.
         */
        val margin = - {
          var sum = 0.0
          data.foreachActive { (index, value) =>
            if (featuresStd(index) != 0.0 && value != 0.0) {
              sum += localWeightsArray(index) * (value / featuresStd(index))
            }
          }
          sum + { if (fitIntercept) localWeightsArray(dim) else 0.0 }
        }

        val multiplier = (1.0 / (1.0 + math.exp(margin))) - label

        data.foreachActive { (index, value) =>
          if (featuresStd(index) != 0.0 && value != 0.0) {
            localGradientSumArray(index) += multiplier * (value / featuresStd(index))
          }
        }

        if (fitIntercept) {
          localGradientSumArray(dim) += multiplier
        }

        if (label > 0) {
          // The following is equivalent to log(1 + exp(margin)) but more numerically stable.
          lossSum += MLUtils.log1pExp(margin)
        } else {
          lossSum += MLUtils.log1pExp(margin) - margin
        }
      case _ =>
        new NotImplementedError("LogisticRegression with ElasticNet in ML package only supports " +
          "binary classification for now.")
    }
    totalCnt += 1
    this
  }

  /**
   * Merge another LogisticAggregator, and update the loss and gradient
   * of the objective function.
   * (Note that it's in place merging; as a result, `this` object will be modified.)
   *
   * @param other The other LogisticAggregator to be merged.
   * @return This LogisticAggregator object.
   */
  def merge(other: LogisticAggregator): this.type = {
    require(dim == other.dim, s"Dimensions mismatch when merging with another " +
      s"LeastSquaresAggregator. Expecting $dim but got ${other.dim}.")

    if (other.totalCnt != 0) {
      totalCnt += other.totalCnt
      lossSum += other.lossSum

      var i = 0
      val localThisGradientSumArray = this.gradientSumArray
      val localOtherGradientSumArray = other.gradientSumArray
      while (i < localThisGradientSumArray.length) {
        localThisGradientSumArray(i) += localOtherGradientSumArray(i)
        i += 1
      }
    }
    this
  }

  def count: Long = totalCnt

  def loss: Double = lossSum / totalCnt

  def gradient: Vector = {
    val result = Vectors.dense(gradientSumArray.clone())
    scal(1.0 / totalCnt, result)
    result
  }
}

/**
 * LogisticCostFun implements Breeze's DiffFunction[T] for a multinomial logistic loss function,
 * as used in multi-class classification (it is also used in binary logistic regression).
 * It returns the loss and gradient with L2 regularization at a particular point (weights).
 * It's used in Breeze's convex optimization routines.
 */
private class LogisticCostFun(
    data: RDD[(Double, Vector)],
    numClasses: Int,
    fitIntercept: Boolean,
    featuresStd: Array[Double],
    featuresMean: Array[Double],
    regParamL2: Double) extends DiffFunction[BDV[Double]] {

  override def calculate(weights: BDV[Double]): (Double, BDV[Double]) = {
    val w = Vectors.fromBreeze(weights)

    val logisticAggregator = data.treeAggregate(new LogisticAggregator(w, numClasses, fitIntercept,
      featuresStd, featuresMean))(
        seqOp = (c, v) => (c, v) match {
          case (aggregator, (label, features)) => aggregator.add(label, features)
        },
        combOp = (c1, c2) => (c1, c2) match {
          case (aggregator1, aggregator2) => aggregator1.merge(aggregator2)
        })

    // regVal is the sum of weight squares for L2 regularization
    val norm = if (fitIntercept) {
      brzNorm(Vectors.dense(weights.toArray.slice(0, weights.size -1)).toBreeze, 2.0)
    } else {
      brzNorm(weights, 2.0)
    }
    val regVal = 0.5 * regParamL2 * norm * norm

    val loss = logisticAggregator.loss + regVal
    val gradient = logisticAggregator.gradient

    if (fitIntercept) {
      axpy(regParamL2, w, gradient)
    } else {
      val wArray = w.toArray.clone()
      wArray(wArray.length - 1) = 0.0
      axpy(regParamL2, Vectors.dense(wArray), gradient)
    }

    (loss, gradient.toBreeze.asInstanceOf[BDV[Double]])
  }
}
