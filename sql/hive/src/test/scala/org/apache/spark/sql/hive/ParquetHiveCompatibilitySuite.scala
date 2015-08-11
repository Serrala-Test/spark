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

package org.apache.spark.sql.hive

import org.apache.hadoop.hive.conf.HiveConf

import org.apache.spark.sql.hive.test.HiveParquetCompatibilityTest
import org.apache.spark.sql.parquet.ParquetCompatibilityTest
import org.apache.spark.sql.{Row, SQLConf}

class ParquetHiveCompatibilitySuite extends HiveParquetCompatibilityTest {
  import ParquetCompatibilityTest.makeNullable

  /**
   * Set the staging directory (and hence path to ignore Parquet files under)
   * to that set by [[HiveConf.ConfVars.STAGINGDIR]].
   */
  override val stagingDir: Option[String] =
    Some(new HiveConf().getVar(HiveConf.ConfVars.STAGINGDIR))

  override protected def beforeAll(): Unit = {
    super.beforeAll()

    withSQLConf(HiveContext.CONVERT_METASTORE_PARQUET.key -> "false") {
      withTempTable("data") {
        ctx.sql(
          s"""CREATE TABLE parquet_compat(
             |  bool_column BOOLEAN,
             |  byte_column TINYINT,
             |  short_column SMALLINT,
             |  int_column INT,
             |  long_column BIGINT,
             |  float_column FLOAT,
             |  double_column DOUBLE,
             |
             |  strings_column ARRAY<STRING>,
             |  int_to_string_column MAP<INT, STRING>
             |)
             |STORED AS PARQUET
             |LOCATION '${parquetStore.getCanonicalPath}'
           """.stripMargin)

        val schema = ctx.table("parquet_compat").schema
        val rowRDD = ctx.sparkContext.parallelize(makeRows).coalesce(1)
        ctx.createDataFrame(rowRDD, schema).registerTempTable("data")
        ctx.sql("INSERT INTO TABLE parquet_compat SELECT * FROM data")
      }
    }
  }

  override protected def afterAll(): Unit = {
    ctx.sql("DROP TABLE parquet_compat")
  }

  test("Read Parquet file generated by parquet-hive") {
    logInfo(
      s"""Schema of the Parquet file written by parquet-hive:
         |${readParquetSchema(parquetStore.getCanonicalPath)}
       """.stripMargin)

    // Unfortunately parquet-hive doesn't add `UTF8` annotation to BINARY when writing strings.
    // Have to assume all BINARY values are strings here.
    withSQLConf(SQLConf.PARQUET_BINARY_AS_STRING.key -> "true") {
      checkAnswer(ctx.read.parquet(parquetStore.getCanonicalPath), makeRows)
    }
  }

  def makeRows: Seq[Row] = {
    (0 until 10).map { i =>
      def nullable[T <: AnyRef]: ( => T) => T = makeNullable[T](i)

      Row(
        nullable(i % 2 == 0: java.lang.Boolean),
        nullable(i.toByte: java.lang.Byte),
        nullable((i + 1).toShort: java.lang.Short),
        nullable(i + 2: Integer),
        nullable(i.toLong * 10: java.lang.Long),
        nullable(i.toFloat + 0.1f: java.lang.Float),
        nullable(i.toDouble + 0.2d: java.lang.Double),
        nullable(Seq.tabulate(3)(n => s"arr_${i + n}")),
        nullable(Seq.tabulate(3)(n => (i + n: Integer) -> s"val_${i + n}").toMap))
    }
  }
}
