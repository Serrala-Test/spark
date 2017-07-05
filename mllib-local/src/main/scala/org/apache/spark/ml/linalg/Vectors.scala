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

package org.apache.spark.ml.linalg

import java.lang.{Double => JavaDouble, Integer => JavaInteger, Iterable => JavaIterable}
import java.util

import scala.annotation.varargs
import scala.collection.JavaConverters._

import breeze.linalg.{DenseVector => BDV, SparseVector => BSV, Vector => BV}

import org.apache.spark.annotation.Since

/**
 * Represents a numeric vector, whose index type is Int and value type is Double.
 *
 * @note Users should not implement this interface.
 */
@Since("2.0.0")
sealed trait Vector extends Serializable {

  /**
   * Size of the vector.
   */
  @Since("2.0.0")
  def size: Int

  /**
   * Converts the instance to a double array.
   */
  @Since("2.0.0")
  def toArray: Array[Double]

  override def equals(other: Any): Boolean = {
    other match {
      case v2: Vector =>
        if (this.size != v2.size) return false
        (this, v2) match {
          case (s1: SparseVector, s2: SparseVector) =>
            Vectors.equals(s1.indices, s1.values, s2.indices, s2.values)
          case (s1: SparseVector, d1: DenseVector) =>
            Vectors.equals(s1.indices, s1.values, 0 until d1.size, d1.values)
          case (d1: DenseVector, s1: SparseVector) =>
            Vectors.equals(0 until d1.size, d1.values, s1.indices, s1.values)
          case (_, _) => util.Arrays.equals(this.toArray, v2.toArray)
        }
      case _ => false
    }
  }

  /**
   * Returns a hash code value for the vector. The hash code is based on its size and its first 128
   * nonzero entries, using a hash algorithm similar to `java.util.Arrays.hashCode`.
   */
  override def hashCode(): Int = {
    // This is a reference implementation. It calls return in foreachActive, which is slow.
    // Subclasses should override it with optimized implementation.
    var result: Int = 31 + size
    var nnz = 0
    this.foreachActive { (index, value) =>
      if (nnz < Vectors.MAX_HASH_NNZ) {
        // ignore explicit 0 for comparison between sparse and dense
        if (value != 0) {
          result = 31 * result + index
          val bits = java.lang.Double.doubleToLongBits(value)
          result = 31 * result + (bits ^ (bits >>> 32)).toInt
          nnz += 1
        }
      } else {
        return result
      }
    }
    result
  }

  /**
   * Converts the instance to a breeze vector.
   */
  private[spark] def asBreeze: BV[Double]

  /**
   * Gets the value of the ith element.
   * @param i index
   */
  @Since("2.0.0")
  def apply(i: Int): Double = asBreeze(i)

  /**
   * Makes a deep copy of this vector.
   */
  @Since("2.0.0")
  def copy: Vector = {
    throw new NotImplementedError(s"copy is not implemented for ${this.getClass}.")
  }

  /**
   * Applies a function `f` to all the active elements of dense and sparse vector.
   *
   * @param f the function takes two parameters where the first parameter is the index of
   *          the vector with type `Int`, and the second parameter is the corresponding value
   *          with type `Double`.
   */
  @Since("2.0.0")
  def foreachActive(f: (Int, Double) => Unit): Unit

  /**
   * Number of active entries.  An "active entry" is an element which is explicitly stored,
   * regardless of its value.  Note that inactive entries have value 0.
   */
  @Since("2.0.0")
  def numActives: Int

  /**
   * Number of nonzero elements. This scans all active values and count nonzeros.
   */
  @Since("2.0.0")
  def numNonzeros: Int

  /**
   * Converts this vector to a sparse vector with all explicit zeros removed.
   */
  @Since("2.0.0")
  def toSparse: SparseVector

  /**
   * Converts this vector to a dense vector.
   */
  @Since("2.0.0")
  def toDense: DenseVector = new DenseVector(this.toArray)

  /**
   * Returns a vector in either dense or sparse format, whichever uses less storage.
   */
  @Since("2.0.0")
  def compressed: Vector = {
    val nnz = numNonzeros
    // A dense vector needs 8 * size + 8 bytes, while a sparse vector needs 12 * nnz + 20 bytes.
    if (1.5 * (nnz + 1.0) < size) {
      toSparse
    } else {
      toDense
    }
  }

