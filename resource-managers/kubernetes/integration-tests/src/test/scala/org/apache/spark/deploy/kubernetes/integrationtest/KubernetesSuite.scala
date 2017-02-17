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
package org.apache.spark.deploy.kubernetes.integrationtest

import java.io.File
import java.nio.file.Paths
import java.util.UUID

import com.google.common.base.Charsets
import com.google.common.collect.ImmutableList
import com.google.common.io.Files
import com.google.common.util.concurrent.SettableFuture
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientException, Watcher}
import io.fabric8.kubernetes.client.Watcher.Action
import org.scalatest.BeforeAndAfter
import org.scalatest.concurrent.{Eventually, PatienceConfiguration}
import org.scalatest.time.{Minutes, Seconds, Span}
import scala.collection.JavaConverters._

import org.apache.spark.{SparkConf, SparkException, SparkFunSuite}
import org.apache.spark.deploy.SparkSubmit
import org.apache.spark.deploy.kubernetes.Client
import org.apache.spark.deploy.kubernetes.integrationtest.docker.SparkDockerImageBuilder
import org.apache.spark.deploy.kubernetes.integrationtest.minikube.Minikube
import org.apache.spark.deploy.kubernetes.integrationtest.restapis.SparkRestApiV1
import org.apache.spark.deploy.kubernetes.integrationtest.sslutil.SSLUtils
import org.apache.spark.status.api.v1.{ApplicationStatus, StageStatus}
import org.apache.spark.util.Utils

