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

package org.apache.spark.sql.catalyst.expressions

import com.clearspring.analytics.stream.cardinality.HyperLogLog

import org.apache.spark.sql.catalyst.trees
import org.apache.spark.sql.catalyst.errors.TreeNodeException
import org.apache.spark.sql.catalyst.analysis.TypeCheckResult
import org.apache.spark.sql.catalyst.util.TypeUtils
import org.apache.spark.sql.types._
import org.apache.spark.util.collection.OpenHashSet

abstract class AggregateExpression extends Expression {
  self: Product =>

  /**
   * Creates a new instance that can be used to compute this aggregate expression for a group
   * of input rows/
   */
  def newInstance(): AggregateFunction

  /**
   * [[AggregateExpression.eval]] should never be invoked because [[AggregateExpression]]'s are
   * replaced with a physical aggregate operator at runtime.
   */
  override def eval(input: InternalRow = null): Any =
    throw new TreeNodeException(this, s"No function to evaluate expression. type: ${this.nodeName}")
}

/**
 * Represents an aggregation that has been rewritten to be performed in two steps.
 *
 * @param finalEvaluation an aggregate expression that evaluates to same final result as the
 *                        original aggregation.
 * @param partialEvaluations A sequence of [[NamedExpression]]s that can be computed on partial
 *                           data sets and are required to compute the `finalEvaluation`.
 */
case class SplitEvaluation(
    finalEvaluation: Expression,
    partialEvaluations: Seq[NamedExpression])

/**
 * An [[AggregateExpression]] that can be partially computed without seeing all relevant tuples.
 * These partial evaluations can then be combined to compute the actual answer.
 */
abstract class PartialAggregate extends AggregateExpression {
  self: Product =>

  /**
   * Returns a [[SplitEvaluation]] that computes this aggregation using partial aggregation.
   */
  def asPartial: SplitEvaluation
}

/**
 * A specific implementation of an aggregate function. Used to wrap a generic
 * [[AggregateExpression]] with an algorithm that will be used to compute one specific result.
 */
abstract class AggregateFunction
  extends AggregateExpression with Serializable with trees.LeafNode[Expression] {
  self: Product =>

  /** Base should return the generic aggregate expression that this function is computing */
  val base: AggregateExpression

  override def nullable: Boolean = base.nullable
  override def dataType: DataType = base.dataType

  def update(input: InternalRow): Unit

  // Do we really need this?
  override def newInstance(): AggregateFunction = {
    makeCopy(productIterator.map { case a: AnyRef => a }.toArray)
  }
}

case class Min(child: Expression) extends PartialAggregate with trees.UnaryNode[Expression] {

  override def nullable: Boolean = true
  override def dataType: DataType = child.dataType
  override def toString: String = s"MIN($child)"

  override def asPartial: SplitEvaluation = {
    val partialMin = Alias(Min(child), "PartialMin")()
    SplitEvaluation(Min(partialMin.toAttribute), partialMin :: Nil)
  }

  override def newInstance(): MinFunction = new MinFunction(child, this)

  override def checkInputDataTypes(): TypeCheckResult =
    TypeUtils.checkForOrderingExpr(child.dataType, "function min")
}

case class MinFunction(expr: Expression, base: AggregateExpression) extends AggregateFunction {
  def this() = this(null, null) // Required for serialization.

  val currentMin: MutableLiteral = MutableLiteral(null, expr.dataType)
  val cmp = GreaterThan(currentMin, expr)

  override def update(input: InternalRow): Unit = {
    if (currentMin.value == null) {
      currentMin.value = expr.eval(input)
    } else if (cmp.eval(input) == true) {
      currentMin.value = expr.eval(input)
    }
  }

  override def eval(input: InternalRow): Any = currentMin.value
}

case class Max(child: Expression) extends PartialAggregate with trees.UnaryNode[Expression] {

  override def nullable: Boolean = true
  override def dataType: DataType = child.dataType
  override def toString: String = s"MAX($child)"

  override def asPartial: SplitEvaluation = {
    val partialMax = Alias(Max(child), "PartialMax")()
    SplitEvaluation(Max(partialMax.toAttribute), partialMax :: Nil)
  }

  override def newInstance(): MaxFunction = new MaxFunction(child, this)

  override def checkInputDataTypes(): TypeCheckResult =
    TypeUtils.checkForOrderingExpr(child.dataType, "function max")
}

