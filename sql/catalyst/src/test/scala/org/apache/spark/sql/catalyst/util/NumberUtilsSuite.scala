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

package org.apache.spark.sql.catalyst.util

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.util.NumberUtils.{format, parse}
import org.apache.spark.sql.types.Decimal
import org.apache.spark.unsafe.types.UTF8String

class NumberUtilsSuite extends SparkFunSuite {

  private def failParseWithInvalidInput(
      input: UTF8String, numberFormat: String, errorMsg: String): Unit = {
    val e = intercept[IllegalArgumentException](parse(input, numberFormat))
    assert(e.getMessage.contains(errorMsg))
  }

  private def failParseWithAnalysisException(
      input: UTF8String, numberFormat: String, errorMsg: String): Unit = {
    val e = intercept[AnalysisException](parse(input, numberFormat))
    assert(e.getMessage.contains(errorMsg))
  }

  private def failFormatWithAnalysisException(
      input: Decimal, numberFormat: String, errorMsg: String): Unit = {
    val e = intercept[AnalysisException](format(input, numberFormat))
    assert(e.getMessage.contains(errorMsg))
  }

  test("parse") {
    failParseWithInvalidInput(UTF8String.fromString("454"), "",
      "Format '' used for parsing string to number or formatting number to string is invalid")

    // Test '9' and '0'
    failParseWithInvalidInput(UTF8String.fromString("454"), "9",
      "Format '9' used for parsing string to number or formatting number to string is invalid")
    failParseWithInvalidInput(UTF8String.fromString("454"), "99",
      "Format '99' used for parsing string to number or formatting number to string is invalid")

    assert(parse(UTF8String.fromString("454"), "999") === Decimal(454))
    assert(parse(UTF8String.fromString("054"), "999") === Decimal(54))
    assert(parse(UTF8String.fromString("404"), "999") === Decimal(404))
    assert(parse(UTF8String.fromString("450"), "999") === Decimal(450))
    assert(parse(UTF8String.fromString("454"), "9999") === Decimal(454))
    assert(parse(UTF8String.fromString("054"), "9999") === Decimal(54))
    assert(parse(UTF8String.fromString("404"), "9999") === Decimal(404))
    assert(parse(UTF8String.fromString("450"), "9999") === Decimal(450))

    failParseWithInvalidInput(UTF8String.fromString("454"), "0",
      "Format '0' used for parsing string to number or formatting number to string is invalid")
    failParseWithInvalidInput(UTF8String.fromString("454"), "00",
      "Format '00' used for parsing string to number or formatting number to string is invalid")

    assert(parse(UTF8String.fromString("454"), "000") === Decimal(454))
    assert(parse(UTF8String.fromString("054"), "000") === Decimal(54))
    assert(parse(UTF8String.fromString("404"), "000") === Decimal(404))
    assert(parse(UTF8String.fromString("450"), "000") === Decimal(450))
    assert(parse(UTF8String.fromString("454"), "0000") === Decimal(454))
    assert(parse(UTF8String.fromString("054"), "0000") === Decimal(54))
    assert(parse(UTF8String.fromString("404"), "0000") === Decimal(404))
    assert(parse(UTF8String.fromString("450"), "0000") === Decimal(450))

    // Test '.' and 'D'
    failParseWithInvalidInput(UTF8String.fromString("454.2"), "999",
      "Format '999' used for parsing string to number or formatting number to string is invalid")
    failParseWithInvalidInput(UTF8String.fromString("454.23"), "999.9",
      "Format '999.9' used for parsing string to number or formatting number to string is invalid")
    assert(parse(UTF8String.fromString("454.2"), "999.9") === Decimal(454.2))
    assert(parse(UTF8String.fromString("454.2"), "000.0") === Decimal(454.2))
    assert(parse(UTF8String.fromString("454.2"), "999D9") === Decimal(454.2))
    assert(parse(UTF8String.fromString("454.2"), "000D0") === Decimal(454.2))
    assert(parse(UTF8String.fromString("454.23"), "999.99") === Decimal(454.23))
    assert(parse(UTF8String.fromString("454.23"), "000.00") === Decimal(454.23))
    assert(parse(UTF8String.fromString("454.23"), "999D99") === Decimal(454.23))
    assert(parse(UTF8String.fromString("454.23"), "000D00") === Decimal(454.23))
    assert(parse(UTF8String.fromString("454.0"), "999.9") === Decimal(454))
    assert(parse(UTF8String.fromString("454.0"), "000.0") === Decimal(454))
    assert(parse(UTF8String.fromString("454.0"), "999D9") === Decimal(454))
    assert(parse(UTF8String.fromString("454.0"), "000D0") === Decimal(454))
    assert(parse(UTF8String.fromString("454.00"), "999.99") === Decimal(454))
    assert(parse(UTF8String.fromString("454.00"), "000.00") === Decimal(454))
    assert(parse(UTF8String.fromString("454.00"), "999D99") === Decimal(454))
    assert(parse(UTF8String.fromString("454.00"), "000D00") === Decimal(454))
    assert(parse(UTF8String.fromString(".4542"), ".9999") === Decimal(0.4542))
    assert(parse(UTF8String.fromString(".4542"), ".0000") === Decimal(0.4542))
    assert(parse(UTF8String.fromString(".4542"), "D9999") === Decimal(0.4542))
    assert(parse(UTF8String.fromString(".4542"), "D0000") === Decimal(0.4542))
    assert(parse(UTF8String.fromString("4542."), "9999.") === Decimal(4542))
    assert(parse(UTF8String.fromString("4542."), "0000.") === Decimal(4542))
    assert(parse(UTF8String.fromString("4542."), "9999D") === Decimal(4542))
    assert(parse(UTF8String.fromString("4542."), "0000D") === Decimal(4542))

    failParseWithAnalysisException(UTF8String.fromString("454.3.2"), "999.9.9",
      "Multiple 'D' or '.' in 999.9.9")
    failParseWithAnalysisException(UTF8String.fromString("454.3.2"), "999D9D9",
      "Multiple 'D' or '.' in 999D9D9")
    failParseWithAnalysisException(UTF8String.fromString("454.3.2"), "999.9D9",
      "Multiple 'D' or '.' in 999.9D9")
    failParseWithAnalysisException(UTF8String.fromString("454.3.2"), "999D9.9",
      "Multiple 'D' or '.' in 999D9.9")

    // Test ',' and 'G'
    assert(parse(UTF8String.fromString("12,454"), "99,999") === Decimal(12454))
    assert(parse(UTF8String.fromString("12,454"), "00,000") === Decimal(12454))
    assert(parse(UTF8String.fromString("12,454"), "99G999") === Decimal(12454))
    assert(parse(UTF8String.fromString("12,454"), "00G000") === Decimal(12454))
    assert(parse(UTF8String.fromString("12,454,367"), "99,999,999") === Decimal(12454367))
    assert(parse(UTF8String.fromString("12,454,367"), "00,000,000") === Decimal(12454367))
    assert(parse(UTF8String.fromString("12,454,367"), "99G999G999") === Decimal(12454367))
    assert(parse(UTF8String.fromString("12,454,367"), "00G000G000") === Decimal(12454367))
    assert(parse(UTF8String.fromString("12,454,"), "99,999,") === Decimal(12454))
    assert(parse(UTF8String.fromString("12,454,"), "00,000,") === Decimal(12454))
    assert(parse(UTF8String.fromString("12,454,"), "99G999G") === Decimal(12454))
    assert(parse(UTF8String.fromString("12,454,"), "00G000G") === Decimal(12454))
    assert(parse(UTF8String.fromString(",454,367"), ",999,999") === Decimal(454367))
    assert(parse(UTF8String.fromString(",454,367"), ",000,000") === Decimal(454367))
    assert(parse(UTF8String.fromString(",454,367"), "G999G999") === Decimal(454367))
    assert(parse(UTF8String.fromString(",454,367"), "G000G000") === Decimal(454367))

    // Test '$'
    assert(parse(UTF8String.fromString("$78.12"), "$99.99") === Decimal(78.12))
    assert(parse(UTF8String.fromString("$78.12"), "$00.00") === Decimal(78.12))
    assert(parse(UTF8String.fromString("78.12$"), "99.99$") === Decimal(78.12))
    assert(parse(UTF8String.fromString("78.12$"), "00.00$") === Decimal(78.12))

    failParseWithAnalysisException(UTF8String.fromString("78$.12"), "99$.99",
      "'$' must be the first or last char")
    failParseWithAnalysisException(UTF8String.fromString("$78.12$"), "$99.99$",
      "Multiple '$' in $99.99$")

    // Test '-' and 'S'
    assert(parse(UTF8String.fromString("454-"), "999-") === Decimal(-454))
    assert(parse(UTF8String.fromString("454-"), "999S") === Decimal(-454))
    assert(parse(UTF8String.fromString("-454"), "-999") === Decimal(-454))
    assert(parse(UTF8String.fromString("-454"), "S999") === Decimal(-454))
    assert(parse(UTF8String.fromString("454-"), "000-") === Decimal(-454))
    assert(parse(UTF8String.fromString("454-"), "000S") === Decimal(-454))
    assert(parse(UTF8String.fromString("-454"), "-000") === Decimal(-454))
    assert(parse(UTF8String.fromString("-454"), "S000") === Decimal(-454))
    assert(parse(UTF8String.fromString("12,454.8-"), "99G999D9S") === Decimal(-12454.8))
    assert(parse(UTF8String.fromString("00,454.8-"), "99G999.9S") === Decimal(-454.8))

    failParseWithAnalysisException(UTF8String.fromString("4-54"), "9S99",
      "'S' or '-' must be the first or last char")
    failParseWithAnalysisException(UTF8String.fromString("4-54"), "9-99",
      "'S' or '-' must be the first or last char")
    failParseWithAnalysisException(UTF8String.fromString("454.3--"), "999D9SS",
      "Multiple 'S' or '-' in 999D9SS")
  }

