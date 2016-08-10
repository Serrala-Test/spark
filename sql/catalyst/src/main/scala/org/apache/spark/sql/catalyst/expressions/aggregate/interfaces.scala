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

package org.apache.spark.sql.catalyst.expressions.aggregate

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.UnresolvedAttribute
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.codegen.CodegenFallback
import org.apache.spark.sql.types._

/** The mode of an [[AggregateFunction]]. */
sealed trait AggregateMode

/**
 * An [[AggregateFunction]] with [[Partial]] mode is used for partial aggregation.
 * This function updates the given aggregation buffer with the original input of this
 * function. When it has processed all input rows, the aggregation buffer is returned.
 */
case object Partial extends AggregateMode

/**
 * An [[AggregateFunction]] with [[PartialMerge]] mode is used to merge aggregation buffers
 * containing intermediate results for this function.
 * This function updates the given aggregation buffer by merging multiple aggregation buffers.
 * When it has processed all input rows, the aggregation buffer is returned.
 */
case object PartialMerge extends AggregateMode

/**
 * An [[AggregateFunction]] with [[Final]] mode is used to merge aggregation buffers
 * containing intermediate results for this function and then generate final result.
 * This function updates the given aggregation buffer by merging multiple aggregation buffers.
 * When it has processed all input rows, the final result of this function is returned.
 */
case object Final extends AggregateMode

/**
 * An [[AggregateFunction]] with [[Complete]] mode is used to evaluate this function directly
 * from original input rows without any partial aggregation.
 * This function updates the given aggregation buffer with the original input of this
 * function. When it has processed all input rows, the final result of this function is returned.
 */
case object Complete extends AggregateMode

/**
 * A place holder expressions used in code-gen, it does not change the corresponding value
 * in the row.
 */
case object NoOp extends Expression with Unevaluable {
  override def nullable: Boolean = true
  override def dataType: DataType = NullType
  override def children: Seq[Expression] = Nil
}

object AggregateExpression {
  def apply(
      aggregateFunction: AggregateFunction,
      mode: AggregateMode,
      isDistinct: Boolean): AggregateExpression = {
    AggregateExpression(
      aggregateFunction,
      mode,
      isDistinct,
      NamedExpression.newExprId)
  }
}

/**
 * A container for an [[AggregateFunction]] with its [[AggregateMode]] and a field
 * (`isDistinct`) indicating if DISTINCT keyword is specified for this function.
 */
case class AggregateExpression(
    aggregateFunction: AggregateFunction,
    mode: AggregateMode,
    isDistinct: Boolean,
    resultId: ExprId)
  extends Expression
  with Unevaluable {

  lazy val resultAttribute: Attribute = if (aggregateFunction.resolved) {
    AttributeReference(
      aggregateFunction.toString,
      aggregateFunction.dataType,
      aggregateFunction.nullable)(exprId = resultId)
  } else {
    // This is a bit of a hack.  Really we should not be constructing this container and reasoning
    // about datatypes / aggregation mode until after we have finished analysis and made it to
    // planning.
    UnresolvedAttribute(aggregateFunction.toString)
  }

  // We compute the same thing regardless of our final result.
  override lazy val canonicalized: Expression =
    AggregateExpression(
      aggregateFunction.canonicalized.asInstanceOf[AggregateFunction],
      mode,
      isDistinct,
      ExprId(0))

  override def children: Seq[Expression] = aggregateFunction :: Nil
  override def dataType: DataType = aggregateFunction.dataType
  override def foldable: Boolean = false
  override def nullable: Boolean = aggregateFunction.nullable

  override def references: AttributeSet = {
    val childReferences = mode match {
      case Partial | Complete => aggregateFunction.references.toSeq
      case PartialMerge | Final => aggregateFunction.aggBufferAttributes
    }

    AttributeSet(childReferences)
  }

  override def toString: String = {
    val prefix = mode match {
      case Partial => "partial_"
      case PartialMerge => "merge_"
      case Final | Complete => ""
    }
    prefix + aggregateFunction.toAggString(isDistinct)
  }

  override def sql: String = aggregateFunction.sql(isDistinct)
}

/**
 * AggregateFunction is the superclass of two aggregation function interfaces:
 *
 *  - [[ImperativeAggregate]] is for aggregation functions that are specified in terms of
 *    initialize(), update(), and merge() functions that operate on Row-based aggregation buffers.
 *  - [[DeclarativeAggregate]] is for aggregation functions that are specified using
 *    Catalyst expressions.
 *
 * In both interfaces, aggregates must define the schema ([[aggBufferSchema]]) and attributes
 * ([[aggBufferAttributes]]) of an aggregation buffer which is used to hold partial aggregate
 * results. At runtime, multiple aggregate functions are evaluated by the same operator using a
 * combined aggregation buffer which concatenates the aggregation buffers of the individual
 * aggregate functions.
 *
 * Code which accepts [[AggregateFunction]] instances should be prepared to handle both types of
 * aggregate functions.
 */