case class MaxFunction(expr: Expression, base: AggregateExpression) extends AggregateFunction {
  def this() = this(null, null) // Required for serialization.

  val currentMax: MutableLiteral = MutableLiteral(null, expr.dataType)
  val cmp = LessThan(currentMax, expr)

  override def update(input: InternalRow): Unit = {
    if (currentMax.value == null) {
      currentMax.value = expr.eval(input)
    } else if (cmp.eval(input) == true) {
      currentMax.value = expr.eval(input)
    }
  }

  override def eval(input: InternalRow): Any = currentMax.value
}

case class Count(child: Expression) extends PartialAggregate with trees.UnaryNode[Expression] {

  override def nullable: Boolean = false
  override def dataType: LongType.type = LongType
  override def toString: String = s"COUNT($child)"

  override def asPartial: SplitEvaluation = {
    val partialCount = Alias(Count(child), "PartialCount")()
    SplitEvaluation(Coalesce(Seq(Sum(partialCount.toAttribute), Literal(0L))), partialCount :: Nil)
  }

  override def newInstance(): CountFunction = new CountFunction(child, this)
}

case class CountFunction(expr: Expression, base: AggregateExpression) extends AggregateFunction {
  def this() = this(null, null) // Required for serialization.

  var count: Long = _

  override def update(input: InternalRow): Unit = {
    val evaluatedExpr = expr.eval(input)
    if (evaluatedExpr != null) {
      count += 1L
    }
  }

  override def eval(input: InternalRow): Any = count
}

case class CountDistinct(expressions: Seq[Expression]) extends PartialAggregate {
  def this() = this(null)

  override def children: Seq[Expression] = expressions

  override def nullable: Boolean = false
  override def dataType: DataType = LongType
  override def toString: String = s"COUNT(DISTINCT ${expressions.mkString(",")})"
  override def newInstance(): CountDistinctFunction = new CountDistinctFunction(expressions, this)

  override def asPartial: SplitEvaluation = {
    val partialSet = Alias(CollectHashSet(expressions), "partialSets")()
    SplitEvaluation(
      CombineSetsAndCount(partialSet.toAttribute),
      partialSet :: Nil)
  }
}

case class CountDistinctFunction(
    @transient expr: Seq[Expression],
    @transient base: AggregateExpression)
  extends AggregateFunction {

  def this() = this(null, null) // Required for serialization.

  val seen = new OpenHashSet[Any]()

  @transient
  val distinctValue = new InterpretedProjection(expr)

  override def update(input: InternalRow): Unit = {
    val evaluatedExpr = distinctValue(input)
    if (!evaluatedExpr.anyNull) {
      seen.add(evaluatedExpr)
    }
  }

  override def eval(input: InternalRow): Any = seen.size.toLong
}

case class CollectHashSet(expressions: Seq[Expression]) extends AggregateExpression {
  def this() = this(null)

  override def children: Seq[Expression] = expressions
  override def nullable: Boolean = false
  override def dataType: OpenHashSetUDT = new OpenHashSetUDT(expressions.head.dataType)
  override def toString: String = s"AddToHashSet(${expressions.mkString(",")})"
  override def newInstance(): CollectHashSetFunction =
    new CollectHashSetFunction(expressions, this)
}

case class CollectHashSetFunction(
    @transient expr: Seq[Expression],
    @transient base: AggregateExpression)
  extends AggregateFunction {

  def this() = this(null, null) // Required for serialization.

  val seen = new OpenHashSet[Any]()

  @transient
  val distinctValue = new InterpretedProjection(expr)

  override def update(input: InternalRow): Unit = {
    val evaluatedExpr = distinctValue(input)
    if (!evaluatedExpr.anyNull) {
      seen.add(evaluatedExpr)
    }
  }

  override def eval(input: InternalRow): Any = {
    seen
  }
}