  test("format") {
    assert(format(Decimal(454), "") === "")

    // Test '9' and '0'
    assert(format(Decimal(454), "9") === "#")
    assert(format(Decimal(454), "99") === "##")
    assert(format(Decimal(454), "999") === "454")
    assert(format(Decimal(54), "999") === "54")
    assert(format(Decimal(404), "999") === "404")
    assert(format(Decimal(450), "999") === "450")
    assert(format(Decimal(454), "9999") === "454")
    assert(format(Decimal(54), "9999") === "54")
    assert(format(Decimal(404), "9999") === "404")
    assert(format(Decimal(450), "9999") === "450")

    assert(format(Decimal(454), "0") === "#")
    assert(format(Decimal(454), "00") === "##")
    assert(format(Decimal(454), "000") === "454")
    assert(format(Decimal(54), "000") === "054")
    assert(format(Decimal(404), "000") === "404")
    assert(format(Decimal(450), "000") === "450")
    assert(format(Decimal(454), "0000") === "0454")
    assert(format(Decimal(54), "0000") === "0054")
    assert(format(Decimal(404), "0000") === "0404")
    assert(format(Decimal(450), "0000") === "0450")

    // Test '.' and 'D'
    assert(format(Decimal(454.2), "999.9") === "454.2")
    assert(format(Decimal(454.2), "000.0") === "454.2")
    assert(format(Decimal(454.2), "999D9") === "454.2")
    assert(format(Decimal(454.2), "000D0") === "454.2")
    assert(format(Decimal(454), "999.9") === "454.0")
    assert(format(Decimal(454), "000.0") === "454.0")
    assert(format(Decimal(454), "999D9") === "454.0")
    assert(format(Decimal(454), "000D0") === "454.0")
    assert(format(Decimal(454), "999.99") === "454.00")
    assert(format(Decimal(454), "000.00") === "454.00")
    assert(format(Decimal(454), "999D99") === "454.00")
    assert(format(Decimal(454), "000D00") === "454.00")
    assert(format(Decimal(0.4542), ".9999") === ".####")
    assert(format(Decimal(0.4542), ".0000") === ".####")
    assert(format(Decimal(0.4542), "D9999") === ".####")
    assert(format(Decimal(0.4542), "D0000") === ".####")
    assert(format(Decimal(4542), "9999.") === "4542.")
    assert(format(Decimal(4542), "0000.") === "4542.")
    assert(format(Decimal(4542), "9999D") === "4542.")
    assert(format(Decimal(4542), "0000D") === "4542.")

    failFormatWithAnalysisException(Decimal(454.32), "999.9.9",
      "Multiple 'D' or '.' in 999.9.9")
    failFormatWithAnalysisException(Decimal(454.32), "999D9D9",
      "Multiple 'D' or '.' in 999D9D9")
    failFormatWithAnalysisException(Decimal(454.32), "999.9D9",
      "Multiple 'D' or '.' in 999.9D9")
    failFormatWithAnalysisException(Decimal(454.32), "999D9.9",
      "Multiple 'D' or '.' in 999D9.9")

    // Test ',' and 'G'
    assert(format(Decimal(12454), "99,999") === "12,454")
    assert(format(Decimal(12454), "00,000") === "12,454")
    assert(format(Decimal(12454), "99G999") === "12,454")
    assert(format(Decimal(12454), "00G000") === "12,454")
    assert(format(Decimal(12454367), "99,999,999") === "12,454,367")
    assert(format(Decimal(12454367), "00,000,000") === "12,454,367")
    assert(format(Decimal(12454367), "99G999G999") === "12,454,367")
    assert(format(Decimal(12454367), "00G000G000") === "12,454,367")
    assert(format(Decimal(12454), "99,999,") === "12,454,")
    assert(format(Decimal(12454), "00,000,") === "12,454,")
    assert(format(Decimal(12454), "99G999G") === "12,454,")
    assert(format(Decimal(12454), "00G000G") === "12,454,")
    assert(format(Decimal(454367), ",999,999") === ",454,367")
    assert(format(Decimal(454367), ",000,000") === ",454,367")
    assert(format(Decimal(454367), "G999G999") === ",454,367")
    assert(format(Decimal(454367), "G000G000") === ",454,367")

    // Test '$'
    assert(format(Decimal(78.12), "$99.99") === "$78.12")
    assert(format(Decimal(78.12), "$00.00") === "$78.12")
    assert(format(Decimal(78.12), "99.99$") === "78.12$")
    assert(format(Decimal(78.12), "00.00$") === "78.12$")

    failFormatWithAnalysisException(Decimal(78.12), "99$.99",
      "'$' must be the first or last char")
    failFormatWithAnalysisException(Decimal(78.12), "$99.99$",
      "Multiple '$' in $99.99$")

    // Test '-' and 'S'
    assert(format(Decimal(-454), "999-") === "454-")
    assert(format(Decimal(-454), "999S") === "454-")
    assert(format(Decimal(-454), "-999") === "-454")
    assert(format(Decimal(-454), "S999") === "-454")
    assert(format(Decimal(-454), "000-") === "454-")
    assert(format(Decimal(-454), "000S") === "454-")
    assert(format(Decimal(-454), "-000") === "-454")
    assert(format(Decimal(-454), "S000") === "-454")
    assert(format(Decimal(-12454.8), "99G999D9S") === "12,454.8-")
    assert(format(Decimal(-454.8), "99G999.9S") === "454.8-")

    failFormatWithAnalysisException(Decimal(-454), "9S99",
      "'S' or '-' must be the first or last char")
    failFormatWithAnalysisException(Decimal(-454), "9-99",
      "'S' or '-' must be the first or last char")
    failFormatWithAnalysisException(Decimal(-454.3), "999D9SS",
      "Multiple 'S' or '-' in 999D9SS")
  }

}
