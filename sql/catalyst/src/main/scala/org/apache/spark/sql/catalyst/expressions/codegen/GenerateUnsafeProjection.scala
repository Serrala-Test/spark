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

import org.apache.commons.text.StringEscapeUtils

import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.BindReferences.bindReferences
import org.apache.spark.sql.catalyst.expressions.codegen.Block._
import org.apache.spark.sql.types._

/**
 * Generates a [[Projection]] that returns an [[UnsafeRow]].
 *
 * It generates the code for all the expressions, computes the total length for all the columns
 * (can be accessed via variables), and then copies the data into a scratch buffer space in the
 * form of UnsafeRow (the scratch buffer will grow as needed).
 *
 * @note The returned UnsafeRow will be pointed to a scratch buffer inside the projection.
 */
object GenerateUnsafeProjection extends CodeGenerator[Seq[Expression], UnsafeProjection] {

  /**
   * @param dataType The data type being projected
   * @param nullable Whether or not it is nullable
   * @param descPath The "path" to this projection, with all descriptors along the way. For example
   *                 with a schema like `f1: STRUCT<f2: ARRAY<STRUCT<f3: INT>>>`, when projecting
   *                 `f3` this path would be like `Seq("<pos 0>", "<array element>", "`f3`")`
   *                 (names aren't available at the top level so a position is used instead).
   */
  case class Schema(dataType: DataType, nullable: Boolean, descPath: Seq[String])

  /** Returns true iff we support this data type. */
  def canSupport(dataType: DataType): Boolean = UserDefinedType.sqlType(dataType) match {
    case NullType => true
    case _: AtomicType => true
    case _: CalendarIntervalType => true
    case t: StructType => t.forall(field => canSupport(field.dataType))
    case t: ArrayType if canSupport(t.elementType) => true
    case MapType(kt, vt, _) if canSupport(kt) && canSupport(vt) => true
    case _ => false
  }

  private def writeStructToBuffer(
      ctx: CodegenContext,
      input: String,
      index: String,
      schemas: Seq[Schema],
      rowWriter: String): String = {
    // Puts `input` in a local variable to avoid to re-evaluate it if it's a statement.
    val tmpInput = ctx.freshName("tmpInput")
    val fieldEvals = schemas.zipWithIndex.map { case (Schema(dt, nullable, _), i) =>
      val isNull = if (nullable) {
        JavaCode.isNullExpression(s"$tmpInput.isNullAt($i)")
      } else {
        FalseLiteral
      }
      ExprCode(isNull, JavaCode.expression(CodeGenerator.getValue(tmpInput, dt, i.toString), dt))
    }

    val rowWriterClass = classOf[UnsafeRowWriter].getName
    val structRowWriter = ctx.addMutableState(rowWriterClass, "rowWriter",
      v => s"$v = new $rowWriterClass($rowWriter, ${fieldEvals.length});")
    val previousCursor = ctx.freshName("previousCursor")
    s"""
       |final InternalRow $tmpInput = $input;
       |if ($tmpInput instanceof UnsafeRow) {
       |  $rowWriter.write($index, (UnsafeRow) $tmpInput);
       |} else {
       |  // Remember the current cursor so that we can calculate how many bytes are
       |  // written later.
       |  final int $previousCursor = $rowWriter.cursor();
       |  ${writeExpressionsToBuffer(ctx, tmpInput, fieldEvals, schemas, structRowWriter)}
       |  $rowWriter.setOffsetAndSizeFromPreviousCursor($index, $previousCursor);
       |}
     """.stripMargin
  }

