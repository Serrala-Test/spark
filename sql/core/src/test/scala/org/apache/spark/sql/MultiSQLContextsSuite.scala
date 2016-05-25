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

package org.apache.spark.sql

import org.scalatest.BeforeAndAfterAll

import org.apache.spark._
import org.apache.spark.sql.internal.SQLConf

class MultiSQLContextsSuite extends SparkFunSuite with BeforeAndAfterAll {

  private var originalActiveSparkSession: Option[SparkSession] = _
  private var originalDefaultSparkSession: Option[SparkSession] = _
  private var sparkConf: SparkConf = _

  override protected def beforeAll(): Unit = {
    originalActiveSparkSession = SparkSession.getActiveSession
    originalDefaultSparkSession = SparkSession.getDefaultSession

    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
    sparkConf =
      new SparkConf(false)
        .setMaster("local[*]")
        .setAppName("test")
        .set("spark.ui.enabled", "false")
        .set("spark.driver.allowMultipleContexts", "true")
  }

  override protected def afterAll(): Unit = {
    // Set these states back.
    originalActiveSparkSession.foreach(session => SparkSession.setActiveSession(session))
    originalDefaultSparkSession.foreach(session => SparkSession.setDefaultSession(session))
  }

  def testCreatingNewSQLContext(allowsMultipleContexts: Boolean): Unit = {
    val conf =
      sparkConf
        .clone
        .set(SQLConf.ALLOW_MULTIPLE_CONTEXTS.key, allowsMultipleContexts.toString)
    val sparkContext = new SparkContext(conf)

    try {
      if (allowsMultipleContexts) {
        new SQLContext(sparkContext)
        // SQLContext.clearActive()
      } else {
        // If allowsMultipleContexts is false, make sure we can get the error.
        val message = intercept[SparkException] {
          new SQLContext(sparkContext)
        }.getMessage
        assert(message.contains("Only one SQLContext/HiveContext may be running"))
      }
    } finally {
      sparkContext.stop()
    }
  }

  test("test the flag to disallow creating multiple root SQLContext") {
    Seq(false, true).foreach { allowMultipleSQLContexts =>
      val conf =
        sparkConf
          .clone
          .set(SQLConf.ALLOW_MULTIPLE_CONTEXTS.key, allowMultipleSQLContexts.toString)
      val sc = new SparkContext(conf)
      try {
        val rootSQLContext = new SQLContext(sc)
        SparkSession.setDefaultSession(rootSQLContext.sparkSession)
        // Make sure we can successfully create new Session.
        rootSQLContext.newSession()
        rootSQLContext.newSession()

        testCreatingNewSQLContext(allowMultipleSQLContexts)
      } finally {
        sc.stop()
        SparkSession.clearActiveSession()
        SparkSession.clearDefaultSession()
      }
    }
  }
}
