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

import scala.reflect.ClassTag

import org.apache.spark.rdd.RDD
import org.apache.spark.util.CollectionsUtils
import org.apache.spark.util.Utils

/**
 * An object that defines how the elements in a key-value pair RDD are partitioned by key.
 * Maps each key to a partition ID, from 0 to `numPartitions - 1`.
 */
abstract class Partitioner extends Serializable {
  def numPartitions: Int
  def getPartition(key: Any): Int
}

object Partitioner {
  /**
   * Choose a partitioner to use for a cogroup-like operation between a number of RDDs.
   *
   * If any of the RDDs already has a partitioner, choose that one.
   *
   * Otherwise, we use a default HashPartitioner. For the number of partitions, if
   * spark.default.parallelism is set, then we'll use the value from SparkContext
   * defaultParallelism, otherwise we'll use the max number of upstream partitions.
   *
   * Unless spark.default.parallelism is set, the number of partitions will be the
   * same as the number of partitions in the largest upstream RDD, as this should
   * be least likely to cause out-of-memory errors.
   *
   * We use two method parameters (rdd, others) to enforce callers passing at least 1 RDD.
   */
  def defaultPartitioner(rdd: RDD[_], others: RDD[_]*): Partitioner = {
    val bySize = (Seq(rdd) ++ others).sortBy(_.partitions.size).reverse
    for (r <- bySize if r.partitioner.isDefined) {
      return r.partitioner.get
    }
    if (rdd.context.conf.contains("spark.default.parallelism")) {
      new HashPartitioner(rdd.context.defaultParallelism)
    } else {
      new HashPartitioner(bySize.head.partitions.size)
    }
  }
}

/**
 * A [[org.apache.spark.Partitioner]] that implements hash-based partitioning using
 * Java's `Object.hashCode`.
 *
 * Java arrays have hashCodes that are based on the arrays' identities rather than their contents,
 * so attempting to partition an RDD[Array[_]] or RDD[(Array[_], _)] using a HashPartitioner will
 * produce an unexpected or incorrect result.
 */
class HashPartitioner(partitions: Int) extends Partitioner {
  def numPartitions = partitions

  def getPartition(key: Any): Int = key match {
    case null => 0
    case _ => Utils.nonNegativeMod(key.hashCode, numPartitions)
  }

  override def equals(other: Any): Boolean = other match {
    case h: HashPartitioner =>
      h.numPartitions == numPartitions
    case _ =>
      false
  }
}

/**
 * A [[org.apache.spark.Partitioner]] that partitions sortable records by range into roughly
 * equal ranges. The ranges are determined by sampling the content of the RDD passed in.
 */
class RangePartitioner[K : Ordering : ClassTag, V](
    partitions: Int,
    @transient rdd: RDD[_ <: Product2[K,V]],
    private val ascending: Boolean = true)
  extends Partitioner {

  private val ordering = implicitly[Ordering[K]]

  // An array of upper bounds for the first (partitions - 1) partitions
  private val rangeBounds: Array[K] = {
    if (partitions == 1) {
      Array()
    } else {
      val rddSize = rdd.count()
      val maxSampleSize = partitions * 20.0
      val frac = math.min(maxSampleSize / math.max(rddSize, 1), 1.0)
      val rddSample = rdd.sample(false, frac, 1).map(_._1).collect().sorted
      if (rddSample.length == 0) {
        Array()
      } else {
        val bounds = new Array[K](partitions - 1)
        for (i <- 0 until partitions - 1) {
          val index = (rddSample.length - 1) * (i + 1) / partitions
          bounds(i) = rddSample(index)
        }
        bounds
      }
    }
  }

  def numPartitions = partitions

  private val binarySearch: ((Array[K], K) => Int) = CollectionsUtils.makeBinarySearch[K]

  def getPartition(key: Any): Int = {
    val k = key.asInstanceOf[K]
    var partition = 0
    if (rangeBounds.length < 1000) {
      // If we have less than 100 partitions naive search
      while (partition < rangeBounds.length && ordering.gt(k, rangeBounds(partition))) {
        partition += 1
      }
    } else {
      // Determine which binary search method to use only once.
      partition = binarySearch(rangeBounds, k)
      // binarySearch either returns the match location or -[insertion point]-1
      if (partition < 0) {
        partition = -partition-1
      }
      if (partition > rangeBounds.length) {
        partition = rangeBounds.length
      }
    }
    if (ascending) {
      partition
    } else {
      rangeBounds.length - partition
    }
  }

  override def equals(other: Any): Boolean = other match {
    case r: RangePartitioner[_,_] =>
      r.rangeBounds.sameElements(rangeBounds) && r.ascending == ascending
    case _ =>
      false
  }
}

/**
 * A [[org.apache.spark.Partitioner]] that partitions records into specified bounds
 * Default value is 1000. Once all partitions have bounds elements, the partitioner
 * allocates 1 element per partition so eventually the smaller partitions are at most
 * off by 1 key compared to the larger partitions.
 */
class BoundaryPartitioner[K : Ordering : ClassTag, V](
                                                    partitions: Int,
                                                    @transient rdd: RDD[_ <: Product2[K,V]],
                                                    private val boundary: Int = 1000)
  extends Partitioner {

  // this array keeps track of keys assigned to a partition
  // counts[0] refers to # of keys in partition 0 and so on
  private val counts: Array[Int] = {
    new Array[Int](numPartitions)
  }

  def numPartitions = math.abs(partitions)

  /*
  * Ideally, this should've been calculated based on # partitions and total keys
  * But we are not calling count on RDD here to avoid calling an action.
   * User has the flexibility of calling count and passing in any appropriate boundary
   */
  def keysPerPartition = boundary

  var currPartition = 0

  /*
  * Pick current partition for the key until we hit the bound for keys / partition,
  * start allocating to next partition at that time.
  *
  * NOTE: In case where we have lets say 2000 keys and user says 3 partitions with 500
  * passed in as boundary, the first 500 will goto P1, 501-1000 go to P2, 1001-1500 go to P3,
  * after that, next keys go to one partition at a time. So 1501 goes to P1, 1502 goes to P2,
  * 1503 goes to P3 and so on.
   */
  def getPartition(key: Any): Int = {
    val partition = currPartition
    counts(partition) = counts(partition) + 1
    /*
    * Since we are filling up a partition before moving to next one (this helps in maintaining
    * order of keys, in certain cases, it is possible to end up with empty partitions, like
    * 3 partitions, 500 keys / partition and if rdd has 700 keys, 1 partition will be entirely
    * empty.
     */
    if(counts(currPartition) >= keysPerPartition)
      currPartition = (currPartition + 1) % numPartitions
    partition
  }

  override def equals(other: Any): Boolean = other match {
    case r: BoundaryPartitioner[_,_] =>
      (r.counts.sameElements(counts) && r.boundary == boundary
        && r.currPartition == currPartition)
    case _ =>
      false
  }
}