  /**
   * Find the index of a maximal element.  Returns the first maximal element in case of a tie.
   * Returns -1 if vector has length 0.
   */
  @Since("2.0.0")
  def argmax: Int
}

/**
 * Factory methods for [[org.apache.spark.ml.linalg.Vector]].
 * We don't use the name `Vector` because Scala imports
 * `scala.collection.immutable.Vector` by default.
 */
@Since("2.0.0")
object Vectors {

  /**
   * Creates a dense vector from its values.
   */
  @varargs
  @Since("2.0.0")
  def dense(firstValue: Double, otherValues: Double*): Vector =
    new DenseVector((firstValue +: otherValues).toArray)

  // A dummy implicit is used to avoid signature collision with the one generated by @varargs.
  /**
   * Creates a dense vector from a double array.
   */
  @Since("2.0.0")
  def dense(values: Array[Double]): Vector = new DenseVector(values)

  /**
   * Creates a sparse vector providing its index array and value array.
   *
   * @param size vector size.
   * @param indices index array, must be strictly increasing.
   * @param values value array, must have the same length as indices.
   */
  @Since("2.0.0")
  def sparse(size: Int, indices: Array[Int], values: Array[Double]): Vector =
    new SparseVector(size, indices, values)

  /**
   * Creates a sparse vector using unordered (index, value) pairs.
   *
   * @param size vector size.
   * @param elements vector elements in (index, value) pairs.
   */
  @Since("2.0.0")
  def sparse(size: Int, elements: Seq[(Int, Double)]): Vector = {
    val (indices, values) = elements.sortBy(_._1).unzip
    new SparseVector(size, indices.toArray, values.toArray)
  }

  /**
   * Creates a sparse vector using unordered (index, value) pairs in a Java friendly way.
   *
   * @param size vector size.
   * @param elements vector elements in (index, value) pairs.
   */
  @Since("2.0.0")
  def sparse(size: Int, elements: JavaIterable[(JavaInteger, JavaDouble)]): Vector = {
    sparse(size, elements.asScala.map { case (i, x) =>
      (i.intValue(), x.doubleValue())
    }.toSeq)
  }

  /**
   * Creates a vector of all zeros.
   *
   * @param size vector size
   * @return a zero vector
   */
  @Since("2.0.0")
  def zeros(size: Int): Vector = {
    new DenseVector(new Array[Double](size))
  }

  /**
   * Creates a vector instance from a breeze vector.
   */
  private[spark] def fromBreeze(breezeVector: BV[Double]): Vector = {
    breezeVector match {
      case v: BDV[Double] =>
        if (v.offset == 0 && v.stride == 1 && v.length == v.data.length) {
          new DenseVector(v.data)
        } else {
          new DenseVector(v.toArray)  // Can't use underlying array directly, so make a new one
        }
      case v: BSV[Double] =>
        if (v.index.length == v.used) {
          new SparseVector(v.length, v.index, v.data)
        } else {
          new SparseVector(v.length, v.index.slice(0, v.used), v.data.slice(0, v.used))
        }
      case v: BV[_] =>
        sys.error("Unsupported Breeze vector type: " + v.getClass.getName)
    }
  }

  /**
   * Returns the p-norm of this vector.
   * @param vector input vector.
   * @param p norm.
   * @return norm in L^p^ space.
   */
  @Since("2.0.0")
  def norm(vector: Vector, p: Double): Double = {
    require(p >= 1.0, "To compute the p-norm of the vector, we require that you specify a p>=1. " +
      s"You specified p=$p.")
    val values = vector match {
      case DenseVector(vs) => vs
      case SparseVector(n, ids, vs) => vs
      case v => throw new IllegalArgumentException("Do not support vector type " + v.getClass)
    }
    val size = values.length

    if (p == 1) {
      var sum = 0.0
      var i = 0
      while (i < size) {
        sum += math.abs(values(i))
        i += 1
      }
      sum
    } else if (p == 2) {
      var sum = 0.0
      var i = 0
      while (i < size) {
        sum += values(i) * values(i)
        i += 1
      }
      math.sqrt(sum)
    } else if (p == Double.PositiveInfinity) {
      var max = 0.0
      var i = 0
      while (i < size) {
        val value = math.abs(values(i))
        if (value > max) max = value
        i += 1
      }
      max
    } else {
      var sum = 0.0
      var i = 0
      while (i < size) {
        sum += math.pow(math.abs(values(i)), p)
        i += 1
      }
      math.pow(sum, 1.0 / p)
    }
  }

