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

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.types._


class LiteralExpressionSuite extends SparkFunSuite with ExpressionEvalHelper {

  test("null") {
    checkEvaluation(Literal.create(null, BooleanType), null)
    checkEvaluation(Literal.create(null, ByteType), null)
    checkEvaluation(Literal.create(null, ShortType), null)
    checkEvaluation(Literal.create(null, IntegerType), null)
    checkEvaluation(Literal.create(null, LongType), null)
    checkEvaluation(Literal.create(null, FloatType), null)
    checkEvaluation(Literal.create(null, LongType), null)
    checkEvaluation(Literal.create(null, StringType), null)
    checkEvaluation(Literal.create(null, BinaryType), null)
    checkEvaluation(Literal.create(null, DecimalType()), null)
    checkEvaluation(Literal.create(null, ArrayType(ByteType, true)), null)
    checkEvaluation(Literal.create(null, MapType(StringType, IntegerType)), null)
    checkEvaluation(Literal.create(null, StructType(Seq.empty)), null)
  }

  test("boolean literals") {
    checkEvaluation(Literal(true), true)
    checkEvaluation(Literal(false), false)
  }

  test("int literals") {
    List(0, 1, Int.MinValue, Int.MaxValue).foreach {
      d => {
        checkEvaluation(Literal(d), d)
        checkEvaluation(Literal(d.toLong), d.toLong)
        checkEvaluation(Literal(d.toShort), d.toShort)
        checkEvaluation(Literal(d.toByte), d.toByte)
      }
    }
    checkEvaluation(Literal(Long.MinValue), Long.MinValue)
    checkEvaluation(Literal(Long.MaxValue), Long.MaxValue)
  }

  test("double literals") {
    List(0.0, -0.0, Double.NegativeInfinity, Double.PositiveInfinity).foreach {
      d => {
        checkEvaluation(Literal(d), d)
        checkEvaluation(Literal(d.toFloat), d.toFloat)
      }
    }
    checkEvaluation(Literal(Double.MinValue), Double.MinValue)
    checkEvaluation(Literal(Double.MaxValue), Double.MaxValue)
    checkEvaluation(Literal(Float.MinValue), Float.MinValue)
    checkEvaluation(Literal(Float.MaxValue), Float.MaxValue)

  }

  test("string literals") {
    checkEvaluation(Literal(""), "")
    checkEvaluation(Literal("test"), "test")
    checkEvaluation(Literal("\0"), "\0")
  }

  test("sum two literals") {
    checkEvaluation(Add(Literal(1), Literal(1)), 2)
  }

  test("binary literals") {
    checkEvaluation(Literal.create(new Array[Byte](0), BinaryType), new Array[Byte](0))
    checkEvaluation(Literal.create(new Array[Byte](2), BinaryType), new Array[Byte](2))
  }

  test("decimal") {
    List(0.0, 1.2, 1.1111, 5).foreach { d =>
      checkEvaluation(Literal(Decimal(d)), Decimal(d))
      checkEvaluation(Literal(Decimal(d.toInt)), Decimal(d.toInt))
      checkEvaluation(Literal(Decimal(d.toLong)), Decimal(d.toLong))
      checkEvaluation(Literal(Decimal((d * 1000L).toLong, 10, 1)),
        Decimal((d * 1000L).toLong, 10, 1))
    }
  }

  // TODO(davies): add tests for ArrayType, MapType and StructType
}
