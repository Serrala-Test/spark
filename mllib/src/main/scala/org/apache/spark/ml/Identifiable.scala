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

package org.apache.spark.ml

import java.util.UUID

import org.apache.spark.annotation.AlphaComponent

/**
 * :: AlphaComponent ::
 * Object with a unique id.
 */
@AlphaComponent
trait Identifiable extends Serializable {

  /**
   * A unique id for the object. The default implementation concatenates the class name, "-", and 8
   * random hex chars.
   */
  val uid: String = this.getClass.getSimpleName + "-" + UUID.randomUUID().toString.take(8)
}
