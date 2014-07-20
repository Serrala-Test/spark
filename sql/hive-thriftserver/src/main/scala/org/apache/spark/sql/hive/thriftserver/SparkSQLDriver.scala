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

package org.apache.spark.sql.hive.thriftserver

import scala.collection.JavaConversions._

import java.util.{ArrayList => JArrayList}

import org.apache.commons.lang.exception.ExceptionUtils
import org.apache.hadoop.hive.metastore.api.{FieldSchema, Schema}
import org.apache.hadoop.hive.ql.Driver
import org.apache.hadoop.hive.ql.processors.CommandProcessorResponse

import org.apache.spark.sql.Logging
import org.apache.spark.sql.hive.{HiveContext, HiveMetastoreTypes}

class SparkSQLDriver(val context: HiveContext = SparkSQLEnv.hiveContext)
  extends Driver with Logging {

  private var tableSchema: Schema = _
  private var hiveResponse: Seq[String] = _

  override def init(): Unit = {
  }

  private def getResultSetSchema(query: context.QueryExecution): Schema = {
    val analyzed = query.analyzed
    logger.debug(s"Result Schema: ${analyzed.output}")
    if (analyzed.output.size == 0) {
      new Schema(new FieldSchema("Response code", "string", "") :: Nil, null)
    } else {
      val fieldSchemas = analyzed.output.map { attr =>
        new FieldSchema(attr.name, HiveMetastoreTypes.toMetastoreType(attr.dataType), "")
      }

      new Schema(fieldSchemas, null)
    }
  }

  override def run(command: String): CommandProcessorResponse = {
    val execution = context.executePlan(context.hql(command).logicalPlan)

    // TODO unify the error code
    try {
      hiveResponse = execution.stringResult()
      tableSchema = getResultSetSchema(execution)
      new CommandProcessorResponse(0)
    } catch {
      case cause: Throwable =>
        logger.error(s"Failed in [$command]", cause)
        new CommandProcessorResponse(-3, ExceptionUtils.getFullStackTrace(cause), null)
    }
  }

  override def close(): Int = {
    hiveResponse = null
    tableSchema = null
    0
  }

  override def getSchema: Schema = tableSchema

  override def getResults(res: JArrayList[String]): Boolean = {
    if (hiveResponse == null) {
      false
    } else {
      res.addAll(hiveResponse)
      hiveResponse = null
      true
    }
  }

  override def destroy() {
    super.destroy()
    hiveResponse = null
    tableSchema = null
  }
}
