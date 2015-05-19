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

package org.apache.spark.sql

import scala.language.implicitConversions

import org.apache.spark.annotation.Experimental
import org.apache.spark.sql.catalyst.expressions._

/**
 * :: Experimental ::
 * A set of methods for window function definition for aggregate expressions.
 * For example:
 * {{{
 *   // predefine a window
 *   val w = partitionBy("name").orderBy("id")
 *
 *   df.select(
 *     first("value")
 *       over(w).as("first_value"),
 *     last("value")
 *       over(w).as("last_value"),
 *     avg("value")
 *       over(
 *       partitionBy("k1")
 *       .orderBy("k2", "k3")
 *       .rows
 *       .following(1)).as("avg_value"),
 *     max("value")
 *       .over(
 *       partitionBy("k2")
 *       .orderBy("k3")
 *       .range
 *       .between
 *       .preceding(4)
 *       .and
 *       .following(3)).as("max_value"))
 *
 * }}}
 *
 */
@Experimental
class WindowFunctionDefinition {
  private var column: Column = _
  private var partitionSpec: Seq[Expression] = Nil
  private var orderSpec: Seq[SortOrder] = Nil
  private var frame: WindowFrame = UnspecifiedFrame

  // Hint of when call the methods `.preceding(n)` `.currentRow()` `.following()`
  // if bindLower == true, then we will set the lower bound, otherwise, we should
  // set the upper bound for the Row/Range Frame.
  private var bindLower: Boolean = true

  private def this(
      column: Column = null,
      partitionSpec: Seq[Expression] = Nil,
      orderSpec: Seq[SortOrder] = Nil,
      frame: WindowFrame = UnspecifiedFrame,
      bindLower: Boolean = true) {
    this()
    this.column = column
    this.partitionSpec = partitionSpec
    this.orderSpec = orderSpec
    this.frame     = frame
    this.bindLower = bindLower
  }

  private[sql] def newColumn(c: Column): WindowFunctionDefinition = {
    new WindowFunctionDefinition(c, partitionSpec, orderSpec, frame, bindLower)
  }

  /**
   * Returns a new [[WindowFunctionDefinition]] partitioned by the specified column.
   * {{{
   *   // The following 2 are equivalent
   *   df.over(partitionBy("k1", "k2", ...))
   *   df.over(partitionBy($"K1", $"k2", ...))
   * }}}
   * @group window_funcs
   */
  @scala.annotation.varargs
  def partitionBy(colName: String, colNames: String*): WindowFunctionDefinition = {
    partitionBy((colName +: colNames).map(Column(_)): _*)
  }

  /**
   * Returns a new [[WindowFunctionDefinition]] partitioned by the specified column. For example:
   * {{{
   *   df.over(partitionBy($"col1", $"col2"))
   * }}}
   * @group window_funcs
   */
  @scala.annotation.varargs
  def partitionBy(cols: Column*): WindowFunctionDefinition = {
    new WindowFunctionDefinition(column, cols.map(_.expr), orderSpec, frame)
  }

  /**
   * Returns a new [[WindowFunctionDefinition]] sorted by the specified column within
   * the partition.
   * {{{
   *   // The following 2 are equivalent
   *   df.over(partitionBy("k1").orderBy("k2", "k3"))
   *   df.over(partitionBy("k1").orderBy($"k2", $"k3"))
   * }}}
   * @group window_funcs
   */
  @scala.annotation.varargs
  def orderBy(colName: String, colNames: String*): WindowFunctionDefinition = {
    orderBy((colName +: colNames).map(Column(_)): _*)
  }

  /**
   * Returns a new [[WindowFunctionDefinition]] sorted by the specified column within
   * the partition. For example
   * {{{
   *   df.over(partitionBy("k1").orderBy($"k2", $"k3"))
   * }}}
   * @group window_funcs
   */
  def orderBy(cols: Column*): WindowFunctionDefinition = {
    val sortOrder: Seq[SortOrder] = cols.map { col =>
      col.expr match {
        case expr: SortOrder =>
          expr
        case expr: Expression =>
          SortOrder(expr, Ascending)
      }
    }
    new WindowFunctionDefinition(column, partitionSpec, sortOrder, frame)
  }

  /**
   * Returns the current [[WindowFunctionDefinition]]. This is a dummy function,
   * which makes the usage more like the SQL.
   * For example:
   * {{{
   *   df.over(partitionBy("k1").orderBy($"k2", $"k3").range.between.preceding(1).and.currentRow)
   * }}}
   * @group window_funcs
   */
  def between: WindowFunctionDefinition = {
    assert(this.frame.isInstanceOf[SpecifiedWindowFrame], "Should be a WindowFrame.")
    new WindowFunctionDefinition(column, partitionSpec, orderSpec, frame, true)
  }

