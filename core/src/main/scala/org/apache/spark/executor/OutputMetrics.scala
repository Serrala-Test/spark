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

package org.apache.spark.executor

import org.apache.spark.{Accumulable, Accumulator, InternalAccumulator}
import org.apache.spark.annotation.DeveloperApi


/**
 * :: DeveloperApi ::
 * Method by which output data was written.
 */
@DeveloperApi
object DataWriteMethod extends Enumeration with Serializable {
  type DataWriteMethod = Value
  val Hadoop = Value
}


/**
 * :: DeveloperApi ::
 * Metrics about writing output data.
 */
@DeveloperApi
class OutputMetrics private (
    val writeMethod: DataWriteMethod.Value,
    _bytesWritten: Accumulator[Long],
    _recordsWritten: Accumulator[Long])
  extends Serializable {

  private[executor] def this(
      writeMethod: DataWriteMethod.Value,
      accumMap: Map[String, Accumulable[_, _]]) {
    this(
      writeMethod,
      TaskMetrics.getLongAccum(accumMap, InternalAccumulator.output.BYTES_WRITTEN),
      TaskMetrics.getLongAccum(accumMap, InternalAccumulator.output.RECORDS_WRITTEN))
  }

  /**
   * Total number of bytes written.
   */
  def bytesWritten: Long = _bytesWritten.localValue

  /**
   * Total number of records written.
   */
  def recordsWritten: Long = _recordsWritten.localValue

  private[spark] def setBytesWritten(v: Long): Unit = _bytesWritten.setValue(v)
  private[spark] def setRecordsWritten(v: Long): Unit = _recordsWritten.setValue(v)
}
