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

import org.apache.spark.sql.catalyst.analysis.UnresolvedException
import org.apache.spark.sql.catalyst.errors.TreeNodeException
import org.apache.spark.sql.types._

case class UnaryMinus(child: Expression) extends UnaryExpression {
  type EvaluatedType = Any

  def dataType = child.dataType
  override def foldable = child.foldable
  def nullable = child.nullable
  override def toString = s"-$child"

  lazy val numeric = dataType match {
    case n: NumericType => n.numeric.asInstanceOf[Numeric[Any]]
    case other => sys.error(s"Type $other does not support numeric operations")
  }

  override def eval(input: Row): Any = {
    val evalE = child.eval(input)
    numeric.negate(evalE)
  }
}

case class Sqrt(child: Expression) extends UnaryExpression {
  type EvaluatedType = Any

  def dataType = DoubleType
  override def foldable = child.foldable
  def nullable = true
  override def toString = s"SQRT($child)"

  lazy val numeric = child.dataType match {
    case n: NumericType => n.numeric.asInstanceOf[Numeric[Any]]
    case other => sys.error(s"Type $other does not support non-negative numeric operations")
  }

  override def eval(input: Row): Any = {
    val evalE = child.eval(input)
    if (evalE == null) {
      null
    } else {
      val value = numeric.toDouble(evalE)
      if (value < 0) null
      else math.sqrt(value)
    }
  }
}

abstract class BinaryArithmetic extends BinaryExpression {
  self: Product =>

  type EvaluatedType = Any

  def nullable = left.nullable || right.nullable

  override lazy val resolved =
    left.resolved && right.resolved &&
    left.dataType == right.dataType &&
    !DecimalType.isFixed(left.dataType)

  def dataType = {
    if (!resolved) {
      throw new UnresolvedException(this,
        s"datatype. Can not resolve due to differing types ${left.dataType}, ${right.dataType}")
    }
    left.dataType
  }

  override def eval(input: Row): Any = {
    val evalE1 = left.eval(input)
    if(evalE1 == null) {
      null
    } else {
      val evalE2 = right.eval(input)
      if (evalE2 == null) {
        null
      } else {
        evalInternal(evalE1, evalE2)
      }
    }
  }

  def evalInternal(evalE1: EvaluatedType, evalE2: EvaluatedType): Any =
    sys.error(s"BinaryExpressions must either override eval or evalInternal")
}

case class Add(left: Expression, right: Expression) extends BinaryArithmetic {
  def symbol = "+"

  lazy val numeric = dataType match {
    case n: NumericType => n.numeric.asInstanceOf[Numeric[Any]]
    case other => sys.error(s"Type $other does not support numeric operations")
  }

  override def evalInternal(evalE1: EvaluatedType, evalE2: EvaluatedType): Any = numeric.plus(evalE1, evalE2)
}

case class Subtract(left: Expression, right: Expression) extends BinaryArithmetic {
  def symbol = "-"

  lazy val numeric = dataType match {
    case n: NumericType => n.numeric.asInstanceOf[Numeric[Any]]
    case other => sys.error(s"Type $other does not support numeric operations")
  }

  override def evalInternal(evalE1: EvaluatedType, evalE2: EvaluatedType): Any = numeric.minus(evalE1, evalE2)
}

case class Multiply(left: Expression, right: Expression) extends BinaryArithmetic {
  def symbol = "*"

  lazy val numeric = dataType match {
    case n: NumericType => n.numeric.asInstanceOf[Numeric[Any]]
    case other => sys.error(s"Type $other does not support numeric operations")
  }

  override def evalInternal(evalE1: EvaluatedType, evalE2: EvaluatedType): Any = numeric.times(evalE1, evalE2)
}

case class Divide(left: Expression, right: Expression) extends BinaryArithmetic {
  def symbol = "/"

  override def nullable = true

  lazy val div: (Any, Any) => Any = dataType match {
    case ft: FractionalType => ft.fractional.asInstanceOf[Fractional[Any]].div
    case it: IntegralType => it.integral.asInstanceOf[Integral[Any]].quot
    case other => sys.error(s"Type $other does not support numeric operations")
  }
  
  override def eval(input: Row): Any = {
    val evalE2 = right.eval(input)
    if (evalE2 == null || evalE2 == 0) {
      null
    } else {
      val evalE1 = left.eval(input)
      if (evalE1 == null) {
        null
      } else {
        div(evalE1, evalE2)
      }
    }
  }
}

case class Remainder(left: Expression, right: Expression) extends BinaryArithmetic {
  def symbol = "%"

  override def nullable = true

  lazy val integral = dataType match {
    case i: IntegralType => i.integral.asInstanceOf[Integral[Any]]
    case i: FractionalType => i.asIntegral.asInstanceOf[Integral[Any]]
    case other => sys.error(s"Type $other does not support numeric operations")
  }

  override def eval(input: Row): Any = {
    val evalE2 = right.eval(input)
    if (evalE2 == null || evalE2 == 0) {
      null
    } else {
      val evalE1 = left.eval(input)
      if (evalE1 == null) {
        null
      } else {
        integral.rem(evalE1, evalE2)
      }
    }
  }
}

