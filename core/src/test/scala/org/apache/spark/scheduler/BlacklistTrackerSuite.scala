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

package org.apache.spark.scheduler

import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfter
import org.scalatest.mock.MockitoSugar

import org.apache.spark.ExceptionFailure
import org.apache.spark.SparkConf
import org.apache.spark.SparkFunSuite
import org.apache.spark.TaskEndReason
import org.apache.spark.util.ManualClock

class BlacklistTrackerSuite extends SparkFunSuite with BeforeAndAfter with MockitoSugar {

  val FAILURE: TaskEndReason = new ExceptionFailure(
      "Fake",
      "fake failure",
      Array.empty[StackTraceElement],
      "fake stack trace",
      None)

  val stage1 = 1
  val stage2 = 2

  val partition1 = 1
  val partition2 = 2
  val partition3 = 3

  // Variable name can indicate basic information of taskInfo
  // hostA: executor 1, 2, 4
  // hostB: executor 3
  // The format is "taskInfo_executorId_hostName"
  val taskInfo_1_hostA = new TaskInfo(1L, 1, 1, 0L, "1", "hostA", TaskLocality.ANY, false)
  val taskInfo_2_hostA = new TaskInfo(2L, 1, 1, 0L, "2", "hostA", TaskLocality.ANY, false)
  val taskInfo_3_hostB = new TaskInfo(3L, 3, 1, 0L, "3", "hostB", TaskLocality.ANY, false)

  val clock = new ManualClock(0)

  test ("expireExecutorsInBlacklist works") {
    // expire time is set to 6s
    val conf = new SparkConf().setAppName("test").setMaster("local")
      .set("spark.ui.enabled", "false")
      .set("spark.scheduler.executorTaskBlacklistTime", "6000")

    val scheduler = mock[TaskSchedulerImpl]

    val tracker = new BlacklistTracker(conf, scheduler, clock)
    // Executor 1 into blacklist at Time 00:00:00
    tracker.taskFailed(stage1, partition1, taskInfo_1_hostA)
    assert(tracker.executorBlacklist(stage1, 1) === Set("1"))

    clock.setTime(2000)
    tracker.expireExecutorsInBlackList()
    assert(tracker.executorBlacklist(stage1, 1) === Set("1"))
    // Executor 1 failed again at Time 00::00:02
    tracker.taskFailed(stage1, partition1, taskInfo_1_hostA)

    clock.setTime(3000)
    // Executor 2 failed at Time 00:00:03
    tracker.taskFailed(stage1, partition1, taskInfo_2_hostA)
    assert(tracker.executorBlacklist(stage1, 1) === Set("1", "2"))

    clock.setTime(6000)
    tracker.expireExecutorsInBlackList()
    assert(tracker.executorBlacklist(stage1, 1) === Set("1", "2"))

    clock.setTime(8000)
    tracker.expireExecutorsInBlackList()
    assert(tracker.executorBlacklist(stage1, 1) === Set("2"))

    clock.setTime(10000)
    tracker.expireExecutorsInBlackList()
    assert(tracker.executorBlacklist(stage1, 1) === Set())
  }

  test("blacklist feature is off by default") {
    val conf = new SparkConf().setAppName("test").setMaster("local")
      .set("spark.ui.enabled", "false")
    val scheduler = mock[TaskSchedulerImpl]

    val tracker = new BlacklistTracker(conf, scheduler, clock)
    tracker.taskFailed(stage1, partition1, taskInfo_1_hostA)
    tracker.taskFailed(stage1, partition1, taskInfo_2_hostA)
    assert(tracker.executorBlacklist(stage1, 1) === Set())
    assert(tracker.executorBlacklist(stage1, 1) === Set())

    tracker.taskFailed(stage1, partition3, taskInfo_3_hostB)
    assert(tracker.executorBlacklist(stage1, 3) === Set())
    assert(tracker.nodeBlacklist() === Set())
  }

