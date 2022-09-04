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
package org.apache.spark.sql;

import org.apache.spark.annotation.Stable;

/**
 * SaveMode is used to specify the expected behavior of saving a DataFrame to a data source.
 *
 * @since 1.3.0
 */
@Stable
public enum SaveMode {
  /**
   * Append mode means that when saving a DataFrame to a data source, if data/table already exists,
   * contents of the DataFrame are expected to be appended to existing data.
   *
   * @since 1.3.0
   */
  Append,
  /**
   * Overwrite mode means that when saving a DataFrame to a data source,
   * if data/table already exists, existing data is expected to be overwritten by the contents of
   * the DataFrame.
   *
   * @since 1.3.0
   */
  Overwrite,
  /**
   * ErrorIfExists mode means that when saving a DataFrame to a data source, if data already exists,
   * an exception is expected to be thrown.
   *
   * @since 1.3.0
   */
  ErrorIfExists,
  /**
   * Ignore mode means that when saving a DataFrame to a data source, if data already exists,
   * the save operation is expected to not save the contents of the DataFrame and to not
   * change the existing data.
   *
   * @since 1.3.0
   */
  Ignore,
  /**
   * REPLACE INTO用于实时覆盖写入数据。写入数据时，会先根据主键判断待写入的数据是否已经存在于表中，并根据判断结果选择不同的方式写入数据：
   * 如果待写入数据已经存在，则先删除该行数据，然后插入新的数据。
   * 如果待写入数据不存在，则直接插入新数据。--by Jyong 20220904 china
   *
   */
  ReplaceInto
}
