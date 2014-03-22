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

package org.apache.spark.deploy.history

import java.text.SimpleDateFormat
import java.util.Date
import javax.servlet.http.HttpServletRequest

import scala.xml.Node

import org.apache.spark.deploy.DeployWebUI
import org.apache.spark.ui.UIUtils

private[spark] class IndexPage(parent: HistoryServer) {
  private val dateFmt = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss")

  def render(request: HttpServletRequest): Seq[Node] = {
    parent.checkForLogs()

    // Populate app table, with most recently modified app first
    val appRows = parent.appIdToInfo.values.toSeq.sortBy { app => -app.lastUpdated }
    val appTable = UIUtils.listingTable(appHeader, appRow, appRows)
    val content =
      <div class="row-fluid">
        <div class="span12">
          <ul class="unstyled">
            <li><strong>Event Log Location: </strong> {parent.baseLogDir}</li>
            <br></br>
            <h4>Finished Applications</h4> {appTable}
          </ul>
        </div>
      </div>

    UIUtils.basicSparkPage(content, "History Server")
  }

  private val appHeader = Seq(
    "App Name",
    "Started",
    "Finished",
    "Duration",
    "Log Directory",
    "Last Updated")

  private def appRow(info: ApplicationHistoryInfo): Seq[Node] = {
    val appName = if (info.started) info.name else parent.getAppId(info.logPath)
    val uiAddress = parent.getAddress + info.ui.basePath
    val startTime = if (info.started) dateFmt.format(new Date(info.startTime)) else "Not started"
    val endTime = if (info.finished) dateFmt.format(new Date(info.endTime)) else "Not finished"
    val difference = if (info.started && info.finished) info.endTime - info.startTime else -1L
    val duration = if (difference > 0) DeployWebUI.formatDuration(difference) else "---"
    val logDirectory = parent.getAppId(info.logPath)
    val lastUpdated = dateFmt.format(new Date(info.lastUpdated))
    <tr>
      <td><a href={uiAddress}>{appName}</a></td>
      <td>{startTime}</td>
      <td>{endTime}</td>
      <td>{duration}</td>
      <td>{logDirectory}</td>
      <td>{lastUpdated}</td>
    </tr>
  }
}