  /**
   * Returns the squared distance between two Vectors.
   * @param v1 first Vector.
   * @param v2 second Vector.
   * @return squared distance between two Vectors.
   */
  @Since("2.0.0")
  def sqdist(v1: Vector, v2: Vector): Double = {
    require(v1.size == v2.size, s"Vector dimensions do not match: Dim(v1)=${v1.size} and Dim(v2)" +
      s"=${v2.size}.")
    var squaredDistance = 0.0
    (v1, v2) match {
      case (v1: SparseVector, v2: SparseVector) =>
        val v1Values = v1.values
        val v1Indices = v1.indices
        val v2Values = v2.values
        val v2Indices = v2.indices
        val nnzv1 = v1Indices.length
        val nnzv2 = v2Indices.length

        var kv1 = 0
        var kv2 = 0
        while (kv1 < nnzv1 || kv2 < nnzv2) {
          var score = 0.0

          if (kv2 >= nnzv2 || (kv1 < nnzv1 && v1Indices(kv1) < v2Indices(kv2))) {
            score = v1Values(kv1)
            kv1 += 1
          } else if (kv1 >= nnzv1 || (kv2 < nnzv2 && v2Indices(kv2) < v1Indices(kv1))) {
            score = v2Values(kv2)
            kv2 += 1
          } else {
            score = v1Values(kv1) - v2Values(kv2)
            kv1 += 1
            kv2 += 1
          }
          squaredDistance += score * score
        }

      case (v1: SparseVector, v2: DenseVector) =>
        squaredDistance = sqdist(v1, v2)

      case (v1: DenseVector, v2: SparseVector) =>
        squaredDistance = sqdist(v2, v1)

      case (DenseVector(vv1), DenseVector(vv2)) =>
        var kv = 0
        val sz = vv1.length
        while (kv < sz) {
          val score = vv1(kv) - vv2(kv)
          squaredDistance += score * score
          kv += 1
        }
      case _ =>
        throw new IllegalArgumentException("Do not support vector type " + v1.getClass +
          " and " + v2.getClass)
    }
    squaredDistance
  }

  /**
   * Returns the squared distance between DenseVector and SparseVector.
   */
  private[ml] def sqdist(v1: SparseVector, v2: DenseVector): Double = {
    var kv1 = 0
    var kv2 = 0
    val indices = v1.indices
    var squaredDistance = 0.0
    val nnzv1 = indices.length
    val nnzv2 = v2.size
    var iv1 = if (nnzv1 > 0) indices(kv1) else -1

    while (kv2 < nnzv2) {
      var score = 0.0
      if (kv2 != iv1) {
        score = v2(kv2)
      } else {
        score = v1.values(kv1) - v2(kv2)
        if (kv1 < nnzv1 - 1) {
          kv1 += 1
          iv1 = indices(kv1)
        }
      }
      squaredDistance += score * score
      kv2 += 1
    }
    squaredDistance
  }

  /**
   * Check equality between sparse/dense vectors
   */
  private[ml] def equals(
      v1Indices: IndexedSeq[Int],
      v1Values: Array[Double],
      v2Indices: IndexedSeq[Int],
      v2Values: Array[Double]): Boolean = {
    val v1Size = v1Values.length
    val v2Size = v2Values.length
    var k1 = 0
    var k2 = 0
    var allEqual = true
    while (allEqual) {
      while (k1 < v1Size && v1Values(k1) == 0) k1 += 1
      while (k2 < v2Size && v2Values(k2) == 0) k2 += 1

      if (k1 >= v1Size || k2 >= v2Size) {
        return k1 >= v1Size && k2 >= v2Size // check end alignment
      }
      allEqual = v1Indices(k1) == v2Indices(k2) && v1Values(k1) == v2Values(k2)
      k1 += 1
      k2 += 1
    }
    allEqual
  }

