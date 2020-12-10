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

import org.apache.spark.{SPARK_REVISION, SPARK_VERSION_SHORT}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.codegen._
import org.apache.spark.sql.catalyst.expressions.codegen.Block._
import org.apache.spark.sql.catalyst.util.RandomUUIDGenerator
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String

/**
 * Print the result of an expression to stderr (used for debugging codegen).
 */
case class PrintToStderr(child: Expression) extends UnaryExpression {

  override def dataType: DataType = child.dataType

  protected override def nullSafeEval(input: Any): Any = {
    // scalastyle:off println
    System.err.println(outputPrefix + input)
    // scalastyle:on println
    input
  }

  private val outputPrefix = s"Result of ${child.simpleString(SQLConf.get.maxToStringFields)} is "

  override def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val outputPrefixField = ctx.addReferenceObj("outputPrefix", outputPrefix)
    nullSafeCodeGen(ctx, ev, c =>
      s"""
         | System.err.println($outputPrefixField + $c);
         | ${ev.value} = $c;
       """.stripMargin)
  }
}

/**
 * Throw with the result of an expression (used for debugging).
 */
@ExpressionDescription(
  usage = "_FUNC_(expr) - Throws an exception with `expr`.",
  examples = """
    Examples:
      > SELECT _FUNC_('custom error message');
       java.lang.RuntimeException
       custom error message
  """,
  since = "3.1.0")
case class RaiseError(child: Expression, error: Option[Throwable] = None)
  extends UnaryExpression with ImplicitCastInputTypes {

  def this(child: Expression) = this(child, None)

  override def nullable: Boolean = true
  override def dataType: DataType = NullType
  override def inputTypes: Seq[AbstractDataType] = Seq(StringType)

  override def prettyName: String = "raise_error"

  private def errorHashCode: Int = error match {
    case Some(err) => 31 * err.getClass.hashCode() + err.getMessage.hashCode
    case None => 0
  }

  override def hashCode(): Int = 31 * child.hashCode() + errorHashCode

  private def errorEquals(other: Option[Throwable]): Boolean = (error, other) match {
    case (Some(e), Some(o)) => e.getClass == o.getClass && e.getMessage == o.getMessage
    case (None, None) => true
    case _ => false
  }

  override def equals(other: Any): Boolean = other match {
    case o: RaiseError => child == o.child && errorEquals(o.error)
    case _ => false
  }

  override def eval(input: InternalRow): Any = {
    error match {
      case Some(err) => throw err
      case None =>
        val value = child.eval(input)
        if (value == null) {
          throw new RuntimeException()
        }
        throw new RuntimeException(value.toString)
    }
  }

  override def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val block = error match {
      case Some(err) => genCodeWithError(ctx, err)
      case None => genCodeWithMessage(ctx)
    }
    ExprCode(
      code = block,
      isNull = TrueLiteral,
      value = JavaCode.defaultLiteral(dataType)
    )
  }

  // if (true) is to avoid codegen compilation exception that statement is unreachable
  private def genCodeWithError(ctx: CodegenContext, error: Throwable): Block = {
    val errorTerm = ctx.addReferenceObj("error", error)
    code"""if (true) throw $errorTerm;"""
  }

  private def genCodeWithMessage(ctx: CodegenContext): Block = {
    val eval = child.genCode(ctx)
    code"""${eval.code}
      |if (true) {
      |  if (${eval.isNull}) {
      |    throw new RuntimeException();
      |  }
      |  throw new RuntimeException(${eval.value}.toString());
      |}""".stripMargin
  }
}

object RaiseError {
  def apply(error: Throwable): RaiseError = {
    new RaiseError(Literal.create(error.getMessage, StringType), Some(error))
  }
}

/**
 * A function that throws an exception if 'condition' is not true.
 */
@ExpressionDescription(
  usage = "_FUNC_(expr) - Throws an exception if `expr` is not true.",
  examples = """
    Examples:
      > SELECT _FUNC_(0 < 1);
       NULL
  """,
  since = "2.0.0")
case class AssertTrue(left: Expression, right: Expression, child: Expression)
  extends RuntimeReplaceable {

  override def prettyName: String = "assert_true"

  def this(left: Expression, right: Expression) = {
    this(left, right, If(left, Literal(null), RaiseError(right)))
  }

  def this(left: Expression) = {
    this(left, Literal(s"'${left.simpleString(SQLConf.get.maxToStringFields)}' is not true!"))
  }

  override def flatArguments: Iterator[Any] = Iterator(left, right)
  override def exprsReplaced: Seq[Expression] = Seq(left, right)
}

