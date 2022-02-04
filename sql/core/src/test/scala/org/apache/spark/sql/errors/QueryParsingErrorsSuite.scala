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

package org.apache.spark.sql.errors

import org.apache.spark.sql.QueryTest
import org.apache.spark.sql.catalyst.parser.ParseException
import org.apache.spark.sql.test.SharedSparkSession

class QueryParsingErrorsSuite extends QueryTest with SharedSparkSession {
  def validateParsingError(
      sqlText: String,
      errorClass: String,
      sqlState: String,
      message: String): Unit = {
    val e = intercept[ParseException] {
      sql(sqlText)
    }
    assert(e.getErrorClass === errorClass)
    assert(e.getSqlState === sqlState)
    assert(e.getMessage.contains(message))
  }

  test("UNSUPPORTED_FEATURE: LATERAL join with NATURAL join not supported") {
    validateParsingError(
      sqlText = "SELECT * FROM t1 NATURAL JOIN LATERAL (SELECT c1 + c2 AS c2)",
      errorClass = "UNSUPPORTED_FEATURE",
      sqlState = "0A000",
      message = "The feature is not supported: LATERAL join with NATURAL join.")
  }

  test("UNSUPPORTED_FEATURE: LATERAL join with USING join not supported") {
    validateParsingError(
      sqlText = "SELECT * FROM t1 JOIN LATERAL (SELECT c1 + c2 AS c2) USING (c2)",
      errorClass = "UNSUPPORTED_FEATURE",
      sqlState = "0A000",
      message = "The feature is not supported: LATERAL join with USING join.")
  }

  test("UNSUPPORTED_FEATURE: Unsupported LATERAL join type") {
    Seq(("RIGHT OUTER", "RightOuter"),
      ("FULL OUTER", "FullOuter"),
      ("LEFT SEMI", "LeftSemi"),
      ("LEFT ANTI", "LeftAnti")).foreach { pair =>
      validateParsingError(
        sqlText = s"SELECT * FROM t1 ${pair._1} JOIN LATERAL (SELECT c1 + c2 AS c3) ON c2 = c3",
        errorClass = "UNSUPPORTED_FEATURE",
        sqlState = "0A000",
        message = s"The feature is not supported: LATERAL join type '${pair._2}'.")
    }
  }

  test("SPARK-35789: INVALID_SQL_SYNTAX - LATERAL can only be used with subquery") {
    Seq("SELECT * FROM t1, LATERAL t2",
      "SELECT * FROM t1 JOIN LATERAL t2",
      "SELECT * FROM t1, LATERAL (t2 JOIN t3)",
      "SELECT * FROM t1, LATERAL (LATERAL t2)",
      "SELECT * FROM t1, LATERAL VALUES (0, 1)",
      "SELECT * FROM t1, LATERAL RANGE(0, 1)").foreach { sqlText =>
      validateParsingError(
        sqlText = sqlText,
        errorClass = "INVALID_SQL_SYNTAX",
        sqlState = "42000",
        message = "Invalid SQL syntax: LATERAL can only be used with subquery.")
    }
  }
  test("MORE_THAN_ONE_FROM_TO_UNIT_IN_INTERVAL_LITERAL: from-to unit in the interval literal") {
    val e = intercept[ParseException] {
      spark.sql("SELECT INTERVAL 1 to 3 year to month AS col")
    }
    assert(e.getErrorClass === "MORE_THAN_ONE_FROM_TO_UNIT_IN_INTERVAL_LITERAL")
    assert(e.getMessage.contains(
      "Can only have a single from-to unit in the interval literal syntax"))
  }

  test("INVALID_INTERVAL_LITERAL: invalid interval literal") {
    val e = intercept[ParseException] {
      spark.sql("SELECT INTERVAL DAY")
    }
    assert(e.getErrorClass === "INVALID_INTERVAL_LITERAL")
    assert(e.getMessage.contains(
      "at least one time unit should be given for interval literal"))
  }

  test("INVALID_FROM_TO_UNIT_VALUE: value of from-to unit must be a string") {
    val e = intercept[ParseException] {
      spark.sql("SELECT INTERVAL -2021 YEAR TO MONTH")
    }
    assert(e.getErrorClass === "INVALID_FROM_TO_UNIT_VALUE")
    assert(e.getMessage.contains(
      "The value of from-to unit must be a string"))
  }

  test("UNSUPPORTED_FEATURE: Unsupported from-to interval") {
    val e = intercept[ParseException] {
      spark.sql("SELECT extract(MONTH FROM INTERVAL '2021-11' YEAR TO DAY)")
    }
    assert(e.getErrorClass === "UNSUPPORTED_FEATURE")
    assert(e.getSqlState === "0A000")
    assert(e.getMessage.contains("The feature is not supported"))
  }
  // Moved from ExpressionParserSuite
  test("UNSUPPORTED_FEATURE: Unsupported month to second interval") {
    val e = intercept[ParseException] {
      spark.sql("SELECT interval '10' month to second")
    }
    assert(e.getErrorClass === "UNSUPPORTED_FEATURE")
    assert(e.getSqlState === "0A000")
    assert(e.getMessage.contains("The feature is not supported"))
  }
  // Moved from Interval.sql
  test("UNSUPPORTED_FEATURE: Unsupported month to second interval") {
    val e = intercept[ParseException] {
      spark.sql("select interval '1' year to second")
    }
    assert(e.getErrorClass === "UNSUPPORTED_FEATURE")
    assert(e.getSqlState === "0A000")
    assert(e.getMessage.contains("The feature is not supported"))
  }
  test("MIXED_INTERVAL_UNITS: Cannot mix year-month and day-time fields") {
    val e = intercept[ParseException] {
      spark.sql("SELECT INTERVAL 1 MONTH 2 HOUR")
    }
    assert(e.getErrorClass === "MIXED_INTERVAL_UNITS")

    assert(e.getMessage.contains(
      "Cannot mix year-month and day-time fields"))
  }

  test("INVALID_INTERVAL_FORM: invalid interval form") {
    val e = intercept[ParseException] {
      spark.sql("SELECT INTERVAL '1 DAY 2' HOUR")
    }
    assert(e.getErrorClass === "INVALID_INTERVAL_FORM")
    assert(e.getMessage.contains(
      "numbers in the interval value part for multiple unit value pairs interval form"))
  }
}
