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

package org.apache.spark.sql.execution.datasources.parquet

import org.apache.spark.sql.execution.datasources.DataSourceCodecTest
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession

class ParquetCodecTestSuite extends DataSourceCodecTest with SharedSparkSession {

  override def dataSourceName: String = "parquet"
  override val codecConfigName = SQLConf.PARQUET_COMPRESSION.key
  // Exclude "lzo" because it is GPL-licenced so not included in Hadoop.
  // TODO (SPARK-36669): Add "lz4" back after fix it.
  override protected def availableCodecs: Seq[String] = Seq("none", "uncompressed", "snappy",
    "gzip", "brotli", "zstd")
}

