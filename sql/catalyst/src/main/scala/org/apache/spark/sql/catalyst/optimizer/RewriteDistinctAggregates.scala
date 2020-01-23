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

import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate.{AggregateExpression, AggregateFunction, Complete}
import org.apache.spark.sql.catalyst.plans.logical.{Aggregate, Expand, LogicalPlan}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.types.IntegerType

/**
 * This rule rewrites an aggregate query with distinct aggregations into an expanded double
 * aggregation in which the regular aggregation expressions and every distinct clause is aggregated
 * in a separate group. The results are then combined in a second aggregate.
 *
 * First example: query without filter clauses (in scala):
 * {{{
 *   val data = Seq(
 *     (1, "a", "ca1", "cb1", 10),
 *     (2, "a", "ca1", "cb2", 5),
 *     (3, "b", "ca1", "cb1", 13))
 *     .toDF("id", "key", "cat1", "cat2", "value")
 *   data.createOrReplaceTempView("data")
 *
 *   val agg = data.groupBy($"key")
 *     .agg(
 *       countDistinct($"cat1").as("cat1_cnt"),
 *       countDistinct($"cat2").as("cat2_cnt"),
 *       sum($"value").as("total"))
 * }}}
 *
 * This translates to the following (pseudo) logical plan:
 * {{{
 * Aggregate(
 *    key = ['key]
 *    functions = [COUNT(DISTINCT 'cat1),
 *                 COUNT(DISTINCT 'cat2),
 *                 sum('value)]
 *    output = ['key, 'cat1_cnt, 'cat2_cnt, 'total])
 *   LocalTableScan [...]
 * }}}
 *
 * This rule rewrites this logical plan to the following (pseudo) logical plan:
 * {{{
 * Aggregate(
 *    key = ['key]
 *    functions = [count(if (('gid = 1)) 'cat1 else null),
 *                 count(if (('gid = 2)) 'cat2 else null),
 *                 first(if (('gid = 0)) 'total else null) ignore nulls]
 *    output = ['key, 'cat1_cnt, 'cat2_cnt, 'total])
 *   Aggregate(
 *      key = ['key, 'cat1, 'cat2, 'gid]
 *      functions = [sum('value)]
 *      output = ['key, 'cat1, 'cat2, 'gid, 'total])
 *     Expand(
 *        projections = [('key, null, null, 0, cast('value as bigint)),
 *                       ('key, 'cat1, null, 1, null),
 *                       ('key, null, 'cat2, 2, null)]
 *        output = ['key, 'cat1, 'cat2, 'gid, 'value])
 *       LocalTableScan [...]
 * }}}
 *
 * Second example: aggregate function without distinct and with filter clauses (in sql):
 * {{{
 *   SELECT
 *     COUNT(DISTINCT cat1) as cat1_cnt,
 *     COUNT(DISTINCT cat2) as cat2_cnt,
 *     SUM(value) FILTER (WHERE id > 1) AS total
 *  FROM
 *    data
 *  GROUP BY
 *    key
 * }}}
 *
 * This translates to the following (pseudo) logical plan:
 * {{{
 * Aggregate(
 *    key = ['key]
 *    functions = [COUNT(DISTINCT 'cat1),
 *                 COUNT(DISTINCT 'cat2),
 *                 sum('value) with FILTER('id > 1)]
 *    output = ['key, 'cat1_cnt, 'cat2_cnt, 'total])
 *   LocalTableScan [...]
 * }}}
 *
 * This rule rewrites this logical plan to the following (pseudo) logical plan:
 * {{{
 * Aggregate(
 *    key = ['key]
 *    functions = [count(if (('gid = 1)) 'cat1 else null),
 *                 count(if (('gid = 2)) 'cat2 else null),
 *                 first(if (('gid = 0)) 'total else null) ignore nulls]
 *    output = ['key, 'cat1_cnt, 'cat2_cnt, 'total])
 *   Aggregate(
 *      key = ['key, 'cat1, 'cat2, 'gid]
 *      functions = [sum('value) with FILTER('id > 1)]
 *      output = ['key, 'cat1, 'cat2, 'gid, 'total])
 *     Expand(
 *        projections = [('key, null, null, 0, cast('value as bigint), 'id),
 *                       ('key, 'cat1, null, 1, null, null),
 *                       ('key, null, 'cat2, 2, null, null)]
 *        output = ['key, 'cat1, 'cat2, 'gid, 'value, 'id])
 *       LocalTableScan [...]
 * }}}
 *
 * Third example: single distinct aggregate function with filter clauses (in sql):
 * {{{
 *   SELECT
 *     COUNT(DISTINCT cat1) FILTER (WHERE id > 1) as cat1_cnt,
 *     COUNT(DISTINCT cat2) as cat2_cnt,
 *     SUM(value) AS total
 *  FROM
 *    data
 *  GROUP BY
 *    key
 * }}}
 *
 * This translates to the following (pseudo) logical plan:
 * {{{
 * Aggregate(
 *    key = ['key]
 *    functions = [COUNT(DISTINCT 'cat1) with FILTER('id > 1),
 *                 COUNT(DISTINCT 'cat2),
 *                 sum('value)]
 *    output = ['key, 'cat1_cnt, 'cat2_cnt, 'total])
 *   LocalTableScan [...]
 * }}}
 *
 * This rule rewrites this logical plan to the following (pseudo) logical plan:
 * {{{
 *   Aggregate(
 *      key = ['key]
 *      functions = [count(if (('gid = 1)) '_gen_distinct_1 else null),
 *                   count(if (('gid = 2)) '_gen_distinct_2 else null),
 *                   first(if (('gid = 0)) 'total else null) ignore nulls]
 *      output = ['key, 'cat1_cnt, 'cat2_cnt, 'total])
 *     Aggregate(
 *        key = ['key, '_gen_distinct_1, '_gen_distinct_2, 'gid]
 *        functions = [sum('value)]
 *        output = ['key, '_gen_distinct_1, '_gen_distinct_2, 'gid, 'total])
 *       Expand(
 *           projections = [('key, null, null, 0, 'value),
 *                          ('key, '_gen_distinct_1, null, 1, null),
 *                          ('key, null, '_gen_distinct_2, 2, null)]
 *           output = ['key, '_gen_distinct_1, '_gen_distinct_2, 'gid, 'value])
 *         Expand(
 *            projections = [('key, if ('id > 1) 'cat1 else null, 'cat2, cast('value as bigint))]
 *            output = ['key, '_gen_distinct_1, '_gen_distinct_2, 'value])
 *           LocalTableScan [...]
 * }}}
 *
 * The rule consists of the two phases as follows.
 *
 * In the first phase, expands data for the distinct aggregates where filter clauses exist:
 * 1. Guaranteed to compute filter clauses in the first aggregate locally.
 * 2. The attributes referenced by different distinct aggregate expressions are likely to overlap,
 *    and if no additional processing is performed, data loss will occur. To prevent this, we
 *    generate new attributes and replace the original ones.
 * 3. If we apply the first phase to distinct aggregate expressions which exists filter
 *    clause, the aggregate after expand may have at least two distinct aggregates, so we need to
 *    apply the second phase too. Please refer to the second phase for more details.
 *
 * In the second phase, rewrites a query with two or more distinct groups:
 * 1. Expand the data. There are three aggregation groups in this query:
 *    i. the non-distinct group;
 *    ii. the distinct 'cat1 group;
 *    iii. the distinct 'cat2 group.
 *    An expand operator is inserted to expand the child data for each group. The expand will null
 *    out all unused columns for the given group; this must be done in order to ensure correctness
 *    later on. Groups can by identified by a group id (gid) column added by the expand operator.
 * 2. De-duplicate the distinct paths and aggregate the non-aggregate path. The group by clause of
 *    this aggregate consists of the original group by clause, all the requested distinct columns
 *    and the group id. Both de-duplication of distinct column and the aggregation of the
 *    non-distinct group take advantage of the fact that we group by the group id (gid) and that we
 *    have nulled out all non-relevant columns the given group.
 * 3. Aggregating the distinct groups and combining this with the results of the non-distinct
 *    aggregation. In this step we use the group id to filter the inputs for the aggregate
 *    functions. The result of the non-distinct group are 'aggregated' by using the first operator,
 *    it might be more elegant to use the native UDAF merge mechanism for this in the future.
 *
 * This rule duplicates the input data by two or more times (# distinct groups + an optional
 * non-distinct group). This will put quite a bit of memory pressure of the used aggregate and
 * exchange operators. Keeping the number of distinct groups as low as possible should be priority,
 * we could improve this in the current rule by applying more advanced expression canonicalization
 * techniques.
 */
