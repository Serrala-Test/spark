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

package org.apache.spark.sql.execution.datasources.v2

import org.apache.spark.sql.catalyst.expressions.{And, Attribute, AttributeReference, Expression, NamedExpression, PredicateHelper, ProjectionOverSchema, SubqueryExpression}
import org.apache.spark.sql.catalyst.expressions.aggregate
import org.apache.spark.sql.catalyst.expressions.aggregate.AggregateExpression
import org.apache.spark.sql.catalyst.planning.ScanOperation
import org.apache.spark.sql.catalyst.plans.logical.{Aggregate, Filter, LeafNode, LogicalPlan, Project}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.connector.read.{Scan, ScanBuilder, SupportsPushDownAggregates, SupportsPushDownFilters, V1Scan}
import org.apache.spark.sql.execution.datasources.DataSourceStrategy
import org.apache.spark.sql.sources
import org.apache.spark.sql.types.StructType

object V2ScanRelationPushDown extends Rule[LogicalPlan] with PredicateHelper {
  import DataSourceV2Implicits._

  def apply(plan: LogicalPlan): LogicalPlan = {
    applyColumnPruning(pushdownAggregate(pushDownFilters(createScanBuilder(plan))))
  }

  private def createScanBuilder(plan: LogicalPlan) = plan.transform {
    case r: DataSourceV2Relation =>
      ScanBuilderHolder(r.output, r, r.table.asReadable.newScanBuilder(r.options))
  }

  private def pushDownFilters(plan: LogicalPlan) = plan.transform {
    // update the scan builder with filter push down and return a new plan with filter pushed
    case Filter(condition, sHolder: ScanBuilderHolder) =>
      val filters = splitConjunctivePredicates(condition)
      val normalizedFilters =
        DataSourceStrategy.normalizeExprs(filters, sHolder.relation.output)
      val (normalizedFiltersWithSubquery, normalizedFiltersWithoutSubquery) =
        normalizedFilters.partition(SubqueryExpression.hasSubquery)

      // `pushedFilters` will be pushed down and evaluated in the underlying data sources.
      // `postScanFilters` need to be evaluated after the scan.
      // `postScanFilters` and `pushedFilters` can overlap, e.g. the parquet row group filter.
      val (pushedFilters, postScanFiltersWithoutSubquery) = PushDownUtils.pushFilters(
        sHolder.builder, normalizedFiltersWithoutSubquery)
      val postScanFilters = postScanFiltersWithoutSubquery ++ normalizedFiltersWithSubquery

      logInfo(
        s"""
           |Pushing operators to ${sHolder.relation.name}
           |Pushed Filters: ${pushedFilters.mkString(", ")}
           |Post-Scan Filters: ${postScanFilters.mkString(",")}
         """.stripMargin)

      val filterCondition = postScanFilters.reduceLeftOption(And)
      filterCondition.map(Filter(_, sHolder)).getOrElse(sHolder)
  }

