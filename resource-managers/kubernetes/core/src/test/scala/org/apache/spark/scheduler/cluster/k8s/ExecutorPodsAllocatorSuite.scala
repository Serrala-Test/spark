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

import io.fabric8.kubernetes.api.model.{DoneablePod, Pod, PodBuilder, PodList}
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.{MixedOperation, PodResource}
import org.mockito.{ArgumentMatcher, Matchers, Mock, MockitoAnnotations}
import org.mockito.Matchers.any
import org.mockito.Mockito.{never, times, verify, when}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.BeforeAndAfter
import scala.collection.mutable

import org.apache.spark.{SparkConf, SparkFunSuite}
import org.apache.spark.deploy.k8s.{KubernetesConf, KubernetesExecutorSpecificConf, SparkPod}
import org.apache.spark.deploy.k8s.Config._
import org.apache.spark.deploy.k8s.Constants._
import org.apache.spark.scheduler.cluster.k8s.ExecutorLifecycleTestUtils._

class ExecutorPodsAllocatorSuite extends SparkFunSuite with BeforeAndAfter {

  private type Pods = MixedOperation[Pod, PodList, DoneablePod, PodResource[Pod, DoneablePod]]

  private val driverPodName = "driver"

  private val driverPod = new PodBuilder()
    .withNewMetadata()
      .withName(driverPodName)
      .addToLabels(SPARK_APP_ID_LABEL, TEST_SPARK_APP_ID)
      .addToLabels(SPARK_ROLE_LABEL, SPARK_POD_DRIVER_ROLE)
      .withUid("driver-pod-uid")
      .endMetadata()
    .build()

  private val conf = new SparkConf().set(KUBERNETES_DRIVER_POD_NAME, driverPodName)

  private val podAllocationSize = conf.get(KUBERNETES_ALLOCATION_BATCH_SIZE)

  private val podAllocationDelay = conf.get(KUBERNETES_ALLOCATION_BATCH_DELAY)

  private var namedExecutorPods: mutable.Map[String, PodResource[Pod, DoneablePod]] = _

  @Mock
  private var kubernetesClient: KubernetesClient = _

  @Mock
  private var podOperations: Pods = _

  @Mock
  private var driverPodOperations: PodResource[Pod, DoneablePod] = _

  @Mock
  private var executorBuilder: KubernetesExecutorBuilder = _

  private var eventQueue: DeterministicExecutorPodsEventQueue = _

  private var podsAllocatorUnderTest: ExecutorPodsAllocator = _

  before {
    MockitoAnnotations.initMocks(this)
    when(kubernetesClient.pods()).thenReturn(podOperations)
    when(podOperations.withName(driverPodName)).thenReturn(driverPodOperations)
    when(driverPodOperations.get).thenReturn(driverPod)
    when(executorBuilder.buildFromFeatures(kubernetesConfWithCorrectFields()))
      .thenAnswer(executorPodAnswer())
    eventQueue = new DeterministicExecutorPodsEventQueue()
    podsAllocatorUnderTest = new ExecutorPodsAllocator(
      conf, executorBuilder, kubernetesClient, eventQueue)
    podsAllocatorUnderTest.start(TEST_SPARK_APP_ID)
  }

  test("Initially request executors in batches. Do not request another batch if the" +
    " first has not finished.") {
    podsAllocatorUnderTest.setTotalExpectedExecutors(podAllocationSize + 1)
    eventQueue.notifySubscribers()
    for (nextId <- 1 to podAllocationSize) {
      verify(podOperations).create(
        podWithAttachedContainerForId(nextId))
    }
    verify(podOperations, never()).create(
      podWithAttachedContainerForId(podAllocationSize + 1))
  }

  test("Request executors in batches. Allow another batch to be requested if" +
    " all pending executors start running.") {
    podsAllocatorUnderTest.setTotalExpectedExecutors(podAllocationSize + 1)
    eventQueue.notifySubscribers()
    for (execId <- 1 until podAllocationSize) {
      eventQueue.pushPodUpdate(runningExecutor(execId))
    }
    eventQueue.notifySubscribers()
    verify(podOperations, never()).create(
      podWithAttachedContainerForId(podAllocationSize + 1))
    eventQueue.pushPodUpdate(
      runningExecutor(podAllocationSize))
    eventQueue.notifySubscribers()
    verify(podOperations).create(podWithAttachedContainerForId(podAllocationSize + 1))
    eventQueue.notifySubscribers()
    verify(podOperations, times(podAllocationSize + 1)).create(any(classOf[Pod]))
  }

  test("When a current batch reaches error states immediately, re-request" +
    " them on the next batch.") {
    podsAllocatorUnderTest.setTotalExpectedExecutors(podAllocationSize)
    eventQueue.notifySubscribers()
    for (execId <- 1 until podAllocationSize) {
      eventQueue.pushPodUpdate(runningExecutor(execId))
    }
    val failedPod = failedExecutorWithoutDeletion(podAllocationSize)
    eventQueue.pushPodUpdate(failedPod)
    eventQueue.notifySubscribers()
    verify(podOperations).create(podWithAttachedContainerForId(podAllocationSize + 1))
  }

  private def executorPodAnswer(): Answer[SparkPod] = {
    new Answer[SparkPod] {
      override def answer(invocation: InvocationOnMock): SparkPod = {
        val k8sConf = invocation.getArgumentAt(
          0, classOf[KubernetesConf[KubernetesExecutorSpecificConf]])
        executorPodWithId(k8sConf.roleSpecificConf.executorId)
      }
    }
  }

  private def kubernetesConfWithCorrectFields(): KubernetesConf[KubernetesExecutorSpecificConf] =
    Matchers.argThat(new ArgumentMatcher[KubernetesConf[KubernetesExecutorSpecificConf]] {
      override def matches(argument: scala.Any): Boolean = {
        if (!argument.isInstanceOf[KubernetesConf[KubernetesExecutorSpecificConf]]) {
          false
        } else {
          val k8sConf = argument.asInstanceOf[KubernetesConf[KubernetesExecutorSpecificConf]]
          val executorSpecificConf = k8sConf.roleSpecificConf
          val expectedK8sConf = KubernetesConf.createExecutorConf(
            conf,
            executorSpecificConf.executorId,
            TEST_SPARK_APP_ID,
            driverPod)
          k8sConf.sparkConf.getAll.toMap == conf.getAll.toMap &&
            // Since KubernetesConf.createExecutorConf clones the SparkConf object, force
            // deep equality comparison for the SparkConf object and use object equality
            // comparison on all other fields.
            k8sConf.copy(sparkConf = conf) == expectedK8sConf.copy(sparkConf = conf)
        }
      }
    })

}
