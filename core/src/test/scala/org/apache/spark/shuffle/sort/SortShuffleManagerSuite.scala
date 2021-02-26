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

package org.apache.spark.shuffle.sort

import java.io.File

import scala.collection.JavaConverters._

import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.TrueFileFilter
import org.mockito.Mockito.{mock, when}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.matchers.must.Matchers
import org.scalatest.matchers.should.Matchers._

import org.apache.spark._
import org.apache.spark.rdd.ShuffledRDD
import org.apache.spark.serializer.{JavaSerializer, KryoSerializer, Serializer}

/**
 * Tests for the fallback logic in UnsafeShuffleManager. Actual tests of shuffling data are
 * performed in other suites.
 */
class SortShuffleManagerSuite extends SparkFunSuite with Matchers {

  private def doReturn(value: Any) = org.mockito.Mockito.doReturn(value, Seq.empty: _*)

  import SortShuffleManager.canUseSerializedShuffle

  private class RuntimeExceptionAnswer extends Answer[Object] {
    override def answer(invocation: InvocationOnMock): Object = {
      throw new RuntimeException("Called non-stubbed method, " + invocation.getMethod.getName)
    }
  }

  private def shuffleDep(
      partitioner: Partitioner,
      serializer: Serializer,
      keyOrdering: Option[Ordering[Any]],
      aggregator: Option[Aggregator[Any, Any, Any]],
      mapSideCombine: Boolean): ShuffleDependency[Any, Any, Any] = {
    val dep = mock(classOf[ShuffleDependency[Any, Any, Any]], new RuntimeExceptionAnswer())
    doReturn(0).when(dep).shuffleId
    doReturn(partitioner).when(dep).partitioner
    doReturn(serializer).when(dep).serializer
    doReturn(keyOrdering).when(dep).keyOrdering
    doReturn(aggregator).when(dep).aggregator
    doReturn(mapSideCombine).when(dep).mapSideCombine
    dep
  }

  test("supported shuffle dependencies for serialized shuffle") {
    val kryo = new KryoSerializer(new SparkConf())

    assert(canUseSerializedShuffle(shuffleDep(
      partitioner = new HashPartitioner(2),
      serializer = kryo,
      keyOrdering = None,
      aggregator = None,
      mapSideCombine = false
    )))

    val rangePartitioner = mock(classOf[RangePartitioner[Any, Any]])
    when(rangePartitioner.numPartitions).thenReturn(2)
    assert(canUseSerializedShuffle(shuffleDep(
      partitioner = rangePartitioner,
      serializer = kryo,
      keyOrdering = None,
      aggregator = None,
      mapSideCombine = false
    )))

    // Shuffles with key orderings are supported as long as no aggregator is specified
    assert(canUseSerializedShuffle(shuffleDep(
      partitioner = new HashPartitioner(2),
      serializer = kryo,
      keyOrdering = Some(mock(classOf[Ordering[Any]])),
      aggregator = None,
      mapSideCombine = false
    )))

    // We support serialized shuffle if we do not need to do map-side aggregation
    assert(canUseSerializedShuffle(shuffleDep(
      partitioner = new HashPartitioner(2),
      serializer = kryo,
      keyOrdering = None,
      aggregator = Some(mock(classOf[Aggregator[Any, Any, Any]])),
      mapSideCombine = false
    )))
  }

  test("unsupported shuffle dependencies for serialized shuffle") {
    val kryo = new KryoSerializer(new SparkConf())
    val java = new JavaSerializer(new SparkConf())

    // We only support serializers that support object relocation
    assert(!canUseSerializedShuffle(shuffleDep(
      partitioner = new HashPartitioner(2),
      serializer = java,
      keyOrdering = None,
      aggregator = None,
      mapSideCombine = false
    )))

    // The serialized shuffle path do not support shuffles with more than 16 million output
    // partitions, due to a limitation in its sorter implementation.
    assert(!canUseSerializedShuffle(shuffleDep(
      partitioner = new HashPartitioner(
        SortShuffleManager.MAX_SHUFFLE_OUTPUT_PARTITIONS_FOR_SERIALIZED_MODE + 1),
      serializer = kryo,
      keyOrdering = None,
      aggregator = None,
      mapSideCombine = false
    )))

    // We do not support serialized shuffle if we need to do map-side aggregation
    assert(!canUseSerializedShuffle(shuffleDep(
      partitioner = new HashPartitioner(2),
      serializer = kryo,
      keyOrdering = Some(mock(classOf[Ordering[Any]])),
      aggregator = Some(mock(classOf[Aggregator[Any, Any, Any]])),
      mapSideCombine = true
    )))
  }

  test("Data could not be cleaned up when unregisterShuffle") {
    withTempDir { tmpDir =>
      val conf = new SparkConf(loadDefaults = false)
      conf.set("spark.local.dir", tmpDir.getAbsolutePath)
      val sc: SparkContext = new SparkContext("local", "SPARK-34541", conf)
      val rdd = sc.parallelize(1 to 10, 1).map(x => (x, x))
      // Create a shuffleRdd
      val shuffledRdd = new ShuffledRDD[Int, Int, Int](rdd, new HashPartitioner(4))
        .setSerializer(new JavaSerializer(conf))
      def getAllFiles: Set[File] =
        FileUtils.listFiles(tmpDir, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE).asScala.toSet
      val filesBeforeShuffle = getAllFiles
      // Force the shuffle to be performed
      shuffledRdd.count()
      // Ensure that the shuffle actually created files that will need to be cleaned up
      val filesCreatedByShuffle = getAllFiles -- filesBeforeShuffle
      filesCreatedByShuffle.map(_.getName) should be
      Set("shuffle_0_0_0.data", "shuffle_0_0_0.index")
      // Check that the cleanup actually removes the files
      sc.env.blockManager.master.removeShuffle(0, blocking = true)
      for (file <- filesCreatedByShuffle) {
        assert (!file.exists(), s"Shuffle file $file was not cleaned up")
      }
    }
  }
}
