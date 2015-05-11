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

package org.apache.spark.mllib.feature

import org.apache.spark.annotation.Experimental
import org.apache.spark.mllib.linalg._

/**
 * :: Experimental ::
 * Outputs the Hadamard product (i.e., the element-wise product) of each input vector with a
 * provided "weight" vector. In other words, it scales each column of the dataset by a scalar
 * multiplier.
 * @param scalingVector The values used to scale the reference vector's individual components.
 */
@Experimental
class ElementwiseProduct(val scalingVector: Vector) extends VectorTransformer {

  /**
   * Does the hadamard product transformation.
   *
   * @param vector vector to be transformed.
   * @return transformed vector.
   */
  override def transform(vector: Vector): Vector = {
    require(vector.size == scalingVector.size,
      s"vector sizes do not match: Expected ${scalingVector.size} but found ${vector.size}")
    vector match {
      case dv: DenseVector =>
        val values: Array[Double] = dv.values.clone()
        val dim = scalingVector.size
        var i = 0
        while (i < dim) {
          values(i) *= scalingVector(i)
          i += 1
        }
        Vectors.dense(values)
      case SparseVector(size, indices, vs) =>
        val values = vs.clone()
        val dim = values.length
        var i = 0
        while (i < dim) {
          values(i) *= scalingVector(indices(i))
          i += 1
        }
        Vectors.sparse(size, indices, values)
      case v => throw new IllegalArgumentException("Does not support vector type " + v.getClass)
    }
  }
}
