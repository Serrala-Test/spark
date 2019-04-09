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
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules.Rule

/**
 * This rule is a variant of [[PushDownPredicate]] which can handle
 * pushing down Left semi and Left Anti joins below the following operators.
 *  1) Project
 *  2) Window
 *  3) Union
 *  4) Aggregate
 *  5) Other permissible unary operators. please see [[PushDownPredicate.canPushThrough]].
 */
object PushDownLeftSemiAntiJoin extends Rule[LogicalPlan] with PredicateHelper {
  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    // LeftSemi/LeftAnti over Project
    case Join(p @ Project(pList, gChild), rightOp, LeftSemiOrAnti(joinType), joinCond, hint)
        if pList.forall(_.deterministic) &&
        !pList.exists(ScalarSubquery.hasCorrelatedScalarSubquery) &&
        canPushThroughCondition(Seq(gChild), joinCond, rightOp) =>
      if (joinCond.isEmpty) {
        // No join condition, just push down the Join below Project
        p.copy(child = Join(gChild, rightOp, joinType, joinCond, hint))
      } else {
        val aliasMap = PushDownPredicate.getAliasMap(p)
        val newJoinCond = if (aliasMap.nonEmpty) {
          Option(replaceAlias(joinCond.get, aliasMap))
        } else {
          joinCond
        }
        p.copy(child = Join(gChild, rightOp, joinType, newJoinCond, hint))
      }

    // LeftSemi/LeftAnti over Aggregate
    case join @ Join(agg: Aggregate, rightOp, LeftSemiOrAnti(_), _, _)
        if agg.aggregateExpressions.forall(_.deterministic) && agg.groupingExpressions.nonEmpty &&
        !agg.aggregateExpressions.exists(ScalarSubquery.hasCorrelatedScalarSubquery) =>
      val aliasMap = PushDownPredicate.getAliasMap(agg)
      val canPushDownPredicate = (predicate: Expression) => {
        val replaced = replaceAlias(predicate, aliasMap)
        predicate.references.nonEmpty &&
          replaced.references.subsetOf(agg.child.outputSet ++ rightOp.outputSet)
      }
      val makeJoinCondition = (predicates: Seq[Expression]) => {
        replaceAlias(predicates.reduce(And), aliasMap)
      }
      pushDownJoin(join, canPushDownPredicate, makeJoinCondition)

    // LeftSemi/LeftAnti over Window
    case join @ Join(w: Window, rightOp, LeftSemiOrAnti(_), _, _)
        if w.partitionSpec.forall(_.isInstanceOf[AttributeReference]) =>
      val partitionAttrs = AttributeSet(w.partitionSpec.flatMap(_.references)) ++ rightOp.outputSet
      pushDownJoin(join, _.references.subsetOf(partitionAttrs), _.reduce(And))

    // LeftSemi/LeftAnti over Union
    case Join(union: Union, rightOp, LeftSemiOrAnti(joinType), joinCond, hint)
        if canPushThroughCondition(union.children, joinCond, rightOp) =>
      if (joinCond.isEmpty) {
        // Push down the Join below Union
        val newGrandChildren = union.children.map { Join(_, rightOp, joinType, joinCond, hint) }
        union.withNewChildren(newGrandChildren)
      } else {
        val output = union.output
        val newGrandChildren = union.children.map { grandchild =>
          val newCond = joinCond.get transform {
            case e if output.exists(_.semanticEquals(e)) =>
              grandchild.output(output.indexWhere(_.semanticEquals(e)))
          }
          assert(newCond.references.subsetOf(grandchild.outputSet ++ rightOp.outputSet))
          Join(grandchild, rightOp, joinType, Option(newCond), hint)
        }
        union.withNewChildren(newGrandChildren)
      }

    // LeftSemi/LeftAnti over UnaryNode
    case join @ Join(u: UnaryNode, rightOp, LeftSemiOrAnti(_), _, _)
        if PushDownPredicate.canPushThrough(u) && u.expressions.forall(_.deterministic) =>
      val validAttrs = u.child.outputSet ++ rightOp.outputSet
      pushDownJoin(join, _.references.subsetOf(validAttrs), _.reduce(And))
  }

  /**
   * Check if we can safely push a join through a project or union by making sure that attributes
   * referred in join condition do not contain the same attributes as the plan they are moved
   * into. This can happen when both sides of join refers to the same source (self join). This
   * function makes sure that the join condition refers to attributes that are not ambiguous (i.e
   * present in both the legs of the join) or else the resultant plan will be invalid.
   */
  private def canPushThroughCondition(
      plans: Seq[LogicalPlan],
      condition: Option[Expression],
      rightOp: LogicalPlan): Boolean = {
    val attributes = AttributeSet(plans.flatMap(_.output))
    if (condition.isDefined) {
      val matched = condition.get.references.intersect(rightOp.outputSet).intersect(attributes)
      matched.isEmpty
    } else {
      true
    }
  }

  private def pushDownJoin(
      join: Join,
      canPushDownPredicate: Expression => Boolean,
      makeJoinCondition: Seq[Expression] => Expression): LogicalPlan = {
    assert(join.left.children.length == 1)

    if (join.condition.isEmpty) {
      join.left.withNewChildren(Seq(join.copy(left = join.left.children.head)))
    } else {
      val (pushDown, stayUp) = splitConjunctivePredicates(join.condition.get)
        .partition(canPushDownPredicate)

      // Check if the remaining predicates do not contain columns from the right hand side of the
      // join. Since the remaining predicates will be kept as a filter over the operator under join,
      // this check is necessary after the left-semi/anti join is pushed down. The reason is, for
      // this kind of join, we only output from the left leg of the join.
      val referRightSideCols = AttributeSet(stayUp.toSet).intersect(join.right.outputSet).nonEmpty

      if (pushDown.isEmpty || referRightSideCols)  {
        join
      } else {
        val newPlan = join.left.withNewChildren(Seq(join.copy(
          left = join.left.children.head, condition = Some(makeJoinCondition(pushDown)))))
        // If there is no more filter to stay up, return the new plan that has join pushed down.
        if (stayUp.isEmpty) {
          newPlan
        } else {
          join.joinType match {
            // In case of Left semi join, the part of the join condition which does not refer to
            // to attributes of the grandchild are kept as a Filter above.
            case LeftSemi => Filter(stayUp.reduce(And), newPlan)
            // In case of left-anti join, the join is pushed down only when the entire join
            // condition is eligible to be pushed down to preserve the semantics of left-anti join.
            case _ => join
          }
        }
      }
    }
  }
}

