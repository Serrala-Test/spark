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

package org.apache.spark.sql.catalyst.util.postgreSQL

import org.apache.spark.unsafe.types.UTF8String

object StringUtils {
  // "true", "yes", "1", "false", "no", "0", and unique prefixes of these strings are accepted.
  private[this] val trueStrings =
    Set("true", "tru", "tr", "t", "yes", "ye", "y", "on", "1").map(UTF8String.fromString)

  private[this] val falseStrings =
    Set("false", "fals", "fal", "fa", "f", "no", "n", "off", "of", "0").map(UTF8String.fromString)

  def isTrueString(s: UTF8String): Boolean = trueStrings.contains(s.trim().toLowerCase())

  def isFalseString(s: UTF8String): Boolean = falseStrings.contains(s.trim().toLowerCase())
}
