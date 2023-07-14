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
package org.apache.spark.sql.connect.client

import scala.collection.mutable

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.ipc.message.{ArrowMessage, ArrowRecordBatch}
import org.apache.arrow.vector.types.pojo

import org.apache.spark.connect.proto
import org.apache.spark.sql.catalyst.encoders.{AgnosticEncoder, RowEncoder}
import org.apache.spark.sql.catalyst.encoders.AgnosticEncoders.{ProductEncoder, UnboundRowEncoder}
import org.apache.spark.sql.connect.client.arrow.{AbstractMessageIterator, ArrowDeserializingIterator, ConcatenatingArrowStreamReader, MessageIterator}
import org.apache.spark.sql.connect.client.util.Cleanable
import org.apache.spark.sql.connect.common.DataTypeProtoConverter
import org.apache.spark.sql.types.{DataType, StructType}
import org.apache.spark.sql.util.ArrowUtils

private[sql] class SparkResult[T](
    responses: java.util.Iterator[proto.ExecutePlanResponse],
    allocator: BufferAllocator,
    encoder: AgnosticEncoder[T])
    extends AutoCloseable
    with Cleanable { self =>

  private[this] var numRecords: Int = 0
  private[this] var structType: StructType = _
  private[this] var arrowSchema: pojo.Schema = _
  private[this] var nextResultIndex: Int = 0
  private val resultMap = mutable.Map.empty[Int, (Long, Seq[ArrowMessage])]

  /**
   * Update RowEncoder and recursively update the fields of the ProductEncoder if found.
   */
  private def createEncoder[E](
      enc: AgnosticEncoder[E],
      dataType: DataType): AgnosticEncoder[E] = {
    enc match {
      case UnboundRowEncoder =>
        // Replace the row encoder with the encoder inferred from the schema.
        RowEncoder
          .encoderFor(dataType.asInstanceOf[StructType])
          .asInstanceOf[AgnosticEncoder[E]]
      case ProductEncoder(clsTag, fields) if ProductEncoder.isTuple(clsTag) =>
        // Recursively continue updating the tuple product encoder
        val schema = dataType.asInstanceOf[StructType]
        assert(fields.length <= schema.fields.length)
        val updatedFields = fields.zipWithIndex.map { case (f, id) =>
          f.copy(enc = createEncoder(f.enc, schema.fields(id).dataType))
        }
        ProductEncoder(clsTag, updatedFields)
      case _ =>
        enc
    }
  }

  private def processResponses(stopOnFirstNonEmptyResponse: Boolean): Boolean = {
    while (responses.hasNext) {
      val response = responses.next()
      if (response.hasSchema) {
        // The original schema should arrive before ArrowBatches.
        structType =
          DataTypeProtoConverter.toCatalystType(response.getSchema).asInstanceOf[StructType]
      }
      if (response.hasArrowBatch) {
        val ipcStreamBytes = response.getArrowBatch.getData
        val reader = new MessageIterator(ipcStreamBytes.newInput(), allocator)
        if (arrowSchema == null) {
          arrowSchema = reader.schema
        } else if (arrowSchema != reader.schema) {
          // Uh oh...
        }
        if (structType == null) {
          // If the schema is not available yet, fallback to the arrow schema.
          structType = ArrowUtils.fromArrowSchema(reader.schema)
        }
        var numRecordsInBatch = 0
        val messages = Seq.newBuilder[ArrowMessage]
        while (reader.hasNext) {
          val message = reader.next()
          message match {
            case batch: ArrowRecordBatch =>
              numRecordsInBatch += batch.getLength
            case _ =>
          }
          messages += message
        }
        numRecords += numRecordsInBatch
        resultMap.put(nextResultIndex, (reader.bytesRead, messages.result()))
        nextResultIndex += 1
        if (stopOnFirstNonEmptyResponse && numRecordsInBatch > 0) {
          return true
        }
      } else {
        throw new UnsupportedOperationException(s"Unsupported response: $response")
      }
    }
    false
  }

  /**
   * Returns the number of elements in the result.
   */
  def length: Int = {
    // We need to process all responses to make sure numRecords is correct.
    processResponses(stopOnFirstNonEmptyResponse = false)
    numRecords
  }

  /**
   * @return
   *   the schema of the result.
   */
  def schema: StructType = {
    processResponses(stopOnFirstNonEmptyResponse = true)
    structType
  }

  /**
   * Create an Array with the contents of the result.
   */
  def toArray: Array[T] = {
    val result = encoder.clsTag.newArray(length)
    val rows = iterator
    var i = 0
    while (rows.hasNext) {
      result(i) = rows.next()
      assert(i < numRecords)
      i += 1
    }
    result
  }

  /**
   * Returns an iterator over the contents of the result.
   */
  def iterator: java.util.Iterator[T] with AutoCloseable =
    buildIterator(destructive = false)

  /**
   * Returns an destructive iterator over the contents of the result.
   */
  def destructiveIterator: java.util.Iterator[T] with AutoCloseable =
    buildIterator(destructive = true)

  private def buildIterator(destructive: Boolean): java.util.Iterator[T] with AutoCloseable = {
    if (schema == null) {
      processResponses(true)
    }
    val iterator = new ArrowDeserializingIterator(
      createEncoder(encoder, structType),
      new ConcatenatingArrowStreamReader(
        allocator,
        Iterator.single(new ResultMessageIterator(destructive)),
        destructive))
    new java.util.Iterator[T] with AutoCloseable {
      override def hasNext: Boolean = iterator.hasNext
      override def next(): T = iterator.next()
      override def close(): Unit = iterator.close()
    }
  }

  /**
   * Close this result, freeing any underlying resources.
   */
  override def close(): Unit = cleaner.close()

  override val cleaner: AutoCloseable = new SparkResultCloseable(resultMap)

  private class ResultMessageIterator(destructive: Boolean) extends AbstractMessageIterator {
    private[this] var totalBytesRead = 0L
    private[this] var nextResultIndex = 0
    private[this] var iterator: Iterator[ArrowMessage] = Iterator.empty

    override def schema: pojo.Schema = arrowSchema
    override def bytesRead: Long = totalBytesRead
    override def hasNext: Boolean = {
      if (iterator.hasNext) {
        return true
      }
      val hasNextResult = if (!resultMap.contains(nextResultIndex)) {
        self.processResponses(true)
      } else {
        true
      }
      if (hasNextResult) {
        val Some((sizeInBytes, messages)) = if (destructive) {
          resultMap.remove(nextResultIndex)
        } else {
          resultMap.get(nextResultIndex)
        }
        totalBytesRead += sizeInBytes
        iterator = messages.iterator
        nextResultIndex += 1
      }
      hasNextResult
    }

    override def next(): ArrowMessage = {
      if (!hasNext) {
        throw new NoSuchElementException()
      }
      iterator.next()
    }
  }
}

private[client] class SparkResultCloseable(resultMap: mutable.Map[Int, (Long, Seq[ArrowMessage])])
    extends AutoCloseable {
  override def close(): Unit = resultMap.values.foreach(_._2.foreach(_.close()))
}
