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

package org.apache.spark.shuffle

import scala.collection.JavaConverters._

import org.apache.spark.SparkEnv
import org.apache.spark.shuffle.api.{ShuffleDataIO, ShuffleDriverComponents, ShuffleExecutorComponents}

/**
 * Thin wrapper around {@link ShuffleDataIO} that ensures the given components are
 * only initialized once and providing the same instance each time.
 * <p>
 * Used to ensure the SparkEnv only instantiates the given components once lazily
 * and then reuses them throughout the lifetime of the SparkEnv.
 */
class MemoizingShuffleDataIO(delegate: ShuffleDataIO) {
  private lazy val _driver = delegate.initializeShuffleDriverComponents()
  private lazy val _executor = {
    val env = SparkEnv.get
    delegate.initializeShuffleExecutorComponents(
      env.conf.getAppId,
      env.executorId,
      env.conf.getAllWithPrefix(ShuffleDataIOUtils.SHUFFLE_SPARK_CONF_PREFIX).toMap.asJava)
  }

  def driver(): ShuffleDriverComponents = _driver

  def executor(): ShuffleExecutorComponents = _executor
}
