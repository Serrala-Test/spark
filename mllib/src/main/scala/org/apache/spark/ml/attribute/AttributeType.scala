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

package org.apache.spark.ml.attribute

import org.apache.spark.annotation.{DeveloperApi, Since}

/**
 * :: DeveloperApi ::
 * An enum-like type for attribute types: [[AttributeType$#Numeric]], [[AttributeType$#Nominal]],
 * and [[AttributeType$#Binary]].
 */
@DeveloperApi
@Since("1.4.0")
sealed abstract class AttributeType(@Since("1.4.0") val name: String)

@DeveloperApi
@Since("1.4.0")
object AttributeType {

  /** Numeric type. */
  @Since("1.4.0")
  val Numeric: AttributeType = {
    case object Numeric extends AttributeType("numeric")
    Numeric
  }

  /** Nominal type. */
  @Since("1.4.0")
  val Nominal: AttributeType = {
    case object Nominal extends AttributeType("nominal")
    Nominal
  }

  /** Binary type. */
  @Since("1.4.0")
  val Binary: AttributeType = {
    case object Binary extends AttributeType("binary")
    Binary
  }

  /** Unresolved type. */
  @Since("1.5.0")
  val Unresolved: AttributeType = {
    case object Unresolved extends AttributeType("unresolved")
    Unresolved
  }

  /**
   * Gets the [[AttributeType]] object from its name.
   * @param name attribute type name: "numeric", "nominal", or "binary"
   */
  @Since("1.4.0")
  def fromName(name: String): AttributeType = {
    if (name == Numeric.name) {
      Numeric
    } else if (name == Nominal.name) {
      Nominal
    } else if (name == Binary.name) {
      Binary
    } else if (name == Unresolved.name) {
      Unresolved
    } else {
      throw new IllegalArgumentException(s"Cannot recognize type $name.")
    }
  }
}
