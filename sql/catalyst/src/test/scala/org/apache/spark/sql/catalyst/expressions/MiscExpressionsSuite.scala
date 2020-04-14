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

import java.io.PrintStream

import scala.util.Random

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._

class MiscExpressionsSuite extends SparkFunSuite with ExpressionEvalHelper {

  test("assert_true") {
    intercept[RuntimeException] {
      checkEvaluation(AssertTrue(Literal.create(false, BooleanType)), null)
    }
    intercept[RuntimeException] {
      checkEvaluation(AssertTrue(Cast(Literal(0), BooleanType)), null)
    }
    intercept[RuntimeException] {
      checkEvaluation(AssertTrue(Literal.create(null, NullType)), null)
    }
    intercept[RuntimeException] {
      checkEvaluation(AssertTrue(Literal.create(null, BooleanType)), null)
    }
    checkEvaluation(AssertTrue(Literal.create(true, BooleanType)), null)
    checkEvaluation(AssertTrue(Cast(Literal(1), BooleanType)), null)
  }

  test("uuid") {
    checkEvaluation(Length(Uuid(Some(0))), 36)
    val r = new Random()
    val seed1 = Some(r.nextLong())
    assert(evaluateWithoutCodegen(Uuid(seed1)) === evaluateWithoutCodegen(Uuid(seed1)))
    assert(evaluateWithMutableProjection(Uuid(seed1)) ===
      evaluateWithMutableProjection(Uuid(seed1)))
    assert(evaluateWithUnsafeProjection(Uuid(seed1)) ===
      evaluateWithUnsafeProjection(Uuid(seed1)))

    val seed2 = Some(r.nextLong())
    assert(evaluateWithoutCodegen(Uuid(seed1)) !== evaluateWithoutCodegen(Uuid(seed2)))
    assert(evaluateWithMutableProjection(Uuid(seed1)) !==
      evaluateWithMutableProjection(Uuid(seed2)))
    assert(evaluateWithUnsafeProjection(Uuid(seed1)) !==
      evaluateWithUnsafeProjection(Uuid(seed2)))

    val uuid = Uuid(seed1)
    assert(uuid.fastEquals(uuid))
    assert(!uuid.fastEquals(Uuid(seed1)))
    assert(!uuid.fastEquals(uuid.freshCopy()))
    assert(!uuid.fastEquals(Uuid(seed2)))
  }

  test("PrintToStderr") {
    val inputExpr = Literal(1)
    val systemErr = System.err

    val (outputEval, outputCodegen) = try {
      val errorStream = new java.io.ByteArrayOutputStream()
      System.setErr(new PrintStream(errorStream))
      // check without codegen
      checkEvaluationWithoutCodegen(PrintToStderr(inputExpr), 1)
      val outputEval = errorStream.toString
      errorStream.reset()
      // check with codegen
      checkEvaluationWithMutableProjection(PrintToStderr(inputExpr), 1)
      val outputCodegen = errorStream.toString
      (outputEval, outputCodegen)
    } finally {
      System.setErr(systemErr)
    }

    assert(outputCodegen.contains(s"Result of $inputExpr is 1"))
    assert(outputEval.contains(s"Result of $inputExpr is 1"))
  }

  test("try expression") {
    val e = intercept[RuntimeException] {
      val try1 = TryExpression(AssertTrue(Literal.create(false, BooleanType)))
      checkEvaluation(try1, null)
    }
    assert(e.getCause.isInstanceOf[RuntimeException])
    assert(e.getCause.getMessage.contains("is not true"))

    checkEvaluation(TryExpression(AnsiCast(Literal("N A N"), DoubleType)), null)
    checkEvaluation(TryExpression(AnsiCast(Literal(128), ByteType)), null)
    checkEvaluation(TryExpression(FormatString(Literal("%s"))), null)

    val maxIntLiteral = Literal(Int.MaxValue)
    val minIntLiteral = Literal(Int.MinValue)
    val e1 = Add(maxIntLiteral, Literal(1))
    val e2 = Subtract(maxIntLiteral, Literal(-1))
    val e3 = Multiply(maxIntLiteral, Literal(2))
    val e4 = Add(minIntLiteral, minIntLiteral)
    val e5 = Subtract(minIntLiteral, maxIntLiteral)
    val e6 = Multiply(minIntLiteral, minIntLiteral)
    withSQLConf(SQLConf.ANSI_ENABLED.key -> "true") {
      Seq(e1, e2, e3, e4, e5, e6).foreach { e =>
        checkEvaluation(TryExpression(e), null)
      }
    }
  }
}