case class CombineSetsAndCount(inputSet: Expression) extends AggregateExpression {
  def this() = this(null)

  override def children: Seq[Expression] = inputSet :: Nil
  override def nullable: Boolean = false
  override def dataType: DataType = LongType
  override def toString: String = s"CombineAndCount($inputSet)"
  override def newInstance(): CombineSetsAndCountFunction = {
    new CombineSetsAndCountFunction(inputSet, this)
  }
}

case class CombineSetsAndCountFunction(
    @transient inputSet: Expression,
    @transient base: AggregateExpression)
  extends AggregateFunction {

  def this() = this(null, null) // Required for serialization.

  val seen = new OpenHashSet[Any]()

  override def update(input: InternalRow): Unit = {
    val inputSetEval = inputSet.eval(input).asInstanceOf[OpenHashSet[Any]]
    val inputIterator = inputSetEval.iterator
    while (inputIterator.hasNext) {
      seen.add(inputIterator.next)
    }
  }

  override def eval(input: InternalRow): Any = seen.size.toLong
}

/** The data type of ApproxCountDistinctPartition since its output is a HyperLogLog object. */
private[sql] case object HyperLogLogUDT extends UserDefinedType[HyperLogLog] {

  override def sqlType: DataType = BinaryType

  /** Since we are using HyperLogLog internally, usually it will not be called. */
  override def serialize(obj: Any): Array[Byte] =
    obj.asInstanceOf[HyperLogLog].getBytes


  /** Since we are using HyperLogLog internally, usually it will not be called. */
  override def deserialize(datum: Any): HyperLogLog =
    HyperLogLog.Builder.build(datum.asInstanceOf[Array[Byte]])

  override def userClass: Class[HyperLogLog] = classOf[HyperLogLog]
}

case class ApproxCountDistinctPartition(child: Expression, relativeSD: Double)
  extends AggregateExpression with trees.UnaryNode[Expression] {

  override def nullable: Boolean = false
  override def dataType: DataType = HyperLogLogUDT
  override def toString: String = s"APPROXIMATE COUNT(DISTINCT $child)"
  override def newInstance(): ApproxCountDistinctPartitionFunction = {
    new ApproxCountDistinctPartitionFunction(child, this, relativeSD)
  }
}

case class ApproxCountDistinctPartitionFunction(
    expr: Expression,
    base: AggregateExpression,
    relativeSD: Double)
  extends AggregateFunction {
  def this() = this(null, null, 0) // Required for serialization.

  private val hyperLogLog = new HyperLogLog(relativeSD)

  override def update(input: InternalRow): Unit = {
    val evaluatedExpr = expr.eval(input)
    if (evaluatedExpr != null) {
      hyperLogLog.offer(evaluatedExpr)
    }
  }

  override def eval(input: InternalRow): Any = hyperLogLog
}

case class ApproxCountDistinctMerge(child: Expression, relativeSD: Double)
  extends AggregateExpression with trees.UnaryNode[Expression] {

  override def nullable: Boolean = false
  override def dataType: LongType.type = LongType
  override def toString: String = s"APPROXIMATE COUNT(DISTINCT $child)"
  override def newInstance(): ApproxCountDistinctMergeFunction = {
    new ApproxCountDistinctMergeFunction(child, this, relativeSD)
  }
}

case class ApproxCountDistinctMergeFunction(
    expr: Expression,
    base: AggregateExpression,
    relativeSD: Double)
  extends AggregateFunction {
  def this() = this(null, null, 0) // Required for serialization.

  private val hyperLogLog = new HyperLogLog(relativeSD)

  override def update(input: InternalRow): Unit = {
    val evaluatedExpr = expr.eval(input)
    hyperLogLog.addAll(evaluatedExpr.asInstanceOf[HyperLogLog])
  }

  override def eval(input: InternalRow): Any = hyperLogLog.cardinality()
}

case class ApproxCountDistinct(child: Expression, relativeSD: Double = 0.05)
  extends PartialAggregate with trees.UnaryNode[Expression] {

  override def nullable: Boolean = false
  override def dataType: LongType.type = LongType
  override def toString: String = s"APPROXIMATE COUNT(DISTINCT $child)"

  override def asPartial: SplitEvaluation = {
    val partialCount =
      Alias(ApproxCountDistinctPartition(child, relativeSD), "PartialApproxCountDistinct")()

    SplitEvaluation(
      ApproxCountDistinctMerge(partialCount.toAttribute, relativeSD),
      partialCount :: Nil)
  }

  override def newInstance(): CountDistinctFunction = new CountDistinctFunction(child :: Nil, this)
}