object RewriteDistinctAggregates extends Rule[LogicalPlan] {

  private def mayNeedtoRewrite(exprs: Seq[Expression]): Boolean = {
    val distinctAggs = exprs.flatMap { _.collect {
      case ae: AggregateExpression if ae.isDistinct => ae
    }}
    // We need at least two distinct aggregates or a single distinct aggregate with a filter for
    // this rule because aggregation strategy can handle a single distinct group without a filter.
    // This check can produce false-positives, e.g., SUM(DISTINCT a) & COUNT(DISTINCT a).
    distinctAggs.size > 1 || distinctAggs.exists(_.filter.isDefined)
  }

  def apply(plan: LogicalPlan): LogicalPlan = plan transformUp {
    case a: Aggregate if mayNeedtoRewrite(a.aggregateExpressions) =>
      val expandAggregate = extractFiltersInDistinctAggregates(a)
      rewriteDistinctAggregates(expandAggregate)
  }

  private def extractFiltersInDistinctAggregates(a: Aggregate): Aggregate = {
    val aggExpressions = collectAggregateExprs(a)
    val (distinctAggExpressions, regularAggExpressions) = aggExpressions.partition(_.isDistinct)
    if (distinctAggExpressions.exists(_.filter.isDefined)) {
      // Constructs pair between old and new expressions for regular aggregates.
      // We will construct a new `Aggregate` and it's child is an `Expand` expands the child
      // of the old `Aggregate`. Because the output attributes of `Expand` are not the same as
      // the output attributes of the old Aggregate's child, we need to change the reference
      // for regular aggregates.
      val regularAggExprs = regularAggExpressions.filter(_.children.exists(!_.foldable))
      val regularFunChildren = regularAggExprs
        .flatMap(_.aggregateFunction.children.filter(!_.foldable))
      val regularFilterAttrs = regularAggExprs.flatMap(_.filterAttributes)
      val regularAggChildren = (regularFunChildren ++ regularFilterAttrs).distinct
      val regularAggChildAttrMap = regularAggChildren.map(expressionAttributePair)
      val regularAggChildAttrLookup = regularAggChildAttrMap.toMap
      val regularAggPairs = regularAggExprs.map {
        case ae @ AggregateExpression(af, _, _, filter, _) =>
          val newChildren = af.children.map(c => regularAggChildAttrLookup.getOrElse(c, c))
          val raf = af.withNewChildren(newChildren).asInstanceOf[AggregateFunction]
          val filterOpt = filter.map(_.transform {
            case a: Attribute => regularAggChildAttrLookup.getOrElse(a, a)
          })
          val aggExpr = ae.copy(aggregateFunction = raf, filter = filterOpt)
          (ae, aggExpr)
      }

      // Setup expand for the distinct aggregate expressions.
      val distinctAggExprs = distinctAggExpressions.filter(e => e.children.exists(!_.foldable))
      val (projections, expressionAttrs, distinctAggPairs) = distinctAggExprs.map {
        case ae @ AggregateExpression(af, _, _, filter, _) =>
          // Why do we need to construct the `exprId` ?
          // First, In order to reduce costs, it is better to handle the filter clause locally.
          // e.g. COUNT (DISTINCT a) FILTER (WHERE id > 1), evaluate expression
          // If(id > 1) 'a else null first, and use the result as output.
          // Second, If at least two DISTINCT aggregate expression which may references the
          // same attributes. We need to construct the generate attributes so as the output not
          // lost. e.g. SUM (DISTINCT a), COUNT (DISTINCT a) FILTER (WHERE id > 1) will output
          // attribute '_gen_distinct-1 and attribute '_gen_distinct-2 instead of two 'a.
          // Note: We just need to illusion the expression with filter clause.
          // The extension mechanism may expand one distinct group into two or more distinct
          // group, so we still need to call `rewriteDistinctAggregates`.
          val exprId = NamedExpression.newExprId.id
          val unfoldableChildren = af.children.filter(!_.foldable)
          val exprAttrs = unfoldableChildren.map { e =>
            (e, AttributeReference(s"_gen_distinct_$exprId", e.dataType, nullable = true)())
          }
          val exprAttrLookup = exprAttrs.toMap
          val newChildren = af.children.map(c => exprAttrLookup.getOrElse(c, c))
          val raf = af.withNewChildren(newChildren).asInstanceOf[AggregateFunction]
          val aggExpr = ae.copy(aggregateFunction = raf, filter = None)
          // Expand projection
          val projection = unfoldableChildren.map {
            case e if filter.isDefined => If(filter.get, e, nullify(e))
            case e => e
          }
          (projection, exprAttrs, (ae, aggExpr))
      }.unzip3
      val distinctAggChildAttrs = expressionAttrs.flatten.map(_._2)
      val allAggAttrs = regularAggChildAttrMap.map(_._2) ++ distinctAggChildAttrs
      // Construct the aggregate input projection.
      val rewriteAggProjections =
        Seq(a.groupingExpressions ++ regularAggChildren ++ projections.flatten)
      val groupByMap = a.groupingExpressions.collect {
        case ne: NamedExpression => ne -> ne.toAttribute
        case e => e -> AttributeReference(e.sql, e.dataType, e.nullable)()
      }
      val groupByAttrs = groupByMap.map(_._2)
      // Construct the expand operator.
      val expand = Expand(rewriteAggProjections, groupByAttrs ++ allAggAttrs, a.child)
      val rewriteAggExprLookup = (distinctAggPairs ++ regularAggPairs).toMap
      val patchedAggExpressions = a.aggregateExpressions.map { e =>
        e.transformDown {
          case ae: AggregateExpression => rewriteAggExprLookup.getOrElse(ae, ae)
        }.asInstanceOf[NamedExpression]
      }
      val expandAggregate = Aggregate(groupByAttrs, patchedAggExpressions, expand)
      expandAggregate
    } else {
      a
    }
  }

