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

package org.apache.spark.resource

import java.util.{Map => JMap}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import scala.collection.JavaConverters._
import scala.collection.immutable.HashMap
import scala.collection.mutable

import org.apache.spark.SparkConf
import org.apache.spark.annotation.Evolving
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config._
import org.apache.spark.resource.ResourceUtils.{RESOURCE_DOT, RESOURCE_PREFIX}

/**
 * Resource profile to associate with an RDD. A ResourceProfile allows the user to
 * specify executor and task requirements for an RDD that will get applied during a
 * stage. This allows the user to change the resource requirements between stages.
 *
 * Only support a subset of the resources for now. The config names supported correspond to the
 * regular Spark configs with the prefix removed. For instance overhead memory in this api
 * is memoryOverhead, which is spark.executor.memoryOverhead with spark.executor removed.
 * Resources like GPUs are resource.gpu (spark configs spark.executor.resource.gpu.*)
 *
 * Executor:
 *   memory - heap
 *   memoryOverhead
 *   pyspark.memory
 *   cores
 *   resource.[resourceName] - GPU, FPGA, etc
 *
 * Task requirements:
 *   cpus
 *   resource.[resourceName] - GPU, FPGA, etc
 *
 * This class is private now for initial development, once we have the feature in place
 * this will become public.
 */
@Evolving
class ResourceProfile() extends Serializable {

  private val _id = ResourceProfile.getNextProfileId
  private val _taskResources = new mutable.HashMap[String, TaskResourceRequest]()
  private val _executorResources = new mutable.HashMap[String, ExecutorResourceRequest]()

  private val allowedExecutorResources = HashMap[String, Boolean](
    (ResourceProfile.MEMORY -> true),
    (ResourceProfile.OVERHEAD_MEM -> true),
    (ResourceProfile.PYSPARK_MEM -> true),
    (ResourceProfile.CORES -> true))

  private val allowedTaskResources = HashMap[String, Boolean]((
    ResourceProfile.CPUS -> true))

  def id: Int = _id

  def taskResources: Map[String, TaskResourceRequest] = _taskResources.toMap

  def executorResources: Map[String, ExecutorResourceRequest] = _executorResources.toMap

  /**
   * (Java-specific) gets a Java Map of resources to TaskResourceRequest
   */
  def taskResourcesJMap: JMap[String, TaskResourceRequest] = _taskResources.asJava

  /**
   * (Java-specific) gets a Java Map of resources to ExecutorResourceRequest
   */
  def executorResourcesJMap: JMap[String, ExecutorResourceRequest] = _executorResources.asJava


  def reset(): Unit = {
    _taskResources.clear()
    _executorResources.clear()
  }

  def require(request: TaskResourceRequest): this.type = {
    if (allowedTaskResources.contains(request.resourceName) ||
      request.resourceName.startsWith(RESOURCE_DOT)) {
      _taskResources(request.resourceName) = request
    } else {
      throw new IllegalArgumentException(s"Task resource not allowed: ${request.resourceName}")
    }
    this
  }

  def require(request: ExecutorResourceRequest): this.type = {
    if (allowedExecutorResources.contains(request.resourceName) ||
        request.resourceName.startsWith(RESOURCE_DOT)) {
      _executorResources(request.resourceName) = request
    } else {
      throw new IllegalArgumentException(s"Executor resource not allowed: ${request.resourceName}")
    }
    this
  }

  override def equals(obj: Any): Boolean = {
    obj match {
      case that: ResourceProfile =>
        that.getClass == this.getClass && that._id == _id &&
          that._taskResources == _taskResources && that._executorResources == _executorResources
      case _ =>
        false
    }
  }

  override def hashCode(): Int = Seq(_taskResources, _executorResources).hashCode()

  override def toString(): String = {
    s"Profile: id = ${_id}, executor resources: ${_executorResources}, " +
      s"task resources: ${_taskResources}"
  }
}