case class Average(child: Expression) extends PartialAggregate with trees.UnaryNode[Expression] {

  override def nullable: Boolean = true

  override def dataType: DataType = child.dataType match {
    case DecimalType.Fixed(precision, scale) =>
      DecimalType(precision + 4, scale + 4)  // Add 4 digits after decimal point, like Hive
    case DecimalType.Unlimited =>
      DecimalType.Unlimited
    case _ =>
      DoubleType
  }

  override def toString: String = s"AVG($child)"

  override def asPartial: SplitEvaluation = {
    child.dataType match {
      case DecimalType.Fixed(_, _) | DecimalType.Unlimited =>
        // Turn the child to unlimited decimals for calculation, before going back to fixed
        val partialSum = Alias(Sum(Cast(child, DecimalType.Unlimited)), "PartialSum")()
        val partialCount = Alias(Count(child), "PartialCount")()

        val castedSum = Cast(Sum(partialSum.toAttribute), DecimalType.Unlimited)
        val castedCount = Cast(Sum(partialCount.toAttribute), DecimalType.Unlimited)
        SplitEvaluation(
          Cast(Divide(castedSum, castedCount), dataType),
          partialCount :: partialSum :: Nil)

      case _ =>
        val partialSum = Alias(Sum(child), "PartialSum")()
        val partialCount = Alias(Count(child), "PartialCount")()

        val castedSum = Cast(Sum(partialSum.toAttribute), dataType)
        val castedCount = Cast(Sum(partialCount.toAttribute), dataType)
        SplitEvaluation(
          Divide(castedSum, castedCount),
          partialCount :: partialSum :: Nil)
    }
  }

  override def newInstance(): AverageFunction = new AverageFunction(child, this)

  override def checkInputDataTypes(): TypeCheckResult =
    TypeUtils.checkForNumericExpr(child.dataType, "function average")
}

case class AverageFunction(expr: Expression, base: AggregateExpression)
  extends AggregateFunction {

  def this() = this(null, null) // Required for serialization.

  private val calcType =
    expr.dataType match {
      case DecimalType.Fixed(_, _) =>
        DecimalType.Unlimited
      case _ =>
        expr.dataType
    }

  private val zero = Cast(Literal(0), calcType)

  private var count: Long = _
  private val sum = MutableLiteral(zero.eval(null), calcType)

  private def addFunction(value: Any) = Add(sum,
    Cast(Literal.create(value, expr.dataType), calcType))

  override def eval(input: InternalRow): Any = {
    if (count == 0L) {
      null
    } else {
      expr.dataType match {
        case DecimalType.Fixed(_, _) =>
          Cast(Divide(
            Cast(sum, DecimalType.Unlimited),
            Cast(Literal(count), DecimalType.Unlimited)), dataType).eval(null)
        case _ =>
          Divide(
            Cast(sum, dataType),
            Cast(Literal(count), dataType)).eval(null)
      }
    }
  }

  override def update(input: InternalRow): Unit = {
    val evaluatedExpr = expr.eval(input)
    if (evaluatedExpr != null) {
      count += 1
      sum.update(addFunction(evaluatedExpr), input)
    }
  }
}

case class Sum(child: Expression) extends PartialAggregate with trees.UnaryNode[Expression] {

  override def nullable: Boolean = true

  override def dataType: DataType = child.dataType match {
    case DecimalType.Fixed(precision, scale) =>
      DecimalType(precision + 10, scale)  // Add 10 digits left of decimal point, like Hive
    case DecimalType.Unlimited =>
      DecimalType.Unlimited
    case _ =>
      child.dataType
  }

  override def toString: String = s"SUM($child)"

  override def asPartial: SplitEvaluation = {
    child.dataType match {
      case DecimalType.Fixed(_, _) =>
        val partialSum = Alias(Sum(Cast(child, DecimalType.Unlimited)), "PartialSum")()
        SplitEvaluation(
          Cast(CombineSum(partialSum.toAttribute), dataType),
          partialSum :: Nil)

      case _ =>
        val partialSum = Alias(Sum(child), "PartialSum")()
        SplitEvaluation(
          CombineSum(partialSum.toAttribute),
          partialSum :: Nil)
    }
  }

  override def newInstance(): SumFunction = new SumFunction(child, this)

  override def checkInputDataTypes(): TypeCheckResult =
    TypeUtils.checkForNumericExpr(child.dataType, "function sum")
}

