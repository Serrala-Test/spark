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

package org.apache.spark.sql.execution.streaming.state

import scala.reflect.ClassTag

import org.apache.spark.rdd.RDD
import org.apache.spark.util.Utils
import org.apache.spark.{Partition, TaskContext}

/**
  * Created by tdas on 3/9/16.
  */
class StateStoreRDD[INPUT: ClassTag, OUTPUT: ClassTag](
    dataRDD: RDD[INPUT],
    storeUpdateFunction: (StateStore, Iterator[INPUT]) => Iterator[OUTPUT],
    operatorId: Long,
    newStoreVersion: Long,
    storeDirectory: String,
    storeCoordinator: StateStoreCoordinator
  )
  extends RDD[OUTPUT](dataRDD) {

  override protected def getPartitions: Array[Partition] = dataRDD.partitions
  override def getPreferredLocations(partition: Partition): Seq[String] = {
    storeCoordinator.getLocation(
      StateStoreId(operatorId, partition.index)).toSeq
  }

  override def compute(partition: Partition, ctxt: TaskContext): Iterator[OUTPUT] = {
    var store: StateStore = null

    Utils.tryWithSafeFinally {
      StateStore.get(
        StateStoreId(operatorId, partition.index),
        storeDirectory
      )
      val inputIter = dataRDD.compute(partition, ctxt)
      store.startUpdates(newStoreVersion)
      val outputIter = storeUpdateFunction(store, inputIter)
      assert(store.hasCommitted)
      outputIter
    } {
      if (store != null) store.cancelUpdates()
    }
  }
}

object StateStoreRDD {
  implicit def withStateStores[INPUT: ClassTag, OUTPUT: ClassTag](
    dataRDD: RDD[INPUT],
    storeUpdateFunction: (StateStore, Iterator[INPUT]) => Iterator[OUTPUT],
    operatorId: Long,
    newStoreVersion: Long,
    storeDirectory: String,
    storeCoordinator: StateStoreCoordinator
  ): RDD[OUTPUT] = {
    new StateStoreRDD(
      dataRDD, storeUpdateFunction, operatorId, newStoreVersion, storeDirectory, storeCoordinator)
  }
}
