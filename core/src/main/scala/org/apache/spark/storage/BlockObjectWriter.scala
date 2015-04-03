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

package org.apache.spark.storage

import java.io.{BufferedOutputStream, FileOutputStream, File, OutputStream}
import java.nio.channels.FileChannel

import org.apache.hadoop.mapreduce.security.TokenCache

import org.apache.spark.crypto.CommonConfigurationKeys._
import org.apache.spark.crypto.{CryptoOutputStream, CryptoCodec}
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.executor.ShuffleWriteMetrics
import org.apache.spark.serializer.{SerializationStream, Serializer}
import org.apache.spark.{Logging,SparkConf}
/**
 * An interface for writing JVM objects to some underlying storage. This interface allows
 * appending data to an existing block, and can guarantee atomicity in the case of faults
 * as it allows the caller to revert partial writes.
 *
 * This interface does not support concurrent writes. Also, once the writer has
 * been opened, it cannot be reopened again.
 */
private[spark] abstract class BlockObjectWriter(val blockId: BlockId) {

  def open(): BlockObjectWriter

  def close()

  def isOpen: Boolean

  /**
   * Flush the partial writes and commit them as a single atomic block.
   */
  def commitAndClose(): Unit

  /**
   * Reverts writes that haven't been flushed yet. Callers should invoke this function
   * when there are runtime exceptions. This method will not throw, though it may be
   * unsuccessful in truncating written data.
   */
  def revertPartialWritesAndClose()

  /**
   * Writes an object.
   */
  def write(value: Any)

  /**
   * Returns the file segment of committed data that this Writer has written.
   * This is only valid after commitAndClose() has been called.
   */
  def fileSegment(): FileSegment
}

/**
 * BlockObjectWriter which writes directly to a file on disk. Appends to the given file.
 */
