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

package org.apache.spark.sql.execution

import scala.collection.mutable.ArrayBuffer

import org.apache.spark.{SparkConf, SparkContext, SparkEnv, TaskContext}
import org.apache.spark.benchmark.{Benchmark, BenchmarkBase}
import org.apache.spark.internal.config
import org.apache.spark.memory.MemoryTestingUtils
import org.apache.spark.sql.catalyst.expressions.UnsafeRow
import org.apache.spark.util.collection.unsafe.sort.UnsafeExternalSorter

/**
  * Synthetic Benchmark for ArrayBuffer and UnsafeRowArray
  * To run this benchmark:
  * {{{
  *   1. without sbt:
  *      bin/spark-submit --class <this class> --jars <spark core test jar> <spark catalyst test jar>
  *   2. build/sbt "catalyst/test:runMain <this class>"
  *   3. generate result:
  *      SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt "catalyst/test:runMain <this class>"
  *      Results will be written to
  *      "benchmarks/ExternalAppendOnlyUnsafeRowArrayBenchmark-results.txt".
  * }}}
  */

object ExternalAppendOnlyUnsafeRowArrayBenchmark extends BenchmarkBase {

  def testAgainstRawArrayBuffer(numSpillThreshold: Int, numRows: Int, iterations: Int): Unit = {
    val random = new java.util.Random()
    val rows = (1 to numRows).map(_ => {
      val row = new UnsafeRow(1)
      row.pointTo(new Array[Byte](64), 16)
      row.setLong(0, random.nextLong())
      row
    })

    val benchmark =
      new Benchmark(s"Array with $numRows rows", iterations * numRows, output = output)

    // Internally, `ExternalAppendOnlyUnsafeRowArray` will create an
    // in-memory buffer of size `numSpillThreshold`. This will mimic that
    val initialSize =
      Math.min(
        ExternalAppendOnlyUnsafeRowArray.DefaultInitialSizeOfInMemoryBuffer,
        numSpillThreshold)

    benchmark.addCase("ArrayBuffer") { _: Int =>
      var sum = 0L
      for (_ <- 0L until iterations) {
        val array = new ArrayBuffer[UnsafeRow](initialSize)

        // Internally, `ExternalAppendOnlyUnsafeRowArray` will create a
        // copy of the row. This will mimic that
        rows.foreach(x => array += x.copy())

        var i = 0
        val n = array.length
        while (i < n) {
          sum = sum + array(i).getLong(0)
          i += 1
        }
        array.clear()
      }
    }

    benchmark.addCase("ExternalAppendOnlyUnsafeRowArray") { _: Int =>
      var sum = 0L
      for (_ <- 0L until iterations) {
        val array = new ExternalAppendOnlyUnsafeRowArray(
          ExternalAppendOnlyUnsafeRowArray.DefaultInitialSizeOfInMemoryBuffer,
          numSpillThreshold)

        rows.foreach(x => array.add(x))

        val iterator = array.generateIterator()
        while (iterator.hasNext) {
          sum = sum + iterator.next().getLong(0)
        }
        array.clear()
      }
    }

    val conf = new SparkConf(false)
    // Make the Java serializer write a reset instruction (TC_RESET) after each object to test
    // for a bug we had with bytes written past the last object in a batch (SPARK-2792)
    conf.set("spark.serializer.objectStreamReset", "1")
    conf.set("spark.serializer", "org.apache.spark.serializer.JavaSerializer")

    val sc = new SparkContext("local", "test", conf)
    val taskContext = MemoryTestingUtils.fakeTaskContext(SparkEnv.get)
    TaskContext.setTaskContext(taskContext)
    benchmark.run()
    sc.stop()
  }

  def testAgainstRawUnsafeExternalSorter(
      numSpillThreshold: Int,
      numRows: Int,
      iterations: Int): Unit = {

    val random = new java.util.Random()
    val rows = (1 to numRows).map(_ => {
      val row = new UnsafeRow(1)
      row.pointTo(new Array[Byte](64), 16)
      row.setLong(0, random.nextLong())
      row
    })

    val benchmark =
      new Benchmark(s"Spilling with $numRows rows", iterations * numRows, output = output)

    benchmark.addCase("UnsafeExternalSorter") { _: Int =>
      var sum = 0L
      for (_ <- 0L until iterations) {
        val array = UnsafeExternalSorter.create(
          TaskContext.get().taskMemoryManager(),
          SparkEnv.get.blockManager,
          SparkEnv.get.serializerManager,
          TaskContext.get(),
          null,
          null,
          1024,
          SparkEnv.get.memoryManager.pageSizeBytes,
          numSpillThreshold,
          false)

        rows.foreach(x =>
          array.insertRecord(
            x.getBaseObject,
            x.getBaseOffset,
            x.getSizeInBytes,
            0,
            false))

        val unsafeRow = new UnsafeRow(1)
        val iter = array.getIterator(0)
        while (iter.hasNext) {
          iter.loadNext()
          unsafeRow.pointTo(iter.getBaseObject, iter.getBaseOffset, iter.getRecordLength)
          sum = sum + unsafeRow.getLong(0)
        }
        array.cleanupResources()
      }
    }

    benchmark.addCase("ExternalAppendOnlyUnsafeRowArray") { _: Int =>
      var sum = 0L
      for (_ <- 0L until iterations) {
        val array = new ExternalAppendOnlyUnsafeRowArray(numSpillThreshold, numSpillThreshold)
        rows.foreach(x => array.add(x))

        val iterator = array.generateIterator()
        while (iterator.hasNext) {
          sum = sum + iterator.next().getLong(0)
        }
        array.clear()
      }
    }

    val conf = new SparkConf(false)
    // Make the Java serializer write a reset instruction (TC_RESET) after each object to test
    // for a bug we had with bytes written past the last object in a batch (SPARK-2792)
    conf.set("spark.serializer.objectStreamReset", "1")
    conf.set("spark.serializer", "org.apache.spark.serializer.JavaSerializer")

    val sc = new SparkContext("local", "test", conf)
    val taskContext = MemoryTestingUtils.fakeTaskContext(SparkEnv.get)
    TaskContext.setTaskContext(taskContext)
    benchmark.run()
    sc.stop()
  }

  override def runBenchmarkSuite(): Unit = {
    runBenchmark("Benchmark for ArrayBuffer and UnsafeRowArray") {
      val spillThreshold = 100 * 1000
      testAgainstRawArrayBuffer(spillThreshold, 1000, 1 << 18)
      testAgainstRawArrayBuffer(spillThreshold, 30 * 1000, 1 << 14)
      testAgainstRawArrayBuffer(spillThreshold, 100 * 1000, 1 << 10)
      testAgainstRawUnsafeExternalSorter(100 * 1000, 1000, 1 << 18)
      testAgainstRawUnsafeExternalSorter(
        config.SHUFFLE_SPILL_NUM_ELEMENTS_FORCE_SPILL_THRESHOLD.defaultValue.get, 10 * 1000, 1 << 4)
    }
  }
}