/**
 * This rule is a variant of [[PushPredicateThroughJoin]] which can handle
 * pushing down Left semi and Left Anti joins below a join operator. The
 * allowable join types are:
 *  1) Inner
 *  2) Cross
 *  3) LeftOuter
 *  4) RightOuter
 */
object PushLeftSemiLeftAntiThroughJoin extends Rule[LogicalPlan] with PredicateHelper {
  /**
   * Define an enumeration to identify whether a LeftSemi/LeftAnti join can be pushed down to
   * the left leg or the right leg of the join.
   */
  object pushdownDirection extends Enumeration {
    val toRightBranch, toLeftBranch, none = Value
  }

  /**
   * LeftSemi/LeftAnti joins are pushed down when its left child is a join operator
   * with a join type that is in the AllowedJoinTypes.
   */
  object AllowedJoinTypes {
    def unapply(joinType: JoinType): Option[JoinType] = joinType match {
      case Inner | Cross | LeftOuter | RightOuter => Some(joinType)
      case _ => None
    }
  }

  /**
   * Determine which side of the join a LeftSemi/LeftAnti join can be pushed to.
   */
  private def pushTo(leftChild: Join, rightChild: LogicalPlan, joinCond: Option[Expression]) = {
    val left = leftChild.left
    val right = leftChild.right
    val joinType = leftChild.joinType
    val rightOutput = rightChild.outputSet

    if (joinCond.nonEmpty) {
      val noPushdown = (pushdownDirection.none, None)
      val conditions = splitConjunctivePredicates(joinCond.get)
      val (leftConditions, rest) =
        conditions.partition(_.references.subsetOf(left.outputSet ++ rightOutput))
      val (rightConditions, commonConditions) =
        rest.partition(_.references.subsetOf(right.outputSet ++ rightOutput))

      if (rest.isEmpty && leftConditions.nonEmpty) {
        // When the join conditions can be computed based on the left leg of
        // leftsemi/anti join then push the leftsemi/anti join to the left side.
        (pushdownDirection.toLeftBranch, leftConditions.reduceLeftOption(And))
      } else if (leftConditions.isEmpty && rightConditions.nonEmpty && commonConditions.isEmpty) {
        // When the join conditions can be computed based on the attributes from right leg of
        // leftsemi/anti join then push the leftsemi/anti join to the right side.
        (pushdownDirection.toRightBranch, rightConditions.reduceLeftOption(And))
      } else {
        noPushdown
      }
    } else {
      /**
       * When the join condition is empty,
       * 1) if this is a left outer join or inner join, push leftsemi/anti join down
       *    to the left leg of join.
       * 2) if a right outer join, to the right leg of join,
       */
      val action = joinType match {
        case RightOuter =>
          pushdownDirection.toRightBranch
        case _: InnerLike | LeftOuter =>
          pushdownDirection.toLeftBranch
        case _ =>
          pushdownDirection.none
      }
      (action, None)
    }
  }

  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    // push LeftSemi/LeftAnti down into the join below
    case j @ Join(left @ Join(gLeft, gRight, AllowedJoinTypes(_), belowJoinCond, childHint),
    right, LeftSemiOrAnti(joinType), joinCond, parentHint) =>
      val belowJoinType = left.joinType
      val (action, newJoinCond) = pushTo(left, right, joinCond)

      action match {
        case pushdownDirection.toLeftBranch
          if (belowJoinType == LeftOuter || belowJoinType.isInstanceOf[InnerLike]) =>
          // push down leftsemi/anti join to the left table
          val newLeft = Join(gLeft, right, joinType, newJoinCond, parentHint)
          Join(newLeft, gRight, belowJoinType, belowJoinCond, childHint)
        case pushdownDirection.toRightBranch
          if (belowJoinType == RightOuter || belowJoinType.isInstanceOf[InnerLike]) =>
          // push down leftsemi/anti join to the right table
          val newRight = Join(gRight, right, joinType, newJoinCond, parentHint)
          Join(gLeft, newRight, belowJoinType, belowJoinCond, childHint)
        case _ =>
          // Do nothing
          j
      }
  }
}


