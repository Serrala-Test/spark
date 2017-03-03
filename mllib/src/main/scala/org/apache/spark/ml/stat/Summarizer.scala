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

package org.apache.spark.ml.stat

import org.apache.spark.annotation.{DeveloperApi, Since}
import org.apache.spark.ml.linalg.{SQLDataTypes, Vector, Vectors}
import org.apache.spark.sql.{Column, Row}
import org.apache.spark.sql.expressions.{MutableAggregationBuffer, UserDefinedAggregateFunction}
import org.apache.spark.sql.types._

/**
 * A builder object that provides summary statistics about a given column.
 *
 * Users should not directly create such builders, but instead use one of the methods in
 * [[Summarizer]].
 */
@Since("2.2.0")
abstract class SummaryBuilder {
  /**
   * Returns an aggregate object that contains the summary of the column with the requested metrics.
   * @param column a column that contains Vector object.
   * @return an aggregate column that contains the statistics. The exact content of this
   *         structure is determined during the creation of the builder. It is also provided by
   *         [[summaryStructure]].
   */
  @Since("2.2.0")
  def summary(column: Column): Column

  /**
   * The structure of the summary that will be returned by this builder.
   * @return the structure of the aggregate data returned by this builder.
   */
  @Since("2.2.0")
  @DeveloperApi
  private[spark] def summaryStructure: StructType
}

/**
 * Tools for vectorized statistics on MLlib Vectors.
 *
 * The methods in this package provide various statistics for Vectors contained inside DataFrames.
 *
 * This class lets users pick the statistics they would like to extract for a given column. Here is
 * an example in Scala:
 * {{{
 *   val dataframe = ... // Some dataframe containing a feature column
 *   val allStats = dataframe.select(Summarizer.metrics("min", "max").summary($"features"))
 *   val Row(min_, max_) = allStats.first()
 * }}}
 *
 * If one wants to get a single metric, shortcuts are also available:
 * {{{
 *   val meanDF = dataframe.select(Summarizer.mean($"features"))
 *   val Row(mean_) = meanDF.first()
 * }}}
 */
@Since("2.2.0")
object Summarizer {

  import SummaryBuilderImpl._

  /**
   * Given a list of metrics, provides a builder that it turns computes metrics from a column.
   *
   * See the documentation of [[Summarizer]] for an example.
   *
   * @param firstMetric the metric being provided
   * @param metrics additional metrics that can be provided.
   * @return a builder.
   * @throws IllegalArgumentException if one of the metric names is not understood.
   */
  @Since("2.2.0")
  def metrics(firstMetric: String, metrics: String*): SummaryBuilder = {
    val (typedMetrics, computeMetrics) = getRelevantMetrics(Seq(firstMetric) ++ metrics)
    new SummaryBuilderImpl(
      SummaryBuilderImpl.buildUdafForMetrics(computeMetrics),
      SummaryBuilderImpl.structureForMetrics(typedMetrics))
  }

  def mean(col: Column): Column = metrics("mean").summary(col)
}

private[ml] class SummaryBuilderImpl(
  udaf: UserDefinedAggregateFunction,
  structType: StructType) extends SummaryBuilder {

  override def summary(column: Column): Column = udaf.apply(column)

  override val summaryStructure: StructType = structType
}

private[ml]
object SummaryBuilderImpl {

  def implementedMetrics: Seq[String] = allMetrics.map(_._1).sorted

  @throws[IllegalArgumentException]("When the list is empty or not a subset of known metrics")
  def getRelevantMetrics(requested: Seq[String]): (Seq[Metrics], Seq[ComputeMetrics]) = {
    val all = requested.map { req =>
      val (_, metric, deps) = allMetrics.find(tup => tup._1 == req).getOrElse {
        throw new IllegalArgumentException(s"Metric $req cannot be found." +
          s" Valid metris are $implementedMetrics")
      }
      metric -> deps
    }
    // TODO: do not sort, otherwise the user has to look the schema to see the order that it
    // is going to be given in.
    val metrics = all.map(_._1).distinct.sortBy(_.toString)
    val computeMetrics = all.flatMap(_._2).distinct.sortBy(_.toString)
    metrics -> computeMetrics
  }

  def structureForMetrics(metrics: Seq[Metrics]): StructType = {
    val fields = metrics.map { m =>
      // All types are vectors, except for one.
      val tpe = if (m == Count) { LongType } else { SQLDataTypes.VectorType }
      // We know the metric is part of the list.
      val (name, _, _) = allMetrics.find(tup => tup._2 == m).get
      StructField(name, tpe, nullable = false)
    }
    StructType(fields)
  }