  /**
   * Returns a new [[WindowFunctionDefinition]] indicate that we need to specify the
   * upper bound.
   * For example:
   * {{{
   *   df.over(partitionBy("k1").orderBy($"k2", $"k3").range.between.preceding(3).and.currentRow)
   * }}}
   * @group window_funcs
   */
  def and: WindowFunctionDefinition = {
    new WindowFunctionDefinition(column, partitionSpec, orderSpec, frame, false)
  }

  /**
   * Returns a new Ranged [[WindowFunctionDefinition]].
   * For example:
   * {{{
   *   df.over(partitionBy("k1").orderBy($"k2", $"k3").range.between.preceding(3).and.currentRow)
   * }}}
   * @group window_funcs
   */
  def range: WindowFunctionDefinition = {
    new WindowFunctionDefinition(column, partitionSpec, orderSpec,
      SpecifiedWindowFrame(RangeFrame, UnboundedPreceding, UnboundedFollowing))
  }

  /**
   * Returns a new [[WindowFunctionDefinition]], with fixed number of records.
   * For example:
   * {{{
   *   df.over(partitionBy("k1").orderBy($"k2", $"k3").rows)
   * }}}
   * @group window_funcs
   */
  def rows: WindowFunctionDefinition = {
    new WindowFunctionDefinition(column, partitionSpec, orderSpec,
      SpecifiedWindowFrame(RowFrame, UnboundedPreceding, UnboundedFollowing))
  }

  /**
   * Returns a new [[WindowFunctionDefinition]], with position specified preceding of CURRENT_ROW.
   * It can be either Lower or Upper Bound position depends on the semantic context.
   * For example:
   * {{{
   *   // [CURRENT_ROW, ~)
   *   df.over(partitionBy("k1").orderBy("k2").rows.preceding(0))
   *   // [CURRENT_ROW - 1, ~)
   *   df.over(partitionBy("k1").orderBy("k2").rows.preceding(1))
   *   // [CURRENT_ROW - 3, CURRENT_ROW - 1]
   *   df.over(partitionBy("k1").orderBy("k2").rows.between.preceding(3).and.preceding(1))
   *   // (~, CURRENT_ROW - 1]
   *   df.over(partitionBy("k1").orderBy("k2").rows.between.unboundedPreceding.and.preceding(1))
   * }}}
   * @group window_funcs
   */
  def preceding(n: Int): WindowFunctionDefinition = {
    require(n >= 0, s"preceding(n) requires n greater than or equals 0, but got $n")
    val newFrame = frame match {
      case f: SpecifiedWindowFrame if bindLower =>
        if (n > 0) {
          f.copy(frameStart = ValuePreceding(n))
        } else {
          f.copy(frameStart = CurrentRow)
        }
      case f: SpecifiedWindowFrame =>
        if (n > 0) {
          f.copy(frameEnd = ValuePreceding(n))
        } else {
          f.copy(frameEnd = CurrentRow)
        }
      case f => throw new UnsupportedOperationException(s"preceding on $f")
    }
    new WindowFunctionDefinition(column, partitionSpec, orderSpec, newFrame, false)
  }

  /**
   * Returns a new [[WindowFunctionDefinition]], with lower position as unbounded.
   * For example:
   * {{{
   *   // (~, CURRENT_ROW]
   *   df.over(partitionBy("k1").orderBy("k2").rows.between.unboundedPreceding.and.currentRow)
   * }}}
   * @group window_funcs
   */
  def unboundedPreceding(): WindowFunctionDefinition = {
    val newFrame = frame match {
      case f : SpecifiedWindowFrame =>
        f.copy(frameStart = UnboundedPreceding)
      case f => throw new UnsupportedOperationException(s"unboundedPreceding on $f")
    }
    new WindowFunctionDefinition(column, partitionSpec, orderSpec, newFrame, false)
  }

  /**
   * Returns a new [[WindowFunctionDefinition]], with upper position as unbounded.
   * For example:
   * {{{
   *   // [CURRENT_ROW, ~)
   *   df.over(partitionBy("k1").orderBy("k2").rows.between.currentRow.and.unboundedFollowing)
   * }}}
   * @group window_funcs
   */
  def unboundedFollowing(): WindowFunctionDefinition = {
    val newFrame = frame match {
      case f : SpecifiedWindowFrame =>
        f.copy(frameEnd = UnboundedFollowing)
      case f => throw new UnsupportedOperationException(s"unboundedFollowing on $f")
    }
    new WindowFunctionDefinition(column, partitionSpec, orderSpec, newFrame, false)
  }

