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

package org.apache.spark.sql.catalyst.plans.logical.statsEstimation

import scala.math.BigDecimal.RoundingMode

import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeMap}
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.types.{DecimalType, _}


object EstimationUtils {

  /** Check if each plan has rowCount in its statistics. */
  def rowCountsExist(plans: LogicalPlan*): Boolean =
    plans.forall(_.stats.rowCount.isDefined)

  /** Check if each attribute has column stat in the corresponding statistics. */
  def columnStatsExist(statsAndAttr: (Statistics, Attribute)*): Boolean = {
    statsAndAttr.forall { case (stats, attr) =>
      stats.attributeStats.contains(attr)
    }
  }

  def nullColumnStat(dataType: DataType, rowCount: BigInt): ColumnStat = {
    ColumnStat(distinctCount = 0, min = None, max = None, nullCount = rowCount,
      avgLen = dataType.defaultSize, maxLen = dataType.defaultSize, histogram = None)
  }

  /**
   * Updates (scales down) the number of distinct values if the number of rows decreases after
   * some operation (such as filter, join). Otherwise keep it unchanged.
   */
  def updateNdv(oldNumRows: BigInt, newNumRows: BigInt, oldNdv: BigInt): BigInt = {
    if (newNumRows < oldNumRows) {
      ceil(BigDecimal(oldNdv) * BigDecimal(newNumRows) / BigDecimal(oldNumRows))
    } else {
      oldNdv
    }
  }

  def ceil(bigDecimal: BigDecimal): BigInt = bigDecimal.setScale(0, RoundingMode.CEILING).toBigInt()

  /** Get column stats for output attributes. */
  def getOutputMap(inputMap: AttributeMap[ColumnStat], output: Seq[Attribute])
    : AttributeMap[ColumnStat] = {
    AttributeMap(output.flatMap(a => inputMap.get(a).map(a -> _)))
  }

  def getOutputSize(
      attributes: Seq[Attribute],
      outputRowCount: BigInt,
      attrStats: AttributeMap[ColumnStat] = AttributeMap(Nil)): BigInt = {
    // We assign a generic overhead for a Row object, the actual overhead is different for different
    // Row format.
    val sizePerRow = 8 + attributes.map { attr =>
      if (attrStats.contains(attr)) {
        attr.dataType match {
          case StringType =>
            // UTF8String: base + offset + numBytes
            attrStats(attr).avgLen + 8 + 4
          case _ =>
            attrStats(attr).avgLen
        }
      } else {
        attr.dataType.defaultSize
      }
    }.sum

    // Output size can't be zero, or sizeInBytes of BinaryNode will also be zero
    // (simple computation of statistics returns product of children).
    if (outputRowCount > 0) outputRowCount * sizePerRow else 1
  }

  /**
   * For simplicity we use Decimal to unify operations for data types whose min/max values can be
   * represented as numbers, e.g. Boolean can be represented as 0 (false) or 1 (true).
   * The two methods below are the contract of conversion.
   */
  def toDecimal(value: Any, dataType: DataType): Decimal = {
    dataType match {
      case _: NumericType | DateType | TimestampType => Decimal(value.toString)
      case BooleanType => if (value.asInstanceOf[Boolean]) Decimal(1) else Decimal(0)
    }
  }

  def fromDecimal(dec: Decimal, dataType: DataType): Any = {
    dataType match {
      case BooleanType => dec.toLong == 1
      case DateType => dec.toInt
      case TimestampType => dec.toLong
      case ByteType => dec.toByte
      case ShortType => dec.toShort
      case IntegerType => dec.toInt
      case LongType => dec.toLong
      case FloatType => dec.toFloat
      case DoubleType => dec.toDouble
      case _: DecimalType => dec
    }
  }

  /**
   * Returns the number of the first bin/bucket into which a column values falls for a specified
   * numeric equi-height histogram.
   *
   * @param value a literal value of a column
   * @param histogram a numeric equi-height histogram
   * @return the number of the first bin/bucket into which a column values falls.
   */

  def findFirstBucketForValue(value: Double, histogram: EquiHeightHistogram): Int = {
    var binId = 0
    histogram.ehBuckets.foreach { bin =>
      if (value > bin.hi) binId += 1
    }
    binId
  }

  /**
   * Returns the number of the last bin/bucket into which a column values falls for a specified
   * numeric equi-height histogram.
   *
   * @param value a literal value of a column
   * @param histogram a numeric equi-height histogram
   * @return the number of the last bin/bucket into which a column values falls.
   */

  def findLastBucketForValue(value: Double, histogram: EquiHeightHistogram): Int = {
    var binId = 0
    for (i <- 0 until histogram.ehBuckets.length) {
      if (value > histogram.ehBuckets(i).hi) {
        // increment binId to point to next bin
        binId += 1
      }
      if ((value == histogram.ehBuckets(i).hi) && (i < histogram.ehBuckets.length - 1)) {
        if (value == histogram.ehBuckets(i + 1).lo) {
          // increment binId since the value appears into this bin and next bin
          binId += 1
        }
      }
    }
    binId
  }

  /**
   * Returns a percentage of a bin/bucket holding values for column value in the range of
   * [lowerValue, higherValue]
   *
   * @param bucketId a given bin/bucket id in a specified histogram
   * @param higherValue a given upper bound value of a specified column value range
   * @param lowerValue a given lower bound value of a specified column value range
   * @param histogram a numeric equi-height histogram
   * @return the percentage of a single bin/bucket holding values in [lowerValue, higherValue].
   */

