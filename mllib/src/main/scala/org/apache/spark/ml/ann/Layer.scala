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

package org.apache.spark.ml.ann

import breeze.generic.{MappingUFunc, UFunc}
import breeze.linalg.{DenseMatrix => BDM, DenseVector => BDV, Vector => BV, axpy => Baxpy, sum => Bsum, max => Bmax, *}
import breeze.numerics.{log => Blog, sigmoid => Bsigmoid}

import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.mllib.optimization._
import org.apache.spark.rdd.RDD
import org.apache.spark.util.random.XORShiftRandom

/**
 * Trait holding Layer properties required for instantiating a [[LayerModel]].
 *
 */
private[ann] trait Layer extends Serializable {
  /**
   * Returns the instance of the layer based on weights provided.
   * @param weights vector with layer weights
   * @param position position of weights in the vector
   * @return the layer model
   */
  def getInstance(weights: Vector, position: Int): LayerModel

  /**
   * Returns the instance of the layer with random generated weights.
   * @param seed seed
   * @return the layer model
   */
  def getInstance(seed: Long): LayerModel
}

/**
 * Trait holding weights (or parameters) for a layer.
 * Implements functions needed for forward and back propagation.
 * Can return weights in [[Vector]] format.
 */
private[ann] trait LayerModel extends Serializable {
  /**
   * Number of weights.
   */
  val size: Int

  /**
   * Evaluates the data (process the data forwards through the layer).
   * @param data data
   * @return processed data
   */
  def eval(data: BDM[Double]): BDM[Double]

  /**
   * Computes the delta (gradient of the objective function with respect to weighted inputs) for
   * back propagation.
   * @param nextDelta delta for the next layer
   * @param input input data
   * @return delta for this layer
   */
  def prevDelta(nextDelta: BDM[Double], input: BDM[Double]): BDM[Double]

  /**
   * Computes the gradient of the objective function with respect to this layer's weights.
   * @param delta delta for this layer
   * @param input input data
   * @return gradient
   */
  def grad(delta: BDM[Double], input: BDM[Double]): Array[Double]

  /**
   * Returns weights for the layer in a single vector.
   * @return layer weights
   */
  def weights(): Vector
}

/**
 * Layer properties for affine transformations, that is y=A*x+b.
 * @param numIn number of inputs
 * @param numOut number of outputs
 */
private[ann] class AffineLayer(val numIn: Int, val numOut: Int) extends Layer {

  override def getInstance(weights: Vector, position: Int): LayerModel = {
    AffineLayerModel(this, weights, position)
  }

  override def getInstance(seed: Long = 11L): LayerModel = {
    AffineLayerModel(this, seed)
  }
}

/**
 * Model of Affine layer y=A*x+b.
 * @param w weights (matrix A)
 * @param b bias (vector b)
 */
private[ann] class AffineLayerModel private(w: BDM[Double], b: BDV[Double]) extends LayerModel {
  val size = w.size + b.length
  val gwb = new Array[Double](size) // gradient with respect to weights + biases
  private lazy val gw: BDM[Double] = new BDM[Double](w.rows, w.cols, gwb) // gradient wrt weights
  private lazy val gb: BDV[Double] = new BDV[Double](gwb, w.size) // gradient wrt biases
  private var z: BDM[Double] = null
  private var d: BDM[Double] = null
  private var ones: BDV[Double] = null

  override def eval(data: BDM[Double]): BDM[Double] = {
    if (z == null || z.cols != data.cols) {
      z = new BDM[Double](w.rows, data.cols)
    }
    z(::, *) := b
    BreezeUtil.dgemm(1.0, w, data, 1.0, z)
    z
  }

  override def prevDelta(nextDelta: BDM[Double], input: BDM[Double]): BDM[Double] = {
    if (d == null || d.cols != nextDelta.cols) {
      d = new BDM[Double](w.cols, nextDelta.cols)
    }
    BreezeUtil.dgemm(1.0, w.t, nextDelta, 0.0, d)
    d
  }

  override def grad(delta: BDM[Double], input: BDM[Double]): Array[Double] = {
    BreezeUtil.dgemm(1.0 / input.cols, delta, input.t, 0.0, gw)
    if (ones == null || ones.length != delta.cols) {
      ones = BDV.ones[Double](delta.cols)
    }
    BreezeUtil.dgemv(1.0 / input.cols, delta, ones, 0.0, gb)
    gwb
  }

  override def weights(): Vector = AffineLayerModel.roll(w, b)
}

