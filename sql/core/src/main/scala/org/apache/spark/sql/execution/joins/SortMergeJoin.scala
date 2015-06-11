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
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.physical._
import org.apache.spark.sql.execution.{BinaryNode, SparkPlan}

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

  override def outputPartitioning: Partitioning = left.outputPartitioning

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
    val leftResults = left.execute().map(_.copy())
    val rightResults = right.execute().map(_.copy())

    leftResults.zipPartitions(rightResults) { (leftIter, rightIter) =>
      new SortMergeJoinIterator(
        leftIter,
        rightIter,
        leftKeyGenerator,
        rightKeyGenerator,
        keyOrdering);
    }
  }
}
