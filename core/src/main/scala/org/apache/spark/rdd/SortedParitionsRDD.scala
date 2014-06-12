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
import scala.collection.mutable.ArrayBuffer
import java.io.{InputStream, BufferedInputStream, FileInputStream, File, Serializable, EOFException}

import org.apache.spark.{Partition, TaskContext}
import org.apache.spark.{Logging, SparkEnv}
import org.apache.spark.serializer.Serializer
import org.apache.spark.storage.{BlockId, BlockManager}

private[spark] class SortedPartitionsRDD[T: ClassTag](
    prev: RDD[T],
    lt: (T, T) => Boolean)
  extends RDD[T](prev) {

  override def getPartitions: Array[Partition] = firstParent[T].partitions

  override val partitioner = prev.partitioner    // Since sorting partitions cannot change a partition's keys

  override def compute(split: Partition, context: TaskContext) = {
    new SortedIterator(firstParent[T].iterator(split, context), lt)
  }
}

private[spark] class SortedIterator[T](iter: Iterator[T], lt: (T, T) => Boolean) extends Iterator[T] {    
  private val sorted = doSort()
  
  def hasNext : Boolean = {
    sorted.hasNext
  }
  
  def next : T = {
    sorted.next
  }
  
  private def doSort() : Iterator[T] = {
    val chunkList = new ArrayBuffer[Iterator[T]]()
    chunkList += nextChunk
    while (iter.hasNext) {
      chunkList += nextChunk
    }
    merge(chunkList)
  }
  
  private def merge(list : ArrayBuffer[Iterator[T]]) : Iterator[T] = {
    if (list.size == 1) {
      return list(0)
    }
    if (list.size == 2) {
      return doMerge(list(0), list(1))
    }
    val mid = list.size >> 1
    // sort right first since it's in memory
    val right = merge(list.slice(mid, list.size))
    val left = merge(list.slice(0, mid))
    doMerge(left, right)
  }

  private def doMerge(it1 : Iterator[T], it2 : Iterator[T]) : Iterator[T] = {
    var array = new DiskBuffer[T]()
//    var array = new ArrayBuffer[T]()
    if (!it1.hasNext) {
      array ++= it2
      return array.iterator
    }
    if (!it2.hasNext) {
      array ++= it1
      return array.iterator
    }
    var t1 = it1.next
    var t2 = it2.next
    while (true) {
      if (lt(t1, t2)) {
        array += t1
        if (it1.hasNext) {
          t1 = it1.next
        } else {
          array += t2
          array ++= it2
          return array.iterator
        }
      } else {
        array += t2
        if (it2.hasNext) {
          t2 = it2.next
        } else {
          array += t1
          array ++= it1
          return array.iterator
        }
      }
    }
    array.iterator
  }

  private def nextChunk() : Iterator[T] = {
    var array = new ArrayBuffer[T](10000)
    // TODO: use SizeEstimator
    while (array.size < 1000 && iter.hasNext) {
      array += iter.next
    }
    return array.sortWith(lt).iterator
  }
}

private class DiskBuffer[T] {
  private val serializer = SparkEnv.get.serializer
  private val blockManager = SparkEnv.get.blockManager
  private val diskBlockManager = blockManager.diskBlockManager
  private val sparkConf = SparkEnv.get.conf
  private val fileBufferSize = sparkConf.getInt("spark.shuffle.file.buffer.kb", 100) * 1024

  val (blockId, file) = diskBlockManager.createTempBlock()
  var writer = blockManager.getDiskWriter(blockId, file, serializer, fileBufferSize)

  def +=(elem: T): DiskBuffer.this.type = {
    writer.write(elem)
    this
  }

  def ++=(xs: TraversableOnce[T]): DiskBuffer.this.type = {
    xs.foreach(writer.write(_))
    this
  }

  def iterator : Iterator[T] = {
    writer.close
    val fileBufferSize = sparkConf.getInt("spark.shuffle.file.buffer.kb", 100) * 1024
    new DiskBufferIterator(file, blockId, serializer, fileBufferSize)
  }
}

private class DiskBufferIterator[T](file: File, blockId: BlockId, serializer: Serializer, fileBufferSize : Int) extends Iterator[T] {
  private val fileStream = new FileInputStream(file)
  private val bufferedStream = new BufferedInputStream(fileStream, fileBufferSize)
  private var compressedStream = SparkEnv.get.blockManager.wrapForCompression(blockId, bufferedStream)
  private var deserializeStream = serializer.newInstance.deserializeStream(compressedStream)
  private var nextItem : T = null.asInstanceOf[T]
  
  def hasNext : Boolean = {
    if (nextItem == null) {
      nextItem = doNext()
    }
    nextItem != null
  }
  
  def next() : T = {
    val item = if (nextItem == null) doNext else nextItem
    if (item == null) {
      throw new NoSuchElementException
    }
    nextItem = null.asInstanceOf[T]
    item
  }

  private def doNext() : T = {
    try {
      deserializeStream.readObject().asInstanceOf[T]
    } catch {
      case e: EOFException =>
        cleanup
        null.asInstanceOf[T]
    }
  }

  private def cleanup() = {
    deserializeStream.close()
    file.delete()
  }
}