/**
 * Fabric for Affine layer models.
 */
private[ann] object AffineLayerModel {

  /**
   * Creates a model of Affine layer.
   * @param layer layer properties
   * @param weights vector with weights
   * @param position position of weights in the vector
   * @return model of Affine layer
   */
  def apply(layer: AffineLayer, weights: Vector, position: Int): AffineLayerModel = {
    val (w, b) = unroll(weights, position, layer.numIn, layer.numOut)
    new AffineLayerModel(w, b)
  }

  /**
   * Creates a model of Affine layer.
   * @param layer layer properties
   * @param seed seed
   * @return model of Affine layer
   */
  def apply(layer: AffineLayer, seed: Long): AffineLayerModel = {
    val (w, b) = randomWeights(layer.numIn, layer.numOut, seed)
    new AffineLayerModel(w, b)
  }

  /**
   * Unrolls the weights from the vector.
   * @param weights vector with weights
   * @param position position of weights for this layer
   * @param numIn number of layer inputs
   * @param numOut number of layer outputs
   * @return matrix A and vector b, both views of the data in `weights`
   */
  def unroll(
      weights: Vector,
      position: Int,
      numIn: Int,
      numOut: Int): (BDM[Double], BDV[Double]) = {
    val weightsArray = weights.toArray
    // TODO: the array is not copied to BDMs, make sure this is OK!
    val a = new BDM[Double](numOut, numIn, weightsArray, position)
    val b = new BDV[Double](weightsArray, position + (numOut * numIn), 1, numOut)
    (a, b)
  }

  /**
   * Roll the layer weights into a vector.
   * @param a matrix A
   * @param b vector b
   * @return vector of weights, a copy of the data in `a` and `b`
   */
  def roll(a: BDM[Double], b: BDV[Double]): Vector = {
    // TODO: the array is copied to Vector, make sure this is necessary!
    Vectors.dense(a.toArray ++ b.toArray)
  }

  /**
   * Generate random weights for the layer.
   * @param numIn number of inputs
   * @param numOut number of outputs
   * @param seed seed
   * @return (matrix A, vector b)
   */
  def randomWeights(numIn: Int, numOut: Int, seed: Long = 11L): (BDM[Double], BDV[Double]) = {
    val rand: XORShiftRandom = new XORShiftRandom(seed)
    val weights = BDM.fill[Double](numOut, numIn){ (rand.nextDouble * 4.8 - 2.4) / numIn }
    val bias = BDV.fill[Double](numOut){ (rand.nextDouble * 4.8 - 2.4) / numIn }
    (weights, bias)
  }
}

/**
 * Trait for functions and their derivatives for functional layers.
 */
private[ann] trait ActivationFunction extends Serializable {

  /**
   * Implements in-place application of a function.
   * @param x input data
   * @param y output data, mutated to hold the result of applying the function to the given input
   */
  def eval(x: BDM[Double], y: BDM[Double]): Unit

  /**
   * Implements in-place application of the derivative of a function (needed for back propagation).
   * @param x input data
   * @param y output data, mutated to hold the function's derivative value for given input
   */
  def derivative(x: BDM[Double], y: BDM[Double]): Unit

  /**
   * Implements the cross entropy error of a function.
   * Needed if the functional layer that contains this function is the output layer
   * of the network.
   * @param target target output
   * @param output computed output
   * @param result intermediate result
   * @return cross-entropy
   */
  def crossEntropy(target: BDM[Double], output: BDM[Double], result: BDM[Double]): Double

  /**
   * Implements the mean squared error of a function.
   * @param target target output
   * @param output computed output
   * @param result intermediate result
   * @return mean squared error
   */
  def squared(target: BDM[Double], output: BDM[Double], result: BDM[Double]): Double
}