  private def writeExpressionsToBuffer(
      ctx: CodegenContext,
      row: String,
      inputs: Seq[ExprCode],
      schemas: Seq[Schema],
      rowWriter: String,
      isTopLevel: Boolean = false): String = {
    val resetWriter = if (isTopLevel) {
      // For top level row writer, it always writes to the beginning of the global buffer holder,
      // which means its fixed-size region always in the same position, so we don't need to call
      // `reset` to set up its fixed-size region every time.
      if (inputs.map(_.isNull).forall(_ == FalseLiteral)) {
        // If all fields are not nullable, which means the null bits never changes, then we don't
        // need to clear it out every time.
        ""
      } else {
        s"$rowWriter.zeroOutNullBytes();"
      }
    } else {
      s"$rowWriter.resetRowWriter();"
    }

    val writeFields = inputs.zip(schemas).zipWithIndex.map {
      case ((input, Schema(dataType, nullable, descPath)), index) =>
        val dt = UserDefinedType.sqlType(dataType)

        val setNull = dt match {
          case t: DecimalType if t.precision > Decimal.MAX_LONG_DIGITS =>
            // Can't call setNullAt() for DecimalType with precision larger than 18.
            s"$rowWriter.write($index, (Decimal) null, ${t.precision}, ${t.scale});"
          case CalendarIntervalType => s"$rowWriter.write($index, (CalendarInterval) null);"
          case _ => s"$rowWriter.setNullAt($index);"
        }

        val writeField = writeElement(ctx, input.value, index.toString, dt, rowWriter, descPath)
        if (!nullable) {
          s"""
             |${input.code}
             |${wrapWithNpeHandling(writeField, descPath).trim}
           """.stripMargin
        } else {
          s"""
             |${input.code}
             |if (${input.isNull}) {
             |  ${setNull.trim}
             |} else {
             |  ${writeField.trim}
             |}
           """.stripMargin
        }
    }

    val writeFieldsCode = if (isTopLevel && (row == null || ctx.currentVars != null)) {
      // TODO: support whole stage codegen
      writeFields.mkString("\n")
    } else {
      assert(row != null, "the input row name cannot be null when generating code to write it.")
      ctx.splitExpressions(
        expressions = writeFields,
        funcName = "writeFields",
        arguments = Seq("InternalRow" -> row))
    }
    s"""
       |$resetWriter
       |$writeFieldsCode
     """.stripMargin
  }

  private def writeArrayToBuffer(
      ctx: CodegenContext,
      input: String,
      elementType: DataType,
      containsNull: Boolean,
      rowWriter: String,
      descPath: Seq[String]): String = {
    // Puts `input` in a local variable to avoid to re-evaluate it if it's a statement.
    val tmpInput = ctx.freshName("tmpInput")
    val numElements = ctx.freshName("numElements")
    val index = ctx.freshName("index")

    val et = UserDefinedType.sqlType(elementType)

    val jt = CodeGenerator.javaType(et)

    val elementOrOffsetSize = et match {
      case t: DecimalType if t.precision <= Decimal.MAX_LONG_DIGITS => 8
      case _ if CodeGenerator.isPrimitiveType(jt) => et.defaultSize
      case _ => 8  // we need 8 bytes to store offset and length
    }

    val arrayWriterClass = classOf[UnsafeArrayWriter].getName
    val arrayWriter = ctx.addMutableState(arrayWriterClass, "arrayWriter",
      v => s"$v = new $arrayWriterClass($rowWriter, $elementOrOffsetSize);")

    val element = CodeGenerator.getValue(tmpInput, et, index)

    val writeElem = writeElement(ctx, element, index, et, arrayWriter, descPath)
    val elementAssignment = if (containsNull) {
      s"""
         |if ($tmpInput.isNullAt($index)) {
         |  $arrayWriter.setNull${elementOrOffsetSize}Bytes($index);
         |} else {
         |  $writeElem
         |}
       """.stripMargin
    } else {
      wrapWithNpeHandling(writeElem, descPath)
    }

    s"""
       |final ArrayData $tmpInput = $input;
       |if ($tmpInput instanceof UnsafeArrayData) {
       |  $rowWriter.write((UnsafeArrayData) $tmpInput);
       |} else {
       |  final int $numElements = $tmpInput.numElements();
       |  $arrayWriter.initialize($numElements);
       |
       |  for (int $index = 0; $index < $numElements; $index++) {
       |    $elementAssignment
       |  }
       |}
     """.stripMargin
  }