/**
 * A function that calculates bitwise and(&) of two numbers.
 */
case class BitwiseAnd(left: Expression, right: Expression) extends BinaryArithmetic {
  def symbol = "&"

  override def evalInternal(evalE1: EvaluatedType, evalE2: EvaluatedType): Any = dataType match {
    case ByteType => (evalE1.asInstanceOf[Byte] & evalE2.asInstanceOf[Byte]).toByte
    case ShortType => (evalE1.asInstanceOf[Short] & evalE2.asInstanceOf[Short]).toShort
    case IntegerType => evalE1.asInstanceOf[Int] & evalE2.asInstanceOf[Int]
    case LongType => evalE1.asInstanceOf[Long] & evalE2.asInstanceOf[Long]
    case other => sys.error(s"Unsupported bitwise & operation on $other")
  }
}

/**
 * A function that calculates bitwise or(|) of two numbers.
 */
case class BitwiseOr(left: Expression, right: Expression) extends BinaryArithmetic {
  def symbol = "|"

  override def evalInternal(evalE1: EvaluatedType, evalE2: EvaluatedType): Any = dataType match {
    case ByteType => (evalE1.asInstanceOf[Byte] | evalE2.asInstanceOf[Byte]).toByte
    case ShortType => (evalE1.asInstanceOf[Short] | evalE2.asInstanceOf[Short]).toShort
    case IntegerType => evalE1.asInstanceOf[Int] | evalE2.asInstanceOf[Int]
    case LongType => evalE1.asInstanceOf[Long] | evalE2.asInstanceOf[Long]
    case other => sys.error(s"Unsupported bitwise | operation on $other")
  }
}

/**
 * A function that calculates bitwise xor(^) of two numbers.
 */
case class BitwiseXor(left: Expression, right: Expression) extends BinaryArithmetic {
  def symbol = "^"

  override def evalInternal(evalE1: EvaluatedType, evalE2: EvaluatedType): Any = dataType match {
    case ByteType => (evalE1.asInstanceOf[Byte] ^ evalE2.asInstanceOf[Byte]).toByte
    case ShortType => (evalE1.asInstanceOf[Short] ^ evalE2.asInstanceOf[Short]).toShort
    case IntegerType => evalE1.asInstanceOf[Int] ^ evalE2.asInstanceOf[Int]
    case LongType => evalE1.asInstanceOf[Long] ^ evalE2.asInstanceOf[Long]
    case other => sys.error(s"Unsupported bitwise ^ operation on $other")
  }
}

/**
 * A function that calculates bitwise not(~) of a number.
 */
case class BitwiseNot(child: Expression) extends UnaryExpression {
  type EvaluatedType = Any

  def dataType = child.dataType
  override def foldable = child.foldable
  def nullable = child.nullable
  override def toString = s"~$child"

  override def eval(input: Row): Any = {
    val evalE = child.eval(input)
    if (evalE == null) {
      null
    } else {
      dataType match {
        case ByteType => ~evalE.asInstanceOf[Byte]
        case ShortType => ~evalE.asInstanceOf[Short]
        case IntegerType => ~evalE.asInstanceOf[Int]
        case LongType => ~evalE.asInstanceOf[Long]
        case other => sys.error(s"Unsupported bitwise ~ operation on $other")
      }
    }
  }
}

case class MaxOf(left: Expression, right: Expression) extends Expression {
  type EvaluatedType = Any

  override def foldable = left.foldable && right.foldable

  override def nullable = left.nullable && right.nullable

  override def children = left :: right :: Nil

  override def dataType = left.dataType

  lazy val ordering = {
    if (left.dataType != right.dataType) {
      throw new TreeNodeException(this,  s"Types do not match ${left.dataType} != ${right.dataType}")
    }
    left.dataType match {
      case i: NativeType => i.ordering.asInstanceOf[Ordering[Any]]
      case other => sys.error(s"Type $other does not support ordered operations")
    }
  }

  override def eval(input: Row): Any = {
    val leftEval = left.eval(input)
    val rightEval = right.eval(input)
    if (leftEval == null) {
      rightEval
    } else if (rightEval == null) {
      leftEval
    } else {
      if (ordering.compare(leftEval, rightEval) < 0) {
        rightEval
      } else {
        leftEval
      }
    }
  }

  override def toString = s"MaxOf($left, $right)"
}

/**
 * A function that get the absolute value of the numeric value.
 */
case class Abs(child: Expression) extends UnaryExpression  {
  type EvaluatedType = Any

  def dataType = child.dataType
  override def foldable = child.foldable
  def nullable = child.nullable
  override def toString = s"Abs($child)"

  lazy val numeric = dataType match {
    case n: NumericType => n.numeric.asInstanceOf[Numeric[Any]]
    case other => sys.error(s"Type $other does not support numeric operations")
  }

  override def eval(input: Row): Any = {
    val evalE = child.eval(input)
    numeric.abs(evalE)
  }
}
