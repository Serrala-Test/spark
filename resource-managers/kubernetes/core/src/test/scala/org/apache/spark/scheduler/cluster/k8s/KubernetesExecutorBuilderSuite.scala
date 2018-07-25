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
package org.apache.spark.scheduler.cluster.k8s

import io.fabric8.kubernetes.api.model.PodBuilder

import org.apache.spark.{SparkConf, SparkFunSuite}
<<<<<<< HEAD
import org.apache.spark.deploy.k8s.{KubernetesConf, KubernetesExecutorSpecificConf, SparkPod}
import org.apache.spark.deploy.k8s.features._
||||||| merged common ancestors
import org.apache.spark.deploy.k8s.{KubernetesConf, KubernetesExecutorSpecificConf, SparkPod}
import org.apache.spark.deploy.k8s.features.{BasicExecutorFeatureStep, EnvSecretsFeatureStep, KubernetesFeaturesTestUtils, LocalDirsFeatureStep, MountSecretsFeatureStep}
=======
import org.apache.spark.deploy.k8s._
import org.apache.spark.deploy.k8s.features._
>>>>>>> upstream/master

class KubernetesExecutorBuilderSuite extends SparkFunSuite {
  private val BASIC_STEP_TYPE = "basic"
  private val SECRETS_STEP_TYPE = "mount-secrets"
  private val MOUNT_LOCAL_FILES_STEP_TYPE = "mount-local-files"
  private val ENV_SECRETS_STEP_TYPE = "env-secrets"
  private val LOCAL_DIRS_STEP_TYPE = "local-dirs"
  private val MOUNT_VOLUMES_STEP_TYPE = "mount-volumes"

  private val basicFeatureStep = KubernetesFeaturesTestUtils.getMockConfigStepForStepType(
    BASIC_STEP_TYPE, classOf[BasicExecutorFeatureStep])
  private val mountSecretsStep = KubernetesFeaturesTestUtils.getMockConfigStepForStepType(
    SECRETS_STEP_TYPE, classOf[MountSecretsFeatureStep])
  private val mountLocalFilesStep = KubernetesFeaturesTestUtils.getMockConfigStepForStepType(
    MOUNT_LOCAL_FILES_STEP_TYPE, classOf[MountLocalFilesFeatureStep])
  private val envSecretsStep = KubernetesFeaturesTestUtils.getMockConfigStepForStepType(
    ENV_SECRETS_STEP_TYPE, classOf[EnvSecretsFeatureStep])
  private val localDirsStep = KubernetesFeaturesTestUtils.getMockConfigStepForStepType(
    LOCAL_DIRS_STEP_TYPE, classOf[LocalDirsFeatureStep])
  private val mountVolumesStep = KubernetesFeaturesTestUtils.getMockConfigStepForStepType(
    MOUNT_VOLUMES_STEP_TYPE, classOf[MountVolumesFeatureStep])

  private val builderUnderTest = new KubernetesExecutorBuilder(
    _ => basicFeatureStep,
    _ => mountSecretsStep,
    _ => mountLocalFilesStep,
    _ => envSecretsStep,
    _ => localDirsStep,
    _ => mountVolumesStep)

  test("Basic steps are consistently applied.") {
    val conf = KubernetesConf(
      new SparkConf(false),
      KubernetesExecutorSpecificConf(
        "executor-id", new PodBuilder().build()),
      "prefix",
      "appId",
      None,
      Map.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      Nil,
      Seq.empty[String])
    validateStepTypesApplied(
      builderUnderTest.buildFromFeatures(conf), BASIC_STEP_TYPE, LOCAL_DIRS_STEP_TYPE)
  }

  test("Apply secrets step if secrets are present.") {
    val conf = KubernetesConf(
      new SparkConf(false),
      KubernetesExecutorSpecificConf(
        "executor-id", new PodBuilder().build()),
      "prefix",
      "appId",
      None,
      Map.empty,
      Map.empty,
      Map("secret" -> "secretMountPath"),
      Map("secret-name" -> "secret-key"),
      Map.empty,
      Nil,
      Seq.empty[String])
    validateStepTypesApplied(
      builderUnderTest.buildFromFeatures(conf),
      BASIC_STEP_TYPE,
      LOCAL_DIRS_STEP_TYPE,
      SECRETS_STEP_TYPE,
      ENV_SECRETS_STEP_TYPE)
  }

<<<<<<< HEAD
  test("Apply mount local files step if secret name is present.") {
    val conf = KubernetesConf(
      new SparkConf(false),
      KubernetesExecutorSpecificConf(
        "executor-id", new PodBuilder().build()),
      "prefix",
      "appId",
      Some("secret"),
      Map.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      Seq.empty[String])
    validateStepTypesApplied(
      builderUnderTest.buildFromFeatures(conf),
      BASIC_STEP_TYPE,
      LOCAL_DIRS_STEP_TYPE,
      MOUNT_LOCAL_FILES_STEP_TYPE)
  }

||||||| merged common ancestors
=======
  test("Apply volumes step if mounts are present.") {
    val volumeSpec = KubernetesVolumeSpec(
      "volume",
      "/tmp",
      false,
      KubernetesHostPathVolumeConf("/checkpoint"))
    val conf = KubernetesConf(
      new SparkConf(false),
      KubernetesExecutorSpecificConf(
        "executor-id", new PodBuilder().build()),
      "prefix",
      "appId",
      Map.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      volumeSpec :: Nil,
      Seq.empty[String])
    validateStepTypesApplied(
      builderUnderTest.buildFromFeatures(conf),
      BASIC_STEP_TYPE,
      LOCAL_DIRS_STEP_TYPE,
      MOUNT_VOLUMES_STEP_TYPE)
  }

>>>>>>> upstream/master
  private def validateStepTypesApplied(resolvedPod: SparkPod, stepTypes: String*): Unit = {
    assert(resolvedPod.pod.getMetadata.getLabels.size === stepTypes.size)
    stepTypes.foreach { stepType =>
      assert(resolvedPod.pod.getMetadata.getLabels.get(stepType) === stepType)
    }
  }
}
