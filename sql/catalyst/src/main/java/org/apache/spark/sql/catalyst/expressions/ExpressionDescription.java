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

package org.apache.spark.sql.catalyst.expressions;

import org.apache.spark.annotation.DeveloperApi;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * ::DeveloperApi::
 *
 * A function description type which can be recognized by FunctionRegistry, and will be used to
 * show the usage of the function in human language.
 *
 * `usage()` will be used for the function usage in brief way.
 *
 * These below are concatenated and used for the function usage in verbose way, suppose arguments,
 * examples, note and since will be provided.
 *
 * `arguments()` describes arguments for the expression. This should follow the format as below:
 *
 *   Arguments:
 *     * arg0 - ...
 *         ....
 *     * arg1 - ...
 *         ....
 *
 * `examples()` describes examples for the expression. This should follow the format as below:
 *
 *   Examples:
 *     > SELECT ...;
 *      ...
 *     > SELECT ...;
 *      ...
 *
 * `note()` contains some notes for the expression optionally. This property should have 4
 * leading spaces and end with a newline.
 *
 * `since()` contains version information for the expression. Version is specified by,
 * for example, "2.2.0". It should not start with non-number characters.
 *
 * `deprecated()` contains deprecation information for the expression optionally, for example,
 * "Deprecated since 2.2.0. Use something else instead". This property should have 4
 *  leading spaces and end with a newline.
 *
 *  We can refer the function name by `_FUNC_`, in `usage`, `arguments` and `examples`, as it's
 *  registered in `FunctionRegistry`.
 *
 *  Note that, if `extended()` is defined, `arguments()`, `examples()`, `note()`, `since()` and
 *  `deprecated()` should be not defined together. `extended()` exists for backward compatibility.
 */
@DeveloperApi
@Retention(RetentionPolicy.RUNTIME)
public @interface ExpressionDescription {
    String usage() default "";
    String extended() default "";
    String arguments() default "";
    String examples() default "";
    String note() default "";
    String since() default "";
    String deprecated() default "";
}
