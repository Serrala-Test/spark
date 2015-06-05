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

package org.apache.spark.deploy.rest

import org.scalatest.PrivateMethodTester

import org.apache.spark.{SecurityManager, SparkConf, SparkFunSuite}
import org.apache.spark.deploy.DriverDescription

/**
 * Tests for the Standalone REST server.
 */
class StandaloneRestServerSuite extends SparkFunSuite with PrivateMethodTester {

  test("Auth secret shouldn't appear on the command line") {
    val servlet = new StandaloneSubmitRequestServlet(null , "", null)
    val buildDriverDesc = PrivateMethod[DriverDescription]('buildDriverDescription)
    val request = new CreateSubmissionRequest
    request.clientSparkVersion = "1.2.3"
    request.appResource = "honey-walnut-cherry.jar"
    request.mainClass = "org.apache.spark.examples.SparkPie"
    request.appArgs = Array("two slices", "a hint of cinnamon")
    val conf = new SparkConf(false)
    conf.set("spark.app.name", "SparkPie")
    request.sparkProperties = conf.getAll.toMap
    request.validate()

    // set secret
    conf.set(SecurityManager.CLUSTER_AUTH_SECRET_CONF, "This is the secret sauce")

    // auth is not set
    request.sparkProperties = conf.getAll.toMap
    var driver = servlet invokePrivate buildDriverDesc(request)
    assert(driver.appSecret === None)
    assert(!driver.command.javaOpts.exists(
      _.startsWith("-D" + SecurityManager.CLUSTER_AUTH_CONF)))
    assert(!driver.command.javaOpts.exists(
      _.startsWith("-D" + SecurityManager.CLUSTER_AUTH_SECRET_CONF)))

    // auth is set to false
    conf.set(SecurityManager.CLUSTER_AUTH_CONF, "false")
    request.sparkProperties = conf.getAll.toMap
    driver = servlet invokePrivate buildDriverDesc(request)
    assert(driver.appSecret === None)
    assert(driver.command.javaOpts.contains(
      "-D" + SecurityManager.CLUSTER_AUTH_CONF + "=false"))
    assert(!driver.command.javaOpts.exists(
      _.startsWith("-D" + SecurityManager.CLUSTER_AUTH_SECRET_CONF)))

    // auth is set to true
    conf.set(SecurityManager.CLUSTER_AUTH_CONF, "true")
    request.sparkProperties = conf.getAll.toMap
    driver = servlet invokePrivate buildDriverDesc(request)
    assert(driver.appSecret === Some("This is the secret sauce"))
    assert(driver.command.javaOpts.contains(
      "-D" + SecurityManager.CLUSTER_AUTH_CONF + "=true"))
    assert(!driver.command.javaOpts.exists(
      _.startsWith("-D" + SecurityManager.CLUSTER_AUTH_SECRET_CONF)))
  }
}
