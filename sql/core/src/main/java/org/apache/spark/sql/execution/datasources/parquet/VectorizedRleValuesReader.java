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

package org.apache.spark.sql.execution.datasources.parquet;

import org.apache.commons.lang.NotImplementedException;
import org.apache.parquet.Preconditions;
import org.apache.parquet.bytes.BytesUtils;
import org.apache.parquet.column.values.ValuesReader;
import org.apache.parquet.column.values.bitpacking.BytePacker;
import org.apache.parquet.column.values.bitpacking.Packer;
import org.apache.parquet.io.ParquetDecodingException;
import org.apache.parquet.io.api.Binary;

import org.apache.spark.sql.Column;
import org.apache.spark.sql.execution.vectorized.ColumnVector;

/**
 * A values reader for Parquet's run-length encoded data. This is based off of the version in
 * parquet-mr with these changes:
 *  - Supports the vectorized interface.
 *  - Works on byte arrays(byte[]) instead of making byte streams.
 *
 * This encoding is used in multiple places:
 *  - Definition/Repetition levels
 *  - Dictionary ids.
 */
public final class VectorizedRleValuesReader extends ValuesReader
    implements VectorizedValuesReader {
  // Current decoding mode. The encoded data contains groups of either run length encoded data
  // (RLE) or bit packed data. Each group contains a header that indicates which group it is and
  // the number of values in the group.
  // More details here: https://github.com/Parquet/parquet-format/blob/master/Encodings.md
  private enum MODE {
    RLE,
    PACKED
  }

  // Encoded data.
  private byte[] in;
  private int end;
  private int offset;

  // bit/byte width of decoded data and utility to batch unpack them.
  private int bitWidth;
  private int bytesWidth;
  private BytePacker packer;

  // Current decoding mode and values
  private MODE mode;
  private int currentCount;
  private int currentValue;

  // Buffer of decoded values if the values are PACKED.
  private int[] currentBuffer = new int[16];
  private int currentBufferIdx = 0;

  // If true, the bit width is fixed. This decoder is used in different places and this also
  // controls if we need to read the bitwidth from the beginning of the data stream.
  private final boolean fixedWidth;

  public VectorizedRleValuesReader() {
    fixedWidth = false;
  }

  public VectorizedRleValuesReader(int bitWidth) {
    fixedWidth = true;
    init(bitWidth);
  }

  @Override
  public void initFromPage(int valueCount, byte[] page, int start) {
    this.offset = start;
    this.in = page;
    if (fixedWidth) {
      int length = readIntLittleEndian();
      this.end = this.offset + length;
    } else {
      this.end = page.length;
      if (this.end != this.offset) init(page[this.offset++] & 255);
    }
    this.currentCount = 0;
  }

  /**
   * Initializes the internal state for decoding ints of `bitWidth`.
   */
  private void init(int bitWidth) {
    Preconditions.checkArgument(bitWidth >= 0 && bitWidth <= 32, "bitWidth must be >= 0 and <= 32");
    this.bitWidth = bitWidth;
    this.bytesWidth = BytesUtils.paddedByteCountFromBits(bitWidth);
    this.packer = Packer.LITTLE_ENDIAN.newBytePacker(bitWidth);
  }

  @Override
  public int getNextOffset() {
    return this.end;
  }

  @Override
  public boolean readBoolean() {
    return this.readInteger() != 0;
  }

  @Override
  public void skip() {
    this.readInteger();
  }

  @Override
  public int readValueDictionaryId() {
    return readInteger();
  }


  @Override
  public int readInteger() {
    if (this.currentCount == 0) { this.readNextGroup(); }

    this.currentCount--;
    switch (mode) {
      case RLE:
        return this.currentValue;
      case PACKED:
        return this.currentBuffer[currentBufferIdx++];
    }
    throw new RuntimeException("Unreachable");
  }

  /**
   * Reads `total` ints into `c` filling them in starting at `c[rowId]`. This reader
   * reads the definition levels and then will read from `data` for the non-null values.
   * If the value is null, c will be populated with `nullValue`. Note that `nullValue` is only
   * necessary for readIntegers because we also use it to decode dictionaryIds and want to make
   * sure it always has a value in range.
   *
   * This is a batched version of this logic:
   *  if (this.readInt() == level) {
   *    c[rowId] = data.readInteger();
   *  } else {
   *    c[rowId] = nullValue;
   *  }
   */
  public void readIntegers(int total, ColumnVector c, int rowId, int level,
      VectorizedValuesReader data, int nullValue) {
    int left = total;
    while (left > 0) {
      if (this.currentCount == 0) this.readNextGroup();
      int n = Math.min(left, this.currentCount);
      switch (mode) {
        case RLE:
          if (currentValue == level) {
            data.readIntegers(n, c, rowId);
            c.putNotNulls(rowId, n);
          } else {
            c.putNulls(rowId, n);
          }
          break;
        case PACKED:
          for (int i = 0; i < n; ++i) {
            if (currentBuffer[currentBufferIdx++] == level) {
              c.putInt(rowId + i, data.readInteger());
              c.putNotNull(rowId + i);
            } else {
              c.putInt(rowId + i, nullValue);
              c.putNull(rowId + i);
            }
          }
          break;
      }
      rowId += n;
      left -= n;
      currentCount -= n;
    }
  }

  // TODO: can this code duplication be removed without a perf penalty?
  public void readBytes(int total, ColumnVector c,
                        int rowId, int level, VectorizedValuesReader data) {
    int left = total;
    while (left > 0) {
      if (this.currentCount == 0) this.readNextGroup();
      int n = Math.min(left, this.currentCount);
      switch (mode) {
        case RLE:
          if (currentValue == level) {
            data.readBytes(n, c, rowId);
            c.putNotNulls(rowId, n);
          } else {
            c.putNulls(rowId, n);
          }
          break;
        case PACKED:
          for (int i = 0; i < n; ++i) {
            if (currentBuffer[currentBufferIdx++] == level) {
              c.putByte(rowId + i, data.readByte());
              c.putNotNull(rowId + i);
            } else {
              c.putNull(rowId + i);
            }
          }
          break;
      }
      rowId += n;
      left -= n;
      currentCount -= n;
    }
  }

  public void readLongs(int total, ColumnVector c, int rowId, int level,
                        VectorizedValuesReader data) {
    int left = total;
    while (left > 0) {
      if (this.currentCount == 0) this.readNextGroup();
      int n = Math.min(left, this.currentCount);
      switch (mode) {
        case RLE:
          if (currentValue == level) {
            data.readLongs(n, c, rowId);
            c.putNotNulls(rowId, n);
          } else {
            c.putNulls(rowId, n);
          }
          break;
        case PACKED:
          for (int i = 0; i < n; ++i) {
            if (currentBuffer[currentBufferIdx++] == level) {
              c.putLong(rowId + i, data.readLong());
              c.putNotNull(rowId + i);
            } else {
              c.putNull(rowId + i);
            }
          }
          break;
      }
      rowId += n;
      left -= n;
      currentCount -= n;
    }
  }

  public void readBinarys(int total, ColumnVector c, int rowId, int level,
                        VectorizedValuesReader data) {
    int left = total;
    while (left > 0) {
      if (this.currentCount == 0) this.readNextGroup();
      int n = Math.min(left, this.currentCount);
      switch (mode) {
        case RLE:
          if (currentValue == level) {
            c.putNotNulls(rowId, n);
            data.readBinary(n, c, rowId);
          } else {
            c.putNulls(rowId, n);
          }
          break;
        case PACKED:
          for (int i = 0; i < n; ++i) {
            if (currentBuffer[currentBufferIdx++] == level) {
              c.putNotNull(rowId + i);
              data.readBinary(1, c, rowId);
            } else {
              c.putNull(rowId + i);
            }
          }
          break;
      }
      rowId += n;
      left -= n;
      currentCount -= n;
    }
  }


  // This is used for decoding dictionary IDs (as opposed to definition levels).
  @Override
  public void readIntegers(int total, ColumnVector c, int rowId) {
    int left = total;
    while (left > 0) {
    if (this.currentCount == 0) this.readNextGroup();
      int n = Math.min(left, this.currentCount);
      switch (mode) {
        case RLE:
          c.putInts(rowId, n, currentValue);
          break;
        case PACKED:
          c.putInts(rowId, n, currentBuffer, currentBufferIdx);
          currentBufferIdx += n;
          break;
      }
      rowId += n;
      left -= n;
      currentCount -= n;
    }
  }

  @Override
  public byte readByte() {
    throw new UnsupportedOperationException("only readInts is valid.");
  }

  @Override
  public void readBytes(int total, ColumnVector c, int rowId) {
    throw new UnsupportedOperationException("only readInts is valid.");
  }

  @Override
  public void readLongs(int total, ColumnVector c, int rowId) {
    throw new UnsupportedOperationException("only readInts is valid.");
  }

  @Override
  public void readBinary(int total, ColumnVector c, int rowId) {
    throw new UnsupportedOperationException("only readInts is valid.");
  }

  @Override
  public void skip(int n) {
    throw new UnsupportedOperationException("only readInts is valid.");
  }


  /**
   * Reads the next varint encoded int.
   */
  private int readUnsignedVarInt() {
    int value = 0;
    int shift = 0;
    int b;
    do {
      b = in[offset++] & 255;
      value |= (b & 0x7F) << shift;
      shift += 7;
    } while ((b & 0x80) != 0);
    return value;
  }

  /**
   * Reads the next 4 byte little endian int.
   */
  private int readIntLittleEndian() {
    int ch4 = in[offset] & 255;
    int ch3 = in[offset + 1] & 255;
    int ch2 = in[offset + 2] & 255;
    int ch1 = in[offset + 3] & 255;
    offset += 4;
    return ((ch1 << 24) + (ch2 << 16) + (ch3 << 8) + (ch4 << 0));
  }

  /**
   * Reads the next byteWidth little endian int.
   */
  private int readIntLittleEndianPaddedOnBitWidth() {
    switch (bytesWidth) {
      case 0:
        return 0;
      case 1:
        return in[offset++] & 255;
      case 2: {
        int ch2 = in[offset] & 255;
        int ch1 = in[offset + 1] & 255;
        offset += 2;
        return (ch1 << 8) + ch2;
      }
      case 3: {
        int ch3 = in[offset] & 255;
        int ch2 = in[offset + 1] & 255;
        int ch1 = in[offset + 2] & 255;
        offset += 3;
        return (ch1 << 16) + (ch2 << 8) + (ch3 << 0);
      }
      case 4: {
        return readIntLittleEndian();
      }
    }
    throw new RuntimeException("Unreachable");
  }

  /**
   * Reads the next group.
   */
  private void readNextGroup()  {
    Preconditions.checkArgument(this.offset < this.end,
        "Reading past RLE/BitPacking stream. offset=" + this.offset + " end=" + this.end);
    int header = readUnsignedVarInt();
    this.mode = (header & 1) == 0 ? MODE.RLE : MODE.PACKED;
    switch (mode) {
      case RLE:
        this.currentCount = header >>> 1;
        this.currentValue = readIntLittleEndianPaddedOnBitWidth();
        return;
      case PACKED:
        int numGroups = header >>> 1;
        this.currentCount = numGroups * 8;

        if (this.currentBuffer.length < this.currentCount) {
          this.currentBuffer = new int[this.currentCount];
        }
        currentBufferIdx = 0;
        int bytesToRead = (int)Math.ceil((double)(this.currentCount * this.bitWidth) / 8.0D);

        bytesToRead = Math.min(bytesToRead, this.end - this.offset);
        int valueIndex = 0;
        for (int byteIndex = offset; valueIndex < this.currentCount; byteIndex += this.bitWidth) {
          this.packer.unpack8Values(in, byteIndex, this.currentBuffer, valueIndex);
          valueIndex += 8;
        }
        offset += bytesToRead;
        return;
      default:
        throw new ParquetDecodingException("not a valid mode " + this.mode);
    }
  }
}