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

import org.apache.spark.sql.catalyst.expressions.NamedExpression
import org.apache.spark.sql.catalyst.expressions.aggregate.AggregateExpression
import org.apache.spark.sql.execution.{ExplainUtils, UnaryExecNode}

/**
 * Holds common logic for aggregate operators
 */
abstract class AggregateExec(
    groupingExpressions: Seq[NamedExpression],
    aggregateExpressions: Seq[AggregateExpression],
    resultExpressions: Seq[NamedExpression])
  extends UnaryExecNode {

  override def verboseStringWithOperatorId(): String = {
    val inputString = child.output.mkString("[", ", ", "]")
    val keyString = groupingExpressions.mkString("[", ", ", "]")
    val functionString = aggregateExpressions.mkString("[", ", ", "]")
    val resultString = resultExpressions.mkString("[", ", ", "]")
    val outputString = output.mkString("[", ", ", "]")
    s"""
       |(${ExplainUtils.getOpId(this)}) $nodeName ${ExplainUtils.getCodegenId(this)}
       |Input: $inputString
       |Keys: $keyString
       |Functions: $functionString
       |Results: $resultString
       |Output: $outputString
     """.stripMargin
  }
}
