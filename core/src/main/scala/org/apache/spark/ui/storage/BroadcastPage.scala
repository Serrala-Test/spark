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

package org.apache.spark.ui.storage

import org.apache.spark.storage.StorageUtils
import org.apache.spark.ui.UIUtils
import org.apache.spark.util.Utils

import scala.xml.Node

private[ui] class BroadcastPage(parent: StorageTab) extends StorageDetailPage("broadcast", parent){

  protected override val workerTableID: String = "broadcast-storage-by-worker-table"
  
  protected override def objectList = listener.broadcastInfoList

  protected override def getBlockTableAndSize(objectId: Any): (Seq[Node], Int) = {
    val blockLocations = StorageUtils.getBroadcastBlockLocation(objectId.asInstanceOf[Long],
      storageStatusList)
    val blocks = listener.storageStatusList
      .flatMap(_.broadcastBlocksById(objectId.asInstanceOf[Long]))
      .sortWith(_._1.name < _._1.name)
      .map { case (blockId, status) =>
      (blockId, status, blockLocations.get(blockId).getOrElse(Seq[String]("Unknown")))
    }
    (UIUtils.listingTable(blockHeader, blockRow, blocks, 
      id = Some("broadcast-storage-by-block-table")), blocks.size)
  }

  protected override def generateContent(objectId: Long): (String, Seq[Node]) = {
    val objectInfo = objectList.find(_.id == objectId).getOrElse {
      // Rather than crashing, render an "Not Found" page
      return (objectId.toString, nonFoundErrorInfo)
    }
    val (workerTable, workerCount) = getWorkerTableAndSize(objectId)

    val (blockTable, blockCount) = getBlockTableAndSize(objectId)

    val content =
      <div class="row-fluid">
        <div class="span12">
          <ul class="unstyled">
            <li>
              <strong>Storage Level:</strong>
              {objectInfo.storageLevel.description}
            </li>
            <li>
              <strong>Memory Size:</strong>
              {Utils.bytesToString(objectInfo.memSize)}
            </li>
            <li>
              <strong>Disk Size:</strong>
              {Utils.bytesToString(objectInfo.diskSize)}
            </li>
          </ul>
        </div>
      </div>

        <div class="row-fluid">
          <div class="span12">
            <h4> Data Distribution on {workerCount} Executors </h4>
            {workerTable}
          </div>
        </div>

        <div class="row-fluid">
          <div class="span12">
            <h4> {blockCount} Partitions </h4>
            {blockTable}
          </div>
        </div>;

    (objectInfo.name, content)
  }
}
