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
package catalyst
package expressions

import errors._
import trees._
import types._

abstract class Expression extends TreeNode[Expression] {
  self: Product =>

  /** The narrowest possible type that is produced when this expression is evaluated. */
  type EvaluatedType <: Any

  def dataType: DataType
  /**
   * Returns true when an expression is a candidate for static evaluation before the query is
   * executed.
   *
   * The following conditions are used to determine suitability for constant folding:
   *  - A [[expressions.Coalesce Coalesce]] is foldable if all of its children are foldable
   *  - A [[expressions.BinaryExpression BinaryExpression]] is foldable if its both left and right
   *    child are foldable
   *  - A [[expressions.Not Not]], [[expressions.IsNull IsNull]], or
   *    [[expressions.IsNotNull IsNotNull]] is foldable if its child is foldable.
   *  - A [[expressions.Literal]] is foldable.
   *  - A [[expressions.Cast Cast]] or [[expressions.UnaryMinus UnaryMinus]] is foldable if its
   *    child is foldable.
   */
  // TODO: Supporting more foldable expressions. For example, deterministic Hive UDFs.
  def foldable: Boolean = false
  def nullable: Boolean
  def references: Set[Attribute]

  /** Returns the result of evaluating this expression on a given input Row */
  def apply(input: Row = null): EvaluatedType =
    throw new TreeNodeException(this, s"No function to evaluate expression. type: ${this.nodeName}")

  // Primitive Accessor functions that avoid boxing for performance.
  // Note this is an Unstable API as it doesn't correctly handle null values yet.

  def applyBoolean(input: Row): Boolean = apply(input).asInstanceOf[Boolean]
  def applyInt(input: Row): Int = apply(input).asInstanceOf[Int]
  def applyDouble(input: Row): Double = apply(input).asInstanceOf[Double]
  def applyString(input: Row): String = apply(input).asInstanceOf[String]

  /**
   * Returns `true` if this expression and all its children have been resolved to a specific schema
   * and `false` if it is still contains any unresolved placeholders. Implementations of expressions
   * should override this if the resolution of this type of expression involves more than just
   * the resolution of its children.
   */
  lazy val resolved: Boolean = childrenResolved

  /**
   * Returns true if  all the children of this expression have been resolved to a specific schema
   * and false if any still contains any unresolved placeholders.
   */
  def childrenResolved = !children.exists(!_.resolved)

  /**
   * A set of helper functions that return the correct descendant of [[scala.math.Numeric]] type
   * and do any casting necessary of child evaluation.
   */
  @inline
  def n1(e: Expression, i: Row, f: ((Numeric[Any], Any) => Any)): Any  = {
    val evalE = e.apply(i)
    if (evalE == null) {
      null
    } else {
      e.dataType match {
        case n: NumericType =>
          val castedFunction = f.asInstanceOf[(Numeric[n.JvmType], n.JvmType) => n.JvmType]
          castedFunction(n.numeric, evalE.asInstanceOf[n.JvmType])
        case other => sys.error(s"Type $other does not support numeric operations")
      }
    }
  }

  @inline
  protected final def n2(
      i: Row,
      e1: Expression,
      e2: Expression,
      f: ((Numeric[Any], Any, Any) => Any)): Any  = {

    if (e1.dataType != e2.dataType) {
      throw new TreeNodeException(this,  s"Types do not match ${e1.dataType} != ${e2.dataType}")
    }

    val evalE1 = e1.apply(i)
    if(evalE1 == null) {
      null
    } else {
      val evalE2 = e2.apply(i)
      if (evalE2 == null) {
        null
      } else {
        e1.dataType match {
          case n: NumericType =>
            f.asInstanceOf[(Numeric[n.JvmType], n.JvmType, n.JvmType) => Int](
              n.numeric, evalE1.asInstanceOf[n.JvmType], evalE2.asInstanceOf[n.JvmType])
          case other => sys.error(s"Type $other does not support numeric operations")
        }
      }
    }
  }

  @inline
  protected final def f2(
      i: Row,
      e1: Expression,
      e2: Expression,
      f: ((Fractional[Any], Any, Any) => Any)): Any  = {
    if (e1.dataType != e2.dataType) {
      throw new TreeNodeException(this,  s"Types do not match ${e1.dataType} != ${e2.dataType}")
    }

    val evalE1 = e1.apply(i: Row)
    if(evalE1 == null) {
      null
    } else {
      val evalE2 = e2.apply(i: Row)
      if (evalE2 == null) {
        null
      } else {
        e1.dataType match {
          case ft: FractionalType =>
            f.asInstanceOf[(Fractional[ft.JvmType], ft.JvmType, ft.JvmType) => ft.JvmType](
              ft.fractional, evalE1.asInstanceOf[ft.JvmType], evalE2.asInstanceOf[ft.JvmType])
          case other => sys.error(s"Type $other does not support fractional operations")
        }
      }
    }
  }

  @inline
  protected final def i2(
      i: Row,
      e1: Expression,
      e2: Expression,
      f: ((Integral[Any], Any, Any) => Any)): Any  = {
    if (e1.dataType != e2.dataType) {
      throw new TreeNodeException(this,  s"Types do not match ${e1.dataType} != ${e2.dataType}")
    }

    val evalE1 = e1.apply(i)
    if(evalE1 == null) {
      null
    } else {
      val evalE2 = e2.apply(i)
      if (evalE2 == null) {
        null
      } else {
        e1.dataType match {
          case i: IntegralType =>
            f.asInstanceOf[(Integral[i.JvmType], i.JvmType, i.JvmType) => i.JvmType](
              i.integral, evalE1.asInstanceOf[i.JvmType], evalE2.asInstanceOf[i.JvmType])
          case other => sys.error(s"Type $other does not support numeric operations")
        }
      }
    }
  }
}

abstract class BinaryExpression extends Expression with trees.BinaryNode[Expression] {
  self: Product =>

  def symbol: String

  override def foldable = left.foldable && right.foldable

  def references = left.references ++ right.references

  override def toString = s"($left $symbol $right)"
}

abstract class LeafExpression extends Expression with trees.LeafNode[Expression] {
  self: Product =>
}

abstract class UnaryExpression extends Expression with trees.UnaryNode[Expression] {
  self: Product =>

  def references = child.references
}
