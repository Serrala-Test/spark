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

import java.io.{IOException, ObjectOutputStream}

import scala.reflect._

import org.apache.spark._
import org.apache.spark.storage.{RDDBlockId, StorageLevel}
import org.apache.spark.util.{TaskCompletionListener, TaskFailureListener, Utils}

private[spark]
class CartesianPartition(
    idx: Int,
    @transient private val rdd1: RDD[_],
    @transient private val rdd2: RDD[_],
    s1Index: Int,
    s2Index: Int
  ) extends Partition {
  var s1 = rdd1.partitions(s1Index)
  var s2 = rdd2.partitions(s2Index)
  override val index: Int = idx

  @throws(classOf[IOException])
  private def writeObject(oos: ObjectOutputStream): Unit = Utils.tryOrIOException {
    // Update the reference to parent split at the time of task serialization
    s1 = rdd1.partitions(s1Index)
    s2 = rdd2.partitions(s2Index)
    oos.defaultWriteObject()
  }
}

private[spark]
class CartesianRDD[T: ClassTag, U: ClassTag](
    sc: SparkContext,
    var rdd1 : RDD[T],
    var rdd2 : RDD[U])
  extends RDD[(T, U)](sc, Nil)
  with Serializable {

  logInfo("-------------> In Cartesian RDD initialize")

  val numPartitionsInRdd2 = rdd2.partitions.length

  override def getPartitions: Array[Partition] = {
    // create the cross product split
    val array = new Array[Partition](rdd1.partitions.length * rdd2.partitions.length)
    for (s1 <- rdd1.partitions; s2 <- rdd2.partitions) {
      val idx = s1.index * numPartitionsInRdd2 + s2.index
      array(idx) = new CartesianPartition(idx, rdd1, rdd2, s1.index, s2.index)
    }
    array
  }

  override def getPreferredLocations(split: Partition): Seq[String] = {
    val currSplit = split.asInstanceOf[CartesianPartition]
    (rdd1.preferredLocations(currSplit.s1) ++ rdd2.preferredLocations(currSplit.s2)).distinct
  }

  override def compute(split: Partition, context: TaskContext): Iterator[(T, U)] = {
    logInfo("-------------------> In Cartesian compute method")
    val currSplit = split.asInstanceOf[CartesianPartition]
    for (x <- rdd1.iterator(currSplit.s1, context);
         y <- getOrCacheBlock(rdd2, currSplit.s2, context, StorageLevel.MEMORY_AND_DISK))
      yield (x, y)
  }

  private def getOrCacheBlock(
      rdd: RDD[U],
      partition: Partition,
      context: TaskContext,
      level: StorageLevel): Iterator[U] = {
    logInfo("--------------> in getOrCacheBlock")
    val blockId = RDDBlockId(rdd.id, partition.index)
    var readCachedBlock = true
    // This method is called on executors, so we need call SparkEnv.get instead of sc.env.
    val iterator = SparkEnv.get.blockManager.getOrElseUpdate(blockId, level, classTag[U], () => {
      readCachedBlock = false
      rdd.iterator(partition, context)
    }, true) match {
      case Left(blockResult) =>
        if (readCachedBlock) {
          val existingMetrics = context.taskMetrics().inputMetrics
          existingMetrics.incBytesRead(blockResult.bytes)
          new InterruptibleIterator[U](context, blockResult.data.asInstanceOf[Iterator[U]]) {
            override def next(): U = {
              existingMetrics.incRecordsRead(1)
              delegate.next()
            }
          }
        } else {
          new InterruptibleIterator(context, blockResult.data.asInstanceOf[Iterator[U]])
        }
      case Right(iter) =>
        new InterruptibleIterator(context, iter.asInstanceOf[Iterator[U]])
    }

//    context.addTaskCompletionListener(new TaskCompletionListener{
//      override def onTaskCompletion(context: TaskContext): Unit = {
//        if (!readCachedBlock) {
//          SparkEnv.get.blockManager.removeBlock(blockId, false)
//        }
//      }
//    })
//
//    context.addTaskFailureListener(new TaskFailureListener{
//      override def onTaskFailure(context: TaskContext, error: Throwable): Unit = {
//        if (!readCachedBlock) {
//          SparkEnv.get.blockManager.removeBlock(blockId, false)
//        }
//      }
//    })

    iterator
  }

  override def getDependencies: Seq[Dependency[_]] = List(
    new NarrowDependency(rdd1) {
      def getParents(id: Int): Seq[Int] = List(id / numPartitionsInRdd2)
    },
    new NarrowDependency(rdd2) {
      def getParents(id: Int): Seq[Int] = List(id % numPartitionsInRdd2)
    }
  )

  override def clearDependencies() {
    super.clearDependencies()
    rdd1 = null
    rdd2 = null
  }
}