  private def rewriteDistinctAggregates(a: Aggregate): Aggregate = {
    val aggExpressions = collectAggregateExprs(a)

    // Extract distinct aggregate expressions.
    val distinctAggGroups = aggExpressions.filter(_.isDistinct).groupBy { e =>
        val unfoldableChildren = e.aggregateFunction.children.filter(!_.foldable).toSet
        if (unfoldableChildren.nonEmpty) {
          // Only expand the unfoldable children
          unfoldableChildren
        } else {
          // If aggregateFunction's children are all foldable
          // we must expand at least one of the children (here we take the first child),
          // or If we don't, we will get the wrong result, for example:
          // count(distinct 1) will be explained to count(1) after the rewrite function.
          // Generally, the distinct aggregateFunction should not run
          // foldable TypeCheck for the first child.
          e.aggregateFunction.children.take(1).toSet
        }
    }

    // Aggregation strategy can handle queries with a single distinct group.
    if (distinctAggGroups.size > 1) {
      // Create the attributes for the grouping id and the group by clause.
      val gid = AttributeReference("gid", IntegerType, nullable = false)()
      val groupByMap = a.groupingExpressions.collect {
        case ne: NamedExpression => ne -> ne.toAttribute
        case e => e -> AttributeReference(e.sql, e.dataType, e.nullable)()
      }
      val groupByAttrs = groupByMap.map(_._2)

      // Functions used to modify aggregate functions and their inputs.
      def evalWithinGroup(id: Literal, e: Expression) = If(EqualTo(gid, id), e, nullify(e))
      def patchAggregateFunctionChildren(
          af: AggregateFunction)(
          attrs: Expression => Option[Expression]): AggregateFunction = {
        val newChildren = af.children.map(c => attrs(c).getOrElse(c))
        af.withNewChildren(newChildren).asInstanceOf[AggregateFunction]
      }

      // Setup unique distinct aggregate children.
      val distinctAggChildren = distinctAggGroups.keySet.flatten.toSeq.distinct
      val distinctAggChildAttrMap = distinctAggChildren.map(expressionAttributePair)
      val distinctAggChildAttrs = distinctAggChildAttrMap.map(_._2)

      // Setup expand & aggregate operators for distinct aggregate expressions.
      val distinctAggChildAttrLookup = distinctAggChildAttrMap.toMap
      val distinctAggOperatorMap = distinctAggGroups.toSeq.zipWithIndex.map {
        case ((group, expressions), i) =>
          val id = Literal(i + 1)

          // Expand projection
          val projection = distinctAggChildren.map {
            case e if group.contains(e) => e
            case e => nullify(e)
          } :+ id

          // Final aggregate
          val operators = expressions.map { e =>
            val af = e.aggregateFunction
            val naf = patchAggregateFunctionChildren(af) { x =>
              distinctAggChildAttrLookup.get(x).map(evalWithinGroup(id, _))
            }
            (e, e.copy(aggregateFunction = naf, isDistinct = false))
          }

          (projection, operators)
      }

      // Constructs pairs between old and new expressions for distinct aggregates, too.
      // only expand unfoldable children
      val regularAggExprs = aggExpressions
        .filter(e => !e.isDistinct && e.children.exists(!_.foldable))
      val regularAggFunChildren = regularAggExprs
        .flatMap(_.aggregateFunction.children.filter(!_.foldable))
      val regularAggFilterAttrs = regularAggExprs.flatMap(_.filterAttributes)
      val regularAggChildren = (regularAggFunChildren ++ regularAggFilterAttrs).distinct
      val regularAggChildAttrMap = regularAggChildren.map(expressionAttributePair)

      // Setup aggregates for 'regular' aggregate expressions.
      val regularGroupId = Literal(0)
      val regularAggChildAttrLookup = regularAggChildAttrMap.toMap
      val regularAggOperatorMap = regularAggExprs.map { e =>
        // Perform the actual aggregation in the initial aggregate.
        val af = patchAggregateFunctionChildren(e.aggregateFunction)(regularAggChildAttrLookup.get)
        // We changed the attributes in the [[Expand]] output using expressionAttributePair.
        // So we need to replace the attributes in FILTER expression with new ones.
        val filterOpt = e.filter.map(_.transform {
          case a: Attribute => regularAggChildAttrLookup.getOrElse(a, a)
        })
        val operator = Alias(e.copy(aggregateFunction = af, filter = filterOpt), e.sql)()

        // Select the result of the first aggregate in the last aggregate.
        val result = AggregateExpression(
          aggregate.First(evalWithinGroup(regularGroupId, operator.toAttribute), Literal(true)),
          mode = Complete,
          isDistinct = false)

        // Some aggregate functions (COUNT) have the special property that they can return a
        // non-null result without any input. We need to make sure we return a result in this case.
        val resultWithDefault = af.defaultResult match {
          case Some(lit) => Coalesce(Seq(result, lit))
          case None => result
        }

        // Return a Tuple3 containing:
        // i. The original aggregate expression (used for look ups).
        // ii. The actual aggregation operator (used in the first aggregate).
        // iii. The operator that selects and returns the result (used in the second aggregate).
        (e, operator, resultWithDefault)
      }

      // Construct the regular aggregate input projection only if we need one.
      val regularAggProjection = if (regularAggExprs.nonEmpty) {
        Seq(a.groupingExpressions ++
          distinctAggChildren.map(nullify) ++
          Seq(regularGroupId) ++
          regularAggChildren)
      } else {
        Seq.empty[Seq[Expression]]
      }

      // Construct the distinct aggregate input projections.
      val regularAggNulls = regularAggChildren.map(nullify)
      val distinctAggProjections = distinctAggOperatorMap.map {
        case (projection, _) =>
          a.groupingExpressions ++
            projection ++
            regularAggNulls
      }

      // Construct the expand operator.
      val expand = Expand(
        regularAggProjection ++ distinctAggProjections,
        groupByAttrs ++ distinctAggChildAttrs ++ Seq(gid) ++ regularAggChildAttrMap.map(_._2),
        a.child)

      // Construct the first aggregate operator. This de-duplicates all the children of
      // distinct operators, and applies the regular aggregate operators.
      val firstAggregateGroupBy = groupByAttrs ++ distinctAggChildAttrs :+ gid
      val firstAggregate = Aggregate(
        firstAggregateGroupBy,
        firstAggregateGroupBy ++ regularAggOperatorMap.map(_._2),
        expand)

      // Construct the second aggregate
      val transformations: Map[Expression, Expression] =
        (distinctAggOperatorMap.flatMap(_._2) ++
          regularAggOperatorMap.map(e => (e._1, e._3))).toMap

      val patchedAggExpressions = a.aggregateExpressions.map { e =>
        e.transformDown {
          case e: Expression =>
            // The same GROUP BY clauses can have different forms (different names for instance) in
            // the groupBy and aggregate expressions of an aggregate. This makes a map lookup
            // tricky. So we do a linear search for a semantically equal group by expression.
            groupByMap
              .find(ge => e.semanticEquals(ge._1))
              .map(_._2)
              .getOrElse(transformations.getOrElse(e, e))
        }.asInstanceOf[NamedExpression]
      }
      Aggregate(groupByAttrs, patchedAggExpressions, firstAggregate)
    } else {
      a
    }
  }

  private def collectAggregateExprs(a: Aggregate): Seq[AggregateExpression] = {
    a.aggregateExpressions.flatMap { _.collect {
        case ae: AggregateExpression => ae
    }}
  }

  private def nullify(e: Expression) = Literal.create(null, e.dataType)

  private def expressionAttributePair(e: Expression) =
    // We are creating a new reference here instead of reusing the attribute in case of a
    // NamedExpression. This is done to prevent collisions between distinct and regular aggregate
    // children, in this case attribute reuse causes the input of the regular aggregate to bound to
    // the (nulled out) input of the distinct aggregate.
    e -> AttributeReference(e.sql, e.dataType, nullable = true)()
}
