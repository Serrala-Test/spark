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

package org.apache.spark.sql.execution.streaming.continuous.shuffle

import java.util.UUID

import org.apache.spark.{Partition, SparkContext, SparkEnv, TaskContext}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.expressions.UnsafeRow
import org.apache.spark.util.NextIterator

case class ContinuousShuffleReadPartition(index: Int) extends Partition {
  // Initialized only on the executor, and only once even as we call compute() multiple times.
  lazy val (receiver, endpoint) = {
    val env = SparkEnv.get.rpcEnv
    val receiver = new UnsafeRowReceiver(env)
    val endpoint = env.setupEndpoint(UUID.randomUUID().toString, receiver)
    TaskContext.get().addTaskCompletionListener { ctx =>
      env.stop(endpoint)
    }
    (receiver, endpoint)
  }
}

/**
 * RDD at the bottom of each continuous processing shuffle task, reading from the
 */
class ContinuousShuffleReadRDD(sc: SparkContext, numPartitions: Int)
    extends RDD[UnsafeRow](sc, Nil) {

  override protected def getPartitions: Array[Partition] = {
    (0 until numPartitions).map(ContinuousShuffleReadPartition).toArray
  }

  override def compute(split: Partition, context: TaskContext): Iterator[UnsafeRow] = {
    val receiver = split.asInstanceOf[ContinuousShuffleReadPartition].receiver

    new NextIterator[UnsafeRow] {
      override def getNext(): UnsafeRow = receiver.poll() match {
        case ReceiverRow(r) => r
        case ReceiverEpochMarker() =>
          finished = true
          null
      }

      override def close(): Unit = {}
    }
  }
}
