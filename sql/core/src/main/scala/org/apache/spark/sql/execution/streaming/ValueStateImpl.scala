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
package org.apache.spark.sql.execution.streaming

import java.time.Duration

import org.apache.spark.internal.Logging
import org.apache.spark.sql.Encoder
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.execution.streaming.StateKeyValueRowSchema.{KEY_ROW_SCHEMA, VALUE_ROW_SCHEMA}
import org.apache.spark.sql.execution.streaming.state.{NoPrefixKeyStateEncoderSpec, StateStore}
import org.apache.spark.sql.streaming.{TTLMode, ValueState}

/**
 * Class that provides a concrete implementation for a single value state associated with state
 * variables used in the streaming transformWithState operator.
 * @param store - reference to the StateStore instance to be used for storing state
 * @param stateName - name of logical state partition
 * @param keyExprEnc - Spark SQL encoder for key
 * @param valEncoder - Spark SQL encoder for value
 * @param ttlMode ttl Mode to evict expired values from state
 * @param batchTimestampMs processing timestamp of the current batch.
 * @param eventTimeWatermarkMs event time watermark for state eviction
 * @tparam S - data type of object that will be stored
 */
class ValueStateImpl[S](
    store: StateStore,
    stateName: String,
    keyExprEnc: ExpressionEncoder[Any],
    valEncoder: Encoder[S],
    ttlMode: TTLMode,
    batchTimestampMs: Option[Long],
    eventTimeWatermarkMs: Option[Long])
  extends ValueState[S]
    with Logging
    with StateVariableTTLSupport {

  private val keySerializer = keyExprEnc.createSerializer()
  private val stateTypesEncoder = StateTypesEncoder(keySerializer, valEncoder, stateName)
  private[sql] var ttlState: Option[SingleKeyTTLState] = None

  initialize()

  private def initialize(): Unit = {
    store.createColFamilyIfAbsent(stateName, KEY_ROW_SCHEMA, VALUE_ROW_SCHEMA,
      NoPrefixKeyStateEncoderSpec(KEY_ROW_SCHEMA))

    if (ttlMode != TTLMode.NoTTL()) {
      val _ttlState = new SingleKeyTTLState(ttlMode, stateName, store,
        batchTimestampMs, eventTimeWatermarkMs, this)
      ttlState = Some(_ttlState)
    }
  }

  /** Function to check if state exists. Returns true if present and false otherwise */
  override def exists(): Boolean = {
    get() != null
  }

  /** Function to return Option of value if exists and None otherwise */
  override def getOption(): Option[S] = {
    Option(get())
  }

  /** Function to return associated value with key if exists and null otherwise */
  override def get(): S = {
    val encodedGroupingKey = stateTypesEncoder.encodeGroupingKey()
    val retRow = store.get(encodedGroupingKey, stateName)

    if (retRow != null) {
      val resState = stateTypesEncoder.decodeValue(retRow)

      val expirationMs = stateTypesEncoder.decodeTtlExpirationMs(retRow)
      val isExpired = StateTTL.isExpired(ttlMode,
        expirationMs, batchTimestampMs, eventTimeWatermarkMs)

      if (!isExpired) {
        resState
      } else {
        null.asInstanceOf[S]
      }
    } else {
      null.asInstanceOf[S]
    }
  }

  /** Function to update and overwrite state associated with given key */
  override def update(
      newState: S,
      ttlDuration: Duration = Duration.ZERO): Unit = {

    if (ttlDuration != Duration.ZERO && ttlState.isEmpty) {
      // TODO(sahnib) throw a StateStoreError here
      throw new RuntimeException()
    }

    var expirationMs: Long = -1
    if (ttlDuration != Duration.ZERO) {
      expirationMs = StateTTL.calculateExpirationTimeForDuration(
        ttlMode, ttlDuration, batchTimestampMs, eventTimeWatermarkMs)
    }

    val serializedGroupingKey = stateTypesEncoder.serializeGroupingKey()
    store.put(stateTypesEncoder.encodeSerializedGroupingKey(serializedGroupingKey),
      stateTypesEncoder.encodeValue(newState, expirationMs), stateName)

    ttlState.foreach(_.upsertTTLForStateKey(expirationMs, serializedGroupingKey))
  }

  /** Function to remove state for given key */
  override def clear(): Unit = {
    store.remove(stateTypesEncoder.encodeGroupingKey(), stateName)
  }

  def clearIfExpired(groupingKey: Array[Byte]): Unit = {
    val encodedGroupingKey = stateTypesEncoder.encodeSerializedGroupingKey(groupingKey)
    val retRow = store.get(encodedGroupingKey, stateName)

    if (retRow != null) {
      val expirationMs = stateTypesEncoder.decodeTtlExpirationMs(retRow)
      val isExpired = StateTTL.isExpired(ttlMode,
        expirationMs, batchTimestampMs, eventTimeWatermarkMs)

      if (!isExpired) {
        store.remove(encodedGroupingKey, stateName)
      }
    }
  }
}
