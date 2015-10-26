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

package org.apache.spark.sql.hive.mapred;

import java.io.IOException;
import java.util.*;

import com.clearspring.analytics.util.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.hadoop.mapred.*;

public class CombineSplitInputFormat<K, V> implements InputFormat<K, V> {

  private InputFormat<K, V> inputformat;
  private long splitSize = 0;

  public CombineSplitInputFormat(InputFormat<K, V> inputformat, long splitSize) {
    this.inputformat = inputformat;
    this.splitSize = splitSize;
  }

  /**
   * Create a combine split from the list of splits
   * Add this new combine split into splitList.
   */
  private void addCreatedSplit(List<CombineSplit> combineSplits,
                               long totalLen,
                               Collection<String> locations,
                               List<InputSplit> combineSplitBuffer) {
    CombineSplit combineSparkSplit =
      new CombineSplit(combineSplitBuffer.toArray(new InputSplit[0]),
        totalLen, locations.toArray(new String[0]));
    combineSplits.add(combineSparkSplit);
  }

  @Override
  public InputSplit[] getSplits(JobConf job, int numSplits) throws IOException {
    InputSplit[] splits = inputformat.getSplits(job, numSplits);
    // populate nodeToSplits and splitsSet
    Map<String, List<InputSplit>> nodeToSplits = Maps.newHashMap();
    Set<InputSplit> splitsSet = Sets.newHashSet();
    for (InputSplit split: splits) {
      for (String node: split.getLocations()) {
        if (!nodeToSplits.containsKey(node)) {
          nodeToSplits.put(node, new ArrayList<InputSplit>());
        }
        nodeToSplits.get(node).add(split);
      }
      splitsSet.add(split);
    }
    // Iterate the nodes to combine in order to evenly distributing the splits
    List<CombineSplit> combineSparkSplits = Lists.newArrayList();
    List<InputSplit> combinedSplitBuffer = Lists.newArrayList();
    long currentSplitSize = 0L;
    for (Map.Entry<String, List<InputSplit>> entry: nodeToSplits.entrySet()) {
      String node = entry.getKey();
      List<InputSplit> splitsPerNode = entry.getValue();
      for (InputSplit split: splitsPerNode) {
        // this split has been combined
        if (!splitsSet.contains(split)) {
          continue;
        } else {
          currentSplitSize += split.getLength();
          combinedSplitBuffer.add(split);
          splitsSet.remove(split);
        }
        if (splitSize != 0 && currentSplitSize > splitSize) {
          // TODO: optimize this by providing the second/third preference locations
          addCreatedSplit(combineSparkSplits,
            currentSplitSize, Collections.singleton(node), combinedSplitBuffer);
          currentSplitSize = 0;
          combinedSplitBuffer.clear();
        }
      }
      // populate the remaining splits into one combined split
      if (!combinedSplitBuffer.isEmpty()) {
        long remainLen = 0;
        for (InputSplit s: combinedSplitBuffer) {
          remainLen += s.getLength();
        }
        addCreatedSplit(combineSparkSplits,
          remainLen, Collections.singleton(node), combinedSplitBuffer);
        currentSplitSize = 0;
        combinedSplitBuffer.clear();
      }
    }
    return combineSparkSplits.toArray(new InputSplit[0]);
  }

  @Override
  public RecordReader getRecordReader(InputSplit split, JobConf job, Reporter reporter) throws IOException {
     return new CombineSplitRecordReader(job, (CombineSplit)split, inputformat);
  }
}
