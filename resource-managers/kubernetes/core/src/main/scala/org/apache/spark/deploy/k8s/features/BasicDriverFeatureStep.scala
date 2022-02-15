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
package org.apache.spark.deploy.k8s.features

import javax.ws.rs.core.UriBuilder

import scala.collection.JavaConverters._
import scala.collection.mutable

import io.fabric8.kubernetes.api.model._

import org.apache.spark.SparkException
import org.apache.spark.deploy.k8s._
import org.apache.spark.deploy.k8s.Config._
import org.apache.spark.deploy.k8s.Constants._
import org.apache.spark.deploy.k8s.submit._
import org.apache.spark.internal.config._
import org.apache.spark.resource.ResourceProfile
import org.apache.spark.ui.SparkUI
import org.apache.spark.util.Utils

private[spark] class BasicDriverFeatureStep(conf: KubernetesDriverConf)
  extends KubernetesFeatureConfigStep {

  private val driverPodName = conf
    .get(KUBERNETES_DRIVER_POD_NAME)
    .getOrElse(s"${conf.resourceNamePrefix}-driver")

  private val driverContainerImage = conf
    .get(DRIVER_CONTAINER_IMAGE)
    .getOrElse(throw new SparkException("Must specify the driver container image"))

  // CPU settings
  private val driverCpuCores = conf.get(DRIVER_CORES)
  private val driverCoresRequest = conf
    .get(KUBERNETES_DRIVER_REQUEST_CORES)
    .getOrElse(driverCpuCores.toString)
  private val driverLimitCores = conf.get(KUBERNETES_DRIVER_LIMIT_CORES)

  // Memory settings
  private val driverMemoryMiB = conf.get(DRIVER_MEMORY)
  private val memoryOverheadFactor = conf.get(MEMORY_OVERHEAD_FACTOR)
    .getOrElse(conf.get(DRIVER_MEMORY_OVERHEAD_FACTOR))

  // The memory overhead factor to use. If the user has not set it, then use a different
  // value for non-JVM apps. This value is propagated to executors.
  private val overheadFactor =
    if (conf.mainAppResource.isInstanceOf[NonJVMResource]) {
      if (conf.contains(MEMORY_OVERHEAD_FACTOR) || conf.contains(DRIVER_MEMORY_OVERHEAD_FACTOR)) {
        memoryOverheadFactor
      } else {
        NON_JVM_MEMORY_OVERHEAD_FACTOR
      }
    } else {
      memoryOverheadFactor
    }

  private val memoryOverheadMiB = conf
    .get(DRIVER_MEMORY_OVERHEAD)
    .getOrElse(math.max((overheadFactor * driverMemoryMiB).toInt,
      ResourceProfile.MEMORY_OVERHEAD_MIN_MIB))
  private val driverMemoryWithOverheadMiB = driverMemoryMiB + memoryOverheadMiB

  override def configurePod(pod: SparkPod): SparkPod = {
    val driverCustomEnvs = (Seq(
      (ENV_APPLICATION_ID, conf.appId)
    ) ++ conf.environment)
      .map { env =>
        new EnvVarBuilder()
          .withName(env._1)
          .withValue(env._2)
          .build()
      }

    val driverCpuQuantity = new Quantity(driverCoresRequest)
    val driverMemoryQuantity = new Quantity(s"${driverMemoryWithOverheadMiB}Mi")
    val maybeCpuLimitQuantity = driverLimitCores.map { limitCores =>
      ("cpu", new Quantity(limitCores))
    }

    val driverResourceQuantities =
      KubernetesUtils.buildResourcesQuantities(SPARK_DRIVER_PREFIX, conf.sparkConf)

    val driverPort = conf.sparkConf.getInt(DRIVER_PORT.key, DEFAULT_DRIVER_PORT)
    val driverBlockManagerPort = conf.sparkConf.getInt(
      DRIVER_BLOCK_MANAGER_PORT.key,
      conf.sparkConf.getInt(BLOCK_MANAGER_PORT.key, DEFAULT_BLOCKMANAGER_PORT)
    )
    val driverUIPort = SparkUI.getUIPort(conf.sparkConf)
    val driverContainer = new ContainerBuilder(pod.container)
      .withName(Option(pod.container.getName).getOrElse(DEFAULT_DRIVER_CONTAINER_NAME))
      .withImage(driverContainerImage)
      .withImagePullPolicy(conf.imagePullPolicy)
      .addNewPort()
        .withName(DRIVER_PORT_NAME)
        .withContainerPort(driverPort)
        .withProtocol("TCP")
        .endPort()
      .addNewPort()
        .withName(BLOCK_MANAGER_PORT_NAME)
        .withContainerPort(driverBlockManagerPort)
        .withProtocol("TCP")
        .endPort()
      .addNewPort()
        .withName(UI_PORT_NAME)
        .withContainerPort(driverUIPort)
        .withProtocol("TCP")
        .endPort()
      .addNewEnv()
        .withName(ENV_SPARK_USER)
        .withValue(Utils.getCurrentUserName())
        .endEnv()
      .addAllToEnv(driverCustomEnvs.asJava)
      .addNewEnv()
        .withName(ENV_DRIVER_BIND_ADDRESS)
        .withValueFrom(new EnvVarSourceBuilder()
          .withNewFieldRef("v1", "status.podIP")
          .build())
        .endEnv()
      .editOrNewResources()
        .addToRequests("cpu", driverCpuQuantity)
        .addToLimits(maybeCpuLimitQuantity.toMap.asJava)
        .addToRequests("memory", driverMemoryQuantity)
        .addToLimits("memory", driverMemoryQuantity)
        .addToLimits(driverResourceQuantities.asJava)
        .endResources()
      .build()

    val driverPod = new PodBuilder(pod.pod)
      .editOrNewMetadata()
        .withName(driverPodName)
        .addToLabels(conf.labels.asJava)
        .addToAnnotations(conf.annotations.asJava)
        .endMetadata()
      .editOrNewSpec()
        .withRestartPolicy("Never")
        .addToNodeSelector(conf.nodeSelector.asJava)
        .addToNodeSelector(conf.driverNodeSelector.asJava)
        .addToImagePullSecrets(conf.imagePullSecrets: _*)
        .endSpec()
      .build()

    conf.schedulerName
      .foreach(driverPod.getSpec.setSchedulerName)

    SparkPod(driverPod, driverContainer)
  }

  override def getAdditionalPodSystemProperties(): Map[String, String] = {
    val additionalProps = mutable.Map(
      KUBERNETES_DRIVER_POD_NAME.key -> driverPodName,
      "spark.app.id" -> conf.appId,
      KUBERNETES_DRIVER_SUBMIT_CHECK.key -> "true",
      MEMORY_OVERHEAD_FACTOR.key -> overheadFactor.toString)
    // try upload local, resolvable files to a hadoop compatible file system
    Seq(JARS, FILES, ARCHIVES, SUBMIT_PYTHON_FILES).foreach { key =>
      val uris = conf.get(key).filter(uri => KubernetesUtils.isLocalAndResolvable(uri))
      val value = {
        if (key == ARCHIVES) {
          uris.map(UriBuilder.fromUri(_).fragment(null).build()).map(_.toString)
        } else {
          uris
        }
      }
      val resolved = KubernetesUtils.uploadAndTransformFileUris(value, Some(conf.sparkConf))
      if (resolved.nonEmpty) {
        val resolvedValue = if (key == ARCHIVES) {
          uris.zip(resolved).map { case (uri, r) =>
            UriBuilder.fromUri(r).fragment(new java.net.URI(uri).getFragment).build().toString
          }
        } else {
          resolved
        }
        additionalProps.put(key.key, resolvedValue.mkString(","))
      }
    }
    additionalProps.toMap
  }
}

