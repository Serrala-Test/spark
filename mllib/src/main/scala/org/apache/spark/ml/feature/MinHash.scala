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

package org.apache.spark.ml.feature

import scala.util.Random

import org.apache.spark.annotation.{Experimental, Since}
import org.apache.spark.ml.linalg.{Vector, Vectors}
import org.apache.spark.ml.util.Identifiable

/**
 * Model produced by [[MinHash]]
 */
@Experimental
@Since("2.1.0")
private[ml] class MinHashModel(override val uid: String, hashFunctions: Seq[Int => Long])
  extends LSHModel[MinHashModel] {

  @Since("2.1.0")
  override protected[this] val hashFunction: Vector => Vector = {
    elems: Vector =>
      require(elems.numNonzeros > 0, "Must have at least 1 non zero entry.")
      Vectors.dense(hashFunctions.map(
        func => elems.toSparse.indices.toList.map(func).min.toDouble
      ).toArray)
  }

  @Since("2.1.0")
  override protected[ml] def keyDistance(x: Vector, y: Vector): Double = {
    val xSet = x.toSparse.indices.toSet
    val ySet = y.toSparse.indices.toSet
    1 - xSet.intersect(ySet).size.toDouble / xSet.union(ySet).size.toDouble
  }

  @Since("2.1.0")
  override protected[ml] def hashDistance(x: Vector, y: Vector): Double = {
    // Since it's generated by hashing, it will be a pair of dense vectors.
    x.toDense.values.zip(y.toDense.values).map(x => math.abs(x._1 - x._2)).min
  }
}

/**
 * LSH class for Jaccard distance
 * The input set should be represented in sparse vector form. For example,
 *    Vectors.sparse(10, Array[(2, 1.0), (3, 1.0), (5, 1.0)])
 * means there are 10 elements in the space. This set contains elem 2, elem 3 and elem 5
 * @param uid
 */
@Experimental
@Since("2.1.0")
private[ml] class MinHash(override val uid: String) extends LSH[MinHashModel] {

  private[this] val prime = 2038074743

  @Since("2.1.0")
  override def setInputCol(value: String): this.type = super.setInputCol(value)

  @Since("2.1.0")
  override def setOutputCol(value: String): this.type = super.setOutputCol(value)

  @Since("2.1.0")
  override def setOutputDim(value: Int): this.type = super.setOutputDim(value)

  private[this] lazy val randSeq: Seq[Int] = {
    Seq.fill($(outputDim))(1 + Random.nextInt(prime - 1)).take($(outputDim))
  }

  @Since("2.1.0")
  private[ml] def this() = {
    this(Identifiable.randomUID("min hash"))
  }

  @Since("2.1.0")
  override protected[this] def createRawLSHModel(inputDim: Int): MinHashModel = {
    val numEntry = inputDim * 2
    assert(numEntry < prime, "The input vector dimension is too large for MinHash to handle.")
    val hashFunctions: Seq[Int => Long] = {
      (0 until $(outputDim)).map { i: Int =>
        // Perfect Hash function, use 2n buckets to reduce collision.
        elem: Int => (1 + elem) * randSeq(i).toLong % prime % numEntry
      }
    }
    new MinHashModel(uid, hashFunctions)
  }
}
