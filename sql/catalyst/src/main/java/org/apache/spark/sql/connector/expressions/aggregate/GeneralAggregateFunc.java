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

package org.apache.spark.sql.connector.expressions.aggregate;

import java.util.Arrays;

import org.apache.spark.annotation.Evolving;
import org.apache.spark.sql.connector.expressions.Expression;
import org.apache.spark.sql.connector.util.ToStringSQLBuilder;

/**
 * The general implementation of {@link AggregateFunc}, which contains the upper-cased function
 * name, the `isDistinct` flag and all the inputs. Note that Spark cannot push down partial
 * aggregate with this function to the source, but can only push down the entire aggregate.
 * <p>
 * The currently supported SQL aggregate functions:
 * <ol>
 *  <li><pre>VAR_POP(input1)</pre> Since 3.3.0</li>
 *  <li><pre>VAR_SAMP(input1)</pre> Since 3.3.0</li>
 *  <li><pre>STDDEV_POP(input1)</pre> Since 3.3.0</li>
 *  <li><pre>STDDEV_SAMP(input1)</pre> Since 3.3.0</li>
 *  <li><pre>COVAR_POP(input1, input2)</pre> Since 3.3.0</li>
 *  <li><pre>COVAR_SAMP(input1, input2)</pre> Since 3.3.0</li>
 *  <li><pre>CORR(input1, input2)</pre> Since 3.3.0</li>
 * </ol>
 *
 * @since 3.3.0
 */
@Evolving
public final class GeneralAggregateFunc implements AggregateFunc {
  private final String name;
  private final boolean isDistinct;
  private final Expression[] children;

  public GeneralAggregateFunc(String name, boolean isDistinct, Expression[] children) {
    this.name = name;
    this.isDistinct = isDistinct;
    this.children = children;
  }

  public String name() { return name; }
  public boolean isDistinct() { return isDistinct; }

  @Override
  public Expression[] children() { return children; }

  @Override
  public String toString() {
    ToStringSQLBuilder builder = new ToStringSQLBuilder();
    try {
      return builder.build(this);
    } catch (Throwable e) {
      if (isDistinct) {
        return name + "(DISTINCT" +
          Arrays.stream(children).map(child -> child.toString()).reduce((a,b) -> a + "," + b) + ")";
      } else {
        return name + "(" +
          Arrays.stream(children).map(child -> child.toString()).reduce((a,b) -> a + "," + b) + ")";
      }
    }
  }
}