/**
 * Implements in-place application of functions.
 */
private[ann] object ActivationFunction {

  def apply(x: BDM[Double], y: BDM[Double], func: UFunc with MappingUFunc)(
      implicit impl: func.Impl[BDM[Double], BDM[Double]]): Unit = {
    y := func(x)
  }

  def apply(
      x1: BDM[Double],
      x2: BDM[Double],
      y: BDM[Double],
      func: UFunc with MappingUFunc)(
      implicit impl: func.Impl2[BDM[Double], BDM[Double], BDM[Double]]): Unit = {
    y := func(x1, x2)
  }
}

/**
 * Implements Softmax activation function.
 */
private[ann] class SoftmaxFunction extends ActivationFunction {
  override def eval(x: BDM[Double], y: BDM[Double]): Unit = {
    (0 until x.cols).foreach { j =>
      // find max value to scale and prevent overflow during exp
      val maxVal = Bmax(x(::, j))
      y(::, j) := x(::, j).map { xVal => Math.exp(xVal) - maxVal }
      y(::, j) :/= Bsum(y(::, j))
    }
  }

  override def crossEntropy(
      output: BDM[Double],
      target: BDM[Double],
      result: BDM[Double]): Double = {
    ActivationFunction(output, target, result, OutputMinusTarget)
    -Bsum( target :* Blog(output)) / output.cols
  }

  private object OutputMinusTarget extends UFunc with MappingUFunc {
    implicit val implDoubleDouble: Impl2[Double, Double, Double] =
      new Impl2[Double, Double, Double] {
        def apply(o: Double, t: Double): Double = o - t
      }
  }

  override def derivative(x: BDM[Double], y: BDM[Double]): Unit = {
    ActivationFunction(x, y, SoftmaxDerivative)
  }

  private object SoftmaxDerivative extends UFunc with MappingUFunc {
    implicit val implDouble: Impl[Double, Double] = new Impl[Double, Double] {
      def apply(z: Double) = (1 - z) * z
    }
  }

  override def squared(output: BDM[Double], target: BDM[Double], result: BDM[Double]): Double = {
    throw new UnsupportedOperationException("Sorry, squared error is not defined for Softmax.")
  }
}

/**
 * Implements Sigmoid activation function.
 */
private[ann] class SigmoidFunction extends ActivationFunction {
  override def eval(x: BDM[Double], y: BDM[Double]): Unit = {
    ActivationFunction(x, y, Sigmoid)
  }

  private object Sigmoid extends UFunc with MappingUFunc {
    implicit val implDouble: Impl[Double, Double] = new Impl[Double, Double] {
      def apply(z: Double): Double = Bsigmoid(z)
    }
  }

  override def crossEntropy(
      output: BDM[Double],
      target: BDM[Double],
      result: BDM[Double]): Double = {
    ActivationFunction(output, target, result, OutputMinusTarget)
    -Bsum(target :* Blog(output)) / output.cols
  }

  private object OutputMinusTarget extends UFunc with MappingUFunc {
    implicit val implDoubleDouble: Impl2[Double, Double, Double] =
      new Impl2[Double, Double, Double] {
        def apply(o: Double, t: Double): Double = o - t
      }
  }

  override def derivative(x: BDM[Double], y: BDM[Double]): Unit = {
    ActivationFunction(x, y, SigmoidDerivative)
  }

  private object SigmoidDerivative extends UFunc with MappingUFunc {
    implicit val implDouble: Impl[Double, Double] =
      new Impl[Double, Double] {
        def apply(z: Double) = (1 - z) * z
      }
  }

  override def squared(output: BDM[Double], target: BDM[Double], result: BDM[Double]): Double = {
    ActivationFunction(output, target, result, OutputMinusTarget)
    val e = (Bsum(result :* result) / 2) / output.cols
    ActivationFunction(result, output, result, ResultTimesOutputMinusOutputSquared)
    e
  }

  private object ResultTimesOutputMinusOutputSquared extends UFunc with MappingUFunc {
    implicit val implDoubleDouble: Impl2[Double, Double, Double] =
      new Impl2[Double, Double, Double] {
        def apply(x: Double, o: Double): Double = x * (o - o * o)
      }
  }
}

