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

package org.apache.spark.rdd

import scala.reflect.ClassTag

import org.apache.spark._
import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.rdd.SortOrder.SortOrder
import org.apache.spark.serializer.Serializer

private[spark] class ShuffledRDDPartition(val idx: Int) extends Partition {
  override val index = idx
  override def hashCode(): Int = idx
}

/**
 * :: DeveloperApi ::
 * The resulting RDD from a shuffle (e.g. repartitioning of data).
 * @param prev the parent RDD.
 * @param part the partitioner used to partition the RDD
 * @tparam K the key class.
 * @tparam V the value class.
 * @tparam C the combiner class.
 */
@DeveloperApi
class ShuffledRDD[K, V, C, P <: Product2[K, C] : ClassTag](
    @transient var prev: RDD[_ <: Product2[K, V]],
    part: Partitioner)
  extends RDD[P](prev.context, Nil) {

  private var serializer: Option[Serializer] = None

  private var keyOrdering: Option[Ordering[K]] = None

  private var aggregator: Option[Aggregator[K, V, C]] = None

  private var mapSideCombine: Boolean = false

  private var sortOrder: Option[SortOrder] = None

  /** Set a serializer for this RDD's shuffle, or null to use the default (spark.serializer) */
  def setSerializer(serializer: Serializer): ShuffledRDD[K, V, C, P] = {
    this.serializer = Option(serializer)
    this
  }

  /** Set key ordering for RDD's shuffle. */
  def setKeyOrdering(keyOrdering: Ordering[K]): ShuffledRDD[K, V, C, P] = {
    this.keyOrdering = Option(keyOrdering)
    this
  }

  /** Set aggregator for RDD's shuffle. */
  def setAggregator(aggregator: Aggregator[K, V, C]): ShuffledRDD[K, V, C, P] = {
    this.aggregator = Option(aggregator)
    this
  }

  /** Set mapSideCombine flag for RDD's shuffle. */
  def setMapSideCombine(mapSideCombine: Boolean): ShuffledRDD[K, V, C, P] = {
    this.mapSideCombine = mapSideCombine
    this
  }

  /** Set sort order for RDD's sorting. */
  def setSortOrder(sortOrder: SortOrder): ShuffledRDD[K, V, C, P] = {
    this.sortOrder = Option(sortOrder)
    this
  }

  override def getDependencies: Seq[Dependency[_]] = {
    List(new ShuffleDependency(prev, part, serializer,
      keyOrdering, aggregator, mapSideCombine, sortOrder))
  }

  override val partitioner = Some(part)

  override def getPartitions: Array[Partition] = {
    Array.tabulate[Partition](part.numPartitions)(i => new ShuffledRDDPartition(i))
  }

  override def compute(split: Partition, context: TaskContext): Iterator[P] = {
    val dep = dependencies.head.asInstanceOf[ShuffleDependency[K, V, C]]
    SparkEnv.get.shuffleManager.getReader(dep.shuffleHandle, split.index, split.index + 1, context)
      .read()
      .asInstanceOf[Iterator[P]]
  }

  override def clearDependencies() {
    super.clearDependencies()
    prev = null
  }
}
