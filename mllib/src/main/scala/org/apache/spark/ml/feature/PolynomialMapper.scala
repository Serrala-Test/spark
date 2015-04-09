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

import org.apache.spark.annotation.AlphaComponent
import org.apache.spark.ml.UnaryTransformer
import org.apache.spark.ml.param.{IntParam, ParamMap}
import org.apache.spark.mllib.linalg._
import org.apache.spark.sql.types.DataType

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

/**
 * :: AlphaComponent ::
 * Polynomially expand a vector into a larger one.
 */
@AlphaComponent
class PolynomialMapper extends UnaryTransformer[Vector, Vector, PolynomialMapper] {

  /**
   * The polynomial degree to expand, which should be larger than 1.
   * @group param
   */
  val degree = new IntParam(this, "degree", "the polynomial degree to expand", Some(2))

  /** @group getParam */
  def getDegree: Int = get(degree)

  /** @group setParam */
  def setDegree(value: Int): this.type = set(degree, value)

  override protected def createTransformFunc(paramMap: ParamMap): Vector => Vector = {
    PolynomialMapper.transform(getDegree)
  }

  override protected def outputDataType: DataType = new VectorUDT()
}

object PolynomialMapper {
  /**
   * The number that combines k items from N items without repeat, i.e. the binomial coefficient.
   */
  private def binomialCoefficient(N: Int, k: Int): Int = {
    (N - k + 1 to N).product / (1 to k).product
  }

  /**
   * The number of monomials of a `numVariables` vector after expanding at a specific polynomial
   * degree `degree`.
   */
  private def numMonomials(degree: Int, numVariables: Int): Int = {
    binomialCoefficient(numVariables + degree - 1, degree)
  }

  /**
   * The number of monomials of a `numVariables` vector after expanding from polynomial degree 1 to
   * polynomial degree `degree`.
   */
  private def numExpandedDims(degree: Int, numVariables: Int): Int = {
    binomialCoefficient(numVariables + degree, numVariables) - 1
  }

  @tailrec
  private def fillDenseVector(
        outputVector: Array[Double],
        prevExpandedVecFrom: Int,
        prevExpandedVecLen: Int,
        currDegree: Int,
        finalDegree: Int,
        nDim: Int): Unit = {
    if (currDegree > finalDegree) {
      return None
    }
    val currExpandedVecFrom = prevExpandedVecFrom + prevExpandedVecLen
    var currIndex = currExpandedVecFrom
    val currExpandedVecLen = numMonomials(currDegree, nDim)
    var leftIndex = 0
    while (leftIndex < nDim) {
      val numToKeep = numMonomials(currDegree - 1, nDim - leftIndex)
      val prevVecStartIndex = prevExpandedVecFrom + prevExpandedVecLen - numToKeep
      var rightIndex = 0
      while (rightIndex < numToKeep) {
        outputVector(currIndex) =
          outputVector(leftIndex) * outputVector(prevVecStartIndex + rightIndex)
        currIndex += 1
        rightIndex += 1
      }
      leftIndex += 1
    }

    fillDenseVector(outputVector, currExpandedVecFrom, currExpandedVecLen, currDegree + 1,
      finalDegree, nDim)
  }

  @tailrec
  private def fillSparseVector(
        outputIndices: ArrayBuffer[Int],
        outputValues: ArrayBuffer[Double],
        originalSparseVecLen: Int,
        prevExpandedSparseVecFrom: Int,
        prevExpandedSparseVecLen: Int,
        currDegree: Int,
        finalDegree: Int,
        nDim: Int): Unit = {
    if (currDegree > finalDegree) {
      return None
    }

    println(outputIndices.toArray.mkString(", "))
    println(outputValues.toArray.mkString(", "))
    println(originalSparseVecLen)
    println(prevExpandedSparseVecFrom)
    println(prevExpandedSparseVecLen)
    println(currDegree)
    println(finalDegree)
    println(nDim)

    val currExpandedSparseVecFrom = prevExpandedSparseVecFrom + prevExpandedSparseVecLen
    var currExpandedSparseVecLen = 0
    val prevExpandedVecFrom = numExpandedDims(currDegree - 2, nDim)
    val prevExpandedVecLen = numMonomials(currDegree - 1, nDim)
    println(prevExpandedVecFrom)
    println(prevExpandedVecLen)
    val currExpandedVecFrom = prevExpandedVecFrom + prevExpandedVecLen
    var leftIndex = 0
    var numToKeepCum = 0
    while (leftIndex < originalSparseVecLen) {
      val numToKeep = numMonomials(currDegree - 1, nDim - outputIndices(leftIndex))
      var rightIndex = 0
      while (rightIndex < prevExpandedSparseVecLen) {
        val realIndex =
          outputIndices(prevExpandedSparseVecFrom + rightIndex) - (prevExpandedVecLen - numToKeep)
        println(s"real index in $currDegree degree is $realIndex")
        if (realIndex >= prevExpandedVecFrom) {
          outputIndices += currExpandedVecFrom + numToKeepCum + realIndex
          outputValues += outputValues(leftIndex) * outputValues(rightIndex)
          currExpandedSparseVecLen += 1
        } else {
          // pass through if the index is invalid
        }
        numToKeepCum += numToKeep
        rightIndex += 1
      }
      leftIndex += 1
    }

    fillSparseVector(outputIndices, outputValues, originalSparseVecLen, currExpandedSparseVecFrom,
      currExpandedSparseVecLen, currDegree + 1, finalDegree, nDim)
  }

