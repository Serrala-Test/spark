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

package org.apache.spark.storage

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.rdd.RDD
import org.apache.spark.util.Utils

@DeveloperApi
class RDDInfo(
    val id: Int,
    val name: String,
    val numPartitions: Int,
    var storageLevel: StorageLevel,
    val scope: String,
    val parentIds: Seq[Int])
  extends Ordered[RDDInfo] {

  var numCachedPartitions = 0
  var memSize = 0L
  var diskSize = 0L
  var tachyonSize = 0L

  def isCached: Boolean = (memSize + diskSize + tachyonSize > 0) && numCachedPartitions > 0

  override def toString: String = {
    import Utils.bytesToString
    val _scope = Option(scope).getOrElse("--")
    ("RDD \"%s\" (%d) StorageLevel: %s; CachedPartitions: %d; TotalPartitions: %d; " +
      "MemorySize: %s; TachyonSize: %s; DiskSize: %s [scope: %s]").format(
        name, id, storageLevel.toString, numCachedPartitions, numPartitions,
        bytesToString(memSize), bytesToString(tachyonSize), bytesToString(diskSize), _scope)
  }

  override def compare(that: RDDInfo): Int = {
    this.id - that.id
  }
}

private[spark] object RDDInfo {
  def fromRdd(rdd: RDD[_]): RDDInfo = {
    val rddName = Option(rdd.name).getOrElse(rdd.id.toString)
    val parentIds = rdd.dependencies.map(_.rdd.id)
    new RDDInfo(rdd.id, rddName, rdd.partitions.length, rdd.getStorageLevel, rdd.scope, parentIds)
  }
}