case class SumFunction(expr: Expression, base: AggregateExpression) extends AggregateFunction {
  def this() = this(null, null) // Required for serialization.

  private val calcType =
    expr.dataType match {
      case DecimalType.Fixed(_, _) =>
        DecimalType.Unlimited
      case _ =>
        expr.dataType
    }

  private val zero = Cast(Literal(0), calcType)

  private val sum = MutableLiteral(null, calcType)

  private val addFunction =
    Coalesce(Seq(Add(Coalesce(Seq(sum, zero)), Cast(expr, calcType)), sum, zero))

  override def update(input: InternalRow): Unit = {
    sum.update(addFunction, input)
  }

  override def eval(input: InternalRow): Any = {
    expr.dataType match {
      case DecimalType.Fixed(_, _) =>
        Cast(sum, dataType).eval(null)
      case _ => sum.eval(null)
    }
  }
}

/**
 * Sum should satisfy 3 cases:
 * 1) sum of all null values = zero
 * 2) sum for table column with no data = null
 * 3) sum of column with null and not null values = sum of not null values
 * Require separate CombineSum Expression and function as it has to distinguish "No data" case
 * versus "data equals null" case, while aggregating results and at each partial expression.i.e.,
 * Combining    PartitionLevel   InputData
 *                           <-- null
 * Zero     <-- Zero         <-- null
 *
 *          <-- null         <-- no data
 * null     <-- null         <-- no data
 */
case class CombineSum(child: Expression) extends AggregateExpression {
  def this() = this(null)

  override def children: Seq[Expression] = child :: Nil
  override def nullable: Boolean = true
  override def dataType: DataType = child.dataType
  override def toString: String = s"CombineSum($child)"
  override def newInstance(): CombineSumFunction = new CombineSumFunction(child, this)
}

case class CombineSumFunction(expr: Expression, base: AggregateExpression)
  extends AggregateFunction {

  def this() = this(null, null) // Required for serialization.

  private val calcType =
    expr.dataType match {
      case DecimalType.Fixed(_, _) =>
        DecimalType.Unlimited
      case _ =>
        expr.dataType
    }

  private val zero = Cast(Literal(0), calcType)

  private val sum = MutableLiteral(null, calcType)

  private val addFunction =
    Coalesce(Seq(Add(Coalesce(Seq(sum, zero)), Cast(expr, calcType)), sum, zero))

  override def update(input: InternalRow): Unit = {
    val result = expr.eval(input)
    // partial sum result can be null only when no input rows present
    if(result != null) {
      sum.update(addFunction, input)
    }
  }

  override def eval(input: InternalRow): Any = {
    expr.dataType match {
      case DecimalType.Fixed(_, _) =>
        Cast(sum, dataType).eval(null)
      case _ => sum.eval(null)
    }
  }
}

case class SumDistinct(child: Expression)
  extends PartialAggregate with trees.UnaryNode[Expression] {

  def this() = this(null)
  override def nullable: Boolean = true
  override def dataType: DataType = child.dataType match {
    case DecimalType.Fixed(precision, scale) =>
      DecimalType(precision + 10, scale)  // Add 10 digits left of decimal point, like Hive
    case DecimalType.Unlimited =>
      DecimalType.Unlimited
    case _ =>
      child.dataType
  }
  override def toString: String = s"SUM(DISTINCT $child)"
  override def newInstance(): SumDistinctFunction = new SumDistinctFunction(child, this)

  override def asPartial: SplitEvaluation = {
    val partialSet = Alias(CollectHashSet(child :: Nil), "partialSets")()
    SplitEvaluation(
      CombineSetsAndSum(partialSet.toAttribute, this),
      partialSet :: Nil)
  }

  override def checkInputDataTypes(): TypeCheckResult =
    TypeUtils.checkForNumericExpr(child.dataType, "function sumDistinct")
}

