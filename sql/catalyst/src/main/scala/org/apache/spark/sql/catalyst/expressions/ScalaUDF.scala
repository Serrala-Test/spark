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

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.CatalystTypeConverters
import org.apache.spark.sql.catalyst.encoders.{ExpressionEncoder, RowEncoder}
import org.apache.spark.sql.catalyst.expressions.codegen._
import org.apache.spark.sql.Row
import org.apache.spark.sql.types._

/**
 * User-defined function.
 * @param function  The user defined scala function to run.
 *                  Note that if you use primitive parameters, you are not able to check if it is
 *                  null or not, and the UDF will return null for you if the primitive input is
 *                  null. Use boxed type or [[Option]] if you wanna do the null-handling yourself.
 * @param dataType  Return type of function.
 * @param children  The input expressions of this UDF.
 * @param inputTypes  The expected input types of this UDF.
 */
case class ScalaUDF(
    function: AnyRef,
    dataType: DataType,
    children: Seq[Expression],
    inputTypes: Seq[DataType] = Nil)
  extends Expression with ImplicitCastInputTypes {

  override def nullable: Boolean = true

  override def toString: String = s"UDF(${children.mkString(",")})"

  // Accessors used in genCode
  def userDefinedFunc(): AnyRef = function
  def getChildren(): Seq[Expression] = children
  def getDataType(): StructType = StructType(StructField("_c0", dataType) :: Nil)
  def getInputSchema(): StructType = inputSchema

  lazy val inputSchema: StructType = {
      val fields = if (inputTypes == Nil) {
        // from the deprecated callUDF codepath
        children.zipWithIndex.map { case (e, i) =>
          StructField(s"_c$i", e.dataType)
        }
      } else {
        inputTypes.zipWithIndex.map { case (t, i) =>
          StructField(s"_c$i", t)
        }
      }
      StructType(fields)
    }

  // scalastyle:off

  /** This method has been generated by this script

    (1 to 22).map { x =>
      val anys = (1 to x).map(x => "Any").reduce(_ + ", " + _)
      val evals = (0 to x - 1).map(x => s"convertedRow.get($x)").reduce(_ + ",\n      " + _)

      s"""case $x =>
      val func = function.asInstanceOf[($anys) => Any]
      (input: InternalRow) => {
        val convertedRow: Row = inputEncoder.fromRow(input)
        func(
          $evals)
      }
      """
    }.foreach(println)

  */

  private[this] val f = {
    lazy val inputEncoder: ExpressionEncoder[Row] = RowEncoder(inputSchema)
    children.size match {
      case 0 =>
        val func = function.asInstanceOf[() => Any]
        (input: InternalRow) => {
          func()
        }

      case 1 =>
        val func = function.asInstanceOf[(Any) => Any]
        (input: InternalRow) => {
          val convertedRow: Row = inputEncoder.fromRow(input)
          func(convertedRow.get(0))
        }

      case 2 =>
        val func = function.asInstanceOf[(Any, Any) => Any]
        (input: InternalRow) => {
          val convertedRow: Row = inputEncoder.fromRow(input)
          func(convertedRow.get(0), convertedRow.get(1))
        }

      case 3 =>
        val func = function.asInstanceOf[(Any, Any, Any) => Any]
        (input: InternalRow) => {
          val convertedRow: Row = inputEncoder.fromRow(input)
          func(convertedRow.get(0), convertedRow.get(1), convertedRow.get(2))
        }

      case 4 =>
        val func = function.asInstanceOf[(Any, Any, Any, Any) => Any]
        (input: InternalRow) => {
          val convertedRow: Row = inputEncoder.fromRow(input)
          func(convertedRow.get(0), convertedRow.get(1), convertedRow.get(2), convertedRow.get(3))
        }

      case 5 =>
        val func = function.asInstanceOf[(Any, Any, Any, Any, Any) => Any]
        (input: InternalRow) => {
          val convertedRow: Row = inputEncoder.fromRow(input)
          func(convertedRow.get(0), convertedRow.get(1), convertedRow.get(2), convertedRow.get(3),
            convertedRow.get(4))
        }

      case 6 =>
        val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any) => Any]
        (input: InternalRow) => {
          val convertedRow: Row = inputEncoder.fromRow(input)
          func(convertedRow.get(0), convertedRow.get(1), convertedRow.get(2), convertedRow.get(3),
            convertedRow.get(4), convertedRow.get(5))
        }

      case 7 =>
        val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any) => Any]
        (input: InternalRow) => {
          val convertedRow: Row = inputEncoder.fromRow(input)
          func(convertedRow.get(0), convertedRow.get(1), convertedRow.get(2), convertedRow.get(3),
            convertedRow.get(4), convertedRow.get(5), convertedRow.get(6))
        }

      case 8 =>
        val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any) => Any]
        (input: InternalRow) => {
          val convertedRow: Row = inputEncoder.fromRow(input)
          func(convertedRow.get(0), convertedRow.get(1), convertedRow.get(2), convertedRow.get(3),
            convertedRow.get(4), convertedRow.get(5), convertedRow.get(6), convertedRow.get(7))
        }

      case 9 =>
        val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
        (input: InternalRow) => {
          val convertedRow: Row = inputEncoder.fromRow(input)
          func(convertedRow.get(0), convertedRow.get(1), convertedRow.get(2), convertedRow.get(3),
            convertedRow.get(4), convertedRow.get(5), convertedRow.get(6), convertedRow.get(7),
            convertedRow.get(8))
        }

      case 10 =>
        val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
        (input: InternalRow) => {
          val convertedRow: Row = inputEncoder.fromRow(input)
          func(convertedRow.get(0), convertedRow.get(1), convertedRow.get(2), convertedRow.get(3),
            convertedRow.get(4), convertedRow.get(5), convertedRow.get(6), convertedRow.get(7),
            convertedRow.get(8), convertedRow.get(9))
        }

      case 11 =>
        val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
        (input: InternalRow) => {
          val convertedRow: Row = inputEncoder.fromRow(input)
          func(convertedRow.get(0), convertedRow.get(1), convertedRow.get(2), convertedRow.get(3),
            convertedRow.get(4), convertedRow.get(5), convertedRow.get(6), convertedRow.get(7),
            convertedRow.get(8), convertedRow.get(9), convertedRow.get(10))
        }

      case 12 =>
        val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
        (input: InternalRow) => {
          val convertedRow: Row = inputEncoder.fromRow(input)
          func(convertedRow.get(0), convertedRow.get(1), convertedRow.get(2), convertedRow.get(3),
            convertedRow.get(4), convertedRow.get(5), convertedRow.get(6), convertedRow.get(7),
            convertedRow.get(8), convertedRow.get(9), convertedRow.get(10), convertedRow.get(11))
        }

      case 13 =>
        val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
        (input: InternalRow) => {
          val convertedRow: Row = inputEncoder.fromRow(input)
          func(convertedRow.get(0), convertedRow.get(1), convertedRow.get(2), convertedRow.get(3),
            convertedRow.get(4), convertedRow.get(5), convertedRow.get(6), convertedRow.get(7),
            convertedRow.get(8), convertedRow.get(9), convertedRow.get(10), convertedRow.get(11),
            convertedRow.get(12))
        }

      case 14 =>
        val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
        (input: InternalRow) => {
          val convertedRow: Row = inputEncoder.fromRow(input)
          func(convertedRow.get(0), convertedRow.get(1), convertedRow.get(2), convertedRow.get(3),
            convertedRow.get(4), convertedRow.get(5), convertedRow.get(6), convertedRow.get(7),
            convertedRow.get(8), convertedRow.get(9), convertedRow.get(10), convertedRow.get(11),
            convertedRow.get(12), convertedRow.get(13))
        }

      case 15 =>
        val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
        (input: InternalRow) => {
          val convertedRow: Row = inputEncoder.fromRow(input)
          func(convertedRow.get(0), convertedRow.get(1), convertedRow.get(2), convertedRow.get(3),
            convertedRow.get(4), convertedRow.get(5), convertedRow.get(6), convertedRow.get(7),
            convertedRow.get(8), convertedRow.get(9), convertedRow.get(10), convertedRow.get(11),
            convertedRow.get(12), convertedRow.get(13), convertedRow.get(14))
        }

      case 16 =>
        val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
        (input: InternalRow) => {
          val convertedRow: Row = inputEncoder.fromRow(input)
          func(convertedRow.get(0), convertedRow.get(1), convertedRow.get(2), convertedRow.get(3),
            convertedRow.get(4), convertedRow.get(5), convertedRow.get(6), convertedRow.get(7),
            convertedRow.get(8), convertedRow.get(9), convertedRow.get(10), convertedRow.get(11),
            convertedRow.get(12), convertedRow.get(13), convertedRow.get(14), convertedRow.get(15))
        }

      case 17 =>
        val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
        (input: InternalRow) => {
          val convertedRow: Row = inputEncoder.fromRow(input)
          func(convertedRow.get(0), convertedRow.get(1), convertedRow.get(2), convertedRow.get(3),
            convertedRow.get(4), convertedRow.get(5), convertedRow.get(6), convertedRow.get(7),
            convertedRow.get(8), convertedRow.get(9), convertedRow.get(10), convertedRow.get(11),
            convertedRow.get(12), convertedRow.get(13), convertedRow.get(14), convertedRow.get(15),
            convertedRow.get(16))
        }

      case 18 =>
        val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
        (input: InternalRow) => {
          val convertedRow: Row = inputEncoder.fromRow(input)
          func(convertedRow.get(0), convertedRow.get(1), convertedRow.get(2), convertedRow.get(3),
            convertedRow.get(4), convertedRow.get(5), convertedRow.get(6), convertedRow.get(7),
            convertedRow.get(8), convertedRow.get(9), convertedRow.get(10), convertedRow.get(11),
            convertedRow.get(12), convertedRow.get(13), convertedRow.get(14), convertedRow.get(15),
            convertedRow.get(16), convertedRow.get(17))
        }

      case 19 =>
        val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
        (input: InternalRow) => {
          val convertedRow: Row = inputEncoder.fromRow(input)
          func(convertedRow.get(0), convertedRow.get(1), convertedRow.get(2), convertedRow.get(3),
            convertedRow.get(4), convertedRow.get(5), convertedRow.get(6), convertedRow.get(7),
            convertedRow.get(8), convertedRow.get(9), convertedRow.get(10), convertedRow.get(11),
            convertedRow.get(12), convertedRow.get(13), convertedRow.get(14), convertedRow.get(15),
            convertedRow.get(16), convertedRow.get(17), convertedRow.get(18))
        }

      case 20 =>
        val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
        (input: InternalRow) => {
          val convertedRow: Row = inputEncoder.fromRow(input)
          func(convertedRow.get(0), convertedRow.get(1), convertedRow.get(2), convertedRow.get(3),
            convertedRow.get(4), convertedRow.get(5), convertedRow.get(6), convertedRow.get(7),
            convertedRow.get(8), convertedRow.get(9), convertedRow.get(10), convertedRow.get(11),
            convertedRow.get(12), convertedRow.get(13), convertedRow.get(14), convertedRow.get(15),
            convertedRow.get(16), convertedRow.get(17), convertedRow.get(18), convertedRow.get(19))
        }

      case 21 =>
        val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
        (input: InternalRow) => {
          val convertedRow: Row = inputEncoder.fromRow(input)
          func(convertedRow.get(0), convertedRow.get(1), convertedRow.get(2), convertedRow.get(3),
            convertedRow.get(4), convertedRow.get(5), convertedRow.get(6), convertedRow.get(7),
            convertedRow.get(8), convertedRow.get(9), convertedRow.get(10), convertedRow.get(11),
            convertedRow.get(12), convertedRow.get(13), convertedRow.get(14), convertedRow.get(15),
            convertedRow.get(16), convertedRow.get(17), convertedRow.get(18), convertedRow.get(19),
            convertedRow.get(20))
        }

      case 22 =>
        val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
        (input: InternalRow) => {
          val convertedRow: Row = inputEncoder.fromRow(input)
          func(convertedRow.get(0), convertedRow.get(1), convertedRow.get(2), convertedRow.get(3),
            convertedRow.get(4), convertedRow.get(5), convertedRow.get(6), convertedRow.get(7),
            convertedRow.get(8), convertedRow.get(9), convertedRow.get(10), convertedRow.get(11),
            convertedRow.get(12), convertedRow.get(13), convertedRow.get(14), convertedRow.get(15),
            convertedRow.get(16), convertedRow.get(17), convertedRow.get(18), convertedRow.get(19),
            convertedRow.get(20), convertedRow.get(21))
        }
    }
  }

  // scalastyle:on

  override def genCode(
      ctx: CodeGenContext,
      ev: GeneratedExpressionCode): String = {

    ctx.references += this
    val scalaUDFTermIdx = ctx.references.size - 1

    val scalaUDFClassName = classOf[ScalaUDF].getName
    val converterClassName = classOf[Any => Any].getName
    val typeConvertersClassName = CatalystTypeConverters.getClass.getName + ".MODULE$"
    val expressionClassName = classOf[Expression].getName
    val expressionEncoderClassName = classOf[ExpressionEncoder[Row]].getName
    val rowEncoderClassName = RowEncoder.getClass.getName + ".MODULE$"
    val structTypeClassName = StructType.getClass.getName + ".MODULE$"
    val rowClassName = Row.getClass.getName + ".MODULE$"
    val rowClass = classOf[Row].getName
    val internalRowClassName = classOf[InternalRow].getName
    // scalastyle:off
    val javaConversionClassName = scala.collection.JavaConversions.getClass.getName + ".MODULE$"
    // scalastyle:on

    // Generate code for input encoder
    val inputExpressionEncoderTerm = ctx.freshName("inputExpressionEncoder")
    ctx.addMutableState(expressionEncoderClassName, inputExpressionEncoderTerm,
      s"this.$inputExpressionEncoderTerm = ($expressionEncoderClassName)$rowEncoderClassName" +
        s".apply((($scalaUDFClassName)expressions" +
          s"[$scalaUDFTermIdx]).getInputSchema());")

    // Generate code for output encoder
    val outputExpressionEncoderTerm = ctx.freshName("outputExpressionEncoder")
    ctx.addMutableState(expressionEncoderClassName, outputExpressionEncoderTerm,
      s"this.$outputExpressionEncoderTerm = ($expressionEncoderClassName)$rowEncoderClassName" +
        s".apply((($scalaUDFClassName)expressions[$scalaUDFTermIdx]).getDataType());")

    val resultTerm = ctx.freshName("result")

    // Initialize user-defined function
    val funcClassName = s"scala.Function${children.size}"

    val funcTerm = ctx.freshName("udf")
    ctx.addMutableState(funcClassName, funcTerm,
      s"this.$funcTerm = ($funcClassName)((($scalaUDFClassName)expressions" +
        s"[$scalaUDFTermIdx]).userDefinedFunc());")

    // codegen for children expressions
    val evals = children.map(_.gen(ctx))
    val evalsArgs = evals.map(_.value).mkString(", ")
    val evalsAsSeq = s"$javaConversionClassName.asScalaIterable" +
      s"(java.util.Arrays.asList($evalsArgs)).toList()"
    val inputInternalRowTerm = ctx.freshName("inputRow")
    val inputInternalRow = s"$rowClass $inputInternalRowTerm = " +
      s"($rowClass)$inputExpressionEncoderTerm.fromRow(InternalRow.fromSeq($evalsAsSeq));"

    // Generate the codes for expressions and calling user-defined function
    // We need to get the boxedType of dataType's javaType here. Because for the dataType
    // such as IntegerType, its javaType is `int` and the returned type of user-defined
    // function is Object. Trying to convert an Object to `int` will cause casting exception.
    val evalCode = evals.map(_.code).mkString

    val funcArguments = (0 until children.size).map { i =>
      s"$inputInternalRowTerm.get($i)"
    }.mkString(", ")

    val rowParametersTerm = ctx.freshName("rowParameters")
    val innerRow = s"$rowClass $rowParametersTerm = $rowClassName.apply(" +
      s"$javaConversionClassName.asScalaIterable" +
      s"(java.util.Arrays.asList($funcTerm.apply($funcArguments))).toList());"
    val internalRowTerm = ctx.freshName("internalRow")
    val internalRow = s"$internalRowClassName $internalRowTerm = ($internalRowClassName)" +
      s"${outputExpressionEncoderTerm}.toRow($rowParametersTerm).copy();"

    val udfDataType = s"(($scalaUDFClassName)expressions[$scalaUDFTermIdx]).dataType()"
    val callFunc = s"${ctx.boxedType(ctx.javaType(dataType))} $resultTerm = " +
      s"(${ctx.boxedType(ctx.javaType(dataType))}) $internalRowTerm.get(0, $udfDataType);"

    evalCode + s"""
      ${ctx.javaType(dataType)} ${ev.value} = ${ctx.defaultValue(dataType)};
      Boolean ${ev.isNull};

      $inputInternalRow
      $innerRow
      $internalRow
      $callFunc

      ${ev.value} = $resultTerm;
      ${ev.isNull} = $resultTerm == null;
    """
  }

  lazy val outputEncoder: ExpressionEncoder[Row] =
      RowEncoder(StructType(StructField("_c0", dataType) :: Nil))

  override def eval(input: InternalRow): Any = {
    val projected = InternalRow.fromSeq(children.map(_.eval(input)))
    outputEncoder.toRow(Row(f(projected))).copy().asInstanceOf[InternalRow].get(0, dataType)
  }
}
