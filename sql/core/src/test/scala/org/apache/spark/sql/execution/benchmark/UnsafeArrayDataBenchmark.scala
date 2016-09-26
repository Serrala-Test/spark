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

package org.apache.spark.sql.execution.benchmark

import org.apache.spark.SparkConf
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.catalyst.expressions.{UnsafeArrayData, UnsafeRow}
import org.apache.spark.sql.catalyst.expressions.codegen.{BufferHolder, UnsafeArrayWriter}
import org.apache.spark.unsafe.Platform
import org.apache.spark.util.Benchmark

/**
 * Benchmark [[UnsafeArrayDataBenchmark]] for UnsafeArrayData
 * To run this:
 *  build/sbt "sql/test-only *benchmark.UnsafeArrayDataBenchmark"
 *
 * Benchmarks in this file are skipped in normal builds.
 */
class UnsafeArrayDataBenchmark extends BenchmarkBase {

  def calculateHeaderPortionInBytes(count: Int) : Int = {
    // Use this assignment for SPARK-15962
    // val size = 4 + 4 * count
    val size = UnsafeArrayData.calculateHeaderPortionInBytes(count)
    size
  }

  def readUnsafeArray(iters: Int): Unit = {
    val count = 1024 * 1024 * 16

    var intResult: Int = 0
    val intBuffer = new Array[Int](count)
    val intEncoder = ExpressionEncoder[Array[Int]].resolveAndBind()
    val intInternalRow = intEncoder.toRow(intBuffer)
    val intUnsafeArray = intInternalRow.getArray(0)
    val readIntArray = { i: Int =>
      var n = 0
      while (n < iters) {
        val len = intUnsafeArray.numElements
        var sum = 0.toInt
        var i = 0
        while (i < len) {
          sum += intUnsafeArray.getInt(i)
          i += 1
        }
        intResult = sum
        n += 1
      }
    }

    var doubleResult: Double = 0
    val doubleBuffer = new Array[Double](count)
    val doubleEncoder = ExpressionEncoder[Array[Double]].resolveAndBind()
    val doubleInternalRow = doubleEncoder.toRow(doubleBuffer)
    val doubleUnsafeArray = doubleInternalRow.getArray(0)
    val readDoubleArray = { i: Int =>
      var n = 0
      while (n < iters) {
        val len = doubleUnsafeArray.numElements
        var sum = 0.toDouble
        var i = 0
        while (i < len) {
          sum += doubleUnsafeArray.getDouble(i)
          i += 1
        }
        doubleResult = sum
        n += 1
      }
    }

    val benchmark = new Benchmark("Read UnsafeArrayData", count * iters)
    benchmark.addCase("Int")(readIntArray)
    benchmark.addCase("Double")(readDoubleArray)
    benchmark.run
    /*
    OpenJDK 64-Bit Server VM 1.8.0_91-b14 on Linux 4.0.4-301.fc22.x86_64
    Intel Xeon E3-12xx v2 (Ivy Bridge)
    Read UnsafeArrayData:                    Best/Avg Time(ms)    Rate(M/s)   Per Row(ns)   Relative
    ------------------------------------------------------------------------------------------------
    Int                                            246 /  259        682.8           1.5       1.0X
    Double                                         259 /  265        648.4           1.5       0.9X
    */
  }

  def writeUnsafeArray(iters: Int): Unit = {
    val count = 1024 * 1024 * 16

    val intUnsafeRow = new UnsafeRow(1)
    val intUnsafeArrayWriter = new UnsafeArrayWriter
    val intBufferHolder = new BufferHolder(intUnsafeRow, 64)
    intBufferHolder.reset()
    intUnsafeArrayWriter.initialize(intBufferHolder, count, 4)
    val intCursor = intBufferHolder.cursor
    val writeIntArray = { i: Int =>
      var n = 0
      while (n < iters) {
        intBufferHolder.cursor = intCursor
        val len = count
        var i = 0
        while (i < len) {
          intUnsafeArrayWriter.write(i, 0.toInt)
          i += 1
        }
        n += 1
      }
    }

    val doubleUnsafeRow = new UnsafeRow(1)
    val doubleUnsafeArrayWriter = new UnsafeArrayWriter
    val doubleBufferHolder = new BufferHolder(doubleUnsafeRow, 64)
    doubleBufferHolder.reset()
    doubleUnsafeArrayWriter.initialize(doubleBufferHolder, count, 8)
    val doubleCursor = doubleBufferHolder.cursor
    val writeDoubleArray = { i: Int =>
      var n = 0
      while (n < iters) {
        doubleBufferHolder.cursor = doubleCursor
        val len = count
        var i = 0
        while (i < len) {
          doubleUnsafeArrayWriter.write(i, 0.toDouble)
          i += 1
        }
        n += 1
      }
    }

    val benchmark = new Benchmark("Write UnsafeArrayData", count * iters)
    benchmark.addCase("Int")(writeIntArray)
    benchmark.addCase("Double")(writeDoubleArray)
    benchmark.run
    /*
    OpenJDK 64-Bit Server VM 1.8.0_91-b14 on Linux 4.0.4-301.fc22.x86_64
    Intel Xeon E3-12xx v2 (Ivy Bridge)
    Write UnsafeArrayData:                   Best/Avg Time(ms)    Rate(M/s)   Per Row(ns)   Relative
    ------------------------------------------------------------------------------------------------
    Int                                             84 /   85       2005.4           0.5       1.0X
    Double                                         152 /  154       1102.5           0.9       0.5X
    */
  }