case class SumDistinctFunction(expr: Expression, base: AggregateExpression)
  extends AggregateFunction {

  def this() = this(null, null) // Required for serialization.

  private val seen = new scala.collection.mutable.HashSet[Any]()

  override def update(input: InternalRow): Unit = {
    val evaluatedExpr = expr.eval(input)
    if (evaluatedExpr != null) {
      seen += evaluatedExpr
    }
  }

  override def eval(input: InternalRow): Any = {
    if (seen.size == 0) {
      null
    } else {
      Cast(Literal(
        seen.reduceLeft(
          dataType.asInstanceOf[NumericType].numeric.asInstanceOf[Numeric[Any]].plus)),
        dataType).eval(null)
    }
  }
}

case class CombineSetsAndSum(inputSet: Expression, base: Expression) extends AggregateExpression {
  def this() = this(null, null)

  override def children: Seq[Expression] = inputSet :: Nil
  override def nullable: Boolean = true
  override def dataType: DataType = base.dataType
  override def toString: String = s"CombineAndSum($inputSet)"
  override def newInstance(): CombineSetsAndSumFunction = {
    new CombineSetsAndSumFunction(inputSet, this)
  }
}

case class CombineSetsAndSumFunction(
    @transient inputSet: Expression,
    @transient base: AggregateExpression)
  extends AggregateFunction {

  def this() = this(null, null) // Required for serialization.

  val seen = new OpenHashSet[Any]()

  override def update(input: InternalRow): Unit = {
    val inputSetEval = inputSet.eval(input).asInstanceOf[OpenHashSet[Any]]
    val inputIterator = inputSetEval.iterator
    while (inputIterator.hasNext) {
      seen.add(inputIterator.next)
    }
  }

  override def eval(input: InternalRow): Any = {
    val casted = seen.asInstanceOf[OpenHashSet[InternalRow]]
    if (casted.size == 0) {
      null
    } else {
      Cast(Literal(
        casted.iterator.map(f => f.apply(0)).reduceLeft(
          base.dataType.asInstanceOf[NumericType].numeric.asInstanceOf[Numeric[Any]].plus)),
        base.dataType).eval(null)
    }
  }
}

case class First(child: Expression) extends PartialAggregate with trees.UnaryNode[Expression] {
  override def nullable: Boolean = true
  override def dataType: DataType = child.dataType
  override def toString: String = s"FIRST($child)"

  override def asPartial: SplitEvaluation = {
    val partialFirst = Alias(First(child), "PartialFirst")()
    SplitEvaluation(
      First(partialFirst.toAttribute),
      partialFirst :: Nil)
  }
  override def newInstance(): FirstFunction = new FirstFunction(child, this)
}

case class FirstFunction(expr: Expression, base: AggregateExpression) extends AggregateFunction {
  def this() = this(null, null) // Required for serialization.

  var result: Any = null

  override def update(input: InternalRow): Unit = {
    if (result == null) {
      result = expr.eval(input)
    }
  }

  override def eval(input: InternalRow): Any = result
}

case class Last(child: Expression) extends PartialAggregate with trees.UnaryNode[Expression] {
  override def references: AttributeSet = child.references
  override def nullable: Boolean = true
  override def dataType: DataType = child.dataType
  override def toString: String = s"LAST($child)"

  override def asPartial: SplitEvaluation = {
    val partialLast = Alias(Last(child), "PartialLast")()
    SplitEvaluation(
      Last(partialLast.toAttribute),
      partialLast :: Nil)
  }
  override def newInstance(): LastFunction = new LastFunction(child, this)
}

case class LastFunction(expr: Expression, base: AggregateExpression) extends AggregateFunction {
  def this() = this(null, null) // Required for serialization.

  var result: Any = null

  override def update(input: InternalRow): Unit = {
    result = input
  }

  override def eval(input: InternalRow): Any = {
    if (result != null) expr.eval(result.asInstanceOf[InternalRow]) else null
  }
}

