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

package org.apache.spark.rdd

import scala.reflect.ClassTag

import org.apache.spark.{Logging, SparkEnv, TaskContext}
import org.apache.spark.storage.{RDDBlockId, StorageLevel}

/**
 * An implementation of checkpointing that writes the RDD data to a local file system.
 *
 * Local checkpointing trades off fault tolerance for performance by skipping the expensive
 * step of replicating the checkpointed data in a reliable storage. This is useful for use
 * cases where RDDs build up long lineages that need to be truncated often (e.g. GraphX).
 */
private[spark] class LocalRDDCheckpointData[T: ClassTag](@transient rdd: RDD[T])
  extends RDDCheckpointData[T](rdd) with Logging {

  /**
   * Write the content of each partition on a local disk store.
   */
  protected override def doCheckpoint(): CheckpointRDD[T] = {
    val checkpointRdd = new LocalCheckpointRDD[T](rdd.context)

    // Note: we persist the partitions using the checkpoint RDD's ID rather than the
    // parent RDD's ID. This is not fundamental in design, but allows us to simply
    // reuse the RDD clean up code path to clean the checkpointed files.
    val checkpointRddId = checkpointRdd.id
    val persistPartition = (taskContext: TaskContext, values: Iterator[T]) => {
      // TODO: if it's already in disk store, just use the existing values
      val blockId = RDDBlockId(checkpointRddId, taskContext.partitionId())
      SparkEnv.get.blockManager.putIterator(blockId, values, StorageLevel.DISK_ONLY)
    }

    // Optionally clean our checkpoint files if the reference is out of scope
    if (rdd.conf.getBoolean("spark.cleaner.referenceTracking.cleanCheckpoints", false)) {
      rdd.context.cleaner.foreach { cleaner =>
        cleaner.registerRDDForCleanup(checkpointRdd)
      }
    }

    rdd.context.runJob(rdd, persistPartition)
    checkpointRdd
  }

}