/**
 * Functional layer properties, y = f(x).
 * @param activationFunction activation function
 */
private[ann] class FunctionalLayer (val activationFunction: ActivationFunction) extends Layer {
  override def getInstance(weights: Vector, position: Int): LayerModel = getInstance(0L)

  override def getInstance(seed: Long): LayerModel =
    FunctionalLayerModel(this)
}

/**
 * Functional layer model. Holds no weights.
 * @param activationFunction activation function
 */
private[ann] class FunctionalLayerModel private (val activationFunction: ActivationFunction)
  extends LayerModel {
  val size = 0
  // matrices for in-place computations
  // outputs
  private var f: BDM[Double] = null
  // delta
  private var d: BDM[Double] = null
  // matrix for error computation
  private var e: BDM[Double] = null
  // delta gradient
  private lazy val dg = new Array[Double](0)

  override def eval(data: BDM[Double]): BDM[Double] = {
    if (f == null || f.cols != data.cols) {
      f = new BDM[Double](data.rows, data.cols)
    }
    activationFunction.eval(data, f)
    f
  }

  override def prevDelta(nextDelta: BDM[Double], input: BDM[Double]): BDM[Double] = {
    if (d == null || d.cols != nextDelta.cols) {
      d = new BDM[Double](nextDelta.rows, nextDelta.cols)
    }
    activationFunction.derivative(input, d)
    d :*= nextDelta
    d
  }

  override def grad(delta: BDM[Double], input: BDM[Double]): Array[Double] = dg

  override def weights(): Vector = Vectors.dense(new Array[Double](0))

  def crossEntropy(output: BDM[Double], target: BDM[Double]): (BDM[Double], Double) = {
    if (e == null || e.cols != output.cols) {
      e = new BDM[Double](output.rows, output.cols)
    }
    val error = activationFunction.crossEntropy(output, target, e)
    (e, error)
  }

  def squared(output: BDM[Double], target: BDM[Double]): (BDM[Double], Double) = {
    if (e == null || e.cols != output.cols) {
      e = new BDM[Double](output.rows, output.cols)
    }
    val error = activationFunction.squared(output, target, e)
    (e, error)
  }

  def error(output: BDM[Double], target: BDM[Double]): (BDM[Double], Double) = {
    // TODO: allow user to pick error
    activationFunction match {
      case sigmoid: SigmoidFunction => squared(output, target)
      case softmax: SoftmaxFunction => crossEntropy(output, target)
    }
  }
}

/**
 * Fabric of functional layer models.
 */
private[ann] object FunctionalLayerModel {
  def apply(layer: FunctionalLayer): FunctionalLayerModel =
    new FunctionalLayerModel(layer.activationFunction)
}

/**
 * Trait for the artificial neural network (ANN) topology properties.
 */
private[ann] trait Topology extends Serializable {
  def getInstance(weights: Vector): TopologyModel
  def getInstance(seed: Long): TopologyModel
}

/**
 * Trait for ANN topology model.
 */
private[ann] trait TopologyModel extends Serializable {
  /**
   * Forward propagation.
   * @param data input data
   * @return array of outputs for each of the layers
   */
  def forward(data: BDM[Double]): Array[BDM[Double]]

  /**
   * Prediction of the model.
   * @param data input data
   * @return prediction
   */
  def predict(data: Vector): Vector

  /**
   * Computes gradient for the network.
   * @param data input data
   * @param target target output
   * @param cumGradient cumulative gradient
   * @param blockSize block size
   * @return error
   */
  def computeGradient(data: BDM[Double], target: BDM[Double], cumGradient: Vector,
      blockSize: Int): Double

  /**
   * Returns the weights of the ANN.
   * @return weights
   */
  def weights(): Vector
}

