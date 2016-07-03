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
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.types._

class GeneratorExpressionSuite extends SparkFunSuite with ExpressionEvalHelper {
  private def checkTuple(actual: Expression, expected: Seq[InternalRow]): Unit = {
    assert(actual.eval(null).asInstanceOf[TraversableOnce[InternalRow]].toSeq === expected)
  }

  private final val empty_array = CreateArray(Seq.empty)
  private final val int_array = CreateArray(Seq(1, 2, 3).map(Literal(_)))
  private final val str_array = CreateArray(Seq("a", "b", "c").map(Literal(_)))

  test("explode") {
    val int_correct_answer = Seq(create_row(1), create_row(2), create_row(3))
    val str_correct_answer = Seq(create_row("a"), create_row("b"), create_row("c"))

    checkTuple(Explode(empty_array), Seq.empty)
    checkTuple(Explode(int_array), int_correct_answer)
    checkTuple(Explode(str_array), str_correct_answer)
  }

  test("posexplode") {
    val int_correct_answer = Seq(create_row(0, 1), create_row(1, 2), create_row(2, 3))
    val str_correct_answer = Seq(create_row(0, "a"), create_row(1, "b"), create_row(2, "c"))

    checkTuple(PosExplode(CreateArray(Seq.empty)), Seq.empty)
    checkTuple(PosExplode(int_array), int_correct_answer)
    checkTuple(PosExplode(str_array), str_correct_answer)
  }

  test("inline") {
    val correct_answer = Seq(create_row(0, "a"), create_row(1, "b"), create_row(2, "c"))

    checkTuple(
      Inline(Literal.create(Array(), ArrayType(new StructType().add("id", LongType)))),
      Seq.empty)

    checkTuple(
      Inline(CreateArray(Seq(
        CreateStruct(Seq(Literal(0), Literal("a"))),
        CreateStruct(Seq(Literal(1), Literal("b"))),
        CreateStruct(Seq(Literal(2), Literal("c")))
      ))),
      correct_answer)
  }

  test("stack") {
    checkTuple(Stack(Seq(1).map(Literal(_))), Seq(create_row()))
    checkTuple(Stack(Seq(1, 1).map(Literal(_))), Seq(create_row(1)))
    checkTuple(Stack(Seq(1, 1, 2).map(Literal(_))), Seq(create_row(1, 2)))
    checkTuple(Stack(Seq(2, 1, 2).map(Literal(_))), Seq(create_row(1), create_row(2)))
    checkTuple(Stack(Seq(2, 1, 2, 3).map(Literal(_))), Seq(create_row(1, 2), create_row(3, null)))

    checkTuple(
      Stack(Seq(2, "a", "b", "c").map(Literal(_))),
      Seq(create_row("a", "b"), create_row("c", null)))
  }
}