  private def writeMapToBuffer(
      ctx: CodegenContext,
      input: String,
      index: String,
      keyType: DataType,
      valueType: DataType,
      valueContainsNull: Boolean,
      rowWriter: String,
      descPath: Seq[String]): String = {
    // Puts `input` in a local variable to avoid to re-evaluate it if it's a statement.
    val tmpInput = ctx.freshName("tmpInput")
    val tmpCursor = ctx.freshName("tmpCursor")
    val previousCursor = ctx.freshName("previousCursor")

    // Writes out unsafe map according to the format described in `UnsafeMapData`.
    val keyArray = writeArrayToBuffer(
      ctx, s"$tmpInput.keyArray()", keyType, false, rowWriter, descPath :+ "<MAP_KEY>")
    val valueArray = writeArrayToBuffer(
      ctx, s"$tmpInput.valueArray()", valueType, valueContainsNull, rowWriter,
      descPath :+ "<MAP_VALUE>")

    s"""
       |final MapData $tmpInput = $input;
       |if ($tmpInput instanceof UnsafeMapData) {
       |  $rowWriter.write($index, (UnsafeMapData) $tmpInput);
       |} else {
       |  // Remember the current cursor so that we can calculate how many bytes are
       |  // written later.
       |  final int $previousCursor = $rowWriter.cursor();
       |
       |  // preserve 8 bytes to write the key array numBytes later.
       |  $rowWriter.grow(8);
       |  $rowWriter.increaseCursor(8);
       |
       |  // Remember the current cursor so that we can write numBytes of key array later.
       |  final int $tmpCursor = $rowWriter.cursor();
       |
       |  $keyArray
       |
       |  // Write the numBytes of key array into the first 8 bytes.
       |  Platform.putLong(
       |    $rowWriter.getBuffer(),
       |    $tmpCursor - 8,
       |    $rowWriter.cursor() - $tmpCursor);
       |
       |  $valueArray
       |  $rowWriter.setOffsetAndSizeFromPreviousCursor($index, $previousCursor);
       |}
     """.stripMargin
  }

  /**
   * Wrap `inputExpr` in a try-catch block that will catch any [[NullPointerException]] that is
   * thrown, instead throwing a (more helpful) error message as provided by
   * [[org.apache.spark.sql.errors.QueryExecutionErrors.valueCannotBeNullError]].
   */
  private def wrapWithNpeHandling(inputExpr: String, descPath: Seq[String]): String =
    s"""
       |try {
       |  ${inputExpr.trim}
       |} catch (NullPointerException npe) {
       |  throw QueryExecutionErrors.valueCannotBeNullError(
       |      "${descPath.map(StringEscapeUtils.escapeJava).mkString(".")}");
       |}
    """.stripMargin

  private def writeElement(
      ctx: CodegenContext,
      input: String,
      index: String,
      dt: DataType,
      writer: String,
      descPath: Seq[String]): String = dt match {
    case t: StructType =>
      val schemas = t.map(e => Schema(e.dataType, e.nullable, descPath :+ s"`${e.name}`"))
      writeStructToBuffer(ctx, input, index, schemas, writer)

    case ArrayType(et, en) =>
      val previousCursor = ctx.freshName("previousCursor")
      s"""
         |// Remember the current cursor so that we can calculate how many bytes are
         |// written later.
         |final int $previousCursor = $writer.cursor();
         |${writeArrayToBuffer(ctx, input, et, en, writer, descPath :+ "<ARRAY_ELEMENT>")}
         |$writer.setOffsetAndSizeFromPreviousCursor($index, $previousCursor);
       """.stripMargin

    case MapType(kt, vt, vn) =>
      writeMapToBuffer(ctx, input, index, kt, vt, vn, writer, descPath)

    case DecimalType.Fixed(precision, scale) =>
      s"$writer.write($index, $input, $precision, $scale);"

    case NullType => ""

    case _ => s"$writer.write($index, $input);"
  }

