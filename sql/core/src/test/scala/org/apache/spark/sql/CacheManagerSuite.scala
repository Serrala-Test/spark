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

package org.apache.spark.sql

import org.apache.hadoop.fs.Path

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.test.SharedSparkSession

class CacheManagerSuite extends SparkFunSuite with SharedSparkSession {

  private def isInStorage(name: String): Boolean =
    spark.sparkContext.getRDDStorageInfo.exists(_.name.contains(s"In-memory table $name"))

  test("SPARK-44199: isSubDirectory tests") {
    val cacheManager = spark.sharedState.cacheManager
    val testCases = Map[(String, String), Boolean](
      ("s3://bucket/a/b", "s3://bucket/a/b/c") -> true,
      ("s3://bucket/a/b/c", "s3://bucket/a/b/c") -> true,
      ("s3://bucket/a/b/c", "s3://bucket/a/b") -> false,
      ("s3://bucket/a/z/c", "s3://bucket/a/b/c") -> false,
      ("s3://bucket/a/b/c", "abfs://bucket/a/b/c") -> false)
    testCases.foreach { test =>
      val result = cacheManager.isSubDir(new Path(test._1._1), new Path(test._1._2))
      assert(result == test._2)
    }
  }

  test("SPARK-45039: Cached table name should be the complete identifier in existing table") {
    val db = "dbtest"
    spark.sql(s"CREATE DATABASE $db")
    spark.sql(s"USE $db")
    try {
      val t = "t1"
      val fullIdent = s"spark_catalog.$db.$t"
      spark.sql(s"CREATE TABLE $db.$t USING parquet AS SELECT 1 AS id")
      spark.sql(s"CACHE TABLE $t")
      assert(isInStorage(fullIdent))
      spark.sql(s"UNCACHE TABLE $t")
      assert(!isInStorage(fullIdent))
      spark.sql(s"CACHE TABLE $db.$t")
      assert(isInStorage(fullIdent))
      spark.sql(s"UNCACHE TABLE $db.$t")
      assert(!isInStorage(fullIdent))
      spark.sql(s"CACHE TABLE $fullIdent")
      assert(isInStorage(fullIdent))
      spark.sql(s"UNCACHE TABLE $fullIdent")
      assert(!isInStorage(fullIdent))
    } finally {
      spark.sql(s"DROP DATABASE $db CASCADE")
    }
  }

  test("SPARK-45039: Cached table as select name should be the name of the temporary table") {
    val view = "v1"
    spark.sql(s"CACHE TABLE $view AS SELECT 1 AS id")
    assert(isInStorage(view))
    spark.sql(s"UNCACHE TABLE $view")
    assert(!isInStorage(view))
  }
}
