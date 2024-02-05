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

package org.apache.spark.sql.catalyst.optimizer

import scala.collection.mutable

import org.apache.spark.sql.catalyst.expressions.{AttributeReference, Expression, GetJsonObject, JsonTuple, NamedExpression}
import org.apache.spark.sql.catalyst.plans.logical.{Generate, LogicalPlan, Project}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.catalyst.trees.TreePattern.GET_JSON_OBJECT
import org.apache.spark.sql.internal.SQLConf.REWRITE_TO_JSON_TUPLE_THRESHOLD
import org.apache.spark.sql.types.StringType

/**
 * This rule rewrites multiple GetJsonObjects to a JsonTuple if their json expression is the same.
 */
object RewriteGetJsonObject extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = conf.getConf(REWRITE_TO_JSON_TUPLE_THRESHOLD) match {
    case None => plan
    case Some(threshold) =>
      plan.transformUpWithPruning(_.containsPattern(GET_JSON_OBJECT), ruleId) { case p: Project =>
        val getJsonObjects = p.projectList.flatMap {
          _.collect { case gjo: GetJsonObject if gjo.rewriteToJsonTuplePath.nonEmpty => gjo }
        }

        val groupedGetJsonObjects = getJsonObjects
          // an enhanced groupBy which result preserves the insertion order of keys
          .foldLeft(new mutable.LinkedHashMap[Expression, mutable.ListBuffer[GetJsonObject]]) {
            case (result: mutable.Map[Expression, mutable.ListBuffer[GetJsonObject]],
                  gjo: GetJsonObject) =>
              result.getOrElseUpdate(gjo.json, new mutable.ListBuffer[GetJsonObject]) += gjo
              result
          }
          .filter { case (_, gjoList) => gjoList.size >= threshold }
          .view.mapValues(_.toSeq)
        if (groupedGetJsonObjects.nonEmpty) {
          var newChild = p.child
          val keyValues = mutable.LinkedHashMap.empty[Expression, AttributeReference]
          groupedGetJsonObjects.foreach {
            case (json, getJsonObjects) =>
              val generatorOutput = getJsonObjects.map { gjo: GetJsonObject =>
                val attr = AttributeReference(gjo.rewriteToJsonTuplePath.get.toString, StringType)()
                keyValues.put(gjo.canonicalized, attr)
                attr
              }
              newChild = Generate(
                JsonTuple(json +: getJsonObjects.flatMap(_.rewriteToJsonTuplePath)),
                Nil,
                outer = false,
                Some(json.sql),
                generatorOutput,
                newChild)
          }

          val newProjectList = p.projectList.map {
            _.transformUp {
              case gjo: GetJsonObject => keyValues.getOrElse(gjo.canonicalized, gjo)
            }.asInstanceOf[NamedExpression]
          }
          p.copy(newProjectList, newChild)
        } else {
          p
        }
      }
  }
}