  private def getOccupation(
      bucketId: Int,
      higherValue: Double,
      lowerValue: Double,
      histogram: EquiHeightHistogram): Double = {
    val curBucket = histogram.ehBuckets(bucketId)
    if (bucketId == 0 && curBucket.hi == curBucket.lo) {
      // the Min of the histogram occupies the whole first bucket
      1.0
    } else if (bucketId == 0 && curBucket.hi != curBucket.lo) {
      if (higherValue == lowerValue) {
        // in the case curBucket.binNdv == 0, current bucket is occupied by one value, which
        // is included in the previous bucket
        1.0 / math.max(curBucket.ndv.toDouble, 1)
      } else {
        (higherValue - lowerValue) / (curBucket.hi - curBucket.lo)
      }
    } else {
      if (curBucket.hi == curBucket.lo) {
        // the entire bucket is covered in the range
        1.0
      } else if (higherValue == lowerValue) {
        // the literal value falls in this bucket
        1.0 / math.max(curBucket.ndv.toDouble, 1)
      } else {
        // Use proration since the range falls inside this bucket.
        math.min((higherValue - lowerValue) / (curBucket.hi - curBucket.lo), 1.0)
      }
    }
  }

  /**
   * Returns the number of buckets for column values in [lowerValue, higherValue].
   * The column value distribution is saved in an equi-height histogram.
   *
   * @param higherEnd a given upper bound value of a specified column value range
   * @param lowerEnd a given lower bound value of a specified column value range
   * @param histogram a numeric equi-height histogram
   * @return the selectivity percentage for column values in [lowerValue, higherValue].
   */

  def getOccupationBuckets(
      higherEnd: Double,
      lowerEnd: Double,
      histogram: EquiHeightHistogram): Double = {
    // find buckets where current min and max locate
    val minBucketId = findFirstBucketForValue(lowerEnd, histogram)
    val maxBucketId = findLastBucketForValue(higherEnd, histogram)
    assert(minBucketId <= maxBucketId)

    // compute how much current [min, max] occupy the histogram, in the number of buckets
    getOccupationBuckets(maxBucketId, minBucketId, higherEnd, lowerEnd, histogram)
  }

  /**
   * Returns the number of buckets for column values in [lowerValue, higherValue].
   * This is an overloaded method. The column value distribution is saved in an
   * equi-height histogram.
   *
   * @param higherId id of the high end bucket holding the high end value of a column range
   * @param lowerId id of the low end bucket holding the low end value of a column range
   * @param higherEnd a given upper bound value of a specified column value range
   * @param lowerEnd a given lower bound value of a specified column value range
   * @param histogram a numeric equi-height histogram
   * @return the selectivity percentage for column values in [lowerEnd, higherEnd].
   */

  def getOccupationBuckets(
      higherId: Int,
      lowerId: Int,
      higherEnd: Double,
      lowerEnd: Double,
      histogram: EquiHeightHistogram): Double = {
    if (lowerId == higherId) {
      getOccupation(lowerId, higherEnd, lowerEnd, histogram)
    } else {
      // compute how much lowerEnd/higherEnd occupy its bucket
      val lowerCurBucket = histogram.ehBuckets(lowerId)
      val lowerPart = getOccupation(lowerId, lowerCurBucket.hi, lowerEnd, histogram)

      // in case higherId > lowerId, higherId must be > 0
      val higherCurBucket = histogram.ehBuckets(higherId)
      val higherPart = getOccupation(higherId, higherEnd, higherCurBucket.lo,
        histogram)
      // the total length is lowerPart + higherPart + buckets between them
      higherId - lowerId - 1 + lowerPart + higherPart
    }
  }

  /**
   * Returns the number of distinct values, ndv, for column values in [lowerEnd, higherEnd].
   * The column value distribution is saved in an equi-height histogram.
   *
   * @param higherId id of the high end bucket holding the high end value of a column range
   * @param lowerId id of the low end bucket holding the low end value of a column range
   * @param higherEnd a given upper bound value of a specified column value range
   * @param lowerEnd a given lower bound value of a specified column value range
   * @param histogram a numeric equi-height histogram
   * @return the number of distinct values, ndv, for column values in [lowerEnd, higherEnd].
   */

  def getOccupationNdv(
      higherId: Int,
      lowerId: Int,
      higherEnd: Double,
      lowerEnd: Double,
      histogram: EquiHeightHistogram)
    : Long = {
    val ndv: Double = if (higherEnd == lowerEnd) {
      1
    } else if (lowerId == higherId) {
      getOccupation(lowerId, higherEnd, lowerEnd, histogram) * histogram.ehBuckets(lowerId).ndv
    } else {
      // compute how much lowerEnd/higherEnd occupy its bucket
      val minCurBucket = histogram.ehBuckets(lowerId)
      val minPartNdv = getOccupation(lowerId, minCurBucket.hi, lowerEnd, histogram) *
        minCurBucket.ndv

      // in case higherId > lowerId, higherId must be > 0
      val maxCurBucket = histogram.ehBuckets(higherId)
      val maxPartNdv = getOccupation(higherId, higherEnd, maxCurBucket.lo, histogram) *
        maxCurBucket.ndv

      // The total ndv is minPartNdv + maxPartNdv + Ndvs between them.
      // In order to avoid counting same distinct value twice, we check if the upperBound value
      // of next bucket is equal to the hi value of the previous bucket.  We bump up
      // ndv value only if the hi values of two consecutive buckets are different.
      var middleNdv: Long = 0
      for (i <- histogram.ehBuckets.indices) {
        val bucket = histogram.ehBuckets(i)
        if (bucket.hi != bucket.lo && i >= lowerId + 1 && i <= higherId - 1) {
          middleNdv += bucket.ndv
        }
      }
      minPartNdv + maxPartNdv + middleNdv
    }
    math.round(ndv)
  }

}