sealed abstract class AggregateFunction extends Expression with ImplicitCastInputTypes {

  /** An aggregate function is not foldable. */
  final override def foldable: Boolean = false

  /** The schema of the aggregation buffer. */
  def aggBufferSchema: StructType

  /** Attributes of fields in aggBufferSchema. */
  def aggBufferAttributes: Seq[AttributeReference]

  /**
   * Attributes of fields in input aggregation buffers (immutable aggregation buffers that are
   * merged with mutable aggregation buffers in the merge() function or merge expressions).
   * These attributes are created automatically by cloning the [[aggBufferAttributes]].
   */
  def inputAggBufferAttributes: Seq[AttributeReference]

  /**
   * Indicates if this function supports partial aggregation.
   * Currently Hive UDAF is the only one that doesn't support partial aggregation.
   */
  def supportsPartial: Boolean = true

  /**
   * Result of the aggregate function when the input is empty. This is currently only used for the
   * proper rewriting of distinct aggregate functions.
   */
  def defaultResult: Option[Literal] = None

  /**
   * Wraps this [[AggregateFunction]] in an [[AggregateExpression]] because
   * [[AggregateExpression]] is the container of an [[AggregateFunction]], aggregation mode,
   * and the flag indicating if this aggregation is distinct aggregation or not.
   * An [[AggregateFunction]] should not be used without being wrapped in
   * an [[AggregateExpression]].
   */
  def toAggregateExpression(): AggregateExpression = toAggregateExpression(isDistinct = false)

  /**
   * Wraps this [[AggregateFunction]] in an [[AggregateExpression]] and set isDistinct
   * field of the [[AggregateExpression]] to the given value because
   * [[AggregateExpression]] is the container of an [[AggregateFunction]], aggregation mode,
   * and the flag indicating if this aggregation is distinct aggregation or not.
   * An [[AggregateFunction]] should not be used without being wrapped in
   * an [[AggregateExpression]].
   */
  def toAggregateExpression(isDistinct: Boolean): AggregateExpression = {
    AggregateExpression(aggregateFunction = this, mode = Complete, isDistinct = isDistinct)
  }

  def sql(isDistinct: Boolean): String = {
    val distinct = if (isDistinct) "DISTINCT " else ""
    s"$prettyName($distinct${children.map(_.sql).mkString(", ")})"
  }

  /** String representation used in explain plans. */
  def toAggString(isDistinct: Boolean): String = {
    val start = if (isDistinct) "(distinct " else "("
    prettyName + flatArguments.mkString(start, ", ", ")")
  }
}

/**
 * API for aggregation functions that are expressed in terms of imperative doInitialize(),
 * doUpdate(), doMerge() and doComplete() functions which operate on Row-based aggregation buffers.
 */
abstract class ImperativeAggregate extends AggregateFunction with CodegenFallback {

  /**
   * The aggregation operator keeps a large shared mutable buffer row for all aggregate functions,
   * each aggregate function should only access a slice of this shared buffer.
   */
  private var mutableBufferRow: SlicedMutableRow = _

  /**
   * During partial aggregation, the input buffer row to be merged is shared among all aggregate
   * functions, each aggregate function should only access a slice of this input buffer.
   */
  private var inputBufferRow: SlicedInternalRow = _

  /**
   * Set the offset of this function's start buffer value in the underlying shared mutable
   * aggregation buffer.
   *
   * For example, we have two aggregate functions `avg(x)` and `avg(y)`, which share the same
   * aggregation buffer. In this shared buffer, the position of the start buffer value of `avg(x)`
   * will be 0 and the position of the start buffer value of `avg(y)` will be 2:
   * {{{
   *          avg(x) mutable buffer offset is 0
   *                  |
   *                  v
   *                  +--------+--------+--------+--------+
   *                  |  sum1  | count1 |  sum2  | count2 |
   *                  +--------+--------+--------+--------+
   *                                    ^
   *                                    |
   *                     avg(y) mutable buffer offset is 2
   * }}}
   */
  final def setMutableBufferOffset(offset: Int): Unit = {
    mutableBufferRow = new SlicedMutableRow(offset, aggBufferAttributes.length)
  }

  /**
   * Set the offset of this function's start buffer value in the underlying shared input aggregation
   * buffer. An input aggregation buffer is used when we merge two aggregation buffers together in
   * the `merge()` function and is immutable (we merge an input aggregation buffer and a mutable
   * aggregation buffer and then store the new buffer values to the mutable aggregation buffer).
   *
   * An input aggregation buffer may contain extra fields, such as grouping keys, at its start, so
   * mutable buffer offset and input buffer offset are often different.
   *
   * For example, say we have a grouping expression, `key`, and two aggregate functions,
   * `avg(x)` and `avg(y)`. In the shared input aggregation buffer, the position of the start
   * buffer value of `avg(x)` will be 1 and the position of the start buffer value of `avg(y)`
   * will be 3 (position 0 is used for the value of `key`):
   * {{{
   *          avg(x) input buffer offset is 1
   *                   |
   *                   v
   *          +--------+--------+--------+--------+--------+
   *          |  key   |  sum1  | count1 |  sum2  | count2 |
   *          +--------+--------+--------+--------+--------+
   *                                     ^
   *                                     |
   *                       avg(y) input buffer offset is 3
   * }}}
   */
  final def setInputBufferOffset(offset: Int): Unit = {
    inputBufferRow = new SlicedInternalRow(offset, aggBufferAttributes.length)
  }

