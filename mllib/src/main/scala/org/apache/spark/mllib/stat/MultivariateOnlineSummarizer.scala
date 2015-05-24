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

package org.apache.spark.mllib.stat

import scala.collection.mutable

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.mllib.linalg.{Vectors, Vector}

/**
 * :: DeveloperApi ::
 * MultivariateOnlineSummarizer implements [[MultivariateStatisticalSummary]] to compute the mean,
 * variance, minimum, maximum, counts, and nonzero counts for samples in sparse or dense vector
 * format in a online fashion.
 *
 * Two MultivariateOnlineSummarizer can be merged together to have a statistical summary of
 * the corresponding joint dataset.
 *
 * A numerically stable algorithm is implemented to compute sample mean and variance:
 * Reference: [[http://en.wikipedia.org/wiki/Algorithms_for_calculating_variance variance-wiki]]
 * Zero elements (including explicit zero values) are skipped when calling add(),
 * to have time complexity O(nnz) instead of O(n) for each column.
 */
@DeveloperApi
class MultivariateOnlineSummarizer extends MultivariateStatisticalSummary with Serializable {

  private var n = 0
  private var currMean: Array[Double] = _
  private var currM2n: Array[Double] = _
  private var currM2: Array[Double] = _
  private var currL1: Array[Double] = _
  private var totalCnt: Long = 0
  private var nnz: Array[Double] = _
  private var currMax: Array[Double] = _
  private var currMin: Array[Double] = _

  private var configure: mutable.Map[String, Boolean] = mutable.Map("mean" -> false, "max" -> false,
    "min" -> false, "variance" -> false, "normL1" -> false, "normL2" -> false) 

  def withMean(flag: Boolean): this.type = {
    configure("mean") = flag
    this
  }

  def withMax(flag: Boolean): this.type = {
    configure("max") = flag
    this
  }

  def withMin(flag: Boolean): this.type = {
    configure("min") = flag
    this
  }

  def withVariance(flag: Boolean): this.type = {
    configure("variance") = flag
    this
  }

  def withNormL2(flag: Boolean): this.type = {
    configure("normL2") = flag
    this
  }

  def withNormL1(flag: Boolean): this.type = {
    configure("normL1") = flag
    this
  }

  /**
   * Add a new sample to this summarizer, and update the statistical summary.
   *
   * @param sample The sample in dense/sparse vector format to be added into this summarizer.
   * @return This MultivariateOnlineSummarizer object.
   */
  def add(sample: Vector): this.type = {
    if (n == 0) {
      require(sample.size > 0, s"Vector should have dimension larger than zero.")
      n = sample.size

      if (configure("mean") || configure("variance")) currMean = Array.ofDim[Double](n)
      if (configure("variance")) currM2n = Array.ofDim[Double](n)
      if (configure("normL2")) currM2 = Array.ofDim[Double](n)
      if (configure("normL1")) currL1 = Array.ofDim[Double](n)
      nnz = Array.ofDim[Double](n)
      if (configure("max")) currMax = Array.fill[Double](n)(Double.MinValue)
      if (configure("min")) currMin = Array.fill[Double](n)(Double.MaxValue)
    }

    require(n == sample.size, s"Dimensions mismatch when adding new sample." +
      s" Expecting $n but got ${sample.size}.")

    val localCurrMean= currMean
    val localCurrM2n = currM2n
    val localCurrM2 = currM2
    val localCurrL1 = currL1
    val localNnz = nnz
    val localCurrMax = currMax
    val localCurrMin = currMin
    sample.foreachActive { (index, value) =>
      if (value != 0.0) {
        if (configure("max") && localCurrMax(index) < value) {
          localCurrMax(index) = value
        }
        if (configure("min") && localCurrMin(index) > value) {
          localCurrMin(index) = value
        }
        if (configure("mean") || configure("variance")) {
          val prevMean = localCurrMean(index)
          val diff = value - prevMean
          localCurrMean(index) = prevMean + diff / (localNnz(index) + 1.0)
          if (configure("variance")) localCurrM2n(index) += (value - localCurrMean(index)) * diff
        }
        if (configure("normL2")) localCurrM2(index) += value * value
        if (configure("normL1")) localCurrL1(index) += math.abs(value)

        localNnz(index) += 1.0
      }
    }

    totalCnt += 1
    this
  }