private[spark] class DiskBlockObjectWriter(
    blockId: BlockId,
    file: File,
    serializer: Serializer,
    bufferSize: Int,
    compressStream: OutputStream => OutputStream,
    syncWrites: Boolean,
    // These write metrics concurrently shared with other active BlockObjectWriter's who
    // are themselves performing writes. All updates must be relative.
    writeMetrics: ShuffleWriteMetrics)
  extends BlockObjectWriter(blockId)
  with Logging
{
  /** Intercepts write calls and tracks total time spent writing. Not thread safe. */
  private class TimeTrackingOutputStream(out: OutputStream) extends OutputStream {
    override def write(i: Int): Unit = callWithTiming(out.write(i))
    override def write(b: Array[Byte]): Unit = callWithTiming(out.write(b))
    override def write(b: Array[Byte], off: Int, len: Int): Unit = {
      callWithTiming(out.write(b, off, len))
    }
    override def close(): Unit = out.close()
    override def flush(): Unit = out.flush()
  }

  /** The file channel, used for repositioning / truncating the file. */
  private var channel: FileChannel = null
  private var bs: OutputStream = null
  private var fos: FileOutputStream = null
  private var ts: TimeTrackingOutputStream = null
  private var objOut: SerializationStream = null
  private var initialized = false
  private var hasBeenClosed = false

  /**
   * Cursors used to represent positions in the file.
   *
   * xxxxxxxx|--------|---       |
   *         ^        ^          ^
   *         |        |        finalPosition
   *         |      reportedPosition
   *       initialPosition
   *
   * initialPosition: Offset in the file where we start writing. Immutable.
   * reportedPosition: Position at the time of the last update to the write metrics.
   * finalPosition: Offset where we stopped writing. Set on closeAndCommit() then never changed.
   * -----: Current writes to the underlying file.
   * xxxxx: Existing contents of the file.
   */
  private val initialPosition = file.length()
  private var finalPosition: Long = -1
  private var reportedPosition = initialPosition

  /**
   * Keep track of number of records written and also use this to periodically
   * output bytes written since the latter is expensive to do for each record.
   */
  private var numRecordsWritten = 0

  private var sparkConf:SparkConf = null

  override def open(): BlockObjectWriter = {
    if (hasBeenClosed) {
      throw new IllegalStateException("Writer already closed. Cannot be reopened.")
    }
    fos = new FileOutputStream(file, true)
    var isEncryptedShuffle: Boolean = if (sparkConf != null) {
      sparkConf.getBoolean("spark.encrypted.shuffle", false)
    }
    else {
      false
    }
    if (isEncryptedShuffle) {
      val cryptoCodec: CryptoCodec = CryptoCodec.getInstance(sparkConf)
      val bufferSize: Int = sparkConf.getInt(
        SPARK_ENCRYPTED_INTERMEDIATE_DATA_BUFFER_KB,
        DEFAULT_SPARK_ENCRYPTED_INTERMEDIATE_DATA_BUFFER_KB) * 1024
      val iv: Array[Byte] = createIV(cryptoCodec)
      val credentials = SparkHadoopUtil.get.getCurrentUserCredentials()
      var key: Array[Byte] = credentials.getSecretKey(SPARK_SHUFFLE_TOKEN)
      fos.write(iv)
      val cos = new CryptoOutputStream(fos, cryptoCodec,
        bufferSize, key, iv, iv.length)
      ts = new TimeTrackingOutputStream(cos)
    } else {
      ts = new TimeTrackingOutputStream(fos)
    }
    channel = fos.getChannel()
    bs = compressStream(new BufferedOutputStream(ts, bufferSize))
    objOut = serializer.newInstance().serializeStream(bs)
    initialized = true
    this
  }

  def createIV(cryptoCodec: CryptoCodec): Array[Byte] = {
    val iv: Array[Byte] = new Array[Byte](cryptoCodec.getCipherSuite.algoBlockSize)
    cryptoCodec.generateSecureRandom(iv)
    iv
  }

  def setSparkConf(sparkConfVal: SparkConf): DiskBlockObjectWriter = {
    sparkConf = sparkConfVal
    this
  }

  override def close() {
    if (initialized) {
      if (syncWrites) {
        // Force outstanding writes to disk and track how long it takes
        objOut.flush()
        callWithTiming {
          fos.getFD.sync()
        }
      }
      objOut.close()

      channel = null
      bs = null
      fos = null
      ts = null
      objOut = null
      initialized = false
      hasBeenClosed = true
    }
  }

  override def isOpen: Boolean = objOut != null

  override def commitAndClose(): Unit = {
    if (initialized) {
      // NOTE: Because Kryo doesn't flush the underlying stream we explicitly flush both the
      //       serializer stream and the lower level stream.
      objOut.flush()
      bs.flush()
      close()
    }
    finalPosition = file.length()
    // In certain compression codecs, more bytes are written after close() is called
    writeMetrics.incShuffleBytesWritten(finalPosition - reportedPosition)
  }

  // Discard current writes. We do this by flushing the outstanding writes and then
  // truncating the file to its initial position.
  override def revertPartialWritesAndClose() {
    try {
      writeMetrics.decShuffleBytesWritten(reportedPosition - initialPosition)
      writeMetrics.decShuffleRecordsWritten(numRecordsWritten)

      if (initialized) {
        objOut.flush()
        bs.flush()
        close()
      }

      val truncateStream = new FileOutputStream(file, true)
      try {
        truncateStream.getChannel.truncate(initialPosition)
      } finally {
        truncateStream.close()
      }
    } catch {
      case e: Exception =>
        logError("Uncaught exception while reverting partial writes to file " + file, e)
    }
  }

  override def write(value: Any) {
    if (!initialized) {
      open()
    }

    objOut.writeObject(value)
    numRecordsWritten += 1
    writeMetrics.incShuffleRecordsWritten(1)

    if (numRecordsWritten % 32 == 0) {
      updateBytesWritten()
    }
  }

  override def fileSegment(): FileSegment = {
    new FileSegment(file, initialPosition, finalPosition - initialPosition)
  }

  /**
   * Report the number of bytes written in this writer's shuffle write metrics.
   * Note that this is only valid before the underlying streams are closed.
   */
  private def updateBytesWritten() {
    val pos = channel.position()
    writeMetrics.incShuffleBytesWritten(pos - reportedPosition)
    reportedPosition = pos
  }

  private def callWithTiming(f: => Unit) = {
    val start = System.nanoTime()
    f
    writeMetrics.incShuffleWriteTime(System.nanoTime() - start)
  }

  // For testing
  private[spark] def flush() {
    objOut.flush()
    bs.flush()
  }
}
