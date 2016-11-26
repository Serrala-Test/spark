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

package org.apache.spark.sql.catalyst.expressions.aggregate

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}
import java.util

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.TypeCheckResult
import org.apache.spark.sql.catalyst.analysis.TypeCheckResult.{TypeCheckFailure, TypeCheckSuccess}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate.Percentile.Countings
import org.apache.spark.sql.catalyst.util._
import org.apache.spark.sql.types._
import org.apache.spark.util.collection.OpenHashMap


/**
 * The Percentile aggregate function returns the exact percentile(s) of numeric column `expr` at
 * the given percentage(s) with value range in [0.0, 1.0].
 *
 * The operator is bound to the slower sort based aggregation path because the number of elements
 * and their partial order cannot be determined in advance. Therefore we have to store all the
 * elements in memory, and that too many elements can cause GC paused and eventually OutOfMemory
 * Errors.
 *
 * @param child child expression that produce numeric column value with `child.eval(inputRow)`
 * @param percentageExpression Expression that represents a single percentage value or an array of
 *                             percentage values. Each percentage value must be in the range
 *                             [0.0, 1.0].
 */
@ExpressionDescription(
  usage =
    """
      _FUNC_(col, percentage) - Returns the exact percentile value of numeric column `col` at the
      given percentage. The value of percentage must be between 0.0 and 1.0.

      _FUNC_(col, array(percentage1 [, percentage2]...)) - Returns the exact percentile value array
      of numeric column `col` at the given percentage(s). Each value of the percentage array must
      be between 0.0 and 1.0.
    """)
case class Percentile(
  child: Expression,
  percentageExpression: Expression,
  mutableAggBufferOffset: Int = 0,
  inputAggBufferOffset: Int = 0) extends TypedImperativeAggregate[Countings] {

  def this(child: Expression, percentageExpression: Expression) = {
    this(child, percentageExpression, 0, 0)
  }

  override def prettyName: String = "percentile"

  override def withNewMutableAggBufferOffset(newMutableAggBufferOffset: Int): Percentile =
    copy(mutableAggBufferOffset = newMutableAggBufferOffset)

  override def withNewInputAggBufferOffset(newInputAggBufferOffset: Int): Percentile =
    copy(inputAggBufferOffset = newInputAggBufferOffset)

  // Mark as lazy so that percentageExpression is not evaluated during tree transformation.
  private lazy val returnPercentileArray = percentageExpression.dataType.isInstanceOf[ArrayType]

  @transient
  private lazy val percentages = evalPercentages(percentageExpression)

  override def children: Seq[Expression] = child :: percentageExpression :: Nil

  // Returns null for empty inputs
  override def nullable: Boolean = true

  override lazy val dataType: DataType = percentageExpression.dataType match {
    case _: ArrayType => ArrayType(DoubleType, false)
    case _ => DoubleType
  }

  override def inputTypes: Seq[AbstractDataType] = percentageExpression.dataType match {
    case _: ArrayType => Seq(NumericType, ArrayType(DoubleType, false))
    case _ => Seq(NumericType, DoubleType)
  }

  // Check the inputTypes are valid, and the percentageExpression satisfies:
  // 1. percentageExpression must be foldable;
  // 2. percentages(s) must be in the range [0.0, 1.0].
  override def checkInputDataTypes(): TypeCheckResult = {
    // Validate the inputTypes
    val defaultCheck = super.checkInputDataTypes()
    if (defaultCheck.isFailure) {
      defaultCheck
    } else if (!percentageExpression.foldable) {
      // percentageExpression must be foldable
      TypeCheckFailure(s"The percentage(s) must be a constant literal, " +
        s"but got ${percentageExpression}")
    } else if (percentages.exists(percentage => percentage < 0.0 || percentage > 1.0)) {
      // percentages(s) must be in the range [0.0, 1.0]
      TypeCheckFailure(s"Percentage(s) must be between 0.0 and 1.0, " +
        s"but got ${percentageExpression}")
    } else {
      TypeCheckSuccess
    }
  }

  override def createAggregationBuffer(): Countings = {
    // Initialize new Countings instance here.
    Countings()
  }

  private def evalPercentages(expr: Expression): Seq[Double] = (expr.dataType, expr.eval()) match {
    case (_, n: Number) => Array(n.doubleValue())
    case (_, d: Decimal) => Array(d.toDouble)
    case (ArrayType(baseType: NumericType, _), arrayData: ArrayData) =>
      val numericArray = arrayData.toObjectArray(baseType)
      numericArray.map { x =>
        baseType.numeric.toDouble(x.asInstanceOf[baseType.InternalType])
      }
    case other =>
      throw new AnalysisException(s"Invalid data type ${other._1} for parameter percentage")
  }

  override def update(buffer: Countings, input: InternalRow): Unit = {
    val key = child.eval(input).asInstanceOf[Number]
    buffer.add(key)
  }

  override def merge(buffer: Countings, other: Countings): Unit = {
    buffer.merge(other)
  }

  override def eval(buffer: Countings): Any = {
    generateOutput(buffer.getPercentiles(percentages))
  }

  private def generateOutput(results: Seq[Double]): Any = {
    if (results.isEmpty) {
      null
    } else if (returnPercentileArray) {
      new GenericArrayData(results)
    } else {
      results.head
    }
  }

  override def serialize(obj: Countings): Array[Byte] = {
    Percentile.serializer.serialize(obj, child.dataType)
  }

  override def deserialize(bytes: Array[Byte]): Countings = {
    Percentile.serializer.deserialize(bytes, child.dataType)
  }
}

