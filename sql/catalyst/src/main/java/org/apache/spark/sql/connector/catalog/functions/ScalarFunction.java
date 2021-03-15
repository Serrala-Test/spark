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

package org.apache.spark.sql.connector.catalog.functions;

import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.types.DataType;

/**
 * Interface for a function that produces a result value for each input row.
 * <p>
 * For each input row, Spark will call a produceResult method that corresponds to the
 * {@link #inputTypes() input data types}. The expected JVM argument types must be the types used by
 * Spark's InternalRow API. If no direct method is found or when not using codegen, Spark will call
 * {@link #produceResult(InternalRow)}.
 * <p>
 * The JVM type of result values produced by this function must be the type used by Spark's
 * InternalRow API for the {@link DataType SQL data type} returned by {@link #resultType()}.
 * <p>
 * <b>IMPORTANT</b>: the default implementation of {@link #produceResult} throws
 * {@link UnsupportedOperationException}. Users can choose to override this method, or implement
 * a "magic method" with name {@link #MAGIC_METHOD_NAME} which takes individual parameters
 * instead of a {@link InternalRow}. The magic method will be loaded by Spark through Java
 * reflection and also will provide better performance in general, due to optimizations such as
 * codegen, Java boxing and so on.
 *
 * For example, a scalar UDF for adding two integers can be defined as follow with the magic
 * method approach:
 *
 * <pre>
 *   {@code
 *     public class IntegerAdd implements ScalarFunction<Integer> {
 *       public int invoke(int left, int right) {
 *         return left + right;
 *       }
 *
 *       @Overrides
 *       public produceResult(InternalRow input) {
 *         int left = input.getInt(0);
 *         int right = input.getInt(1);
 *         return left + right;
 *       }
 *     }
 *   }
 * </pre>
 * In this case, both {@link #MAGIC_METHOD_NAME} and {@link #produceResult} are defined, and Spark will
 * first lookup the {@code invoke} method during query analysis. It checks whether the method
 * parameters have the valid types that are supported by Spark. If the check fails it falls back
 * to use {@link #produceResult}.
 *
 * @param <R> the JVM type of result values
 */
public interface ScalarFunction<R> extends BoundFunction {
  String MAGIC_METHOD_NAME = "invoke";

  /**
   * Applies the function to an input row to produce a value.
   *
   * @param input an input row
   * @return a result value
   */
  default R produceResult(InternalRow input) {
    throw new UnsupportedOperationException(
        "Cannot find a compatible ScalarFunction#produceResult");
  }

}
