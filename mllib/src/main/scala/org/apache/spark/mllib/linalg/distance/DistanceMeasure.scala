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

package org.apache.spark.mllib.linalg.distance

import breeze.linalg.{Vector => BV}
import org.apache.spark.annotation.Experimental
import org.apache.spark.mllib.linalg.Vector

import scala.language.implicitConversions

/**
 * :: Experimental ::
 * This trait is used for objects which can determine a distance between two points
 *
 * Classes which inherits from this class are required to satisfy the follow condition:
 * 1. d(x, y) >= 0 (non-negative)
 * 2. d(x, y) = 0 if and only if x = y (identity of indiscernibles)
 * 3. d(x, y) = d(y, x) (symmetry)
 * However, classes which inherits aren't require to satisfy triangle inequality
 */
@Experimental
trait DistanceMeasure extends Function2[Vector, Vector, Double] with Serializable {

  /**
   * Calculates the distance metric between 2 points
   *
   * @param v1 a Vector defining a multidimensional point in some feature space
   * @param v2 a Vector defining a multidimensional point in some feature space
   * @return a scalar doubles of the distance
   */
  override def apply(v1: Vector, v2: Vector): Double = {
    validate(v1, v2)
    val breezeVector = mixVectors(v1, v2)
    vectorToDistance(dotProductWithWeight(breezeVector))
  }

  /**
   * Mix the target vectors
   *
   * @param v1 a Vector defining a multidimensional point in some feature space
   * @param v2 a Vector defining a multidimensional point in some feature space
   * @return Breeze Vector[Doube]
   */
  def mixVectors(v1: Vector, v2: Vector): BV[Double] = {
    throw new NotImplementedError("mixVectors is not implemented")
  }

  /**
   * Calculates dot product with the weighted vector
   *
   * @param breezeVector Breeze Vector[Double]
   * @return Breeze Vector[Double]
   */
  def dotProductWithWeight(breezeVector: BV[Double]): BV[Double] = breezeVector

  /**
   * Converts the mixed vector to distance
   *
   * @param breezeVector Breeze Vector[Double]
   * @return Double
   */
  def vectorToDistance(breezeVector: BV[Double]): Double = {
    throw new NotImplementedError("vectorToDistance is not implemented")
  }

  /**
   * Checks whether both of the length are same
   *
   * @param v1 a Vector defining a multidimensional point in some feature space
   * @param v2 a Vector defining a multidimensional point in some feature space
   * @throws IllegalArgumentException if the size of both vector is not same
   */
  def validate(v1: Vector, v2: Vector) {
    if(!v1.size.equals(v2.size)) {
      throw new IllegalArgumentException("The number of features must be same")
    }
  }
}


object DistanceMeasure {

  /**
   * Implicit method for DistanceMeasure
   *
   * @param f calculating distance function (Vector, Vector) => Double
   * @return DistanceMeasure
   */
  implicit def functionToDistanceMeasure(f: (Vector, Vector) => Double): DistanceMeasure = new
      DistanceMeasure {
    override def apply(v1: Vector, v2: Vector): Double = f(v1, v2)
  }
}

