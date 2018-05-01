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

package org.apache.spark.sql.catalyst.expressions.codegen

import java.lang.{Boolean => JBool}

import scala.language.{existentials, implicitConversions}

import org.apache.spark.sql.types.{BooleanType, DataType}

/**
 * Trait representing an opaque fragments of java code.
 */
trait JavaCode {
  def code: String
  override def toString: String = code
}

/**
 * Utility functions for creating [[JavaCode]] fragments.
 */
object JavaCode {
  /**
   * Create a java literal.
   */
  def literal(v: String, dataType: DataType): LiteralValue = dataType match {
    case BooleanType if v == "true" => TrueLiteral
    case BooleanType if v == "false" => FalseLiteral
    case _ => new LiteralValue(v, CodeGenerator.javaClass(dataType))
  }

  /**
   * Create a default literal. This is null for reference types, false for boolean types and
   * -1 for other primitive types.
   */
  def defaultLiteral(dataType: DataType): LiteralValue = {
    new LiteralValue(
      CodeGenerator.defaultValue(dataType, typedNull = true),
      CodeGenerator.javaClass(dataType))
  }

  /**
   * Create a local java variable.
   */
  def variable(name: String, dataType: DataType): VariableValue = {
    variable(name, CodeGenerator.javaClass(dataType))
  }

  /**
   * Create a local java variable.
   */
  def variable(name: String, javaClass: Class[_]): VariableValue = {
    VariableValue(name, javaClass)
  }

  /**
   * Create a local isNull variable.
   */
  def isNullVariable(name: String): VariableValue = variable(name, BooleanType)

  /**
   * Create a global java variable.
   */
  def global(name: String, dataType: DataType): GlobalValue = {
    global(name, CodeGenerator.javaClass(dataType))
  }

  /**
   * Create a global java variable.
   */
  def global(name: String, javaClass: Class[_]): GlobalValue = {
    GlobalValue(name, javaClass)
  }

  /**
   * Create a global isNull variable.
   */
  def isNullGlobal(name: String): GlobalValue = global(name, BooleanType)

  /**
   * Create an expression fragment.
   */
  def expression(code: String, dataType: DataType): SimpleExprValue = {
    expression(code, CodeGenerator.javaClass(dataType))
  }

  /**
   * Create an expression fragment.
   */
  def expression(code: String, javaClass: Class[_]): SimpleExprValue = {
    SimpleExprValue(code, javaClass)
  }

  /**
   * Create a isNull expression fragment.
   */
  def isNullExpression(code: String): SimpleExprValue = {
    expression(code, BooleanType)
  }

  def block(code: String): Block = {
    CodeBlock(codeParts = Seq(code), exprValues = Seq.empty)
  }
}

/**
 * A block of java code which involves some expressions represented by `ExprValue`.
 */
trait Block extends JavaCode {
  def exprValues: Seq[Any]

  // This will be called during string interpolation.
  override def toString: String = _marginChar match {
    case Some(c) => code.stripMargin(c)
    case _ => code
  }

  var _marginChar: Option[Char] = None

  def stripMargin(c: Char): this.type = {
    _marginChar = Some(c)
    this
  }

  def stripMargin: this.type = {
    _marginChar = Some('|')
    this
  }

  def + (other: Block): Block
}

object Block {
  implicit def blockToString(block: Block): String = block.toString

  implicit def blocksToBlock(blocks: Seq[Block]): Block = Blocks(blocks)

  implicit class BlockHelper(val sc: StringContext) extends AnyVal {
    def code(args: Any*): Block = {
      if (sc.parts.length == 0) {
        EmptyBlock
      } else {
        args.foreach {
          case _: ExprValue => true
          case _: Int | _: Long | _: Float | _: Double | _: String => true
          case _: Block => true
          case other => throw new IllegalArgumentException(
            s"Can not interpolate ${other.getClass} into code block.")
        }
        CodeBlock(sc.parts, args)
      }
    }
  }
}

/**
 * A block of java code.
 */
case class CodeBlock(codeParts: Seq[String], exprValues: Seq[Any]) extends Block {
  override def code: String = {
    val strings = codeParts.iterator
    val expressions = exprValues.iterator
    var buf = new StringBuffer(strings.next)
    while (strings.hasNext) {
      if (expressions.hasNext) {
        buf append expressions.next
        buf append strings.next
      }
    }
    buf.toString
  }

  override def + (other: Block): Block = other match {
    case c: CodeBlock => Blocks(Seq(this, c))
    case b: Blocks => Blocks(Seq(this) ++ b.blocks)
    case EmptyBlock => this
  }
}

case class Blocks(blocks: Seq[Block]) extends Block {
  override def exprValues: Seq[Any] = blocks.flatMap(_.exprValues)
  override def code: String = blocks.map(_.toString).mkString

  override def + (other: Block): Block = other match {
    case c: CodeBlock => Blocks(blocks :+ c)
    case b: Blocks => Blocks(blocks ++ b.blocks)
    case EmptyBlock => this
  }
}

object EmptyBlock extends Block with Serializable {
  override def code: String = ""
  override def exprValues: Seq[Any] = Seq.empty

  override def + (other: Block): Block = other
}

/**
 * A typed java fragment that must be a valid java expression.
 */
trait ExprValue extends JavaCode {
  def javaType: Class[_]
  def isPrimitive: Boolean = javaType.isPrimitive

  // This will be called during string interpolation.
  override def toString: String = ExprValue.exprValueToString(this)
}

object ExprValue {
  implicit def exprValueToString(exprValue: ExprValue): String = exprValue.code
}

/**
 * A java expression fragment.
 */
case class SimpleExprValue(expr: String, javaType: Class[_]) extends ExprValue {
  override def code: String = s"($expr)"
}

/**
 * A local variable java expression.
 */
case class VariableValue(variableName: String, javaType: Class[_]) extends ExprValue {
  override def code: String = variableName
}

/**
 * A global variable java expression.
 */
case class GlobalValue(value: String, javaType: Class[_]) extends ExprValue {
  override def code: String = value
}

/**
 * A literal java expression.
 */
class LiteralValue(val value: String, val javaType: Class[_]) extends ExprValue with Serializable {
  override def code: String = value

  override def equals(arg: Any): Boolean = arg match {
    case l: LiteralValue => l.javaType == javaType && l.value == value
    case _ => false
  }

  override def hashCode(): Int = value.hashCode() * 31 + javaType.hashCode()
}

case object TrueLiteral extends LiteralValue("true", JBool.TYPE)
case object FalseLiteral extends LiteralValue("false", JBool.TYPE)
