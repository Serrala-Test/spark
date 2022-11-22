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

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.roaringbitmap.RoaringBitmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.*;

import org.apache.spark.network.buffer.FileSegmentManagedBuffer;
import org.apache.spark.network.client.StreamCallbackWithID;
import org.apache.spark.network.server.BlockPushNonFatalFailure;
import org.apache.spark.network.shuffle.RemoteBlockPushResolver.MergeShuffleFile;
import org.apache.spark.network.shuffle.protocol.BlockPushReturnCode;
import org.apache.spark.network.shuffle.protocol.BlockTransferMessage;
import org.apache.spark.network.shuffle.protocol.ExecutorShuffleInfo;
import org.apache.spark.network.shuffle.protocol.FinalizeShuffleMerge;
import org.apache.spark.network.shuffle.protocol.MergeStatuses;
import org.apache.spark.network.shuffle.protocol.PushBlockStream;
import org.apache.spark.network.util.MapConfigProvider;
import org.apache.spark.network.util.TransportConf;

/**
 * Tests for {@link RemoteBlockPushResolver}.
 */
public class RemoteBlockPushResolverSuite {

  private static final Logger log = LoggerFactory.getLogger(RemoteBlockPushResolverSuite.class);
  private final String TEST_APP = "testApp";
  private final String MERGE_DIRECTORY = "merge_manager";
  private final int NO_ATTEMPT_ID = -1;
  private final int ATTEMPT_ID_1 = 1;
  private final int ATTEMPT_ID_2 = 2;
  private final String MERGE_DIRECTORY_META = "shuffleManager:{\"mergeDir\": \"merge_manager\"}";
  private final String MERGE_DIRECTORY_META_1 =
    "shuffleManager:{\"mergeDir\": \"merge_manager_1\", \"attemptId\": \"1\"}";
  private final String MERGE_DIRECTORY_META_2 =
    "shuffleManager:{\"mergeDir\": \"merge_manager_2\", \"attemptId\": \"2\"}";
  private final String INVALID_MERGE_DIRECTORY_META =
          "shuffleManager:{\"mergeDirInvalid\": \"merge_manager_2\", \"attemptId\": \"2\"}";
  private final String BLOCK_MANAGER_DIR = "blockmgr-193d8401";

  private TransportConf conf;
  private RemoteBlockPushResolver pushResolver;
  private Path[] localDirs;

  @Before
  public void before() throws IOException {
    localDirs = createLocalDirs(2);
    MapConfigProvider provider = new MapConfigProvider(
      ImmutableMap.of("spark.shuffle.push.server.minChunkSizeInMergedShuffleFile", "4"));
    conf = new TransportConf("shuffle", provider);
    pushResolver = new RemoteBlockPushResolver(conf, null);
    registerExecutor(TEST_APP, prepareLocalDirs(localDirs, MERGE_DIRECTORY), MERGE_DIRECTORY_META);
  }

  @After
  public void after() {
    try {
      for (Path local : localDirs) {
        FileUtils.deleteDirectory(local.toFile());
      }
      removeApplication(TEST_APP);
    } catch (Exception e) {
      // don't fail if clean up doesn't succeed.
      log.debug("Error while tearing down", e);
    }
  }

  @Test
  public void testErrorLogging() {
    ErrorHandler.BlockPushErrorHandler errorHandler = RemoteBlockPushResolver.createErrorHandler();
    assertFalse(errorHandler.shouldLogError(new BlockPushNonFatalFailure(
      BlockPushNonFatalFailure.ReturnCode.TOO_LATE_BLOCK_PUSH, "")));
    assertFalse(errorHandler.shouldLogError(new BlockPushNonFatalFailure(
      BlockPushNonFatalFailure.ReturnCode.TOO_OLD_ATTEMPT_PUSH, "")));
    assertFalse(errorHandler.shouldLogError(new BlockPushNonFatalFailure(
      BlockPushNonFatalFailure.ReturnCode.STALE_BLOCK_PUSH, "")));
    assertFalse(errorHandler.shouldLogError(new BlockPushNonFatalFailure(
      BlockPushNonFatalFailure.ReturnCode.BLOCK_APPEND_COLLISION_DETECTED, "")));
    assertTrue(errorHandler.shouldLogError(new Throwable()));
  }

  @Test
  public void testNoIndexFile() {
    RuntimeException re = assertThrows(RuntimeException.class,
      () -> pushResolver.getMergedBlockMeta(TEST_APP, 0, 0, 0));
    assertTrue(re.getMessage().startsWith("Merged shuffle index file"));
  }

