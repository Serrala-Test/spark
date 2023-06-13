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

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.expressions.{Alias, And, Attribute, AttributeReference, Expression, IsNotNull, MetadataAttribute, MonotonicallyIncreasingID, PredicateHelper}
import org.apache.spark.sql.catalyst.expressions.Literal.{FalseLiteral, TrueLiteral}
import org.apache.spark.sql.catalyst.plans.{FullOuter, Inner, JoinType, LeftAnti, LeftOuter, RightOuter}
import org.apache.spark.sql.catalyst.plans.logical.{AppendData, DeleteAction, Filter, HintInfo, InsertAction, Join, JoinHint, LogicalPlan, MergeAction, MergeIntoTable, MergeRows, NO_BROADCAST_AND_REPLICATION, Project, UpdateAction, WriteDelta}
import org.apache.spark.sql.catalyst.plans.logical.MergeRows.{Instruction, Keep, ROW_ID, Split}
import org.apache.spark.sql.catalyst.util.RowDeltaUtils.OPERATION_COLUMN
import org.apache.spark.sql.connector.catalog.SupportsRowLevelOperations
import org.apache.spark.sql.connector.write.{RowLevelOperationTable, SupportsDelta}
import org.apache.spark.sql.connector.write.RowLevelOperation.Command.MERGE
import org.apache.spark.sql.execution.datasources.v2.DataSourceV2Relation
import org.apache.spark.sql.types.IntegerType
import org.apache.spark.sql.util.CaseInsensitiveStringMap

/**
 * A rule that rewrites MERGE operations using plans that operate on individual or groups of rows.
 *
 * This rule assumes the commands have been fully resolved and all assignments have been aligned.
 */
object RewriteMergeIntoTable extends RewriteRowLevelCommand with PredicateHelper {

  private final val ROW_FROM_SOURCE = "__row_from_source"
  private final val ROW_FROM_TARGET = "__row_from_target"

  override def apply(plan: LogicalPlan): LogicalPlan = plan resolveOperators {
    case m @ MergeIntoTable(aliasedTable, source, cond, matchedActions, notMatchedActions,
        notMatchedBySourceActions) if m.resolved && m.rewritable && m.aligned &&
        matchedActions.isEmpty && notMatchedActions.size == 1 &&
        notMatchedBySourceActions.isEmpty =>

      EliminateSubqueryAliases(aliasedTable) match {
        case r: DataSourceV2Relation =>
          // NOT MATCHED conditions may only refer to columns in source so they can be pushed down
          val insertAction = notMatchedActions.head.asInstanceOf[InsertAction]
          val filteredSource = insertAction.condition match {
            case Some(insertCond) => Filter(insertCond, source)
            case None => source
          }

          // there is only one NOT MATCHED action, use a left anti join to remove any matching rows
          // and switch to using a regular append instead of a row-level MERGE operation
          // only unmatched source rows that match the condition are appended to the table
          val joinPlan = Join(filteredSource, r, LeftAnti, Some(cond), JoinHint.NONE)

          val output = insertAction.assignments.map(_.value)
          val outputColNames = r.output.map(_.name)
          val projectList = output.zip(outputColNames).map { case (expr, name) =>
            Alias(expr, name)()
          }
          val project = Project(projectList, joinPlan)

          AppendData.byPosition(r, project)

        case _ =>
          m
      }

    case m @ MergeIntoTable(aliasedTable, source, cond, matchedActions, notMatchedActions,
        notMatchedBySourceActions) if m.resolved && m.rewritable && m.aligned &&
        matchedActions.isEmpty && notMatchedBySourceActions.isEmpty =>

      EliminateSubqueryAliases(aliasedTable) match {
        case r: DataSourceV2Relation =>
          // there are only NOT MATCHED actions, use a left anti join to remove any matching rows
          // and switch to using a regular append instead of a row-level MERGE operation
          // only unmatched source rows that match action conditions are appended to the table
          val joinPlan = Join(source, r, LeftAnti, Some(cond), JoinHint.NONE)

          val notMatchedInstructions = notMatchedActions.map {
            case InsertAction(cond, assignments) =>
              Keep(cond.getOrElse(TrueLiteral), assignments.map(_.value))
            case other =>
              throw new AnalysisException(s"Unexpected WHEN NOT MATCHED action: $other")
          }

          val outputs = notMatchedInstructions.flatMap(_.outputs)

          // merge rows as there are multiple NOT MATCHED actions
          val mergeRows = MergeRows(
            isSourceRowPresent = TrueLiteral,
            isTargetRowPresent = FalseLiteral,
            matchedInstructions = Nil,
            notMatchedInstructions = notMatchedInstructions,
            notMatchedBySourceInstructions = Nil,
            checkCardinality = false,
            output = generateExpandOutput(r.output, outputs),
            joinPlan)

          AppendData.byPosition(r, mergeRows)

        case _ =>
          m
      }

    case m @ MergeIntoTable(aliasedTable, source, cond, matchedActions, notMatchedActions,
        notMatchedBySourceActions) if m.resolved && m.rewritable && m.aligned =>

      EliminateSubqueryAliases(aliasedTable) match {
        case r @ DataSourceV2Relation(tbl: SupportsRowLevelOperations, _, _, _, _) =>
          val table = buildOperationTable(tbl, MERGE, CaseInsensitiveStringMap.empty())
          table.operation match {
            case _: SupportsDelta =>
              buildWriteDeltaPlan(
                r, table, source, cond, matchedActions,
                notMatchedActions, notMatchedBySourceActions)
            case _ =>
              throw new AnalysisException("Group-based MERGE commands are not supported yet")
          }

        case _ =>
          m
      }
  }

