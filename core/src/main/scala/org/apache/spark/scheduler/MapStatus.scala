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

package org.apache.spark.scheduler

import java.io.{Externalizable, ObjectInput, ObjectOutput}

import org.roaringbitmap.RoaringBitmap

import org.apache.spark.storage.BlockManagerId

/**
 * Result returned by a ShuffleMapTask to a scheduler. Includes the block manager address that the
 * task ran on as well as the sizes of outputs for each reducer, for passing on to the reduce tasks.
 */
private[spark] sealed trait MapStatus {
  /** Location where this task was run. */
  def location: BlockManagerId

  /**
   * Estimated size for the reduce block, in bytes.
   *
   * If a block is non-empty, then this method MUST return a non-zero size.  This invariant is
   * necessary for correctness, since block fetchers are allowed to skip zero-size blocks.
   */
  def getSizeForBlock(reduceId: Int): Long
}


private[spark] object MapStatus {

  def apply(loc: BlockManagerId, uncompressedSizes: Array[Long]): MapStatus = {
    if (uncompressedSizes.length > 2000) {
      HighlyCompressedMapStatus(loc, uncompressedSizes)
    } else {
      new CompressedMapStatus(loc, uncompressedSizes)
    }
  }

  private[this] val LOG_BASE = 1.1

  /**
   * Compress a size in bytes to 8 bits for efficient reporting of map output sizes.
   * We do this by encoding the log base 1.1 of the size as an integer, which can support
   * sizes up to 35 GB with at most 10% error.
   */
  def compressSize(size: Long): Byte = {
    if (size == 0) {
      0
    } else if (size <= 1L) {
      1
    } else {
      math.min(255, math.ceil(math.log(size) / math.log(LOG_BASE)).toInt).toByte
    }
  }

  /**
   * Decompress an 8-bit encoded block size, using the reverse operation of compressSize.
   */
  def decompressSize(compressedSize: Byte): Long = {
    if (compressedSize == 0) {
      0
    } else {
      math.pow(LOG_BASE, compressedSize & 0xFF).toLong
    }
  }
}


/**
 * A [[MapStatus]] implementation that tracks the size of each block. Size for each block is
 * represented using a single byte.
 *
 * @param loc location where the task is being executed.
 * @param compressedSizes size of the blocks, indexed by reduce partition id.
 */
private[spark] class CompressedMapStatus(
    private[this] var loc: BlockManagerId,
    private[this] var compressedSizes: Array[Byte])
  extends MapStatus with Externalizable {

  protected def this() = this(null, null.asInstanceOf[Array[Byte]])  // For deserialization only

  def this(loc: BlockManagerId, uncompressedSizes: Array[Long]) {
    this(loc, uncompressedSizes.map(MapStatus.compressSize))
  }

  override def location: BlockManagerId = loc

  override def getSizeForBlock(reduceId: Int): Long = {
    MapStatus.decompressSize(compressedSizes(reduceId))
  }

  override def writeExternal(out: ObjectOutput): Unit = {
    loc.writeExternal(out)
    out.writeInt(compressedSizes.length)
    out.write(compressedSizes)
  }

  override def readExternal(in: ObjectInput): Unit = {
    loc = BlockManagerId(in)
    val len = in.readInt()
    compressedSizes = new Array[Byte](len)
    in.readFully(compressedSizes)
  }
}

/**
 * A [[MapStatus]] implementation that only stores the average size of non-empty blocks,
 * plus a bitmap for tracking which blocks are non-empty.
 *
 * @param loc location where the task is being executed
 * @param nonEmptyBlocks a bitmap tracking which blocks are non-empty
 * @param avgSize average size of the non-empty blocks
 */
private[spark] class HighlyCompressedMapStatus(
    private[this] var loc: BlockManagerId,
    private[this] var nonEmptyBlocks: RoaringBitmap,
    private[this] var avgSize: Long)
  extends MapStatus with Externalizable {

  require(loc == null || avgSize > 0 || nonEmptyBlocks.getCardinality == 0,
    "Average size can only be zero for map stages that produced no output")

  protected def this() = this(null, null, -1)  // For deserialization only

  override def location: BlockManagerId = loc

  override def getSizeForBlock(reduceId: Int): Long = {
    if (nonEmptyBlocks.contains(reduceId)) {
      avgSize
    } else {
      0
    }
  }

  override def writeExternal(out: ObjectOutput): Unit = {
    loc.writeExternal(out)
    nonEmptyBlocks.writeExternal(out)
    out.writeLong(avgSize)
  }

  override def readExternal(in: ObjectInput): Unit = {
    loc = BlockManagerId(in)
    nonEmptyBlocks = new RoaringBitmap()
    nonEmptyBlocks.readExternal(in)
    avgSize = in.readLong()
  }
}

object HighlyCompressedMapStatus {
  def apply(loc: BlockManagerId, uncompressedSizes: Array[Long]): HighlyCompressedMapStatus = {
    var i = 0
    var numNonEmptyBlocks: Long = 0
    var totalSize: Long = 0
    val nonEmptyBlocks = new RoaringBitmap()
    val totalNumBlocks = uncompressedSizes.length
    while (i < totalNumBlocks) {
      var size = uncompressedSizes(i)
      if (size > 0) {
        nonEmptyBlocks.add(i)
        numNonEmptyBlocks += 1
        totalSize += size
      }
      i += 1
    }
    val avgSize = if (numNonEmptyBlocks > 0) {
      totalSize / numNonEmptyBlocks
    } else {
      0
    }
    new HighlyCompressedMapStatus(loc, nonEmptyBlocks, avgSize)
  }
}
