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

package org.apache.spark.sql.execution.exchange

import java.util.{HashMap => JHashMap, Map => JMap}
import javax.annotation.concurrent.GuardedBy

import scala.collection.mutable.ArrayBuffer

import org.apache.spark.{MapOutputStatistics, ShuffleDependency, SimpleFutureAction}
import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.plans.physical.UnknownPartitioning
import org.apache.spark.sql.execution.{ShuffledRowRDD, SkewPartitionDecs, SkewShuffleRowRDD, SparkPlan}

/**
 * A coordinator used to determines how we shuffle data between stages generated by Spark SQL.
 * Right now, the work of this coordinator is to determine the number of post-shuffle partitions
 * for a stage that needs to fetch shuffle data from one or multiple stages.
 *
 * A coordinator is constructed with three parameters, `numExchanges`,
 * `targetPostShuffleInputSize`, and `minNumPostShufflePartitions`.
 *  - `numExchanges` is used to indicated that how many [[ShuffleExchange]]s that will be registered
 *    to this coordinator. So, when we start to do any actual work, we have a way to make sure that
 *    we have got expected number of [[ShuffleExchange]]s.
 *  - `targetPostShuffleInputSize` is the targeted size of a post-shuffle partition's
 *    input data size. With this parameter, we can estimate the number of post-shuffle partitions.
 *    This parameter is configured through
 *    `spark.sql.adaptive.shuffle.targetPostShuffleInputSize`.
 *  - `minNumPostShufflePartitions` is an optional parameter. If it is defined, this coordinator
 *    will try to make sure that there are at least `minNumPostShufflePartitions` post-shuffle
 *    partitions.
 *
 * The workflow of this coordinator is described as follows:
 *  - Before the execution of a [[SparkPlan]], for a [[ShuffleExchange]] operator,
 *    if an [[ExchangeCoordinator]] is assigned to it, it registers itself to this coordinator.
 *    This happens in the `doPrepare` method.
 *  - Once we start to execute a physical plan, a [[ShuffleExchange]] registered to this
 *    coordinator will call `postShuffleRDD` to get its corresponding post-shuffle
 *    [[ShuffledRowRDD]].
 *    If this coordinator has made the decision on how to shuffle data, this [[ShuffleExchange]]
 *    will immediately get its corresponding post-shuffle [[ShuffledRowRDD]].
 *  - If this coordinator has not made the decision on how to shuffle data, it will ask those
 *    registered [[ShuffleExchange]]s to submit their pre-shuffle stages. Then, based on the
 *    size statistics of pre-shuffle partitions, this coordinator will determine the number of
 *    post-shuffle partitions and pack multiple pre-shuffle partitions with continuous indices
 *    to a single post-shuffle partition whenever necessary.
 *  - Finally, this coordinator will create post-shuffle [[ShuffledRowRDD]]s for all registered
 *    [[ShuffleExchange]]s. So, when a [[ShuffleExchange]] calls `postShuffleRDD`, this coordinator
 *    can lookup the corresponding [[RDD]].
 *
 * The strategy used to determine the number of post-shuffle partitions is described as follows.
 * To determine the number of post-shuffle partitions, we have a target input size for a
 * post-shuffle partition. Once we have size statistics of pre-shuffle partitions from stages
 * corresponding to the registered [[ShuffleExchange]]s, we will do a pass of those statistics and
 * pack pre-shuffle partitions with continuous indices to a single post-shuffle partition until
 * adding another pre-shuffle partition would cause the size of a post-shuffle partition to be
 * greater than the target size.
 *
 * For example, we have two stages with the following pre-shuffle partition size statistics:
 * stage 1: [100 MB, 20 MB, 100 MB, 10MB, 30 MB]
 * stage 2: [10 MB,  10 MB, 70 MB,  5 MB, 5 MB]
 * assuming the target input size is 128 MB, we will have three post-shuffle partitions,
 * which are:
 *  - post-shuffle partition 0: pre-shuffle partition 0 (size 110 MB)
 *  - post-shuffle partition 1: pre-shuffle partition 1 (size 30 MB)
 *  - post-shuffle partition 2: pre-shuffle partition 2 (size 170 MB)
 *  - post-shuffle partition 3: pre-shuffle partition 3 and 4 (size 50 MB)
 */
