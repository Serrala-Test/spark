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

import scala.collection.immutable.Seq

import org.apache.spark.SparkConf
import org.apache.spark.sql.catalyst.ExtendedAnalysisException
import org.apache.spark.sql.catalyst.expressions.{ExpressionEvalHelper, Literal, SubstringIndex}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.StringType

class CollationStringExpressionsSuite extends QueryTest
  with SharedSparkSession with ExpressionEvalHelper {

  case class CollationTestCase[R](s1: String, s2: String, collation: String, expectedResult: R)
  case class CollationTestFail[R](s1: String, s2: String, collation: String)

  test("Support ConcatWs string expression with Collation") {
    // Supported collations
    val checks = Seq(
      CollationTestCase("Spark", "SQL", "UTF8_BINARY", "Spark SQL")
    )
    checks.foreach(ct => {
      checkAnswer(sql(s"SELECT concat_ws(collate(' ', '${ct.collation}'), " +
        s"collate('${ct.s1}', '${ct.collation}'), collate('${ct.s2}', '${ct.collation}'))"),
        Row(ct.expectedResult))
    })
    // Unsupported collations
    val fails = Seq(
      CollationTestCase("ABC", "%b%", "UTF8_BINARY_LCASE", false),
      CollationTestCase("ABC", "%B%", "UNICODE", true),
      CollationTestCase("ABC", "%b%", "UNICODE_CI", false)
    )
    fails.foreach(ct => {
      val expr = s"concat_ws(collate(' ', '${ct.collation}'), " +
        s"collate('${ct.s1}', '${ct.collation}'), collate('${ct.s2}', '${ct.collation}'))"
      checkError(
        exception = intercept[ExtendedAnalysisException] {
          sql(s"SELECT $expr")
        },
        errorClass = "DATATYPE_MISMATCH.UNEXPECTED_INPUT_TYPE",
        sqlState = "42K09",
        parameters = Map(
          "sqlExpr" -> s"\"concat_ws(collate( ), collate(${ct.s1}), collate(${ct.s2}))\"",
          "paramIndex" -> "first",
          "inputSql" -> s"\"collate( )\"",
          "inputType" -> s"\"STRING COLLATE ${ct.collation}\"",
          "requiredType" -> "\"STRING\""
        ),
        context = ExpectedContext(
          fragment = s"$expr",
          start = 7,
          stop = 73 + 3 * ct.collation.length
        )
      )
    })
  }

  test("SUBSTRING_INDEX check result on explicitly collated strings") {
    def testSubstringIndex(str: String,
                  delim: String,
                  cnt: Integer,
                  stringType: Integer,
                  expected: String,
                  ): Unit = {
      val string = Literal.create(str, StringType(stringType))
      val delimiter = Literal.create(delim, StringType(stringType))
      val count = Literal(cnt)

      checkEvaluation(SubstringIndex(string, delimiter, count), expected)
    }

    // UTF8_BINARY_LCASE
    testSubstringIndex("www.apache.org", ".", 3, 0, "www.apache.org")
    testSubstringIndex("www.apache.org", ".", 3, 0, "www.apache.org")
    testSubstringIndex("www.apache.org", ".", 2, 0, "www.apache")
    testSubstringIndex("www.apache.org", ".", 1, 0, "www")
    testSubstringIndex("www.apache.org", ".", 0, 0, "")
    testSubstringIndex("www.apache.org", ".", -3, 0, "www.apache.org")
    testSubstringIndex("www.apache.org", ".", -2, 0, "apache.org")
    testSubstringIndex("www.apache.org", ".", -1, 0, "org")
    testSubstringIndex("", ".", -2, 0, "")
    // scalastyle:off
    testSubstringIndex("大千世界大千世界", "千", 2, 0, "大千世界大")
    // scalastyle:on
    testSubstringIndex("www||apache||org", "||", 2, 0, "www||apache")
    // UTF8_BINARY_LCASE
    testSubstringIndex("www.apache.org", ".", 3, 1, "www.apache.org")
    testSubstringIndex("www.apache.org", ".", 2, 1, "www.apache")
    testSubstringIndex("www.apache.org", ".", 1, 1, "www")
    testSubstringIndex("www.apache.org", ".", 0, 1, "")
    testSubstringIndex("www.apache.org", ".", -3, 1, "www.apache.org")
    testSubstringIndex("www.apache.org", ".", -2, 1, "apache.org")
    testSubstringIndex("www.apache.org", ".", -1, 1, "org")
    testSubstringIndex("", ".", -2, 1, "")
    // scalastyle:off
    testSubstringIndex("大千世界大千世界", "千", 2, 1, "大千世界大")
    // scalastyle:on
    testSubstringIndex("www||apache||org", "||", 2, 1, "www||apache")
    // UNICODE
    testSubstringIndex("www.apache.org", ".", 3, 2, "www.apache.org")
    testSubstringIndex("www.apache.org", ".", 2, 2, "www.apache")
    testSubstringIndex("www.apache.org", ".", 1, 2, "www")
    testSubstringIndex("www.apache.org", ".", 0, 2, "")
    testSubstringIndex("www.apache.org", ".", -3, 2, "www.apache.org")
    testSubstringIndex("www.apache.org", ".", -2, 2, "apache.org")
    testSubstringIndex("www.apache.org", ".", -1, 2, "org")
    testSubstringIndex("", ".", -2, 2, "")
    // scalastyle:off
    testSubstringIndex("大千世界大千世界", "千", 2, 2, "大千世界大")
    // scalastyle:on
    testSubstringIndex("www||apache||org", "||", 2, 2, "www||apache")
    // UNICODE_CI
    testSubstringIndex("www.apache.org", ".", 3, 3, "www.apache.org")
    testSubstringIndex("www.apache.org", ".", 2, 3, "www.apache")
    testSubstringIndex("www.apache.org", ".", 1, 3, "www")
    testSubstringIndex("www.apache.org", ".", 0, 3, "")
    testSubstringIndex("www.apache.org", ".", -3, 3, "www.apache.org")
    testSubstringIndex("www.apache.org", ".", -2, 3, "apache.org")
    testSubstringIndex("www.apache.org", ".", -1, 3, "org")
    testSubstringIndex("", ".", -2, 3, "")
    // scalastyle:off
    testSubstringIndex("大千世界大千世界", "千", 2, 3, "大千世界大")
    // scalastyle:on
    testSubstringIndex("www||apache||org", "||", 2, 3, "www||apache")
  }

  // TODO: Add more tests for other string expressions

}

class CollationStringExpressionsANSISuite extends CollationRegexpExpressionsSuite {
  override protected def sparkConf: SparkConf =
    super.sparkConf.set(SQLConf.ANSI_ENABLED, true)
}
