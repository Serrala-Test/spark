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

package org.apache.spark.ml.tree.impl

import org.roaringbitmap.RoaringBitmap

import org.apache.spark.internal.Logging
import org.apache.spark.ml.classification.DecisionTreeClassificationModel
import org.apache.spark.ml.regression.DecisionTreeRegressionModel
import org.apache.spark.ml.tree._
import org.apache.spark.mllib.linalg.Vector
import org.apache.spark.mllib.tree.configuration.{Algo => OldAlgo}
import org.apache.spark.mllib.tree.impurity._
import org.apache.spark.mllib.tree.model.Predict
import org.apache.spark.util.collection.BitSet

/**
 * Utility methods for local decision tree training.
 */
private[ml] object LocalDecisionTreeUtils extends Logging {

  /**
   * Returns a single-node impurity aggregator consisting of label statistics for the rows
   * coresponding to the feature values at indices [from, to) in the passed-in column
   * @param metadata Metadata object describing parameters of the learning algorithm.
   */
  private[impl] def getImpurity(
      col: FeatureVector,
      from: Int,
      to: Int,
      metadata: DecisionTreeMetadata,
      labels: Array[Double]): ImpurityAggregatorSingle = {
    val aggregator = metadata.createImpurityAggregator()
    from.until(to).foreach { idx =>
      val rowIndex = col.indices(idx)
      val label = labels(rowIndex)
      aggregator.update(label)
    }
    aggregator
  }

  /**
   * Given the root node of a decision tree, returns a corresponding DecisionTreeModel
   * @param algo Enum describing the algorithm used to fit the tree
   * @param numClasses Number of label classes (for classification trees)
   * @param parentUID UID of parent estimator
   */
  private[impl] def finalizeTree(
      rootNode: Node,
      algo: OldAlgo.Algo,
      numClasses: Int,
      numFeatures: Int,
      parentUID: Option[String]): DecisionTreeModel = {
    parentUID match {
      case Some(uid) =>
        if (algo == OldAlgo.Classification) {
          new DecisionTreeClassificationModel(uid, rootNode, numFeatures = numFeatures,
            numClasses = numClasses)
        } else {
          new DecisionTreeRegressionModel(uid, rootNode, numFeatures = numFeatures)
        }
      case None =>
        if (algo == OldAlgo.Classification) {
          new DecisionTreeClassificationModel(rootNode, numFeatures = numFeatures,
            numClasses = numClasses)
        } else {
          new DecisionTreeRegressionModel(rootNode, numFeatures = numFeatures)
        }
    }
  }

  /** Converts the passed-in compressed bitmap to a bitset of the specified size */
  private[impl] def toBitset(bitmap: RoaringBitmap, size: Int): BitSet = {
    val result = new BitSet(size)
    val iter = bitmap.getIntIterator
    while(iter.hasNext) {
      result.set(iter.next)
    }
    result
  }

  /**
   * For a given feature, for a given node, apply a split and return a bit vector indicating the
   * outcome of the split for each instance at that node.
   *
   * @param col  Column for feature
   * @param from  Start offset in col for the node
   * @param to  End offset in col for the node
   * @param split  Split to apply to instances at this node.
   * @return  Bits indicating splits for instances at this node.
   *          These bits are sorted by the row indices, in order to guarantee an ordering
   *          understood by all workers.
   *          Thus, the bit indices used are based on 2-level sorting: first by node, and
   *          second by sorted row indices within the node's rows.
   *          bit[index in sorted array of row indices] = false for left, true for right
   */
  private[impl] def bitVectorFromSplit(
      col: FeatureVector,
      from: Int,
      to: Int,
      split: Split,
      allSplits: Array[Array[Split]]): RoaringBitmap = {
    val bitv = new RoaringBitmap()
    from.until(to).foreach { i =>
      val idx = col.indices(i)
      if (!split.shouldGoLeft(col.values(i), allSplits(col.featureIndex))) {
        bitv.add(idx)
      }
    }
    bitv
  }

  /**
   * Compute bit vector (1 bit/instance) indicating whether each instance goes left/right.
   * - For each node that we split during this round, produce a bitmap (one bit per row
   *   in the training set)
   *   in which we set entries corresponding to rows for that node that were split left.
   * - Aggregate the partial bit vectors to create one vector (of length numRows).
   *   Correction: Aggregate only the pieces of that vector corresponding to instances at
   *   active nodes.
   *
   * @param partitionInfo  Contains feature data, plus current status metadata
   * @param numRows Number of rows
   * @return Array of bit vectors, ordered by offset ranges
   */
  private[impl] def computeBitVector(
      partitionInfo: PartitionInfo,
      numRows: Int,
      allSplits: Array[Array[Split]]): BitSet = {

    val bitmap = partitionInfo match {
      case PartitionInfo(oldCols: Array[FeatureVector], oldNodeOffsets: Array[(Int, Int)],
      oldActiveNodes: Array[LearningNode]) => {
        // Build up a bitmap identifying whether each row splits left or right
        oldActiveNodes.zipWithIndex.foldLeft(new RoaringBitmap()) {
          case (bitmap: RoaringBitmap, (node: LearningNode, nodeIdx: Int)) =>
            // Update bitmap for each node that was split
            if (node.split.isDefined) {
              val split = node.split.get
              val (fromOffset, toOffset) = oldNodeOffsets(nodeIdx)
              val bv: RoaringBitmap = bitVectorFromSplit(oldCols(split.featureIndex), fromOffset,
                toOffset, split, allSplits)
              bitmap.or(bv)
            }
          bitmap
        }
      }
    }
    toBitset(bitmap, numRows)
  }

  /**
   * Sorts the subset of feature values at indices [from, to) in the passed-in column
   *
   * @param tempVals Destination buffer for sorted feature values
   * @param tempIndices Destination buffer for row indices corresponding to sorted feature values
   * @param numLeftRows Number of rows on the left side of the split
   * @param instanceBitVector instanceBitVector(i) = true if the row for the ith feature
   *                          value splits right, false otherwise
   */
  private[ml] def sortCol(
      col: FeatureVector,
      from: Int,
      to: Int,
      numLeftRows: Int,
      tempVals: Array[Int],
      tempIndices: Array[Int],
      instanceBitVector: BitSet): Unit = {

    // BEGIN SORTING
    // We sort the [from, to) slice of col based on instance bit, then
    // instance value. This is required to match the bit vector across all
    // workers. All instances going "left" in the split (which are false)
    // should be ordered before the instances going "right". The instanceBitVector
    // gives us the bit value for each instance based on the instance's index.
    // Then both [from, numLeftRows) and [numLeftRows, to) need to be sorted
    // by value.
    // Since the column is already sorted by value, we can compute
    // this sort in a single pass over the data. We iterate from start to finish
    // (which preserves the sorted order), and then copy the values
    // into @tempVals and @tempIndices either:
    // 1) in the [from, numLeftRows) range if the bit is false, or
    // 2) in the [numBitsNotSet, to) range if the bit is true.
    var (leftInstanceIdx, rightInstanceIdx) = (from, from + numLeftRows)
    var idx = from
    while (idx < to) {
      val indexForVal = col.indices(idx)
      val bit = instanceBitVector.get(indexForVal)
      if (bit) {
        tempVals(rightInstanceIdx) = col.values(idx)
        tempIndices(rightInstanceIdx) = indexForVal
        rightInstanceIdx += 1
      } else {
        tempVals(leftInstanceIdx) = col.values(idx)
        tempIndices(leftInstanceIdx) = indexForVal
        leftInstanceIdx += 1
      }
      idx += 1
    }
    // END SORTING
    // update the column values and indices
    // with the corresponding indices
    System.arraycopy(tempVals, from, col.values, from, to - from)
    System.arraycopy(tempIndices, from, col.indices, from, to - from)
  }

  /**
   * Convert a dataset of [[Vector]] from row storage to column storage.
   * This can take any [[Vector]] type but stores data as [[DenseVector]].
   *
   * This maintains sparsity in the data.
   *
   * This maintains matrix structure.  I.e., each partition of the output RDD holds adjacent
   * columns.  The number of partitions will be min(input RDD's number of partitions, numColumns).
   *
   * @param rowStore  An array of input data rows, each represented as an
   *                  int array of binned feature values
   * @return Transpose of rowStore with
   *
   * TODO: Add implementation for sparse data.
   *       For sparse data, distribute more evenly based on number of non-zeros.
   *       (First collect stats to decide how to partition.)
   */
  private[impl] def rowToColumnStoreDense(rowStore: Array[Array[Int]]): Array[Array[Int]] = {
    // Compute the number of rows in the data
    val numRows = {
      val longNumRows: Long = rowStore.length
      require(longNumRows < Int.MaxValue, s"rowToColumnStore given RDD with $longNumRows rows," +
        s" but can handle at most ${Int.MaxValue} rows")
      longNumRows.toInt
    }

    // Return an empty array for a dataset with zero rows or columns, otherwise
    // return the transpose of the rowStore matrix
    if (numRows == 0 || rowStore(0).length == 0) {
      Array.empty
    } else {
      val numCols = rowStore(0).length
      0.until(numCols).map { colIdx =>
        rowStore.map(row => row(colIdx))
      }.toArray
    }
  }

}