  /**
   * Multiply two polynomials, the first is the original vector, i.e. the expanded vector with
   * degree 1, while the second is the expanded vector with degree `currDegree - 1`. A new expanded
   * vector with degree `currDegree` will be generated after the function call.
   *
   * @param lhs original vector with degree 1
   * @param rhs expanded vector with degree `currDegree - 1`
   * @param nDim the dimension of original vector
   * @param currDegree the polynomial degree that need to be achieved
   */
  private def expandVector(lhs: Vector, rhs: Vector, nDim: Int, currDegree: Int): Vector = {
    (lhs, rhs) match {
      case (l: DenseVector, r: DenseVector) =>
        val rLen = rhs.size
        val allExpansions = l.toArray.zipWithIndex.flatMap { case (lVal, lIdx) =>
          val numToKeep = numMonomials(currDegree - 1, nDim - lIdx)
          r.toArray.slice(rLen - numToKeep, rLen).map(rVal => lVal * rVal)
        }
        Vectors.dense(allExpansions)

      case (SparseVector(lLen, lIdx, lVal), SparseVector(rLen, rIdx, rVal)) =>
        val len = numMonomials(currDegree, nDim)
        var numToKeepCum = 0
        val allExpansions = lVal.zip(lIdx).flatMap { case (lv, li) =>
          val numToKeep = numMonomials(currDegree - 1, nDim - li)
          val currExpansions = rVal.zip(rIdx).map { case (rv, ri) =>
            val realIdx = ri - (rLen - numToKeep)
            (if (realIdx >= 0) lv * rv else 0.0, numToKeepCum + realIdx)
          }
          numToKeepCum += numToKeep
          currExpansions
        }.filter(_._1 != 0.0)
        Vectors.sparse(len, allExpansions.map(_._2), allExpansions.map(_._1))

      case _ => throw new Exception("vector types are not match.")
    }
  }

  private def transform2(degree: Int)(feature: Vector): Vector = {
    val originalDims = feature.size
    val expectedDims = numExpandedDims(degree, feature.size)
    feature match {
      case f: DenseVector =>
        (2 to degree).foldLeft(Array(feature.copy)) { (vectors, currDegree) =>
          vectors ++ Array(expandVector(feature, vectors.last, originalDims, currDegree))
          }.reduce((lhs, rhs) => Vectors.dense(lhs.toArray ++ rhs.toArray))
      case f: SparseVector =>
        (2 to degree).foldLeft(Array(feature.copy)) { (vectors, currDegree) =>
          vectors ++ Array(expandVector(feature, vectors.last, originalDims, currDegree))
        }.reduce { (lhs, rhs) =>
          (lhs, rhs) match {
            case (SparseVector(lLen, lIdx, lVal), SparseVector(rLen, rIdx, rVal)) =>
              Vectors.sparse(lLen + rLen, lIdx ++ rIdx.map(_ + lLen), lVal ++ rVal)
          }
        }
      case _ => throw new Exception("vector type is invalid.")
    }
  }

  /**
   * Transform a vector of variables into a larger vector which stores the polynomial expansion from
   * degree 1 to degree `degree`.
   */
  private def transform(degree: Int)(feature: Vector): Vector = {
    val originalDims = feature.size
    val expectedDims = numExpandedDims(degree, feature.size)
    feature match {
      case f: DenseVector =>
        val res = Array.fill[Double](expectedDims)(0.0)
        for (i <- 0 until f.size) {
          res(i) = f(i)
        }
        fillDenseVector(res, 0, originalDims, 2, degree, originalDims)
        Vectors.dense(res)

      case f: SparseVector =>
        val resIndices = new ArrayBuffer[Int]()
        val resValues = new ArrayBuffer[Double]()
        for (i <- 0 until f.indices.size) {
          resIndices += f.indices(i)
          resValues += f.values(i)
        }
        fillSparseVector(resIndices, resValues, f.indices.size, 0, f.indices.size, 2, degree,
          originalDims)
        Vectors.sparse(expectedDims, resIndices.toArray, resValues.toArray)
    }
  }
}
