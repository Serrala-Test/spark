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
package org.apache.spark.sql.parser

import scala.collection.JavaConverters._

import org.antlr.runtime.{RecognitionException, BaseRecognizer}

/**
 * Exception occured during the parsing process.
 *
 * This is based on Hive's org.apache.hadoop.hive.ql.parse.ParseException
 */
class ParseException(val errors: Seq[ParseError]) extends Exception {
  def this(errors: java.util.List[ParseError]) = this(errors.asScala)
  override def getMessage: String = {
    val builder = new StringBuilder
    errors.foreach(_.buildMessage(builder))
    builder.toString()
  }
}

/**
 * Error collected during the parsing process.
 *
 * This is based on Hive's org.apache.hadoop.hive.ql.parse.ParseError
 */
private[parser] case class ParseError(
    br: BaseRecognizer,
    re: RecognitionException,
    tokenNames: Array[String]) {
  def buildMessage(s: StringBuilder): Unit = {
    s.append(br.getErrorHeader(re)).append(" ").append(br.getErrorMessage(re, tokenNames))
  }
}
