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

import scala.collection.mutable.ArrayBuffer

import org.apache.spark.util.collection.OpenHashSet
import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules._
import org.apache.spark.sql.types._

/**
 * A trivial [[Analyzer]] with an [[EmptyCatalog]] and [[EmptyFunctionRegistry]]. Used for testing
 * when all relations are already filled in and the analyzer needs only to resolve attribute
 * references.
 */
object SimpleAnalyzer extends Analyzer(EmptyCatalog, EmptyFunctionRegistry, true)

/**
 * Provides a logical query plan analyzer, which translates [[UnresolvedAttribute]]s and
 * [[UnresolvedRelation]]s into fully typed objects using information in a schema [[Catalog]] and
 * a [[FunctionRegistry]].
 */
class Analyzer(
    catalog: Catalog,
    registry: FunctionRegistry,
    caseSensitive: Boolean,
    maxIterations: Int = 100)
  extends RuleExecutor[LogicalPlan] with HiveTypeCoercion with CheckAnalysis {

  val resolver = if (caseSensitive) caseSensitiveResolution else caseInsensitiveResolution

  val fixedPoint = FixedPoint(maxIterations)

  /**
   * Override to provide additional rules for the "Resolution" batch.
   */
  val extendedResolutionRules: Seq[Rule[LogicalPlan]] = Nil

  lazy val batches: Seq[Batch] = Seq(
    Batch("Resolution", fixedPoint,
      ResolveRelations ::
      ResolveReferences ::
      ResolveGroupingAnalytics ::
      ResolveSortReferences ::
      ResolveGenerate ::
      ImplicitGenerate ::
      ResolveFunctions ::
      ExtractWindowExpressions ::
      GlobalAggregates ::
      UnresolvedHavingClauseAttributes ::
      TrimGroupingAliases ::
      typeCoercionRules ++
      extendedResolutionRules : _*)
  )

  /**
   * Removes no-op Alias expressions from the plan.
   */
  object TrimGroupingAliases extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan transform {
      case Aggregate(groups, aggs, child) =>
        Aggregate(groups.map(_.transform { case Alias(c, _) => c }), aggs, child)
    }
  }

  object ResolveGroupingAnalytics extends Rule[LogicalPlan] {
    /**
     * Extract attribute set according to the grouping id
     * @param bitmask bitmask to represent the selected of the attribute sequence
     * @param exprs the attributes in sequence
     * @return the attributes of non selected specified via bitmask (with the bit set to 1)
     */
    private def buildNonSelectExprSet(bitmask: Int, exprs: Seq[Expression])
    : OpenHashSet[Expression] = {
      val set = new OpenHashSet[Expression](2)

      var bit = exprs.length - 1
      while (bit >= 0) {
        if (((bitmask >> bit) & 1) == 0) set.add(exprs(bit))
        bit -= 1
      }

      set
    }

    /*
     *  GROUP BY a, b, c WITH ROLLUP
     *  is equivalent to
     *  GROUP BY a, b, c GROUPING SETS ( (a, b, c), (a, b), (a), ( ) ).
     *  Group Count: N + 1 (N is the number of group expressions)
     *
     *  We need to get all of its subsets for the rule described above, the subset is
     *  represented as the bit masks.
     */
    def bitmasks(r: Rollup): Seq[Int] = {
      Seq.tabulate(r.groupByExprs.length + 1)(idx => {(1 << idx) - 1})
    }

    /*
     *  GROUP BY a, b, c WITH CUBE
     *  is equivalent to
     *  GROUP BY a, b, c GROUPING SETS ( (a, b, c), (a, b), (b, c), (a, c), (a), (b), (c), ( ) ).
     *  Group Count: 2 ^ N (N is the number of group expressions)
     *
     *  We need to get all of its subsets for a given GROUPBY expression, the subsets are
     *  represented as the bit masks.
     */
    def bitmasks(c: Cube): Seq[Int] = {
      Seq.tabulate(1 << c.groupByExprs.length)(i => i)
    }

    /**
     * Create an array of Projections for the child projection, and replace the projections'
     * expressions which equal GroupBy expressions with Literal(null), if those expressions
     * are not set for this grouping set (according to the bit mask).
     */
    private[this] def expand(g: GroupingSets): Seq[GroupExpression] = {
      val result = new scala.collection.mutable.ArrayBuffer[GroupExpression]

      g.bitmasks.foreach { bitmask =>
        // get the non selected grouping attributes according to the bit mask
        val nonSelectedGroupExprSet = buildNonSelectExprSet(bitmask, g.groupByExprs)

        val substitution = (g.child.output :+ g.gid).map(expr => expr transformDown {
          case x: Expression if nonSelectedGroupExprSet.contains(x) =>
            // if the input attribute in the Invalid Grouping Expression set of for this group
            // replace it with constant null
            Literal.create(null, expr.dataType)
          case x if x == g.gid =>
            // replace the groupingId with concrete value (the bit mask)
            Literal.create(bitmask, IntegerType)
        })

        result += GroupExpression(substitution)
      }

      result.toSeq
    }

    def apply(plan: LogicalPlan): LogicalPlan = plan transform {
      case a: Cube if a.resolved =>
        GroupingSets(bitmasks(a), a.groupByExprs, a.child, a.aggregations, a.gid)
      case a: Rollup if a.resolved =>
        GroupingSets(bitmasks(a), a.groupByExprs, a.child, a.aggregations, a.gid)
      case x: GroupingSets if x.resolved =>
        Aggregate(
          x.groupByExprs :+ x.gid,
          x.aggregations,
          Expand(expand(x), x.child.output :+ x.gid, x.child))
    }
  }

  /**
   * Replaces [[UnresolvedRelation]]s with concrete relations from the catalog.
   */
  object ResolveRelations extends Rule[LogicalPlan] {
    def getTable(u: UnresolvedRelation, cteRelations: Map[String, LogicalPlan]): LogicalPlan = {
      try {
        // In hive, if there is same table name in database and CTE definition,
        // hive will use the table in database, not the CTE one.
        // Taking into account the reasonableness and the implementation complexity,
        // here use the CTE definition first, check table name only and ignore database name
        cteRelations.get(u.tableIdentifier.last)
          .map(relation => u.alias.map(Subquery(_, relation)).getOrElse(relation))
          .getOrElse(catalog.lookupRelation(u.tableIdentifier, u.alias))
      } catch {
        case _: NoSuchTableException =>
          u.failAnalysis(s"no such table ${u.tableName}")
      }
    }

    def apply(plan: LogicalPlan): LogicalPlan = {
      val (realPlan, cteRelations) = plan match {
        // TODO allow subquery to define CTE
        // Add cte table to a temp relation map,drop `with` plan and keep its child
        case With(child, relations) => (child, relations)
        case other => (other, Map.empty[String, LogicalPlan])
      }

      realPlan transform {
        case i@InsertIntoTable(u: UnresolvedRelation, _, _, _, _) =>
          i.copy(
            table = EliminateSubQueries(getTable(u, cteRelations)))
        case u: UnresolvedRelation =>
          getTable(u, cteRelations)
      }
    }
  }

  /**
   * Replaces [[UnresolvedAttribute]]s with concrete
   * [[catalyst.expressions.AttributeReference AttributeReferences]] from a logical plan node's
   * children.
   */
  object ResolveReferences extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan transformUp {
      case p: LogicalPlan if !p.childrenResolved => p

      // If the projection list contains Stars, expand it.
      case p @ Project(projectList, child) if containsStar(projectList) =>
        Project(
          projectList.flatMap {
            case s: Star => s.expand(child.output, resolver)
            case Alias(f @ UnresolvedFunction(_, args), name) if containsStar(args) =>
              val expandedArgs = args.flatMap {
                case s: Star => s.expand(child.output, resolver)
                case o => o :: Nil
              }
              Alias(child = f.copy(children = expandedArgs), name)() :: Nil
            case Alias(c @ CreateArray(args), name) if containsStar(args) =>
              val expandedArgs = args.flatMap {
                case s: Star => s.expand(child.output, resolver)
                case o => o :: Nil
              }
              Alias(c.copy(children = expandedArgs), name)() :: Nil
            case Alias(c @ CreateStruct(args), name) if containsStar(args) =>
              val expandedArgs = args.flatMap {
                case s: Star => s.expand(child.output, resolver)
                case o => o :: Nil
              }
              Alias(c.copy(children = expandedArgs), name)() :: Nil
            case o => o :: Nil
          },
          child)
      case t: ScriptTransformation if containsStar(t.input) =>
        t.copy(
          input = t.input.flatMap {
            case s: Star => s.expand(t.child.output, resolver)
            case o => o :: Nil
          }
        )

      // If the aggregate function argument contains Stars, expand it.
      case a: Aggregate if containsStar(a.aggregateExpressions) =>
        a.copy(
          aggregateExpressions = a.aggregateExpressions.flatMap {
            case s: Star => s.expand(a.child.output, resolver)
            case o => o :: Nil
          }
        )

      // Special handling for cases when self-join introduce duplicate expression ids.
      case j @ Join(left, right, _, _) if left.outputSet.intersect(right.outputSet).nonEmpty =>
        val conflictingAttributes = left.outputSet.intersect(right.outputSet)
        logDebug(s"Conflicting attributes ${conflictingAttributes.mkString(",")} in $j")

        val (oldRelation, newRelation) = right.collect {
          // Handle base relations that might appear more than once.
          case oldVersion: MultiInstanceRelation
              if oldVersion.outputSet.intersect(conflictingAttributes).nonEmpty =>
            val newVersion = oldVersion.newInstance()
            (oldVersion, newVersion)

          // Handle projects that create conflicting aliases.
          case oldVersion @ Project(projectList, _)
              if findAliases(projectList).intersect(conflictingAttributes).nonEmpty =>
            (oldVersion, oldVersion.copy(projectList = newAliases(projectList)))

          case oldVersion @ Aggregate(_, aggregateExpressions, _)
              if findAliases(aggregateExpressions).intersect(conflictingAttributes).nonEmpty =>
            (oldVersion, oldVersion.copy(aggregateExpressions = newAliases(aggregateExpressions)))
        }.headOption.getOrElse { // Only handle first case, others will be fixed on the next pass.
          sys.error(
            s"""
              |Failure when resolving conflicting references in Join:
              |$plan
              |
              |Conflicting attributes: ${conflictingAttributes.mkString(",")}
              """.stripMargin)
        }

        val attributeRewrites = AttributeMap(oldRelation.output.zip(newRelation.output))
        val newRight = right transformUp {
          case r if r == oldRelation => newRelation
        } transformUp {
          case other => other transformExpressions {
            case a: Attribute => attributeRewrites.get(a).getOrElse(a)
          }
        }
        j.copy(right = newRight)

      case q: LogicalPlan =>
        logTrace(s"Attempting to resolve ${q.simpleString}")
        q transformExpressionsUp  {
          case u @ UnresolvedAttribute(nameParts) if nameParts.length == 1 &&
            resolver(nameParts(0), VirtualColumn.groupingIdName) &&
            q.isInstanceOf[GroupingAnalytics] =>
            // Resolve the virtual column GROUPING__ID for the operator GroupingAnalytics
            q.asInstanceOf[GroupingAnalytics].gid
          case u @ UnresolvedAttribute(nameParts) =>
            // Leave unchanged if resolution fails.  Hopefully will be resolved next round.
            val result =
              withPosition(u) { q.resolveChildren(nameParts, resolver).getOrElse(u) }
            logDebug(s"Resolving $u to $result")
            result
          case UnresolvedExtractValue(child, fieldExpr) if child.resolved =>
            ExtractValue(child, fieldExpr, resolver)
        }
    }

    def newAliases(expressions: Seq[NamedExpression]): Seq[NamedExpression] = {
      expressions.map {
        case a: Alias => Alias(a.child, a.name)()
        case other => other
      }
    }

    def findAliases(projectList: Seq[NamedExpression]): AttributeSet = {
      AttributeSet(projectList.collect { case a: Alias => a.toAttribute })
    }

    /**
     * Returns true if `exprs` contains a [[Star]].
     */
    protected def containsStar(exprs: Seq[Expression]): Boolean =
      exprs.exists(_.collect { case _: Star => true }.nonEmpty)
  }

  /**
   * In many dialects of SQL it is valid to sort by attributes that are not present in the SELECT
   * clause.  This rule detects such queries and adds the required attributes to the original
   * projection, so that they will be available during sorting. Another projection is added to
   * remove these attributes after sorting.
   */
  object ResolveSortReferences extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan transformUp {
      case s @ Sort(ordering, global, p @ Project(projectList, child))
          if !s.resolved && p.resolved =>
        val (resolvedOrdering, missing) = resolveAndFindMissing(ordering, p, child)

        // If this rule was not a no-op, return the transformed plan, otherwise return the original.
        if (missing.nonEmpty) {
          // Add missing attributes and then project them away after the sort.
          Project(p.output,
            Sort(resolvedOrdering, global,
              Project(projectList ++ missing, child)))
        } else {
          logDebug(s"Failed to find $missing in ${p.output.mkString(", ")}")
          s // Nothing we can do here. Return original plan.
        }
      case s @ Sort(ordering, global, a @ Aggregate(grouping, aggs, child))
          if !s.resolved && a.resolved =>
        val unresolved = ordering.flatMap(_.collect { case UnresolvedAttribute(name) => name })
        // A small hack to create an object that will allow us to resolve any references that
        // refer to named expressions that are present in the grouping expressions.
        val groupingRelation = LocalRelation(
          grouping.collect { case ne: NamedExpression => ne.toAttribute }
        )

        val (resolvedOrdering, missing) = resolveAndFindMissing(ordering, a, groupingRelation)

        if (missing.nonEmpty) {
          // Add missing grouping exprs and then project them away after the sort.
          Project(a.output,
            Sort(resolvedOrdering, global,
              Aggregate(grouping, aggs ++ missing, child)))
        } else {
          s // Nothing we can do here. Return original plan.
        }
    }

    /**
     * Given a child and a grandchild that are present beneath a sort operator, returns
     * a resolved sort ordering and a list of attributes that are missing from the child
     * but are present in the grandchild.
     */
    def resolveAndFindMissing(
        ordering: Seq[SortOrder],
        child: LogicalPlan,
        grandchild: LogicalPlan): (Seq[SortOrder], Seq[Attribute]) = {
      // Find any attributes that remain unresolved in the sort.
      val unresolved: Seq[Seq[String]] =
        ordering.flatMap(_.collect { case UnresolvedAttribute(nameParts) => nameParts })

      // Create a map from name, to resolved attributes, when the desired name can be found
      // prior to the projection.
      val resolved: Map[Seq[String], NamedExpression] =
        unresolved.flatMap(u => grandchild.resolve(u, resolver).map(a => u -> a)).toMap

      // Construct a set that contains all of the attributes that we need to evaluate the
      // ordering.
      val requiredAttributes = AttributeSet(resolved.values)

      // Figure out which ones are missing from the projection, so that we can add them and
      // remove them after the sort.
      val missingInProject = requiredAttributes -- child.output

      // Now that we have all the attributes we need, reconstruct a resolved ordering.
      // It is important to do it here, instead of waiting for the standard resolved as adding
      // attributes to the project below can actually introduce ambiquity that was not present
      // before.
      val resolvedOrdering = ordering.map(_ transform {
        case u @ UnresolvedAttribute(name) => resolved.getOrElse(name, u)
      }).asInstanceOf[Seq[SortOrder]]

      (resolvedOrdering, missingInProject.toSeq)
    }
  }

  /**
   * Replaces [[UnresolvedFunction]]s with concrete [[catalyst.expressions.Expression Expressions]].
   */
  object ResolveFunctions extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan transform {
      case q: LogicalPlan =>
        q transformExpressions {
          case u @ UnresolvedFunction(name, children) if u.childrenResolved =>
            registry.lookupFunction(name, children)
        }
    }
  }

  /**
   * Turns projections that contain aggregate expressions into aggregations.
   */
  object GlobalAggregates extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan transform {
      case Project(projectList, child) if containsAggregates(projectList) =>
        Aggregate(Nil, projectList, child)
    }

    def containsAggregates(exprs: Seq[Expression]): Boolean = {
      exprs.foreach(_.foreach {
        case agg: AggregateExpression => return true
        case _ =>
      })
      false
    }
  }

  /**
   * This rule finds expressions in HAVING clause filters that depend on
   * unresolved attributes.  It pushes these expressions down to the underlying
   * aggregates and then projects them away above the filter.
   */
  object UnresolvedHavingClauseAttributes extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan transformUp {
      case filter @ Filter(havingCondition, aggregate @ Aggregate(_, originalAggExprs, _))
          if aggregate.resolved && containsAggregate(havingCondition) => {
        val evaluatedCondition = Alias(havingCondition,  "havingCondition")()
        val aggExprsWithHaving = evaluatedCondition +: originalAggExprs

        Project(aggregate.output,
          Filter(evaluatedCondition.toAttribute,
            aggregate.copy(aggregateExpressions = aggExprsWithHaving)))
      }
    }

    protected def containsAggregate(condition: Expression): Boolean =
      condition
        .collect { case ae: AggregateExpression => ae }
        .nonEmpty
  }

  /**
   * When a SELECT clause has only a single expression and that expression is a
   * [[catalyst.expressions.Generator Generator]] we convert the
   * [[catalyst.plans.logical.Project Project]] to a [[catalyst.plans.logical.Generate Generate]].
   */
  object ImplicitGenerate extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan transform {
      case Project(Seq(Alias(g: Generator, name)), child) =>
        Generate(g, join = false, outer = false,
          qualifier = None, UnresolvedAttribute(name) :: Nil, child)
      case Project(Seq(MultiAlias(g: Generator, names)), child) =>
        Generate(g, join = false, outer = false,
          qualifier = None, names.map(UnresolvedAttribute(_)), child)
    }
  }

  /**
   * Resolve the Generate, if the output names specified, we will take them, otherwise
   * we will try to provide the default names, which follow the same rule with Hive.
   */
  object ResolveGenerate extends Rule[LogicalPlan] {
    // Construct the output attributes for the generator,
    // The output attribute names can be either specified or
    // auto generated.
    private def makeGeneratorOutput(
        generator: Generator,
        generatorOutput: Seq[Attribute]): Seq[Attribute] = {
      val elementTypes = generator.elementTypes

      if (generatorOutput.length == elementTypes.length) {
        generatorOutput.zip(elementTypes).map {
          case (a, (t, nullable)) if !a.resolved =>
            AttributeReference(a.name, t, nullable)()
          case (a, _) => a
        }
      } else if (generatorOutput.length == 0) {
        elementTypes.zipWithIndex.map {
          // keep the default column names as Hive does _c0, _c1, _cN
          case ((t, nullable), i) => AttributeReference(s"_c$i", t, nullable)()
        }
      } else {
        throw new AnalysisException(
          s"""
             |The number of aliases supplied in the AS clause does not match
             |the number of columns output by the UDTF expected
             |${elementTypes.size} aliases but got ${generatorOutput.size}
           """.stripMargin)
      }
    }

    def apply(plan: LogicalPlan): LogicalPlan = plan transform {
      case p: Generate if !p.child.resolved || !p.generator.resolved => p
      case p: Generate if p.resolved == false =>
        // if the generator output names are not specified, we will use the default ones.
        Generate(
          p.generator,
          join = p.join,
          outer = p.outer,
          p.qualifier,
          makeGeneratorOutput(p.generator, p.generatorOutput), p.child)
    }
  }

  /**
   * Extracts [[WindowExpression]]s from the projectList of a [[Project]] operator and
   * aggregateExpressions of an [[Aggregate]] operator and creates individual [[Window]]
   * operators for every distinct [[WindowSpecDefinition]].
   *
   * This rule handles three cases:
   *  - A [[Project]] having [[WindowExpression]]s in its projectList;
   *  - An [[Aggregate]] having [[WindowExpression]]s in its aggregateExpressions.
   *  - An [[Filter]]->[[Aggregate]] pattern representing GROUP BY with a HAVING
   *    clause and the [[Aggregate]] has [[WindowExpression]]s in its aggregateExpressions.
   * Note: If there is a GROUP BY clause in the query, aggregations and corresponding
   * filters (expressions in the HAVING clause) should be evaluated before any
   * [[WindowExpression]]. If a query has SELECT DISTINCT, the DISTINCT part should be
   * evaluated after all [[WindowExpression]]s.
   *
   * For every case, the transformation works as follows:
   * 1. For a list of [[Expression]]s (a projectList or an aggregateExpressions), partitions
   *    it two lists of [[Expression]]s, one for all [[WindowExpression]]s and another for
   *    all regular expressions.
   * 2. For all [[WindowExpression]]s, groups them based on their [[WindowSpecDefinition]]s.
   * 3. For every distinct [[WindowSpecDefinition]], creates a [[Window]] operator and inserts
   *    it into the plan tree.
   */
  object ExtractWindowExpressions extends Rule[LogicalPlan] {
    def hasWindowFunction(projectList: Seq[NamedExpression]): Boolean =
      projectList.exists(hasWindowFunction)

    def hasWindowFunction(expr: NamedExpression): Boolean = {
      expr.find {
        case window: WindowExpression => true
        case _ => false
      }.isDefined
    }

    /**
     * From a Seq of [[NamedExpression]]s, extract window expressions and
     * other regular expressions.
     */
    def extract(
        expressions: Seq[NamedExpression]): (Seq[NamedExpression], Seq[NamedExpression]) = {
      // First, we simple partition the input expressions to two part, one having
      // WindowExpressions and another one without WindowExpressions.
      val (windowExpressions, regularExpressions) = expressions.partition(hasWindowFunction)

      // Then, we need to extract those regular expressions used in the WindowExpression.
      // For example, when we have col1 - Sum(col2 + col3) OVER (PARTITION BY col4 ORDER BY col5),
      // we need to make sure that col1 to col5 are all projected from the child of the Window
      // operator.
      val extractedExprBuffer = new ArrayBuffer[NamedExpression]()
      def extractExpr(expr: Expression): Expression = expr match {
        case ne: NamedExpression =>
          // If a named expression is not in regularExpressions, add extract it and replace it
          // with an AttributeReference.
          val missingExpr =
            AttributeSet(Seq(expr)) -- (regularExpressions ++ extractedExprBuffer)
          if (missingExpr.nonEmpty) {
            extractedExprBuffer += ne
          }
          ne.toAttribute
        case e: Expression if e.foldable =>
          e // No need to create an attribute reference if it will be evaluated as a Literal.
        case e: Expression =>
          // For other expressions, we extract it and replace it with an AttributeReference (with
          // an interal column name, e.g. "_w0").
          val withName = Alias(e, s"_w${extractedExprBuffer.length}")()
          extractedExprBuffer += withName
          withName.toAttribute
      }

      // Now, we extract expressions from windowExpressions by using extractExpr.
      val newWindowExpressions = windowExpressions.map {
        _.transform {
          // Extracts children expressions of a WindowFunction (input parameters of
          // a WindowFunction).
          case wf : WindowFunction =>
            val newChildren = wf.children.map(extractExpr(_))
            wf.withNewChildren(newChildren)

          // Extracts expressions from the partition spec and order spec.
          case wsc @ WindowSpecDefinition(partitionSpec, orderSpec, _) =>
            val newPartitionSpec = partitionSpec.map(extractExpr(_))
            val newOrderSpec = orderSpec.map { so =>
              val newChild = extractExpr(so.child)
              so.copy(child = newChild)
            }
            wsc.copy(partitionSpec = newPartitionSpec, orderSpec = newOrderSpec)

          // Extracts AggregateExpression. For example, for SUM(x) - Sum(y) OVER (...),
          // we need to extract SUM(x).
          case agg: AggregateExpression =>
            val withName = Alias(agg, s"_w${extractedExprBuffer.length}")()
            extractedExprBuffer += withName
            withName.toAttribute
        }.asInstanceOf[NamedExpression]
      }

      (newWindowExpressions, regularExpressions ++ extractedExprBuffer)
    }

    /**
     * Adds operators for Window Expressions. Every Window operator handles a single Window Spec.
     */
    def addWindow(windowExpressions: Seq[NamedExpression], child: LogicalPlan): LogicalPlan = {
      // First, we group window expressions based on their Window Spec.
      val groupedWindowExpression = windowExpressions.groupBy { expr =>
        val windowSpec = expr.collectFirst {
          case window: WindowExpression => window.windowSpec
        }
        windowSpec.getOrElse(
          failAnalysis(s"$windowExpressions does not have any WindowExpression."))
      }.toSeq

      // For every Window Spec, we add a Window operator and set currentChild as the child of it.
      var currentChild = child
      var i = 0
      while (i < groupedWindowExpression.size) {
        val (windowSpec, windowExpressions) = groupedWindowExpression(i)
        // Set currentChild to the newly created Window operator.
        currentChild = Window(currentChild.output, windowExpressions, windowSpec, currentChild)

        // Move to next WindowExpression.
        i += 1
      }

      // We return the top operator.
      currentChild
    }

    // We have to use transformDown at here to make sure the rule of
    // "Aggregate with Having clause" will be triggered.
    def apply(plan: LogicalPlan): LogicalPlan = plan transformDown {
      // Lookup WindowSpecDefinitions. This rule works with unresolved children.
      case WithWindowDefinition(windowDefinitions, child) =>
        child.transform {
          case plan => plan.transformExpressions {
            case UnresolvedWindowExpression(c, WindowSpecReference(windowName)) =>
              val errorMessage =
                s"Window specification $windowName is not defined in the WINDOW clause."
              val windowSpecDefinition =
                windowDefinitions
                  .get(windowName)
                  .getOrElse(failAnalysis(errorMessage))
              WindowExpression(c, windowSpecDefinition)
          }
        }

      // Aggregate with Having clause. This rule works with an unresolved Aggregate because
      // a resolved Aggregate will not have Window Functions.
      case f @ Filter(condition, a @ Aggregate(groupingExprs, aggregateExprs, child))
        if child.resolved &&
           hasWindowFunction(aggregateExprs) &&
           a.expressions.forall(_.resolved) =>
        val (windowExpressions, aggregateExpressions) = extract(aggregateExprs)
        // Create an Aggregate operator to evaluate aggregation functions.
        val withAggregate = Aggregate(groupingExprs, aggregateExpressions, child)
        // Add a Filter operator for conditions in the Having clause.
        val withFilter = Filter(condition, withAggregate)
        val withWindow = addWindow(windowExpressions, withFilter)

        // Finally, generate output columns according to the original projectList.
        val finalProjectList = aggregateExprs.map (_.toAttribute)
        Project(finalProjectList, withWindow)

      case p: LogicalPlan if !p.childrenResolved => p

      // Aggregate without Having clause.
      case a @ Aggregate(groupingExprs, aggregateExprs, child)
        if hasWindowFunction(aggregateExprs) &&
           a.expressions.forall(_.resolved) =>
        val (windowExpressions, aggregateExpressions) = extract(aggregateExprs)
        // Create an Aggregate operator to evaluate aggregation functions.
        val withAggregate = Aggregate(groupingExprs, aggregateExpressions, child)
        // Add Window operators.
        val withWindow = addWindow(windowExpressions, withAggregate)

        // Finally, generate output columns according to the original projectList.
        val finalProjectList = aggregateExprs.map (_.toAttribute)
        Project(finalProjectList, withWindow)

      // We only extract Window Expressions after all expressions of the Project
      // have been resolved.
      case p @ Project(projectList, child)
        if hasWindowFunction(projectList) && !p.expressions.exists(!_.resolved) =>
        val (windowExpressions, regularExpressions) = extract(projectList)
        // We add a project to get all needed expressions for window expressions from the child
        // of the original Project operator.
        val withProject = Project(regularExpressions, child)
        // Add Window operators.
        val withWindow = addWindow(windowExpressions, withProject)

        // Finally, generate output columns according to the original projectList.
        val finalProjectList = projectList.map (_.toAttribute)
        Project(finalProjectList, withWindow)
    }
  }
}

/**
 * Removes [[catalyst.plans.logical.Subquery Subquery]] operators from the plan.  Subqueries are
 * only required to provide scoping information for attributes and can be removed once analysis is
 * complete.
 */
object EliminateSubQueries extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case Subquery(_, child) => child
  }
}