  /**
   * Returns a new [[WindowFunctionDefinition]], with position as CURRENT_ROW.
   * It can be either Lower or Upper Bound position, depends on the semantic context.
   * For example:
   * {{{
   *   // [CURRENT_ROW, ~)
   *   df.over(partitionBy("k1").orderBy("k2").rows.between.currentRow.and.unboundedFollowing)
   *   // [CURRENT_ROW - 3, CURRENT_ROW]
   *   df.over(partitionBy("k1").orderBy("k2").rows.between.preceding(3).and.currentRow)
   * }}}
   * @group window_funcs
   */
  def currentRow(): WindowFunctionDefinition = {
    val newFrame = frame match {
      case f : SpecifiedWindowFrame if bindLower =>
        f.copy(frameStart = CurrentRow)
      case f : SpecifiedWindowFrame =>
        f.copy(frameEnd = CurrentRow)
      case f => throw new UnsupportedOperationException(s"currentRow on $f")
    }
    new WindowFunctionDefinition(column, partitionSpec, orderSpec, newFrame, false)
  }

  /**
   * Returns a new [[WindowFunctionDefinition]], with position specified following of CURRENT_ROW.
   * It can be either Lower or Upper Bound position, depends on the semantic context.
   * For example:
   * {{{
   *   // [CURRENT_ROW, ~)
   *   df.over(partitionBy("k1").orderBy("k2").rows.following(0))
   *   // [CURRENT_ROW + 1, ~)
   *   df.over(partitionBy("k1").orderBy("k2").rows.following(1))
   *   // [CURRENT_ROW + 1, CURRENT_ROW + 3]
   *   df.over(partitionBy("k1").orderBy("k2").rows.between.following(1).and.following(3))
   *   // [CURRENT_ROW + 1, ~)
   *   df.over(partitionBy("k1").orderBy("k2").rows.between.following(1).and.unboundedFollowing)
   * }}}
   * @group window_funcs
   */
  def following(n: Int): WindowFunctionDefinition = {
    require(n >= 0, s"following(n) requires n greater than or equals 0, but got $n")
    val newFrame = frame match {
      case f: SpecifiedWindowFrame if bindLower =>
        if (n > 0) {
          f.copy(frameStart = ValueFollowing(n))
        } else {
          f.copy(frameStart = CurrentRow)
        }
      case f: SpecifiedWindowFrame =>
        if (n > 0) {
          f.copy(frameEnd = ValueFollowing(n))
        } else {
          f.copy(frameEnd = CurrentRow)
        }
      case f => throw new UnsupportedOperationException(s"following on $f")
    }
    new WindowFunctionDefinition(column, partitionSpec, orderSpec, newFrame, false)
  }

  /**
   * Convert the window definition into a Column object.
   * @group window_funcs
   */
  private[sql] def toColumn: Column = {
    if (column == null) {
      throw new AnalysisException("Window didn't bind with expression")
    }
    val windowExpr = column.expr match {
      case Average(child) => WindowExpression(
        UnresolvedWindowFunction("avg", child :: Nil),
        WindowSpecDefinition(partitionSpec, orderSpec, frame))
      case Sum(child) => WindowExpression(
        UnresolvedWindowFunction("sum", child :: Nil),
        WindowSpecDefinition(partitionSpec, orderSpec, frame))
      case Count(child) => WindowExpression(
        UnresolvedWindowFunction("count", child :: Nil),
        WindowSpecDefinition(partitionSpec, orderSpec, frame))
      case First(child) => WindowExpression(
        // TODO this is a hack for Hive UDAF first_value
        UnresolvedWindowFunction("first_value", child :: Nil),
        WindowSpecDefinition(partitionSpec, orderSpec, frame))
      case Last(child) => WindowExpression(
        // TODO this is a hack for Hive UDAF last_value
        UnresolvedWindowFunction("last_value", child :: Nil),
        WindowSpecDefinition(partitionSpec, orderSpec, frame))
      case Min(child) => WindowExpression(
        UnresolvedWindowFunction("min", child :: Nil),
        WindowSpecDefinition(partitionSpec, orderSpec, frame))
      case Max(child) => WindowExpression(
        UnresolvedWindowFunction("max", child :: Nil),
        WindowSpecDefinition(partitionSpec, orderSpec, frame))
      case wf: WindowFunction => WindowExpression(
        wf,
        WindowSpecDefinition(partitionSpec, orderSpec, frame))
      case x =>
        throw new UnsupportedOperationException(s"We don't support $x in window operation.")
    }
    new Column(windowExpr)
  }
}
