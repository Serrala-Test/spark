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

package org.apache.spark.sql.connector.expressions;

import java.io.Serializable;
import java.util.Arrays;

import org.apache.spark.annotation.Evolving;
import org.apache.spark.sql.connector.util.V2ExpressionSQLBuilder;

// scalastyle:off line.size.limit
/**
 * The general representation of SQL scalar expressions, which contains the upper-cased
 * expression name and all the children expressions.
 * <p>
 * The currently supported SQL aggregate functions:
 * <ol>
 *  <li><pre>Name: &lt;&gt;</pre><pre>SQL semantic: `expr1 &lt;&gt; expr2`</pre> ANSI enabled: No, Since version 3.3.0</li>
 * </ol>
 *
 * @since 3.3.0
 */
// scalastyle:on line.size.limit
@Evolving
public class GeneralScalarExpression implements Expression, Serializable {
  private String name;
  private Expression[] children;

  public GeneralScalarExpression(String name, Expression[] children) {
    this.name = name;
    this.children = children;
  }

  public String name() { return name; }
  public Expression[] children() { return children; }

  @Override
  public String toString() {
    V2ExpressionSQLBuilder builder = new V2ExpressionSQLBuilder();
    try {
      return builder.build(this);
    } catch (Throwable e) {
      return name + "(" +
        Arrays.stream(children).map(child -> child.toString()).reduce((a,b) -> a + "," + b) + ")";
    }
  }
}
