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

package org.apache.spark.sql.execution.joins

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.physical._
import org.apache.spark.sql.execution.{BinaryNode, SparkPlan}
import org.apache.spark.util.collection.CompactBuffer

/**
 * :: DeveloperApi ::
 * Performs an sort merge join of two child relations.
 */
@DeveloperApi
case class SortMergeJoin(
    leftKeys: Seq[Expression],
    rightKeys: Seq[Expression],
    left: SparkPlan,
    right: SparkPlan) extends BinaryNode {

  override def output: Seq[Attribute] = left.output ++ right.output

  override def outputPartitioning: Partitioning =
    PartitioningCollection(Seq(left.outputPartitioning, right.outputPartitioning))

  override def requiredChildDistribution: Seq[Distribution] =
    ClusteredDistribution(leftKeys) :: ClusteredDistribution(rightKeys) :: Nil

  // this is to manually construct an ordering that can be used to compare keys from both sides
  private val keyOrdering: RowOrdering = RowOrdering.forSchema(leftKeys.map(_.dataType))

  override def outputOrdering: Seq[SortOrder] = requiredOrders(leftKeys)

  override def requiredChildOrdering: Seq[Seq[SortOrder]] =
    requiredOrders(leftKeys) :: requiredOrders(rightKeys) :: Nil

  @transient protected lazy val leftKeyGenerator = newProjection(leftKeys, left.output)
  @transient protected lazy val rightKeyGenerator = newProjection(rightKeys, right.output)

  private def requiredOrders(keys: Seq[Expression]): Seq[SortOrder] =
    keys.map(SortOrder(_, Ascending))

  protected override def doExecute(): RDD[InternalRow] = {
    left.execute().zipPartitions(right.execute()) { (leftIter, rightIter) =>
      new Iterator[InternalRow] {
        private[this] var currentLeftRow: InternalRow = _
        private[this] var currentRightMatches: CompactBuffer[InternalRow] = _
        private[this] var currentMatchIdx: Int = -1
        private[this] val smjScanner = new SortMergeJoinScanner(
          leftKeyGenerator,
          rightKeyGenerator,
          keyOrdering,
          leftIter,
          rightIter
        )
        private[this] val joinRow = new JoinedRow

        override final def hasNext: Boolean =
          (currentMatchIdx != -1 && currentMatchIdx < currentRightMatches.length) || fetchNext()

        private[this] def fetchNext(): Boolean = {
          if (smjScanner.findNextInnerJoinRows()) {
            currentRightMatches = smjScanner.getRightMatches
            currentLeftRow = smjScanner.getLeftRow
            currentMatchIdx = 0
            true
          } else {
            currentRightMatches = null
            currentLeftRow = null
            currentMatchIdx = -1
            false
          }
        }

        override def next(): InternalRow = {
          if (currentMatchIdx == -1 || currentMatchIdx == currentRightMatches.length) {
            fetchNext()
          }
          val joinedRow = joinRow(currentLeftRow, currentRightMatches(currentMatchIdx))
          currentMatchIdx += 1
          joinedRow
        }
      }
    }
  }
}

/**
 * Helper class that is used to implement [[SortMergeJoin]] and [[SortMergeOuterJoin]].
 */
private[joins] class SortMergeJoinScanner(
    leftKeyGenerator: Projection,
    rightKeyGenerator: Projection,
    keyOrdering: RowOrdering,
    leftIter: Iterator[InternalRow],
    rightIter: Iterator[InternalRow]) {
  private[this] var leftRow: InternalRow = _
  private[this] var leftJoinKey: InternalRow = _
  private[this] var rightRow: InternalRow = _
  private[this] var rightJoinKey: InternalRow = _
   /** The join key for the rows buffered in `rightMatches`, or null if `rightMatches` is empty */
  private[this] var matchedJoinKey: InternalRow = _
  /** Buffered rows from the right side of the join. This is never null. */
  private[this] var rightMatches: CompactBuffer[InternalRow] = new CompactBuffer[InternalRow]()

  // Initialization (note: do _not_ want to advance left here).
  advanceRight()

  // --- Public methods ---------------------------------------------------------------------------

  /**
   * Advances both input iterators, stopping when we have found rows with matching join keys.
   * @return true if matching rows have been found and false otherwise. If this returns true, then
   *         [[getLeftRow]] and [[getRightMatches]] can be called to produce the join results.
   */
  final def findNextInnerJoinRows(): Boolean = {
    advanceLeft()
    if (leftRow == null) {
      // We have consumed the entire left iterator, so there can be no more matches.
      false
    } else if (matchedJoinKey != null && keyOrdering.compare(leftJoinKey, matchedJoinKey) == 0) {
      // The new left row has the same join key as the previous row, so return the same matches.
      true
    } else if (rightRow == null) {
      // The left row's join key does not match the current batch of right rows and there are no
      // more rows to read from the right iterator, so there can be no more matches.
      false
    } else {
      // Advance both the left and right iterators to find the next pair of matching rows.
      var comp = 0
      do {
        if (leftJoinKey.anyNull) {
          advanceLeft()
        } else if (rightJoinKey.anyNull) {
          advanceRight()
        } else {
          comp = keyOrdering.compare(leftJoinKey, rightJoinKey)
          if (comp > 0) advanceRight()
          else if (comp < 0) advanceLeft()
        }
      } while (leftRow != null && rightRow != null && comp != 0)
      if (leftRow == null || rightRow == null) {
        // We have either hit the end of one of the iterators, so there can be no more matches.
        false
      } else {
        // The left and right rows have matching join keys, so scan through the right iterator to
        // buffer all matching rows.
        assert(comp == 0)
        matchedJoinKey = leftJoinKey
        rightMatches = new CompactBuffer[InternalRow]()
        do {
          // TODO(josh): could maybe avoid a copy for case where all rows have exactly one match
          rightMatches += rightRow.copy() // need to copy mutable rows before buffering them
          advanceRight()
        } while (rightRow != null && keyOrdering.compare(leftJoinKey, rightJoinKey) == 0)
        true
      }
    }
  }

  def getRightMatches: CompactBuffer[InternalRow] = rightMatches
  def getLeftRow: InternalRow = leftRow

  // --- Private methods --------------------------------------------------------------------------

  /**
   * Advance the left iterator and compute the new row's join key.
   */
  private def advanceLeft(): Unit = {
    if (leftIter.hasNext) {
      leftRow = leftIter.next()
      leftJoinKey = leftKeyGenerator(leftRow)
    } else {
      leftRow = null
      leftJoinKey = null
    }
  }

  /**
   * Advance the right iterator and compute the new row's join key.
   */
  private def advanceRight(): Unit = {
    if (rightIter.hasNext) {
      rightRow = rightIter.next()
      rightJoinKey = rightKeyGenerator(rightRow)
    } else {
      rightRow = null
      rightJoinKey = null
    }
  }
}
