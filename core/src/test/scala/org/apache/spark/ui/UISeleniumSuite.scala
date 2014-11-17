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

package org.apache.spark.ui

import org.openqa.selenium.WebDriver
import org.openqa.selenium.htmlunit.HtmlUnitDriver
import org.scalatest._
import org.scalatest.concurrent.Eventually._
import org.scalatest.selenium.WebBrowser
import org.scalatest.time.SpanSugar._

import org.apache.spark.api.java.StorageLevels
import org.apache.spark._
import org.apache.spark.SparkContext._
import org.apache.spark.LocalSparkContext._
import org.apache.spark.shuffle.FetchFailedException

/**
 * Selenium tests for the Spark Web UI.  These tests are not run by default
 * because they're slow.
 */
@DoNotDiscover
class UISeleniumSuite extends FunSuite with WebBrowser with Matchers {
  implicit val webDriver: WebDriver = new HtmlUnitDriver

  /**
   * Create a test SparkContext with the SparkUI enabled.
   * It is safe to `get` the SparkUI directly from the SparkContext returned here.
   */
  private def newSparkContext(): SparkContext = {
    val conf = new SparkConf()
      .setMaster("local")
      .setAppName("test")
      .set("spark.ui.enabled", "true")
    val sc = new SparkContext(conf)
    assert(sc.ui.isDefined)
    sc
  }

  test("effects of unpersist() / persist() should be reflected") {
    // Regression test for SPARK-2527
    withSpark(newSparkContext()) { sc =>
      val ui = sc.ui.get
      val rdd = sc.parallelize(Seq(1, 2, 3))
      rdd.persist(StorageLevels.DISK_ONLY).count()
      eventually(timeout(5 seconds), interval(50 milliseconds)) {
        go to (ui.appUIAddress.stripSuffix("/") + "/storage")
        val tableRowText = findAll(cssSelector("#storage-by-rdd-table td")).map(_.text).toSeq
        tableRowText should contain (StorageLevels.DISK_ONLY.description)
      }
      eventually(timeout(5 seconds), interval(50 milliseconds)) {
        go to (ui.appUIAddress.stripSuffix("/") + "/storage/rdd/?id=0")
        val tableRowText = findAll(cssSelector("#rdd-storage-by-block-table td")).map(_.text).toSeq
        tableRowText should contain (StorageLevels.DISK_ONLY.description)
      }

      rdd.unpersist()
      rdd.persist(StorageLevels.MEMORY_ONLY).count()
      eventually(timeout(5 seconds), interval(50 milliseconds)) {
        go to (ui.appUIAddress.stripSuffix("/") + "/storage")
        val tableRowText = findAll(cssSelector("#storage-by-rdd-table td")).map(_.text).toSeq
        tableRowText should contain (StorageLevels.MEMORY_ONLY.description)
      }
      eventually(timeout(5 seconds), interval(50 milliseconds)) {
        go to (ui.appUIAddress.stripSuffix("/") + "/storage/rdd/?id=0")
        val tableRowText = findAll(cssSelector("#rdd-storage-by-block-table td")).map(_.text).toSeq
        tableRowText should contain (StorageLevels.MEMORY_ONLY.description)
      }
    }
  }

  test("failed stages should not appear to be active") {
    withSpark(newSparkContext()) { sc =>
      // Regression test for SPARK-3021
      intercept[SparkException] {
        sc.parallelize(1 to 10).map { x => throw new Exception()}.collect()
      }
      eventually(timeout(5 seconds), interval(50 milliseconds)) {
        go to (sc.ui.get.appUIAddress.stripSuffix("/") + "/stages")
        find(id("active")).get.text should be("Active Stages (0)")
        find(id("failed")).get.text should be("Failed Stages (1)")
      }

      // Regression test for SPARK-2105
      class NotSerializable
      val unserializableObject = new NotSerializable
      intercept[SparkException] {
        sc.parallelize(1 to 10).map { x => unserializableObject}.collect()
      }
      eventually(timeout(5 seconds), interval(50 milliseconds)) {
        go to (sc.ui.get.appUIAddress.stripSuffix("/") + "/stages")
        find(id("active")).get.text should be("Active Stages (0)")
        // The failure occurs before the stage becomes active, hence we should still show only one
        // failed stage, not two:
        find(id("failed")).get.text should be("Failed Stages (1)")
      }
    }
  }