/**
 * Feed forward ANN.
 * @param layers the layers of the neural network
 */
private[ann] class FeedForwardTopology private(val layers: Array[Layer]) extends Topology {
  override def getInstance(weights: Vector): TopologyModel = FeedForwardModel(this, weights)

  override def getInstance(seed: Long): TopologyModel = FeedForwardModel(this, seed)
}

/**
 * Factory for some of the frequently-used topologies.
 */
private[ml] object FeedForwardTopology {
  /**
   * Creates a feed forward topology from the array of layers.
   * @param layers array of layers
   * @return feed forward topology
   */
  def apply(layers: Array[Layer]): FeedForwardTopology = {
    new FeedForwardTopology(layers)
  }

  /**
   * Creates a multi-layer perceptron.
   * @param layerSizes sizes of layers including input and output size
   * @param softmax wether to use Softmax or Sigmoid function for an output layer (default: Softmax)
   * @return multilayer perceptron topology
   */
  def multiLayerPerceptron(layerSizes: Array[Int], softmax: Boolean = true): FeedForwardTopology = {
    val layers = new Array[Layer]((layerSizes.length - 1) * 2)
    for (i <- 0 until layerSizes.length - 1) {
      layers(i * 2) = new AffineLayer(layerSizes(i), layerSizes(i + 1))
      layers(i * 2 + 1) =
        if (softmax && i == layerSizes.length - 2) {
          new FunctionalLayer(new SoftmaxFunction())
        } else {
          new FunctionalLayer(new SigmoidFunction())
        }
    }
    FeedForwardTopology(layers)
  }
}

/**
 * Model of Feed Forward Neural Network.
 * Implements forward, gradient computation and can return weights in vector format.
 * @param layerModels models of layers
 * @param topology topology of the network
 */
private[ml] class FeedForwardModel private(
    val layerModels: Array[LayerModel],
    val topology: FeedForwardTopology) extends TopologyModel {
  override def forward(data: BDM[Double]): Array[BDM[Double]] = {
    val outputs = new Array[BDM[Double]](layerModels.length)
    outputs(0) = layerModels(0).eval(data)
    for (i <- 1 until layerModels.length) {
      outputs(i) = layerModels(i).eval(outputs(i-1))
    }
    outputs
  }

  override def computeGradient(
      data: BDM[Double],
      target: BDM[Double],
      cumGradient: Vector,
      realBatchSize: Int): Double = {
    val outputs = forward(data)
    val deltas = new Array[BDM[Double]](layerModels.length)
    val L = layerModels.length - 1
    val (newE, newError) = layerModels.last match {
      case flm: FunctionalLayerModel => flm.error(outputs.last, target)
      case _ =>
        throw new UnsupportedOperationException("Non-functional layer not supported at the top")
    }
    // backward pass (back-propagate deltas given errors)
    deltas(L) = new BDM[Double](0, 0)
    deltas(L - 1) = newE
    for (i <- (L - 2) to (0, -1)) {
      deltas(i) = layerModels(i + 1).prevDelta(deltas(i + 1), outputs(i + 1))
    }
    // forward pass (forward-propagate gradients given inputs)
    val grads = layerModels.zipWithIndex.map { case (layer, i) =>
      val input = if (i == 0) data else outputs(i - 1)
      layer.grad(deltas(i), input)
    }
    // update cumulative gradients
    val cumGradientArray = cumGradient.toArray
    grads.flatten.zipWithIndex.foreach { case (newGrad, i) =>
      cumGradientArray(i) += newGrad
    }
    newError
  }

  override def weights(): Vector = {
    Vectors.dense(layerModels.flatMap(_.weights().toArray))
  }

  override def predict(data: Vector): Vector = {
    val size = data.size
    val result = forward(new BDM[Double](size, 1, data.toArray))
    Vectors.dense(result.last.toArray)
  }
}

