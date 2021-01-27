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

package org.apache.spark.sql.connector.distributions;

import org.apache.spark.annotation.Experimental;
import org.apache.spark.sql.connector.expressions.Expression;

/**
 * A distribution where tuples that share the same values for clustering expressions are co-located
 * in the same partition.
 *
 * @since 3.2.0
 */
@Experimental
public interface ClusteredDistribution extends Distribution {
  /**
   * Returns clustering expressions.
   */
  Expression[] clustering();

  /**
   * Returns the number of partitions required by this write.
   * <p>
   * Implementations may want to override this if it requires the specific number of partitions.
   *
   * @return the required number of partitions, non-positive values mean no requirement.
   */
  default int requiredNumPartitions() { return 0; }
}