  // build a rewrite plan for sources that support row deltas
  private def buildWriteDeltaPlan(
      relation: DataSourceV2Relation,
      operationTable: RowLevelOperationTable,
      source: LogicalPlan,
      cond: Expression,
      matchedActions: Seq[MergeAction],
      notMatchedActions: Seq[MergeAction],
      notMatchedBySourceActions: Seq[MergeAction]): WriteDelta = {

    val operation = operationTable.operation.asInstanceOf[SupportsDelta]

    // resolve all needed attrs (e.g. row ID and any required metadata attrs)
    val rowAttrs = relation.output
    val rowIdAttrs = resolveRowIdAttrs(relation, operation)
    val metadataAttrs = resolveRequiredMetadataAttrs(relation, operation)

    // construct a read relation and include all required metadata columns
    val readRelation = buildRelationWithAttrs(relation, operationTable, metadataAttrs, rowIdAttrs)

    // if there is no NOT MATCHED BY SOURCE clause, predicates of the ON condition that
    // reference only the target table can be pushed down
    val (filteredReadRelation, joinCond) = if (notMatchedBySourceActions.isEmpty) {
      pushDownTargetPredicates(readRelation, cond)
    } else {
      (readRelation, cond)
    }

    val checkCardinality = shouldCheckCardinality(matchedActions)

    val joinType = chooseWriteDeltaJoinType(notMatchedActions, notMatchedBySourceActions)
    val joinPlan = join(filteredReadRelation, source, joinType, joinCond, checkCardinality)

    val mergeRowsPlan = buildWriteDeltaMergeRowsPlan(
      readRelation, joinPlan, matchedActions, notMatchedActions,
      notMatchedBySourceActions, rowIdAttrs, checkCardinality,
      operation.representUpdateAsDeleteAndInsert)

    // build a plan to write the row delta to the table
    val writeRelation = relation.copy(table = operationTable)
    val projections = buildWriteDeltaProjections(mergeRowsPlan, rowAttrs, rowIdAttrs, metadataAttrs)
    WriteDelta(writeRelation, cond, mergeRowsPlan, relation, projections)
  }

  private def chooseWriteDeltaJoinType(
      notMatchedActions: Seq[MergeAction],
      notMatchedBySourceActions: Seq[MergeAction]): JoinType = {

    val unmatchedTargetRowsRequired = notMatchedBySourceActions.nonEmpty
    val unmatchedSourceRowsRequired = notMatchedActions.nonEmpty

    if (unmatchedTargetRowsRequired && unmatchedSourceRowsRequired) {
      FullOuter
    } else if (unmatchedTargetRowsRequired) {
      LeftOuter
    } else if (unmatchedSourceRowsRequired) {
      RightOuter
    } else {
      Inner
    }
  }

  private def buildWriteDeltaMergeRowsPlan(
      targetTable: DataSourceV2Relation,
      joinPlan: LogicalPlan,
      matchedActions: Seq[MergeAction],
      notMatchedActions: Seq[MergeAction],
      notMatchedBySourceActions: Seq[MergeAction],
      rowIdAttrs: Seq[Attribute],
      checkCardinality: Boolean,
      splitUpdates: Boolean): MergeRows = {

    val (metadataAttrs, rowAttrs) = targetTable.output.partition { attr =>
      MetadataAttribute.isValid(attr.metadata)
    }

    val originalRowIdValues = if (splitUpdates) {
      Seq.empty
    } else {
      // original row ID values must be preserved and passed back to the table to encode updates
      // if there are any assignments to row ID attributes, add extra columns for original values
      val updateAssignments = (matchedActions ++ notMatchedBySourceActions).flatMap {
        case UpdateAction(_, assignments) => assignments
        case _ => Nil
      }
      buildOriginalRowIdValues(rowIdAttrs, updateAssignments)
    }

    val matchedInstructions = matchedActions.map { action =>
      toInstruction(action, rowAttrs, rowIdAttrs, metadataAttrs, originalRowIdValues, splitUpdates)
    }

    val notMatchedInstructions = notMatchedActions.map { action =>
      toInstruction(action, rowAttrs, rowIdAttrs, metadataAttrs, originalRowIdValues, splitUpdates)
    }

    val notMatchedBySourceInstructions = notMatchedBySourceActions.map { action =>
      toInstruction(action, rowAttrs, rowIdAttrs, metadataAttrs, originalRowIdValues, splitUpdates)
    }

    val rowFromSourceAttr = resolveAttrRef(ROW_FROM_SOURCE, joinPlan)
    val rowFromTargetAttr = resolveAttrRef(ROW_FROM_TARGET, joinPlan)

    val outputs = matchedInstructions.flatMap(_.outputs) ++
      notMatchedInstructions.flatMap(_.outputs) ++
      notMatchedBySourceInstructions.flatMap(_.outputs)

    val operationTypeAttr = AttributeReference(OPERATION_COLUMN, IntegerType, nullable = false)()
    val originalRowIdAttrs = originalRowIdValues.map(_.toAttribute)
    val attrs = Seq(operationTypeAttr) ++ targetTable.output ++ originalRowIdAttrs

    MergeRows(
      isSourceRowPresent = IsNotNull(rowFromSourceAttr),
      isTargetRowPresent = IsNotNull(rowFromTargetAttr),
      matchedInstructions = matchedInstructions,
      notMatchedInstructions = notMatchedInstructions,
      notMatchedBySourceInstructions = notMatchedBySourceInstructions,
      checkCardinality = checkCardinality,
      output = generateExpandOutput(attrs, outputs),
      joinPlan)
  }

