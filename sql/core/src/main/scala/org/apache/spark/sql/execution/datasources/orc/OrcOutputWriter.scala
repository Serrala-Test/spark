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

package org.apache.spark.sql.execution.datasources.orc

import java.nio.charset.StandardCharsets.UTF_8

import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.NullWritable
import org.apache.hadoop.mapreduce.TaskAttemptContext
import org.apache.orc.OrcFile
import org.apache.orc.mapred.{OrcOutputFormat => OrcMapRedOutputFormat, OrcStruct}
import org.apache.orc.mapreduce.{OrcMapreduceRecordWriter, OrcOutputFormat}

import org.apache.spark.SPARK_VERSION_SHORT
import org.apache.spark.sql.SPARK_VERSION_METADATA_KEY
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.execution.datasources.OutputWriter
import org.apache.spark.sql.types._

private[orc] class OrcOutputWriter(
    path: String,
    dataSchema: StructType,
    context: TaskAttemptContext)
  extends OutputWriter {

  private[this] val serializer = new OrcSerializer(dataSchema)

  private val recordWriter = {
    val orcOutputFormat = new OrcOutputFormat[OrcStruct]() {
      override def getDefaultWorkFile(context: TaskAttemptContext, extension: String): Path = {
        new Path(path)
      }
    }
    val filename = orcOutputFormat.getDefaultWorkFile(context, ".orc")
    val options = OrcMapRedOutputFormat.buildOptions(context.getConfiguration)
    val writer = OrcFile.createWriter(filename, options)
    val recordWriter = new OrcMapreduceRecordWriter[OrcStruct](writer)
    writer.addUserMetadata(SPARK_VERSION_METADATA_KEY, UTF_8.encode(SPARK_VERSION_SHORT))
    recordWriter
  }

  override def write(row: InternalRow): Unit = {
    recordWriter.write(NullWritable.get(), serializer.serialize(row))
  }

  override def close(): Unit = {
    recordWriter.close(context)
  }
}