// Compute standard deviation based on online algorithm specified here:
// http://en.wikipedia.org/wiki/Algorithms_for_calculating_variance
case class Stddev(child: Expression) extends PartialAggregate with trees.UnaryNode[Expression] {
  override def nullable: Boolean = true
  override def dataType: DataType = child.dataType match {
    case DecimalType.Fixed(_, _) | DecimalType.Unlimited =>
      DecimalType.Unlimited
    case _ =>
      DoubleType
  }
  override def toString: String = s"STDDEV($child)"
  override def asPartial: SplitEvaluation = {
    val partialStd = Alias(ComputePartialStd(Cast(child, dataType)), "PartialStddev")()
    SplitEvaluation(CombinePartialStd(partialStd.toAttribute), partialStd :: Nil)
  }
  override def newInstance(): StddevFunction = new StddevFunction(child, this)
}

case class ComputePartialStd(child: Expression) extends AggregateExpression {
    def this() = this(null)

    override def children: Seq[Expression] = child :: Nil
    override def nullable: Boolean = false
    override def dataType: DataType = child.dataType match {
      case DecimalType.Unlimited => ArrayType(DecimalType.Unlimited)
      case _ => ArrayType(DoubleType)
    }
    override def toString: String = s"computePartialStddev($child)"
    override def newInstance(): ComputePartialStdFunction =
      new ComputePartialStdFunction(child, this)
}

case class CombinePartialStd(child: Expression) extends AggregateExpression {
  def this() = this(null)

  override def children: Seq[Expression] = child:: Nil
  override def nullable: Boolean = false
  override def dataType: DataType = child.dataType match {
    case ArrayType(DecimalType.Unlimited, _) => DecimalType.Unlimited
    case _ => DoubleType
  }
  override def toString: String = s"CombinePartialStd($child)"
  override def newInstance(): CombinePartialStdFunction = {
    new CombinePartialStdFunction(child, this)
  }
}

case class ComputePartialStdFunction (
    expr: Expression,
    base: AggregateExpression
) extends AggregateFunction {
  def this() = this(null, null)  // Required for serialization

  private val computeType = expr.dataType
  private val zero = Cast(Literal(0), computeType)
  private var partialCount: Long = 0L

  // the mean of data processed so far
  private val partialAvg: MutableLiteral = MutableLiteral(zero.eval(null), computeType)

  // update average based on this formula:
  // avg = avg + (value - avg)/count
  private def avgAddFunction (value: Literal): Expression = {
    val delta = Subtract(Cast(value, computeType), partialAvg)
    Add(partialAvg, Divide(delta, Cast(Literal(partialCount), computeType)))
  }

  // the sum of squares of difference from mean
  private val partialMk: MutableLiteral = MutableLiteral(zero.eval(null), computeType)

  // update sum of square of difference from mean based on following formula:
  // Mk = Mk + (value - preAvg) * (value - updatedAvg)
  private def mkAddFunction(value: Literal, prePartialAvg: MutableLiteral): Expression = {
    val delta1 = Subtract(Cast(value, computeType), prePartialAvg)
    val delta2 = Subtract(Cast(value, computeType), partialAvg)
    Add(partialMk, Multiply(delta1, delta2))
  }

  override def update(input: InternalRow): Unit = {
    val evaluatedExpr = expr.eval(input)
    if (evaluatedExpr != null) {
      val exprValue = Literal.create(evaluatedExpr, expr.dataType)
      val prePartialAvg = partialAvg.copy()
      partialCount += 1
      partialAvg.update(avgAddFunction(exprValue), input)
      partialMk.update(mkAddFunction(exprValue, prePartialAvg), input)
    }
  }

  override def eval(input: InternalRow): Any = {
    Seq(Cast(Literal(partialCount), computeType).eval(null),
        partialAvg.eval(null),
        partialMk.eval(null))
  }
}

