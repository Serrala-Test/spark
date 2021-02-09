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

package org.apache.spark.ml.tuning

import org.apache.spark.annotation.Since
import org.apache.spark.ml.param._

case class Limits[T: Numeric](x: T, y: T)

abstract class RandomT[T: Numeric] {
  def randomT(): T
  def randomTLog(n: Int): T
}

abstract class Generator[T: Numeric] {
  def apply(lim: Limits[T]): RandomT[T]
}

object RandomRanges {

  val rnd = new scala.util.Random

  private[tuning] def randomBigInt0To(x: BigInt): BigInt = {
    var randVal = BigInt(x.bitLength, rnd)
    while (randVal > x) {
      randVal = BigInt(x.bitLength, rnd)
    }
    randVal
  }

  def bigIntBetween(lower: BigInt, upper: BigInt): BigInt = {
    val diff: BigInt = upper - lower
    randomBigInt0To(diff) + lower
  }

  private def randomBigDecimalBetween(lower: BigDecimal, upper: BigDecimal): BigDecimal = {
    val zeroCenteredRnd: BigDecimal = BigDecimal(rnd.nextDouble() - 0.5)
    val range: BigDecimal = upper - lower
    val halfWay: BigDecimal = lower + range / 2
    (zeroCenteredRnd * range) + halfWay
  }

  implicit object DoubleGenerator extends Generator[Double] {
    def apply(limits: Limits[Double]): RandomT[Double] = new RandomT[Double] {
      import limits._
      val lower: Double = math.min(x, y)
      val upper: Double = math.max(x, y)

      override def randomTLog(n: Int): Double =
        RandomRanges.randomLog(lower, upper, n)

      override def randomT(): Double =
        randomBigDecimalBetween(BigDecimal(lower), BigDecimal(upper)).doubleValue()
    }
  }

  implicit object FloatGenerator extends Generator[Float] {
    def apply(limits: Limits[Float]): RandomT[Float] = new RandomT[Float] {
      import limits._
      val lower: Float = math.min(x, y)
      val upper: Float = math.max(x, y)

      override def randomTLog(n: Int): Float =
        RandomRanges.randomLog(lower, upper, n).toFloat

      override def randomT(): Float =
        randomBigDecimalBetween(BigDecimal(lower), BigDecimal(upper)).floatValue()
    }
  }

  implicit object IntGenerator extends Generator[Int] {
    def apply(limits: Limits[Int]): RandomT[Int] = new RandomT[Int] {
      import limits._
      val lower: Int = math.min(x, y)
      val upper: Int = math.max(x, y)

      override def randomTLog(n: Int): Int =
        RandomRanges.randomLog(lower, upper, n).toInt

      override def randomT(): Int =
        bigIntBetween(BigInt(lower), BigInt(upper)).intValue()
    }
  }

  implicit object LongGenerator extends Generator[Long] {
    def apply(limits: Limits[Long]): RandomT[Long] = new RandomT[Long] {
      import limits._
      val lower: Long = math.min(x, y)
      val upper: Long = math.max(x, y)

      override def randomTLog(n: Int): Long =
        RandomRanges.randomLog(lower, upper, n).toLong

      override def randomT(): Long =
        bigIntBetween(BigInt(lower), BigInt(upper)).longValue()
    }
  }

  def logN(x: Double, base: Int): Double = math.log(x) / math.log(base)

  def randomLog(lower: Double, upper: Double, n: Int): Double = {
    val logLower: Double = logN(lower, n)
    val logUpper: Double = logN(upper, n)
    val logLimits: Limits[Double] = Limits(logLower, logUpper)
    val rndLogged: RandomT[Double] = RandomRanges(logLimits)
    math.pow(n, rndLogged.randomT())
  }

  def apply[T: Generator](lim: Limits[T])(implicit t: Generator[T]): RandomT[T] = t(lim)

}

/**
 * "For any distribution over a sample space with a finite maximum, the maximum of 60 random
 * observations lies within the top 5% of the true maximum, with 95% probability"
 * - Evaluating Machine Learning Models by Alice Zheng
 * https://www.oreilly.com/library/view/evaluating-machine-learning/9781492048756/ch04.html
 *
 * Note: if you want more sophisticated hyperparameter tuning, consider Python libraries such as Hyperopt.
 */
@Since("3.1.0")
class ParamRandomBuilder extends ParamGridBuilder {
  @Since("3.1.0")
  def addRandom[T: Generator](param: Param[T], lim: Limits[T], n: Int): this.type = {
    val gen: RandomT[T] = RandomRanges(lim)
    addGrid(param, (1 to n).map { _: Int => gen.randomT() })
  }

}