  def buildUdafForMetrics(metrics: Seq[ComputeMetrics]): UserDefinedAggregateFunction = null

  /**
   * All the metrics that can be currently computed by Spark for vectors.
   *
   * This list associates the user name, the internal (typed) name, and the list of computation
   * metrics that need to de computed internally to get the final result.
   */
  private val allMetrics = Seq(
    ("mean", Mean, Seq(ComputeMean, ComputeWeightSum, ComputeTotalWeightSum)),
    ("variance", Variance, Seq(ComputeTotalWeightSum, ComputeWeightSquareSum, ComputeMean,
                               ComputeM2n)),
    ("count", Count, Seq(ComputeCount)),
    ("numNonZeros", NumNonZeros, Seq(ComputeNNZ)),
    ("max", Max, Seq(ComputeMax, ComputeTotalWeightSum, ComputeNNZ, ComputeCount)),
    ("min", Min, Seq(ComputeMin, ComputeTotalWeightSum, ComputeNNZ, ComputeCount)),
    ("normL2", NormL2, Seq(ComputeTotalWeightSum, ComputeM2)),
    ("normL1", Min, Seq(ComputeTotalWeightSum, ComputeL1))
  )

  /**
   * The metrics that are currently implemented.
   */
  sealed trait Metrics
  case object Mean extends Metrics
  case object Variance extends Metrics
  case object Count extends Metrics
  case object NumNonZeros extends Metrics
  case object Max extends Metrics
  case object Min extends Metrics
  case object NormL2 extends Metrics
  case object NormL1 extends Metrics

  /**
   * The running metrics that are going to be computing.
   *
   * There is a bipartite graph between the metrics and the computed metrics.
   */
  private sealed trait ComputeMetrics
  case object ComputeMean extends ComputeMetrics
  case object ComputeM2n extends ComputeMetrics
  case object ComputeM2 extends ComputeMetrics
  case object ComputeL1 extends ComputeMetrics
  case object ComputeCount extends ComputeMetrics
  case object ComputeTotalWeightSum extends ComputeMetrics
  case object ComputeWeightSquareSum extends ComputeMetrics
  case object ComputeWeightSum extends ComputeMetrics
  case object ComputeNNZ extends ComputeMetrics
  case object ComputeMax extends ComputeMetrics
  case object ComputeMin extends ComputeMetrics

  // The order in which the metrics will be stored in the buffer.
  // This order replicates the order above.
  private val metricsWithOrder = Seq(ComputeMean, ComputeM2n, ComputeM2, ComputeL1, ComputeCount,
    ComputeTotalWeightSum, ComputeWeightSquareSum, ComputeWeightSum, ComputeNNZ, ComputeMax,
    ComputeMin).zipWithIndex


