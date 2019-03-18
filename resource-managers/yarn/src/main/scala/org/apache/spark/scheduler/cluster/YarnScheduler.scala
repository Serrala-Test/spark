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

package org.apache.spark.scheduler.cluster

import org.apache.hadoop.net.NetworkTopology

import org.apache.spark._
import org.apache.spark.deploy.yarn.SparkRackResolver
import org.apache.spark.scheduler.TaskSchedulerImpl
import org.apache.spark.util.Utils

private[spark] class YarnScheduler(sc: SparkContext) extends TaskSchedulerImpl(sc) {

  private[spark] val resolver = new SparkRackResolver

  override def defaultRackValue: Option[String] = Option(NetworkTopology.DEFAULT_RACK)

  /**
   * Get racks info for a list of host.
   * Use [[SparkRackResolver]] to resolve racks before the dependency Hadoop applied YARN-9332
   * @param hostPorts a list of task preferred locations to be resolved.
   * @return resolved rack list.
   */
  override def getRacksForHosts(hostPorts: List[String]): List[Option[String]] = {
    val hosts = hostPorts.map(Utils.parseHostPort(_)._1)
    resolver.resolve(sc.hadoopConfiguration, hosts).map { node =>
      Option(node.getNetworkLocation)
    }
  }
}
