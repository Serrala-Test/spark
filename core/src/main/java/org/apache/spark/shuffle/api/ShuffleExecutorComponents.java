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

package org.apache.spark.shuffle.api;

import java.io.IOException;
import java.util.Optional;

import org.apache.spark.annotation.Private;

/**
 * :: Private ::
 * An interface for building shuffle support for Executors.
 *
 * @since 3.0.0
 */
@Private
public interface ShuffleExecutorComponents {

  /**
   * Called once per executor to bootstrap this module with state that is specific to
   * that executor, specifically the application ID and executor ID.
   */
  void initializeExecutor(String appId, String execId);

  /**
   * Called once per map task to create a writer that will be responsible for persisting all the
   * partitioned bytes written by that map task.
   * @param shuffleId Unique identifier for the shuffle the map task is a part of
   * @param mapTaskAttemptId Identifier of the task attempt. Multiple attempts of the same map task
   *                         with the same (shuffleId, mapId) pair can be distinguished by the
   *                         different values of mapTaskAttemptId.
   * @param numPartitions The number of partitions that will be written by the map task. Some of
   *                      these partitions may be empty.
   */
  ShuffleMapOutputWriter createMapOutputWriter(
      int shuffleId,
      long mapTaskAttemptId,
      int numPartitions) throws IOException;

  /**
   * An optional extension for creating a map output writer that can optimize the transfer of a
   * single partition file, as the entire result of a map task, to the backing store.
   * <p>
   * Most implementations should return the default {@link Optional#empty()} to indicate that
   * they do not support this optimization. This primarily is for backwards-compatibility in
   * preserving an optimization in the local disk shuffle storage implementation.
   *
   * @param shuffleId Unique identifier for the shuffle the map task is a part of
   * @param mapTaskAttemptId Identifier of the task attempt. Multiple attempts of the same map task
   *                         with the same (shuffleId, mapId) pair can be distinguished by the
   *                         different values of mapTaskAttemptId.
   */
  default Optional<SingleSpillShuffleMapOutputWriter> createSingleFileMapOutputWriter(
      int shuffleId,
      long mapTaskAttemptId) throws IOException {
    return Optional.empty();
  }
}