  test("spark.ui.killEnabled should properly control kill button display") {
    def getSparkContext(killEnabled: Boolean): SparkContext = {
      val conf = new SparkConf()
        .setMaster("local")
        .setAppName("test")
        .set("spark.ui.enabled", "true")
        .set("spark.ui.killEnabled", killEnabled.toString)
      new SparkContext(conf)
    }

    def hasKillLink = find(className("kill-link")).isDefined
    def runSlowJob(sc: SparkContext) {
      sc.parallelize(1 to 10).map{x => Thread.sleep(10000); x}.countAsync()
    }

    withSpark(getSparkContext(killEnabled = true)) { sc =>
      runSlowJob(sc)
      eventually(timeout(5 seconds), interval(50 milliseconds)) {
        go to (sc.ui.get.appUIAddress.stripSuffix("/") + "/stages")
        assert(hasKillLink)
      }
    }

    withSpark(getSparkContext(killEnabled = false)) { sc =>
      runSlowJob(sc)
      eventually(timeout(5 seconds), interval(50 milliseconds)) {
        go to (sc.ui.get.appUIAddress.stripSuffix("/") + "/stages")
        assert(!hasKillLink)
      }
    }
  }

  test("jobs page should not display job group name unless some job was submitted in a job group") {
    withSpark(newSparkContext()) { sc =>
      // If no job has been run in a job group, then "(Job Group)" should not appear in the header
      sc.parallelize(Seq(1, 2, 3)).count()
      eventually(timeout(5 seconds), interval(50 milliseconds)) {
        go to (sc.ui.get.appUIAddress.stripSuffix("/") + "/jobs")
        val tableHeaders = findAll(cssSelector("th")).map(_.text).toSeq
        tableHeaders should not contain "Job Id (Job Group)"
      }
      // Once at least one job has been run in a job group, then we should display the group name:
      sc.setJobGroup("my-job-group", "my-job-group-description")
      sc.parallelize(Seq(1, 2, 3)).count()
      eventually(timeout(5 seconds), interval(50 milliseconds)) {
        go to (sc.ui.get.appUIAddress.stripSuffix("/") + "/jobs")
        val tableHeaders = findAll(cssSelector("th")).map(_.text).toSeq
        tableHeaders should contain ("Job Id (Job Group)")
      }
    }
  }

  test("stage failures / recomputations should not cause stages to be overcounted on job page") {
    withSpark(newSparkContext()) { sc =>
      val data = sc.parallelize(Seq(1, 2, 3)).map(identity).groupBy(identity)
      val shuffleHandle =
        data.dependencies.head.asInstanceOf[ShuffleDependency[_, _, _]].shuffleHandle
      // Simulate fetch failures:
      val mappedData = data.map { x =>
        val taskContext = TaskContext.get
        if (taskContext.attemptId() == 1) {  // Cause this stage to fail on its first attempt.
          val env = SparkEnv.get
          val bmAddress = env.blockManager.blockManagerId
          val shuffleId = shuffleHandle.shuffleId
          val mapId = 0
          val reduceId = taskContext.partitionId()
          val message = "Simulated fetch failure"
          throw new FetchFailedException(bmAddress, shuffleId, mapId, reduceId, message)
        } else {
          x
        }
      }
      mappedData.count()
      eventually(timeout(5 seconds), interval(50 milliseconds)) {
        go to (sc.ui.get.appUIAddress.stripSuffix("/") + "/jobs")
        find(cssSelector(".stage-progress-cell .completed-stages")).get.text should be ("2")
        find(cssSelector(".stage-progress-cell .total-stages")).get.text should be ("2")
        find(cssSelector(".stage-progress-cell .failed-stages")).get.text should be ("(1 failed)")
      }
    }
  }
}