  /**
   * Merge another MultivariateOnlineSummarizer, and update the statistical summary.
   * (Note that it's in place merging; as a result, `this` object will be modified.)
   *
   * @param other The other MultivariateOnlineSummarizer to be merged.
   * @return This MultivariateOnlineSummarizer object.
   */
  def merge(other: MultivariateOnlineSummarizer): this.type = {
    if (this.totalCnt != 0 && other.totalCnt != 0) {
      require(n == other.n, s"Dimensions mismatch when merging with another summarizer. " +
        s"Expecting $n but got ${other.n}.")
      require(this.configure == other.configure,
        s"Configures mismatch when merging with another summarizer. " +
          s"Expecting ${this.configure} but got ${other.configure}")
      totalCnt += other.totalCnt
      var i = 0
      while (i < n) {
        val thisNnz = nnz(i)
        val otherNnz = other.nnz(i)
        val totalNnz = thisNnz + otherNnz
        if (totalNnz != 0.0) {
          // merge mean together
          if (configure("mean") || configure("variance")) {
            val deltaMean = other.currMean(i) - currMean(i)
            currMean(i) += deltaMean * otherNnz / totalNnz
            // merge m2n together
            if (configure("variance")) {
              currM2n(i) += other.currM2n(i) + deltaMean * deltaMean * thisNnz * otherNnz / totalNnz
            }
          }
          // merge m2 together
          if (configure("normL2")) currM2(i) += other.currM2(i)
          // merge l1 together
          if (configure("normL1")) currL1(i) += other.currL1(i)
          // merge max and min
          if (configure("max")) currMax(i) = math.max(currMax(i), other.currMax(i))
          if (configure("min")) currMin(i) = math.min(currMin(i), other.currMin(i))
        }
        nnz(i) = totalNnz
        i += 1
      }
    } else if (totalCnt == 0 && other.totalCnt != 0) {
      this.n = other.n
      if (configure("mean") || configure("variance")) this.currMean = other.currMean.clone()
      if (configure("variance")) this.currM2n = other.currM2n.clone()
      if (configure("normL2")) this.currM2 = other.currM2.clone()
      if (configure("normL1")) this.currL1 = other.currL1.clone()
      this.totalCnt = other.totalCnt
      this.nnz = other.nnz.clone()
      if (configure("max")) this.currMax = other.currMax.clone()
      if (configure("min")) this.currMin = other.currMin.clone()
    }
    this
  }

  override def mean: Vector = {
    require(totalCnt > 0, s"Nothing has been added to this summarizer.")
    require(configure("mean"), s"Mean is not computed for this summarizer.")

    val realMean = Array.ofDim[Double](n)
    var i = 0
    while (i < n) {
      realMean(i) = currMean(i) * (nnz(i) / totalCnt)
      i += 1
    }
    Vectors.dense(realMean)
  }

  override def variance: Vector = {
    require(totalCnt > 0, s"Nothing has been added to this summarizer.")
    require(configure("variance"), s"Variance is not computed for this summarizer.")

    val realVariance = Array.ofDim[Double](n)

    val denominator = totalCnt - 1.0

    // Sample variance is computed, if the denominator is less than 0, the variance is just 0.
    if (denominator > 0.0) {
      val deltaMean = currMean
      var i = 0
      val len = currM2n.length
      while (i < len) {
        realVariance(i) =
          currM2n(i) + deltaMean(i) * deltaMean(i) * nnz(i) * (totalCnt - nnz(i)) / totalCnt
        realVariance(i) /= denominator
        i += 1
      }
    }
    Vectors.dense(realVariance)
  }

  override def count: Long = totalCnt

  override def numNonzeros: Vector = {
    require(totalCnt > 0, s"Nothing has been added to this summarizer.")

    Vectors.dense(nnz)
  }

  override def max: Vector = {
    require(totalCnt > 0, s"Nothing has been added to this summarizer.")
    require(configure("max"), s"Max is not computed for this summarizer.")

    var i = 0
    while (i < n) {
      if ((nnz(i) < totalCnt) && (currMax(i) < 0.0)) currMax(i) = 0.0
      i += 1
    }
    Vectors.dense(currMax)
  }

  override def min: Vector = {
    require(totalCnt > 0, s"Nothing has been added to this summarizer.")
    require(configure("min"), s"Min is not computed for this summarizer.")

    var i = 0
    while (i < n) {
      if ((nnz(i) < totalCnt) && (currMin(i) > 0.0)) currMin(i) = 0.0
      i += 1
    }
    Vectors.dense(currMin)
  }

  override def normL2: Vector = {
    require(totalCnt > 0, s"Nothing has been added to this summarizer.")
    require(configure("normL2"), s"NormL2 is not computed for this summarizer.")

    val realMagnitude = Array.ofDim[Double](n)

    var i = 0
    val len = currM2.length
    while (i < len) {
      realMagnitude(i) = math.sqrt(currM2(i))
      i += 1
    }
    Vectors.dense(realMagnitude)
  }

  override def normL1: Vector = {
    require(totalCnt > 0, s"Nothing has been added to this summarizer.")
    require(configure("normL1"), s"NormL1 is not computed for this summarizer.")

    Vectors.dense(currL1)
  }
}
