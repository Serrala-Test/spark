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

import org.apache.spark.SparkContext
import org.apache.spark.mllib.linalg.Vector
import org.apache.spark.mllib.optimization._
import org.apache.spark.mllib.util.MLUtils
import org.apache.spark.rdd.RDD

/**
 * Regression model trained using Lasso.
 *
 * @param weights Weights computed for every feature.
 * @param intercept Intercept computed for this model.
 */
class LassoModel(
    override val weights: Vector,
    override val intercept: Double)
  extends GeneralizedLinearModel(weights, intercept)
  with RegressionModel with Serializable {

  override protected def predictPoint(
      dataMatrix: Vector,
      weightMatrix: Vector,
      intercept: Double): Double = {
    weightMatrix.toBreeze.dot(dataMatrix.toBreeze) + intercept
  }
}

/**
 * Train a regression model with L1-regularization using Stochastic Gradient Descent.
 * This solves the l1-regularized least squares regression formulation
 *          f(weights) = 1/n ||A weights-y||^2  + regParam ||weights||_1
 * Here the data matrix has n rows, and the input RDD holds the set of rows of A, each with
 * its corresponding right hand side label y.
 * See also the documentation for the precise formulation.
 */
class LassoWithSGD private (
    var stepSize: Double,
    var numIterations: Int,
    var regParam: Double,
    var miniBatchFraction: Double)
  extends GeneralizedLinearAlgorithm[LassoModel] with Serializable {

  val gradient = new LeastSquaresGradient()
  val updater = new L1Updater()
  @transient val optimizer = new GradientDescent(gradient, updater).setStepSize(stepSize)
    .setNumIterations(numIterations)
    .setRegParam(regParam)
    .setMiniBatchFraction(miniBatchFraction)

  // We don't want to penalize the intercept, so set this to false.
  super.setIntercept(false)

  /**
   * Construct a Lasso object with default parameters
   */
  def this() = this(1.0, 100, 1.0, 1.0)

  override def setIntercept(addIntercept: Boolean): this.type = {
    // TODO: Support adding intercept.
    if (addIntercept) throw new UnsupportedOperationException("Adding intercept is not supported.")
    this
  }

  override protected def createModel(weights: Vector, intercept: Double) = {
    new LassoModel(weights, intercept)
  }
}

/**
 * Train a regression model with L1-regularization and L2-regularization using
 * Alternating Direction Method of Multiplier (ADMM).
 * This solves the l1-regularized least squares regression formulation
 *    f(weights) = 1/2 ||A weights-y||^2  + l1RegParam ||weights||_1 + l2RegParam/2 ||weights||_2^2
 * Here the data matrix has n rows, and the input RDD holds the set of rows of A, each with
 * its corresponding right hand side label y.
 * See also the documentation for the precise formulation.
 */
class LassoWithADMM private (
    var numPartitions: Int,
    var numIterations: Int,
    var l1RegParam: Double,
    var l2RegParam: Double,
    var penalty: Double)
  extends GeneralizedLinearAlgorithm[LassoModel] with Serializable {

  @transient val optimizer = new ADMMLasso().setNumPartitions(numPartitions)
    .setNumIterations(numIterations)
    .setL1RegParam(l1RegParam)
    .setL2RegParam(l2RegParam)
    .setPenalty(penalty)

  // We don't want to penalize the intercept, so set this to false.
  super.setIntercept(false)

  /**
   * Construct a LassoWithADMM object with default parameters
   */
  def this() = this(5, 50, 1.0, .0, 10.0)

  override def setIntercept(addIntercept: Boolean): this.type = {
    // TODO: Support adding intercept.
    if (addIntercept) throw new UnsupportedOperationException("Adding intercept is not supported.")
    this
  }

  override protected def createModel(weights: Vector, intercept: Double) = {
    new LassoModel(weights, intercept)
  }
}

/**
 * Top-level methods for calling Lasso.
 */
object LassoWithSGD {

  /**
   * Train a Lasso model given an RDD of (label, features) pairs. We run a fixed number
   * of iterations of gradient descent using the specified step size. Each iteration uses
   * `miniBatchFraction` fraction of the data to calculate a stochastic gradient. The weights used
   * in gradient descent are initialized using the initial weights provided.
   *
   * @param input RDD of (label, array of features) pairs. Each pair describes a row of the data
   *              matrix A as well as the corresponding right hand side label y
   * @param numIterations Number of iterations of gradient descent to run.
   * @param stepSize Step size scaling to be used for the iterations of gradient descent.
   * @param regParam Regularization parameter.
   * @param miniBatchFraction Fraction of data to be used per iteration.
   * @param initialWeights Initial set of weights to be used. Array should be equal in size to
   *        the number of features in the data.
   */
  def train(
      input: RDD[LabeledPoint],
      numIterations: Int,
      stepSize: Double,
      regParam: Double,
      miniBatchFraction: Double,
      initialWeights: Vector): LassoModel = {
    new LassoWithSGD(stepSize, numIterations, regParam, miniBatchFraction)
      .run(input, initialWeights)
  }

