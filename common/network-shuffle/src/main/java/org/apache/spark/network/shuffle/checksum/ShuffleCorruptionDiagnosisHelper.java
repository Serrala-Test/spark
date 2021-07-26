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

package org.apache.spark.network.shuffle.checksum;

import java.io.*;
import java.util.zip.Adler32;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.Checksum;

import com.google.common.io.ByteStreams;
import org.apache.spark.annotation.Private;
import org.apache.spark.network.buffer.ManagedBuffer;
import org.apache.spark.network.corruption.Cause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A set of utility functions for the shuffle checksum.
 */
@Private
public class ShuffleCorruptionDiagnosisHelper {
  private static final Logger logger =
    LoggerFactory.getLogger(ShuffleCorruptionDiagnosisHelper.class);

  public static final int CHECKSUM_CALCULATION_BUFFER = 8192;

  private static Checksum[] getChecksumByAlgorithm(int num, String algorithm)
    throws UnsupportedOperationException {
    Checksum[] checksums;
    switch (algorithm) {
      case "ADLER32":
        checksums = new Adler32[num];
        for (int i = 0; i < num; i++) {
          checksums[i] = new Adler32();
        }
        return checksums;

      case "CRC32":
        checksums = new CRC32[num];
        for (int i = 0; i < num; i++) {
          checksums[i] = new CRC32();
        }
        return checksums;

      default:
        throw new UnsupportedOperationException("Unsupported shuffle checksum algorithm: " +
          algorithm);
    }
  }

  public static Checksum getChecksumByFileExtension(String fileName) {
    int index = fileName.lastIndexOf(".");
    String algorithm = fileName.substring(index + 1);
    return getChecksumByAlgorithm(1, algorithm)[0];
  }

  private static long readChecksumByReduceId(File checksumFile, int reduceId) throws IOException {
    try (DataInputStream in = new DataInputStream(new FileInputStream(checksumFile))) {
      ByteStreams.skipFully(in, reduceId * 8);
      return in.readLong();
    }
  }

  private static long calculateChecksumForPartition(
      ManagedBuffer partitionData,
      Checksum checksumAlgo) throws IOException {
    InputStream in = partitionData.createInputStream();
    byte[] buffer = new byte[CHECKSUM_CALCULATION_BUFFER];
    try(CheckedInputStream checksumIn = new CheckedInputStream(in, checksumAlgo)) {
      while (checksumIn.read(buffer, 0, CHECKSUM_CALCULATION_BUFFER) != -1) {}
      return checksumAlgo.getValue();
    }
  }

  /**
   * Diagnose the possible cause of the shuffle data corruption by verifying the shuffle checksums.
   *
   * There're 3 different kinds of checksums for the same shuffle partition:
   *   - checksum (c1) that is calculated by the shuffle data reader
   *   - checksum (c2) that is calculated by the shuffle data writer and stored in the checksum file
   *   - checksum (c3) that is recalculated during diagnosis
   *
   * And the diagnosis mechanism works like this:
   * If c2 != c3, we suspect the corruption is caused by the DISK_ISSUE. Otherwise, if c1 != c3,
   * we suspect the corruption is caused by the NETWORK_ISSUE. Otherwise, the cause remains
   * CHECKSUM_VERIFY_PASS. In case of the any other failures, the cause remains UNKNOWN_ISSUE.
   *
   * @param checksumFile The checksum file that written by the shuffle writer
   * @param reduceId The reduceId of the shuffle block
   * @param partitionData The partition data of the shuffle block
   * @param checksumByReader The checksum value that calculated by the shuffle data reader
   * @return The cause of data corruption
   */
  public static Cause diagnoseCorruption(
      File checksumFile,
      int reduceId,
      ManagedBuffer partitionData,
      long checksumByReader) {
    Cause cause;
    try {
      long diagnoseStart = System.currentTimeMillis();
      long checksumByWriter = readChecksumByReduceId(checksumFile, reduceId);
      Checksum checksumAlgo = getChecksumByFileExtension(checksumFile.getName());
      long checksumByReCalculation = calculateChecksumForPartition(partitionData, checksumAlgo);
      long duration = System.currentTimeMillis() - diagnoseStart;
      logger.info("Shuffle corruption diagnosis took {} ms, checksum file {}",
        duration, checksumFile.getAbsolutePath());
      if (checksumByWriter != checksumByReCalculation) {
        cause = Cause.DISK_ISSUE;
      } else if (checksumByWriter != checksumByReader) {
        cause = Cause.NETWORK_ISSUE;
      } else {
        cause = Cause.CHECKSUM_VERIFY_PASS;
      }
    } catch (FileNotFoundException e) {
      // Even if checksum is enabled, a checksum file may not exist if error throws during writing.
      logger.warn("Checksum file " + checksumFile.getName() + " doesn't exit");
      cause = Cause.UNKNOWN_ISSUE;
    } catch (Exception e) {
      logger.warn("Exception throws while diagnosing shuffle block corruption.", e);
      cause = Cause.UNKNOWN_ISSUE;
    }
    return cause;
  }
}
