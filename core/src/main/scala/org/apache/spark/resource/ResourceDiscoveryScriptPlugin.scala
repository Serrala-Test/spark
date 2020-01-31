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

import java.io.File

import org.apache.spark.{SparkConf, SparkException}
import org.apache.spark.api.resource.ResourceDiscoveryPlugin
import org.apache.spark.internal.Logging
import org.apache.spark.util.Utils.executeAndGetOutput

/**
 * The default plugin that is loaded into a Spark application to control how custom
 * resources are discovered. This executes the discovery script specified by the user
 * and gets the json output back and contructs ResourceInformation objects from that.
 */
class ResourceDiscoveryScriptPlugin extends ResourceDiscoveryPlugin with Logging {
  override def discoverResource(
      request: ResourceRequest,
      sparkConf: SparkConf): ResourceInformation = {
    val script = request.discoveryScript
    val resourceName = request.id.resourceName
    val result = if (script.isPresent) {
      val scriptFile = new File(script.get)
      logInfo(s"Discovering resources for $resourceName with script: $scriptFile")
      // check that script exists and try to execute
      if (scriptFile.exists()) {
        val output = executeAndGetOutput(Seq(script.get), new File("."))
        ResourceInformation.parseJson(output)
      } else {
        throw new SparkException(s"Resource script: $scriptFile to discover $resourceName " +
          "doesn't exist!")
      }
    } else {
      throw new SparkException(s"User is expecting to use resource: $resourceName, but " +
        "didn't specify a discovery script!")
    }
    if (!result.name.equals(resourceName)) {
      throw new SparkException(s"Error running the resource discovery script ${script.get}: " +
        s"script returned resource name ${result.name} and we were expecting $resourceName.")
    }
    result
  }
}
