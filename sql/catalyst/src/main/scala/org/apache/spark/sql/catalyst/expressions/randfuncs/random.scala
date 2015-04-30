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

package org.apache.spark.sql.catalyst.expressions.randfuncs

import org.apache.spark.TaskContext
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.types.{DoubleType, DataType}
import org.apache.spark.util.random.XORShiftRandom

/**
 * A Random distribution generating expression.
 * TODO: This can be made generic to generate any type of random distribution, or any type of  
 * StructType.
 *
 * Since this expression is stateful, it cannot be a case object.
 */
private[sql] abstract class RDG(seed: Long) extends LeafExpression with Serializable { 
  self: Product =>

  /**
   * Record ID within each partition. By being transient, the Random Number Generator is
   * reset every time we serialize and deserialize it.
   */
  @transient private[this] lazy val rng = new XORShiftRandom(seed + TaskContext.get().partitionId())

  override type EvaluatedType = Double

  override def nullable: Boolean = false

  override def dataType: DataType = DoubleType

  def generateNumber(random: XORShiftRandom): Double

  override def eval(input: Row): Double = {
    generateNumber(rng)
  }
}

/** Generate a random column with i.i.d. uniformly distributed values in [0, 1). */
private[sql] case class Rand(seed: Long) extends RDG(seed) {
  override def generateNumber(random: XORShiftRandom): Double = random.nextDouble()
}

/** Generate a random column with i.i.d. gaussian random distribution. */
private[sql] case class Randn(seed: Long) extends RDG(seed) {
  override def generateNumber(random: XORShiftRandom): Double = random.nextGaussian()
}