class ExchangeCoordinator(
    numExchanges: Int,
    advisoryTargetPostShuffleInputSize: Long,
    minNumPostShufflePartitions: Option[Int] = None,
    skewThreshold: Long = -1,
    isJoin: Boolean = false)
  extends Logging {

  // The registered Exchange operators.
  private[this] val exchanges = ArrayBuffer[ShuffleExchange]()

  // This map is used to lookup the post-shuffle ShuffledRowRDD for an Exchange operator.
  private[this] val postShuffleRDDs: JMap[ShuffleExchange, ShuffledRowRDD] =
    new JHashMap[ShuffleExchange, ShuffledRowRDD](numExchanges)

  // A boolean that indicates if this coordinator has made decision on how to shuffle data.
  // This variable will only be updated by doEstimationIfNecessary, which is protected by
  // synchronized.
  @volatile private[this] var estimated: Boolean = false

  /**
   * Registers a [[ShuffleExchange]] operator to this coordinator. This method is only allowed to
   * be called in the `doPrepare` method of a [[ShuffleExchange]] operator.
   */
  @GuardedBy("this")
  def registerExchange(exchange: ShuffleExchange): Unit = synchronized {
    exchanges += exchange
  }

  def isEstimated: Boolean = estimated

  /**
   * Estimates partition start indices for post-shuffle partitions based on
   * mapOutputStatistics provided by all pre-shuffle stages.
   */
  def estimatePartitionStartIndices(
      mapOutputStatistics: Array[MapOutputStatistics]): Array[Int] = {
    // If we have mapOutputStatistics.length < numExchange, it is because we do not submit
    // a stage when the number of partitions of this dependency is 0.
    assert(mapOutputStatistics.length <= numExchanges)

    // If minNumPostShufflePartitions is defined, it is possible that we need to use a
    // value less than advisoryTargetPostShuffleInputSize as the target input size of
    // a post shuffle task.
    val targetPostShuffleInputSize = minNumPostShufflePartitions match {
      case Some(numPartitions) =>
        val totalPostShuffleInputSize = mapOutputStatistics.map(_.bytesByPartitionId.sum).sum
        // The max at here is to make sure that when we have an empty table, we
        // only have a single post-shuffle partition.
        // There is no particular reason that we pick 16. We just need a number to
        // prevent maxPostShuffleInputSize from being set to 0.
        val maxPostShuffleInputSize =
          math.max(math.ceil(totalPostShuffleInputSize / numPartitions.toDouble).toLong, 16)
        math.min(maxPostShuffleInputSize, advisoryTargetPostShuffleInputSize)

      case None => advisoryTargetPostShuffleInputSize
    }

    logInfo(
      s"advisoryTargetPostShuffleInputSize: $advisoryTargetPostShuffleInputSize, " +
      s"targetPostShuffleInputSize $targetPostShuffleInputSize.")

    // Make sure we do get the same number of pre-shuffle partitions for those stages.
    val distinctNumPreShufflePartitions =
      mapOutputStatistics.map(stats => stats.bytesByPartitionId.length).distinct
    // The reason that we are expecting a single value of the number of pre-shuffle partitions
    // is that when we add Exchanges, we set the number of pre-shuffle partitions
    // (i.e. map output partitions) using a static setting, which is the value of
    // spark.sql.shuffle.partitions. Even if two input RDDs are having different
    // number of partitions, they will have the same number of pre-shuffle partitions
    // (i.e. map output partitions).
    assert(
      distinctNumPreShufflePartitions.length == 1,
      "There should be only one distinct value of the number pre-shuffle partitions " +
        "among registered Exchange operator.")
    val numPreShufflePartitions = distinctNumPreShufflePartitions.head

    val partitionStartIndices = ArrayBuffer[Int]()
    // The first element of partitionStartIndices is always 0.
    partitionStartIndices += 0

    var postShuffleInputSize = 0L

    var i = 0
    while (i < numPreShufflePartitions) {
      // We calculate the total size of ith pre-shuffle partitions from all pre-shuffle stages.
      // Then, we add the total size to postShuffleInputSize.
      var nextShuffleInputSize = 0L
      var j = 0
      while (j < mapOutputStatistics.length) {
        nextShuffleInputSize += mapOutputStatistics(j).bytesByPartitionId(i)
        j += 1
      }

      // If including the nextShuffleInputSize would exceed the target partition size, then start a
      // new partition.
      if (i > 0 && postShuffleInputSize + nextShuffleInputSize > targetPostShuffleInputSize) {
        partitionStartIndices += i
        // reset postShuffleInputSize.
        postShuffleInputSize = nextShuffleInputSize
      } else postShuffleInputSize += nextShuffleInputSize

      i += 1
    }
    partitionStartIndices.toArray
  }



 /**
  * the skew algorithm , given last stage map output statitsics,  partitionStartIndices
  * provided by estimatePartitionStartIndices function. pre-shuffle stages partition num.
  * And return a Array of 2-item tuples , the return value is use to create SkewShuffleRowRDD.
  *
  * we find data skew partition by mapOutputStatistics, and reuse partitionStartIndices which
  * provided by estimatePartitionStartIndices function, to generate new partition start indices
  * call "skewPartitionStartIndices". skewPartitionStartIndices is the function return value use
  * for generate SkewShuffleRowRDD
  * For example, we have two stages with the following pre-shuffle partition size statistics
  * stage 1: [100 MB, 10 MB, 20000 MB, 10MB, 30 MB] and stage 1 partition num is 3
  * stage 2: [10 MB,  10 MB, 70 MB,  5 MB, 5 MB]
  * assuming the target input size is 128 MB
  * obviously partition 3 is data skew partition is [2].
  * the partitionStartIndices is [0,3]
  * in this case , we find partition3 is data skew,as SPARK-9862 said ,we don't put this
  * partition in a reduce task.but broadcast other stage partition3 to this stage partition.
  * so the skewPartitionStartIndices  like this:
  * ( 5/*partition num*/
  * ((-1/*mean no skew*/,0/* index like partitionStartIndices */ ),1 /*only generate 1 partition*/)
  * (1/*mean this side data skew*/,2/*index*/,3/*generate 3 partition*/),
  * (-1,3,1))// this for generate  SkewShuffleRowRDD of stage 1.
  * ( 5/*partition num*/)
  * ((-1/*mean no skew*/),0/*index like partitionStartIndices */,1 /*only generate 1 partition*/),
  * (2/*mean  other side data skew*/,2/*index*/,3/*generate 3 partition*/),
  * (-1,3,1)// this for generate SkewShuffleRowRDD of stage 2.
  *
  * @param mapOutputStatistics pre-shuffle stages.
  * @param prePartitionNum  partition num of pre-shuffle stages
  * @param partitionStartIndices provided by estimatePartitionStartIndices function
  * @return return Array of 2-item tuples, the first item in the tuple is mean how many
  * partition should generate by SkewShuffleRowRDD, if the value is -1, then use ShuffledRowRDD
  * second item is a array of (isSkew, partition index, gen partition num)
  * isSkew is -1 mean's no skew. 1 my side is skew. SkewShuffleRowRDD should generate many
  * partition by gen partition num,a partition only read a pre-state partition one block
  * isSkew is 2 mean's other side data skew , so SkewShuffleRowRDD should generate many some
  * partition .
  */
 def skewPartitionIdx(
    mapOutputStatistics: Array[MapOutputStatistics],
    prePartitionNum: Array[Int],
    partitionStartIndices: Option[Array[Int]] = None):
 Array[(Int, Array[(Int, Long, Int, Int)])] = {

   if (mapOutputStatistics.length != 2 || !isJoin) {
     return (0 until numExchanges).map(_ =>
       (0, Array[(Int, Long, Int, Int)]((-1, 0, 0, 0)))).toArray
   }
   // find which partition is skew
   var skewPartition = mapOutputStatistics.map(ms =>
     ms.bytesByPartitionId.zipWithIndex
       .filter(x => x._1 > skewThreshold)
   )
   // if 2 stage some partition output size both over than skewSize
   // then choose a size big one as the skew side.
   skewPartition = skewPartition.zipWithIndex.map(sti => {
     val index = if (sti._2 == 0) 1 else 0
     sti._1.filterNot(
       p => skewPartition(index).exists(p1 => p1._2 == p._2 && p._1 < p1._1))
   })

   // skewSize must great than TargetPostShuffleInputSize
   val isSkew = skewPartition.flatten.length > 0 && isJoin &&
     skewThreshold > advisoryTargetPostShuffleInputSize
   if (!isSkew) {
     return (0 until numExchanges).map(_ =>
       (0, Array[(Int, Long, Int, Int)]((-1, 0, 0, 0)))).toArray
   }
   val flatSkewPartition = skewPartition.zipWithIndex.flatMap(m => m._1.map(x => (m._2, x)))
   val retIndices = new Array[ArrayBuffer[(Int, Long, Int, Int)]](numExchanges)
   for (i <- 0 until numExchanges) retIndices(i) = new ArrayBuffer[(Int, Long, Int, Int)]
   val partStartIndices: Array[Int] = partitionStartIndices match {
     case Some(indices) => indices
     case None => Array[Int]()
   }
   var pos = 0
   for (i <- 0 until partStartIndices.length) {
     val (side, thisPartitionSkew) = if (flatSkewPartition.isDefinedAt(pos)) {
       flatSkewPartition(pos)
     } else {
       (0, (-1, -1))
     }
     val otherSide = if (side == 0) 1 else 0
     if (partStartIndices(i) >= thisPartitionSkew._2 && thisPartitionSkew._2 > -1) {
       retIndices(side).append((1, thisPartitionSkew._1.asInstanceOf[Long],
         thisPartitionSkew._2, prePartitionNum(side)))
       retIndices(otherSide).append((2, thisPartitionSkew._1.asInstanceOf[Long],
         thisPartitionSkew._2, prePartitionNum(side)))
       pos += 1

       if (partStartIndices(i) > thisPartitionSkew._2 &&
         !flatSkewPartition.exists(m => {
           m._2._2 == partStartIndices(i)
         })) {
         // one range of partStartIndices only have one skew partition
         // so when partStartIndices(i) not equal to other side skew, need add skew partition
         // to  partStartIndices(i) as normal partition
         retIndices(side).append((-1, 0, partStartIndices(i), 1))
         retIndices(otherSide).append((-1, 0, partStartIndices(i), 1))
       }
     } else {
       retIndices(side).append((-1, 0, partStartIndices(i), 1))
       retIndices(otherSide).append((-1, 0, partStartIndices(i), 1))
     }

   }
   if (pos == flatSkewPartition.length - 1) {
     val (side, thisPartitionSkew: (Long, Int)) = flatSkewPartition(pos)
     val otherSide = if (side == 0) 1 else 0
     retIndices(side).append((1, thisPartitionSkew._1,
       thisPartitionSkew._2, prePartitionNum(side)))
     retIndices(otherSide).append((2, thisPartitionSkew._1,
       thisPartitionSkew._2, prePartitionNum(side)))
   }
   retIndices.map(indices => {
     val partitionNum = indices.foldLeft(0) { (z, B) =>
       if (B._1 < 0) {
         z + 1
       } else {
         val num = B._2 / skewThreshold + 1
         val partNum = if (num >= B._4) B._4 else num
         (z + partNum).asInstanceOf[Int]
       }
     }
     (partitionNum, indices.toArray)
   })
 }

  @GuardedBy("this")
  private def doEstimationIfNecessary(): Unit = synchronized {
    // It is unlikely that this method will be called from multiple threads
    // (when multiple threads trigger the execution of THIS physical)
    // because in common use cases, we will create new physical plan after
    // users apply operations (e.g. projection) to an existing DataFrame.
    // However, if it happens, we have synchronized to make sure only one
    // thread will trigger the job submission.
    if (!estimated) {
      // Make sure we have the expected number of registered Exchange operators.
      assert(exchanges.length == numExchanges)

      val newPostShuffleRDDs = new JHashMap[ShuffleExchange, ShuffledRowRDD](numExchanges)

      // Submit all map stages
      val shuffleDependencies = ArrayBuffer[ShuffleDependency[Int, InternalRow, InternalRow]]()
      val submittedStageFutures = ArrayBuffer[SimpleFutureAction[MapOutputStatistics]]()
      var i = 0
      while (i < numExchanges) {
        val exchange = exchanges(i)
        val shuffleDependency = exchange.prepareShuffleDependency()
        shuffleDependencies += shuffleDependency
        if (shuffleDependency.rdd.partitions.length != 0) {
          // submitMapStage does not accept RDD with 0 partition.
          // So, we will not submit this dependency.
          submittedStageFutures +=
            exchange.sqlContext.sparkContext.submitMapStage(shuffleDependency)
        }
        i += 1
      }

      // Wait for the finishes of those submitted map stages.
      val mapOutputStatistics = new Array[MapOutputStatistics](submittedStageFutures.length)
      var j = 0
      while (j < submittedStageFutures.length) {
        // This call is a blocking call. If the stage has not finished, we will wait at here.
        mapOutputStatistics(j) = submittedStageFutures(j).get()
        j += 1
      }

      // Now, we estimate partitionStartIndices. partitionStartIndices.length will be the
      // number of post-shuffle partitions.
      val partitionStartIndices =
        if (mapOutputStatistics.length == 0) {
          None
        } else {
          Some(estimatePartitionStartIndices(mapOutputStatistics))
        }

      var k = 0
      val prePartitionNum =
        exchanges.map(e => e.prepareShuffleDependency().rdd.partitions.length).toArray
      val skewPartitionIndices =
        skewPartitionIdx(mapOutputStatistics, prePartitionNum, partitionStartIndices)

      while (k < numExchanges) {
        val exchange = exchanges(k)
        val rdd = if (skewPartitionIndices(k)._1 > 0) {
          exchange.newPartitioning = UnknownPartitioning(skewPartitionIndices(k)._1)
          val skewParts: Array[SkewPartitionDecs] = skewPartitionIndices(k)._2.
            map(m => SkewPartitionDecs(m._1, m._2, m._3, m._4))
          new SkewShuffleRowRDD(shuffleDependencies(k),
            skewParts,
            skewThreshold)
        } else exchange.preparePostShuffleRDD(shuffleDependencies(k), partitionStartIndices)
        newPostShuffleRDDs.put(exchange, rdd)

        k += 1
      }

      // Finally, we set postShuffleRDDs and estimated.
      assert(postShuffleRDDs.isEmpty)
      assert(newPostShuffleRDDs.size() == numExchanges)
      postShuffleRDDs.putAll(newPostShuffleRDDs)
      estimated = true
    }
  }

  def postShuffleRDD(exchange: ShuffleExchange): ShuffledRowRDD = {
    doEstimationIfNecessary()

    if (!postShuffleRDDs.containsKey(exchange)) {
      throw new IllegalStateException(
        s"The given $exchange is not registered in this coordinator.")
    }

    postShuffleRDDs.get(exchange)
  }

  override def toString: String = {
    s"coordinator[target post-shuffle partition size: $advisoryTargetPostShuffleInputSize]"
  }
}