private[spark] class KubernetesSuite extends SparkFunSuite with BeforeAndAfter {

  private val EXAMPLES_JAR_FILE = Paths.get("target", "integration-tests-spark-jobs")
    .toFile
    .listFiles()(0)

  private val HELPER_JAR_FILE = Paths.get("target", "integration-tests-spark-jobs-helpers")
      .toFile
      .listFiles()(0)

  private val TEST_EXISTENCE_FILE = Paths.get("test-data", "input.txt").toFile
  private val TEST_EXISTENCE_FILE_CONTENTS = Files.toString(TEST_EXISTENCE_FILE, Charsets.UTF_8)
  private val TIMEOUT = PatienceConfiguration.Timeout(Span(2, Minutes))
  private val INTERVAL = PatienceConfiguration.Interval(Span(2, Seconds))
  private val SPARK_PI_MAIN_CLASS = "org.apache.spark.deploy.kubernetes" +
    ".integrationtest.jobs.SparkPiWithInfiniteWait"
  private val FILE_EXISTENCE_MAIN_CLASS = "org.apache.spark.deploy.kubernetes" +
    ".integrationtest.jobs.FileExistenceTest"
  private val NAMESPACE = UUID.randomUUID().toString.replaceAll("-", "")
  private var minikubeKubernetesClient: KubernetesClient = _
  private var clientConfig: Config = _
  private var keyStoreFile: File = _
  private var trustStoreFile: File = _

  override def beforeAll(): Unit = {
    Minikube.startMinikube()
    new SparkDockerImageBuilder(Minikube.getDockerEnv).buildSparkDockerImages()
    Minikube.getKubernetesClient.namespaces.createNew()
      .withNewMetadata()
        .withName(NAMESPACE)
        .endMetadata()
      .done()
    minikubeKubernetesClient = Minikube.getKubernetesClient.inNamespace(NAMESPACE)
    clientConfig = minikubeKubernetesClient.getConfiguration
    val (keyStore, trustStore) = SSLUtils.generateKeyStoreTrustStorePair(
      Minikube.getMinikubeIp,
      "changeit",
      "changeit",
      "changeit")
    keyStoreFile = keyStore
    trustStoreFile = trustStore
  }

  before {
    Eventually.eventually(TIMEOUT, INTERVAL) {
      val podsList = minikubeKubernetesClient.pods().list()
      assert(podsList == null
        || podsList.getItems == null
        || podsList.getItems.isEmpty
      )
      val servicesList = minikubeKubernetesClient.services().list()
      assert(servicesList == null
        || servicesList.getItems == null
        || servicesList.getItems.isEmpty)
    }
  }

  after {
    val pods = minikubeKubernetesClient.pods().list().getItems.asScala
    pods.par.foreach(pod => {
      minikubeKubernetesClient
        .pods()
        .withName(pod.getMetadata.getName)
        .withGracePeriod(60)
        .delete
    })
  }

  override def afterAll(): Unit = {
    if (!System.getProperty("spark.docker.test.persistMinikube", "false").toBoolean) {
      Minikube.deleteMinikube()
    }
  }

  private def getSparkMetricsService(sparkBaseAppName: String): SparkRestApiV1 = {
    val serviceName = minikubeKubernetesClient.services()
      .withLabel("spark-app-name", sparkBaseAppName)
      .list()
      .getItems
      .get(0)
      .getMetadata
      .getName
    Minikube.getService[SparkRestApiV1](serviceName, NAMESPACE, "spark-ui-port")
  }

  private def expectationsForStaticAllocation(sparkMetricsService: SparkRestApiV1): Unit = {
    val apps = Eventually.eventually(TIMEOUT, INTERVAL) {
      val result = sparkMetricsService
        .getApplications(ImmutableList.of(ApplicationStatus.RUNNING, ApplicationStatus.COMPLETED))
      assert(result.size == 1
        && !result.head.id.equalsIgnoreCase("appid")
        && !result.head.id.equalsIgnoreCase("{appId}"))
      result
    }
    Eventually.eventually(TIMEOUT, INTERVAL) {
      val result = sparkMetricsService.getExecutors(apps.head.id)
      assert(result.size == 2)
      assert(result.count(exec => exec.id != "driver") == 1)
      result
    }
    Eventually.eventually(TIMEOUT, INTERVAL) {
      val result = sparkMetricsService.getStages(
        apps.head.id, Seq(StageStatus.COMPLETE).asJava)
      assert(result.size == 1)
      result
    }
  }

  test("Run a simple example") {
    // We'll make assertions based on spark rest api, so we need to turn on
    // spark.ui.enabled explicitly since the scalatest-maven-plugin would set it
    // to false by default.
    val sparkConf = new SparkConf(true)
      .setMaster(s"k8s://https://${Minikube.getMinikubeIp}:8443")
      .set("spark.kubernetes.submit.caCertFile", clientConfig.getCaCertFile)
      .set("spark.kubernetes.submit.clientKeyFile", clientConfig.getClientKeyFile)
      .set("spark.kubernetes.submit.clientCertFile", clientConfig.getClientCertFile)
      .set("spark.kubernetes.namespace", NAMESPACE)
      .set("spark.kubernetes.driver.docker.image", "spark-driver:latest")
      .set("spark.kubernetes.executor.docker.image", "spark-executor:latest")
      .set("spark.jars", HELPER_JAR_FILE.getAbsolutePath)
      .set("spark.executor.memory", "500m")
      .set("spark.executor.cores", "1")
      .set("spark.executors.instances", "1")
      .set("spark.app.name", "spark-pi")
      .set("spark.ui.enabled", "true")
      .set("spark.testing", "false")
      .set("spark.kubernetes.submit.waitAppCompletion", "false")
    val mainAppResource = s"file://${EXAMPLES_JAR_FILE.getAbsolutePath}"

    new Client(
      sparkConf = sparkConf,
      mainClass = SPARK_PI_MAIN_CLASS,
      mainAppResource = mainAppResource,
      appArgs = Array.empty[String]).run()
    val sparkMetricsService = getSparkMetricsService("spark-pi")
    expectationsForStaticAllocation(sparkMetricsService)
  }

  test("Run using spark-submit") {
    val args = Array(
      "--master", s"k8s://https://${Minikube.getMinikubeIp}:8443",
      "--deploy-mode", "cluster",
      "--kubernetes-namespace", NAMESPACE,
      "--name", "spark-pi",
      "--executor-memory", "512m",
      "--executor-cores", "1",
      "--num-executors", "1",
      "--jars", HELPER_JAR_FILE.getAbsolutePath,
      "--class", SPARK_PI_MAIN_CLASS,
      "--conf", "spark.ui.enabled=true",
      "--conf", "spark.testing=false",
      "--conf", s"spark.kubernetes.submit.caCertFile=${clientConfig.getCaCertFile}",
      "--conf", s"spark.kubernetes.submit.clientKeyFile=${clientConfig.getClientKeyFile}",
      "--conf", s"spark.kubernetes.submit.clientCertFile=${clientConfig.getClientCertFile}",
      "--conf", "spark.kubernetes.executor.docker.image=spark-executor:latest",
      "--conf", "spark.kubernetes.driver.docker.image=spark-driver:latest",
      "--conf", "spark.kubernetes.submit.waitAppCompletion=false",
      EXAMPLES_JAR_FILE.getAbsolutePath)
    SparkSubmit.main(args)
    val sparkMetricsService = getSparkMetricsService("spark-pi")
    expectationsForStaticAllocation(sparkMetricsService)
  }

  test("Run using spark-submit with the examples jar on the docker image") {
    val args = Array(
      "--master", s"k8s://${Minikube.getMinikubeIp}:8443",
      "--deploy-mode", "cluster",
      "--kubernetes-namespace", NAMESPACE,
      "--name", "spark-pi",
      "--executor-memory", "512m",
      "--executor-cores", "1",
      "--num-executors", "1",
      "--jars", s"local:///opt/spark/examples/integration-tests-jars/${HELPER_JAR_FILE.getName}",
      "--class", SPARK_PI_MAIN_CLASS,
      "--conf", s"spark.kubernetes.submit.caCertFile=${clientConfig.getCaCertFile}",
      "--conf", s"spark.kubernetes.submit.clientKeyFile=${clientConfig.getClientKeyFile}",
      "--conf", s"spark.kubernetes.submit.clientCertFile=${clientConfig.getClientCertFile}",
      "--conf", "spark.kubernetes.executor.docker.image=spark-executor:latest",
      "--conf", "spark.kubernetes.driver.docker.image=spark-driver:latest",
      "--conf", "spark.kubernetes.submit.waitAppCompletion=false",
      s"local:///opt/spark/examples/integration-tests-jars/${EXAMPLES_JAR_FILE.getName}")
    SparkSubmit.main(args)
    val sparkMetricsService = getSparkMetricsService("spark-pi")
    expectationsForStaticAllocation(sparkMetricsService)
  }

  test("Run with custom labels") {
    val args = Array(
      "--master", s"k8s://https://${Minikube.getMinikubeIp}:8443",
      "--deploy-mode", "cluster",
      "--kubernetes-namespace", NAMESPACE,
      "--name", "spark-pi",
      "--executor-memory", "512m",
      "--executor-cores", "1",
      "--num-executors", "1",
      "--jars", HELPER_JAR_FILE.getAbsolutePath,
      "--class", SPARK_PI_MAIN_CLASS,
      "--conf", s"spark.kubernetes.submit.caCertFile=${clientConfig.getCaCertFile}",
      "--conf", s"spark.kubernetes.submit.clientKeyFile=${clientConfig.getClientKeyFile}",
      "--conf", s"spark.kubernetes.submit.clientCertFile=${clientConfig.getClientCertFile}",
      "--conf", "spark.kubernetes.executor.docker.image=spark-executor:latest",
      "--conf", "spark.kubernetes.driver.docker.image=spark-driver:latest",
      "--conf", "spark.kubernetes.driver.labels=label1=label1value,label2=label2value",
      "--conf", "spark.kubernetes.submit.waitAppCompletion=false",
      EXAMPLES_JAR_FILE.getAbsolutePath)
    SparkSubmit.main(args)
    val driverPodLabels = minikubeKubernetesClient
      .pods
      .withLabel("spark-app-name", "spark-pi")
      .list()
      .getItems
      .get(0)
      .getMetadata
      .getLabels
    // We can't match all of the selectors directly since one of the selectors is based on the
    // launch time.
    assert(driverPodLabels.size == 5, "Unexpected number of pod labels.")
    assert(driverPodLabels.get("spark-app-name") == "spark-pi", "Unexpected value for" +
      " spark-app-name label.")
    assert(driverPodLabels.get("spark-app-id").startsWith("spark-pi"), "Unexpected value for" +
      " spark-app-id label (should be prefixed with the app name).")
    assert(driverPodLabels.get("label1") == "label1value", "Unexpected value for label1")
    assert(driverPodLabels.get("label2") == "label2value", "Unexpected value for label2")
  }

  test("Enable SSL on the driver submit server") {
    val args = Array(
      "--master", s"k8s://https://${Minikube.getMinikubeIp}:8443",
      "--deploy-mode", "cluster",
      "--kubernetes-namespace", NAMESPACE,
      "--name", "spark-pi",
      "--executor-memory", "512m",
      "--executor-cores", "1",
      "--num-executors", "1",
      "--jars", HELPER_JAR_FILE.getAbsolutePath,
      "--class", SPARK_PI_MAIN_CLASS,
      "--conf", s"spark.kubernetes.submit.caCertFile=${clientConfig.getCaCertFile}",
      "--conf", s"spark.kubernetes.submit.clientKeyFile=${clientConfig.getClientKeyFile}",
      "--conf", s"spark.kubernetes.submit.clientCertFile=${clientConfig.getClientCertFile}",
      "--conf", "spark.kubernetes.executor.docker.image=spark-executor:latest",
      "--conf", "spark.kubernetes.driver.docker.image=spark-driver:latest",
      "--conf", "spark.ssl.kubernetes.submit.enabled=true",
      "--conf", "spark.ssl.kubernetes.submit.keyStore=" +
        s"file://${keyStoreFile.getAbsolutePath}",
      "--conf", "spark.ssl.kubernetes.submit.keyStorePassword=changeit",
      "--conf", "spark.ssl.kubernetes.submit.keyPassword=changeit",
      "--conf", "spark.ssl.kubernetes.submit.trustStore=" +
        s"file://${trustStoreFile.getAbsolutePath}",
      "--conf", s"spark.ssl.kubernetes.driverlaunch.trustStorePassword=changeit",
      "--conf", "spark.kubernetes.submit.waitAppCompletion=false",
      EXAMPLES_JAR_FILE.getAbsolutePath)
    SparkSubmit.main(args)
  }

  test("Added files should exist on the driver.") {
    val args = Array(
      "--master", s"k8s://https://${Minikube.getMinikubeIp}:8443",
      "--deploy-mode", "cluster",
      "--kubernetes-namespace", NAMESPACE,
      "--name", "spark-file-existence-test",
      "--executor-memory", "512m",
      "--executor-cores", "1",
      "--num-executors", "1",
      "--jars", HELPER_JAR_FILE.getAbsolutePath,
      "--files", TEST_EXISTENCE_FILE.getAbsolutePath,
      "--class", FILE_EXISTENCE_MAIN_CLASS,
      "--conf", "spark.ui.enabled=false",
      "--conf", "spark.testing=true",
      "--conf", s"spark.kubernetes.submit.caCertFile=${clientConfig.getCaCertFile}",
      "--conf", s"spark.kubernetes.submit.clientKeyFile=${clientConfig.getClientKeyFile}",
      "--conf", s"spark.kubernetes.submit.clientCertFile=${clientConfig.getClientCertFile}",
      "--conf", "spark.kubernetes.executor.docker.image=spark-executor:latest",
      "--conf", "spark.kubernetes.driver.docker.image=spark-driver:latest",
      "--conf", "spark.kubernetes.submit.waitAppCompletion=false",
      EXAMPLES_JAR_FILE.getAbsolutePath,
      TEST_EXISTENCE_FILE.getName,
      TEST_EXISTENCE_FILE_CONTENTS)
    val podCompletedFuture = SettableFuture.create[Boolean]
    val watch = new Watcher[Pod] {
      override def eventReceived(action: Action, pod: Pod): Unit = {
        val containerStatuses = pod.getStatus.getContainerStatuses.asScala
        val allSuccessful = containerStatuses.nonEmpty && containerStatuses
          .forall(status => {
            status.getState.getTerminated != null && status.getState.getTerminated.getExitCode == 0
        })
        if (allSuccessful) {
          podCompletedFuture.set(true)
        } else {
          val failedContainers = containerStatuses.filter(container => {
            container.getState.getTerminated != null &&
              container.getState.getTerminated.getExitCode != 0
          })
          if (failedContainers.nonEmpty) {
            podCompletedFuture.setException(new SparkException(
              "One or more containers in the driver failed with a nonzero exit code."))
          }
        }
      }

      override def onClose(e: KubernetesClientException): Unit = {
        logWarning("Watch closed", e)
      }
    }
    Utils.tryWithResource(minikubeKubernetesClient
        .pods
        .withLabel("spark-app-name", "spark-file-existence-test")
        .watch(watch)) { _ =>
      SparkSubmit.main(args)
      assert(podCompletedFuture.get, "Failed to run driver pod")
      val driverPod = minikubeKubernetesClient
        .pods
        .withLabel("spark-app-name", "spark-file-existence-test")
        .list()
        .getItems
        .get(0)
      val podLog = minikubeKubernetesClient
        .pods
        .withName(driverPod.getMetadata.getName)
        .getLog
      assert(podLog.contains(s"File found at /opt/spark/${TEST_EXISTENCE_FILE.getName}" +
        s" with correct contents."), "Job did not find the file as expected.")
    }
  }
}
