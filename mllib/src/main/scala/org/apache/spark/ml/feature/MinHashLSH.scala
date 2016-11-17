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

import org.apache.hadoop.fs.Path

import org.apache.spark.annotation.{Experimental, Since}
import org.apache.spark.ml.linalg.{Vector, Vectors, VectorUDT}
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.ml.param.shared.HasSeed
import org.apache.spark.ml.util._
import org.apache.spark.sql.types.StructType

/**
 * :: Experimental ::
 *
 * Model produced by [[MinHashLSH]], where multiple hash functions are stored. Each hash function is
 * picked from a hash function for a specific set `S` with cardinality equal to `numEntries`:
 *    `h_i(x) = ((x \cdot a_i + b_i) \mod prime) \mod numEntries`
 *
 * @param numEntries The number of entries of the hash functions.
 * @param randCoefficients Pairs of random coefficients. Each pair is used by one hash function.
 */
@Experimental
@Since("2.1.0")
class MinHashLSHModel private[ml](
    override val uid: String,
    private[ml] val numEntries: Int,
    private[ml] val randCoefficients: Array[(Int, Int)])
  extends LSHModel[MinHashLSHModel] {

  @Since("2.1.0")
  override protected[ml] val hashFunction: Vector => Array[Vector] = {
    elems: Vector => {
      require(elems.numNonzeros > 0, "Must have at least 1 non zero entry.")
      val elemsList = elems.toSparse.indices.toList
      val hashValues = randCoefficients.map({ case (a: Int, b: Int) =>
        elemsList.map { elem: Int =>
          ((1 + elem) * a + b) % MinHashLSH.HASH_PRIME % numEntries
        }.min.toDouble
      })
      // TODO: Output vectors of dimension numHashFunctions in SPARK-18450
      hashValues.grouped(1).map(Vectors.dense).toArray
    }
  }

  @Since("2.1.0")
  override protected[ml] def keyDistance(x: Vector, y: Vector): Double = {
    val xSet = x.toSparse.indices.toSet
    val ySet = y.toSparse.indices.toSet
    val intersectionSize = xSet.intersect(ySet).size.toDouble
    val unionSize = xSet.size + ySet.size - intersectionSize
    assert(unionSize > 0, "The union of two input sets must have at least 1 elements")
    1 - intersectionSize / unionSize
  }

  @Since("2.1.0")
  override protected[ml] def hashDistance(x: Seq[Vector], y: Seq[Vector]): Double = {
    // Since it's generated by hashing, it will be a pair of dense vectors.
    // TODO: This hashDistance function requires more discussion in SPARK-18454
    x.zip(y).map(vectorPair =>
      vectorPair._1.toArray.zip(vectorPair._2.toArray).count(pair => pair._1 != pair._2)
    ).min
  }

  @Since("2.1.0")
  override def copy(extra: ParamMap): this.type = defaultCopy(extra)

  @Since("2.1.0")
  override def write: MLWriter = new MinHashLSHModel.MinHashLSHModelWriter(this)
}

/**
 * :: Experimental ::
 *
 * LSH class for Jaccard distance.
 *
 * The input can be dense or sparse vectors, but it is more efficient if it is sparse. For example,
 *    `Vectors.sparse(10, Array((2, 1.0), (3, 1.0), (5, 1.0)))`
 * means there are 10 elements in the space. This set contains elements 2, 3, and 5. Also, any
 * input vector must have at least 1 non-zero index, and all non-zero values are
 * treated as binary "1" values.
 *
 * References:
 * [[https://en.wikipedia.org/wiki/MinHash Wikipedia on MinHash]]
 */
@Experimental
@Since("2.1.0")
class MinHashLSH(override val uid: String) extends LSH[MinHashLSHModel] with HasSeed {

  @Since("2.1.0")
  override def setInputCol(value: String): this.type = super.setInputCol(value)

  @Since("2.1.0")
  override def setOutputCol(value: String): this.type = super.setOutputCol(value)

  @Since("2.1.0")
  override def setNumHashTables(value: Int): this.type = super.setNumHashTables(value)

  @Since("2.1.0")
  def this() = {
    this(Identifiable.randomUID("mh-lsh"))
  }

  /** @group setParam */
  @Since("2.1.0")
  def setSeed(value: Long): this.type = set(seed, value)

  @Since("2.1.0")
  override protected[ml] def createRawLSHModel(inputDim: Int): MinHashLSHModel = {
    require(inputDim <= MinHashLSH.HASH_PRIME,
      s"The input vector dimension $inputDim exceeds the threshold ${MinHashLSH.HASH_PRIME}.")
    val rand = new Random($(seed))
    val numEntry = inputDim
    val randCoefs: Array[(Int, Int)] = Array.fill(2 * $(numHashTables)) {
        (1 + rand.nextInt(MinHashLSH.HASH_PRIME - 1), rand.nextInt(MinHashLSH.HASH_PRIME - 1))
      }
    new MinHashLSHModel(uid, numEntry, randCoefs)
  }

  @Since("2.1.0")
  override def transformSchema(schema: StructType): StructType = {
    SchemaUtils.checkColumnType(schema, $(inputCol), new VectorUDT)
    validateAndTransformSchema(schema)
  }

  @Since("2.1.0")
  override def copy(extra: ParamMap): this.type = defaultCopy(extra)
}

@Since("2.1.0")
object MinHashLSH extends DefaultParamsReadable[MinHashLSH] {
  // A large prime smaller than sqrt(2^63 − 1)
  private[ml] val HASH_PRIME = 2038074743

  @Since("2.1.0")
  override def load(path: String): MinHashLSH = super.load(path)
}

@Since("2.1.0")
object MinHashLSHModel extends MLReadable[MinHashLSHModel] {

  @Since("2.1.0")
  override def read: MLReader[MinHashLSHModel] = new MinHashLSHModelReader

  @Since("2.1.0")
  override def load(path: String): MinHashLSHModel = super.load(path)

  private[MinHashLSHModel] class MinHashLSHModelWriter(instance: MinHashLSHModel)
    extends MLWriter {

    private case class Data(numEntries: Int, randCoefficients: Array[Int])

    override protected def saveImpl(path: String): Unit = {
      DefaultParamsWriter.saveMetadata(instance, path, sc)
      val data = Data(instance.numEntries, instance.randCoefficients
        .flatMap(tuple => Array(tuple._1, tuple._2)))
      val dataPath = new Path(path, "data").toString
      sparkSession.createDataFrame(Seq(data)).repartition(1).write.parquet(dataPath)
    }
  }

  private class MinHashLSHModelReader extends MLReader[MinHashLSHModel] {

    /** Checked against metadata when loading model */
    private val className = classOf[MinHashLSHModel].getName

    override def load(path: String): MinHashLSHModel = {
      val metadata = DefaultParamsReader.loadMetadata(path, sc, className)

      val dataPath = new Path(path, "data").toString
      val data = sparkSession.read.parquet(dataPath).select("numEntries", "randCoefficients").head()
      val numEntries = data.getAs[Int](0)
      val randCoefficients = data.getAs[Seq[Int]](1).grouped(2)
        .map(tuple => (tuple(0), tuple(1))).toArray
      val model = new MinHashLSHModel(metadata.uid, numEntries, randCoefficients)

      DefaultParamsReader.getAndSetParams(model, metadata)
      model
    }
  }
}
