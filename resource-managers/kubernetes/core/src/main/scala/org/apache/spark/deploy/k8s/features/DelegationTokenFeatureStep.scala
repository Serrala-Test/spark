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

import io.fabric8.kubernetes.api.model.{ContainerBuilder, HasMetadata, PodBuilder, SecretBuilder}
import org.apache.commons.codec.binary.Base64
import org.apache.hadoop.security.UserGroupInformation

import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.deploy.k8s.{KubernetesConf, KubernetesUtils, SparkPod}
import org.apache.spark.deploy.k8s.Config._
import org.apache.spark.deploy.k8s.Constants._
import org.apache.spark.deploy.security.HadoopDelegationTokenManager
import org.apache.spark.internal.config._

/**
 * Delegation token support for Spark apps on kubernetes.
 *
 * When preparing driver resources, this step will generate delegation tokens for the app if
 * they're needed.
 *
 * When preparing pods, this step will mount the delegation token secret (either pre-defined,
 * or generated by this step when preparing the driver).
 */
private[spark] class DelegationTokenFeatureStep(conf: KubernetesConf[_], isDriver: Boolean)
  extends KubernetesFeatureConfigStep {

  private val existingSecret = conf.get(KUBERNETES_KERBEROS_DT_SECRET_NAME)
  private val existingItemKey = conf.get(KUBERNETES_KERBEROS_DT_SECRET_ITEM_KEY)
  private val shouldCreateTokens = isDriver && !conf.sparkConf.contains(KEYTAB) &&
    existingSecret.isEmpty && UserGroupInformation.isSecurityEnabled()

  KubernetesUtils.requireBothOrNeitherDefined(
    existingSecret,
    existingItemKey,
    "If a secret data item-key where the data of the Kerberos Delegation Token is specified" +
      " you must also specify the name of the secret",
    "If a secret storing a Kerberos Delegation Token is specified you must also" +
      " specify the item-key where the data is stored")

  private def dtSecretName: String = s"${conf.appResourceNamePrefix}-delegation-tokens"

  override def configurePod(pod: SparkPod): SparkPod = {
    pod.transform { case pod if shouldCreateTokens | existingSecret.isDefined =>
      val secretName = existingSecret.getOrElse(dtSecretName)
      val itemKey = existingItemKey.getOrElse(KERBEROS_SECRET_KEY)

      val podWithTokens = new PodBuilder(pod.pod)
        .editOrNewSpec()
          .addNewVolume()
            .withName(SPARK_APP_HADOOP_SECRET_VOLUME_NAME)
            .withNewSecret()
              .withSecretName(secretName)
              .endSecret()
            .endVolume()
          .endSpec()
        .build()

      val containerWithTokens = new ContainerBuilder(pod.container)
        .addNewVolumeMount()
          .withName(SPARK_APP_HADOOP_SECRET_VOLUME_NAME)
          .withMountPath(SPARK_APP_HADOOP_CREDENTIALS_BASE_DIR)
          .endVolumeMount()
        .addNewEnv()
          .withName(ENV_HADOOP_TOKEN_FILE_LOCATION)
          .withValue(s"$SPARK_APP_HADOOP_CREDENTIALS_BASE_DIR/$itemKey")
          .endEnv()
        .build()

      SparkPod(podWithTokens, containerWithTokens)
    }
  }

  override def getAdditionalKubernetesResources(): Seq[HasMetadata] = {
    if (shouldCreateTokens) {
      val tokenManager = new HadoopDelegationTokenManager(conf.sparkConf,
        SparkHadoopUtil.get.newConfiguration(conf.sparkConf))
      val creds = UserGroupInformation.getCurrentUser().getCredentials()
      tokenManager.obtainDelegationTokens(creds)
      val tokenData = SparkHadoopUtil.get.serialize(creds)
      Seq(new SecretBuilder()
        .withNewMetadata()
          .withName(dtSecretName)
          .endMetadata()
        .addToData(KERBEROS_SECRET_KEY, Base64.encodeBase64String(tokenData))
        .build())
    } else {
      Nil
    }
  }

}
