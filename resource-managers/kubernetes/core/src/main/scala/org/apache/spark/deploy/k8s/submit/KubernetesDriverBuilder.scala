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
package org.apache.spark.deploy.k8s.submit

<<<<<<< HEAD
import java.io.File

import org.apache.spark.deploy.k8s._
import org.apache.spark.deploy.k8s.features._
||||||| merged common ancestors
import org.apache.spark.deploy.k8s.{KubernetesConf, KubernetesDriverSpec, KubernetesDriverSpecificConf, KubernetesRoleSpecificConf}
import org.apache.spark.deploy.k8s.features.{BasicDriverFeatureStep, DriverKubernetesCredentialsFeatureStep, DriverServiceFeatureStep, EnvSecretsFeatureStep, KubernetesFeatureConfigStep, LocalDirsFeatureStep, MountSecretsFeatureStep}
=======
import org.apache.spark.deploy.k8s.{KubernetesConf, KubernetesDriverSpec, KubernetesDriverSpecificConf, KubernetesRoleSpecificConf}
import org.apache.spark.deploy.k8s.features._
>>>>>>> upstream/master
import org.apache.spark.deploy.k8s.features.bindings.{JavaDriverFeatureStep, PythonDriverFeatureStep}
import org.apache.spark.util.Utils

private[spark] class KubernetesDriverBuilder(
    provideBasicStep: (KubernetesConf[KubernetesDriverSpecificConf]) => BasicDriverFeatureStep =
      new BasicDriverFeatureStep(_),
    provideCredentialsStep: (KubernetesConf[KubernetesDriverSpecificConf])
      => DriverKubernetesCredentialsFeatureStep =
      new DriverKubernetesCredentialsFeatureStep(_),
    provideServiceStep: (KubernetesConf[KubernetesDriverSpecificConf]) => DriverServiceFeatureStep =
      new DriverServiceFeatureStep(_),
    provideSecretsStep: (KubernetesConf[_ <: KubernetesRoleSpecificConf]
      => MountSecretsFeatureStep) =
      new MountSecretsFeatureStep(_),
    provideEnvSecretsStep: (KubernetesConf[_ <: KubernetesRoleSpecificConf]
      => EnvSecretsFeatureStep) =
<<<<<<< HEAD
      new EnvSecretsFeatureStep(_),
    provideLocalDirsStep: (KubernetesConf[_ <: KubernetesRoleSpecificConf]
      => LocalDirsFeatureStep) =
||||||| merged common ancestors
    new EnvSecretsFeatureStep(_),
    provideLocalDirsStep: (KubernetesConf[_ <: KubernetesRoleSpecificConf]
      => LocalDirsFeatureStep) =
=======
      new EnvSecretsFeatureStep(_),
    provideLocalDirsStep: (KubernetesConf[_ <: KubernetesRoleSpecificConf])
      => LocalDirsFeatureStep =
>>>>>>> upstream/master
      new LocalDirsFeatureStep(_),
<<<<<<< HEAD
    provideMountLocalFilesStep: (KubernetesConf[_ <: KubernetesRoleSpecificConf]
      => MountLocalFilesFeatureStep) =
      new MountLocalFilesFeatureStep(_),
    provideJavaStep: (KubernetesConf[KubernetesDriverSpecificConf] => JavaDriverFeatureStep) =
||||||| merged common ancestors
    provideJavaStep: (
      KubernetesConf[KubernetesDriverSpecificConf]
        => JavaDriverFeatureStep) =
=======
    provideVolumesStep: (KubernetesConf[_ <: KubernetesRoleSpecificConf]
      => MountVolumesFeatureStep) =
      new MountVolumesFeatureStep(_),
    provideJavaStep: (
      KubernetesConf[KubernetesDriverSpecificConf]
        => JavaDriverFeatureStep) =
>>>>>>> upstream/master
      new JavaDriverFeatureStep(_),
    providePythonStep: (KubernetesConf[KubernetesDriverSpecificConf] => PythonDriverFeatureStep) =
      new PythonDriverFeatureStep(_)) {

  import KubernetesDriverBuilder._

  def buildFromFeatures(
    kubernetesConf: KubernetesConf[KubernetesDriverSpecificConf]): KubernetesDriverSpec = {
    val baseFeatures = Seq(
      provideBasicStep(kubernetesConf),
      provideCredentialsStep(kubernetesConf),
      provideServiceStep(kubernetesConf),
      provideLocalDirsStep(kubernetesConf))

    val secretFeature = if (kubernetesConf.roleSecretNamesToMountPaths.nonEmpty) {
      Seq(provideSecretsStep(kubernetesConf))
    } else Nil
    val envSecretFeature = if (kubernetesConf.roleSecretEnvNamesToKeyRefs.nonEmpty) {
      Seq(provideEnvSecretsStep(kubernetesConf))
    } else Nil
    val volumesFeature = if (kubernetesConf.roleVolumes.nonEmpty) {
      Seq(provideVolumesStep(kubernetesConf))
    } else Nil

    val bindingsStep = kubernetesConf.roleSpecificConf.mainAppResource.map {
        case JavaMainAppResource(_) =>
          provideJavaStep(kubernetesConf)
        case PythonMainAppResource(_) =>
<<<<<<< HEAD
          providePythonStep(kubernetesConf)}
      .getOrElse(provideJavaStep(kubernetesConf))

    val localFiles = KubernetesUtils.submitterLocalFiles(kubernetesConf.sparkFiles)
      .map(new File(_))
    require(localFiles.forall(_.isFile), s"All submitted local files must be present and not" +
      s" directories, Got got: ${localFiles.map(_.getAbsolutePath).mkString(",")}")

    val totalFileSize = localFiles.map(_.length()).sum
    val totalSizeBytesString = Utils.bytesToString(totalFileSize)
    require(totalFileSize < MAX_SECRET_BUNDLE_SIZE_BYTES,
      s"Total size of all files submitted must be less than $MAX_SECRET_BUNDLE_SIZE_BYTES_STRING." +
        s" Total size for files ended up being $totalSizeBytesString")
    val providedLocalFiles = if (localFiles.nonEmpty) {
      Some(provideMountLocalFilesStep(kubernetesConf))
    } else None
||||||| merged common ancestors
          providePythonStep(kubernetesConf)}.getOrElse(provideJavaStep(kubernetesConf))
=======
          providePythonStep(kubernetesConf)}
      .getOrElse(provideJavaStep(kubernetesConf))