  def getPrimitiveArray(iters: Int): Unit = {
    val count = 1024 * 1024 * 12

    val intUnsafeArray = new UnsafeArrayData
    val intSize = calculateHeaderPortionInBytes(count) + 4 * count
    val intBuffer = new Array[Byte](intSize)
    Platform.putInt(intBuffer, Platform.BYTE_ARRAY_OFFSET, count)
    intUnsafeArray.pointTo(intBuffer, Platform.BYTE_ARRAY_OFFSET, intSize)
    var intPrimitiveArray: Array[Int] = null
    val readIntArray = { i: Int =>
      var n = 0
      while (n < iters) {
        intPrimitiveArray = intUnsafeArray.toIntArray
        n += 1
      }
    }

    val doubleUnsafeArray = new UnsafeArrayData
    val doubleSize = calculateHeaderPortionInBytes(count) + 8 * count
    val doubleBuffer = new Array[Byte](doubleSize)
    Platform.putInt(doubleBuffer, Platform.BYTE_ARRAY_OFFSET, count)
    doubleUnsafeArray.pointTo(doubleBuffer, Platform.BYTE_ARRAY_OFFSET, doubleSize)
    var doublePrimitiveArray: Array[Double] = null
    val readDoubleArray = { i: Int =>
      var n = 0
      while (n < iters) {
        doublePrimitiveArray = doubleUnsafeArray.toDoubleArray
        n += 1
      }
    }

    val benchmark = new Benchmark("Get primitive array from UnsafeArrayData", count * iters)
    benchmark.addCase("Int")(readIntArray)
    benchmark.addCase("Double")(readDoubleArray)
    benchmark.run
    /*
    OpenJDK 64-Bit Server VM 1.8.0_91-b14 on Linux 4.0.4-301.fc22.x86_64
    Intel Xeon E3-12xx v2 (Ivy Bridge)
    Get primitive array from UnsafeArrayData: Best/Avg Time(ms)    Rate(M/s)   Per Row(ns)  Relative
    ------------------------------------------------------------------------------------------------
    Int                                             86 /   96        734.2           1.4       1.0X
    Double                                         203 /  268        310.6           3.2       0.4X
    */
  }

  def putPrimitiveArray(iters: Int): Unit = {
    val count = 1024 * 1024 * 12

    val intPrimitiveArray: Array[Int] = new Array[Int](count)
    var intUnsafeArray: UnsafeArrayData = null
    val createIntArray = { i: Int =>
      var n = 0
      while (n < iters) {
        intUnsafeArray = UnsafeArrayData.fromPrimitiveArray(intPrimitiveArray)
        n += 1
      }
    }

    val doublePrimitiveArray: Array[Double] = new Array[Double](count)
    var doubleUnsafeArray: UnsafeArrayData = null
    val createDoubleArray = { i: Int =>
      var n = 0
      while (n < iters) {
        doubleUnsafeArray = UnsafeArrayData.fromPrimitiveArray(doublePrimitiveArray)
        n += 1
      }
    }

    val benchmark = new Benchmark("Create UnsafeArrayData from primitive array", count * iters)
    benchmark.addCase("Int")(createIntArray)
    benchmark.addCase("Double")(createDoubleArray)
    benchmark.run
    /*
    OpenJDK 64-Bit Server VM 1.8.0_91-b14 on Linux 4.0.4-301.fc22.x86_64
    Intel Xeon E3-12xx v2 (Ivy Bridge)
    Create UnsafeArrayData from primitive array: Best/Avg Time(ms)   Rate(M/s)  Per Row(ns) Relative
    ------------------------------------------------------------------------------------------------
    Int                                             84 /   95        748.3           1.3       1.0X
    Double                                         196 /  227        320.9           3.1       0.4X
    */
  }

  ignore("Benchmark UnsafeArrayData") {
    readUnsafeArray(10)
    writeUnsafeArray(10)
    getPrimitiveArray(5)
    putPrimitiveArray(5)
  }

  def main(args: Array[String]): Unit = {
    readUnsafeArray(10)
    writeUnsafeArray(10)
    getPrimitiveArray(5)
    putPrimitiveArray(5)
  }
}
