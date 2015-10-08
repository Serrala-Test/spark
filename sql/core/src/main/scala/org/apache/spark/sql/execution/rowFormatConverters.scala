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

package org.apache.spark.sql.execution

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.physical.Partitioning
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.columnar.{InMemoryRelation, InMemoryColumnarTableScan}

/**
 * :: DeveloperApi ::
 * Converts Java-object-based rows into [[UnsafeRow]]s.
 */
@DeveloperApi
case class ConvertToUnsafe(child: SparkPlan) extends UnaryNode {

  require(UnsafeProjection.canSupport(child.schema), s"Cannot convert ${child.schema} to Unsafe")

  override def output: Seq[Attribute] = child.output
  override def outputPartitioning: Partitioning = child.outputPartitioning
  override def outputOrdering: Seq[SortOrder] = child.outputOrdering
  override def outputsUnsafeRows: Boolean = true
  override def canProcessUnsafeRows: Boolean = false
  override def canProcessSafeRows: Boolean = true
  override protected def doExecute(): RDD[InternalRow] = {
    child.execute().mapPartitions { iter =>
      val convertToUnsafe = UnsafeProjection.create(child.schema)
      iter.map(convertToUnsafe)
    }
  }
}

/**
 * :: DeveloperApi ::
 * Converts [[UnsafeRow]]s back into Java-object-based rows.
 */
@DeveloperApi
case class ConvertToSafe(child: SparkPlan) extends UnaryNode {
  override def output: Seq[Attribute] = child.output
  override def outputPartitioning: Partitioning = child.outputPartitioning
  override def outputOrdering: Seq[SortOrder] = child.outputOrdering
  override def outputsUnsafeRows: Boolean = false
  override def canProcessUnsafeRows: Boolean = true
  override def canProcessSafeRows: Boolean = false
  override protected def doExecute(): RDD[InternalRow] = {
    child.execute().mapPartitions { iter =>
      val convertToSafe = FromUnsafeProjection(child.output.map(_.dataType))
      iter.map(convertToSafe)
    }
  }
}

private[sql] object EnsureRowFormats extends Rule[SparkPlan] {

  private def onlyHandlesSafeRows(operator: SparkPlan): Boolean =
    operator.canProcessSafeRows && !operator.canProcessUnsafeRows

  private def onlyHandlesUnsafeRows(operator: SparkPlan): Boolean =
    operator.canProcessUnsafeRows && !operator.canProcessSafeRows

  private def handlesBothSafeAndUnsafeRows(operator: SparkPlan): Boolean =
    operator.canProcessSafeRows && operator.canProcessUnsafeRows

  override def apply(operator: SparkPlan): SparkPlan = operator.transformUp {
    case operator: InMemoryColumnarTableScan if !operator.relation.child.outputsUnsafeRows =>
      val cache = operator.relation
      val newCache = InMemoryRelation(cache.useCompression, cache.batchSize, cache.storageLevel,
        ConvertToUnsafe(cache.child), cache.tableName)
      operator.copy(relation = newCache)

    case operator: SparkPlan if onlyHandlesSafeRows(operator) =>
      if (operator.children.exists(_.outputsUnsafeRows)) {
        operator.withNewChildren {
          operator.children.map {
            c => if (c.outputsUnsafeRows) ConvertToSafe(c) else c
          }
        }
      } else {
        operator
      }
    case operator: SparkPlan if onlyHandlesUnsafeRows(operator) =>
      if (operator.children.exists(!_.outputsUnsafeRows)) {
        operator.withNewChildren {
          operator.children.map {
            c => if (!c.outputsUnsafeRows) ConvertToUnsafe(c) else c
          }
        }
      } else {
        operator
      }
    case operator: SparkPlan if handlesBothSafeAndUnsafeRows(operator) =>
      if (operator.children.map(_.outputsUnsafeRows).toSet.size != 1) {
        // If this operator's children produce both unsafe and safe rows,
        // convert everything unsafe rows if all the schema of them are support by UnsafeRow
        if (operator.children.forall(c => UnsafeProjection.canSupport(c.schema))) {
          operator.withNewChildren {
            operator.children.map {
              c => if (!c.outputsUnsafeRows) ConvertToUnsafe(c) else c
            }
          }
        } else {
          operator.withNewChildren {
            operator.children.map {
              c => if (c.outputsUnsafeRows) ConvertToSafe(c) else c
            }
          }
        }
      } else {
        operator
      }
  }
}
