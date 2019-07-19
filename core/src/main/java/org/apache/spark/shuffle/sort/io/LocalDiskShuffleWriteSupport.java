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

package org.apache.spark.shuffle.sort.io;

import org.apache.spark.SparkConf;
import org.apache.spark.shuffle.ShuffleWriteMetricsReporter;
import org.apache.spark.shuffle.api.ShuffleMapOutputWriter;
import org.apache.spark.shuffle.api.ShuffleWriteSupport;
import org.apache.spark.shuffle.IndexShuffleBlockResolver;

public class LocalDiskShuffleWriteSupport implements ShuffleWriteSupport {

  private final SparkConf sparkConf;
  private final IndexShuffleBlockResolver blockResolver;

  public LocalDiskShuffleWriteSupport(
      SparkConf sparkConf,
      IndexShuffleBlockResolver blockResolver) {
    this.sparkConf = sparkConf;
    this.blockResolver = blockResolver;
  }

  @Override
  public ShuffleMapOutputWriter createMapOutputWriter(
      int shuffleId,
      int mapId,
      long mapTaskAttemptId,
      int numPartitions,
      ShuffleWriteMetricsReporter writeMetrics) {
    return new LocalDiskShuffleMapOutputWriter(
      shuffleId, mapId, numPartitions, writeMetrics, blockResolver, sparkConf);
  }
}
