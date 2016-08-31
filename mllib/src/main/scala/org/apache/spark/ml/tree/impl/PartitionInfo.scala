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

import scala.collection.mutable.ArrayBuffer

import org.apache.spark.ml.tree.LearningNode
import org.apache.spark.util.collection.BitSet

/**
 * Intermediate data stored during learning.
 * TODO(smurching): Rename; maybe TrainingInfo?
 *
 * Node indexing for nodeOffsets, activeNodes:
 * Nodes are indexed left-to-right along the periphery of the tree, with 0-based indices.
 * The periphery is the set of leaf nodes (active and inactive).
 *
 * @param columns  Array of columns.
 *                 Each column is sorted first by nodes (left-to-right along the tree periphery);
 *                 all columns share this first level of sorting.
 *                 Within each node's group, each column is sorted based on feature value;
 *                 this second level of sorting differs across columns.
 * @param nodeOffsets  Offsets into the columns indicating the first level of sorting (by node).
 *                     The rows corresponding to the node activeNodes(i) are in the range
 *                     [nodeOffsets(i)(0), nodeOffsets(i)(1)) .
 * @param activeNodes  Nodes which are active (still being split).
 *                     Inactive nodes are known to be leaves in the final tree.
 */
private[impl] case class PartitionInfo(
    columns: Array[FeatureVector],
    nodeOffsets: Array[(Int, Int)],
    activeNodes: Array[LearningNode],
    fullImpurityAggs: Array[ImpurityAggregatorSingle]) extends Serializable {

  // pre-allocated temporary buffers that we use to sort
  // instances in left and right children during update
  val tempVals: Array[Int] = new Array[Int](columns(0).values.length)
  val tempIndices: Array[Int] = new Array[Int](columns(0).values.length)

  /** For debugging */
  override def toString: String = {
    "PartitionInfo(" +
      "  columns: {\n" +
      columns.mkString(",\n") +
      "  },\n" +
      s"  nodeOffsets: ${nodeOffsets.mkString(", ")},\n" +
      s"  activeNodes: ${activeNodes.iterator.mkString(", ")},\n" +
      ")\n"
  }

  /**
   * Update columns and nodeOffsets for the next level of the tree.
   *
   * Update columns:
   *   For each column,
   *     For each (previously) active node,
   *       Sort corresponding range of instances based on bit vector.
   * Update nodeOffsets, activeNodes:
   *   Split offsets for nodes which split (which can be identified using the bit vector).
   *
   * @param instanceBitVector  Bit vector encoding splits for the next level of the tree.
   *                    These must follow a 2-level ordering, where the first level is by node
   *                    and the second level is by row index.
   *                    bitVector(i) = false iff instance i goes to the left child.
   *                    For instances at inactive (leaf) nodes, the value can be arbitrary.
   * @return Updated partition info
   */
  def update(
      instanceBitVector: BitSet,
      newActiveNodes: Array[LearningNode],
      labels: Array[Double],
      metadata: DecisionTreeMetadata): PartitionInfo = {

    // Create buffers for storing our new arrays of node offsets & impurities
    val newNodeOffsets = new ArrayBuffer[(Int, Int)]()
    val newFullImpurityAggs = new ArrayBuffer[ImpurityAggregatorSingle]()

    // Update first-level (per-node) sorting of each column to account for creation
    // of new nodes
    val newColumns = columns.zipWithIndex.map { case (col, index) =>
      // For the first column, determine the new offsets of active nodes & build
      // new impurity aggregators for each node.
      index match {
        case 0 => first(col, instanceBitVector, metadata, labels,
          newNodeOffsets, newFullImpurityAggs)
        case _ => rest(col, instanceBitVector, newNodeOffsets, newActiveNodes)
      }
      col
    }

    PartitionInfo(newColumns, newNodeOffsets.toArray, newActiveNodes, newFullImpurityAggs.toArray)
  }

  /**
   * TODO(smurching): Update doc
   * @param from
   * @param to
   * @param instanceBitVector
   * @param metadata
   * @param col
   * @param labels
   * @return
   */
  private def getImpurities(
      from: Int,
      to: Int,
      instanceBitVector: BitSet,
      metadata: DecisionTreeMetadata,
      col: FeatureVector,
      labels: Array[Double]): (ImpurityAggregatorSingle, ImpurityAggregatorSingle) = {
      val leftImpurity = metadata.createImpurityAggregator()
      val rightImpurity = metadata.createImpurityAggregator()
      from.until(to).foreach { idx =>
        val rowIndex = col.indices(idx)
        val label = labels(rowIndex)
        if (instanceBitVector.get(rowIndex)) {
          rightImpurity.update(label)
        } else {
          leftImpurity.update(label)
        }
      }
    (leftImpurity, rightImpurity)
  }

  /**
   * TODO(smurching): Update doc
   * @param from
   * @param numLeftBits
   * @param to
   * @param instanceBitVector
   * @param col
   */
  private def sortCol(
      from: Int,
      numLeftBits: Int,
      to: Int,
      instanceBitVector: BitSet,
      col: FeatureVector): Unit = {

    // BEGIN SORTING
    // We sort the [from, to) slice of col based on instance bit, then
    // instance value. This is required to match the bit vector across all
    // workers. All instances going "left" in the split (which are false)
    // should be ordered before the instances going "right". The instanceBitVector
    // gives us the bit value for each instance based on the instance's index.
    // Then both [from, numBitsNotSet) and [numBitsNotSet, to) need to be sorted
    // by value.
    // Since the column is already sorted by value, we can compute
    // this sort in a single pass over the data. We iterate from start to finish
    // (which preserves the sorted order), and then copy the values
    // into @tempVals and @tempIndices either:
    // 1) in the [from, numBitsNotSet) range if the bit is false, or
    // 2) in the [numBitsNotSet, to) range if the bit is true.
    var (leftInstanceIdx, rightInstanceIdx) = (from, from + numLeftBits)
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
   * Sort the very first column in the [[PartitionInfo.columns]]. While
   * we sort the column, we also update [[PartitionInfo.nodeOffsets]]
   * (by modifying @param newNodeOffsets) and [[PartitionInfo.fullImpurityAggs]]
   * (by modifying @param newFullImpurityAggs).
   *
   * @param col The very first column in [[PartitionInfo.columns]]
   * @param metadata Used to create new [[ImpurityAggregatorSingle]] for a new child
   *                 node in the tree
   * @param labels   Labels are read as we sort column to populate stats for each
   *                 new ImpurityAggregatorSingle
   */
  private def first(
      col: FeatureVector,
      instanceBitVector: BitSet,
      metadata: DecisionTreeMetadata,
      labels: Array[Double],
      newNodeOffsets: ArrayBuffer[(Int, Int)],
      newFullImpurityAggs: ArrayBuffer[ImpurityAggregatorSingle]): Unit = {

    activeNodes.indices.foreach { nodeIdx =>
      // WHAT TO OPTIMIZE:
      // - try skipping numBitsSet
      // - maybe uncompress bitmap
      val (from, to) = nodeOffsets(nodeIdx)

      // If this is the very first time we split,
      // we don't use rangeIndices to count the number of bits set;
      // the entire bit vector will be used, so getCardinality
      // will give us the same result more cheaply.
      val numBitsSet = if (nodeOffsets.length == 1) {
        instanceBitVector.cardinality()
      } else {
          from.until(to).foldLeft(0) { case (count, i) =>
            count + (if (instanceBitVector.get(col.indices(i))) 1 else 0)
          }
      }

      val numBitsNotSet = to - from - numBitsSet // number of instances splitting left
      // If numBitsNotSet or numBitsSet equals 0, then this node was not split,
      // so we do not need to update its part of the column. Otherwise, we update it.
      val wasSplit = numBitsNotSet != 0 && numBitsSet != 0
      if (wasSplit) {
        val leftIndices = (from, from + numBitsNotSet)
        val rightIndices = (from + numBitsNotSet, to)
        // TODO(smurching): Check that this adds indices in the same order as activeNodes
        // are produced during splitting
        newNodeOffsets ++= Array(leftIndices, rightIndices)

        // Compute impurities for the current node and add them to our buffer of
        // active node impurities
        val (leftImpurity, rightImpurity) = getImpurities(from, to,
          instanceBitVector, metadata, col, labels)
        newFullImpurityAggs ++= Array(leftImpurity, rightImpurity)

        sortCol(from, numBitsNotSet, to, instanceBitVector, col)
      }
    }

  }

  /**
   * Sort the remaining columns in the [[PartitionInfo.columns]]. Since
   * we already computed [[PartitionInfo.nodeOffsets]] and
   * [[PartitionInfo.fullImpurityAggs]] while we sorted the first column,
   * we skip the computation for those here.
   *
   * @param col The very first column in [[PartitionInfo.columns]]
   * @param newNodeOffsets Instead of re-computing number of bits set/not set
   *                       per split, we read those values from here
   */
  private def rest(
      col: FeatureVector,
      instanceBitVector: BitSet,
      newNodeOffsets: ArrayBuffer[(Int, Int)],
      newActiveNodes: Array[LearningNode]): Unit = {

    // TODO(smurching) newOffsets used to determine whether to split...
    // Can we still do this if you do the (from, to) range thing?
    // newOffsets(nodeIdx) = Array(left range, right range) if split, else Array(orig range)

    // Iterate over new active nodes in pairs
    0.until(newActiveNodes.length, 2).foreach { nodeIdx =>

      val (leftFrom, leftTo) = newNodeOffsets(nodeIdx)
      val (rightFrom, rightTo) = newNodeOffsets(nodeIdx + 1)

      // TODO(smurching): Rework this somehow, this pairwise-iteration thing is sketchy
      // Number of rows on the left side of the split
      val numBitsNotSet = leftTo - leftFrom
      sortCol(leftFrom, numBitsNotSet, rightTo, instanceBitVector, col)
    }

  }

}