  final def initialize(mutableAggBuffer: MutableRow): Unit = {
    doInitialize(mutableBufferRow.target(mutableAggBuffer))
  }

  final def update(mutableAggBuffer: MutableRow, inputRow: InternalRow): Unit = {
    doUpdate(mutableBufferRow.target(mutableAggBuffer), inputRow)
  }

  final def merge(mutableAggBuffer: MutableRow, inputAggBuffer: InternalRow): Unit = {
    doMerge(mutableBufferRow.target(mutableAggBuffer), inputBufferRow.target(inputAggBuffer))
  }

  final override def eval(aggBuffer: InternalRow): Any = {
    assert(aggBuffer.isInstanceOf[MutableRow])
    doEval(mutableBufferRow.target(aggBuffer.asInstanceOf[MutableRow]))
  }

  // Note: although all subclasses implement inputAggBufferAttributes by simply cloning
  // aggBufferAttributes, that common clone code cannot be placed here in the abstract
  // ImperativeAggregate class, since that will lead to initialization ordering issues.

  /**
   * Initializes the mutable aggregation buffer.
   */
  protected def doInitialize(mutableAggBuffer: MutableRow): Unit

  /**
   * Updates its aggregation buffer, based on the given `inputRow`.
   */
  protected def doUpdate(mutableAggBuffer: MutableRow, inputRow: InternalRow): Unit

  /**
   * Combines new intermediate results from the `inputAggBuffer` with the existing intermediate
   * results in the `mutableAggBuffer.`
   */
  protected def doMerge(mutableAggBuffer: MutableRow, inputAggBuffer: InternalRow): Unit

  /**
   * Evaluates the final result of this aggregate function, based on the mutable aggregation buffer.
   */
  protected def doEval(aggBuffer: InternalRow): Any
}

/**
 * API for aggregation functions that are expressed in terms of Catalyst expressions.
 *
 * When implementing a new expression-based aggregate function, start by implementing
 * `bufferAttributes`, defining attributes for the fields of the mutable aggregation buffer. You
 * can then use these attributes when defining `updateExpressions`, `mergeExpressions`, and
 * `evaluateExpressions`.
 *
 * Please note that children of an aggregate function can be unresolved (it will happen when
 * we create this function in DataFrame API). So, if there is any fields in
 * the implemented class that need to access fields of its children, please make
 * those fields `lazy val`s.
 */
abstract class DeclarativeAggregate
  extends AggregateFunction
  with Serializable
  with Unevaluable {

  /**
   * Expressions for initializing empty aggregation buffers.
   */
  val initialValues: Seq[Expression]

  /**
   * Expressions for updating the mutable aggregation buffer based on an input row.
   */
  val updateExpressions: Seq[Expression]

  /**
   * A sequence of expressions for merging two aggregation buffers together. When defining these
   * expressions, you can use the syntax `attributeName.left` and `attributeName.right` to refer
   * to the attributes corresponding to each of the buffers being merged (this magic is enabled
   * by the [[RichAttribute]] implicit class).
   */
  val mergeExpressions: Seq[Expression]

  /**
   * An expression which returns the final value for this aggregate function. Its data type should
   * match this expression's [[dataType]].
   */
  val evaluateExpression: Expression

  /** An expression-based aggregate's bufferSchema is derived from bufferAttributes. */
  final override def aggBufferSchema: StructType = StructType.fromAttributes(aggBufferAttributes)

  final lazy val inputAggBufferAttributes: Seq[AttributeReference] =
    aggBufferAttributes.map(_.newInstance())

  /**
   * A helper class for representing an attribute used in merging two
   * aggregation buffers. When merging two buffers, `bufferLeft` and `bufferRight`,
   * we merge buffer values and then update bufferLeft. A [[RichAttribute]]
   * of an [[AttributeReference]] `a` has two functions `left` and `right`,
   * which represent `a` in `bufferLeft` and `bufferRight`, respectively.
   */
  implicit class RichAttribute(a: AttributeReference) {
    /** Represents this attribute at the mutable buffer side. */
    def left: AttributeReference = a

    /** Represents this attribute at the input buffer side (the data value is read-only). */
    def right: AttributeReference = inputAggBufferAttributes(aggBufferAttributes.indexOf(a))
  }
}
