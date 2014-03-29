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
package org.apache.spark.mllib.rdd

import breeze.linalg.{Vector => BV, *}

import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.mllib.util.MLUtils._
import org.apache.spark.rdd.RDD

/**
 * Extra functions available on RDDs of [[org.apache.spark.mllib.linalg.Vector Vector]] through an implicit conversion.
 * Import `org.apache.spark.MLContext._` at the top of your program to use these functions.
 */
class VectorRDDFunctions(self: RDD[Vector]) extends Serializable {

  def rowMeans(): RDD[Double] = {
    self.map(x => x.toArray.sum / x.size)
  }

  def rowNorm2(): RDD[Double] = {
    self.map(x => math.sqrt(x.toArray.map(x => x*x).sum))
  }

  def rowSDs(): RDD[Double] = {
    val means = self.rowMeans()
    self.zip(means)
      .map{ case(x, m) => x.toBreeze - m }
      .map{ x => math.sqrt(x.toArray.map(x => x*x).sum / x.size) }
  }

  def colMeans(): Vector = colMeans(self.take(1).head.size)

  def colMeans(size: Int): Vector = {
    Vectors.fromBreeze(self.map(_.toBreeze).aggregate((BV.zeros[Double](size), 0.0))(
      seqOp = (c, v) => (c, v) match {
        case ((prev, cnt), current) =>
          (((prev :* cnt) + current) :/ (cnt + 1.0), cnt + 1.0)
      },
      combOp = (lhs, rhs) => (lhs, rhs) match {
        case ((lhsVec, lhsCnt), (rhsVec, rhsCnt)) =>
          ((lhsVec :* lhsCnt) + (rhsVec :* rhsCnt) :/ (lhsCnt + rhsCnt), lhsCnt + rhsCnt)
      }
    )._1)
  }

  def colNorm2(): Vector = colNorm2(self.take(1).head.size)

  def colNorm2(size: Int): Vector = Vectors.fromBreeze(self.map(_.toBreeze).aggregate(BV.zeros[Double](size))(
    seqOp = (c, v) => c + (v :* v),
    combOp = (lhs, rhs) => lhs + rhs
  ).map(math.sqrt))

  def colSDs(): Vector = colSDs(self.take(1).head.size)

  def colSDs(size: Int): Vector = {
    val means = self.colMeans()
    Vectors.fromBreeze(self.map(x => x.toBreeze - means.toBreeze).aggregate((BV.zeros[Double](size), 0.0))(
      seqOp = (c, v) => (c, v) match {
        case ((prev, cnt), current) =>
          (((prev :* cnt) + (current :* current)) :/ (cnt + 1.0), cnt + 1.0)
      },
      combOp = (lhs, rhs) => (lhs, rhs) match {
        case ((lhsVec, lhsCnt), (rhsVec, rhsCnt)) =>
          ((lhsVec :* lhsCnt) + (rhsVec :* rhsCnt) :/ (lhsCnt + rhsCnt), lhsCnt + rhsCnt)
      }
    )._1.map(math.sqrt))
  }

  private def maxMinOption(cmp: (Vector, Vector) => Boolean): Option[Vector] = {
    def cmpMaxMin(x1: Vector, x2: Vector) = if (cmp(x1, x2)) x1 else x2
    self.mapPartitions { iterator =>
      Seq(iterator.reduceOption(cmpMaxMin)).iterator
    }.collect { case Some(x) => x }.collect().reduceOption(cmpMaxMin)
  }

  def maxOption(cmp: (Vector, Vector) => Boolean) = maxMinOption(cmp)

  def minOption(cmp: (Vector, Vector) => Boolean) = maxMinOption(!cmp(_, _))

  def rowShrink(): RDD[Vector] = self.filter(x => x.toArray.sum != 0)

  def colShrink(): RDD[Vector] = {
    val means = self.colMeans()
    self.map( v => Vectors.dense(v.toArray.zip(means.toArray).filter{ case (x, m) => m != 0.0 }.map(_._1)))
  }
}
