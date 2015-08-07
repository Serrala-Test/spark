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

package org.apache.spark.sql.execution.aggregate

import org.apache.spark.TaskContext
import org.apache.spark.rdd.{MapPartitionsWithPreparationRDD, RDD}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.errors._
import org.apache.spark.sql.catalyst.expressions.aggregate.AggregateExpression2
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.physical.{UnspecifiedDistribution, ClusteredDistribution, AllTuples, Distribution}
import org.apache.spark.sql.execution.{UnaryNode, SparkPlan}

case class TungstenAggregate(
    requiredChildDistributionExpressions: Option[Seq[Expression]],
    groupingExpressions: Seq[NamedExpression],
    nonCompleteAggregateExpressions: Seq[AggregateExpression2],
    completeAggregateExpressions: Seq[AggregateExpression2],
    initialInputBufferOffset: Int,
    resultExpressions: Seq[NamedExpression],
    child: SparkPlan)
  extends UnaryNode {

  override def outputsUnsafeRows: Boolean = true

  override def canProcessUnsafeRows: Boolean = true

  override def canProcessSafeRows: Boolean = false

  override def output: Seq[Attribute] = resultExpressions.map(_.toAttribute)

  override def requiredChildDistribution: List[Distribution] = {
    requiredChildDistributionExpressions match {
      case Some(exprs) if exprs.length == 0 => AllTuples :: Nil
      case Some(exprs) if exprs.length > 0 => ClusteredDistribution(exprs) :: Nil
      case None => UnspecifiedDistribution :: Nil
    }
  }

  // This is for testing. We force TungstenAggregationIterator to fall back to sort-based
  // aggregation once it has processed a given number of input rows.
  private val testFallbackStartsAt: Option[Int] = {
    sqlContext.getConf("spark.sql.TungstenAggregate.testFallbackStartsAt", null) match {
      case null | "" => None
      case fallbackStartsAt => Some(fallbackStartsAt.toInt)
    }
  }

  protected override def doExecute(): RDD[InternalRow] = attachTree(this, "execute") {

    /**
     * Set up the underlying unsafe data structures used before computing the parent partition.
     * This makes sure our iterator is not starved by other operators in the same task.
     */
    def preparePartition(): TungstenAggregationIterator = {
      new TungstenAggregationIterator(
        groupingExpressions,
        nonCompleteAggregateExpressions,
        completeAggregateExpressions,
        initialInputBufferOffset,
        resultExpressions,
        newMutableProjection,
        child.output,
        testFallbackStartsAt)
    }

    /** Compute a partition using the iterator already set up previously. */
    def executePartition(
        context: TaskContext,
        partitionIndex: Int,
        aggregationIterator: TungstenAggregationIterator,
        parentIterator: Iterator[UnsafeRow]): Iterator[UnsafeRow] = {
      val hasInput = parentIterator.hasNext
      if (!hasInput) {
        // We're not using the underlying map, so we just can free it here
        aggregationIterator.free()
        if (groupingExpressions.isEmpty) {
          // This is a grouped aggregate and the input iterator is empty,
          // so return an empty iterator.
          Iterator.single[UnsafeRow](aggregationIterator.outputForEmptyGroupingKeyWithoutInput())
        } else {
          Iterator[UnsafeRow]()
        }
      } else {
        aggregationIterator.start(parentIterator)
        aggregationIterator
      }
    }

    // Note: we need to set up the external sorter in each partition before computing
    // the parent partition, so we cannot simply use `mapPartitions` here (SPARK-9747).
    val parentPartition = child.execute().asInstanceOf[RDD[UnsafeRow]]
    val resultRdd = {
      new MapPartitionsWithPreparationRDD[UnsafeRow, UnsafeRow, TungstenAggregationIterator](
        parentPartition, preparePartition, executePartition, preservesPartitioning = true)
    }
    resultRdd.asInstanceOf[RDD[InternalRow]]
  }

  override def simpleString: String = {
    val allAggregateExpressions = nonCompleteAggregateExpressions ++ completeAggregateExpressions

    testFallbackStartsAt match {
      case None => s"TungstenAggregate ${groupingExpressions} ${allAggregateExpressions}"
      case Some(fallbackStartsAt) =>
        s"TungstenAggregateWithControlledFallback ${groupingExpressions} " +
          s"${allAggregateExpressions} fallbackStartsAt=$fallbackStartsAt"
    }
  }
}
