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

package org.apache.spark

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.util.collection.{AppendOnlyMap, ExternalAppendOnlyMap}
import org.apache.spark.serializer.Serializer

/**
 * :: DeveloperApi ::
 * A set of functions used to aggregate data.
 *
 * @param createCombiner function to create the initial value of the aggregation.
 * @param mergeValue function to merge a new value into the aggregation result.
 * @param mergeCombiners function to merge outputs from multiple mergeValue function.
 * @param serializer serializer to persist data internally.
 */
@DeveloperApi
case class Aggregator[K, V, C] (
    createCombiner: V => C,
    mergeValue: (C, V) => C,
    mergeCombiners: (C, C) => C,
    serializer: Serializer = SparkEnv.get.serializer) {

  private val externalSorting = SparkEnv.get.conf.getBoolean("spark.shuffle.spill", true)

  @deprecated("use combineValuesByKey with TaskContext argument", "0.9.0")
  def combineValuesByKey(iter: Iterator[_ <: Product2[K, V]]): Iterator[(K, C)] =
    combineValuesByKey(iter, null)

  def combineValuesByKey(iter: Iterator[_ <: Product2[K, V]],
                         context: TaskContext): Iterator[(K, C)] = {
    if (!externalSorting) {
      val combiners = new AppendOnlyMap[K,C]
      var kv: Product2[K, V] = null
      val update = (hadValue: Boolean, oldValue: C) => {
        if (hadValue) mergeValue(oldValue, kv._2) else createCombiner(kv._2)
      }
      while (iter.hasNext) {
        kv = iter.next()
        combiners.changeValue(kv._1, update)
      }
      combiners.iterator
    } else {
      val combiners = new ExternalAppendOnlyMap[K, V, C](
        createCombiner, mergeValue, mergeCombiners, serializer)
      while (iter.hasNext) {
        val (k, v) = iter.next()
        combiners.insert(k, v)
      }
      // TODO: Make this non optional in a future release
      Option(context).foreach(c => c.taskMetrics.memoryBytesSpilled = combiners.memoryBytesSpilled)
      Option(context).foreach(c => c.taskMetrics.diskBytesSpilled = combiners.diskBytesSpilled)
      combiners.iterator
    }
  }

  @deprecated("use combineCombinersByKey with TaskContext argument", "0.9.0")
  def combineCombinersByKey(iter: Iterator[(K, C)]) : Iterator[(K, C)] =
    combineCombinersByKey(iter, null)

  def combineCombinersByKey(iter: Iterator[(K, C)], context: TaskContext) : Iterator[(K, C)] = {
    if (!externalSorting) {
      val combiners = new AppendOnlyMap[K,C]
      var kc: Product2[K, C] = null
      val update = (hadValue: Boolean, oldValue: C) => {
        if (hadValue) mergeCombiners(oldValue, kc._2) else kc._2
      }
      while (iter.hasNext) {
        kc = iter.next()
        combiners.changeValue(kc._1, update)
      }
      combiners.iterator
    } else {
      val combiners = new ExternalAppendOnlyMap[K, C, C](
        identity, mergeCombiners, mergeCombiners, serializer)
      while (iter.hasNext) {
        val (k, c) = iter.next()
        combiners.insert(k, c)
      }
      // TODO: Make this non optional in a future release
      Option(context).foreach(c => c.taskMetrics.memoryBytesSpilled = combiners.memoryBytesSpilled)
      Option(context).foreach(c => c.taskMetrics.diskBytesSpilled = combiners.diskBytesSpilled)
      combiners.iterator
    }
  }
}