  test("SingleTask strategy works") {
    val conf = new SparkConf().setAppName("test").setMaster("local")
      .set("spark.ui.enabled", "false")
      .set("spark.scheduler.executorTaskBlacklistTime", "1000")
    val scheduler = mock[TaskSchedulerImpl]

    // Task 1 failed on both executor 1 and executor 2
    val tracker = new BlacklistTracker(conf, scheduler, clock)
    tracker.taskFailed(stage1, partition1, taskInfo_1_hostA)
    tracker.taskFailed(stage1, partition1, taskInfo_2_hostA)
    assert(tracker.executorBlacklist(stage1, 1) === Set("1", "2"))
    assert(tracker.executorBlacklist(stage1, 2) === Set())

    // Task 1 succeeded on executor 1, so we remove executor 1 from blacklist
    tracker.taskSucceeded(stage1, partition1, taskInfo_1_hostA)
    assert(tracker.executorBlacklist(stage1, 1) === Set("2"))
    assert(tracker.nodeBlacklist() === Set())

    // Task 2 succeed on executor 3, no effect on blacklist for Task 1
    tracker.taskSucceeded(stage1, partition3, taskInfo_3_hostB)
    assert(tracker.executorBlacklist(stage1, 1) === Set("2"))

    tracker.taskFailed(stage1, partition3, taskInfo_3_hostB)
    assert(tracker.executorBlacklist(stage1, 3) === Set("3"))
    assert(tracker.nodeBlacklist() === Set())

    tracker.taskSucceeded(stage1, partition1, taskInfo_2_hostA)
    assert(tracker.executorBlacklist(stage1, 1) === Set())

    // Task 2 on Stage 2 failed on Executor 2
    tracker.taskFailed(stage2, partition2, taskInfo_2_hostA)
    assert(tracker.executorBlacklist(stage1, 1) === Set())
    assert(tracker.executorBlacklist(stage2, 1) === Set())
    assert(tracker.executorBlacklist(stage1, 2) === Set())
    assert(tracker.executorBlacklist(stage2, 2) === Set("2"))
  }

  test("AdvencedSingleTask strategy works") {
    val conf = new SparkConf().setAppName("test").setMaster("local")
      .set("spark.ui.enabled", "false")
      .set("spark.scheduler.blacklist.advancedStrategy", "true")
      .set("spark.scheduler.executorTaskBlacklistTime", "1000")
    val scheduler = mock[TaskSchedulerImpl]
    when(scheduler.getExecutorsAliveOnHost("hostA")).thenReturn(Some(Set("1", "2", "4")))
    Set("1", "2", "4").foreach { execId =>
      when(scheduler.getHostForExecutor(execId)).thenReturn("hostA")
    }

    // Task 1 failed on executor 1
    val tracker = new BlacklistTracker(conf, scheduler, clock)
    tracker.taskFailed(stage1, partition1, taskInfo_1_hostA)
    assert(tracker.executorBlacklist(stage1, 1) === Set("1"))
    assert(tracker.executorBlacklist(stage1, 2) === Set())
    assert(tracker.nodeBlacklist() === Set())

    // Task 1 failed on both executor 1 & 2, so we blacklist all executors on that host,
    // for all tasks for the stage.  Note the api expects multiple checks for each type of
    // blacklist -- this actually fits naturally with its use in the scheduler
    tracker.taskFailed(stage1, partition1, taskInfo_2_hostA)
    // we don't explicitly return the executors in hostA here, but that is OK
    assert(tracker.executorBlacklist(stage1, 1) === Set("1", "2"))
    assert(tracker.executorBlacklist(stage2, 1) === Set())
    assert(tracker.nodeBlacklistForStage(stage1) === Set("hostA"))
    assert(tracker.nodeBlacklistForStage(stage2) === Set())
    assert(tracker.nodeBlacklist() === Set("hostA"))

    // Task 1 succeeded on executor 1, so we remove executor 1 from blacklist
    tracker.taskSucceeded(stage1, partition1, taskInfo_1_hostA)
    assert(tracker.executorBlacklist(stage1, 1) === Set("2"))
    assert(tracker.nodeBlacklistForStage(stage1) === Set())
    assert(tracker.nodeBlacklist() === Set())

    // TODO clarify the semantics around executors with some successes
    pending
  }

  test("executors can be blacklisted with only a few failures per stage") {
    pending
  }

  test("executors aren't blacklisted if task sets fail") {
    pending
  }

  test("blacklisted executors and nodes get recovered with time") {
    pending
  }
}
