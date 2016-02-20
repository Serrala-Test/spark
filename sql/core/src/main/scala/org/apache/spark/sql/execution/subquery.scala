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

import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{ExprId, ScalarSubquery, SubqueryExpression}
import org.apache.spark.sql.catalyst.expressions.codegen.CodegenFallback
import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, ReturnAnswer}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.types.DataType

/**
 * A subquery that will return only one row and one column.
 *
 * This is the physical copy of ScalarSubquery to be used inside SparkPlan.
 */
case class SparkScalarSubquery(
    @transient executedPlan: SparkPlan,
    exprId: ExprId)
  extends SubqueryExpression with CodegenFallback {

  override def query: LogicalPlan = throw new UnsupportedOperationException
  override def withNewPlan(plan: LogicalPlan): SubqueryExpression = {
    throw new UnsupportedOperationException
  }
  override def plan: SparkPlan = Subquery(simpleString, executedPlan)

  override def dataType: DataType = executedPlan.schema.fields.head.dataType
  override def nullable: Boolean = true
  override def toString: String = s"subquery#${exprId.id}"

  // the first column in first row from `query`.
  private var result: Any = null

  def updateResult(v: Any): Unit = {
    result = v
  }

  override def eval(input: InternalRow): Any = result
}

/**
 * Convert the subquery from logical plan into executed plan.
 */
private[sql] case class ConvertSubquery(sqlContext: SQLContext) extends Rule[SparkPlan] {
  def apply(plan: SparkPlan): SparkPlan = {
    plan.transformAllExpressions {
      // Only scalar subquery will be executed separately, all others will be written as join.
      case subquery: ScalarSubquery =>
        val sparkPlan = sqlContext.planner.plan(ReturnAnswer(subquery.query)).next()
        val executedPlan = sqlContext.prepareForExecution.execute(sparkPlan)
        SparkScalarSubquery(executedPlan, subquery.exprId)
    }
  }
}
