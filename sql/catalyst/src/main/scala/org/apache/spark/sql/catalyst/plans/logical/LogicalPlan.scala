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

package org.apache.spark.sql.catalyst.plans.logical

import org.apache.spark.internal.Logging
import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.analysis._
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.QueryPlan
import org.apache.spark.sql.catalyst.plans.logical.statsEstimation.LogicalPlanStats
import org.apache.spark.sql.catalyst.trees.{CurrentOrigin, TreeNode}
import org.apache.spark.sql.types.StructType
import org.apache.spark.util.Utils


object LogicalPlan {

  private val resolveOperatorDepth = new ThreadLocal[Int] {
    override def initialValue(): Int = 0
  }

  def allowInvokingTransformsInAnalyzer[T](f: => T): T = {
    resolveOperatorDepth.set(resolveOperatorDepth.get + 1)
    try f finally {
      resolveOperatorDepth.set(resolveOperatorDepth.get - 1)
    }
  }
}


abstract class LogicalPlan
  extends QueryPlan[LogicalPlan]
  with LogicalPlanStats
  with QueryPlanConstraints
  with Logging {

  private var _analyzed: Boolean = false

  /**
   * Marks this plan as already analyzed. This should only be called by [[CheckAnalysis]].
   */
  private[catalyst] def setAnalyzed(): Unit = { _analyzed = true }

  /**
   * Returns true if this node and its children have already been gone through analysis and
   * verification.  Note that this is only an optimization used to avoid analyzing trees that
   * have already been analyzed, and can be reset by transformations.
   */
  def analyzed: Boolean = _analyzed

  /**
   * Returns a copy of this node where `rule` has been recursively applied first to all of its
   * children and then itself (post-order, bottom-up). When `rule` does not apply to a given node,
   * it is left unchanged.  This function is similar to `transformUp`, but skips sub-trees that
   * have already been marked as analyzed.
   *
   * @param rule the function use to transform this nodes children
   */
  def resolveOperators(rule: PartialFunction[LogicalPlan, LogicalPlan]): LogicalPlan = {
    if (!analyzed) {
      LogicalPlan.allowInvokingTransformsInAnalyzer {
        val afterRuleOnChildren = mapChildren(_.resolveOperators(rule))
        if (this fastEquals afterRuleOnChildren) {
          CurrentOrigin.withOrigin(origin) {
            rule.applyOrElse(this, identity[LogicalPlan])
          }
        } else {
          CurrentOrigin.withOrigin(origin) {
            rule.applyOrElse(afterRuleOnChildren, identity[LogicalPlan])
          }
        }
      }
    } else {
      this
    }
  }

  /** Similar to [[resolveOperators]], but does it top-down. */
  def resolveOperatorsDown(rule: PartialFunction[LogicalPlan, LogicalPlan]): LogicalPlan = {
    if (!analyzed) {
      LogicalPlan.allowInvokingTransformsInAnalyzer {
        val afterRule = CurrentOrigin.withOrigin(origin) {
          rule.applyOrElse(this, identity[LogicalPlan])
        }

        // Check if unchanged and then possibly return old copy to avoid gc churn.
        if (this fastEquals afterRule) {
          mapChildren(_.resolveOperatorsDown(rule))
        } else {
          afterRule.mapChildren(_.resolveOperatorsDown(rule))
        }
      }
    } else {
      this
    }
  }

  /**
   * Recursively transforms the expressions of a tree, skipping nodes that have already
   * been analyzed.
   */
  def resolveExpressions(r: PartialFunction[Expression, Expression]): LogicalPlan = {
    this resolveOperators  {
      case p => p.transformExpressions(r)
    }
  }

  protected def assertNotAnalysisRule(): Unit = {
    if (Utils.isTesting && LogicalPlan.resolveOperatorDepth.get == 0) {
      if (Thread.currentThread.getStackTrace.exists(_.getClassName.contains("Analyzer"))) {
        val e = new RuntimeException("This method should not be called in the analyzer")
        e.printStackTrace()
        throw e
      }
    }
  }

  /**
   * In analyzer, use [[resolveOperatorsDown()]] instead. If this is used in the analyzer,
   * an exception will be thrown in test mode. It is however OK to call this function within
   * the scope of a [[resolveOperatorsDown()]] call.
   * @see [[TreeNode.transformDown()]].
   */
  override def transformDown(rule: PartialFunction[LogicalPlan, LogicalPlan]): LogicalPlan = {
    assertNotAnalysisRule()
    super.transformDown(rule)
  }

  /**
   * Use [[resolveOperators()]] in the analyzer.
   * @see [[TreeNode.transformUp()]]
   */
  override def transformUp(rule: PartialFunction[LogicalPlan, LogicalPlan]): LogicalPlan = {
    assertNotAnalysisRule()
    super.transformUp(rule)
  }

  /**
   * Use [[resolveExpressions()]] in the analyzer.
   * @see [[QueryPlan.transformAllExpressions()]]
   */
  override def transformAllExpressions(rule: PartialFunction[Expression, Expression]): this.type = {
    assertNotAnalysisRule()
    super.transformAllExpressions(rule)
  }

  /** Returns true if this subtree has data from a streaming data source. */
  def isStreaming: Boolean = children.exists(_.isStreaming == true)

  override def verboseStringWithSuffix: String = {
    super.verboseString + statsCache.map(", " + _.toString).getOrElse("")
  }

  /**
   * Returns the maximum number of rows that this plan may compute.
   *
   * Any operator that a Limit can be pushed passed should override this function (e.g., Union).
   * Any operator that can push through a Limit should override this function (e.g., Project).
   */
  def maxRows: Option[Long] = None

  /**
   * Returns the maximum number of rows this plan may compute on each partition.
   */
  def maxRowsPerPartition: Option[Long] = maxRows

  /**
   * Returns true if this expression and all its children have been resolved to a specific schema
   * and false if it still contains any unresolved placeholders. Implementations of LogicalPlan
   * can override this (e.g.
   * [[org.apache.spark.sql.catalyst.analysis.UnresolvedRelation UnresolvedRelation]]
   * should return `false`).
   */
  lazy val resolved: Boolean = expressions.forall(_.resolved) && childrenResolved

  override protected def statePrefix = if (!resolved) "'" else super.statePrefix

  /**
   * Returns true if all its children of this query plan have been resolved.
   */
  def childrenResolved: Boolean = children.forall(_.resolved)

  /**
   * Resolves a given schema to concrete [[Attribute]] references in this query plan. This function
   * should only be called on analyzed plans since it will throw [[AnalysisException]] for
   * unresolved [[Attribute]]s.
   */
  def resolve(schema: StructType, resolver: Resolver): Seq[Attribute] = {
    schema.map { field =>
      resolve(field.name :: Nil, resolver).map {
        case a: AttributeReference => a
        case _ => sys.error(s"can not handle nested schema yet...  plan $this")
      }.getOrElse {
        throw new AnalysisException(
          s"Unable to resolve ${field.name} given [${output.map(_.name).mkString(", ")}]")
      }
    }
  }

  private[this] lazy val childAttributes = AttributeSeq(children.flatMap(_.output))

  private[this] lazy val outputAttributes = AttributeSeq(output)

  /**
   * Optionally resolves the given strings to a [[NamedExpression]] using the input from all child
   * nodes of this LogicalPlan. The attribute is expressed as
   * as string in the following form: `[scope].AttributeName.[nested].[fields]...`.
   */
  def resolveChildren(
      nameParts: Seq[String],
      resolver: Resolver): Option[NamedExpression] =
    childAttributes.resolve(nameParts, resolver)

  /**
   * Optionally resolves the given strings to a [[NamedExpression]] based on the output of this
   * LogicalPlan. The attribute is expressed as string in the following form:
   * `[scope].AttributeName.[nested].[fields]...`.
   */
  def resolve(
      nameParts: Seq[String],
      resolver: Resolver): Option[NamedExpression] =
    outputAttributes.resolve(nameParts, resolver)

  /**
   * Given an attribute name, split it to name parts by dot, but
   * don't split the name parts quoted by backticks, for example,
   * `ab.cd`.`efg` should be split into two parts "ab.cd" and "efg".
   */
  def resolveQuoted(
      name: String,
      resolver: Resolver): Option[NamedExpression] = {
    outputAttributes.resolve(UnresolvedAttribute.parseAttributeName(name), resolver)
  }

  /**
   * Refreshes (or invalidates) any metadata/data cached in the plan recursively.
   */
  def refresh(): Unit = children.foreach(_.refresh())

  /**
   * Returns the output ordering that this plan generates.
   */
  def outputOrdering: Seq[SortOrder] = Nil
}