case class CombinePartialStdFunction(
    expr: Expression,
    base: AggregateExpression
) extends AggregateFunction {
  def this() = this (null, null) // Required for serialization

  private val computeType = expr.dataType match {
    case ArrayType(DecimalType.Unlimited, _) => DecimalType.Unlimited
    case _ => DoubleType
  }
  private val zero = Cast(Literal(0), computeType)
  private val combineCount = MutableLiteral(zero.eval(null), computeType)
  private val combineAvg = MutableLiteral(zero.eval(null), computeType)
  private val combineMk = MutableLiteral(zero.eval(null), computeType)

  private def avgUpdateFunction(preCount: Expression,
                                partialCount: Expression,
                                partialAvg: Expression): Expression = {
    Divide(Add(Multiply(combineAvg, preCount),
               Multiply(partialAvg, partialCount)),
           Add(preCount, partialCount))
  }

  override def update(input: InternalRow): Unit = {
    val evaluatedExpr = expr.eval(input)

    if (evaluatedExpr != null) {
      val exprValue = evaluatedExpr.asInstanceOf[Seq[Any]]
      val (partialCount, partialAvg, partialMk) =
        (Literal.create(exprValue(0), computeType),
         Literal.create(exprValue(1), computeType),
         Literal.create(exprValue(2), computeType))

      if (Cast(partialCount, LongType).eval(null).asInstanceOf[Long] > 0) {
        val preCount = combineCount.copy()
        combineCount.update(Add(combineCount, partialCount), input)

        val preAvg = combineAvg.copy()
        val avgDelta = Subtract(partialAvg, preAvg)
        val mkDelta = Multiply(Multiply(avgDelta, avgDelta),
                               Divide(Multiply(preCount, partialCount),
                                      combineCount))

        // update average based on following formula
        // (combineAvg * preCount + partialAvg * partialCount) / (preCount + partialCount)
        combineAvg.update(
          avgUpdateFunction(preCount, partialCount, partialAvg), input)

        // update sum of square differences from mean based on following formula
        // (combineMk + partialMk + (avgDelta * avgDelta) * (preCount * partialCount/combineCount)
        combineMk.update(
          Add(combineMk, Add(partialMk, mkDelta)), input)
      }
    }
  }

  override def eval(input: InternalRow): Any = {
    val count = Cast(combineCount, LongType).eval(null)

    if (count.asInstanceOf[Long] == 0 ) null
    else if (count.asInstanceOf[Long] < 2) zero.eval(null)
    else {
      // when total count > 2
      // stddev = sqrt (combineMk/(combineCount -1))
      Sqrt(Divide(combineMk,
           Subtract(combineCount, Cast(Literal(1), computeType)))).eval(null)
    }
  }
}

case class StddevFunction(
    expr: Expression,
    base: AggregateExpression
) extends AggregateFunction {

  def this() = this(null, null) // Required for serialization

  private val computeType = expr.dataType match {
    case DecimalType.Fixed(_, _) | DecimalType.Unlimited =>
      DecimalType.Unlimited
    case _ => DoubleType
  }
  private var curCount: Long = 0L
  private val zero = Cast(Literal(0), computeType)
  private val curAvg = MutableLiteral(zero.eval(null), computeType)
  private val curMk = MutableLiteral(zero.eval(null), computeType)

  private def curAvgAddFunction(value: Literal): Expression = {
    val delta = Subtract(Cast(value, computeType), curAvg)
    Add(curAvg, Divide(delta, Cast(Literal(curCount), computeType)))
  }
  private def curMkAddFunction(value: Literal, preAvg: MutableLiteral): Expression = {
    val delta1 = Subtract(Cast(value, computeType), preAvg)
    val delta2 = Subtract(Cast(value, computeType), curAvg)
    Add(curMk, Multiply(delta1, delta2))
  }

  override def update(input: InternalRow): Unit = {
    val evaluatedExpr = expr.eval(input)
    if (evaluatedExpr != null) {
      val preAvg: MutableLiteral = curAvg.copy()
      val exprValue = Literal.create(evaluatedExpr, expr.dataType)
      curCount += 1
      curAvg.update(curAvgAddFunction(exprValue), input)
      curMk.update(curMkAddFunction(exprValue, preAvg), input)
    }
  }

  override def eval(input: InternalRow): Any = {
    if (curCount == 0) null
    else if (curCount < 2) zero.eval(null)
    else {
      // when total count > 2, stddev = sqrt(curMk/(curCount -1))
      Sqrt(Divide(curMk, Cast(Literal(curCount -1), computeType))).eval(null)
    }
  }
}
