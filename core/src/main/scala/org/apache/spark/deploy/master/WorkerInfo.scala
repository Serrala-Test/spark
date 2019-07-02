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

package org.apache.spark.deploy.master

import scala.collection.mutable

import org.apache.spark.resource.{ResourceAllocator, ResourceInformation}
import org.apache.spark.rpc.RpcEndpointRef
import org.apache.spark.util.Utils

private[spark] case class WorkerResourceInfo(name: String, addresses: Seq[String])
  extends ResourceAllocator(name, addresses) {

  def toResourceInformation(): ResourceInformation = {
    new ResourceInformation(name, addresses.toArray)
  }

  def acquire(amount: Int): Seq[String] = {
    val allocated = availableAddrs.take(amount)
    acquire(allocated)
    allocated
  }
}

private[spark] class WorkerInfo(
    val id: String,
    val host: String,
    val port: Int,
    val cores: Int,
    val memory: Int,
    val endpoint: RpcEndpointRef,
    val webUiAddress: String,
    val resources: Map[String, WorkerResourceInfo])
  extends Serializable {

  Utils.checkHost(host)
  assert (port > 0)

  @transient var executors: mutable.HashMap[String, ExecutorDesc] = _ // executorId => info
  @transient var drivers: mutable.HashMap[String, DriverInfo] = _ // driverId => info
  @transient var state: WorkerState.Value = _
  @transient var coresUsed: Int = _
  @transient var memoryUsed: Int = _
  @transient var driverToResourcesUsed: mutable.HashMap[String, Map[String, Seq[String]]] = _
  @transient var execToResourcesUsed: mutable.HashMap[String, Map[String, Seq[String]]] = _

  @transient var lastHeartbeat: Long = _

  init()

  def coresFree: Int = cores - coresUsed
  def memoryFree: Int = memory - memoryUsed
  def resourcesFree: Map[String, Int] = {
    resources.map { case (rName, rInfo) =>
      rName -> rInfo.availableAddrs.length
    }
  }

  def assignedResources: Map[String, Seq[String]] = {
    resources.map { case (rName, rInfo) =>
      (rName, rInfo.addresses)
    }
  }

  private def readObject(in: java.io.ObjectInputStream): Unit = Utils.tryOrIOException {
    in.defaultReadObject()
    init()
  }

  private def init() {
    executors = new mutable.HashMap
    drivers = new mutable.HashMap
    state = WorkerState.ALIVE
    coresUsed = 0
    memoryUsed = 0
    driverToResourcesUsed = new mutable.HashMap
    execToResourcesUsed = new mutable.HashMap
    lastHeartbeat = System.currentTimeMillis()
  }

  def hostPort: String = {
    assert (port > 0)
    host + ":" + port
  }

  def addExecutor(exec: ExecutorDesc) {
    executors(exec.fullId) = exec
    coresUsed += exec.cores
    memoryUsed += exec.memory
    execToResourcesUsed(exec.fullId) = exec.resources
  }

  def removeExecutor(exec: ExecutorDesc) {
    if (executors.contains(exec.fullId)) {
      executors -= exec.fullId
      coresUsed -= exec.cores
      memoryUsed -= exec.memory
      execToResourcesUsed.remove(exec.fullId)
    }
  }

  def hasExecutor(app: ApplicationInfo): Boolean = {
    executors.values.exists(_.application == app)
  }

  def addDriver(driver: DriverInfo) {
    drivers(driver.id) = driver
    memoryUsed += driver.desc.mem
    coresUsed += driver.desc.cores
    driverToResourcesUsed(driver.id) = driver.resources
  }

  def removeDriver(driver: DriverInfo) {
    drivers -= driver.id
    memoryUsed -= driver.desc.mem
    coresUsed -= driver.desc.cores
    driverToResourcesUsed.remove(driver.id)
  }

  def setState(state: WorkerState.Value): Unit = {
    this.state = state
  }

  def isAlive(): Boolean = this.state == WorkerState.ALIVE

  /**
   * acquire specified amount resources for driver/executor from the worker
   * @param resourceReqs the resources requirement from driver/executor
   * @return
   */
  def acquireResources(resourceReqs: Map[String, Int])
  : Map[String, Seq[String]] = {
    resourceReqs.map { case (rName, amount) =>
      rName -> resources(rName).acquire(amount)
    }
  }

  /**
   * used during master recovery
   */
  def notifyResources(expected: Map[String, Seq[String]]): Unit = {
    expected.foreach { case (rName, addresses) =>
      resources(rName).acquire(addresses)
    }
  }

  /**
   * release resources to worker from the driver/executor
   * @param allocated the resources which allocated to driver/executor previously
   */
  def releaseResources(allocated: Map[String, Seq[String]])
  : Unit = {
    allocated.foreach { case (rName, addresses) =>
      resources(rName).release(addresses)
    }
  }
}