private[spark] object ResourceProfile extends Logging {
  val UNKNOWN_RESOURCE_PROFILE_ID = -1
  val DEFAULT_RESOURCE_PROFILE_ID = 0

  val SPARK_RP_TASK_PREFIX = "spark.resourceProfile.task"
  val SPARK_RP_EXEC_PREFIX = "spark.resourceProfile.executor"

  // Helper class for constructing the resource profile internal configs used to pass to
  // executors
  case class ResourceProfileInternalConf(componentName: String,
      id: Int, resourceName: String) {
    def confPrefix: String = s"$componentName.$id.$resourceName."
    def amountConf: String = s"$confPrefix${ResourceUtils.AMOUNT}"
    def discoveryScriptConf: String = s"$confPrefix${ResourceUtils.DISCOVERY_SCRIPT}"
    def vendorConf: String = s"$confPrefix${ResourceUtils.VENDOR}"
  }

  val CPUS = "cpus"
  val CORES = "cores"
  val MEMORY = "memory"
  val OVERHEAD_MEM = "memoryOverhead"
  val PYSPARK_MEM = "pyspark.memory"

  private lazy val nextProfileId = new AtomicInteger(0)

  // The default resource profile uses the application level configs.
  // Create the default profile immediately to get ID 0, its initialized later when fetched.
  private val defaultProfileRef: AtomicReference[ResourceProfile] =
    new AtomicReference[ResourceProfile](new ResourceProfile())

  assert(defaultProfileRef.get().id == DEFAULT_RESOURCE_PROFILE_ID,
    s"Default Profile must have the default profile id: $DEFAULT_RESOURCE_PROFILE_ID")

  def getNextProfileId: Int = nextProfileId.getAndIncrement()

  def getOrCreateDefaultProfile(conf: SparkConf): ResourceProfile = {
    val defaultProf = defaultProfileRef.get()
    // check to see if the default profile was initialized yet
    if (defaultProf.executorResources == Map.empty) {
      synchronized {
        val prof = defaultProfileRef.get()
        if (prof.executorResources == Map.empty) {
          addDefaultTaskResources(prof, conf)
          addDefaultExecutorResources(prof, conf)
        }
        prof
      }
    } else {
      defaultProf
    }
  }

  private def addDefaultTaskResources(rprof: ResourceProfile, conf: SparkConf): Unit = {
    val cpusPerTask = conf.get(CPUS_PER_TASK)
    rprof.require(new TaskResourceRequest(CPUS, cpusPerTask))
    val taskReq = ResourceUtils.parseResourceRequirements(conf, SPARK_TASK_PREFIX)

    taskReq.foreach { req =>
      val name = s"${RESOURCE_PREFIX}.${req.resourceName}"
      rprof.require(new TaskResourceRequest(name, req.amount))
    }
  }

  private def addDefaultExecutorResources(rprof: ResourceProfile, conf: SparkConf): Unit = {
    rprof.require(new ExecutorResourceRequest(CORES, conf.get(EXECUTOR_CORES)))
    rprof.require(
      new ExecutorResourceRequest(MEMORY, conf.get(EXECUTOR_MEMORY).toInt, "b"))
    val execReq = ResourceUtils.parseAllResourceRequests(conf, SPARK_EXECUTOR_PREFIX)

    execReq.foreach { req =>
      val name = s"${RESOURCE_PREFIX}.${req.id.resourceName}"
      val execReq = new ExecutorResourceRequest(name, req.amount, "",
        req.discoveryScript.getOrElse(""), req.vendor.getOrElse(""))
      rprof.require(execReq)
    }
  }

  // for testing purposes
  def resetDefaultProfile(conf: SparkConf): Unit = getOrCreateDefaultProfile(conf).reset()

  /**
   * Create the ResourceProfile internal confs that are used to pass between Driver and Executors.
   * It pulls any "resource." resources from the ResourceProfile and returns a Map of key
   * to value where the keys get formatted as:
   *
   * spark.resourceProfile.executor.[rpId].resource.[resourceName].[amount, vendor, discoveryScript]
   * spark.resourceProfile.task.[rpId].resource.[resourceName].amount
   *
   * Keep this here as utility a function rather then in public ResourceProfile interface because
   * end users shouldn't need this.
   */
  def createResourceProfileInternalConfs(rp: ResourceProfile): Map[String, String] = {
    val res = new mutable.HashMap[String, String]()
    // task resources
    rp.taskResources.filterKeys(_.startsWith(RESOURCE_DOT)).foreach { case (name, req) =>
      val taskIntConf = ResourceProfileInternalConf(SPARK_RP_TASK_PREFIX, rp.id, name)
      res(s"${taskIntConf.amountConf}") = req.amount.toString
    }
    // executor resources
    rp.executorResources.filterKeys(_.startsWith(RESOURCE_DOT)).foreach { case (name, req) =>
      val execIntConf = ResourceProfileInternalConf(SPARK_RP_EXEC_PREFIX, rp.id, name)
      res(execIntConf.amountConf) = req.amount.toString
      if (req.vendor.nonEmpty) res(execIntConf.vendorConf) = req.vendor
      if (req.discoveryScript.nonEmpty) res(execIntConf.discoveryScriptConf) = req.discoveryScript
    }
    res.toMap
  }

  /**
   * Parse out just the resourceName given the map of confs. It only looks for confs that
   * end with .amount because we should always have one of those for every resource.
   * Format is expected to be: [resourcesname].amount, where resourceName could have multiple
   * .'s like resource.gpu.foo.amount
   */
  private def listResourceNames(confs: Map[String, String]): Seq[String] = {
    confs.filterKeys(_.endsWith(ResourceUtils.AMOUNT)).
      map { case (key, _) => key.substring(0, key.lastIndexOf(s".${ResourceUtils.AMOUNT}")) }.toSeq
  }

  /**
   * Get a ResourceProfile with the task requirements from the internal resource confs.
   * The configs looks like:
   * spark.resourceProfile.task.[rpId].resource.[resourceName].amount
   */
  def getTaskRequirementsFromInternalConfs(sparkConf: SparkConf, rpId: Int): ResourceProfile = {
    val rp = new ResourceProfile()
    val taskRpIdConfPrefix = s"${SPARK_RP_TASK_PREFIX}.${rpId}."
    val taskConfs = sparkConf.getAllWithPrefix(taskRpIdConfPrefix).toMap
    val taskResourceNames = listResourceNames(taskConfs)
    taskResourceNames.foreach { resource =>
      val amount = taskConfs.get(s"${resource}.amount").get.toDouble
      rp.require(new TaskResourceRequest(resource, amount))
    }
    rp
  }

  /**
   * Get the executor ResourceRequests from the internal resource confs
   * The configs looks like:
   * spark.resourceProfile.executor.[rpId].resource.gpu.[amount, vendor, discoveryScript]
   */
  def getResourceRequestsFromInternalConfs(
      sparkConf: SparkConf,
      rpId: Int): Seq[ResourceRequest] = {
    val execRpIdConfPrefix = s"${SPARK_RP_EXEC_PREFIX}.${rpId}.${RESOURCE_DOT}"
    val execConfs = sparkConf.getAllWithPrefix(execRpIdConfPrefix).toMap
    val execResourceNames = listResourceNames(execConfs)
    val resourceReqs = execResourceNames.map { resource =>
      val amount = execConfs.get(s"${resource}.amount").get.toInt
      val vendor = execConfs.get(s"${resource}.vendor")
      val discoveryScript = execConfs.get(s"${resource}.discoveryScript")
      ResourceRequest(ResourceID(SPARK_EXECUTOR_PREFIX, resource), amount, discoveryScript, vendor)
    }
    resourceReqs
  }

  /**
   * Utility function to calculate the number of tasks you can run on a single Executor based
   * on the task and executor resource requests in the ResourceProfile. This will be based
   * off the resource that is most restrictive. For instance, if the executor
   * request is for 4 cpus and 2 gpus and your task request is for 1 cpu and 1 gpu each, the
   * limiting resource is gpu, and this function will return 2.
   *
   * @param coresPerExecutor Number of cores per Executor
   * @param resourceProf ResourceProfile
   * @param sparkConf SparkConf
   * @return number of tasks that could be run on a single Executor
   */
  def numTasksPerExecutor(
      coresPerExecutor: Int,
      resourceProf: ResourceProfile,
      sparkConf: SparkConf): Int = {
    val cpusPerTask = resourceProf.taskResources.get(ResourceProfile.CPUS)
      .map(_.amount).getOrElse(sparkConf.get(CPUS_PER_TASK).toDouble).toInt
    val tasksBasedOnCores = coresPerExecutor / cpusPerTask
    var limitingResource = "CPUS"
    var taskLimit = tasksBasedOnCores
    // assumes the task resources are specified
    resourceProf.executorResources.foreach { case (rName, request) =>
      val taskReq = resourceProf.taskResources.get(rName).map(_.amount).getOrElse(0.0)
      if (taskReq > 0.0) {
        var parts = 1
        var numPerTask = taskReq
        if (taskReq < 1.0) {
          parts = Math.floor(1.0 / taskReq).toInt
          numPerTask = Math.ceil(taskReq)
        }
        val numTasks = (request.amount * parts) / numPerTask.toInt
        if (numTasks < taskLimit) {
          limitingResource = rName
          taskLimit = numTasks
        }
      }
    }
    logInfo(s"Limiting resource is $limitingResource at $taskLimit tasks per Executor")
    taskLimit
  }
}
