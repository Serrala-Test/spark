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

package org.apache.spark.deploy.mesos.ui

import org.apache.spark.scheduler.cluster.mesos.MesosClusterScheduler
import org.apache.spark.{SecurityManager, SparkConf}
import org.apache.spark.ui.JettyUtils._
import org.apache.spark.ui.{SparkUI, WebUI}

/**
 * UI that displays driver results from the [[org.apache.spark.deploy.mesos.MesosClusterDispatcher]]
 */
private[spark] class MesosClusterUI(
    securityManager: SecurityManager,
    port: Int,
    conf: SparkConf,
    dispatcherPublicAddress: String,
    val scheduler: MesosClusterScheduler)
  extends WebUI(securityManager, port, conf) {

  initialize()

  def activeWebUiUrl: String = "http://" + dispatcherPublicAddress + ":" + boundPort

  override def initialize() {
    attachPage(new MesosClusterPage(this))
    attachHandler(createStaticHandler(MesosClusterUI.STATIC_RESOURCE_DIR, "/static"))
  }
}

private object MesosClusterUI {
  val STATIC_RESOURCE_DIR = SparkUI.STATIC_RESOURCE_DIR
}