  def pushdownAggregate(plan: LogicalPlan): LogicalPlan = plan.transform {
    // update the scan builder with agg pushdown and return a new plan with agg pushed
    case aggNode @ Aggregate(groupingExpressions, resultExpressions, child) =>
      child match {
        case ScanOperation(project, filters, sHolder: ScanBuilderHolder)
          if project.forall(_.isInstanceOf[AttributeReference]) =>
          sHolder.builder match {
            case _: SupportsPushDownAggregates =>
              if (filters.length == 0) { // can't push down aggregate if postScanFilters exist
                val aggregates = resultExpressions.flatMap { expr =>
                  expr.collect {
                    case agg: AggregateExpression => agg
                  }
                }
                val pushedAggregates = PushDownUtils
                  .pushAggregates(sHolder.builder, aggregates, groupingExpressions)
                if (pushedAggregates.isEmpty) {
                  aggNode // return original plan node
                } else {
                  // No need to do column pruning because only the aggregate columns are used as
                  // DataSourceV2ScanRelation output columns. All the other columns are not
                  // included in the output.
                  val scan = sHolder.builder.build()

                  // scalastyle:off
                  // use the group by columns and aggregate columns as the output columns
                  // e.g. TABLE t (c1 INT, c2 INT, c3 INT)
                  // SELECT min(c1), max(c1) FROM t GROUP BY c2;
                  // Use c2, min(c1), max(c1) as output for DataSourceV2ScanRelation
                  // We want to have the following logical plan:
                  // == Optimized Logical Plan ==
                  // Aggregate [c2#10], [min(min(c1)#21) AS min(c1)#17, max(max(c1)#22) AS max(c1)#18]
                  // +- RelationV2[c2#10, min(c1)#21, max(c1)#22]
                  // scalastyle:on
                  val newOutput = scan.readSchema().toAttributes
                  assert(newOutput.length == groupingExpressions.length + aggregates.length)
                  val groupAttrs = groupingExpressions.zip(newOutput).map {
                    case (a: Attribute, b: Attribute) => b.withExprId(a.exprId)
                    case (_, b) => b
                  }
                  val output = groupAttrs ++ newOutput.drop(groupAttrs.length)
                  
                  logInfo(
                    s"""
                       |Pushing operators to ${sHolder.relation.name}
                       |Pushed Aggregate Functions:
                       | ${pushedAggregates.get.getAggregateExpressions.mkString(", ")}
                       |Pushed Group by:
                       | ${pushedAggregates.get.getGroupByColumns.mkString(", ")}
                       |Output: ${output.mkString(", ")}
                      """.stripMargin)

                  val scanRelation = DataSourceV2ScanRelation(sHolder.relation, scan, output)

                  val plan = Aggregate(
                    output.take(groupingExpressions.length), resultExpressions, scanRelation)

                  // scalastyle:off
                  // Change the optimized logical plan to reflect the pushed down aggregate
                  // e.g. TABLE t (c1 INT, c2 INT, c3 INT)
                  // SELECT min(c1), max(c1) FROM t GROUP BY c2;
                  // The original logical plan is
                  // Aggregate [c2#10],[min(c1#9) AS min(c1)#17, max(c1#9) AS max(c1)#18]
                  // +- RelationV2[c1#9, c2#10] ...
                  //
                  // After change the V2ScanRelation output to [c2#10, min(c1)#21, max(c1)#22]
                  // we have the following
                  // !Aggregate [c2#10], [min(c1#9) AS min(c1)#17, max(c1#9) AS max(c1)#18]
                  // +- RelationV2[c2#10, min(c1)#21, max(c1)#22] ...
                  //
                  // We want to change it to
                  // == Optimized Logical Plan ==
                  // Aggregate [c2#10], [min(min(c1)#21) AS min(c1)#17, max(max(c1)#22) AS max(c1)#18]
                  // +- RelationV2[c2#10, min(c1)#21, max(c1)#22] ...
                  // scalastyle:on
                  var i = 0
                  val aggOutput = output.drop(groupAttrs.length)
                  plan.transformExpressions {
                    case agg: AggregateExpression =>
                      val aggFunction: aggregate.AggregateFunction =
                        agg.aggregateFunction match {
                          case _: aggregate.Max => aggregate.Max(aggOutput(i))
                          case _: aggregate.Min => aggregate.Min(aggOutput(i))
                          case _: aggregate.Sum => aggregate.Sum(aggOutput(i))
                          case _: aggregate.Count => aggregate.Sum(aggOutput(i))
                          case _ => agg.aggregateFunction
                        }
                      i += 1
                      agg.copy(aggregateFunction = aggFunction)
                  }
                }
              } else {
                aggNode
              }
            case _ => aggNode
          }
        case _ => aggNode
      }
  }

  def applyColumnPruning(plan: LogicalPlan): LogicalPlan = plan.transform {
    case ScanOperation(project, filters, sHolder: ScanBuilderHolder) =>
      // column pruning
      val normalizedProjects = DataSourceStrategy
        .normalizeExprs(project, sHolder.output)
        .asInstanceOf[Seq[NamedExpression]]
      val (scan, output) = PushDownUtils.pruneColumns(
        sHolder.builder, sHolder.relation, normalizedProjects, filters)

      logInfo(
        s"""
           |Output: ${output.mkString(", ")}
         """.stripMargin)

      val wrappedScan = scan match {
        case v1: V1Scan =>
          val translated = filters.flatMap(DataSourceStrategy.translateFilter(_, true))
          val pushedFilters = sHolder.builder match {
            case f: SupportsPushDownFilters =>
              f.pushedFilters()
            case _ => Array.empty[sources.Filter]
          }
          V1ScanWrapper(v1, translated, pushedFilters)
        case _ => scan
      }

      val scanRelation = DataSourceV2ScanRelation(sHolder.relation, wrappedScan, output)

      val projectionOverSchema = ProjectionOverSchema(output.toStructType)
      val projectionFunc = (expr: Expression) => expr transformDown {
        case projectionOverSchema(newExpr) => newExpr
      }

      val filterCondition = filters.reduceLeftOption(And)
      val newFilterCondition = filterCondition.map(projectionFunc)
      val withFilter = newFilterCondition.map(Filter(_, scanRelation)).getOrElse(scanRelation)

      val withProjection = if (withFilter.output != project) {
        val newProjects = normalizedProjects
          .map(projectionFunc)
          .asInstanceOf[Seq[NamedExpression]]
        Project(newProjects, withFilter)
      } else {
        withFilter
      }
      withProjection
  }
}

case class ScanBuilderHolder(
    output: Seq[AttributeReference],
    relation: DataSourceV2Relation,
    builder: ScanBuilder) extends LeafNode

// A wrapper for v1 scan to carry the translated filters and the handled ones. This is required by
// the physical v1 scan node.
case class V1ScanWrapper(
    v1Scan: V1Scan,
    translatedFilters: Seq[sources.Filter],
    handledFilters: Seq[sources.Filter]) extends Scan {
  override def readSchema(): StructType = v1Scan.readSchema()
}