  /**
   * Train a Lasso model given an RDD of (label, features) pairs. We run a fixed number
   * of iterations of gradient descent using the specified step size. Each iteration uses
   * `miniBatchFraction` fraction of the data to calculate a stochastic gradient.
   *
   * @param input RDD of (label, array of features) pairs. Each pair describes a row of the data
   *              matrix A as well as the corresponding right hand side label y
   * @param numIterations Number of iterations of gradient descent to run.
   * @param stepSize Step size to be used for each iteration of gradient descent.
   * @param regParam Regularization parameter.
   * @param miniBatchFraction Fraction of data to be used per iteration.
   */
  def train(
      input: RDD[LabeledPoint],
      numIterations: Int,
      stepSize: Double,
      regParam: Double,
      miniBatchFraction: Double): LassoModel = {
    new LassoWithSGD(stepSize, numIterations, regParam, miniBatchFraction).run(input)
  }

  /**
   * Train a Lasso model given an RDD of (label, features) pairs. We run a fixed number
   * of iterations of gradient descent using the specified step size. We use the entire data set to
   * update the true gradient in each iteration.
   *
   * @param input RDD of (label, array of features) pairs. Each pair describes a row of the data
   *              matrix A as well as the corresponding right hand side label y
   * @param stepSize Step size to be used for each iteration of Gradient Descent.
   * @param regParam Regularization parameter.
   * @param numIterations Number of iterations of gradient descent to run.
   * @return a LassoModel which has the weights and offset from training.
   */
  def train(
      input: RDD[LabeledPoint],
      numIterations: Int,
      stepSize: Double,
      regParam: Double): LassoModel = {
    train(input, numIterations, stepSize, regParam, 1.0)
  }

  /**
   * Train a Lasso model given an RDD of (label, features) pairs. We run a fixed number
   * of iterations of gradient descent using a step size of 1.0. We use the entire data set to
   * compute the true gradient in each iteration.
   *
   * @param input RDD of (label, array of features) pairs. Each pair describes a row of the data
   *              matrix A as well as the corresponding right hand side label y
   * @param numIterations Number of iterations of gradient descent to run.
   * @return a LassoModel which has the weights and offset from training.
   */
  def train(
      input: RDD[LabeledPoint],
      numIterations: Int): LassoModel = {
    train(input, numIterations, 1.0, 1.0, 1.0)
  }

  def main(args: Array[String]) {
    if (args.length != 5) {
      println("Usage: Lasso <master> <input_dir> <step_size> <regularization_parameter> <niters>")
      System.exit(1)
    }
    val sc = new SparkContext(args(0), "Lasso")
    val data = MLUtils.loadLabeledData(sc, args(1))
    val model = LassoWithSGD.train(data, args(4).toInt, args(2).toDouble, args(3).toDouble)

    println("Weights: " + model.weights)
    println("Intercept: " + model.intercept)

    sc.stop()
  }
}

object LassoWithADMM {
  /**
   * Train a Lasso model given an RDD of (label, features) pairs using ADMM. We run a fixed number
   * of outer ADMM iterations. The weights are initialized using the initial weights provided.
   *
   * @param input RDD of (label, array of features) pairs. Each pair describes a row of the data
   *              matrix A as well as the corresponding right hand side label y
   * @param numPartitions Number of data blocks to partition the data into
   * @param numIterations Number of iterations of gradient descent to run.
   * @param l1RegParam l1-regularization parameter
   * @param l2RegParam l2-regularization parameter
   * @param penalty ADMM penalty of the constraint
   * @param initialWeights set of weights to be used. Array should be equal in size to
   *        the number of features in the data.
   */
  def train(
      input: RDD[LabeledPoint],
      numPartitions: Int,
      numIterations: Int,
      l1RegParam: Double,
      l2RegParam: Double,
      penalty: Double,
      initialWeights: Vector): LassoModel = {
    new LassoWithADMM(numPartitions, numIterations, l1RegParam, l2RegParam, penalty)
      .run(input, initialWeights)
  }

  /**
   * Train a Lasso model given an RDD of (label, features) pairs using ADMM. We run a fixed number
   * of outer ADMM iterations. The weights are initialized using default value.
   *
   * @param input RDD of (label, array of features) pairs. Each pair describes a row of the data
   *              matrix A as well as the corresponding right hand side label y
   * @param numPartitions Number of data blocks to partition the data into
   * @param numIterations Number of iterations of gradient descent to run.
   * @param l1RegParam l1-regularization parameter
   * @param l2RegParam l2-regularization parameter
   * @param penalty ADMM penalty of the constraint
   */
  def train(
      input: RDD[LabeledPoint],
      numPartitions: Int,
      numIterations: Int,
      l1RegParam: Double,
      l2RegParam: Double,
      penalty: Double): LassoModel = {
    new LassoWithADMM(numPartitions, numIterations, l1RegParam, l2RegParam, penalty).run(input)
  }

  def main(args: Array[String]) {
    if (args.length != 7) {
      println("Usage: Lasso <master> <input_dir> <numPartitions> <niters> <l1-regularizaztion> " +
        "<l2-regularizaztion> <penalty>")
      System.exit(1)
    }
    val sc = new SparkContext(args(0), "Lasso")
    val data = MLUtils.loadLabeledData(sc, args(1))
    val model = LassoWithADMM.train(data, args(2).toInt, args(3).toInt, args(4).toDouble,
      args(5).toDouble, args(6).toDouble)

    println("Weights: " + model.weights)
    println("Intercept: " + model.intercept)

    sc.stop()
  }
}
