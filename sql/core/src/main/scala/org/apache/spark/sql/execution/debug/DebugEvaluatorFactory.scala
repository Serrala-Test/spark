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

package org.apache.spark.sql.execution.debug

import org.apache.spark.{PartitionEvaluator, PartitionEvaluatorFactory}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.util.LongAccumulator

class DebugEvaluatorFactory(
    tupleCount: LongAccumulator,
    numColumns: Int,
    columnAccumulator: Array[SetAccumulator[String]],
    output: Seq[Attribute]) extends PartitionEvaluatorFactory[InternalRow, InternalRow] {
  override def createEvaluator(): PartitionEvaluator[InternalRow, InternalRow] = {
    new DebugEvaluator()
  }

  class DebugEvaluator extends PartitionEvaluator[InternalRow, InternalRow] {
    override def eval(
        partitionIndex: Int,
        inputs: Iterator[InternalRow]*): Iterator[InternalRow] = {
      val input = inputs.head
      new Iterator[InternalRow] {
        def hasNext: Boolean = input.hasNext

        def next(): InternalRow = {
          val currentRow = input.next()
          tupleCount.add(1)
          var i = 0
          while (i < numColumns) {
            val value = currentRow.get(i, output(i).dataType)
            if (value != null) {
              columnAccumulator(i).add(value.getClass.getName)
            }
            i += 1
          }
          currentRow
        }
      }
    }
  }
}
