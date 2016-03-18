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

package org.apache.spark.streaming.dstream

import scala.reflect.ClassTag

import org.apache.spark._
import org.apache.spark.annotation.Experimental
import org.apache.spark.rdd.{EmptyRDD, RDD}
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming._
<<<<<<< HEAD
import org.apache.spark.streaming.dstream.InternalMapWithStateDStream._
import org.apache.spark.streaming.rdd.{MapWithStateRDD, MapWithStateRDDRecord}
=======
import org.apache.spark.streaming.rdd.{MapWithStateRDD, MapWithStateRDDRecord}
import org.apache.spark.streaming.dstream.InternalMapWithStateDStream._
>>>>>>> 022e06d18471bf54954846c815c8a3666aef9fc3

/**
 * :: Experimental ::
 * DStream representing the stream of data generated by `mapWithState` operation on a
 * [[org.apache.spark.streaming.dstream.PairDStreamFunctions pair DStream]].
 * Additionally, it also gives access to the stream of state snapshots, that is, the state data of
 * all keys after a batch has updated them.
 *
 * @tparam KeyType Class of the key
 * @tparam ValueType Class of the value
 * @tparam StateType Class of the state data
 * @tparam MappedType Class of the mapped data
 */
@Experimental
sealed abstract class MapWithStateDStream[KeyType, ValueType, StateType, MappedType: ClassTag](
    ssc: StreamingContext) extends DStream[MappedType](ssc) {

  /** Return a pair DStream where each RDD is the snapshot of the state of all the keys. */
  def stateSnapshots(): DStream[(KeyType, StateType)]
}

/** Internal implementation of the [[MapWithStateDStream]] */
private[streaming] class MapWithStateDStreamImpl[
    KeyType: ClassTag, ValueType: ClassTag, StateType: ClassTag, MappedType: ClassTag](
    dataStream: DStream[(KeyType, ValueType)],
    spec: StateSpecImpl[KeyType, ValueType, StateType, MappedType])
  extends MapWithStateDStream[KeyType, ValueType, StateType, MappedType](dataStream.context) {

  private val internalStream =
    new InternalMapWithStateDStream[KeyType, ValueType, StateType, MappedType](dataStream, spec)

  override def slideDuration: Duration = internalStream.slideDuration

  override def dependencies: List[DStream[_]] = List(internalStream)

  override def compute(validTime: Time): Option[RDD[MappedType]] = {
    internalStream.getOrCompute(validTime).map { _.flatMap[MappedType] { _.mappedData } }
  }

  /**
   * Forward the checkpoint interval to the internal DStream that computes the state maps. This
   * to make sure that this DStream does not get checkpointed, only the internal stream.
   */
  override def checkpoint(checkpointInterval: Duration): DStream[MappedType] = {
    internalStream.checkpoint(checkpointInterval)
    this
  }

  /** Return a pair DStream where each RDD is the snapshot of the state of all the keys. */
  def stateSnapshots(): DStream[(KeyType, StateType)] = {
    internalStream.flatMap {
      _.stateMap.getAll().map { case (k, s, _) => (k, s) }.toTraversable }
  }

  def keyClass: Class[_] = implicitly[ClassTag[KeyType]].runtimeClass

  def valueClass: Class[_] = implicitly[ClassTag[ValueType]].runtimeClass

  def stateClass: Class[_] = implicitly[ClassTag[StateType]].runtimeClass

  def mappedClass: Class[_] = implicitly[ClassTag[MappedType]].runtimeClass
}

/**
<<<<<<< HEAD
 * A DStream that allows per-key state to be maintained, and arbitrary records to be generated
=======
 * A DStream that allows per-key state to be maintains, and arbitrary records to be generated
>>>>>>> 022e06d18471bf54954846c815c8a3666aef9fc3
 * based on updates to the state. This is the main DStream that implements the `mapWithState`
 * operation on DStreams.
 *
 * @param parent Parent (key, value) stream that is the source
 * @param spec Specifications of the mapWithState operation
 * @tparam K   Key type
 * @tparam V   Value type
 * @tparam S   Type of the state maintained
 * @tparam E   Type of the mapped data
 */
private[streaming]
class InternalMapWithStateDStream[K: ClassTag, V: ClassTag, S: ClassTag, E: ClassTag](
    parent: DStream[(K, V)], spec: StateSpecImpl[K, V, S, E])
  extends DStream[MapWithStateRDDRecord[K, S, E]](parent.context) {

  persist(StorageLevel.MEMORY_ONLY)

  private val partitioner = spec.getPartitioner().getOrElse(
    new HashPartitioner(ssc.sc.defaultParallelism))

  private val mappingFunction = spec.getFunction()

  override def slideDuration: Duration = parent.slideDuration

  override def dependencies: List[DStream[_]] = List(parent)

  /** Enable automatic checkpointing */
  override val mustCheckpoint = true

  /** Override the default checkpoint duration */
  override def initialize(time: Time): Unit = {
    if (checkpointDuration == null) {
      checkpointDuration = slideDuration * DEFAULT_CHECKPOINT_DURATION_MULTIPLIER
    }
    super.initialize(time)
  }

  /** Method that generates a RDD for the given time */
  override def compute(validTime: Time): Option[RDD[MapWithStateRDDRecord[K, S, E]]] = {
    // Get the previous state or create a new empty state RDD
    val prevStateRDD = getOrCompute(validTime - slideDuration) match {
      case Some(rdd) =>
        if (rdd.partitioner != Some(partitioner)) {
          // If the RDD is not partitioned the right way, let us repartition it using the
          // partition index as the key. This is to ensure that state RDD is always partitioned
          // before creating another state RDD using it
          MapWithStateRDD.createFromRDD[K, V, S, E](
            rdd.flatMap { _.stateMap.getAll() }, partitioner, validTime)
        } else {
          rdd
        }
      case None =>
        MapWithStateRDD.createFromPairRDD[K, V, S, E](
          spec.getInitialStateRDD().getOrElse(new EmptyRDD[(K, S)](ssc.sparkContext)),
          partitioner,
          validTime
        )
    }


    // Compute the new state RDD with previous state RDD and partitioned data RDD
    // Even if there is no data RDD, use an empty one to create a new state RDD
    val dataRDD = parent.getOrCompute(validTime).getOrElse {
      context.sparkContext.emptyRDD[(K, V)]
    }
    val partitionedDataRDD = dataRDD.partitionBy(partitioner)
    val timeoutThresholdTime = spec.getTimeoutInterval().map { interval =>
      (validTime - interval).milliseconds
    }
    Some(new MapWithStateRDD(
      prevStateRDD, partitionedDataRDD, mappingFunction, validTime, timeoutThresholdTime))
  }
}

private[streaming] object InternalMapWithStateDStream {
  private val DEFAULT_CHECKPOINT_DURATION_MULTIPLIER = 10
}
