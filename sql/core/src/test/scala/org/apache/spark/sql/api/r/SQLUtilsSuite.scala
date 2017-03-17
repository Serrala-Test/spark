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

package org.apache.spark.sql.api.r

import java.util.HashMap

import org.apache.spark.{SparkConf, SparkContext, SparkFunSuite}
import org.apache.spark.api.java.JavaSparkContext
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.test.SharedSQLContext

class SQLUtilsSuite extends SparkFunSuite {

  test("dfToCols should collect and transpose a data frame") {
    val sparkSession = SparkSession.builder()
      .master("local")
      .config("spark.ui.enabled", value = false)
      .getOrCreate()

    import sparkSession.implicits._

    val df = Seq(
      (1, 2, 3),
      (4, 5, 6)
    ).toDF
    assert(SQLUtils.dfToCols(df) === Array(
      Array(1, 4),
      Array(2, 5),
      Array(3, 6)
    ))
    sparkSession.stop()
  }

  test("warehouse path is set correctly by R constructor") {
    SparkSession.clearDefaultSession()
    val conf = new SparkConf().setAppName("test").setMaster("local")
    val sparkContext2 = new SparkContext(conf)
    val jsc = new JavaSparkContext(sparkContext2)
    val warehouseDir = "/tmp/test-warehouse-dir"
    val session = SQLUtils.getOrCreateSparkSession(
      jsc, new HashMap[Object, Object], false, warehouseDir)
    assert(session.sessionState.conf.warehousePath == warehouseDir)
    session.stop()
    SparkSession.clearDefaultSession()
  }
}