>>>>>>> upstream/master

<<<<<<< HEAD
    val allFeatures: Seq[KubernetesFeatureConfigStep] =
      (baseFeatures :+ bindingsStep) ++
        maybeRoleSecretNamesStep.toSeq ++
        maybeProvideSecretsStep.toSeq ++
        providedLocalFiles.toSeq
||||||| merged common ancestors
    val allFeatures: Seq[KubernetesFeatureConfigStep] =
      (baseFeatures :+ bindingsStep) ++
        maybeRoleSecretNamesStep.toSeq ++
        maybeProvideSecretsStep.toSeq
=======
    val allFeatures = (baseFeatures :+ bindingsStep) ++
      secretFeature ++ envSecretFeature ++ volumesFeature
>>>>>>> upstream/master

    var spec = KubernetesDriverSpec.initialSpec(kubernetesConf.sparkConf.getAll.toMap)
    for (feature <- allFeatures) {
      val configuredPod = feature.configurePod(spec.pod)
      val addedSystemProperties = feature.getAdditionalPodSystemProperties()
      val addedResources = feature.getAdditionalKubernetesResources()
      spec = KubernetesDriverSpec(
        configuredPod,
        spec.driverKubernetesResources ++ addedResources,
        spec.systemProperties ++ addedSystemProperties)
    }
    spec
  }
}

private object KubernetesDriverBuilder {
  val MAX_SECRET_BUNDLE_SIZE_BYTES = 20480
  val MAX_SECRET_BUNDLE_SIZE_BYTES_STRING =
    Utils.bytesToString(MAX_SECRET_BUNDLE_SIZE_BYTES)
}