  private class MetricsUDAF(
      requested: Seq[Metrics],
      metrics: Seq[ComputeMetrics]) extends UserDefinedAggregateFunction {

    // All of these are the indexes of the value in the temporary buffer, or -1 if these values are
    // not requested.
    // All these values are unrolled so that we do not pay too steep a penalty for the extra level
    // of indirection associated with selectively computing some metrics.
    private[this] val computeMean = getIndexFor(ComputeMean)
    private[this] val computeM2n = getIndexFor(ComputeM2n)
    private[this] val computeM2 = getIndexFor(ComputeM2)
    private[this] val computeL1 = getIndexFor(ComputeL1)
    private[this] val computeTotalCount = getIndexFor(ComputeCount)
    // TODO: why is it not used?
    private[this] val computeTotalWeightSum = getIndexFor(ComputeTotalWeightSum)
    private[this] val computeWeightSquareSum = getIndexFor(ComputeWeightSquareSum)
    private[this] val computeWeightSum = getIndexFor(ComputeWeightSum)
    private[this] val computeNNZ = getIndexFor(ComputeNNZ)
    private[this] val computeCurrMax = getIndexFor(ComputeMax)
    private[this] val computeCurrMin = getIndexFor(ComputeMin)
    // Always computed, and put at the end.
    private[this] val computeN = metricsWithOrder.size

    override def inputSchema: StructType = {
      StructType(Seq(StructField("features", SQLDataTypes.VectorType)))
    }

    // The schema for the buffer contains all the structures, but a subset of them may be null.
    override def bufferSchema: StructType = {
      val fields = metricsWithOrder.map { case (m, _) =>
        // TODO(thunterdb) this could be merged with the types above so that there is no confusion
        // when adding other types later, and with the initialization below.
        val tpe = m match {
          case ComputeCount => LongType
          case ComputeTotalWeightSum => DoubleType
          case ComputeWeightSquareSum => DoubleType
          case _ => SQLDataTypes.VectorType
        }
        StructField(m.toString, tpe, nullable = true)
      } :+ StructField("n", IntegerType, nullable = false)
      StructType(fields)
    }

    override def dataType: DataType = {
      structureForMetrics(requested)
    }

    override def deterministic: Boolean = true

    // The initialization does not do anything because we do not know the size of the vectors yet.
    override def initialize(buffer: MutableAggregationBuffer): Unit = {
      // Set n to -1 to indicate that we do not know the size yet.
      buffer.update(computeN, -1)
    }

    override def update(buffer: MutableAggregationBuffer, input: Row): Unit = {
      val v = input.getAs[Vector](0)
      // TODO: add weights later.
      update(buffer, v, 1.0)
    }

    override def merge(buffer1: MutableAggregationBuffer, buffer2: Row): Unit = {
      val thisUsed = ((computeTotalWeightSum >= 0 && buffer1.getDouble(computeTotalWeightSum) > 0)
                    || (computeTotalCount >= 0 && buffer1.getLong(computeTotalCount) > 0))
      val otherUsed = ((computeTotalWeightSum >= 0 && buffer2.getDouble(computeTotalWeightSum) > 0)
        || (computeTotalCount >= 0 && buffer2.getLong(computeTotalCount) > 0))
      if (thisUsed && otherUsed) {
        // Merge onto this one.
        val n1 = getN(buffer1)
        val n2 = getN(buffer2)
        require(n1 == n2, s"Dimensions mismatch between summarizers: expected $n1 but got $n2")
        require(n1 > 0, s"Expected the summarizers to be initialized")
        // The scalar values.
        if (computeTotalCount >= 0) {
          buffer1.update(computeTotalCount,
            buffer1.getLong(computeTotalCount) + buffer2.getLong(computeTotalCount))
        }
        if (computeTotalWeightSum >= 0) {
          buffer1.update(computeTotalWeightSum,
            buffer1.getDouble(computeTotalWeightSum) + buffer2.getDouble(computeTotalWeightSum))
        }
        if (computeWeightSquareSum >= 0) {
          buffer1.update(computeWeightSquareSum,
            buffer1.getDouble(computeWeightSquareSum) + buffer2.getDouble(computeWeightSquareSum))
        }
        // The vector values.
        val currMean = exposeDArray(buffer1, computeMean)
        val currM2n = exposeDArray(buffer1, computeM2n)
        val currM2 = exposeDArray(buffer1, computeM2)
        val currL1 = exposeDArray(buffer1, computeL1)
        val weightSum = exposeDArray(buffer1, computeWeightSum)
        val numNonzeros = exposeDArray(buffer1, computeNNZ)
        val currMax = exposeDArray(buffer1, computeCurrMax)
        val currMin = exposeDArray(buffer1, computeCurrMin)

        val otherMean = exposeDArray2(buffer2, computeMean)
        val otherM2n = exposeDArray2(buffer2, computeM2n)
        val otherM2 = exposeDArray2(buffer2, computeM2)
        val otherL1 = exposeDArray2(buffer2, computeL1)
        val otherWeightSum = exposeDArray2(buffer2, computeWeightSum)
        val otherNumNonzeros = exposeDArray2(buffer2, computeNNZ)
        val otherMax = exposeDArray2(buffer2, computeCurrMax)
        val otherMin = exposeDArray2(buffer2, computeCurrMin)


        var i = 0
        while (i < n1) {
          // This case is written do maximize conformance with the current code.
          // For more proper implementation, the differnt loops should be disentangled.
          // The case of 0.0 values being used should not happen if the proper selection
          // of metrics is done.
          // TODO: should it be NaN so that any computation with it will indicate an error,
          // instead of failing silently?
          // Using exceptions is not great for performance in this loop.
          val thisNnz = if (weightSum == null) { 0.0 } else { weightSum(i) }
          val otherNnz = if (otherWeightSum == null) { 0.0 } else { otherWeightSum(i) }
          val totalNnz = thisNnz + otherNnz
          val totalCnnz = if (numNonzeros == null || numNonzeros == null) { 0.0 } else {
            numNonzeros(i) + otherNumNonzeros(i)
          }
          if (totalNnz != 0.0) {
            val deltaMean = if (computeMean >= 0) { otherMean(i) - currMean(i) } else { 0.0 }
            // merge mean together
            if (computeMean >= 0) {
              currMean(i) += deltaMean * otherNnz / totalNnz
            }
            // merge m2n together
            if (computeM2n >= 0) {
              currM2n(i) += otherM2n(i) + deltaMean * deltaMean * thisNnz * otherNnz / totalNnz
            }
            // merge m2 together
            if (computeM2 >= 0) {
              currM2(i) += otherM2(i)
            }
            // merge l1 together
            if (computeL1 >= 0) {
              currL1(i) += otherL1(i)
            }
            // merge max and min
            if (computeCurrMax >= 0) {
              currMax(i) = math.max(currMax(i), otherMax(i))
            }
            if (computeCurrMin >= 0) {
              currMin(i) = math.min(currMin(i), otherMin(i))
            }
          }
          if (computeWeightSum >= 0) {
            weightSum(i) = totalNnz
          }
          if (computeNNZ >= 0) {
            numNonzeros(i) = totalCnnz
          }

          i += 1
        }
      } else if (otherUsed) {
        // Copy buffer1 onto buffer2.
        // TODO
      }
    }

    // This function packs the requested values, in order.
    override def evaluate(buffer: Row): Row = {
      val values = requested.map {
        case Mean => evaluateMean(buffer)
        case Variance => evaluateVariance(buffer)
        case NumNonZeros => evaluateNNZ(buffer)
        case Max => evaluateMax(buffer)
        case Min => evaluateMin(buffer)
        case NormL1 => evaluateNormL1(buffer)
        case NormL2 => evaluateNormL2(buffer)
      }
      Row(values: _*)
    }

    private def evaluateMean(buffer: Row): Vector = {
      assert(computeMean >= 0) // This is a programming error.
      assert(computeWeightSum >= 0)
      assert(computeTotalWeightSum >= 0)
      val n = getN(buffer)
      assert(n >= 0)
      val realMean = Array.ofDim[Double](n)
      val currMean = exposeDArray2(buffer, computeMean)
      val weightSum = exposeDArray2(buffer, computeWeightSum)
      val totalWeightSum = buffer.getDouble(computeTotalWeightSum)
      // TODO: should this be turned into NaN instead? That would be more consistent than
      // an error.
      require(totalWeightSum > 0, "Data has zero weight")
      var i = 0
      while (i < n) {
        realMean(i) = currMean(i) * (weightSum(i) / totalWeightSum)
        i += 1
      }
      Vectors.dense(realMean)
    }

    private def evaluateVariance(buffer: Row): Vector = {
      assert(computeTotalWeightSum >= 0)
      assert(computeWeightSquareSum >= 0)
      assert(computeWeightSum >= 0)
      assert(computeM2 >= 0)
      assert(computeM2n >= 0)

      val totalWeightSum = buffer.getDouble(computeTotalWeightSum)
      require(totalWeightSum > 0, s"Nothing has been added to this summarizer.")

      val n = getN(buffer)
      assert(n >= 0)

      val weightSquareSum = buffer.getDouble(computeWeightSquareSum)
      val realVariance = Array.ofDim[Double](n)

      val currMean = exposeDArray2(buffer, computeMean)
      val currM2n = exposeDArray2(buffer, computeM2n)
      val weightSum = exposeDArray2(buffer, computeWeightSum)
      val denominator = totalWeightSum - (weightSquareSum / totalWeightSum)

      // Sample variance is computed, if the denominator is less than 0, the variance is just 0.
      if (denominator > 0.0) {
        val deltaMean = currMean
        var i = 0
        val len = currM2n.length
        while (i < len) {
          realVariance(i) = (currM2n(i) + deltaMean(i) * deltaMean(i) * weightSum(i) *
            (totalWeightSum - weightSum(i)) / totalWeightSum) / denominator
          i += 1
        }
      }
      Vectors.dense(realVariance)
    }

    private def evaluateNNZ(buffer: Row): Vector = {
      null // TODO
    }

    private def evaluateMax(buffer: Row): Vector = {
      null // TODO
    }

    private def evaluateMin(buffer: Row): Vector = {
      null // TODO
    }

    private def evaluateNormL1(buffer: Row): Vector = {
      null // TODO
    }

    private def evaluateNormL2(buffer: Row): Vector = {
      null // TODO
    }

    private def update(buffer: MutableAggregationBuffer, instance: Vector, weight: Double): Unit = {
      val startN = getN(buffer)
      if (startN == -1) {
        initialize(buffer, instance.size)
      } else {
        require(startN == instance.size,
          s"Trying to insert a vector of size $instance into a buffer that " +
          s"has been sized with $startN")
      }
      // It should be initialized.
      assert(getN(buffer) > 0)
      // Update the scalars that do not require going through the vector first.
      if (computeTotalWeightSum >= 0) {
        buffer.update(computeTotalWeightSum, buffer.getDouble(computeTotalWeightSum) + weight)
      }
      if (computeWeightSquareSum >= 0) {
        buffer.update(computeWeightSquareSum,
          buffer.getDouble(computeWeightSquareSum) + weight * weight)
      }
      if (computeTotalCount >= 0) {
        buffer.update(computeTotalCount, buffer.getLong(computeTotalCount) + 1)
      }

      // All these may be null pointers.
      val localCurrMean = exposeDArray(buffer, computeMean)
      val localCurrM2n = exposeDArray(buffer, computeM2n)
      val localCurrM2 = exposeDArray(buffer, computeM2)
      val localCurrL1 = exposeDArray(buffer, computeL1)
      val localWeightSum = exposeDArray(buffer, computeWeightSum)
      val localNumNonzeros = exposeDArray(buffer, computeNNZ)
      val localCurrMax = exposeDArray(buffer, computeCurrMax)
      val localCurrMin = exposeDArray(buffer, computeCurrMin)

      // Update the requested vector values.
      // All these changes are in place. There is no need to make copies.
      instance.foreachActive { (index, value) =>
        if (value != 0.0) {
          if (localCurrMax != null && localCurrMax(index) < value) {
             localCurrMax(index) = value
          }
          if (localCurrMin != null && localCurrMin(index) > value) {
            localCurrMin(index) = value
          }

          if (localCurrMean != null) {
            val prevMean = localCurrMean(index)
            val diff = value - prevMean
            localCurrMean(index) = prevMean + weight * diff / (localWeightSum(index) + weight)
            if (localCurrM2n != null) {
              // require: localCurrMean != null.
              localCurrM2n(index) += weight * (value - localCurrMean(index)) * diff
            }
          }

          if (localCurrM2 != null) {
            localCurrM2(index) += weight * value * value
          }
          if (localCurrL1 != null) {
            localCurrL1(index) += weight * math.abs(value)
          }
          if (localWeightSum != null) {
            localWeightSum(index) += weight
          }
          if (localNumNonzeros != null) {
            localNumNonzeros(index) += 1
          }
        }
      }
    }

    // the current vector size, or -1 if not available yet.
    private def getN(buffer: Row): Int = {
      buffer.getInt(computeN)
    }


    private[this] def exposeDArray2(buffer: Row, index: Int): Array[Double] = {
      if (index == -1) {
        null
      } else {
        buffer.getAs[Array[Double]](index)
      }
    }

    private[this] def exposeDArray(buffer: MutableAggregationBuffer, index: Int): Array[Double] = {
      if (index == -1) {
        null
      } else {
        buffer.getAs[Array[Double]](index)
      }
    }

    // Performs the initialization of the requested fields, if required.
    private def initialize(buffer: MutableAggregationBuffer, n: Int): Unit = {
      require(n > 0, s"Cannot set n to $n: must be positive")
      val currentN = buffer.getInt(computeN)
      require(currentN == -1 || (currentN == n),
        s"The vector size has already been set with $currentN, requested change to $n")
      if (currentN == -1) {
        // Set all the vectors to the given value.
        // This is not a tight loop, so there should be no performance issue in
        // making lookups for the values.
        buffer.update(computeN, n)
        // Only initialize the metricts that we want to compute:
        metricsWithOrder.filter(p => metrics.contains(p._1)).foreach { case (m, idx) =>
          // Special cases for the scalar values.
          m match {
            case ComputeCount =>
              buffer.update(idx, 0L)
            case ComputeTotalWeightSum =>
              buffer.update(idx, 0.0)
            case ComputeWeightSquareSum =>
              buffer.update(idx, 0.0)
            case _ =>
              buffer.update(idx, Array.ofDim[Double](n))
          }
        }
      }
    }

    /**
     * Returns a fixed index for the compute metric if it is in the list of requested metrics,
     * and -1 otherwise.
     */
    private def getIndexFor(m: ComputeMetrics): Int = {
      if (metrics.contains(m)) {
        metricsWithOrder.find(p => p._1 == m).get._2
      } else {
        -1
      }
    }

  }

}
