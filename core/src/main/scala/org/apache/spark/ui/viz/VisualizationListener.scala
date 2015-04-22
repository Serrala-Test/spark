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

package org.apache.spark.ui.viz

import scala.collection.mutable

import org.apache.spark.scheduler._

/**
 * A SparkListener that...
 */
private[ui] class VisualizationListener extends SparkListener {
  private val graphsByStageId = new mutable.HashMap[Int, VizGraph] // stage ID -> viz graph

  /**  */
  def getVizGraph(stageId: Int): Option[VizGraph] = {
    graphsByStageId.get(stageId)
  }

  override def onStageCompleted(stageCompleted: SparkListenerStageCompleted): Unit = synchronized {
    val stageId = stageCompleted.stageInfo.stageId
    val rddInfos = stageCompleted.stageInfo.rddInfos
    val vizGraph = VizGraph.makeVizGraph(rddInfos)
    graphsByStageId(stageId) = vizGraph
  }
}
