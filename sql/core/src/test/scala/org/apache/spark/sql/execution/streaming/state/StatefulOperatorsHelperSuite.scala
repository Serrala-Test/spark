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

import org.apache.spark.sql.catalyst.expressions.{Attribute, SpecificInternalRow, UnsafeProjection, UnsafeRow}
import org.apache.spark.sql.catalyst.expressions.codegen.GenerateUnsafeProjection
import org.apache.spark.sql.execution.streaming.StatefulOperatorsHelper.StreamingAggregationStateManager
import org.apache.spark.sql.streaming.StreamTest
import org.apache.spark.sql.types.{IntegerType, StructField, StructType}

class StatefulOperatorsHelperSuite extends StreamTest {
  // ============================ fields and method for test data ============================

  val testKeys: Seq[String] = Seq("key1", "key2")
  val testValues: Seq[String] = Seq("sum(key1)", "sum(key2)")

  val testOutputSchema: StructType = StructType(
    testKeys.map(createIntegerField) ++ testValues.map(createIntegerField))

  val testOutputAttributes: Seq[Attribute] = testOutputSchema.toAttributes
  val testKeyAttributes: Seq[Attribute] = testOutputAttributes.filter { p =>
    testKeys.contains(p.name)
  }
  val testValuesAttributes: Seq[Attribute] = testOutputAttributes.filter { p =>
    testValues.contains(p.name)
  }
  val expectedTestValuesSchema: StructType = testValuesAttributes.toStructType

  val testRow: UnsafeRow = {
    val unsafeRowProjection = UnsafeProjection.create(testOutputSchema)
    val row = unsafeRowProjection(new SpecificInternalRow(testOutputSchema))
    (testKeys ++ testValues).zipWithIndex.foreach { case (_, index) => row.setInt(index, index) }
    row
  }

  val expectedTestKeyRow: UnsafeRow = {
    val keyProjector = GenerateUnsafeProjection.generate(testKeyAttributes, testOutputAttributes)
    keyProjector(testRow)
  }

  val expectedTestValueRowForV2: UnsafeRow = {
    val valueProjector = GenerateUnsafeProjection.generate(testValuesAttributes,
      testOutputAttributes)
    valueProjector(testRow)
  }

  private def createIntegerField(name: String): StructField = {
    StructField(name, IntegerType, nullable = false)
  }

  // ============================ StateManagerImplV1 ============================

  test("StateManager v1 - get, put, iter") {
    val stateManager = newStateManager(testKeyAttributes, testOutputAttributes, 1)

    // in V1, input row is stored as value
    testGetPutIterOnStateManager(stateManager, testOutputSchema, testRow,
      expectedTestKeyRow, testRow)
  }

  // ============================ StateManagerImplV2 ============================
  test("StateManager v2 - get, put, iter") {
    val stateManager = newStateManager(testKeyAttributes, testOutputAttributes, 2)

    // in V2, row for values itself (excluding keys from input row) is stored as value
    // so that stored value doesn't have key part, but state manager V2 will provide same output
    // as V1 when getting row for key
    testGetPutIterOnStateManager(stateManager, expectedTestValuesSchema, testRow,
      expectedTestKeyRow, expectedTestValueRowForV2)
  }

  private def newStateManager(
      keysAttributes: Seq[Attribute],
      inputRowAttributes: Seq[Attribute],
      version: Int): StreamingAggregationStateManager = {
    StreamingAggregationStateManager.createStateManager(keysAttributes, inputRowAttributes, version)
  }

  private def testGetPutIterOnStateManager(
      stateManager: StreamingAggregationStateManager,
      expectedValueSchema: StructType,
      inputRow: UnsafeRow,
      expectedStateKey: UnsafeRow,
      expectedStateValue: UnsafeRow): Unit = {

    assert(stateManager.getStateValueSchema === expectedValueSchema)

    val memoryStateStore = new MemoryStateStore()
    stateManager.put(memoryStateStore, inputRow)

    assert(memoryStateStore.iterator().size === 1)

    val keyRow = stateManager.getKey(inputRow)
    assert(keyRow === expectedStateKey)

    // iterate state store and verify whether expected format of key and value are stored
    val pair = memoryStateStore.iterator().next()
    assert(pair.key === keyRow)
    assert(pair.value === expectedStateValue)
    assert(stateManager.restoreOriginRow(pair) === inputRow)

    // verify the stored value once again via get
    assert(memoryStateStore.get(keyRow) === expectedStateValue)

    // state manager should return row which is same as input row regardless of format version
    assert(inputRow === stateManager.get(memoryStateStore, keyRow))
  }

}
