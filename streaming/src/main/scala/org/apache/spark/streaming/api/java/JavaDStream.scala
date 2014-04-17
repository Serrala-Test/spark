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

package org.apache.spark.streaming.api.java

import org.apache.spark.streaming.{Duration, Time}
import org.apache.spark.api.java.function.{Function => JFunction}
import org.apache.spark.api.java.JavaRDD
import org.apache.spark.storage.StorageLevel
import org.apache.spark.rdd.RDD

import scala.language.implicitConversions
import scala.reflect.ClassTag
import org.apache.spark.streaming.dstream.DStream

/**
 * A Java-friendly interface to [[org.apache.spark.streaming.dstream.DStream]], the basic
 * abstraction in Spark Streaming that represents a continuous stream of data.
 * DStreams can either be created from live data (such as, data from TCP sockets, Kafka, Flume,
 * etc.) or it can be generated by transforming existing DStreams using operations such as `map`,
 * `window`. For operations applicable to key-value pair DStreams, see
 * [[org.apache.spark.streaming.api.java.JavaPairDStream]].
 */
class JavaDStream[T](val dstream: DStream[T])(implicit val classTag: ClassTag[T])
    extends JavaDStreamLike[T, JavaDStream[T], JavaRDD[T]] {

  override def wrapRDD(rdd: RDD[T]): JavaRDD[T] = JavaRDD.fromRDD(rdd)

  /** Return a new DStream containing only the elements that satisfy a predicate. */
  def filter(f: JFunction[T, java.lang.Boolean]): JavaDStream[T] =
    dstream.filter((x => f.call(x).booleanValue()))

  /** Persist RDDs of this DStream with the default storage level (MEMORY_ONLY_SER) */
  def cache(): JavaDStream[T] = dstream.cache()

  /** Persist RDDs of this DStream with the default storage level (MEMORY_ONLY_SER) */
  def persist(): JavaDStream[T] = dstream.persist()

  /** Persist the RDDs of this DStream with the given storage level */
  def persist(storageLevel: StorageLevel): JavaDStream[T] = dstream.persist(storageLevel)

  /** Generate an RDD for the given duration */
  def compute(validTime: Time): JavaRDD[T] = {
    dstream.compute(validTime) match {
      case Some(rdd) => new JavaRDD(rdd)
      case None => null
    }
  }

  /**
   * Return a new DStream in which each RDD contains all the elements in seen in a
   * sliding window of time over this DStream. The new DStream generates RDDs with
   * the same interval as this DStream.
   * @param windowDuration width of the window; must be a multiple of this DStream's interval.
   */
  def window(windowDuration: Duration): JavaDStream[T] =
    dstream.window(windowDuration)

  /**
   * Return a new DStream in which each RDD contains all the elements in seen in a
   * sliding window of time over this DStream.
   * @param windowDuration width of the window; must be a multiple of this DStream's
   *                       batching interval
   * @param slideDuration  sliding interval of the window (i.e., the interval after which
   *                       the new DStream will generate RDDs); must be a multiple of this
   *                       DStream's batching interval
   */
  def window(windowDuration: Duration, slideDuration: Duration): JavaDStream[T] =
    dstream.window(windowDuration, slideDuration)

  /**
   * Return a new DStream by unifying data of another DStream with this DStream.
   * @param that Another DStream having the same interval (i.e., slideDuration) as this DStream.
   */
  def union(that: JavaDStream[T]): JavaDStream[T] =
    dstream.union(that.dstream)

  /**
   * Return a new DStream with an increased or decreased level of parallelism. Each RDD in the
   * returned DStream has exactly numPartitions partitions.
   */
  def repartition(numPartitions: Int): JavaDStream[T] = dstream.repartition(numPartitions)
}

object JavaDStream {
  implicit def fromDStream[T: ClassTag](dstream: DStream[T]): JavaDStream[T] =
    new JavaDStream[T](dstream)
}
