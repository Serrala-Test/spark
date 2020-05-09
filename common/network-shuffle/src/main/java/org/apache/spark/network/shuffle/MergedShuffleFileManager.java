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

package org.apache.spark.network.shuffle;

import java.io.IOException;

import org.apache.spark.network.buffer.ManagedBuffer;
import org.apache.spark.network.client.StreamCallbackWithID;
import org.apache.spark.network.shuffle.protocol.FinalizeShuffleMerge;
import org.apache.spark.network.shuffle.protocol.MergeStatuses;
import org.apache.spark.network.shuffle.protocol.PushBlockStream;


/**
 * The MergedShuffleFileManager is used to process push based shuffle when enabled. It works
 * along side {@link ExternalShuffleBlockHandler} and serves as an RPCHandler for
 * {@link org.apache.spark.network.server.RpcHandler#receiveStream}, where it processes the
 * remotely pushed streams of shuffle blocks to merge them into merged shuffle files. Right
 * now, push based shuffle can only be enabled when external shuffle service in YARN mode
 * is used.
 */
public interface MergedShuffleFileManager {
  /**
   * Provides the stream callback used to process a remotely pushed block. The callback is
   * used by the {@link org.apache.spark.network.client.StreamInterceptor} installed on the
   * channel to process the block data in the channel outside of the message frame.
   *
   * @param msg metadata of the remotely pushed blocks. This is processed inside the message frame
   * @return A stream callback to process the block data in streaming fashion as it arrives
   */
  StreamCallbackWithID receiveBlockDataAsStream(PushBlockStream msg);

  /**
   * Handles the request to finalize shuffle merge for a given shuffle.
   *
   * @param msg contains appId and shuffleId to uniquely identify a shuffle to be finalized
   * @return The statuses of the merged shuffle partitions for the given shuffle on this
   *         shuffle service
   * @throws IOException
   */
  MergeStatuses finalizeShuffleMerge(FinalizeShuffleMerge msg) throws IOException;

  /**
   * Registers an application when it starts. This provides the application specific path
   * so MergedShuffleFileManager knows where to store and look for shuffle data for a
   * given application. Right now, this is invoked by YarnShuffleService.
   *
   * @param appId application ID
   * @param relativeAppPath The relative path which is application unique. The actual directory
   *                        path is split into 2 parts, where the first half is one of the
   *                        several configured local dirs that're shared across all applications
   *                        and the second half is application unique.
   */
  void registerApplication(String appId, String relativeAppPath);

  /**
   * Invoked when an application finishes. This cleans up any remaining metadata associated with
   * this application, and optionally deletes the application specific directory path.
   *
   * @param appId application ID
   * @param cleanupLocalDirs flag indicating whether MergedShuffleFileManager should handle
   *                         deletion of local dirs itself. Ideally, we should be able to delegate
   *                         to YARN to handle local dir deletion in YARN mode. This does not work
   *                         as expected yet. See LIHADOOP-52202.
   */
  void applicationRemoved(String appId, boolean cleanupLocalDirs);

  /**
   * Get the buffer for a given merged shuffle chunk when serving merged shuffle to reducers
   *
   * @param appId application ID
   * @param shuffleId shuffle ID
   * @param reduceId reducer ID
   * @param chunkId merged shuffle file chunk ID
   * @return The {@link ManagedBuffer} for the given merged shuffle chunk
   */
  ManagedBuffer getMergedBlockData(String appId, int shuffleId, int reduceId, int chunkId);

  /**
   * Get the number of chunks for a given merged shuffle file.
   *
   * @param appId application ID
   * @param shuffleId shuffle ID
   * @param reduceId reducer ID
   * @return number of chunks
   */
  int getChunkCount(String appId, int shuffleId, int reduceId);
}
