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

package org.apache.spark;

/**
 * Interface mixed into Throwables thrown from Spark.
 *
 * - For backwards compatibility, existing throwable types can be thrown with an arbitrary error
 *   message with no error class. See [[SparkException]].
 * - To promote standardization, throwables should be thrown with an error class and message
 *   parameters to construct an error message with SparkThrowableHelper.getMessage(). New throwable
 *   types should not accept arbitrary error messages. See [[SparkArithmeticException]].
 */
public interface SparkThrowable {
    // Succinct, human-readable, unique, and consistent representation of the error category
    String getErrorClass();

    // Parameters provided to format the error message
    String[] getMessageParameters();

    // Optional portable error identifier across SQL engines
    String getSqlState();
}
