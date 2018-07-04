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

import scala.collection.JavaConverters._
import scala.collection.mutable

import io.fabric8.kubernetes.api.model._

import org.apache.spark.SparkException
import org.apache.spark.deploy.k8s._
import org.apache.spark.deploy.k8s.Config._
import org.apache.spark.deploy.k8s.Constants._
import org.apache.spark.deploy.k8s.submit._
import org.apache.spark.internal.config._

private[spark] class BasicDriverFeatureStep(
    conf: KubernetesConf[KubernetesDriverSpecificConf])
  extends KubernetesFeatureConfigStep {

  private val driverPodNamePrefix = conf
    .get(KUBERNETES_DRIVER_POD_NAME_PREFIX)
    .getOrElse(s"${conf.appResourceNamePrefix}-driver")

  private val driverContainerImage = conf
    .get(DRIVER_CONTAINER_IMAGE)
    .getOrElse(throw new SparkException("Must specify the driver container image"))

  // CPU settings
  private val driverCpuCores = conf.get("spark.driver.cores", "1")
  private val driverLimitCores = conf.get(KUBERNETES_DRIVER_LIMIT_CORES)

  // Memory settings
  private val driverMemoryMiB = conf.get(DRIVER_MEMORY)
  private val memoryOverheadMiB = conf
    .get(DRIVER_MEMORY_OVERHEAD)
    .getOrElse(math.max((conf.get(MEMORY_OVERHEAD_FACTOR) * driverMemoryMiB).toInt,
      MEMORY_OVERHEAD_MIN_MIB))
  private val driverMemoryWithOverheadMiB = driverMemoryMiB + memoryOverheadMiB

  override def configurePod(pod: SparkPod): SparkPod = {
    val driverCustomEnvs = conf.roleEnvs
      .toSeq
      .map { env =>
        new EnvVarBuilder()
          .withName(env._1)
          .withValue(env._2)
          .build()
      }

    val driverCpuQuantity = new QuantityBuilder(false)
      .withAmount(driverCpuCores)
      .build()
    val driverMemoryQuantity = new QuantityBuilder(false)
      .withAmount(s"${driverMemoryWithOverheadMiB}Mi")
      .build()
    val maybeCpuLimitQuantity = driverLimitCores.map { limitCores =>
      ("cpu", new QuantityBuilder(false).withAmount(limitCores).build())
    }

    val driverContainer = new ContainerBuilder(pod.container)
      .withName(DRIVER_CONTAINER_NAME)
      .withImage(driverContainerImage)
      .withImagePullPolicy(conf.imagePullPolicy())
      .addAllToEnv(driverCustomEnvs.asJava)
      .addNewEnv()
        .withName(ENV_DRIVER_BIND_ADDRESS)
        .withValueFrom(new EnvVarSourceBuilder()
          .withNewFieldRef("v1", "status.podIP")
          .build())
        .endEnv()
      .withNewResources()
        .addToRequests("cpu", driverCpuQuantity)
        .addToLimits(maybeCpuLimitQuantity.toMap.asJava)
        .addToRequests("memory", driverMemoryQuantity)
        .addToLimits("memory", driverMemoryQuantity)
        .endResources()
      .build()

    val driverPod = new PodBuilder(pod.pod)
      .editOrNewMetadata()
        .withName(driverPodNamePrefix)
        .addToLabels(conf.roleLabels.asJava)
        .addToAnnotations(conf.roleAnnotations.asJava)
        .endMetadata()
      .withNewSpec()
        .withRestartPolicy("Never")
        .withNodeSelector(conf.nodeSelector().asJava)
        .addToImagePullSecrets(conf.imagePullSecrets(): _*)
        .endSpec()
      .build()

    SparkPod(driverPod, driverContainer)
  }

  override def getAdditionalPodSystemProperties(): Map[String, String] = {
    val additionalProps = mutable.Map(
      KUBERNETES_DRIVER_POD_NAME_PREFIX.key -> driverPodNamePrefix,
      "spark.app.id" -> conf.appId,
      KUBERNETES_EXECUTOR_POD_NAME_PREFIX.key -> conf.appResourceNamePrefix,
      KUBERNETES_DRIVER_SUBMIT_CHECK.key -> "true")

    val resolvedSparkJars = KubernetesUtils.resolveFileUrisAndPath(
      conf.sparkJars())
    val resolvedSparkFiles = KubernetesUtils.resolveFileUrisAndPath(
      conf.sparkFiles)
    if (resolvedSparkJars.nonEmpty) {
      additionalProps.put("spark.jars", resolvedSparkJars.mkString(","))
    }
    if (resolvedSparkFiles.nonEmpty) {
      additionalProps.put("spark.files", resolvedSparkFiles.mkString(","))
    }
    additionalProps.toMap
  }

}
