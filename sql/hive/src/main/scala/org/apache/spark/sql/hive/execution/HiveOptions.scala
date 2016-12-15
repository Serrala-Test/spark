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

package org.apache.spark.sql.hive.execution

import org.apache.spark.sql.catalyst.util.CaseInsensitiveMap

/**
 * Options for the Hive data source.
 */
class HiveOptions(@transient private val parameters: CaseInsensitiveMap) extends Serializable {
  import HiveOptions._

  def this(parameters: Map[String, String]) = this(new CaseInsensitiveMap(parameters))

  val format = parameters.get(FORMAT).map(_.toLowerCase)
  val inputFormat = parameters.get(INPUT_FORMAT)
  val outputFormat = parameters.get(OUTPUT_FORMAT)

  if (inputFormat.isDefined != outputFormat.isDefined) {
    throw new IllegalArgumentException("Cannot specify only inputFormat or outputFormat, you " +
      "have to specify both of them.")
  }

  if (format.isDefined && inputFormat.isDefined) {
    throw new IllegalArgumentException("Cannot specify format and inputFormat/outputFormat " +
      "together for Hive data source.")
  }

  val serde = parameters.get(SERDE)

  for (f <- format if serde.isDefined) {
    if (!Set("sequencefile", "textfile", "rcfile").contains(f)) {
      throw new IllegalArgumentException(s"format '$f' already specifies a serde.")
    }
  }

  val containsDelimiters = delimiterOptions.keys.exists(parameters.contains)

  for (f <- format if f != "textfile" && containsDelimiters) {
    throw new IllegalArgumentException("Cannot specify delimiters as they are only compatible " +
      s"with format 'textfile', not $f.")
  }

  for (lineDelim <- parameters.get("lineDelim") if lineDelim != "\n") {
    throw new IllegalArgumentException("Hive data source only support newline '\\n' as " +
      s"line delimiter, but given: $lineDelim.")
  }

  def serdeProperties: Map[String, String] = parameters.filterKeys {
    k => !lowerCasedOptionNames.contains(k.toLowerCase)
  }.map { case (k, v) => delimiterOptions.getOrElse(k, k) -> v }
}

object HiveOptions {
  private val lowerCasedOptionNames = collection.mutable.Set[String]()

  private def newOption(name: String): String = {
    lowerCasedOptionNames += name.toLowerCase
    name
  }

  val FORMAT = newOption("format")
  val INPUT_FORMAT = newOption("inputFormat")
  val OUTPUT_FORMAT = newOption("outputFormat")
  val SERDE = newOption("serde")

  // A map from the public delimiter option keys to the underlying Hive serde property keys.
  val delimiterOptions = Map(
    "fieldDelim" -> "field.delim",
    "escapeDelim" -> "escape.delim",
    // The following typo is inherited from Hive...
    "collectionDelim" -> "colelction.delim",
    "mapkeyDelim" -> "mapkey.delim",
    "lineDelim" -> "line.delim").map { case (k, v) => k.toLowerCase -> v }
}
