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

package org.apache.spark.sql.execution.datasources

import org.apache.hadoop.fs.Path
import org.apache.hadoop.mapreduce.{OutputCommitter, TaskAttemptContext}

import org.apache.spark.internal.Logging
import org.apache.spark.internal.io.HadoopMapReduceCommitProtocol
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.internal.SQLConf

/**
 * A variant of [[HadoopMapReduceCommitProtocol]] that allows specifying the actual
 * Hadoop output committer using an option specified in SQLConf.
 */
class SQLPathHadoopMapReduceCommitProtocol(
    jobId: String,
    path: String,
    dynamicPartitionOverwrite: Boolean = false)
  extends HadoopMapReduceCommitProtocol(jobId, path, dynamicPartitionOverwrite)
    with Serializable with Logging {

  private val committerStagingDir = SQLPathOutputCommitter.newVersionExternalTempPath(
    jobId, new Path(path), SparkSession.getActiveSession.get.sharedState.hadoopConf,
    SQLConf.get.getConfString("spark.sql.source.stagingDir", ".stagingDir"))

  val sqlPathOutputCommitter: SQLPathOutputCommitter =
    committer.asInstanceOf[SQLPathOutputCommitter]

  override protected def setupCommitter(context: TaskAttemptContext): OutputCommitter = {
    // The specified output committer is a FileOutputCommitter.
    // So, we will use the FileOutputCommitter-specified constructor.
    val committerOutputPath = new Path(path)
    val committer =
      new SQLPathOutputCommitter(committerStagingDir, committerOutputPath, context)
    logInfo(s"Using output committer class ${committer.getClass.getCanonicalName}")
    committer
  }

  override def newTaskTempFile(
    taskContext: TaskAttemptContext,
    dir: Option[String],
    ext: String): String = {
    val filename = getFilename(taskContext, ext)
    dir.map { d =>
      new Path(
        new Path(sqlPathOutputCommitter.getTaskAttemptPath(taskContext), d), filename).toString
    }.getOrElse {
      new Path(sqlPathOutputCommitter.getTaskAttemptPath(taskContext), filename).toString
    }
  }
}