/**
 * Fabric for feed forward ANN models.
 */
private[ann] object FeedForwardModel {

  /**
   * Creates a model from a topology and weights.
   * @param topology topology
   * @param weights weights
   * @return model
   */
  def apply(topology: FeedForwardTopology, weights: Vector): FeedForwardModel = {
    val layers = topology.layers
    val layerModels = new Array[LayerModel](layers.length)
    var offset = 0
    for (i <- 0 until layers.length) {
      layerModels(i) = layers(i).getInstance(weights, offset)
      offset += layerModels(i).size
    }
    new FeedForwardModel(layerModels, topology)
  }

  /**
   * Creates a model given a topology and seed.
   * @param topology topology
   * @param seed seed for generating the weights
   * @return model
   */
  def apply(topology: FeedForwardTopology, seed: Long = 11L): FeedForwardModel = {
    val layers = topology.layers
    val layerModels = new Array[LayerModel](layers.length)
    var offset = 0
    for(i <- 0 until layers.length){
      layerModels(i) = layers(i).getInstance(seed)
      offset += layerModels(i).size
    }
    new FeedForwardModel(layerModels, topology)
  }
}

/**
 * Neural network gradient. Does nothing but call [[LayerModel.grad()]].
 * @param topology topology
 * @param dataStacker data stacker
 */
private[ann] class ANNGradient(topology: Topology, dataStacker: DataStacker) extends Gradient {

  override def compute(data: Vector, label: Double, weights: Vector): (Vector, Double) = {
    val gradient = Vectors.zeros(weights.size)
    val loss = compute(data, label, weights, gradient)
    (gradient, loss)
  }

  override def compute(
      data: Vector,
      label: Double,
      weights: Vector,
      cumGradient: Vector): Double = {
    val (input, target, realBatchSize) = dataStacker.unstack(data)
    val model = topology.getInstance(weights)
    model.computeGradient(input, target, cumGradient, realBatchSize)
  }
}

/**
 * Stacks pairs of training samples (input, output) in one vector allowing them to pass
 * through Optimizer/Gradient interfaces. If stackSize is more than one, makes blocks
 * or matrices of inputs and outputs and then stack them in one vector.
 * This can be used for further batch computations after unstacking.
 * @param stackSize stack size
 * @param inputSize size of the input vectors
 * @param outputSize size of the output vectors
 */
private[ann] class DataStacker(stackSize: Int, inputSize: Int, outputSize: Int)
  extends Serializable {

  /**
   * Stacks the data.
   * @param data RDD of vector pairs
   * @return RDD of double (always zero) and vector that contains the stacked vectors
   */
  def stack(data: RDD[(Vector, Vector)]): RDD[(Double, Vector)] = {
    val stackedData = if (stackSize == 1) {
      data.map { v =>
        (0.0,
          Vectors.fromBreeze(BDV.vertcat(
            v._1.toBreeze.toDenseVector,
            v._2.toBreeze.toDenseVector)))
      }
    } else {
      data.mapPartitions { it =>
        it.grouped(stackSize).map { seq =>
          val size = seq.size
          val bigVector = new Array[Double](inputSize * size + outputSize * size)
          var i = 0
          seq.foreach { case (in, out) =>
            System.arraycopy(in.toArray, 0, bigVector, i * inputSize, inputSize)
            System.arraycopy(out.toArray, 0, bigVector,
              inputSize * size + i * outputSize, outputSize)
            i += 1
          }
          (0.0, Vectors.dense(bigVector))
        }
      }
    }
    stackedData
  }

  /**
   * Unstack the stacked vectors into matrices for batch operations.
   * @param data stacked vector
   * @return pair of matrices holding input and output data and the real stack size
   */
  def unstack(data: Vector): (BDM[Double], BDM[Double], Int) = {
    val arrData = data.toArray
    val realStackSize = arrData.length / (inputSize + outputSize)
    val input = new BDM(inputSize, realStackSize, arrData)
    val target = new BDM(outputSize, realStackSize, arrData, inputSize * realStackSize)
    (input, target, realStackSize)
  }
}

