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

import java.io.File

import org.apache.spark.Logging

/**
 * Ensures that on each executor, there are no conflicting writes to the same shuffle files.  It
 * implements "first write wins", by atomically moving all shuffle files into their final location,
 * only if the files did not already exist. See SPARK-8029
 */
object ShuffleOutputCoordinator extends Logging {

  /**
   * if all of the destination files do not exist, then move all of the temporary files to their
   * destinations.  If any destination files exist, then simply delete all temporary files
   *
   * @param tmpToDest pairs of (temporary, destination) file pairs
   * @return
   */
  def commitOutputs(
      shuffleId: Int,
      partitionId: Int, tmpToDest: Seq[(File, File)]): Boolean = synchronized {
    logInfo(s"renaming: $tmpToDest")

    // HashShuffleWriter might not write any records to some of its files -- that's OK, we only
    // move the files that do exist
    val toMove = tmpToDest.filter{_._1.exists()}

    val destAlreadyExists = toMove.forall(_._2.exists)
    if (!destAlreadyExists) {
      // if any of the renames fail, delete all the dest files.  otherwise, future
      // attempts have no hope of succeeding
      val renamesSucceeded = toMove.map { case (tmp, dest) =>
        if (dest.exists()) {
          dest.delete()
        }
        val r = tmp.renameTo(dest)
        if (!r) {
          logInfo(s"failed to rename $tmp to $dest.  ${tmp.exists()}; ${dest.exists()}")
        }
        r
      }.forall{identity}
      if (!renamesSucceeded) {
        toMove.foreach { case (tmp, dest) => if (dest.exists()) dest.delete() }
        false
      } else {
        true
      }
    } else {
      logInfo(s"shuffle output for shuffle $shuffleId, partition $partitionId already exists, " +
        s"not overwriting.  Another task must have created this shuffle output.")
      toMove.foreach{ case (tmp, _) => tmp.delete()}
      false
    }
  }
}
