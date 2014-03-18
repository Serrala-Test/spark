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

package org.apache.spark.sql
package columnar

import java.nio.ByteBuffer

import org.apache.spark.sql.catalyst.types._

/**
 * An abstract class that represents type of a column. Used to append/extract Java objects into/from
 * the underlying [[ByteBuffer]] of a column.
 *
 * @param typeId A unique ID representing the type.
 * @param defaultSize Default size in bytes for one element of type T (e.g. 4 for `Int`).
 * @tparam T Scala data type for the column.
 * @tparam JvmType Underlying Java type to represent the elements.
 */
sealed abstract class ColumnType[T <: DataType, JvmType](
    val typeId: Int,
    val defaultSize: Int) {

  /**
   * Extracts a value out of the buffer at the buffer's current position.
   */
  def extract(buffer: ByteBuffer): JvmType

  /**
   * Appends the given value v of type T into the given ByteBuffer.
   */
  def append(v: JvmType, buffer: ByteBuffer)

  /**
   * Returns the size of the value. This is used to calculate the size of variable length types
   * such as byte arrays and strings.
   */
  def actualSize(v: JvmType): Int = defaultSize

  /**
   * Creates a duplicated copy of the value.
   */
  def clone(v: JvmType): JvmType = v
}

private[columnar] abstract class NativeColumnType[T <: NativeType](
    val dataType: T,
    typeId: Int,
    defaultSize: Int)
  extends ColumnType[T, T#JvmType](typeId, defaultSize) {

  /**
   * Scala TypeTag. Can be used to create primitive arrays and hash tables.
   */
  def scalaTag = dataType.tag
}

object INT extends NativeColumnType(IntegerType, 0, 4) {
  def append(v: Int, buffer: ByteBuffer) {
    buffer.putInt(v)
  }

  def extract(buffer: ByteBuffer) = {
    buffer.getInt()
  }
}

object LONG extends NativeColumnType(LongType, 1, 8) {
  override def append(v: Long, buffer: ByteBuffer) {
    buffer.putLong(v)
  }

  override def extract(buffer: ByteBuffer) = {
    buffer.getLong()
  }
}

object FLOAT extends NativeColumnType(FloatType, 2, 4) {
  override def append(v: Float, buffer: ByteBuffer) {
    buffer.putFloat(v)
  }

  override def extract(buffer: ByteBuffer) = {
    buffer.getFloat()
  }
}

object DOUBLE extends NativeColumnType(DoubleType, 3, 8) {
  override def append(v: Double, buffer: ByteBuffer) {
    buffer.putDouble(v)
  }

  override def extract(buffer: ByteBuffer) = {
    buffer.getDouble()
  }
}

object BOOLEAN extends NativeColumnType(BooleanType, 4, 1) {
  override def append(v: Boolean, buffer: ByteBuffer) {
    buffer.put(if (v) 1.toByte else 0.toByte)
  }

  override def extract(buffer: ByteBuffer) = {
    if (buffer.get() == 1) true else false
  }
}

object BYTE extends NativeColumnType(ByteType, 5, 1) {
  override def append(v: Byte, buffer: ByteBuffer) {
    buffer.put(v)
  }

  override def extract(buffer: ByteBuffer) = {
    buffer.get()
  }
}

object SHORT extends NativeColumnType(ShortType, 6, 2) {
  override def append(v: Short, buffer: ByteBuffer) {
    buffer.putShort(v)
  }

  override def extract(buffer: ByteBuffer) = {
    buffer.getShort()
  }
}

object STRING extends NativeColumnType(StringType, 7, 8) {
  override def actualSize(v: String): Int = v.getBytes.length + 4

  override def append(v: String, buffer: ByteBuffer) {
    val stringBytes = v.getBytes()
    buffer.putInt(stringBytes.length).put(stringBytes, 0, stringBytes.length)
  }

  override def extract(buffer: ByteBuffer) = {
    val length = buffer.getInt()
    val stringBytes = new Array[Byte](length)
    buffer.get(stringBytes, 0, length)
    new String(stringBytes)
  }
}

object BINARY extends ColumnType[BinaryType.type, Array[Byte]](8, 16) {
  override def actualSize(v: Array[Byte]) = v.length + 4

  override def append(v: Array[Byte], buffer: ByteBuffer) {
    buffer.putInt(v.length).put(v, 0, v.length)
  }

  override def extract(buffer: ByteBuffer) = {
    val length = buffer.getInt()
    val bytes = new Array[Byte](length)
    buffer.get(bytes, 0, length)
    bytes
  }
}

// Used process generic objects (all types other than those listed above). Objects should be
// serialized first before appending to the column `ByteBuffer`, and is also extracted as serialized
// byte array.
object GENERIC extends ColumnType[DataType, Array[Byte]](9, 16) {
  override def actualSize(v: Array[Byte]) = v.length + 4

  override def append(v: Array[Byte], buffer: ByteBuffer) {
    buffer.putInt(v.length).put(v)
  }

  override def extract(buffer: ByteBuffer) = {
    val length = buffer.getInt()
    val bytes = new Array[Byte](length)
    buffer.get(bytes, 0, length)
    bytes
  }
}
