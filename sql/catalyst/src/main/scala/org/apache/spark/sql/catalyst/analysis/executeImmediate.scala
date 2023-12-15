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

package org.apache.spark.sql.catalyst.analysis

import scala.util.{Either, Left, Right}

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.expressions.{Alias, Expression, VariableReference}
import org.apache.spark.sql.catalyst.parser.ParseException
import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, SetVariable}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.catalyst.trees.TreePattern.{EXECUTE_IMMEDIATE, TreePattern}
import org.apache.spark.sql.connector.catalog.CatalogManager
import org.apache.spark.sql.errors.QueryCompilationErrors.{invalidNameParameterizedQueryParametersMustBeNamed, unresolvedVariableError, unsupportedParameterExpression}
import org.apache.spark.sql.types.StringType

/**
 * Logical plan representing execute immediate query.
 *
 * @param args
 *   parameters of query
 * @param query
 *   query string or variable
 * @param targetVariables
 *   variables to store the result of the query
 */
case class ExecuteImmediateQuery(
    args: Seq[Expression],
    query: Either[String, UnresolvedAttribute],
    targetVariables: Seq[UnresolvedAttribute])
  extends UnresolvedLeafNode {
  final override val nodePatterns: Seq[TreePattern] = Seq(EXECUTE_IMMEDIATE)
}

/**
 * This rule substitutes execute immediate query node with plan that is passed as string literal
 * or session parameter.
 */
class SubstituteExecuteImmediate(val catalogManager: CatalogManager)
  extends Rule[LogicalPlan]
  with ColumnResolutionHelper {

  def resolveVariable(e: Expression): Expression = {

    /**
     * We know that the expression is either UnresolvedAttribute or Alias, as passed from the
     * parser. If it is an UnresolvedAttribute, we look it up in the catalog and return it. If it
     * is an Alias, we resolve the child and return an Alias with the same name.
     */
    e match {
      case u: UnresolvedAttribute =>
        getVariableReference(u, u.nameParts)
      case a: Alias =>
        Alias(resolveVariable(a.child), a.name)()
      case other =>
        throw unsupportedParameterExpression(other)
    }
  }

  def resolveArguments(expressions: Seq[Expression]): Seq[Expression] = {
    expressions.map { exp =>
      if (exp.resolved) {
        exp
      } else {
        resolveVariable(exp)
      }
    }
  }

  def extractQueryString(either: Either[String, UnresolvedAttribute]): String = {
    either match {
      case Left(v) => v
      case Right(u) =>
        val varReference = getVariableReference(u, u.nameParts)

        if (!varReference.dataType.sameType(StringType)) {
          throw new AnalysisException(
            errorClass = "INVALID_VARIABLE_TYPE_FOR_QUERY_EXECUTE_IMMEDIATE",
            messageParameters = Map("varType" -> varReference.dataType.simpleString))
        }

        // Call eval with null value passed instead of a row.
        // This is ok as this is variable and invoking eval should
        // be independent of row value.
        varReference.eval(null).toString
    }
  }

  override def apply(plan: LogicalPlan): LogicalPlan =
    plan.resolveOperatorsWithPruning(_.containsPattern(EXECUTE_IMMEDIATE), ruleId) {
      case ExecuteImmediateQuery(expressions, query, targetVariables) =>
        val queryString = extractQueryString(query)
        val plan =
          parseStatement(queryString, targetVariables)

        val posNodes = plan.collect { case p: LogicalPlan =>
          p.expressions.flatMap(_.collect { case n: PosParameter => n })
        }.flatten
        val namedNodes = plan.collect { case p: LogicalPlan =>
          p.expressions.flatMap(_.collect { case n: NamedParameter => n })
        }.flatten

        val queryPlan = if (expressions.isEmpty || (posNodes.isEmpty && namedNodes.isEmpty)) {
          plan
        } else if (posNodes.nonEmpty && namedNodes.nonEmpty) {
          throw new AnalysisException(
            errorClass = "INVALID_QUERY_BOTH_POSITIONAL_AND_NAMED_PARAMETERS_PRESENT",
            messageParameters = Map.empty)
        } else {
          if (posNodes.nonEmpty) {
            PosParameterizedQuery(
              plan,
              // We need to resolve arguments before Resolution batch to make sure
              // that some rule does not accidently resolve our parameters.
              // We do not want this as they can resolve some unsupported parameters
              resolveArguments(expressions))
          } else {
            val aliases = expressions.collect {
              case (e: Alias) => e
            }
            val nonAliases = expressions.filter(!_.isInstanceOf[Alias])

            if (nonAliases.nonEmpty) {
              throw invalidNameParameterizedQueryParametersMustBeNamed(nonAliases)
            }

            NameParameterizedQuery(
              plan,
              aliases.map(_.name),
              // We need to resolve arguments before Resolution batch to make sure
              // that some rule does not accidently resolve our parameters.
              // We do not want this as they can resolve some unsupported parameters.
              resolveArguments(aliases))
          }
        }

        if (targetVariables.nonEmpty) {
          SetVariable(targetVariables, queryPlan)
        } else { queryPlan }
    }

  private def parseStatement(
      queryString: String,
      targetVariables: Seq[Expression]): LogicalPlan = {
    // If targetVariables is defined, statement needs to be a query.
    // Otherwise, it can be anything.
    if (targetVariables.nonEmpty) {
      try {
        catalogManager.v1SessionCatalog.parser.parseQuery(queryString)
      } catch {
        case e: ParseException =>
          // Since we do not have a way of telling that parseQuery failed because of
          // actual parsing error or because statement was passed where query was expected,
          // we need to make sure that parsePlan wouldn't throw
          catalogManager.v1SessionCatalog.parser.parsePlan(queryString)

          // Plan was sucessfully parsed, but query wasn't - throw.
          throw new AnalysisException(
            errorClass = "INVALID_STATEMENT_FOR_EXECUTE_INTO",
            messageParameters = Map("sqlString" -> queryString),
            cause = Some(e))
      }
    } else {
      val plan = catalogManager.v1SessionCatalog.parser.parsePlan(queryString)

      // do not allow nested execute immediate
      if (plan.containsPattern(EXECUTE_IMMEDIATE)) {
        throw new AnalysisException(
          errorClass = "NESTED_EXECUTE_IMMEDIATE",
          messageParameters = Map("sqlString" -> queryString))
      }

      plan
    }
  }

  private def getVariableReference(expr: Expression, nameParts: Seq[String]): VariableReference = {
    lookupVariable(nameParts) match {
      case Some(variable) => variable.copy(canFold = false)
      case _ => throw unresolvedVariableError(nameParts, Seq("SYSTEM", "SESSION"), expr.origin)
    }
  }
}