  private def pushDownTargetPredicates(
      targetTable: LogicalPlan,
      cond: Expression): (LogicalPlan, Expression) = {

    val predicates = splitConjunctivePredicates(cond)
    val (targetPredicates, joinPredicates) = predicates.partition { predicate =>
      predicate.references.subsetOf(targetTable.outputSet)
    }
    val targetCond = targetPredicates.reduceOption(And).getOrElse(TrueLiteral)
    val joinCond = joinPredicates.reduceOption(And).getOrElse(TrueLiteral)
    (Filter(targetCond, targetTable), joinCond)
  }

  private def join(
      targetTable: LogicalPlan,
      source: LogicalPlan,
      joinType: JoinType,
      joinCond: Expression,
      checkCardinality: Boolean): LogicalPlan = {

    // project an extra column to check if a target row exists after the join
    // if needed, project a synthetic row ID used to perform the cardinality check later
    val rowFromTarget = Alias(TrueLiteral, ROW_FROM_TARGET)()
    val targetTableProjExprs = if (checkCardinality) {
      val rowId = Alias(MonotonicallyIncreasingID(), ROW_ID)()
      targetTable.output ++ Seq(rowFromTarget, rowId)
    } else {
      targetTable.output :+ rowFromTarget
    }
    val targetTableProj = Project(targetTableProjExprs, targetTable)

    // project an extra column to check if a source row exists after the join
    val rowFromSource = Alias(TrueLiteral, ROW_FROM_SOURCE)()
    val sourceTableProjExprs = source.output :+ rowFromSource
    val sourceTableProj = Project(sourceTableProjExprs, source)

    // the cardinality check prohibits broadcasting and replicating the target table
    // all matches for a particular target row must be in one partition
    val joinHint = if (checkCardinality) {
      JoinHint(leftHint = Some(HintInfo(Some(NO_BROADCAST_AND_REPLICATION))), rightHint = None)
    } else {
      JoinHint.NONE
    }
    Join(targetTableProj, sourceTableProj, joinType, Some(joinCond), joinHint)
  }

  // skip the cardinality check in these cases:
  // - no MATCHED actions
  // - there is only one MATCHED action and it is an unconditional DELETE
  private def shouldCheckCardinality(matchedActions: Seq[MergeAction]): Boolean = {
    matchedActions match {
      case Nil => false
      case Seq(DeleteAction(None)) => false
      case _ => true
    }
  }

  // converts a MERGE action into an instruction on top of the joined plan for delta-based plans
  private def toInstruction(
      action: MergeAction,
      rowAttrs: Seq[Attribute],
      rowIdAttrs: Seq[Attribute],
      metadataAttrs: Seq[Attribute],
      originalRowIdValues: Seq[Alias],
      splitUpdates: Boolean): Instruction = {

    action match {
      case UpdateAction(cond, assignments) if splitUpdates =>
        val output = deltaDeleteOutput(rowAttrs, rowIdAttrs, metadataAttrs, originalRowIdValues)
        val otherOutput = deltaInsertOutput(assignments, metadataAttrs, originalRowIdValues)
        Split(cond.getOrElse(TrueLiteral), output, otherOutput)

      case UpdateAction(cond, assignments) =>
        val output = deltaUpdateOutput(assignments, metadataAttrs, originalRowIdValues)
        Keep(cond.getOrElse(TrueLiteral), output)

      case DeleteAction(cond) =>
        val output = deltaDeleteOutput(rowAttrs, rowIdAttrs, metadataAttrs, originalRowIdValues)
        Keep(cond.getOrElse(TrueLiteral), output)

      case InsertAction(cond, assignments) =>
        val output = deltaInsertOutput(assignments, metadataAttrs, originalRowIdValues)
        Keep(cond.getOrElse(TrueLiteral), output)

      case other =>
        throw new AnalysisException(s"Unexpected action: $other")
    }
  }
}
