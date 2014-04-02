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

package org.apache.spark.mllib.linalg

import breeze.linalg.{Matrix => BM, DenseMatrix => BDM}

/**
 * Trait for matrix.
 */
trait Matrix extends Serializable {

  /** Number of rows. */
  def m: Int

  /** Number of columns. */
  def n: Int

  /** Converts to a dense array in column major. */
  def toArray: Array[Double]

  /** Converts to a breeze matrix. */
  private[mllib] def toBreeze: BM[Double]
}

/**
 * Column majored dense matrix.
 *
 * @param m
 * @param n
 * @param values
 */
class DenseMatrix(val m: Int, val n: Int, val values: Array[Double]) extends Matrix {

  require(values.length == m * n)

  def toArray: Array[Double] = values

  private[mllib] def toBreeze: BM[Double] = new BDM[Double](m, n, values)
}

object Matrices {

  def dense(m: Int, n: Int, values: Array[Double]): Matrix = {
    new DenseMatrix(m, n, values)
  }

  private[mllib] def fromBreeze(breeze: BM[Double]): Matrix = {
    breeze match {
      case dm: BDM[Double] =>
        require(dm.majorStride == dm.rows)
        new DenseMatrix(dm.rows, dm.cols, dm.data)
      case _ =>
        throw new UnsupportedOperationException(
          s"Do not support conversion from type ${breeze.getClass.getName}.")
    }
  }
}
