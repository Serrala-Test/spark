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

class HigherOrderFunctionsSuite extends SparkFunSuite with ExpressionEvalHelper {
  import org.apache.spark.sql.catalyst.dsl.expressions._

  private def createLambda(
      dt: DataType,
      nullable: Boolean,
      f: Expression => Expression): Expression = {
    val lv = NamedLambdaVariable("arg", dt, nullable)
    val function = f(lv)
    LambdaFunction(function, Seq(lv))
  }

  private def createLambda(
      dt1: DataType,
      nullable1: Boolean,
      dt2: DataType,
      nullable2: Boolean,
      f: (Expression, Expression) => Expression): Expression = {
    val lv1 = NamedLambdaVariable("arg1", dt1, nullable1)
    val lv2 = NamedLambdaVariable("arg2", dt2, nullable2)
    val function = f(lv1, lv2)
    LambdaFunction(function, Seq(lv1, lv2))
  }

  def transform(expr: Expression, f: Expression => Expression): Expression = {
    val at = expr.dataType.asInstanceOf[ArrayType]
    ArrayTransform(expr, createLambda(at.elementType, at.containsNull, f))
  }

  def transform(expr: Expression, f: (Expression, Expression) => Expression): Expression = {
    val at = expr.dataType.asInstanceOf[ArrayType]
    ArrayTransform(expr, createLambda(at.elementType, at.containsNull, IntegerType, false, f))
  }

  test("ArrayTransform") {
    val ai0 = Literal.create(Seq(1, 2, 3), ArrayType(IntegerType, containsNull = false))
    val ai1 = Literal.create(Seq[Integer](1, null, 3), ArrayType(IntegerType, containsNull = true))
    val ain = Literal.create(null, ArrayType(IntegerType, containsNull = false))

    val plusOne: Expression => Expression = x => x + 1
    val plusIndex: (Expression, Expression) => Expression = (x, i) => x + i

    checkEvaluation(transform(ai0, plusOne), Seq(2, 3, 4))
    checkEvaluation(transform(ai0, plusIndex), Seq(1, 3, 5))
    checkEvaluation(transform(transform(ai0, plusIndex), plusOne), Seq(2, 4, 6))
    checkEvaluation(transform(ai1, plusOne), Seq(2, null, 4))
    checkEvaluation(transform(ai1, plusIndex), Seq(1, null, 5))
    checkEvaluation(transform(transform(ai1, plusIndex), plusOne), Seq(2, null, 6))
    checkEvaluation(transform(ain, plusOne), null)

    val as0 = Literal.create(Seq("a", "b", "c"), ArrayType(StringType, containsNull = false))
    val as1 = Literal.create(Seq("a", null, "c"), ArrayType(StringType, containsNull = true))
    val asn = Literal.create(null, ArrayType(StringType, containsNull = false))

    val repeatTwice: Expression => Expression = x => Concat(Seq(x, x))
    val repeatIndexTimes: (Expression, Expression) => Expression = (x, i) => StringRepeat(x, i)

    checkEvaluation(transform(as0, repeatTwice), Seq("aa", "bb", "cc"))
    checkEvaluation(transform(as0, repeatIndexTimes), Seq("", "b", "cc"))
    checkEvaluation(transform(transform(as0, repeatIndexTimes), repeatTwice),
      Seq("", "bb", "cccc"))
    checkEvaluation(transform(as1, repeatTwice), Seq("aa", null, "cc"))
    checkEvaluation(transform(as1, repeatIndexTimes), Seq("", null, "cc"))
    checkEvaluation(transform(transform(as1, repeatIndexTimes), repeatTwice),
      Seq("", null, "cccc"))
    checkEvaluation(transform(asn, repeatTwice), null)

    val aai = Literal.create(Seq(Seq(1, 2, 3), null, Seq(4, 5)),
      ArrayType(ArrayType(IntegerType, containsNull = false), containsNull = true))
    checkEvaluation(transform(aai, array => Cast(transform(array, plusOne), StringType)),
      Seq("[2, 3, 4]", null, "[5, 6]"))
    checkEvaluation(transform(aai, array => Cast(transform(array, plusIndex), StringType)),
      Seq("[1, 3, 5]", null, "[4, 6]"))
  }

  test("MapFilter") {
    def mapFilter(expr: Expression, f: (Expression, Expression) => Expression): Expression = {
      val mt = expr.dataType.asInstanceOf[MapType]
      MapFilter(expr, createLambda(mt.keyType, false, mt.valueType, mt.valueContainsNull, f))
    }
    val mii0 = Literal.create(Map(1 -> 0, 2 -> 10, 3 -> -1),
      MapType(IntegerType, IntegerType, valueContainsNull = false))
    val mii1 = Literal.create(Map(1 -> null, 2 -> 10, 3 -> null),
      MapType(IntegerType, IntegerType, valueContainsNull = true))
    val miin = Literal.create(null, MapType(IntegerType, IntegerType, valueContainsNull = false))

    val kGreaterThanV: (Expression, Expression) => Expression = (k, v) => k > v

    checkEvaluation(mapFilter(mii0, kGreaterThanV), Map(1 -> 0, 3 -> -1))
    checkEvaluation(mapFilter(mii1, kGreaterThanV), Map())
    checkEvaluation(mapFilter(miin, kGreaterThanV), null)

    val valueNull: (Expression, Expression) => Expression = (_, v) => v.isNull

    checkEvaluation(mapFilter(mii0, valueNull), Map())
    checkEvaluation(mapFilter(mii1, valueNull), Map(1 -> null, 3 -> null))
    checkEvaluation(mapFilter(miin, valueNull), null)

    val msi0 = Literal.create(Map("abcdf" -> 5, "abc" -> 10, "" -> 0),
      MapType(StringType, IntegerType, valueContainsNull = false))
    val msi1 = Literal.create(Map("abcdf" -> 5, "abc" -> 10, "" -> null),
      MapType(StringType, IntegerType, valueContainsNull = true))
    val msin = Literal.create(null, MapType(StringType, IntegerType, valueContainsNull = false))

    val isLengthOfKey: (Expression, Expression) => Expression = (k, v) => Length(k) === v

    checkEvaluation(mapFilter(msi0, isLengthOfKey), Map("abcdf" -> 5, "" -> 0))
    checkEvaluation(mapFilter(msi1, isLengthOfKey), Map("abcdf" -> 5))
    checkEvaluation(mapFilter(msin, isLengthOfKey), null)

    val mia0 = Literal.create(Map(1 -> Seq(0, 1, 2), 2 -> Seq(10), -3 -> Seq(-1, 0, -2, 3)),
      MapType(IntegerType, ArrayType(IntegerType), valueContainsNull = false))
    val mia1 = Literal.create(Map(1 -> Seq(0, 1, 2), 2 -> null, -3 -> Seq(-1, 0, -2, 3)),
      MapType(IntegerType, ArrayType(IntegerType), valueContainsNull = true))
    val mian = Literal.create(
      null, MapType(IntegerType, ArrayType(IntegerType), valueContainsNull = false))

    val customFunc: (Expression, Expression) => Expression = (k, v) => Size(v) + k > 3

    checkEvaluation(mapFilter(mia0, customFunc), Map(1 -> Seq(0, 1, 2)))
    checkEvaluation(mapFilter(mia1, customFunc), Map(1 -> Seq(0, 1, 2)))
    checkEvaluation(mapFilter(mian, customFunc), null)
  }
}
