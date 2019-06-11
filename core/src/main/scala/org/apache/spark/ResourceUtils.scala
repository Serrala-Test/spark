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

package org.apache.spark

import java.io.{BufferedInputStream, File, FileInputStream}

import com.fasterxml.jackson.databind.exc.MismatchedInputException
import org.json4s.{DefaultFormats, MappingException}
import org.json4s.JsonAST.JObject
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._

import org.apache.spark.internal.Logging
import org.apache.spark.internal.config._
import org.apache.spark.util.Utils.executeAndGetOutput

/**
 * Resource identifier.
 * @param componentName spark.driver / spark.executor / spark.task
 * @param resourceName  gpu, fpga, etc
 */
private[spark] case class ResourceID(componentName: String, resourceName: String) {
  def confPrefix: String = s"$componentName.resource.$resourceName." // with ending dot
  def amountConf: String = s"$confPrefix${ResourceUtils.AMOUNT}"
  def discoveryScriptConf: String = s"$confPrefix${ResourceUtils.DISCOVERY_SCRIPT}"
  def vendorConf: String = s"$confPrefix${ResourceUtils.VENDOR}"
}

private[spark] case class ResourceRequest(
    id: ResourceID,
    amount: Int,
    discoveryScript: Option[String],
    vendor: Option[String]) {
  def toSparkConfEntries: Seq[(String, String)] = {
    Seq(id.amountConf -> amount.toString) ++
      discoveryScript.map(id.discoveryScriptConf -> _) ++
      vendor.map(id.vendorConf -> _)
  }
}

private[spark] case class TaskResourceRequirement(resourceName: String, count: Int)

private[spark] case class ResourceAllocation(id: ResourceID, addresses: Seq[String]) {
  def toResourceInfo: ResourceInformation = {
    new ResourceInformation(id.resourceName, addresses.toArray)
  }

  def toJson: JObject = {
    ("id" ->
      ("componentName" -> id.componentName) ~
      ("resourceName" -> id.resourceName)) ~
    ("addresses" -> addresses)
  }
}

