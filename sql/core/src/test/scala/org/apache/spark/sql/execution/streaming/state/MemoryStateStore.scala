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

package org.apache.spark.sql.execution.streaming.state

import java.util.concurrent.ConcurrentHashMap

import org.apache.spark.sql.catalyst.expressions.UnsafeRow
import org.apache.spark.sql.types.StructType

class MemoryStateStore extends StateStore() {
  import scala.jdk.CollectionConverters._
  private val map = new ConcurrentHashMap[UnsafeRow, UnsafeRow]

  override def iterator(colFamilyName: String): Iterator[UnsafeRowPair] = {
    map.entrySet.iterator.asScala.map { case e => new UnsafeRowPair(e.getKey, e.getValue) }
  }

  override def createColFamilyIfAbsent(
      colFamilyName: String,
      keySchema: StructType,
      numColsPrefixKey: Int,
      valueSchema: StructType): Unit = {
    throw StateStoreErrors.multipleColumnFamiliesNotSupported("MemoryStateStoreProvider")
  }

  override def removeColFamilyIfExists(colFamilyName: String): Unit = {
    throw StateStoreErrors.removingColumnFamiliesNotSupported("MemoryStateStoreProvider")
  }

  override def getWithCompositeKey(key: UnsafeRow, userKey: UnsafeRow,
                                   colFamilyName: String): UnsafeRow = {
    throw new UnsupportedOperationException(
      "Get with composite key is not supported for Memory State")
  }

  def putWithCompositeKey(key: UnsafeRow, userKey: UnsafeRow, value: UnsafeRow,
                          colFamilyName: String = StateStore.DEFAULT_COL_FAMILY_NAME): Unit = {
    throw new UnsupportedOperationException(
      "Put with composite key is not supported for Memory State")
  }

  def removeWithCompositeKey(key: UnsafeRow, userKey: UnsafeRow,
                             colFamilyName: String = StateStore.DEFAULT_COL_FAMILY_NAME): Unit = {
    throw new UnsupportedOperationException(
      "Remove with composite key is not supported for Memory State")
  }

  override def get(key: UnsafeRow, colFamilyName: String): UnsafeRow = map.get(key)

  override def put(key: UnsafeRow, newValue: UnsafeRow, colFamilyName: String): Unit =
    map.put(key.copy(), newValue.copy())

  override def remove(key: UnsafeRow, colFamilyName: String): Unit = map.remove(key)

  override def commit(): Long = version + 1

  override def abort(): Unit = {}

  override def id: StateStoreId = null

  override def version: Long = 0

  override def metrics: StateStoreMetrics = new StateStoreMetrics(map.size, 0, Map.empty)

  override def hasCommitted: Boolean = true

  override def prefixScan(prefixKey: UnsafeRow, colFamilyName: String): Iterator[UnsafeRowPair] = {
    throw new UnsupportedOperationException("Doesn't support prefix scan!")
  }
}