  /** Max number of nonzero entries used in computing hash code. */
  private[linalg] val MAX_HASH_NNZ = 128
}

/**
 * A dense vector represented by a value array.
 */
@Since("2.0.0")
class DenseVector @Since("2.0.0") ( @Since("2.0.0") val values: Array[Double]) extends Vector {

  override def size: Int = values.length

  override def toString: String = values.mkString("[", ",", "]")

  override def toArray: Array[Double] = values

  private[spark] override def asBreeze: BV[Double] = new BDV[Double](values)

  override def apply(i: Int): Double = values(i)

  override def copy: DenseVector = {
    new DenseVector(values.clone())
  }

  override def foreachActive(f: (Int, Double) => Unit): Unit = {
    var i = 0
    val localValuesSize = values.length
    val localValues = values

    while (i < localValuesSize) {
      f(i, localValues(i))
      i += 1
    }
  }

  override def equals(other: Any): Boolean = super.equals(other)

  override def hashCode(): Int = {
    var result: Int = 31 + size
    var i = 0
    val end = values.length
    var nnz = 0
    while (i < end && nnz < Vectors.MAX_HASH_NNZ) {
      val v = values(i)
      if (v != 0.0) {
        result = 31 * result + i
        val bits = java.lang.Double.doubleToLongBits(values(i))
        result = 31 * result + (bits ^ (bits >>> 32)).toInt
        nnz += 1
      }
      i += 1
    }
    result
  }

  override def numActives: Int = size

  override def numNonzeros: Int = {
    // same as values.count(_ != 0.0) but faster
    var nnz = 0
    values.foreach { v =>
      if (v != 0.0) {
        nnz += 1
      }
    }
    nnz
  }

  override def toSparse: SparseVector = {
    val nnz = numNonzeros
    val ii = new Array[Int](nnz)
    val vv = new Array[Double](nnz)
    var k = 0
    foreachActive { (i, v) =>
      if (v != 0) {
        ii(k) = i
        vv(k) = v
        k += 1
      }
    }
    new SparseVector(size, ii, vv)
  }

  override def argmax: Int = {
    if (size == 0) {
      -1
    } else {
      var maxIdx = 0
      var maxValue = values(0)
      var i = 1
      while (i < size) {
        if (values(i) > maxValue) {
          maxIdx = i
          maxValue = values(i)
        }
        i += 1
      }
      maxIdx
    }
  }
}

@Since("2.0.0")
object DenseVector {

  /** Extracts the value array from a dense vector. */
  @Since("2.0.0")
  def unapply(dv: DenseVector): Option[Array[Double]] = Some(dv.values)
}

/**
 * A sparse vector represented by an index array and a value array.
 *
 * @param size size of the vector.
 * @param indices index array, assume to be strictly increasing.
 * @param values value array, must have the same length as the index array.
 */