/**
 * A logical plan node with no children.
 */
abstract class LeafNode extends LogicalPlan {
  override final def children: Seq[LogicalPlan] = Nil
  override def producedAttributes: AttributeSet = outputSet

  /** Leaf nodes that can survive analysis must define their own statistics. */
  def computeStats(): Statistics = throw new UnsupportedOperationException
}

/**
 * A logical plan node with single child.
 */
abstract class UnaryNode extends LogicalPlan {
  def child: LogicalPlan

  override final def children: Seq[LogicalPlan] = child :: Nil

  /**
   * Generates an additional set of aliased constraints by replacing the original constraint
   * expressions with the corresponding alias
   */
  protected def getAliasedConstraints(projectList: Seq[NamedExpression]): Set[Expression] = {
    var allConstraints = child.constraints.asInstanceOf[Set[Expression]]
    projectList.foreach {
      case a @ Alias(l: Literal, _) =>
        allConstraints += EqualTo(a.toAttribute, l)
      case a @ Alias(e, _) =>
        // For every alias in `projectList`, replace the reference in constraints by its attribute.
        allConstraints ++= allConstraints.map(_ transform {
          case expr: Expression if expr.semanticEquals(e) =>
            a.toAttribute
        })
        allConstraints += EqualNullSafe(e, a.toAttribute)
      case _ => // Don't change.
    }

    allConstraints -- child.constraints
  }

  override protected def validConstraints: Set[Expression] = child.constraints
}

/**
 * A logical plan node with a left and right child.
 */
abstract class BinaryNode extends LogicalPlan {
  def left: LogicalPlan
  def right: LogicalPlan

  override final def children: Seq[LogicalPlan] = Seq(left, right)
}

abstract class OrderPreservingUnaryNode extends UnaryNode {
  override final def outputOrdering: Seq[SortOrder] = child.outputOrdering
}
