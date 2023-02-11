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

import java.io.Closeable

import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.{KubernetesClient, Watcher, WatcherException}
import io.fabric8.kubernetes.client.Watcher.Action

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.annotation.{DeveloperApi, Since, Stable}
import org.apache.spark.deploy.k8s.Config.KUBERNETES_EXECUTOR_ENABLE_API_WATCHER
import org.apache.spark.deploy.k8s.Config.KUBERNETES_EXECUTOR_ENABLE_WATCHER_AUTO_RESET
import org.apache.spark.deploy.k8s.Config.KUBERNETES_NAMESPACE
import org.apache.spark.deploy.k8s.Constants._
import org.apache.spark.internal.Logging
import org.apache.spark.util.Utils

/**
 * :: DeveloperApi ::
 *
 * A class used for watching K8s executor pods by ExternalClusterManagers.
 *
 * @since 3.1.3
 */
@Stable
@DeveloperApi
class ExecutorPodsWatchSnapshotSource(
    snapshotsStore: ExecutorPodsSnapshotsStore,
    kubernetesClient: KubernetesClient,
    conf: SparkConf) extends Logging {

  private var watchConnection: Closeable = _
  private var watchApplicationId: String = null
  private val enableWatching = conf.get(KUBERNETES_EXECUTOR_ENABLE_API_WATCHER)
  private val enableWatchingAutoReset = conf.get(KUBERNETES_EXECUTOR_ENABLE_WATCHER_AUTO_RESET)

  private val namespace = conf.get(KUBERNETES_NAMESPACE)

  // If we're constructed with the old API get the SparkConf from the running SparkContext.
  def this(snapshotsStore: ExecutorPodsSnapshotsStore, kubernetesClient: KubernetesClient) = {
    this(snapshotsStore, kubernetesClient, SparkContext.getOrCreate().conf)
  }

  @Since("3.1.3")
  def start(applicationId: String): Unit = {
    if (enableWatching) {
      require(watchConnection == null, "Cannot start the watcher twice.")
      logDebug(s"Starting to watch for pods with labels $SPARK_APP_ID_LABEL=$applicationId," +
        s" $SPARK_ROLE_LABEL=$SPARK_POD_EXECUTOR_ROLE.")
      watchApplicationId = applicationId
      watchConnection = kubernetesClient.pods()
        .inNamespace(namespace)
        .withLabel(SPARK_APP_ID_LABEL, applicationId)
        .withLabel(SPARK_ROLE_LABEL, SPARK_POD_EXECUTOR_ROLE)
        .watch(new ExecutorPodsWatcher())
    }
  }

  @Since("3.1.3")
  def stop(): Unit = {
    if (watchConnection != null) {
      Utils.tryLogNonFatalError {
        watchConnection.close()
      }
      watchConnection = null
    }
  }

  def reset(watcher: ExecutorPodsWatcher): Unit = {
    stop()
    watchConnection = kubernetesClient.pods()
      .inNamespace(namespace)
      .withLabel(SPARK_APP_ID_LABEL, watchApplicationId)
      .withLabel(SPARK_ROLE_LABEL, SPARK_POD_EXECUTOR_ROLE)
      .watch(watcher)
  }

  private class ExecutorPodsWatcher extends Watcher[Pod] {
    override def eventReceived(action: Action, pod: Pod): Unit = {
      val podName = pod.getMetadata.getName
      logDebug(s"Received executor pod update for pod named $podName, action $action")
      snapshotsStore.updatePod(pod)
    }

    override def onClose(e: WatcherException): Unit = {
      if (enableWatchingAutoReset && e != null && e.isHttpGone) {
        logDebug(s"Got HTTP Gone code, should be reset watcher connection, " +
          s"resource version changed in k8s api: $e")
        reset(this)
      } else {
        logWarning("Kubernetes client has been closed (this is expected if the application is" +
          " shutting down.)", e)
      }
    }

    override def onClose(): Unit = {
      logWarning("Kubernetes client has been closed.")
    }
  }

}
