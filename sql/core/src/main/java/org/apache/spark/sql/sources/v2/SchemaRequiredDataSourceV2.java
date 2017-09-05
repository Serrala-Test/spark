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

package org.apache.spark.sql.sources.v2;

import org.apache.spark.sql.sources.v2.reader.DataSourceV2Reader;
import org.apache.spark.sql.types.StructType;

/**
 * A variant of `DataSourceV2` which requires users to provide a schema when reading data. A data
 * source can inherit both `DataSourceV2` and `SchemaRequiredDataSourceV2` if it supports both schema
 * inference and user-specified schemas.
 */
public interface SchemaRequiredDataSourceV2 {

  /**
   * Create a `DataSourceV2Reader` to scan the data for this data source.
   *
   * @param schema the full schema of this data source reader. Full schema usually maps to the
   *               physical schema of the underlying storage of this data source reader, e.g.
   *               CSV files, JSON files, etc, while this reader may not read data with full
   *               schema, as column pruning or other optimizations may happen.
   * @param options the options for this data source reader, which is an immutable case-insensitive
   *                string-to-string map.
   * @return a reader that implements the actual read logic.
   */
  DataSourceV2Reader createReader(StructType schema, DataSourceV2Options options);
}
