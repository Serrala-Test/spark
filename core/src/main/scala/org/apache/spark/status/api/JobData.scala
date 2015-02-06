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
package org.apache.spark.status.api

import org.apache.spark.JobExecutionStatus
import org.apache.spark.util.collection.OpenHashSet

case class JobData(
  jobId: Int,
  name: String,
  description: Option[String],
  submissionTime: Option[Long],
  completionTime: Option[Long],
  stageIds: Seq[Int],
  jobGroup: Option[String],
  status: JobExecutionStatus,
  numTasks: Int,
  numActiveTasks: Int,
  numCompletedTasks: Int,
  numSkippedTasks: Int,
  numFailedTasks: Int,
  numActiveStages: Int,
  numCompletedStages: Int,
  numSkippedStages: Int,
  numFailedStages: Int
)
