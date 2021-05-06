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

import scala.collection.mutable

import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.optimizer.{CombineUnions}
import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, Project, Union}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.catalyst.trees.TreePattern.UNION
import org.apache.spark.sql.errors.QueryCompilationErrors
import org.apache.spark.sql.types._
import org.apache.spark.sql.util.SchemaUtils

/**
 * Resolves different children of Union to a common set of columns.
 */
object ResolveUnion extends Rule[LogicalPlan] {
  /**
   * Adds missing fields recursively into given `col` expression, based on the target `StructType`.
   * This is called by `compareAndAddFields` when we find two struct columns with same name but
   * different nested fields. This method will find out the missing nested fields from `col` to
   * `target` struct and add these missing nested fields. Currently we don't support finding out
   * missing nested fields of struct nested in array or struct nested in map.
   */
  private def addFields(col: Expression, expectedFields: Seq[StructField]): Expression = {
    assert(col.dataType.isInstanceOf[StructType], "Only support StructType.")

    val resolver = conf.resolver
    val colType = col.dataType.asInstanceOf[StructType]
    val newStructFields = expectedFields.flatMap(expectedField => {
      val currentField = colType.fields.find(f => resolver(f.name, expectedField.name))

      val newExpression = (currentField, expectedField.dataType) match {
        case (Some(cf), expectedType: StructType) if cf.dataType.isInstanceOf[StructType] =>
            val extractedValue = ExtractValue(col, Literal(cf.name), resolver)
            val combinedStruct = addFields(extractedValue, expectedType.fields)
            if (extractedValue.nullable) {
              If(IsNull(extractedValue),
                Literal(null, combinedStruct.dataType),
                combinedStruct)
            } else {
              combinedStruct
            }
        case (Some(cf), _) =>
          ExtractValue(col, Literal(cf.name), resolver)
        case (_, expectedType) =>
          Literal(null, expectedType)
      }
      Literal(expectedField.name) :: newExpression :: Nil
    })
    CreateNamedStruct(newStructFields)
  }


  /**
   * This method will compare right to left plan's outputs. If there is one struct attribute
   * at right side has same name with left side struct attribute, but two structs are not the
   * same data type, i.e., some missing (nested) fields at right struct attribute, then this
   * method will try to add missing (nested) fields into the right attribute with null values.
   */
  private def compareAndAddFields(
      left: LogicalPlan,
      right: LogicalPlan,
      allowMissingCol: Boolean): (Seq[NamedExpression], Seq[NamedExpression]) = {
    val resolver = conf.resolver
    val leftOutputAttrs = left.output
    val rightOutputAttrs = right.output

    val aliased = mutable.ArrayBuffer.empty[Attribute]

    val rightProjectList = leftOutputAttrs.map { lattr =>
      val found = rightOutputAttrs.find { rattr => resolver(lattr.name, rattr.name) }
      if (found.isDefined) {
        val foundAttr = found.get
        val foundDt = foundAttr.dataType
        (foundDt, lattr.dataType) match {
          case (source: StructType, target: StructType)
              if allowMissingCol && !source.sameType(target) =>
            // Having an output with same name, but different struct type.
            // We need to add missing fields. Note that if there are deeply nested structs such as
            // nested struct of array in struct, we don't support to add missing deeply nested field
            // like that. We will sort columns in the struct expression to make sure two sides of
            // union have consistent schema.
            aliased += foundAttr
            val targetType = try {
              target.merge(source, conf.resolver)
            } catch { case e: Throwable =>
              throw QueryExecutionErrors.failedToMergeIncompatibleSchemasError(target, source, e)
            }
            Alias(addFields(foundAttr, targetType.fields.toSeq), foundAttr.name)()
          case _ =>
            // We don't need/try to add missing fields if:
            // 1. The attributes of left and right side are the same struct type
            // 2. The attributes are not struct types. They might be primitive types, or array, map
            //    types. We don't support adding missing fields of nested structs in array or map
            //    types now.
            // 3. `allowMissingCol` is disabled.
            foundAttr
        }
      } else {
        if (allowMissingCol) {
          Alias(Literal(null, lattr.dataType), lattr.name)()
        } else {
          throw QueryCompilationErrors.cannotResolveColumnNameAmongAttributesError(
            lattr, rightOutputAttrs)
        }
      }
    }

    (rightProjectList, aliased.toSeq)
  }

  private def unionTwoSides(
      left: LogicalPlan,
      right: LogicalPlan,
      allowMissingCol: Boolean): LogicalPlan = {
    // Builds a project list for `right` based on `left` output names
    val (rightProjectList, aliased) = compareAndAddFields(left, right, allowMissingCol)

    // Delegates failure checks to `CheckAnalysis`
    val notFoundAttrs = right.output.diff(rightProjectList ++ aliased)
    val rightChild = Project(rightProjectList ++ notFoundAttrs, right)

    // Builds a project for `logicalPlan` based on `right` output names, if allowing
    // missing columns.
    val leftChild = if (allowMissingCol) {
      // Add missing (nested) fields to left plan.
      val (leftProjectList, _) = compareAndAddFields(rightChild, left, allowMissingCol)
      if (leftProjectList.map(_.toAttribute) != left.output) {
        Project(leftProjectList, left)
      } else {
        left
      }
    } else {
      left
    }

    Union(leftChild, rightChild)
  }

  // Check column name duplication
  private def checkColumnNames(left: LogicalPlan, right: LogicalPlan): Unit = {
    val caseSensitiveAnalysis = conf.caseSensitiveAnalysis
    val leftOutputAttrs = left.output
    val rightOutputAttrs = right.output

    SchemaUtils.checkColumnNameDuplication(
      leftOutputAttrs.map(_.name),
      "in the left attributes",
      caseSensitiveAnalysis)
    SchemaUtils.checkColumnNameDuplication(
      rightOutputAttrs.map(_.name),
      "in the right attributes",
      caseSensitiveAnalysis)
  }

  def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsUpWithPruning(
    _.containsPattern(UNION), ruleId) {
    case e if !e.childrenResolved => e

    case Union(children, byName, allowMissingCol) if byName =>
      val union = children.reduceLeft { (left, right) =>
        checkColumnNames(left, right)
        unionTwoSides(left, right, allowMissingCol)
      }
      CombineUnions(union)
  }
}
