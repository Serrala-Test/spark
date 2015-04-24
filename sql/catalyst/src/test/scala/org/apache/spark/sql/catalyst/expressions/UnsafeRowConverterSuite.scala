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

package org.apache.spark.sql.catalyst.expressions

import java.util.Arrays

import org.scalatest.{FunSuite, Matchers}

import org.apache.spark.sql.types._
import org.apache.spark.unsafe.PlatformDependent
import org.apache.spark.unsafe.array.ByteArrayMethods

class UnsafeRowConverterSuite extends FunSuite with Matchers {

  test("basic conversion with only primitive types") {
    val fieldTypes: Array[DataType] = Array(LongType, LongType, IntegerType)
    val converter = new UnsafeRowConverter(fieldTypes)

    val row = new SpecificMutableRow(fieldTypes)
    row.setLong(0, 0)
    row.setLong(1, 1)
    row.setInt(2, 2)

    val sizeRequired: Int = converter.getSizeRequirement(row)
    sizeRequired should be (8 + (3 * 8))
    val buffer: Array[Long] = new Array[Long](sizeRequired / 8)
    val numBytesWritten = converter.writeRow(row, buffer, PlatformDependent.LONG_ARRAY_OFFSET)
    numBytesWritten should be (sizeRequired)

    val unsafeRow = new UnsafeRow()
    unsafeRow.pointTo(buffer, PlatformDependent.LONG_ARRAY_OFFSET, fieldTypes.length, null)
    unsafeRow.getLong(0) should be (0)
    unsafeRow.getLong(1) should be (1)
    unsafeRow.getInt(2) should be (2)
  }

  test("basic conversion with primitive and string types") {
    val fieldTypes: Array[DataType] = Array(LongType, StringType, StringType)
    val converter = new UnsafeRowConverter(fieldTypes)

    val row = new SpecificMutableRow(fieldTypes)
    row.setLong(0, 0)
    row.setString(1, "Hello")
    row.setString(2, "World")

    val sizeRequired: Int = converter.getSizeRequirement(row)
    sizeRequired should be (8 + (8 * 3) +
      ByteArrayMethods.roundNumberOfBytesToNearestWord("Hello".getBytes.length + 8) +
      ByteArrayMethods.roundNumberOfBytesToNearestWord("World".getBytes.length + 8))
    val buffer: Array[Long] = new Array[Long](sizeRequired / 8)
    val numBytesWritten = converter.writeRow(row, buffer, PlatformDependent.LONG_ARRAY_OFFSET)
    numBytesWritten should be (sizeRequired)

    val unsafeRow = new UnsafeRow()
    unsafeRow.pointTo(buffer, PlatformDependent.LONG_ARRAY_OFFSET, fieldTypes.length, null)
    unsafeRow.getLong(0) should be (0)
    unsafeRow.getString(1) should be ("Hello")
    unsafeRow.getString(2) should be ("World")
  }

  test("null handling") {
    val fieldTypes: Array[DataType] = Array(IntegerType, LongType, FloatType, DoubleType)
    val converter = new UnsafeRowConverter(fieldTypes)

    val rowWithAllNullColumns: Row = {
      val r = new SpecificMutableRow(fieldTypes)
      for (i <- 0 to 3) {
        r.setNullAt(i)
      }
      r
    }

    val sizeRequired: Int = converter.getSizeRequirement(rowWithAllNullColumns)
    val createdFromNullBuffer: Array[Long] = new Array[Long](sizeRequired / 8)
    val numBytesWritten = converter.writeRow(
      rowWithAllNullColumns, createdFromNullBuffer, PlatformDependent.LONG_ARRAY_OFFSET)
    numBytesWritten should be (sizeRequired)

    val createdFromNull = new UnsafeRow()
    createdFromNull.pointTo(
      createdFromNullBuffer, PlatformDependent.LONG_ARRAY_OFFSET, fieldTypes.length, null)
    for (i <- 0 to 3) {
      assert(createdFromNull.isNullAt(i))
    }
    createdFromNull.getInt(0) should be (0)
    createdFromNull.getLong(1) should be (0)
    assert(java.lang.Float.isNaN(createdFromNull.getFloat(2)))
    assert(java.lang.Double.isNaN(createdFromNull.getFloat(3)))

    // If we have an UnsafeRow with columns that are initially non-null and we null out those
    // columns, then the serialized row representation should be identical to what we would get by
    // creating an entirely null row via the converter
    val rowWithNoNullColumns: Row = {
      val r = new SpecificMutableRow(fieldTypes)
      r.setInt(0, 100)
      r.setLong(1, 200)
      r.setFloat(2, 300)
      r.setDouble(3, 400)
      r
    }
    val setToNullAfterCreationBuffer: Array[Long] = new Array[Long](sizeRequired / 8)
    converter.writeRow(
      rowWithNoNullColumns, setToNullAfterCreationBuffer, PlatformDependent.LONG_ARRAY_OFFSET)
    val setToNullAfterCreation = new UnsafeRow()
    setToNullAfterCreation.pointTo(
      setToNullAfterCreationBuffer, PlatformDependent.LONG_ARRAY_OFFSET, fieldTypes.length, null)
    setToNullAfterCreation.getInt(0) should be (rowWithNoNullColumns.getInt(0))
    setToNullAfterCreation.getLong(1) should be (rowWithNoNullColumns.getLong(1))
    setToNullAfterCreation.getFloat(2) should be (rowWithNoNullColumns.getFloat(2))
    setToNullAfterCreation.getDouble(3) should be (rowWithNoNullColumns.getDouble(3))

    for (i <- 0 to 3) {
      setToNullAfterCreation.setNullAt(i)
    }
    assert(Arrays.equals(createdFromNullBuffer, setToNullAfterCreationBuffer))
  }

}
