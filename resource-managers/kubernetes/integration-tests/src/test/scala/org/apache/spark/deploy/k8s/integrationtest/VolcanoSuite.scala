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
package org.apache.spark.deploy.k8s.integrationtest

import scala.collection.mutable

import org.scalatest.Tag
import org.scalatest.concurrent.Eventually

import org.apache.spark.deploy.k8s.integrationtest.KubernetesSuite.{INTERVAL, TIMEOUT}

class VolcanoSuite extends KubernetesSuite with VolcanoTestsSuite {

  override protected def setUpTest(): Unit = {
    super.setUpTest()
    sparkAppConf
      .set("spark.kubernetes.driver.scheduler.name", "volcano")
      .set("spark.kubernetes.executor.scheduler.name", "volcano")
    testGroups = mutable.Set.empty
  }

  private def deletePodInTestGroup(): Unit = {
    testGroups.map{ g =>
      kubernetesTestComponents.kubernetesClient.pods().withLabel("spark-group-locator", g).delete()
      Eventually.eventually(TIMEOUT, INTERVAL) {
        val size = kubernetesTestComponents.kubernetesClient
          .pods()
          .withLabel("spark-app-locator", g)
          .list().getItems.size()
        assert(size === 0)
      }
    }
  }

  override protected def cleanUpTest(): Unit = {
    super.cleanUpTest()
    deletePodInTestGroup()
  }
}

private[spark] object VolcanoSuite {
  val volcanoTag = Tag("volcano")
}