@Since("2.0.0")
class SparseVector @Since("2.0.0") (
    override val size: Int,
    @Since("2.0.0") val indices: Array[Int],
    @Since("2.0.0") val values: Array[Double]) extends Vector {

  // validate the data
  {
    require(size >= 0, "The size of the requested sparse vector must be greater than 0.")
    require(indices.length == values.length, "Sparse vectors require that the dimension of the" +
      s" indices match the dimension of the values. You provided ${indices.length} indices and " +
      s" ${values.length} values.")
    require(indices.length <= size, s"You provided ${indices.length} indices and values, " +
      s"which exceeds the specified vector size ${size}.")

    if (indices.nonEmpty) {
      require(indices(0) >= 0, s"Found negative index: ${indices(0)}.")
    }
    var prev = -1
    indices.foreach { i =>
      require(prev < i, s"Index $i follows $prev and is not strictly increasing")
      prev = i
    }
    require(prev < size, s"Index $prev out of bounds for vector of size $size")
  }

  override def toString: String =
    s"($size,${indices.mkString("[", ",", "]")},${values.mkString("[", ",", "]")})"

  override def toArray: Array[Double] = {
    val data = new Array[Double](size)
    var i = 0
    val nnz = indices.length
    while (i < nnz) {
      data(indices(i)) = values(i)
      i += 1
    }
    data
  }

  override def copy: SparseVector = {
    new SparseVector(size, indices.clone(), values.clone())
  }

  private[spark] override def asBreeze: BV[Double] = new BSV[Double](indices, values, size)

  override def foreachActive(f: (Int, Double) => Unit): Unit = {
    var i = 0
    val localValuesSize = values.length
    val localIndices = indices
    val localValues = values

    while (i < localValuesSize) {
      f(localIndices(i), localValues(i))
      i += 1
    }
  }

  override def equals(other: Any): Boolean = super.equals(other)

  override def hashCode(): Int = {
    var result: Int = 31 + size
    val end = values.length
    var k = 0
    var nnz = 0
    while (k < end && nnz < Vectors.MAX_HASH_NNZ) {
      val v = values(k)
      if (v != 0.0) {
        val i = indices(k)
        result = 31 * result + i
        val bits = java.lang.Double.doubleToLongBits(v)
        result = 31 * result + (bits ^ (bits >>> 32)).toInt
        nnz += 1
      }
      k += 1
    }
    result
  }

  override def numActives: Int = values.length

  override def numNonzeros: Int = {
    var nnz = 0
    values.foreach { v =>
      if (v != 0.0) {
        nnz += 1
      }
    }
    nnz
  }

  override def toSparse: SparseVector = {
    val nnz = numNonzeros
    if (nnz == numActives) {
      this
    } else {
      val ii = new Array[Int](nnz)
      val vv = new Array[Double](nnz)
      var k = 0
      foreachActive { (i, v) =>
        if (v != 0.0) {
          ii(k) = i
          vv(k) = v
          k += 1
        }
      }
      new SparseVector(size, ii, vv)
    }
  }

  override def argmax: Int = {
    if (size == 0) {
      -1
    } else if (numActives == 0) {
      0
    } else {
      // Find the max active entry.
      var maxIdx = indices(0)
      var maxValue = values(0)
      var maxJ = 0
      var j = 1
      val na = numActives
      while (j < na) {
        val v = values(j)
        if (v > maxValue) {
          maxValue = v
          maxIdx = indices(j)
          maxJ = j
        }
        j += 1
      }

      // If the max active entry is nonpositive and there exists inactive ones, find the first zero.
      if (maxValue <= 0.0 && na < size) {
        if (maxValue == 0.0) {
          // If there exists an inactive entry before maxIdx, find it and return its index.
          if (maxJ < maxIdx) {
            var k = 0
            while (k < maxJ && indices(k) == k) {
              k += 1
            }
            maxIdx = k
          }
        } else {
          // If the max active value is negative, find and return the first inactive index.
          var k = 0
          while (k < na && indices(k) == k) {
            k += 1
          }
          maxIdx = k
        }
      }

      maxIdx
    }
  }

  /**
   * Create a slice of this vector based on the given indices.
   * @param selectedIndices Unsorted list of indices into the vector.
   *                        This does NOT do bound checking.
   * @return  New SparseVector with values in the order specified by the given indices.
   *
   * NOTE: The API needs to be discussed before making this public.
   *       Also, if we have a version assuming indices are sorted, we should optimize it.
   */
  private[spark] def slice(selectedIndices: Array[Int]): SparseVector = {
    var currentIdx = 0
    val (sliceInds, sliceVals) = selectedIndices.flatMap { origIdx =>
      val iIdx = java.util.Arrays.binarySearch(this.indices, origIdx)
      val i_v = if (iIdx >= 0) {
        Iterator((currentIdx, this.values(iIdx)))
      } else {
        Iterator()
      }
      currentIdx += 1
      i_v
    }.unzip
    new SparseVector(selectedIndices.length, sliceInds.toArray, sliceVals.toArray)
  }
}

@Since("2.0.0")
object SparseVector {
  @Since("2.0.0")
  def unapply(sv: SparseVector): Option[(Int, Array[Int], Array[Double])] =
    Some((sv.size, sv.indices, sv.values))
}
