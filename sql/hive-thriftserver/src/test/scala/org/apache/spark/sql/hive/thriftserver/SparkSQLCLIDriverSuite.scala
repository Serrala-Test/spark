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

import org.apache.hadoop.hive.cli.CliSessionState
import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.ql.session.SessionState
import org.mockito.ArgumentMatcher
import org.mockito.Matchers.argThat
import org.mockito.Mockito._

import org.apache.spark.SparkFunSuite

class SparkSQLCLIDriverSuite extends SparkFunSuite {

  def matchSQL(sql: String, candidates: String*): Unit = {
    class SQLMatcher extends ArgumentMatcher[String] {
      override def matches(command: Any): Boolean =
        candidates.contains(command.asInstanceOf[String])
    }

    val conf = new HiveConf(classOf[SessionState])
    val sessionState = new CliSessionState(conf)
    SessionState.start(sessionState)
    val cli = mock(classOf[SparkSQLCLIDriver])

    when(cli.processCmd(argThat(new SQLMatcher))).thenReturn(0)
    assert(cli.processLine(sql) == 0)
  }

  test("SPARK-26312: Split a SQL in a correct way") {
    // semicolon in a string
    val sql =
      """
        |select "^;^"
      """.stripMargin.trim
    matchSQL(sql, sql)

    // normal statements
    val statements =
      """
        |select d from dada;
        |select a from dada
      """.stripMargin
    val dStatement = "select d from dada"
    val aStatement = "select a from dada"
    matchSQL(statements, dStatement, aStatement)
  }
}
