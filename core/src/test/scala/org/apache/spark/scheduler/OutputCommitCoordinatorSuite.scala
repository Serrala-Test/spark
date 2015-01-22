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

import java.io.{ObjectInputStream, ObjectOutputStream, IOException}

import scala.collection.mutable

import org.scalatest.concurrent.Timeouts
import org.scalatest.{BeforeAndAfter, FunSuiteLike}

import org.apache.hadoop.mapred.{TaskAttemptID, JobConf, TaskAttemptContext, OutputCommitter}
import org.mockito.Mockito._

import org.apache.spark._
import org.apache.spark.executor.{TaskMetrics}
import org.apache.spark.rdd.FakeOutputCommitter

/**
 * Unit tests for the output commit coordination functionality. Overrides the SchedulerImpl
 * to just run the tasks directly and send completion or error messages back to the
 * DAG scheduler.
 */
class OutputCommitCoordinatorSuite
    extends FunSuiteLike
    with BeforeAndAfter
    with LocalSparkContext
    with Timeouts {

  val conf = new SparkConf().set("spark.localExecution.enabled", "true")

  var taskScheduler: TaskSchedulerImpl = null
  var dagScheduler: DAGScheduler = null
  var dagSchedulerEventProcessLoop: DAGSchedulerEventProcessLoop = null
  var accum: Accumulator[Int] = null
  var accumId: Long = 0

  before {
    sc = new SparkContext("local", "Output Commit Coordinator Suite")
    accum = sc.accumulator[Int](0)
    Accumulators.register(accum, true)
    accumId = accum.id

    taskScheduler = new TaskSchedulerImpl(sc, 4, true) {
      override def submitTasks(taskSet: TaskSet) {
        // Instead of submitting a task to some executor, just run the task directly.
        // Make two attempts. The first may or may not succeed. If the first
        // succeeds then the second is redundant and should be handled
        // accordingly by OutputCommitCoordinator. Otherwise the second
        // should not be blocked from succeeding.
        execTasks(taskSet, 0)
        execTasks(taskSet, 1)
      }

      private def execTasks(taskSet: TaskSet, attemptNumber: Int) {
        var taskIndex = 0
        taskSet.tasks.foreach(t => {
          val tid = newTaskId
          val taskInfo = new TaskInfo(tid, taskIndex, 0, System.currentTimeMillis, "0",
            "localhost", TaskLocality.NODE_LOCAL, false)
          taskIndex += 1
          // Track the successful commits in an accumulator. However, we can't just invoke
          // accum += 1 since this unit test circumvents the usual accumulator updating
          // infrastructure. So just send the accumulator update manually.
          val accumUpdates = new mutable.HashMap[Long, Any]
          try {
            accumUpdates(accumId) = t.run(attemptNumber, attemptNumber)
            dagSchedulerEventProcessLoop.post(
                new CompletionEvent(t, Success, 0, accumUpdates, taskInfo, new TaskMetrics))
          } catch {
            case e: Throwable =>
              dagSchedulerEventProcessLoop.post(new CompletionEvent(t, new ExceptionFailure(e,
                  Option.empty[TaskMetrics]), 1, accumUpdates, taskInfo, new TaskMetrics))
          }
        })
      }
    }

    dagScheduler = new DAGScheduler(sc, taskScheduler)
    taskScheduler.setDAGScheduler(dagScheduler)
    sc.dagScheduler = dagScheduler
    dagSchedulerEventProcessLoop = new DAGSchedulerSingleThreadedProcessLoop(dagScheduler)
  }

  /**
   * Function that constructs a SparkHadoopWriter with a mock committer and runs its commit
   */
  private class OutputCommittingFunctionWithAccumulator(var accum: Accumulator[Int])
      extends ((TaskContext, Iterator[Int]) => Int) with Serializable {

    def apply(ctxt: TaskContext, it: Iterator[Int]): Int = {
      val outputCommitter = new FakeOutputCommitter {
        override def commitTask(taskAttemptContext: TaskAttemptContext) {
          super.commitTask(taskAttemptContext)
        }
      }
      runCommitWithProvidedCommitter(ctxt, it, outputCommitter)
    }

    protected def runCommitWithProvidedCommitter(
        ctxt: TaskContext,
        it: Iterator[Int],
        outputCommitter: OutputCommitter): Int = {
      def jobConf = new JobConf {
        override def getOutputCommitter(): OutputCommitter = outputCommitter
      }
      val sparkHadoopWriter = new SparkHadoopWriter(jobConf) {
        override def newTaskAttemptContext(
            conf: JobConf,
            attemptId: TaskAttemptID): TaskAttemptContext = {
          mock(classOf[TaskAttemptContext])
        }
      }
      sparkHadoopWriter.setup(ctxt.stageId, ctxt.partitionId, ctxt.attemptNumber)
      sparkHadoopWriter.commit
      if (FakeOutputCommitter.ran) {
        FakeOutputCommitter.ran = false
        1
      } else {
        0
      }
    }

    @throws(classOf[IOException])
    private def writeObject(out: ObjectOutputStream) {
      out.writeObject(accum)
    }

    @throws(classOf[IOException])
    private def readObject(in: ObjectInputStream) {
      accum = in.readObject.asInstanceOf[Accumulator[Int]]
    }
  }

  /**
   * Function that will explicitly fail to commit on the first attempt
   */
  private class FailFirstTimeCommittingFunctionWithAccumulator(accum: Accumulator[Int])
      extends OutputCommittingFunctionWithAccumulator(accum) {
    override def apply(ctxt: TaskContext, it: Iterator[Int]): Int = {
      if (ctxt.attemptNumber == 0) {
        val outputCommitter = new FakeOutputCommitter {
          override def commitTask(taskAttemptContext: TaskAttemptContext) {
            throw new RuntimeException
          }
        }
        runCommitWithProvidedCommitter(ctxt, it, outputCommitter)
      } else {
        super.apply(ctxt, it)
      }
    }
  }

  test("Only one of two duplicate commit tasks should commit") {
    val rdd = sc.parallelize(1 to 10, 10)
    sc.runJob(rdd, new OutputCommittingFunctionWithAccumulator(accum))
    assert(accum.value === 10)
  }

  test("If commit fails, if task is retried it should not be locked, and will succeed.") {
    val rdd = sc.parallelize(Seq(1), 1)
    sc.runJob(rdd, new FailFirstTimeCommittingFunctionWithAccumulator(accum))
    assert(accum.value == 1)
  }
}
