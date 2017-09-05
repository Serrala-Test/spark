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

package org.apache.spark.sql.sources.v2.reader.scan;

import java.util.List;

import org.apache.spark.annotation.Experimental;
import org.apache.spark.annotation.InterfaceStability;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.catalyst.expressions.UnsafeRow;
import org.apache.spark.sql.sources.v2.reader.DataSourceV2Reader;
import org.apache.spark.sql.sources.v2.reader.ReadTask;

/**
 * A mix-in interface for `DataSourceV2Reader`. Users can implement this interface to output
 * unsafe rows directly and avoid the row copy at Spark side.
 *
 * Note that, this is an experimental and unstable interface, as `UnsafeRow` is not public and
 * may get changed in future Spark versions.
 */
@Experimental
@InterfaceStability.Unstable
public interface UnsafeRowScan extends DataSourceV2Reader {

  @Override
  default List<ReadTask<Row>> createReadTasks() {
    throw new IllegalStateException("createReadTasks should not be called with UnsafeRowScan.");
  }

  /**
   * Similar to `DataSourceV2Reader.createReadTasks`, but return data in unsafe row format.
   */
  List<ReadTask<UnsafeRow>> createUnsafeRowReadTasks();
}