private[spark] object ResourceUtils extends Logging {

  // config suffixes
  val DISCOVERY_SCRIPT = "discoveryScript"
  val VENDOR = "vendor"
  // user facing configs use .amount to allow to extend in the future,
  // internally we currently only support addresses, so its just an integer count
  val AMOUNT = "amount"

  // case class to make extracting the JSON resource information easy
  private case class JsonResourceInformation(name: String, addresses: Seq[String]) {
    def toResourceInformation: ResourceInformation = {
      new ResourceInformation(name, addresses.toArray)
    }
  }

  def parseResourceRequest(sparkConf: SparkConf, resourceId: ResourceID): ResourceRequest = {
    val settings = sparkConf.getAllWithPrefix(resourceId.confPrefix).toMap
    val amount = settings.getOrElse(AMOUNT,
      throw new SparkException(s"You must specify an amount for ${resourceId.resourceName}")
    ).toInt
    val discoveryScript = settings.get(DISCOVERY_SCRIPT)
    val vendor = settings.get(VENDOR)
    ResourceRequest(resourceId, amount, discoveryScript, vendor)
  }

  def listResourceIds(sparkConf: SparkConf, componentName: String): Seq[ResourceID] = {
    sparkConf.getAllWithPrefix(s"$componentName.resource.").map { case (key, _) =>
      key.substring(0, key.indexOf('.'))
    }.toSet.toSeq.map(name => ResourceID(componentName, name))
  }

  def parseAllResourceRequests(
      sparkConf: SparkConf,
      componentName: String): Seq[ResourceRequest] = {
    listResourceIds(sparkConf, componentName).map { id =>
      parseResourceRequest(sparkConf, id)
    }
  }

  def parseTaskResourceRequirements(sparkConf: SparkConf): Seq[TaskResourceRequirement] = {
    parseAllResourceRequests(sparkConf, SPARK_TASK_PREFIX).map { request =>
      TaskResourceRequirement(request.id.resourceName, request.amount)
    }
  }

  private def parseAllocatedFromJsonFile(resourcesFile: String): Seq[ResourceAllocation] = {
    implicit val formats = DefaultFormats
    val resourceInput = new BufferedInputStream(new FileInputStream(resourcesFile))
    try {
      parse(resourceInput).extract[Seq[ResourceAllocation]]
    } catch {
      case e@(_: MappingException | _: MismatchedInputException | _: ClassCastException) =>
        throw new SparkException(s"Exception parsing the resources in $resourcesFile", e)
    } finally {
      resourceInput.close()
    }
  }

  private def parseResourceInformationFromJson(resourcesJson: String): ResourceInformation = {
    implicit val formats = DefaultFormats
    try {
      parse(resourcesJson).extract[JsonResourceInformation].toResourceInformation
    } catch {
      case e@(_: MappingException | _: MismatchedInputException | _: ClassCastException) =>
        throw new SparkException(s"Exception parsing the resources in $resourcesJson", e)
    }
  }

  private def parseAllocatedOrDiscoverResources(
      sparkConf: SparkConf,
      componentName: String,
      resourcesFileOpt: Option[String]): Seq[ResourceAllocation] = {
    val allocated = resourcesFileOpt.toSeq.flatMap(parseAllocatedFromJsonFile)
      .filter(_.id.componentName == componentName)
    val otherResourceIds = listResourceIds(sparkConf, componentName).diff(allocated.map(_.id))
    allocated ++ otherResourceIds.map { id =>
      val request = parseResourceRequest(sparkConf, id)
      ResourceAllocation(id, discoverResource(request).addresses)
    }
  }

  private def assertResourceAllocationMeetsRequest(
      allocation: ResourceAllocation,
      request: ResourceRequest): Unit = {
    require(allocation.id == request.id && allocation.addresses.size >= request.amount,
      s"Resource: ${allocation.id.resourceName}, with addresses: " +
      s"${allocation.addresses.mkString(",")} " +
      s"is less than what the user requested: ${request.amount})")
  }

  private def assertAllResourceAllocationsMeetRequests(
      allocations: Seq[ResourceAllocation],
      requests: Seq[ResourceRequest]): Unit = {
    val allocated = allocations.map(x => x.id -> x).toMap
    requests.foreach(r => assertResourceAllocationMeetsRequest(allocated(r.id), r))
  }

  /**
   * Gets all resource information for the input component.
   * @return a map from resource name to resource info
   */
  def getAllResources(
      sparkConf: SparkConf,
      componentName: String,
      resourcesFileOpt: Option[String]): Map[String, ResourceInformation] = {
    val requests = parseAllResourceRequests(sparkConf, componentName)
    val allocations = parseAllocatedOrDiscoverResources(sparkConf, componentName, resourcesFileOpt)
    assertAllResourceAllocationsMeetRequests(allocations, requests)
    val resourceInfoMap = allocations.map(a => (a.id.resourceName, a.toResourceInfo)).toMap
    logInfo("==============================================================")
    logInfo("Resources:")
    resourceInfoMap.foreach { case (k, v) => logInfo(s"$k -> $v") }
    logInfo("==============================================================")
    resourceInfoMap
  }

  // visible for test
  def discoverResource(resourceRequest: ResourceRequest): ResourceInformation = {
    val resourceName = resourceRequest.id.resourceName
    val script = resourceRequest.discoveryScript
    val result = if (script.nonEmpty) {
      val scriptFile = new File(script.get)
      // check that script exists and try to execute
      if (scriptFile.exists()) {
        val output = executeAndGetOutput(Seq(script.get), new File("."))
        parseResourceInformationFromJson(output)
      } else {
        throw new SparkException(s"Resource script: $scriptFile to discover $resourceName " +
          "doesn't exist!")
      }
    } else {
      throw new SparkException(s"User is expecting to use resource: $resourceName but " +
        "didn't specify a discovery script!")
    }
    if (!result.name.equals(resourceName)) {
      throw new SparkException("Error running the resource discovery script, script returned " +
        s"resource name: ${result.name} and we were expecting $resourceName")
    }
    result
  }

  def setResourceRequestConf(conf: SparkConf, request: ResourceRequest): Unit = {
    conf.setAll(request.toSparkConfEntries)
  }

  // known types of resources
  final val GPU: String = "gpu"
  final val FPGA: String = "fpga"
}
