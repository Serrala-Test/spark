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

package org.apache.spark.sql.sources

abstract class Filter

case class EqualTo(attribute: String, value: Any) extends Filter
case class GreaterThan(attribute: String, value: Any) extends Filter
case class GreaterThanOrEqual(attribute: String, value: Any) extends Filter
case class LessThan(attribute: String, value: Any) extends Filter
case class LessThanOrEqual(attribute: String, value: Any) extends Filter
case class In(attribute: String, values: Array[Any]) extends Filter
case class IsNull(attribute: String) extends Filter
case class IsNotNull(attribute: String) extends Filter
case class And(left: Filter, right: Filter) extends Filter
case class Or(left: Filter, right: Filter) extends Filter
case class Not(child: Filter) extends Filter