object Percentile {

  object Countings {
    def apply(): Countings = Countings(new OpenHashMap[Number, Long])

    def apply(counts: OpenHashMap[Number, Long]): Countings = new Countings(counts)
  }

  /**
   * A class that stores the numbers and their counts, used to support [[Percentile]] function.
   */
  class Countings(val counts: OpenHashMap[Number, Long]) extends Serializable {
    /**
     * Insert a key into countings map.
     */
    def add(key: Number): Unit = {
      // Null values are ignored in countings.
      if (key != null) {
        counts.changeValue(key, 1L, _ + 1L)
      }
    }

    /**
     * In place merges in another Countings.
     */
    def merge(other: Countings): Unit = {
      other.counts.foreach { pair =>
        counts.changeValue(pair._1, pair._2, _ + pair._2)
      }
    }

    /**
     * Get the percentile value for every percentile in `percentages`.
     */
    def getPercentiles(percentages: Seq[Double]): Seq[Double] = {
      if (counts.isEmpty) {
        return Seq.empty
      }

      val sortedCounts = counts.toSeq.sortBy(_._1)(new Ordering[Number]() {
        override def compare(a: Number, b: Number): Int =
          scala.math.signum(a.doubleValue() - b.doubleValue()).toInt
      })
      var sum = 0L
      val aggreCounts = sortedCounts.map { case (key, count) =>
        sum += count
        (key, sum)
      }
      val maxPosition = aggreCounts.last._2 - 1

      percentages.map { percentile =>
        getPercentile(aggreCounts, maxPosition * percentile).doubleValue()
      }
    }

    /**
     * Get the percentile value.
     *
     * This function has been based upon similar function from HIVE
     * `org.apache.hadoop.hive.ql.udf.UDAFPercentile.getPercentile()`.
     */
    private def getPercentile(aggreCounts: Seq[(Number, Long)], position: Double): Number = {
      // We may need to do linear interpolation to get the exact percentile
      val lower = position.floor.toLong
      val higher = position.ceil.toLong

      // Use binary search to find the lower and the higher position.
      val countsArray = aggreCounts.map(_._2).toArray[Long]
      val lowerIndex = binarySearchCount(countsArray, 0, aggreCounts.size, lower + 1)
      val higherIndex = binarySearchCount(countsArray, 0, aggreCounts.size, higher + 1)

      val lowerKey = aggreCounts(lowerIndex)._1
      if (higher == lower) {
        // no interpolation needed because position does not have a fraction
        return lowerKey
      }

      val higherKey = aggreCounts(higherIndex)._1
      if (higherKey == lowerKey) {
        // no interpolation needed because lower position and higher position has the same key
        return lowerKey
      }

      // Linear interpolation to get the exact percentile
      return (higher - position) * lowerKey.doubleValue() +
        (position - lower) * higherKey.doubleValue()
    }

    /**
     * use a binary search to find the index of the position closest to the current value.
     */
    private def binarySearchCount(
        countsArray: Array[Long], start: Int, end: Int, value: Long): Int = {
      util.Arrays.binarySearch(countsArray, 0, end, value) match {
        case ix if ix < 0 => -(ix + 1)
        case ix => ix
      }
    }
  }

  /**
   * Serializer for class [[Countings]]
   *
   * This class is thread safe.
   */
  class CountingsSerializer {

    final def serialize(obj: Countings, dataType: DataType): Array[Byte] = {
      val buffer = new Array[Byte](4 << 10)  // 4K
      val bos = new ByteArrayOutputStream()
      val out = new DataOutputStream(bos)
      try {
        val counts = obj.counts
        val projection = UnsafeProjection.create(Array[DataType](dataType, LongType))
        // Write pairs in counts map to byte buffer.
        counts.foreach { case (key, count) =>
          val row = InternalRow.apply(key, count)
          val unsafeRow = projection.apply(row)
          out.writeInt(unsafeRow.getSizeInBytes)
          unsafeRow.writeToStream(out, buffer)
        }
        out.writeInt(-1)
        out.flush()

        bos.toByteArray
      } finally {
        out.close()
        bos.close()
      }
    }

    final def deserialize(bytes: Array[Byte], dataType: DataType): Countings = {
      val bis = new ByteArrayInputStream(bytes)
      val ins = new DataInputStream(bis)
      try {
        val counts = new OpenHashMap[Number, Long]
        // Read unsafeRow size and content in bytes.
        var sizeOfNextRow = ins.readInt()
        while (sizeOfNextRow >= 0) {
          val bs = new Array[Byte](sizeOfNextRow)
          ins.readFully(bs)
          val row = new UnsafeRow(2)
          row.pointTo(bs, sizeOfNextRow)
          // Insert the pairs into counts map.
          val key = row.get(0, dataType).asInstanceOf[Number]
          val count = row.get(1, LongType).asInstanceOf[Long]
          counts.update(key, count)
          sizeOfNextRow = ins.readInt()
        }

        Countings(counts)
      } finally {
        ins.close()
        bis.close()
      }
    }
  }

  val serializer: CountingsSerializer = new CountingsSerializer
}
