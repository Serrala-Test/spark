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

package org.apache.spark.sql.sources.v2.reader;

import org.apache.spark.annotation.Evolving;
import org.apache.spark.sql.types.StructType;

/**
 * An internal base interface for batch and streaming scan interfaces.
 */
@Evolving
public interface Scan {

  /**
   * Returns the actual schema of this data source scan, which may be different from the physical
   * schema of the underlying storage, as column pruning or other optimizations may happen.
   */
  StructType readSchema();

  /**
   * A description string of this scan, which may includes information like: what filters are
   * configured for this scan, what's the value of some important options like path, etc. The
   * description doesn't need to include {@link #readSchema()}, as Spark already knows it.
   * <p>
   * By default this returns the class name of the implementation. Please override it to provide a
   * meaningful description.
   * </p>
   */
  default String description() {
    return this.getClass().toString();
  }
}
