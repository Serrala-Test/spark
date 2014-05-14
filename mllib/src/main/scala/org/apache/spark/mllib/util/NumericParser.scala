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

package org.apache.spark.mllib.util

import java.util.StringTokenizer

import scala.collection.mutable.{ArrayBuffer, ListBuffer}

import org.apache.spark.SparkException

private[mllib] object NumericTokenizer {
  val NUMBER = -1
  val END = -2
}

import NumericTokenizer._

/**
 * Simple tokenizer for a numeric structure consisting of three types:
 *
 *  - number: a double in Java's floating number format
 *  - array: an array of numbers stored as `[v0,v1,...,vn]`
 *  - tuple: a list of numbers, arrays, or tuples stored as `(...)`
 *
 * @param s input string
 */
private[mllib] class NumericTokenizer(s: String) {

  private var allowComma = false
  private var _value = Double.NaN
  private val stringTokenizer = new StringTokenizer(s, "()[],", true)

  /**
   * Returns the most recent parsed number.
   */
  def value: Double = _value

  /**
   * Returns the next token, which could be any of the following:
   *  - '[', ']', '(', or ')'.
   *  - [[org.apache.spark.mllib.util.NumericTokenizer#NUMBER]], call value() to get its value.
   *  - [[org.apache.spark.mllib.util.NumericTokenizer#END]].
   */
  def next(): Int = {
    if (stringTokenizer.hasMoreTokens()) {
      val token = stringTokenizer.nextToken()
      if (token == "(" || token == "[") {
        allowComma = false
        token.charAt(0)
      } else if (token == ")" || token == "]") {
        allowComma = true
        token.charAt(0)
      } else if (token == ",") {
        if (allowComma) {
          allowComma = false
          next()
        } else {
          throw new SparkException("Found a ',' at a wrong position.")
        }
      } else {
        // expecting a number
        _value = java.lang.Double.parseDouble(token)
        allowComma = true
        NUMBER
      }
    } else {
      END
    }
  }
}

/**
 * Simple parser for tokens from [[org.apache.spark.mllib.util.NumericTokenizer]].
 */
private[mllib] object NumericParser {

  /** Parses a string into a Double, an Array[Double], or a Seq[Any]. */
  def parse(s: String): Any = parse(new NumericTokenizer(s))

  private def parse(tokenizer: NumericTokenizer): Any = {
    val token = tokenizer.next()
    if (token == NUMBER) {
      tokenizer.value
    } else if (token == '(') {
      parseTuple(tokenizer)
    } else if (token == '[') {
      parseArray(tokenizer)
    } else if (token == END) {
      null
    } else {
      throw new SparkException(s"Cannot recognize token type: $token.")
    }
  }

  private def parseArray(tokenizer: NumericTokenizer): Array[Double] = {
    val values = ArrayBuffer.empty[Double]
    var token = tokenizer.next()
    while (token == NUMBER) {
      values.append(tokenizer.value)
      token = tokenizer.next()
    }
    if (token != ']') {
      throw new SparkException(s"An array must end with ] but got $token.")
    }
    values.toArray
  }

  private def parseTuple(tokenizer: NumericTokenizer): Seq[_] = {
    val items = ListBuffer.empty[Any]
    var token = tokenizer.next()
    while (token != ')' && token != END) {
      if (token == NUMBER) {
        items.append(tokenizer.value)
      } else if (token == '(') {
        items.append(parseTuple(tokenizer))
      } else if (token == '[') {
        items.append(parseArray(tokenizer))
      } else {
        throw new SparkException(s"Cannot recognize token type: $token.")
      }
      token = tokenizer.next()
    }
    if (token != ')') {
      throw new SparkException(s"A tuple must end with ) but got $token.")
    }
    items.toSeq
  }
}