object AssertTrue {
  def apply(left: Expression): AssertTrue = new AssertTrue(left)
}

/**
 * Returns the current database of the SessionCatalog.
 */
@ExpressionDescription(
  usage = "_FUNC_() - Returns the current database.",
  examples = """
    Examples:
      > SELECT _FUNC_();
       default
  """,
  since = "1.6.0")
case class CurrentDatabase() extends LeafExpression with Unevaluable {
  override def dataType: DataType = StringType
  override def nullable: Boolean = false
  override def prettyName: String = "current_database"
}

/**
 * Returns the current catalog.
 */
@ExpressionDescription(
  usage = "_FUNC_() - Returns the current catalog.",
  examples = """
    Examples:
      > SELECT _FUNC_();
       spark_catalog
  """,
  since = "3.1.0")
case class CurrentCatalog() extends LeafExpression with Unevaluable {
  override def dataType: DataType = StringType
  override def nullable: Boolean = false
  override def prettyName: String = "current_catalog"
}

// scalastyle:off line.size.limit
@ExpressionDescription(
  usage = """_FUNC_() - Returns an universally unique identifier (UUID) string. The value is returned as a canonical UUID 36-character string.""",
  examples = """
    Examples:
      > SELECT _FUNC_();
       46707d92-02f4-4817-8116-a4c3b23e6266
  """,
  note = """
    The function is non-deterministic.
  """,
  since = "2.3.0")
// scalastyle:on line.size.limit
case class Uuid(randomSeed: Option[Long] = None) extends LeafExpression with Stateful
    with ExpressionWithRandomSeed {

  def this() = this(None)

  override def withNewSeed(seed: Long): Uuid = Uuid(Some(seed))

  override lazy val resolved: Boolean = randomSeed.isDefined

  override def nullable: Boolean = false

  override def dataType: DataType = StringType

  @transient private[this] var randomGenerator: RandomUUIDGenerator = _

  override protected def initializeInternal(partitionIndex: Int): Unit =
    randomGenerator = RandomUUIDGenerator(randomSeed.get + partitionIndex)

  override protected def evalInternal(input: InternalRow): Any =
    randomGenerator.getNextUUIDUTF8String()

  override def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val randomGen = ctx.freshName("randomGen")
    ctx.addMutableState("org.apache.spark.sql.catalyst.util.RandomUUIDGenerator", randomGen,
      forceInline = true,
      useFreshName = false)
    ctx.addPartitionInitializationStatement(s"$randomGen = " +
      "new org.apache.spark.sql.catalyst.util.RandomUUIDGenerator(" +
      s"${randomSeed.get}L + partitionIndex);")
    ev.copy(code = code"final UTF8String ${ev.value} = $randomGen.getNextUUIDUTF8String();",
      isNull = FalseLiteral)
  }

  override def freshCopy(): Uuid = Uuid(randomSeed)
}

// scalastyle:off line.size.limit
@ExpressionDescription(
  usage = """_FUNC_() - Returns the Spark version. The string contains 2 fields, the first being a release version and the second being a git revision.""",
  examples = """
    Examples:
      > SELECT _FUNC_();
       3.1.0 a6d6ea3efedbad14d99c24143834cd4e2e52fb40
  """,
  since = "3.0.0")
// scalastyle:on line.size.limit
case class SparkVersion() extends LeafExpression with CodegenFallback {
  override def nullable: Boolean = false
  override def foldable: Boolean = true
  override def dataType: DataType = StringType
  override def prettyName: String = "version"
  override def eval(input: InternalRow): Any = {
    UTF8String.fromString(SPARK_VERSION_SHORT + " " + SPARK_REVISION)
  }
}

@ExpressionDescription(
  usage = """_FUNC_(expr) - Return DDL-formatted type string for the data type of the input.""",
  examples = """
    Examples:
      > SELECT _FUNC_(1);
       int
      > SELECT _FUNC_(array(1));
       array<int>
  """,
  since = "3.0.0")
case class TypeOf(child: Expression) extends UnaryExpression {
  override def nullable: Boolean = false
  override def foldable: Boolean = true
  override def dataType: DataType = StringType
  override def eval(input: InternalRow): Any = UTF8String.fromString(child.dataType.catalogString)

  override def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    defineCodeGen(ctx, ev, _ => s"""UTF8String.fromString("${child.dataType.catalogString}")""")
  }
}
