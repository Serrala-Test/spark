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

package org.apache.spark.graphx

import org.apache.spark.{Logging, SparkContext}
import org.apache.spark.graphx.impl.{EdgePartitionBuilder, GraphImpl}

/**
 * Provides utilities for loading [[Graph]]s from files.
 */
object GraphLoader extends Logging {

  /**
   * Loads a graph from an edge list formatted file where each line contains atleast two integers: a source
   * id and a target id. If third Integer is porivided that is used as edge attribute; default edge attribute is 1.
   * Skips lines that begin with `#`.
   *
   * If desired the edges can be automatically oriented in the positive
   * direction (source Id < target Id) by setting `canonicalOrientation` to
   * true.
   *
   * @example Loads a file in the following format:
   * {{{
   * # Comment Line
   * # Source Id <\t> Target Id [<\t> Edge attribute]
   * 1   -5   100 
   * 1    2   200
   * 2    7   
   * 1    8
   * }}}
   *
   * @param sc SparkContext
   * @param path the path to the file (e.g., /home/data/file or hdfs://file)
   * @param canonicalOrientation whether to orient edges in the positive
   *        direction
   * @param minEdgePartitions the number of partitions for the edge RDD
   */
  def edgeListFile(
      sc: SparkContext,
      path: String,
      canonicalOrientation: Boolean = false,
      minEdgePartitions: Int = 1)
    : Graph[Int, Int] =
  {
    val startTime = System.currentTimeMillis

    // Parse the edge data table directly into edge partitions
    val lines = sc.textFile(path, minEdgePartitions).coalesce(minEdgePartitions)
    val edges = lines.mapPartitionsWithIndex { (pid, iter) =>
      val builder = new EdgePartitionBuilder[Int, Int]
      iter.foreach { line =>
        if (!line.isEmpty && line(0) != '#') {
          val lineArray = line.split("\\s+")
          if (lineArray.length < 2) {
            logWarning("Invalid line: " + line)
          }
          val srcId = lineArray(0).toLong
          val dstId = lineArray(1).toLong
          val edgeAttr = 
            if (lineArray.length >= 3) lineArray(2).toInt
            else 1
          if (canonicalOrientation && srcId > dstId) {
            builder.add(dstId, srcId, edgeAttr)
          } else {
            builder.add(srcId, dstId, edgeAttr)
          }
        }
      }
      Iterator((pid, builder.toEdgePartition))
    }.cache().setName("GraphLoader.edgeListFile - edges (%s)".format(path))
    edges.count()

    logInfo("It took %d ms to load the edges".format(System.currentTimeMillis - startTime))

    GraphImpl.fromEdgePartitions(edges, defaultVertexAttr = 1)
  } // end of edgeListFile

}