/**
 * Simple updater.
 */
private[ann] class ANNUpdater extends Updater {

  override def compute(
      weightsOld: Vector,
      gradient: Vector,
      stepSize: Double,
      iter: Int,
      regParam: Double): (Vector, Double) = {
    val thisIterStepSize = stepSize
    val brzWeights: BV[Double] = weightsOld.toBreeze.toDenseVector
    Baxpy(-thisIterStepSize, gradient.toBreeze, brzWeights)
    (Vectors.fromBreeze(brzWeights), 0)
  }
}

/**
 * MLlib-style trainer class that trains a network given the data and topology.
 * @param topology topology of ANN
 * @param inputSize input size
 * @param outputSize output size
 */
private[ml] class FeedForwardTrainer(
    topology: Topology,
    val inputSize: Int,
    val outputSize: Int) extends Serializable {

  // TODO: what if we need to pass random seed?
  private var _weights = topology.getInstance(11L).weights()
  private var _stackSize = 128
  private var dataStacker = new DataStacker(_stackSize, inputSize, outputSize)
  private var _gradient: Gradient = new ANNGradient(topology, dataStacker)
  private var _updater: Updater = new ANNUpdater()
  private var optimizer: Optimizer = LBFGSOptimizer.setConvergenceTol(1e-4).setNumIterations(100)

  /**
   * Returns weights.
   * @return weights
   */
  def getWeights: Vector = _weights

  /**
   * Sets weights.
   * @param value weights
   * @return trainer
   */
  def setWeights(value: Vector): FeedForwardTrainer = {
    _weights = value
    this
  }

  /**
   * Sets the stack size.
   * @param value stack size
   * @return trainer
   */
  def setStackSize(value: Int): FeedForwardTrainer = {
    _stackSize = value
    dataStacker = new DataStacker(value, inputSize, outputSize)
    this
  }

  /**
   * Sets the SGD optimizer.
   * @return SGD optimizer
   */
  def SGDOptimizer: GradientDescent = {
    val sgd = new GradientDescent(_gradient, _updater)
    optimizer = sgd
    sgd
  }

  /**
   * Sets the L-BFGS optimizer.
   * @return L-BFGS optimizer
   */
  def LBFGSOptimizer: LBFGS = {
    val lbfgs = new LBFGS(_gradient, _updater)
    optimizer = lbfgs
    lbfgs
  }

  /**
   * Sets the updater.
   * @param updater updater
   * @return trainer
   */
  def setUpdater(updater: Updater): FeedForwardTrainer = {
    _updater = updater
    optimizer match {
      case lbfgs: LBFGS => lbfgs.setUpdater(updater)
      case sgd: GradientDescent => sgd.setUpdater(updater)
      case other => throw new UnsupportedOperationException(
        s"Only LBFGS and GradientDescent are supported but got ${other.getClass}.")
    }
    this
  }

  /**
   * Sets the gradient.
   * @param gradient gradient
   * @return trainer
   */
  def setGradient(gradient: Gradient): FeedForwardTrainer = {
    _gradient = gradient
    optimizer match {
      case lbfgs: LBFGS => lbfgs.setGradient(gradient)
      case sgd: GradientDescent => sgd.setGradient(gradient)
      case other => throw new UnsupportedOperationException(
        s"Only LBFGS and GradientDescent are supported but got ${other.getClass}.")
    }
    this
  }

  /**
   * Trains the ANN.
   * @param data RDD of input and output vector pairs
   * @return model
   */
  def train(data: RDD[(Vector, Vector)]): TopologyModel = {
    val newWeights = optimizer.optimize(dataStacker.stack(data), getWeights)
    topology.getInstance(newWeights)
  }

}
