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

package org.apache.spark.sql.connector.write;

import org.apache.spark.annotation.Evolving;
import org.apache.spark.sql.connector.catalog.Column;
import org.apache.spark.sql.connector.catalog.Table;

/**
 * Trait for tables that support custom schemas for write operations including INSERT INTO commands
 * whose target table columns have explicit or implicit default values.
 *
 * @since 3.5.0
 */
@Evolving
public interface SupportsCustomSchemaWrite extends Table {
    /**
     * Represents a table with a custom schema to use for resolving DEFAULT column references when
     * inserting into the table. For example, this can be useful for excluding hidden pseudocolumns.
     *
     * @return the new schema to use for this process.
     */
    Column[] customColumnsForInserts();
}
