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

package org.apache.spark.sql.execution.command

import org.apache.spark.sql.{AnalysisException, QueryTest, Row}

trait DropTableSuiteBase extends QueryTest with DDLCommandTestUtils {
  override val command = "DROP TABLE"

  protected def createTable(tableName: String): Unit = {
    sql(s"CREATE TABLE $tableName (c int) $defaultUsing")
    sql(s"INSERT INTO $tableName SELECT 0")
  }

  protected def checkTables(namespace: String, expectedTables: String*): Unit = {
    val tables = sql(s"SHOW TABLES IN $catalog.$namespace").select("tableName")
    val rows = expectedTables.map(Row(_))
    checkAnswer(tables, rows)
  }

  test("basic") {
    withNamespace(s"$catalog.ns") {
      sql(s"CREATE NAMESPACE $catalog.ns")

      createTable(s"$catalog.ns.tbl")
      checkTables("ns", "tbl")

      sql(s"DROP TABLE $catalog.ns.tbl")
      checkTables("ns") // no tables
    }
  }

  test("if exists") {
    withNamespace(s"$catalog.ns") {
      sql(s"CREATE NAMESPACE $catalog.ns")

      val errMsg = intercept[AnalysisException] {
        sql(s"DROP TABLE $catalog.ns.notbl")
      }.getMessage
      assert(errMsg.contains("Table or view not found"))

      sql(s"DROP TABLE IF EXISTS $catalog.ns.notbl")
      checkTables("ns")
    }
  }
}
