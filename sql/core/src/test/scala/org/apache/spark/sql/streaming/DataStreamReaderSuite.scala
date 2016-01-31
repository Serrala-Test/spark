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

package org.apache.spark.sql.streaming.test

import java.io.File

import org.apache.spark.sql.catalyst.util._
import org.apache.spark.sql.execution.streaming.{Batch, Offset, Source, Sink}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{SQLContext, StreamTest}
import org.apache.spark.sql.sources.{StreamSinkProvider, StreamSourceProvider}
import org.apache.spark.sql.test.SharedSQLContext

object LastOptions {
  var parameters: Map[String, String] = null
  var schema: Option[StructType] = null
}

/** Dummy provider: returns no-op source/sink and records options in [[LastOptions]]. */
class DefaultSource extends StreamSourceProvider with StreamSinkProvider {
  override def createSource(
      sqlContext: SQLContext,
      parameters: Map[String, String],
      schema: Option[StructType]): Source = {
    LastOptions.parameters = parameters
    LastOptions.schema = schema
    new Source {
      override def getNextBatch(start: Option[Offset]): Option[Batch] = None
      override def schema: StructType = StructType(Nil)
    }
  }

  override def createSink(
      sqlContext: SQLContext,
      parameters: Map[String, String]): Sink = {
    LastOptions.parameters = parameters
    new Sink {
      override def addBatch(batch: Batch): Unit = {}
      override def currentOffset: Option[Offset] = None
    }
  }
}

class DataStreamReaderWriterSuite extends StreamTest with SharedSQLContext {
  import testImplicits._

  test("resolve default source") {
    sqlContext.streamFrom
      .format("org.apache.spark.sql.streaming.test")
      .open()
      .streamTo
      .format("org.apache.spark.sql.streaming.test")
      .start()
      .stop()
  }

  test("resolve full class") {
    sqlContext.streamFrom
      .format("org.apache.spark.sql.streaming.test.DefaultSource")
      .open()
      .streamTo
      .format("org.apache.spark.sql.streaming.test")
      .start()
      .stop()
  }

  test("options") {
    val map = new java.util.HashMap[String, String]
    map.put("opt3", "3")

    val df = sqlContext.streamFrom
        .format("org.apache.spark.sql.streaming.test")
        .option("opt1", "1")
        .options(Map("opt2" -> "2"))
        .options(map)
        .open()

    assert(LastOptions.parameters("opt1") == "1")
    assert(LastOptions.parameters("opt2") == "2")
    assert(LastOptions.parameters("opt3") == "3")

    LastOptions.parameters = null

    df.streamTo
      .format("org.apache.spark.sql.streaming.test")
      .option("opt1", "1")
      .options(Map("opt2" -> "2"))
      .options(map)
      .start()
      .stop()

    assert(LastOptions.parameters("opt1") == "1")
    assert(LastOptions.parameters("opt2") == "2")
    assert(LastOptions.parameters("opt3") == "3")
  }

  test("stream paths") {
    val df = sqlContext.streamFrom
      .format("org.apache.spark.sql.streaming.test")
      .open("/test")

    assert(LastOptions.parameters("path") == "/test")

    LastOptions.parameters = null

    df.streamTo
      .format("org.apache.spark.sql.streaming.test")
      .start("/test")
      .stop()

    assert(LastOptions.parameters("path") == "/test")
  }

}
