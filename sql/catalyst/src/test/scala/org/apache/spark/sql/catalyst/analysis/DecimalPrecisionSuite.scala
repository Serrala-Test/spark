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

package org.apache.spark.sql.catalyst.analysis

import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.logical.{Project, LocalRelation}
import org.apache.spark.sql.catalyst.types._
import org.apache.spark.sql.catalyst.test.SimpleConf
import org.scalatest.{BeforeAndAfter, FunSuite}

class DecimalPrecisionSuite extends FunSuite with BeforeAndAfter {
  val conf = new SimpleConf
  val catalog = new SimpleCatalog(conf)
  val analyzer = new Analyzer(catalog, EmptyFunctionRegistry, conf)

  val relation = LocalRelation(
    AttributeReference("i", IntegerType)(),
    AttributeReference("d1", DecimalType(2, 1))(),
    AttributeReference("d2", DecimalType(5, 2))(),
    AttributeReference("u", DecimalType.Unlimited)(),
    AttributeReference("f", FloatType)()
  )

  val i: Expression = UnresolvedAttribute("i")
  val d1: Expression = UnresolvedAttribute("d1")
  val d2: Expression = UnresolvedAttribute("d2")
  val u: Expression = UnresolvedAttribute("u")
  val f: Expression = UnresolvedAttribute("f")

  before {
    catalog.registerTable(None, "table", relation)
  }

  private def checkType(expression: Expression, expectedType: DataType): Unit = {
    val plan = Project(Seq(Alias(expression, "c")()), relation)
    assert(analyzer(plan).schema.fields(0).dataType === expectedType)
  }

  test("basic operations") {
    checkType(Add(d1, d2), DecimalType(6, 2))
    checkType(Subtract(d1, d2), DecimalType(6, 2))
    checkType(Multiply(d1, d2), DecimalType(8, 3))
    checkType(Divide(d1, d2), DecimalType(10, 7))
    checkType(Divide(d2, d1), DecimalType(10, 6))
    checkType(Remainder(d1, d2), DecimalType(3, 2))
    checkType(Remainder(d2, d1), DecimalType(3, 2))
    checkType(Sum(d1), DecimalType(12, 1))
    checkType(Average(d1), DecimalType(6, 5))

    checkType(Add(Add(d1, d2), d1), DecimalType(7, 2))
    checkType(Add(Add(Add(d1, d2), d1), d2), DecimalType(8, 2))
    checkType(Add(Add(d1, d2), Add(d1, d2)), DecimalType(7, 2))
  }

  test("bringing in primitive types") {
    checkType(Add(d1, i), DecimalType(12, 1))
    checkType(Add(d1, f), DoubleType)
    checkType(Add(i, d1), DecimalType(12, 1))
    checkType(Add(f, d1), DoubleType)
    checkType(Add(d1, Cast(i, LongType)), DecimalType(22, 1))
    checkType(Add(d1, Cast(i, ShortType)), DecimalType(7, 1))
    checkType(Add(d1, Cast(i, ByteType)), DecimalType(5, 1))
    checkType(Add(d1, Cast(i, DoubleType)), DoubleType)
  }

  test("unlimited decimals make everything else cast up") {
    for (expr <- Seq(d1, d2, i, f, u)) {
      checkType(Add(expr, u), DecimalType.Unlimited)
      checkType(Subtract(expr, u), DecimalType.Unlimited)
      checkType(Multiply(expr, u), DecimalType.Unlimited)
      checkType(Divide(expr, u), DecimalType.Unlimited)
      checkType(Remainder(expr, u), DecimalType.Unlimited)
    }
  }
}