  def createCode(
      ctx: CodegenContext,
      expressions: Seq[Expression],
      useSubexprElimination: Boolean = false): ExprCode = {
    val exprEvals = ctx.generateExpressions(expressions, useSubexprElimination)
    val exprSchemas = expressions.zipWithIndex.map { case (e, idx) =>
      Schema(e.dataType, e.nullable, Seq(s"<POS_$idx>"))
    }

    val numVarLenFields = exprSchemas.count {
      case Schema(dt, _, _) => !UnsafeRow.isFixedLength(dt)
      // TODO: consider large decimal and interval type
    }

    val rowWriterClass = classOf[UnsafeRowWriter].getName
    val rowWriter = ctx.addMutableState(rowWriterClass, "rowWriter",
      v => s"$v = new $rowWriterClass(${expressions.length}, ${numVarLenFields * 32});")

    // Evaluate all the subexpression.
    val evalSubexpr = ctx.subexprFunctionsCode

    val writeExpressions = writeExpressionsToBuffer(
      ctx, ctx.INPUT_ROW, exprEvals, exprSchemas, rowWriter, isTopLevel = true)

    val code =
      code"""
         |$rowWriter.reset();
         |$evalSubexpr
         |$writeExpressions
       """.stripMargin
    // `rowWriter` is declared as a class field, so we can access it directly in methods.
    ExprCode(code, FalseLiteral, JavaCode.expression(s"$rowWriter.getRow()", classOf[UnsafeRow]))
  }

  protected def canonicalize(in: Seq[Expression]): Seq[Expression] =
    in.map(ExpressionCanonicalizer.execute)

  protected def bind(in: Seq[Expression], inputSchema: Seq[Attribute]): Seq[Expression] =
    bindReferences(in, inputSchema)

  def generate(
      expressions: Seq[Expression],
      subexpressionEliminationEnabled: Boolean): UnsafeProjection = {
    create(canonicalize(expressions), subexpressionEliminationEnabled)
  }

  protected def create(references: Seq[Expression]): UnsafeProjection = {
    create(references, subexpressionEliminationEnabled = false)
  }

  private def create(
      expressions: Seq[Expression],
      subexpressionEliminationEnabled: Boolean): UnsafeProjection = {
    val ctx = newCodeGenContext()
    val eval = createCode(ctx, expressions, subexpressionEliminationEnabled)

    val codeBody =
      s"""
         |public java.lang.Object generate(Object[] references) {
         |  return new SpecificUnsafeProjection(references);
         |}
         |
         |class SpecificUnsafeProjection extends ${classOf[UnsafeProjection].getName} {
         |
         |  private Object[] references;
         |  ${ctx.declareMutableStates()}
         |
         |  public SpecificUnsafeProjection(Object[] references) {
         |    this.references = references;
         |    ${ctx.initMutableStates()}
         |  }
         |
         |  public void initialize(int partitionIndex) {
         |    ${ctx.initPartition()}
         |  }
         |
         |  // Scala.Function1 need this
         |  public java.lang.Object apply(java.lang.Object row) {
         |    return apply((InternalRow) row);
         |  }
         |
         |  public UnsafeRow apply(InternalRow ${ctx.INPUT_ROW}) {
         |    ${eval.code}
         |    return ${eval.value};
         |  }
         |
         |  ${ctx.declareAddedFunctions()}
         |}
       """.stripMargin

    val code = CodeFormatter.stripOverlappingComments(
      new CodeAndComment(codeBody, ctx.getPlaceHolderToComments()))
    logDebug(s"code for ${expressions.mkString(",")}:\n${CodeFormatter.format(code)}")

    val (clazz, _) = CodeGenerator.compile(code)
    clazz.generate(ctx.references.toArray).asInstanceOf[UnsafeProjection]
  }
}
