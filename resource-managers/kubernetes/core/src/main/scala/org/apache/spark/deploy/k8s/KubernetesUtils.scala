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
package org.apache.spark.deploy.k8s

import scala.collection.JavaConverters._

import io.fabric8.kubernetes.api.model.{ContainerStateRunning, ContainerStateTerminated, ContainerStateWaiting, ContainerStatus, Pod, Time}

import org.apache.spark.{SparkConf, SparkException}
import org.apache.spark.util.Utils

private[spark] object KubernetesUtils {

  /**
   * Extract and parse Spark configuration properties with a given name prefix and
   * return the result as a Map. Keys must not have more than one value.
   *
   * @param sparkConf Spark configuration
   * @param prefix the given property name prefix
   * @return a Map storing the configuration property keys and values
   */
  def parsePrefixedKeyValuePairs(
      sparkConf: SparkConf,
      prefix: String): Map[String, String] = {
    sparkConf.getAllWithPrefix(prefix).toMap
  }

  def requireNandDefined(opt1: Option[_], opt2: Option[_], errMessage: String): Unit = {
    opt1.foreach { _ => require(opt2.isEmpty, errMessage) }
  }

  /**
   * For the given collection of file URIs, resolves them as follows:
   * - File URIs with scheme local:// resolve to just the path of the URI.
   * - Otherwise, the URIs are returned as-is.
   */
  def resolveFileUrisAndPath(fileUris: Iterable[String]): Iterable[String] = {
    fileUris.map { uri =>
      resolveFileUri(uri)
    }
  }

  def resolveFileUri(uri: String): String = {
    val fileUri = Utils.resolveURI(uri)
    val fileScheme = Option(fileUri.getScheme).getOrElse("file")
    fileScheme match {
      case "local" => fileUri.getPath
      case _ => uri
    }
  }

  def parseMasterUrl(url: String): String = url.substring("k8s://".length)

  def formatPairsBundle(pairs: Seq[(String, String)], indent: Int = 1) = {
    // Use more loggable format if value is null or empty
    val indentStr = "\t" * indent
    pairs.map {
      case (k, v) => s"\n$indentStr $k: ${Option(v).filter(_.nonEmpty).getOrElse("N/A")}"
    }.mkString("")
  }

  /**
    * Given a pod output a human readable representation of its state
    * @param pod Pod
    * @return Human readable pod state
    */
  def formatPodState(pod: Pod): String = {
    val details = Seq[(String, String)](
      // pod metadata
      ("pod name", pod.getMetadata.getName),
      ("namespace", pod.getMetadata.getNamespace),
      ("labels", pod.getMetadata.getLabels.asScala.mkString(", ")),
      ("pod uid", pod.getMetadata.getUid),
      ("creation time", formatTime(pod.getMetadata.getCreationTimestamp)),

      // spec details
      ("service account name", pod.getSpec.getServiceAccountName),
      ("volumes", pod.getSpec.getVolumes.asScala.map(_.getName).mkString(", ")),
      ("node name", pod.getSpec.getNodeName),

      // status
      ("start time", formatTime(pod.getStatus.getStartTime)),
      ("container images",
        pod.getStatus.getContainerStatuses
          .asScala
          .map(_.getImage)
          .mkString(", ")),
      ("phase", pod.getStatus.getPhase),
      ("status", pod.getStatus.getContainerStatuses.asScala.map { status =>
        Seq(
          ("Container name", status.getName),
          ("Container image", status.getImage)) ++
          containerStatusDescription(status)
      }.map(p => formatPairsBundle(p, 2)).mkString("\n\n"))
    )

    formatPairsBundle(details)
  }

  def containersDescription(p: Pod): String = {
    p.getStatus.getContainerStatuses.asScala.map { status =>
      Seq(
        ("Container name", status.getName),
        ("Container image", status.getImage)) ++
        containerStatusDescription(status)
    }.map(p => formatPairsBundle(p, 1)).mkString("\n\n")
  }

  def containerStatusDescription(containerStatus: ContainerStatus)
    : Seq[(String, String)] = {
    val state = containerStatus.getState
    Option(state.getRunning)
      .orElse(Option(state.getTerminated))
      .orElse(Option(state.getWaiting))
      .map {
        case running: ContainerStateRunning =>
          Seq(
            ("Container state", "Running"),
            ("Container started at", formatTime(running.getStartedAt)))
        case waiting: ContainerStateWaiting =>
          Seq(
            ("Container state", "Waiting"),
            ("Pending reason", waiting.getReason))
        case terminated: ContainerStateTerminated =>
          Seq(
            ("Container state", "Terminated"),
            ("Container started at", formatTime(terminated.getStartedAt)),
            ("Container finished at", formatTime(terminated.getFinishedAt)),
            ("Exit code", terminated.getExitCode.toString),
            ("Termination reason", terminated.getReason))
        case unknown =>
          throw new SparkException(s"Unexpected container status type ${unknown.getClass}.")
      }.getOrElse(Seq(("Container state", "N/A")))
  }

  def formatTime(time: Time): String = {
    if (time != null) time.getTime else "N/A"
  }
}
