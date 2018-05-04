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

package org.apache.spark.sql.catalyst.data

import org.apache.spark.sql.types.DataType

/**
 * Represents a map in Spark SQL that holds data values in Spark's internal representation of the
 * map's key and value types. For more information on Spark's internal representation, see
 * [[org.apache.spark.sql.catalyst.data]].
 *
 * This does not implement `equals` and `hashCode` because maps cannot be used as join keys,
 * grouping keys, or in equality tests. See SPARK-9415 and PR#13847 for more information.
 */
abstract class MapData extends Serializable {

  def numElements: Int

  def keyArray: ArrayData

  def valueArray: ArrayData

  def copy(): MapData

  def foreach(keyType: DataType, valueType: DataType, f: (Any, Any) => Unit): Unit = {
    val length = numElements
    val keys = keyArray
    val keyAccessor = InternalRow.getAccessor(keyType)
    val values = valueArray
    val valueAccessor = InternalRow.getAccessor(valueType)
    var i = 0
    while (i < length) {
      f(keyAccessor(keys, i), valueAccessor(values, i))
      i += 1
    }
  }
}