  @Test
  public void testBasicBlockMerge() throws IOException {
    PushBlock[] pushBlocks = new PushBlock[] {
      new PushBlock(0, 0, 0, 0, ByteBuffer.wrap(new byte[4])),
      new PushBlock(0,  0, 1, 0, ByteBuffer.wrap(new byte[5]))
    };
    pushBlockHelper(TEST_APP, NO_ATTEMPT_ID, pushBlocks);
    MergeStatuses statuses = pushResolver.finalizeShuffleMerge(
      new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 0, 0));
    validateMergeStatuses(statuses, new int[] {0}, new long[] {9});
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0, 0);
    validateChunks(TEST_APP, 0, 0, 0, blockMeta, new int[]{4, 5}, new int[][]{{0}, {1}});
  }

  @Test
  public void testDividingMergedBlocksIntoChunks() throws IOException {
    PushBlock[] pushBlocks = new PushBlock[] {
      new PushBlock(0, 0, 0, 0, ByteBuffer.wrap(new byte[2])),
      new PushBlock(0, 0, 1, 0, ByteBuffer.wrap(new byte[3])),
      new PushBlock(0, 0, 2, 0, ByteBuffer.wrap(new byte[5])),
      new PushBlock(0, 0, 3, 0, ByteBuffer.wrap(new byte[3]))
    };
    pushBlockHelper(TEST_APP, NO_ATTEMPT_ID, pushBlocks);
    MergeStatuses statuses = pushResolver.finalizeShuffleMerge(
      new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 0, 0));
    validateMergeStatuses(statuses, new int[] {0}, new long[] {13});
    MergedBlockMeta meta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0, 0);
    validateChunks(TEST_APP, 0, 0, 0, meta, new int[]{5, 5, 3}, new int[][]{{0, 1}, {2}, {3}});
  }

  @Test
  public void testFinalizeWithMultipleReducePartitions() throws IOException {
    PushBlock[] pushBlocks = new PushBlock[] {
      new PushBlock(0, 0, 0, 0, ByteBuffer.wrap(new byte[2])),
      new PushBlock(0, 0, 1, 0, ByteBuffer.wrap(new byte[3])),
      new PushBlock(0, 0, 0, 1, ByteBuffer.wrap(new byte[5])),
      new PushBlock(0, 0, 1, 1, ByteBuffer.wrap(new byte[3]))
    };
    pushBlockHelper(TEST_APP, NO_ATTEMPT_ID, pushBlocks);
    MergeStatuses statuses = pushResolver.finalizeShuffleMerge(
      new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 0, 0));
    validateMergeStatuses(statuses, new int[] {0, 1}, new long[] {5, 8});
    MergedBlockMeta meta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0, 0);
    validateChunks(TEST_APP, 0, 0, 0, meta, new int[]{5}, new int[][]{{0, 1}});
  }

  @Test
  public void testDeferredBufsAreWrittenDuringOnData() throws IOException {
    StreamCallbackWithID stream1 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 0, 0, 0));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    StreamCallbackWithID stream2 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 1, 0, 0));
    // This should be deferred
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[3]));
    // stream 1 now completes
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    stream1.onComplete(stream1.getID());
    // stream 2 has more data and then completes
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[3]));
    stream2.onComplete(stream2.getID());
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 0, 0));
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0, 0);
    validateChunks(TEST_APP, 0, 0, 0, blockMeta, new int[]{4, 6}, new int[][]{{0}, {1}});
  }

  @Test
  public void testDeferredBufsAreWrittenDuringOnComplete() throws IOException {
    StreamCallbackWithID stream1 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 0, 0, 0));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    StreamCallbackWithID stream2 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 1, 0, 0));
    // This should be deferred
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[3]));
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[3]));
    // stream 1 now completes
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    stream1.onComplete(stream1.getID());
    // stream 2 now completes completes
    stream2.onComplete(stream2.getID());
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 0, 0));
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0, 0);
    validateChunks(TEST_APP, 0, 0, 0, blockMeta, new int[]{4, 6}, new int[][]{{0}, {1}});
  }

  @Test
  public void testDuplicateBlocksAreIgnoredWhenPrevStreamHasCompleted() throws IOException {
    StreamCallbackWithID stream1 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 0, 0, 0));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    stream1.onComplete(stream1.getID());
    StreamCallbackWithID stream2 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 0, 0, 0));
    // This should be ignored
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[2]));
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[2]));
    stream2.onComplete(stream2.getID());
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 0, 0));
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0, 0);
    validateChunks(TEST_APP, 0, 0, 0, blockMeta, new int[]{4}, new int[][]{{0}});
  }

  @Test
  public void testDuplicateBlocksAreIgnoredWhenPrevStreamIsInProgress() throws IOException {
    StreamCallbackWithID stream1 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 0, 0, 0));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    StreamCallbackWithID stream2 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 0, 0, 0));
    // This should be ignored
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[2]));
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[2]));
    // stream 1 now completes
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    stream1.onComplete(stream1.getID());
    // stream 2 now completes completes
    stream2.onComplete(stream2.getID());
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 0, 0));
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0, 0);
    validateChunks(TEST_APP, 0, 0, 0, blockMeta, new int[]{4}, new int[][]{{0}});
  }

  @Test
  public void testFailureAfterData() throws IOException {
    StreamCallbackWithID stream =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 0, 0, 0));
    stream.onData(stream.getID(), ByteBuffer.wrap(new byte[4]));
    stream.onFailure(stream.getID(), new RuntimeException("Forced Failure"));
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 0, 0));
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0, 0);
    assertEquals("num-chunks", 0, blockMeta.getNumChunks());
  }

  @Test
  public void testFailureAfterMultipleDataBlocks() throws IOException {
    StreamCallbackWithID stream =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 0, 0, 0));
    stream.onData(stream.getID(), ByteBuffer.wrap(new byte[2]));
    stream.onData(stream.getID(), ByteBuffer.wrap(new byte[3]));
    stream.onData(stream.getID(), ByteBuffer.wrap(new byte[4]));
    stream.onFailure(stream.getID(), new RuntimeException("Forced Failure"));
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 0, 0));
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0, 0);
    assertEquals("num-chunks", 0, blockMeta.getNumChunks());
  }

  @Test
  public void testFailureAfterComplete() throws IOException {
    StreamCallbackWithID stream =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 0, 0, 0));
    stream.onData(stream.getID(), ByteBuffer.wrap(new byte[2]));
    stream.onData(stream.getID(), ByteBuffer.wrap(new byte[3]));
    stream.onData(stream.getID(), ByteBuffer.wrap(new byte[4]));
    stream.onComplete(stream.getID());
    stream.onFailure(stream.getID(), new RuntimeException("Forced Failure"));
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 0, 0));
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0, 0);
    validateChunks(TEST_APP, 0, 0, 0, blockMeta, new int[]{9}, new int[][]{{0}});
  }

  @Test
  public void testBlockReceivedAfterMergeFinalize() throws IOException {
    ByteBuffer[] blocks = new ByteBuffer[]{
      ByteBuffer.wrap(new byte[4]),
      ByteBuffer.wrap(new byte[5])
    };
    StreamCallbackWithID stream = pushResolver.receiveBlockDataAsStream(
      new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 0, 0, 0));
    for (ByteBuffer block : blocks) {
      stream.onData(stream.getID(), block);
    }
    stream.onComplete(stream.getID());
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 0, 0));
    StreamCallbackWithID stream1 = pushResolver.receiveBlockDataAsStream(
      new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 1, 0, 0));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[4]));
    BlockPushNonFatalFailure e = assertThrows(BlockPushNonFatalFailure.class,
      () -> stream1.onComplete(stream1.getID()));
    BlockPushReturnCode errorCode =
      (BlockPushReturnCode) BlockTransferMessage.Decoder.fromByteBuffer(e.getResponse());
    assertEquals(BlockPushNonFatalFailure.ReturnCode.TOO_LATE_BLOCK_PUSH.id(),
      errorCode.returnCode);
    assertEquals(errorCode.failureBlockId, stream1.getID());
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0, 0);
    validateChunks(TEST_APP, 0, 0, 0, blockMeta, new int[]{9}, new int[][]{{0}});
  }

  @Test
  public void testIncompleteStreamsAreOverwritten() throws IOException {
    registerExecutor(TEST_APP, prepareLocalDirs(localDirs, MERGE_DIRECTORY), MERGE_DIRECTORY_META);
    byte[] expectedBytes = new byte[4];
    ThreadLocalRandom.current().nextBytes(expectedBytes);

    StreamCallbackWithID stream1 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 0, 0, 0));
    byte[] data = new byte[10];
    ThreadLocalRandom.current().nextBytes(data);
    stream1.onData(stream1.getID(), ByteBuffer.wrap(data));
    // There is a failure
    stream1.onFailure(stream1.getID(), new RuntimeException("forced error"));
    StreamCallbackWithID stream2 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 1, 0, 0));
    ByteBuffer nextBuf= ByteBuffer.wrap(expectedBytes, 0, 2);
    stream2.onData(stream2.getID(), nextBuf);
    stream2.onComplete(stream2.getID());
    StreamCallbackWithID stream3 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 2, 0, 0));
    nextBuf =  ByteBuffer.wrap(expectedBytes, 2, 2);
    stream3.onData(stream3.getID(), nextBuf);
    stream3.onComplete(stream3.getID());
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 0, 0));
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0, 0);
    validateChunks(TEST_APP, 0, 0, 0, blockMeta, new int[]{4}, new int[][]{{1, 2}});
    FileSegmentManagedBuffer mb =
      (FileSegmentManagedBuffer) pushResolver.getMergedBlockData(TEST_APP, 0, 0, 0, 0);
    assertArrayEquals(expectedBytes, mb.nioByteBuffer().array());
  }

  @Test
  public void testCollision() throws IOException {
    StreamCallbackWithID stream1 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 0, 0, 0));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    StreamCallbackWithID stream2 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 1, 0, 0));
    // This should be deferred
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[5]));
    // Since stream2 didn't get any opportunity it will throw couldn't find opportunity error
    BlockPushNonFatalFailure e = assertThrows(BlockPushNonFatalFailure.class,
      () -> stream2.onComplete(stream2.getID()));
    BlockPushReturnCode errorCode =
            (BlockPushReturnCode) BlockTransferMessage.Decoder.fromByteBuffer(e.getResponse());
    assertEquals(BlockPushNonFatalFailure.ReturnCode.BLOCK_APPEND_COLLISION_DETECTED.id(),
            errorCode.returnCode);
    assertEquals(errorCode.failureBlockId, stream2.getID());
  }

  @Test
  public void testFailureInAStreamDoesNotInterfereWithStreamWhichIsWriting() throws IOException {
    StreamCallbackWithID stream1 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 0, 0, 0));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    StreamCallbackWithID stream2 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 1, 0, 0));
    // There is a failure with stream2
    stream2.onFailure(stream2.getID(), new RuntimeException("forced error"));
    StreamCallbackWithID stream3 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 2, 0, 0));
    // This should be deferred
    stream3.onData(stream3.getID(), ByteBuffer.wrap(new byte[5]));
    // Since this stream didn't get any opportunity it will throw couldn't find opportunity error
    BlockPushNonFatalFailure e = assertThrows(BlockPushNonFatalFailure.class,
      () -> stream3.onComplete(stream3.getID()));
    BlockPushReturnCode errorCode =
      (BlockPushReturnCode) BlockTransferMessage.Decoder.fromByteBuffer(e.getResponse());
    assertEquals(BlockPushNonFatalFailure.ReturnCode.BLOCK_APPEND_COLLISION_DETECTED.id(),
      errorCode.returnCode);
    assertEquals(errorCode.failureBlockId, stream3.getID());
    // stream 1 now completes
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    stream1.onComplete(stream1.getID());

    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 0, 0));
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0, 0);
    validateChunks(TEST_APP, 0, 0, 0, blockMeta, new int[] {4}, new int[][] {{0}});
  }

  @Test
  public void testUpdateLocalDirsOnlyOnce() throws IOException {
    String testApp = "updateLocalDirsOnlyOnceTest";
    Path[] activeLocalDirs = createLocalDirs(1);
    registerExecutor(testApp, prepareLocalDirs(activeLocalDirs, MERGE_DIRECTORY),
      MERGE_DIRECTORY_META);
    assertEquals(pushResolver.getMergedBlockDirs(testApp).length, 1);
    assertTrue(pushResolver.getMergedBlockDirs(testApp)[0].contains(
      activeLocalDirs[0].toFile().getPath()));
    // Any later executor register from the same application attempt should not change the active
    // local dirs list
    Path[] updatedLocalDirs = localDirs;
    registerExecutor(testApp, prepareLocalDirs(updatedLocalDirs, MERGE_DIRECTORY),
      MERGE_DIRECTORY_META);
    assertEquals(pushResolver.getMergedBlockDirs(testApp).length, 1);
    assertTrue(pushResolver.getMergedBlockDirs(testApp)[0].contains(
      activeLocalDirs[0].toFile().getPath()));
    removeApplication(testApp);
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
      () -> pushResolver.getMergedBlockDirs(testApp));
    assertEquals(e.getMessage(),
      "application " + testApp + " is not registered or NM was restarted.");
  }

  @Test
  public void testExecutorRegisterWithInvalidJsonForPushShuffle() throws IOException {
    String testApp = "executorRegisterWithInvalidShuffleManagerMeta";
    Path[] activeLocalDirs = createLocalDirs(1);
    IllegalArgumentException re = assertThrows(IllegalArgumentException.class,
      () -> registerExecutor(testApp, prepareLocalDirs(activeLocalDirs, MERGE_DIRECTORY),
        INVALID_MERGE_DIRECTORY_META));
    assertEquals("Failed to get the merge directory information from the shuffleManagerMeta " +
      "shuffleManager:{\"mergeDirInvalid\": \"merge_manager_2\", \"attemptId\": \"2\"} in " +
      "executor registration message", re.getMessage());
  }

  @Test
  public void testExecutorRegistrationFromTwoAppAttempts() throws IOException {
    String testApp = "testExecutorRegistrationFromTwoAppAttempts";
    Path[] attempt1LocalDirs = createLocalDirs(1);
    registerExecutor(testApp,
      prepareLocalDirs(attempt1LocalDirs, MERGE_DIRECTORY + "_" + ATTEMPT_ID_1),
      MERGE_DIRECTORY_META_1);
    assertEquals(pushResolver.getMergedBlockDirs(testApp).length, 1);
    assertTrue(pushResolver.getMergedBlockDirs(testApp)[0].contains(
      attempt1LocalDirs[0].toFile().getPath()));
    // Any later executor register from the same application attempt should not change the active
    // local dirs list
    Path[] attempt1UpdatedLocalDirs = localDirs;
    registerExecutor(testApp,
      prepareLocalDirs(attempt1UpdatedLocalDirs, MERGE_DIRECTORY + "_" + ATTEMPT_ID_1),
      MERGE_DIRECTORY_META_1);
    assertEquals(pushResolver.getMergedBlockDirs(testApp).length, 1);
    assertTrue(pushResolver.getMergedBlockDirs(testApp)[0].contains(
      attempt1LocalDirs[0].toFile().getPath()));
    // But a new attempt from the same application can change the active local dirs list
    Path[] attempt2LocalDirs = createLocalDirs(2);
    registerExecutor(testApp,
      prepareLocalDirs(attempt2LocalDirs, MERGE_DIRECTORY + "_" + ATTEMPT_ID_2),
      MERGE_DIRECTORY_META_2);
    assertEquals(pushResolver.getMergedBlockDirs(testApp).length, 2);
    assertTrue(pushResolver.getMergedBlockDirs(testApp)[0].contains(
      attempt2LocalDirs[0].toFile().getPath()));
    removeApplication(testApp);
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
      () -> pushResolver.getMergedBlockDirs(testApp));
    assertEquals(e.getMessage(),
      "application " + testApp + " is not registered or NM was restarted.");
  }

  @Test
  public void testCleanUpDirectory() throws IOException, InterruptedException {
    String testApp = "cleanUpDirectory";
    Semaphore deleted = new Semaphore(0);
    pushResolver = new RemoteBlockPushResolver(conf, null) {
      @Override
      void deleteExecutorDirs(AppShuffleInfo appShuffleInfo) {
        super.deleteExecutorDirs(appShuffleInfo);
        deleted.release();
      }
    };

    Path[] activeDirs = createLocalDirs(1);
    registerExecutor(testApp, prepareLocalDirs(activeDirs, MERGE_DIRECTORY), MERGE_DIRECTORY_META);
    PushBlock[] pushBlocks = new PushBlock[] {
      new PushBlock(0, 0, 0, 0, ByteBuffer.wrap(new byte[4]))};
    pushBlockHelper(testApp, NO_ATTEMPT_ID, pushBlocks);
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(testApp, NO_ATTEMPT_ID, 0, 0));
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(testApp, 0, 0, 0);
    validateChunks(testApp, 0, 0, 0, blockMeta, new int[]{4}, new int[][]{{0}});
    String[] mergeDirs = pushResolver.getMergedBlockDirs(testApp);
    pushResolver.applicationRemoved(testApp,  true);
    // Since the cleanup happen in a different thread, check few times to see if the merge dirs gets
    // deleted.
    deleted.acquire();
    for (String mergeDir : mergeDirs) {
      Assert.assertFalse(Files.exists(Paths.get(mergeDir)));
    }
  }

  @Test
  public void testRecoverIndexFileAfterIOExceptions() throws IOException {
    useTestFiles(true, false);
    RemoteBlockPushResolver.PushBlockStreamCallback callback1 =
      (RemoteBlockPushResolver.PushBlockStreamCallback) pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 0, 0, 0));
    callback1.onData(callback1.getID(), ByteBuffer.wrap(new byte[4]));
    callback1.onComplete(callback1.getID());
    RemoteBlockPushResolver.AppShufflePartitionInfo partitionInfo = callback1.getPartitionInfo();
    // Close the index stream so it throws IOException
    TestMergeShuffleFile testIndexFile = (TestMergeShuffleFile) partitionInfo.getIndexFile();
    testIndexFile.close();
    StreamCallbackWithID callback2 = pushResolver.receiveBlockDataAsStream(
      new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 1, 0, 0));
    callback2.onData(callback2.getID(), ByteBuffer.wrap(new byte[5]));
    // This will complete without any IOExceptions because number of IOExceptions are less than
    // the threshold but the update to index file will be unsuccessful.
    callback2.onComplete(callback2.getID());
    assertEquals("index position", 16, testIndexFile.getPos());
    // Restore the index stream so it can write successfully again.
    testIndexFile.restore();
    StreamCallbackWithID callback3 = pushResolver.receiveBlockDataAsStream(
      new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 2, 0, 0));
    callback3.onData(callback3.getID(), ByteBuffer.wrap(new byte[2]));
    callback3.onComplete(callback3.getID());
    assertEquals("index position", 24, testIndexFile.getPos());
    MergeStatuses statuses = pushResolver.finalizeShuffleMerge(
      new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 0, 0));
    validateMergeStatuses(statuses, new int[] {0}, new long[] {11});
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0, 0);
    validateChunks(TEST_APP, 0, 0, 0, blockMeta, new int[] {4, 7}, new int[][] {{0}, {1, 2}});
  }

  @Test
  public void testRecoverIndexFileAfterIOExceptionsInFinalize() throws IOException {
    useTestFiles(true, false);
    RemoteBlockPushResolver.PushBlockStreamCallback callback1 =
      (RemoteBlockPushResolver.PushBlockStreamCallback) pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 0, 0, 0));
    callback1.onData(callback1.getID(), ByteBuffer.wrap(new byte[4]));
    callback1.onComplete(callback1.getID());
    RemoteBlockPushResolver.AppShufflePartitionInfo partitionInfo = callback1.getPartitionInfo();
    // Close the index stream so it throws IOException
    TestMergeShuffleFile testIndexFile = (TestMergeShuffleFile) partitionInfo.getIndexFile();
    testIndexFile.close();
    StreamCallbackWithID callback2 = pushResolver.receiveBlockDataAsStream(
      new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 1, 0, 0));
    callback2.onData(callback2.getID(), ByteBuffer.wrap(new byte[5]));
    // This will complete without any IOExceptions because number of IOExceptions are less than
    // the threshold but the update to index file will be unsuccessful.
    callback2.onComplete(callback2.getID());
    assertEquals("index position", 16, testIndexFile.getPos());
    // The last update to index was unsuccessful however any further updates will be successful.
    // Restore the index stream so it can write successfully again.
    testIndexFile.restore();
    MergeStatuses statuses = pushResolver.finalizeShuffleMerge(
      new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 0, 0));
    assertEquals("index position", 24, testIndexFile.getPos());
    validateMergeStatuses(statuses, new int[] {0}, new long[] {9});
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0, 0);
    validateChunks(TEST_APP, 0, 0, 0, blockMeta, new int[] {4, 5}, new int[][] {{0}, {1}});
  }

  @Test
  public void testRecoverMetaFileAfterIOExceptions() throws IOException {
    useTestFiles(false, true);
    RemoteBlockPushResolver.PushBlockStreamCallback callback1 =
      (RemoteBlockPushResolver.PushBlockStreamCallback) pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 0, 0, 0));
    callback1.onData(callback1.getID(), ByteBuffer.wrap(new byte[4]));
    callback1.onComplete(callback1.getID());
    RemoteBlockPushResolver.AppShufflePartitionInfo partitionInfo = callback1.getPartitionInfo();
    // Close the meta stream so it throws IOException
    TestMergeShuffleFile testMetaFile = (TestMergeShuffleFile) partitionInfo.getMetaFile();
    long metaPosBeforeClose = testMetaFile.getPos();
    testMetaFile.close();
    StreamCallbackWithID callback2 = pushResolver.receiveBlockDataAsStream(
      new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 1, 0, 0));
    callback2.onData(callback2.getID(), ByteBuffer.wrap(new byte[5]));
    // This will complete without any IOExceptions because number of IOExceptions are less than
    // the threshold but the update to index and meta file will be unsuccessful.
    callback2.onComplete(callback2.getID());
    assertEquals("index position", 16, partitionInfo.getIndexFile().getPos());
    assertEquals("meta position", metaPosBeforeClose, testMetaFile.getPos());
    // Restore the meta stream so it can write successfully again.
    testMetaFile.restore();
    StreamCallbackWithID callback3 = pushResolver.receiveBlockDataAsStream(
      new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 2, 0, 0));
    callback3.onData(callback3.getID(), ByteBuffer.wrap(new byte[2]));
    callback3.onComplete(callback3.getID());
    assertEquals("index position", 24, partitionInfo.getIndexFile().getPos());
    assertTrue("meta position", testMetaFile.getPos() > metaPosBeforeClose);
    MergeStatuses statuses = pushResolver.finalizeShuffleMerge(
      new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 0, 0));
    validateMergeStatuses(statuses, new int[] {0}, new long[] {11});
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0, 0);
    validateChunks(TEST_APP, 0, 0, 0, blockMeta, new int[] {4, 7}, new int[][] {{0}, {1, 2}});
  }

  @Test
  public void testRecoverMetaFileAfterIOExceptionsInFinalize() throws IOException {
    useTestFiles(false, true);
    RemoteBlockPushResolver.PushBlockStreamCallback callback1 =
      (RemoteBlockPushResolver.PushBlockStreamCallback) pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 0, 0, 0));
    callback1.onData(callback1.getID(), ByteBuffer.wrap(new byte[4]));
    callback1.onComplete(callback1.getID());
    RemoteBlockPushResolver.AppShufflePartitionInfo partitionInfo = callback1.getPartitionInfo();
    // Close the meta stream so it throws IOException
    TestMergeShuffleFile testMetaFile = (TestMergeShuffleFile) partitionInfo.getMetaFile();
    long metaPosBeforeClose = testMetaFile.getPos();
    testMetaFile.close();
    StreamCallbackWithID callback2 = pushResolver.receiveBlockDataAsStream(
      new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 1, 0, 0));
    callback2.onData(callback2.getID(), ByteBuffer.wrap(new byte[5]));
    // This will complete without any IOExceptions because number of IOExceptions are less than
    // the threshold but the update to index and meta file will be unsuccessful.
    callback2.onComplete(callback2.getID());
    MergeShuffleFile indexFile = partitionInfo.getIndexFile();
    assertEquals("index position", 16, indexFile.getPos());
    assertEquals("meta position", metaPosBeforeClose, testMetaFile.getPos());
    // Restore the meta stream so it can write successfully again.
    testMetaFile.restore();
    MergeStatuses statuses = pushResolver.finalizeShuffleMerge(
      new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 0, 0));
    assertEquals("index position", 24, indexFile.getPos());
    assertTrue("meta position", testMetaFile.getPos() > metaPosBeforeClose);
    validateMergeStatuses(statuses, new int[] {0}, new long[] {9});
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0, 0);
    validateChunks(TEST_APP, 0, 0, 0, blockMeta, new int[] {4, 5}, new int[][] {{0}, {1}});
  }

  @Test
  public void testIOExceptionsExceededThreshold() throws IOException {
    RemoteBlockPushResolver.PushBlockStreamCallback callback =
      (RemoteBlockPushResolver.PushBlockStreamCallback) pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 0, 0, 0));
    RemoteBlockPushResolver.AppShufflePartitionInfo partitionInfo = callback.getPartitionInfo();
    callback.onData(callback.getID(), ByteBuffer.wrap(new byte[4]));
    callback.onComplete(callback.getID());
    // Close the data stream so it throws continuous IOException
    partitionInfo.getDataChannel().close();
    for (int i = 1; i < 5; i++) {
      RemoteBlockPushResolver.PushBlockStreamCallback callback1 =
        (RemoteBlockPushResolver.PushBlockStreamCallback) pushResolver.receiveBlockDataAsStream(
          new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, i, 0, 0));
      IOException ioe = assertThrows(IOException.class,
        () -> callback1.onData(callback1.getID(), ByteBuffer.wrap(new byte[2])));
      // this will throw IOException so the client can retry.
      callback1.onFailure(callback1.getID(), ioe);
    }
    assertEquals(4, partitionInfo.getNumIOExceptions());
    // After 4 IOException, the server will respond with IOExceptions exceeded threshold
    RemoteBlockPushResolver.PushBlockStreamCallback callback2 =
      (RemoteBlockPushResolver.PushBlockStreamCallback) pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 5, 0, 0));
    IllegalStateException e = assertThrows(IllegalStateException.class,
      () -> callback2.onData(callback.getID(), ByteBuffer.wrap(new byte[1])));
    assertEquals("IOExceptions exceeded the threshold when merging shufflePush_0_0_5_0",
      e.getMessage());
  }

  @Test
  public void testIOExceptionsDuringMetaUpdateIncreasesExceptionCount() throws IOException {
    useTestFiles(true, false);
    RemoteBlockPushResolver.PushBlockStreamCallback callback =
      (RemoteBlockPushResolver.PushBlockStreamCallback) pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 0, 0, 0));
    RemoteBlockPushResolver.AppShufflePartitionInfo partitionInfo = callback.getPartitionInfo();
    callback.onData(callback.getID(), ByteBuffer.wrap(new byte[4]));
    callback.onComplete(callback.getID());
    TestMergeShuffleFile testIndexFile = (TestMergeShuffleFile) partitionInfo.getIndexFile();
    testIndexFile.close();
    for (int i = 1; i < 5; i++) {
      RemoteBlockPushResolver.PushBlockStreamCallback callback1 =
        (RemoteBlockPushResolver.PushBlockStreamCallback) pushResolver.receiveBlockDataAsStream(
          new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, i, 0, 0));
      callback1.onData(callback1.getID(), ByteBuffer.wrap(new byte[5]));
      // This will complete without any exceptions but the exception count is increased.
      callback1.onComplete(callback1.getID());
    }
    assertEquals(4, partitionInfo.getNumIOExceptions());
    // After 4 IOException, the server will respond with IOExceptions exceeded threshold for any
    // new request for this partition.
    RemoteBlockPushResolver.PushBlockStreamCallback callback2 =
      (RemoteBlockPushResolver.PushBlockStreamCallback) pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 5, 0, 0));
    callback2.onData(callback2.getID(), ByteBuffer.wrap(new byte[4]));
    IllegalStateException e = assertThrows(IllegalStateException.class,
      () -> callback2.onComplete(callback2.getID()));
    assertEquals("IOExceptions exceeded the threshold when merging shufflePush_0_0_5_0",
      e.getMessage());
  }

  @Test
  public void testRequestForAbortedShufflePartitionThrowsException() throws IOException {
    // No more blocks can be merged to this partition.
    testIOExceptionsDuringMetaUpdateIncreasesExceptionCount();

    IllegalStateException t = assertThrows(IllegalStateException.class,
      () -> pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 10, 0, 0)));
    assertEquals("IOExceptions exceeded the threshold when merging shufflePush_0_0_10_0",
      t.getMessage());
  }

  @Test
  public void testPendingBlockIsAbortedImmediately() throws IOException {
    useTestFiles(true, false);
    RemoteBlockPushResolver.PushBlockStreamCallback callback =
      (RemoteBlockPushResolver.PushBlockStreamCallback) pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 0, 0, 0));
    RemoteBlockPushResolver.AppShufflePartitionInfo partitionInfo = callback.getPartitionInfo();
    TestMergeShuffleFile testIndexFile = (TestMergeShuffleFile) partitionInfo.getIndexFile();
    testIndexFile.close();
    for (int i = 1; i < 6; i++) {
      RemoteBlockPushResolver.PushBlockStreamCallback callback1 =
        (RemoteBlockPushResolver.PushBlockStreamCallback) pushResolver.receiveBlockDataAsStream(
          new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, i, 0, 0));
      callback1.onData(callback1.getID(), ByteBuffer.wrap(new byte[5]));
      if (i < 5) {
        // This will complete without any exceptions but the exception count is increased.
        callback1.onComplete(callback1.getID());
      } else {
        Throwable t = assertThrows(Throwable.class, () -> callback1.onComplete(callback1.getID()));
        callback1.onFailure(callback1.getID(), t);
      }
    }
    assertEquals(5, partitionInfo.getNumIOExceptions());
    // The server will respond with IOExceptions exceeded threshold for any additional attempts
    // to write.
    IllegalStateException e = assertThrows(IllegalStateException.class,
      () -> callback.onData(callback.getID(), ByteBuffer.wrap(new byte[4])));
    assertEquals("IOExceptions exceeded the threshold when merging shufflePush_0_0_0_0",
      e.getMessage());
  }

  @Test
  public void testWritingPendingBufsIsAbortedImmediatelyDuringComplete() throws IOException {
    useTestFiles(true, false);
    RemoteBlockPushResolver.PushBlockStreamCallback callback =
      (RemoteBlockPushResolver.PushBlockStreamCallback) pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 0, 0, 0));
    RemoteBlockPushResolver.AppShufflePartitionInfo partitionInfo = callback.getPartitionInfo();
    TestMergeShuffleFile testIndexFile = (TestMergeShuffleFile) partitionInfo.getIndexFile();
    testIndexFile.close();
    for (int i = 1; i < 5; i++) {
      RemoteBlockPushResolver.PushBlockStreamCallback callback1 =
        (RemoteBlockPushResolver.PushBlockStreamCallback) pushResolver.receiveBlockDataAsStream(
          new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, i, 0, 0));
      callback1.onData(callback1.getID(), ByteBuffer.wrap(new byte[5]));
      // This will complete without any exceptions but the exception count is increased.
      callback1.onComplete(callback1.getID());
    }
    assertEquals(4, partitionInfo.getNumIOExceptions());
    RemoteBlockPushResolver.PushBlockStreamCallback callback2 =
      (RemoteBlockPushResolver.PushBlockStreamCallback) pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 5, 0, 0));
    callback2.onData(callback2.getID(), ByteBuffer.wrap(new byte[5]));
    // This is deferred
    callback.onData(callback.getID(), ByteBuffer.wrap(new byte[4]));
    // Callback2 completes which will throw another exception.
    Throwable t = assertThrows(Throwable.class, () -> callback2.onComplete(callback2.getID()));
    callback2.onFailure(callback2.getID(), t);
    assertEquals(5, partitionInfo.getNumIOExceptions());
    // Restore index file so that any further writes to it are successful and any exceptions are
    // due to IOExceptions exceeding threshold.
    testIndexFile.restore();
    IllegalStateException ie = assertThrows(IllegalStateException.class,
      () -> callback.onComplete(callback.getID()));
    assertEquals("IOExceptions exceeded the threshold when merging shufflePush_0_0_0_0",
      ie.getMessage());
  }

  @Test
  public void testFailureWhileTruncatingFiles() throws IOException {
    useTestFiles(true, false);
    PushBlock[] pushBlocks = new PushBlock[] {
      new PushBlock(0, 0, 0, 0, ByteBuffer.wrap(new byte[2])),
      new PushBlock(0, 0, 1, 0, ByteBuffer.wrap(new byte[3])),
      new PushBlock(0, 0, 0, 1, ByteBuffer.wrap(new byte[5])),
      new PushBlock(0, 0, 1, 1, ByteBuffer.wrap(new byte[3]))
    };
    pushBlockHelper(TEST_APP, NO_ATTEMPT_ID, pushBlocks);
    RemoteBlockPushResolver.PushBlockStreamCallback callback =
      (RemoteBlockPushResolver.PushBlockStreamCallback) pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 2, 0, 0));
    callback.onData(callback.getID(), ByteBuffer.wrap(new byte[2]));
    callback.onComplete(callback.getID());
    RemoteBlockPushResolver.AppShufflePartitionInfo partitionInfo = callback.getPartitionInfo();
    TestMergeShuffleFile testIndexFile = (TestMergeShuffleFile) partitionInfo.getIndexFile();
    // Close the index file so truncate throws IOException
    testIndexFile.close();
    MergeStatuses statuses = pushResolver.finalizeShuffleMerge(
      new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 0, 0));
    validateMergeStatuses(statuses, new int[] {1}, new long[] {8});
    MergedBlockMeta meta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0, 1);
    validateChunks(TEST_APP, 0, 0, 1, meta, new int[]{5, 3}, new int[][]{{0},{1}});
  }

  @Test
  public void testOnFailureInvokedMoreThanOncePerBlock() throws IOException {
    StreamCallbackWithID stream1 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 0, 0, 0));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    stream1.onFailure(stream1.getID(), new RuntimeException("forced error"));
    StreamCallbackWithID stream2 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 1, 0, 0));
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[5]));
    // On failure on stream1 gets invoked again and should cause no interference
    stream1.onFailure(stream1.getID(), new RuntimeException("2nd forced error"));
    StreamCallbackWithID stream3 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 3, 0, 0));
    // This should be deferred as stream 2 is still the active stream
    stream3.onData(stream3.getID(), ByteBuffer.wrap(new byte[2]));
    // Stream 2 writes more and completes
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[4]));
    stream2.onComplete(stream2.getID());
    stream3.onComplete(stream3.getID());
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 0, 0));
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0, 0);
    validateChunks(TEST_APP, 0, 0, 0, blockMeta, new int[] {9, 2}, new int[][] {{1},{3}});
    removeApplication(TEST_APP);
  }

  @Test
  public void testFailureAfterDuplicateBlockDoesNotInterfereActiveStream() throws IOException {
    StreamCallbackWithID stream1 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 0, 0, 0));
    StreamCallbackWithID stream1Duplicate =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 0, 0, 0));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    stream1.onComplete(stream1.getID());
    stream1Duplicate.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));

    StreamCallbackWithID stream2 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 1, 0, 0));
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[5]));
    // Should not change the current map id of the reduce partition
    stream1Duplicate.onFailure(stream2.getID(), new RuntimeException("forced error"));

    StreamCallbackWithID stream3 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 0, 2, 0, 0));
    // This should be deferred as stream 2 is still the active stream
    stream3.onData(stream3.getID(), ByteBuffer.wrap(new byte[2]));
    BlockPushNonFatalFailure e = assertThrows(BlockPushNonFatalFailure.class,
      () -> stream3.onComplete(stream3.getID()));
    BlockPushReturnCode errorCode =
      (BlockPushReturnCode) BlockTransferMessage.Decoder.fromByteBuffer(e.getResponse());
    assertEquals(BlockPushNonFatalFailure.ReturnCode.BLOCK_APPEND_COLLISION_DETECTED.id(),
      errorCode.returnCode);
    assertEquals(errorCode.failureBlockId, stream3.getID());
    // Stream 2 writes more and completes
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[4]));
    stream2.onComplete(stream2.getID());
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 0, 0));
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0, 0);
    validateChunks(TEST_APP, 0, 0, 0, blockMeta, new int[] {11}, new int[][] {{0, 1}});
    removeApplication(TEST_APP);
  }

  @Test
  public void testPushBlockFromPreviousAttemptIsRejected()
      throws IOException, InterruptedException {
    Semaphore closed = new Semaphore(0);
    pushResolver = new RemoteBlockPushResolver(conf, null) {
      @Override
      void closeAndDeletePartitionsIfNeeded(
          AppShuffleInfo appShuffleInfo,
          boolean cleanupLocalDirs) {
        super.closeAndDeletePartitionsIfNeeded(appShuffleInfo, cleanupLocalDirs);
        closed.release();
      }
    };
    String testApp = "testPushBlockFromPreviousAttemptIsRejected";
    Path[] attempt1LocalDirs = createLocalDirs(1);
    registerExecutor(testApp,
      prepareLocalDirs(attempt1LocalDirs, MERGE_DIRECTORY + "_" + ATTEMPT_ID_1),
      MERGE_DIRECTORY_META_1);
    ByteBuffer[] blocks = new ByteBuffer[]{
      ByteBuffer.wrap(new byte[4]),
      ByteBuffer.wrap(new byte[5])
    };
    StreamCallbackWithID stream1 = pushResolver.receiveBlockDataAsStream(
      new PushBlockStream(testApp, 1, 0, 0, 0, 0, 0));
    for (ByteBuffer block : blocks) {
      stream1.onData(stream1.getID(), block);
    }
    stream1.onComplete(stream1.getID());
    RemoteBlockPushResolver.AppShuffleInfo appShuffleInfo =
      pushResolver.validateAndGetAppShuffleInfo(testApp);
    RemoteBlockPushResolver.AppShuffleMergePartitionsInfo partitions
      = appShuffleInfo.getShuffles().get(0);
    for (RemoteBlockPushResolver.AppShufflePartitionInfo partitionInfo :
        partitions.getShuffleMergePartitions().values()) {
      assertTrue(partitionInfo.getDataChannel().isOpen());
      assertTrue(partitionInfo.getMetaFile().getChannel().isOpen());
      assertTrue(partitionInfo.getIndexFile().getChannel().isOpen());
    }
    Path[] attempt2LocalDirs = createLocalDirs(2);
    registerExecutor(testApp,
      prepareLocalDirs(attempt2LocalDirs, MERGE_DIRECTORY + "_" + ATTEMPT_ID_2),
      MERGE_DIRECTORY_META_2);
    StreamCallbackWithID stream2 = pushResolver.receiveBlockDataAsStream(
      new PushBlockStream(testApp, 2, 0, 0, 1, 0, 0));
    for (ByteBuffer block : blocks) {
      stream2.onData(stream2.getID(), block);
    }
    stream2.onComplete(stream2.getID());
    closed.acquire();
    // Check if all the file channels created for the first attempt are safely closed.
    for (RemoteBlockPushResolver.AppShufflePartitionInfo partitionInfo :
      partitions.getShuffleMergePartitions().values()) {
      assertFalse(partitionInfo.getDataChannel().isOpen());
      assertFalse(partitionInfo.getMetaFile().getChannel().isOpen());
      assertFalse(partitionInfo.getIndexFile().getChannel().isOpen());
    }
    BlockPushNonFatalFailure re = assertThrows(BlockPushNonFatalFailure.class,
      () -> pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(testApp, 1, 0, 0, 1, 0, 0)));
    BlockPushReturnCode errorCode =
      (BlockPushReturnCode) BlockTransferMessage.Decoder.fromByteBuffer(re.getResponse());
    assertEquals(BlockPushNonFatalFailure.ReturnCode.TOO_OLD_ATTEMPT_PUSH.id(),
      errorCode.returnCode);
    assertEquals(errorCode.failureBlockId, stream2.getID());
  }

  @Test
  public void testFinalizeShuffleMergeFromPreviousAttemptIsAborted()
    throws IOException {
    String testApp = "testFinalizeShuffleMergeFromPreviousAttemptIsAborted";
    Path[] attempt1LocalDirs = createLocalDirs(1);
    registerExecutor(testApp,
      prepareLocalDirs(attempt1LocalDirs, MERGE_DIRECTORY + "_" + ATTEMPT_ID_1),
      MERGE_DIRECTORY_META_1);
    ByteBuffer[] blocks = new ByteBuffer[]{
      ByteBuffer.wrap(new byte[4]),
      ByteBuffer.wrap(new byte[5])
    };
    StreamCallbackWithID stream1 = pushResolver.receiveBlockDataAsStream(
      new PushBlockStream(testApp, 1, 0, 0, 0, 0, 0));
    for (ByteBuffer block : blocks) {
      stream1.onData(stream1.getID(), block);
    }
    stream1.onComplete(stream1.getID());
    Path[] attempt2LocalDirs = createLocalDirs(2);
    registerExecutor(testApp,
      prepareLocalDirs(attempt2LocalDirs, MERGE_DIRECTORY + "_" + ATTEMPT_ID_2),
      MERGE_DIRECTORY_META_2);
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
      () -> pushResolver.finalizeShuffleMerge(
              new FinalizeShuffleMerge(testApp, ATTEMPT_ID_1, 0, 0)));
    assertEquals(e.getMessage(),
      String.format("The attempt id %s in this FinalizeShuffleMerge message does not " +
        "match with the current attempt id %s stored in shuffle service for application %s",
        ATTEMPT_ID_1, ATTEMPT_ID_2, testApp));
  }

  @Test
  public void testOngoingMergeOfBlockFromPreviousAttemptIsAborted()
      throws IOException, InterruptedException {
    Semaphore closed = new Semaphore(0);
    pushResolver = new RemoteBlockPushResolver(conf, null) {
      @Override
      void closeAndDeletePartitionsIfNeeded(
          AppShuffleInfo appShuffleInfo,
          boolean cleanupLocalDirs) {
        super.closeAndDeletePartitionsIfNeeded(appShuffleInfo, cleanupLocalDirs);
        closed.release();
      }
    };
    String testApp = "testOngoingMergeOfBlockFromPreviousAttemptIsAborted";
    Path[] attempt1LocalDirs = createLocalDirs(1);
    registerExecutor(testApp,
      prepareLocalDirs(attempt1LocalDirs, MERGE_DIRECTORY + "_" + ATTEMPT_ID_1),
      MERGE_DIRECTORY_META_1);
    ByteBuffer[] blocks = new ByteBuffer[]{
      ByteBuffer.wrap(new byte[4]),
      ByteBuffer.wrap(new byte[5]),
      ByteBuffer.wrap(new byte[6]),
      ByteBuffer.wrap(new byte[7])
    };
    StreamCallbackWithID stream1 = pushResolver.receiveBlockDataAsStream(
      new PushBlockStream(testApp, 1, 0, 0, 0, 0, 0));
    // The onData callback should be called 4 times here before the onComplete callback. But a
    // register executor message arrives in shuffle service after the 2nd onData callback. The 3rd
    // onData callback should all throw ClosedChannelException as their channels are closed.
    stream1.onData(stream1.getID(), blocks[0]);
    stream1.onData(stream1.getID(), blocks[1]);
    Path[] attempt2LocalDirs = createLocalDirs(2);
    registerExecutor(testApp,
      prepareLocalDirs(attempt2LocalDirs, MERGE_DIRECTORY + "_" + ATTEMPT_ID_2),
      MERGE_DIRECTORY_META_2);
    closed.acquire();
    // Should throw ClosedChannelException here.
    assertThrows(ClosedChannelException.class,
      () -> stream1.onData(stream1.getID(), blocks[3]));
  }

  @Test
  public void testBlockPushWithOlderShuffleMergeId() throws IOException {
    StreamCallbackWithID stream1 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 1, 0, 0, 0));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    StreamCallbackWithID stream2 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 2, 0, 0, 0));
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[2]));
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[2]));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    BlockPushNonFatalFailure e = assertThrows(BlockPushNonFatalFailure.class,
      () -> stream1.onComplete(stream1.getID()));
    BlockPushReturnCode errorCode =
      (BlockPushReturnCode) BlockTransferMessage.Decoder.fromByteBuffer(e.getResponse());
    assertEquals(BlockPushNonFatalFailure.ReturnCode.STALE_BLOCK_PUSH.id(),
      errorCode.returnCode);
    assertEquals(errorCode.failureBlockId, stream1.getID());
    // stream 2 now completes
    stream2.onComplete(stream2.getID());
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 0, 2));
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 2, 0);
    validateChunks(TEST_APP, 0, 2, 0, blockMeta, new int[]{4}, new int[][]{{0}});
  }

  @Test
  public void testFinalizeWithOlderShuffleMergeId() throws IOException {
    StreamCallbackWithID stream1 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 1, 0, 0, 0));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    StreamCallbackWithID stream2 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 2, 0, 0, 0));
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[2]));
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[2]));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    // stream 1 push should be rejected as it is from an older shuffleMergeId
    BlockPushNonFatalFailure e = assertThrows(BlockPushNonFatalFailure.class,
      () -> stream1.onComplete(stream1.getID()));
    BlockPushReturnCode errorCode =
      (BlockPushReturnCode) BlockTransferMessage.Decoder.fromByteBuffer(e.getResponse());
    assertEquals(BlockPushNonFatalFailure.ReturnCode.STALE_BLOCK_PUSH.id(),
      errorCode.returnCode);
    assertEquals(errorCode.failureBlockId, stream1.getID());
    // stream 2 now completes
    stream2.onComplete(stream2.getID());
    RuntimeException re = assertThrows(RuntimeException.class,
      () -> pushResolver.finalizeShuffleMerge(
              new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 0, 1)));
    assertEquals("Shuffle merge finalize request for shuffle 0 with shuffleMergeId 1 is stale"
      + " shuffle finalize request as shuffle blocks of a higher shuffleMergeId for the shuffle"
      + " is already being pushed", re.getMessage());
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 0, 2));

    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 2, 0);
    validateChunks(TEST_APP, 0, 2, 0, blockMeta, new int[]{4}, new int[][]{{0}});
  }

  @Test
  public void testFinalizeOfDeterminateShuffle() throws IOException {
    PushBlock[] pushBlocks = new PushBlock[] {
      new PushBlock(0, 0, 0, 0, ByteBuffer.wrap(new byte[4])),
      new PushBlock(0,  0, 1, 0, ByteBuffer.wrap(new byte[5]))
    };
    pushBlockHelper(TEST_APP, NO_ATTEMPT_ID, pushBlocks);
    MergeStatuses statuses = pushResolver.finalizeShuffleMerge(
      new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 0, 0));

    RemoteBlockPushResolver.AppShuffleInfo appShuffleInfo =
      pushResolver.validateAndGetAppShuffleInfo(TEST_APP);
    assertTrue("Determinate shuffle should be marked finalized",
        appShuffleInfo.getShuffles().get(0).isFinalized());
    validateMergeStatuses(statuses, new int[] {0}, new long[] {9});
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 0, 0);
    validateChunks(TEST_APP, 0, 0, 0, blockMeta, new int[]{4, 5}, new int[][]{{0}, {1}});
  }

  @Test
  public void testBlockFetchWithOlderShuffleMergeId() throws IOException {
    StreamCallbackWithID stream1 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 1, 0, 0, 0));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    StreamCallbackWithID stream2 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 0, 2, 0, 0, 0));
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[2]));
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[2]));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    // stream 1 push should be rejected as it is from an older shuffleMergeId
    BlockPushNonFatalFailure e = assertThrows(BlockPushNonFatalFailure.class,
      () -> stream1.onComplete(stream1.getID()));
    BlockPushReturnCode errorCode =
      (BlockPushReturnCode) BlockTransferMessage.Decoder.fromByteBuffer(e.getResponse());
    assertEquals(BlockPushNonFatalFailure.ReturnCode.STALE_BLOCK_PUSH.id(),
      errorCode.returnCode);
    assertEquals(errorCode.failureBlockId, stream1.getID());
    // stream 2 now completes
    stream2.onComplete(stream2.getID());
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 0, 2));
    RuntimeException re0 = assertThrows(RuntimeException.class,
      () -> pushResolver.getMergedBlockMeta(TEST_APP, 0, 0, 0));
    assertEquals("MergedBlockMeta fetch for shuffle 0 with shuffleMergeId 0 reduceId 0"
      + " is stale shuffle block fetch request as shuffle blocks of a higher shuffleMergeId for"
      + " the shuffle is available", re0.getMessage());
    RuntimeException re1 = assertThrows(RuntimeException.class,
      () -> pushResolver.finalizeShuffleMerge(
              new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 0, 1)));
    assertEquals("Shuffle merge finalize request for shuffle 0 with shuffleMergeId 1 is stale"
      + " shuffle finalize request as shuffle blocks of a higher shuffleMergeId for the shuffle"
      + " is already being pushed", re1.getMessage());
    RuntimeException re2 = assertThrows(RuntimeException.class,
      () -> pushResolver.getMergedBlockData(TEST_APP, 0, 1, 0, 0));
    assertEquals("MergedBlockData fetch for shuffle 0 with shuffleMergeId 1 reduceId 0"
      + " is stale shuffle block fetch request as shuffle blocks of a higher shuffleMergeId for"
      + " the shuffle is available", re2.getMessage());
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 0, 2, 0);
    validateChunks(TEST_APP, 0, 2, 0, blockMeta, new int[]{4}, new int[][]{{0}});
  }

  @Test
  public void testCleanupOlderShuffleMergeId() throws IOException, InterruptedException {
    Semaphore closed = new Semaphore(0);
    pushResolver = new RemoteBlockPushResolver(conf, null) {
      @Override
      void deleteCurrentOutDatedShufflePartitions(AppShuffleInfo shuffleInfo, Integer shuffleId,
          AppAttemptShuffleMergeId higherShuffleMergeIdToClean) {
        super.deleteCurrentOutDatedShufflePartitions(shuffleInfo, shuffleId, higherShuffleMergeIdToClean);
        closed.release();
      }
    };
    String testApp = "testCleanupOlderShuffleMergeId";
    registerExecutor(testApp, prepareLocalDirs(localDirs, MERGE_DIRECTORY), MERGE_DIRECTORY_META);
    StreamCallbackWithID stream1 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(testApp, NO_ATTEMPT_ID, 0, 1, 0, 0, 0));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[2]));
    StreamCallbackWithID stream2 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(testApp, NO_ATTEMPT_ID, 0, 2, 0, 0, 0));
    RemoteBlockPushResolver.AppShuffleInfo appShuffleInfo =
      pushResolver.validateAndGetAppShuffleInfo(testApp);
    closed.acquire();
    assertFalse("Data files on the disk should be cleaned up",
      appShuffleInfo.getMergedShuffleDataFile(0, 1, 0).exists());
    assertFalse("Meta files on the disk should be cleaned up",
      appShuffleInfo.getMergedShuffleMetaFile(0, 1, 0).exists());
    assertFalse("Index files on the disk should be cleaned up",
      new File(appShuffleInfo.getMergedShuffleIndexFilePath(0, 1, 0)).exists());
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[2]));
    stream2.onData(stream2.getID(), ByteBuffer.wrap(new byte[2]));
    // stream 2 now completes
    stream2.onComplete(stream2.getID());
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(testApp, NO_ATTEMPT_ID, 0, 2));
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(testApp, 0, 2, 0);
    validateChunks(testApp, 0, 2, 0, blockMeta, new int[]{4}, new int[][]{{0}});

    // Check whether the metadata is cleaned up or not
    StreamCallbackWithID stream3 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(testApp, NO_ATTEMPT_ID, 0, 3, 0, 0, 0));
    closed.acquire();
    stream3.onData(stream3.getID(), ByteBuffer.wrap(new byte[2]));
    stream3.onComplete(stream3.getID());
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(testApp, NO_ATTEMPT_ID, 0, 3));
    MergedBlockMeta mergedBlockMeta = pushResolver.getMergedBlockMeta(testApp, 0, 3, 0);
    validateChunks(testApp, 0, 3, 0, mergedBlockMeta, new int[]{2}, new int[][]{{0}});

    StreamCallbackWithID stream4 =
      pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(testApp, NO_ATTEMPT_ID, 0, 4, 0, 0, 0));
    closed.acquire();
    // Do not finalize shuffleMergeId 4 can happen during stage cancellation.
    stream4.onData(stream4.getID(), ByteBuffer.wrap(new byte[2]));
    stream4.onComplete(stream4.getID());

    // Check whether the data is cleaned up when higher shuffleMergeId finalize request comes
    // but no blocks pushed for that shuffleMergeId
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(testApp, NO_ATTEMPT_ID, 0, 5));
    closed.acquire();
    assertFalse("MergedBlock meta file for shuffle 0 and shuffleMergeId 4 should be cleaned"
      + " up", appShuffleInfo.getMergedShuffleMetaFile(0, 4, 0).exists());
    assertFalse("MergedBlock index file for shuffle 0 and shuffleMergeId 4 should be cleaned"
      + " up", new File(appShuffleInfo.getMergedShuffleIndexFilePath(0, 4, 0)).exists());
    assertFalse("MergedBlock data file for shuffle 0 and shuffleMergeId 4 should be cleaned"
      + " up", appShuffleInfo.getMergedShuffleDataFile(0, 4, 0).exists());
  }

  @Test
  public void testFinalizationResultIsEmptyWhenTheServerDidNotReceiveAnyBlocks() {
    //shuffle 1 0 is finalized even though the server didn't receive any blocks for it.
    MergeStatuses statuses = pushResolver.finalizeShuffleMerge(
        new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 1, 0));
    assertEquals("no partitions were merged", 0, statuses.reduceIds.length);
    RemoteBlockPushResolver.AppShuffleInfo appShuffleInfo =
        pushResolver.validateAndGetAppShuffleInfo(TEST_APP);
    assertTrue("shuffle 1 should be marked finalized",
        appShuffleInfo.getShuffles().get(1).isFinalized());
    removeApplication(TEST_APP);
  }

  // Test for SPARK-37675 and SPARK-37793
  @Test
  public void testEmptyMergePartitionsAreNotReported() throws IOException {
    //shufflePush_1_0_0_100 is received by the server
    StreamCallbackWithID stream1 = pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 1, 0, 0, 100, 0));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[4]));
    //shuffle 1 0 is finalized
    MergeStatuses statuses = pushResolver.finalizeShuffleMerge(
        new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 1, 0));
    assertEquals("no partitions were merged", 0, statuses.reduceIds.length);
    removeApplication(TEST_APP);
  }

  // Test for SPARK-37675 and SPARK-37793
  @Test
  public void testAllBlocksAreRejectedWhenReceivedAfterFinalization() throws IOException {
    //shufflePush_1_0_0_100 is received by the server
    StreamCallbackWithID stream1 = pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 1, 0, 0, 100, 0));
    stream1.onData(stream1.getID(), ByteBuffer.wrap(new byte[4]));
    stream1.onComplete(stream1.getID());
    //shuffle 1 0 is finalized
    pushResolver.finalizeShuffleMerge(new FinalizeShuffleMerge(TEST_APP, NO_ATTEMPT_ID, 1, 0));
    //shufflePush_1_0_0_200 is received by the server after finalization of shuffle 1 0 which
    //should be rejected
    StreamCallbackWithID failureCallback0 = pushResolver.receiveBlockDataAsStream(
      new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 1, 0, 0, 200, 0));
    BlockPushNonFatalFailure e0 = assertThrows(BlockPushNonFatalFailure.class,
      () -> failureCallback0.onComplete(failureCallback0.getID()));
    BlockPushReturnCode errorCode0 =
      (BlockPushReturnCode) BlockTransferMessage.Decoder.fromByteBuffer(e0.getResponse());
    assertEquals(BlockPushNonFatalFailure.ReturnCode.TOO_LATE_BLOCK_PUSH.id(),
      errorCode0.returnCode);
    assertEquals(errorCode0.failureBlockId, "shufflePush_1_0_0_200");
    //shufflePush_1_0_1_100 is received by the server after finalization of shuffle 1 0 which
    //should also be rejected
    StreamCallbackWithID failureCallback = pushResolver.receiveBlockDataAsStream(
      new PushBlockStream(TEST_APP, NO_ATTEMPT_ID, 1, 0, 1, 100, 0));
    BlockPushNonFatalFailure e1 = assertThrows(BlockPushNonFatalFailure.class,
      () -> failureCallback.onComplete(failureCallback.getID()));
    BlockPushReturnCode errorCode1 =
      (BlockPushReturnCode) BlockTransferMessage.Decoder.fromByteBuffer(e1.getResponse());
    assertEquals(BlockPushNonFatalFailure.ReturnCode.TOO_LATE_BLOCK_PUSH.id(),
      errorCode1.returnCode);
    assertEquals(errorCode1.failureBlockId, "shufflePush_1_0_1_100");
    MergedBlockMeta blockMeta = pushResolver.getMergedBlockMeta(TEST_APP, 1, 0, 100);
    validateChunks(TEST_APP, 1, 0, 100, blockMeta, new int[]{4}, new int[][]{{0}});
    removeApplication(TEST_APP);
  }


  @Test
  public void testJsonSerializationOfPushShufflePartitionInfo() throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    RemoteBlockPushResolver.AppAttemptId appAttemptId =
      new RemoteBlockPushResolver.AppAttemptId("foo", 1);
    String appAttemptIdJson = mapper.writeValueAsString(appAttemptId);
    RemoteBlockPushResolver.AppAttemptId parsedAppAttemptId =
      mapper.readValue(appAttemptIdJson, RemoteBlockPushResolver.AppAttemptId.class);
    assertEquals(appAttemptId, parsedAppAttemptId);

    RemoteBlockPushResolver.AppPathsInfo pathInfo =
      new RemoteBlockPushResolver.AppPathsInfo(new String[]{"/foo", "/bar"}, 64);
    String pathInfoJson = mapper.writeValueAsString(pathInfo);
    RemoteBlockPushResolver.AppPathsInfo
      parsedPathInfo = mapper.readValue(pathInfoJson, RemoteBlockPushResolver.AppPathsInfo.class);
    assertEquals(pathInfo, parsedPathInfo);

    RemoteBlockPushResolver.AppAttemptShuffleMergeId partitionId =
      new RemoteBlockPushResolver.AppAttemptShuffleMergeId("foo", 1, 1, 1);
    String partitionIdJson = mapper.writeValueAsString(partitionId);
    RemoteBlockPushResolver.AppAttemptShuffleMergeId parsedPartitionId =
      mapper.readValue(partitionIdJson, RemoteBlockPushResolver.AppAttemptShuffleMergeId.class);
    assertEquals(partitionId, parsedPartitionId);

    // Intentionally keep these hard-coded strings in here, to check backwards-compatibility.
    // It is not legacy yet, but keeping this here in case anybody changes it
    String legacyAppAttemptIdJson = "{\"appId\": \"foo\", \"attemptId\":\"1\"}";
    assertEquals(appAttemptId,
      mapper.readValue(legacyAppAttemptIdJson, RemoteBlockPushResolver.AppAttemptId.class));
    String legacyAppPathInfoJson =
      "{\"activeLocalDirs\": [\"/foo\", \"/bar\"], \"subDirsPerLocalDir\":\"64\"}";
    assertEquals(pathInfo,
      mapper.readValue(legacyAppPathInfoJson, RemoteBlockPushResolver.AppPathsInfo.class));
    String legacyPartitionIdJson = "{\"appId\":\"foo\", \"attemptId\":\"1\", "
      + "\"shuffleId\":\"1\", \"shuffleMergeId\":\"1\"}";
    assertEquals(partitionId, mapper.readValue(legacyPartitionIdJson,
      RemoteBlockPushResolver.AppAttemptShuffleMergeId.class));
  }

  private void useTestFiles(boolean useTestIndexFile, boolean useTestMetaFile) throws IOException {
    pushResolver = new RemoteBlockPushResolver(conf, null) {
      @Override
      AppShufflePartitionInfo newAppShufflePartitionInfo(
          AppShuffleInfo appShuffleInfo,
          int shuffleId,
          int shuffleMergeId,
          int reduceId,
          File dataFile,
          File indexFile,
          File metaFile) throws IOException {
        MergeShuffleFile mergedIndexFile = useTestIndexFile ?
          new TestMergeShuffleFile(indexFile)
            : new MergeShuffleFile(indexFile);
        MergeShuffleFile mergedMetaFile = useTestMetaFile ?
          new TestMergeShuffleFile(metaFile) :
            new MergeShuffleFile(metaFile);
        return new AppShufflePartitionInfo(new AppAttemptShuffleMergeId(
            appShuffleInfo.appId, appShuffleInfo.attemptId, shuffleId, shuffleMergeId), reduceId,
            dataFile, mergedIndexFile, mergedMetaFile);
      }
    };
    registerExecutor(TEST_APP, prepareLocalDirs(localDirs, MERGE_DIRECTORY), MERGE_DIRECTORY_META);
  }

  private Path[] createLocalDirs(int numLocalDirs) throws IOException {
    Path[] localDirs = new Path[numLocalDirs];
    for (int i = 0; i < localDirs.length; i++) {
      localDirs[i] = Files.createTempDirectory("shuffleMerge");
      localDirs[i].toFile().deleteOnExit();
    }
    return localDirs;
  }

  private void registerExecutor(String appId, String[] localDirs, String shuffleManagerMeta) {
    ExecutorShuffleInfo shuffleInfo = new ExecutorShuffleInfo(localDirs, 1, shuffleManagerMeta);
    pushResolver.registerExecutor(appId, shuffleInfo);
  }

  private String[] prepareLocalDirs(Path[] localDirs, String mergeDir) throws IOException {
    String[] blockMgrDirs = new String[localDirs.length];
    for (int i = 0; i< localDirs.length; i++) {
      Files.createDirectories(localDirs[i].resolve(mergeDir + File.separator + "00"));
      blockMgrDirs[i] = localDirs[i].toFile().getPath() + File.separator + BLOCK_MANAGER_DIR;
    }
    return blockMgrDirs;
  }

  private void removeApplication(String appId) {
    // PushResolver cleans up the local dirs in a different thread which can conflict with the test
    // data of other tests, since they are using the same Application Id.
    pushResolver.applicationRemoved(appId,  false);
  }

  private void validateMergeStatuses(
      MergeStatuses mergeStatuses,
      int[] expectedReduceIds,
      long[] expectedSizes) {
    assertArrayEquals(expectedReduceIds, mergeStatuses.reduceIds);
    assertArrayEquals(expectedSizes, mergeStatuses.sizes);
  }

  private void validateChunks(
      String appId,
      int shuffleId,
      int shuffleMergeId,
      int reduceId,
      MergedBlockMeta meta,
      int[] expectedSizes,
      int[][] expectedMapsPerChunk) throws IOException {
    assertEquals("num chunks", expectedSizes.length, meta.getNumChunks());
    RoaringBitmap[] bitmaps = meta.readChunkBitmaps();
    assertEquals("num of bitmaps", meta.getNumChunks(), bitmaps.length);
    for (int i = 0; i < meta.getNumChunks(); i++) {
      RoaringBitmap chunkBitmap = bitmaps[i];
      assertEquals("cardinality", expectedMapsPerChunk[i].length, chunkBitmap.getCardinality());
      Arrays.stream(expectedMapsPerChunk[i]).forEach(x -> assertTrue(chunkBitmap.contains(x)));
    }
    for (int i = 0; i < meta.getNumChunks(); i++) {
      FileSegmentManagedBuffer mb =
        (FileSegmentManagedBuffer) pushResolver.getMergedBlockData(appId, shuffleId,
          shuffleMergeId, reduceId, i);
      assertEquals(expectedSizes[i], mb.getLength());
    }
  }

  private void pushBlockHelper(
      String appId,
      int attemptId,
      PushBlock[] blocks) throws IOException {
    for (PushBlock block : blocks) {
      StreamCallbackWithID stream = pushResolver.receiveBlockDataAsStream(
        new PushBlockStream(appId, attemptId, block.shuffleId, block.shuffleMergeId,
          block.mapIndex, block.reduceId, 0));
      stream.onData(stream.getID(), block.buffer);
      stream.onComplete(stream.getID());
    }
  }

  private static class PushBlock {
    private final int shuffleId;
    private final int shuffleMergeId;
    private final int mapIndex;
    private final int reduceId;
    private final ByteBuffer buffer;
    PushBlock(int shuffleId, int shuffleMergeId, int mapIndex, int reduceId, ByteBuffer buffer) {
      this.shuffleId = shuffleId;
      this.shuffleMergeId = shuffleMergeId;
      this.mapIndex = mapIndex;
      this.reduceId = reduceId;
      this.buffer = buffer;
    }
  }

  private static class TestMergeShuffleFile extends MergeShuffleFile {
    private DataOutputStream activeDos;
    private File file;
    private FileChannel channel;

    private TestMergeShuffleFile(File file) throws IOException {
      super(file);
      this.file = file;
      FileOutputStream fos = new FileOutputStream(file);
      channel = fos.getChannel();
      activeDos = new DataOutputStream(fos);
    }

    @Override
    public DataOutputStream getDos() {
      return activeDos;
    }

    @Override
    FileChannel getChannel() {
      return channel;
    }

    @Override
    void close() throws IOException {
      activeDos.close();
    }

    void restore() throws IOException {
      FileOutputStream fos = new FileOutputStream(file, true);
      channel = fos.getChannel();
      activeDos = new DataOutputStream(fos);
    }
  }
}
