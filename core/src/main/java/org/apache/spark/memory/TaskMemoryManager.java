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

package org.apache.spark.memory;

import java.io.IOException;
import java.util.BitSet;
import java.util.HashMap;

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.spark.SparkException;
import org.apache.spark.unsafe.Platform;
import org.apache.spark.unsafe.memory.MemoryBlock;
import org.apache.spark.util.Utils;

/**
 * Manages the memory allocated by an individual task.
 * <p>
 * Most of the complexity in this class deals with encoding of off-heap addresses into 64-bit longs.
 * In off-heap mode, memory can be directly addressed with 64-bit longs. In on-heap mode, memory is
 * addressed by the combination of a base Object reference and a 64-bit offset within that object.
 * This is a problem when we want to store pointers to data structures inside of other structures,
 * such as record pointers inside hashmaps or sorting buffers. Even if we decided to use 128 bits
 * to address memory, we can't just store the address of the base object since it's not guaranteed
 * to remain stable as the heap gets reorganized due to GC.
 * <p>
 * Instead, we use the following approach to encode record pointers in 64-bit longs: for off-heap
 * mode, just store the raw address, and for on-heap mode use the upper 13 bits of the address to
 * store a "page number" and the lower 51 bits to store an offset within this page. These page
 * numbers are used to index into a "page table" array inside of the MemoryManager in order to
 * retrieve the base object.
 * <p>
 * This allows us to address 8192 pages. In on-heap mode, the maximum page size is limited by the
 * maximum size of a long[] array, allowing us to address 8192 * 2^32 * 8 bytes, which is
 * approximately 35 terabytes of memory.
 */
public class TaskMemoryManager {

  private final Logger logger = LoggerFactory.getLogger(TaskMemoryManager.class);

  /** The number of bits used to address the page table. */
  private static final int PAGE_NUMBER_BITS = 13;

  /** The number of bits used to encode offsets in data pages. */
  @VisibleForTesting
  static final int OFFSET_BITS = 64 - PAGE_NUMBER_BITS;  // 51

  /** The number of entries in the page table. */
  private static final int PAGE_TABLE_SIZE = 1 << PAGE_NUMBER_BITS;

  /**
   * Maximum supported data page size (in bytes). In principle, the maximum addressable page size is
   * (1L &lt;&lt; OFFSET_BITS) bytes, which is 2+ petabytes. However, the on-heap allocator's maximum page
   * size is limited by the maximum amount of data that can be stored in a  long[] array, which is
   * (2^32 - 1) * 8 bytes (or 16 gigabytes). Therefore, we cap this at 16 gigabytes.
   */
  public static final long MAXIMUM_PAGE_SIZE_BYTES = ((1L << 31) - 1) * 8L;

  /** Bit mask for the lower 51 bits of a long. */
  private static final long MASK_LONG_LOWER_51_BITS = 0x7FFFFFFFFFFFFL;

  /** Bit mask for the upper 13 bits of a long */
  private static final long MASK_LONG_UPPER_13_BITS = ~MASK_LONG_LOWER_51_BITS;

  /**
   * Similar to an operating system's page table, this array maps page numbers into base object
   * pointers, allowing us to translate between the hashtable's internal 64-bit address
   * representation and the baseObject+offset representation which we use to support both in- and
   * off-heap addresses. When using an off-heap allocator, every entry in this map will be `null`.
   * When using an in-heap allocator, the entries in this map will point to pages' base objects.
   * Entries are added to this map as new data pages are allocated.
   */
  private final MemoryBlock[] pageTable = new MemoryBlock[PAGE_TABLE_SIZE];

  /**
   * Bitmap for tracking free pages.
   */
  private final BitSet allocatedPages = new BitSet(PAGE_TABLE_SIZE);

  private final MemoryManager memoryManager;

  private final long taskAttemptId;

  /**
   * Tracks whether we're in-heap or off-heap. For off-heap, we short-circuit most of these methods
   * without doing any masking or lookups. Since this branching should be well-predicted by the JIT,
   * this extra layer of indirection / abstraction hopefully shouldn't be too expensive.
   */
  private final boolean inHeap;

  /**
   * The size of memory granted to each consumer.
   */
  private final HashMap<MemoryConsumer, Long> consumers;

  /**
   * Construct a new TaskMemoryManager.
   */
  public TaskMemoryManager(MemoryManager memoryManager, long taskAttemptId) {
    this.inHeap = memoryManager.tungstenMemoryIsAllocatedInHeap();
    this.memoryManager = memoryManager;
    this.taskAttemptId = taskAttemptId;
    this.consumers = new HashMap<>();
  }

  /**
   * Acquire N bytes of memory for a consumer. If there is no enough memory, it will call
   * spill() of consumers to release more memory.
   *
   * @return number of bytes successfully granted (<= N).
   */
  public long acquireExecutionMemory(long required, MemoryConsumer consumer) throws IOException {
    assert(required >= 0);
    synchronized (this) {
      long got = memoryManager.acquireExecutionMemory(required, taskAttemptId);

      if (got < required) {
        // consumers could be modified by spill(), so we should have a copy here.
        MemoryConsumer[] cs = new MemoryConsumer[consumers.size()];
        consumers.keySet().toArray(cs);
        // Call spill() on other consumers to release memory
        for (MemoryConsumer c: cs) {
          if (c != null) {
            long released = c.spill(required - got, consumer);
            logger.info("released " + Utils.bytesToString(released) + " from " + consumer);
            if (released >= 0) {
              got += memoryManager.acquireExecutionMemory(required - got, taskAttemptId);
              if (got >= required) {
                break;
              }
            }
          }
        }
      }

      // Update the accounting, even consumer is null
      if (got > 0) {
        long old = 0L;
        if (consumers.containsKey(consumer)) {
          old = consumers.get(consumer);
        }
        consumers.put(consumer, got + old);
      }

      if (logger.isTraceEnabled()) {
        logger.trace("Acquire {} for {}", Utils.bytesToString(got), consumer);
      }
      return got;
    }
  }

  /**
   * Release N bytes of execution memory for a MemoryConsumer.
   */
  public void releaseExecutionMemory(long size, MemoryConsumer consumer) {
    assert(size >= 0);
    if (size == 0) {
      return;
    }
    synchronized (this) {
      if (consumers.containsKey(consumer)) {
        long old = consumers.get(consumer);
        if (old > size) {
          consumers.put(consumer, old - size);
        } else {
          if (old < size) {
            String msg = "Release " + size + " bytes memory (more than acquired " + old + ") for "
              + consumer;
            logger.warn(msg);
            if (Utils.isTesting()) {
              Platform.throwException(new SparkException(msg));
            }
          }
          consumers.remove(consumer);
        }
      } else {
        String msg = "Release " + size + " bytes memory for non-existent " + consumer;
        logger.warn(msg);
        if (Utils.isTesting()) {
          Platform.throwException(new SparkException(msg));
        }
      }
    }

    if (logger.isTraceEnabled()) {
      logger.trace("Release {} from {}", Utils.bytesToString(size), consumer);
    }
    memoryManager.releaseExecutionMemory(size, taskAttemptId);
  }

  /**
   * Dump the memory usage of all consumers.
   */
  public void showMemoryUsage() {
    logger.info("Memory used in task " + taskAttemptId);
    synchronized (this) {
      for (MemoryConsumer c: consumers.keySet()) {
        logger.info("acquired by " + c + ": " + Utils.bytesToString(consumers.get(c)));
      }
    }
  }

  /**
   * Return the page size in bytes.
   */
  public long pageSizeBytes() {
    return memoryManager.pageSizeBytes();
  }

  /**
   * Allocate a block of memory that will be tracked in the MemoryManager's page table; this is
   * intended for allocating large blocks of Tungsten memory that will be shared between operators.
   *
   * Returns `null` if there was not enough memory to allocate the page.
   */
  public MemoryBlock allocatePage(long size, MemoryConsumer consumer) throws IOException {
    if (size > MAXIMUM_PAGE_SIZE_BYTES) {
      throw new IllegalArgumentException(
        "Cannot allocate a page with more than " + MAXIMUM_PAGE_SIZE_BYTES + " bytes");
    }

    long acquired = acquireExecutionMemory(size, consumer);
    if (acquired <= 0) {
      return null;
    }

    final int pageNumber;
    synchronized (this) {
      pageNumber = allocatedPages.nextClearBit(0);
      if (pageNumber >= PAGE_TABLE_SIZE) {
        releaseExecutionMemory(acquired, consumer);
        throw new IllegalStateException(
          "Have already allocated a maximum of " + PAGE_TABLE_SIZE + " pages");
      }
      allocatedPages.set(pageNumber);
    }
    final MemoryBlock page = memoryManager.tungstenMemoryAllocator().allocate(acquired);
    page.pageNumber = pageNumber;
    pageTable[pageNumber] = page;
    if (logger.isTraceEnabled()) {
      logger.trace("Allocate page number {} ({} bytes)", pageNumber, acquired);
    }
    return page;
  }

  /**
   * Free a block of memory allocated via {@link TaskMemoryManager#allocatePage}.
   */
  public void freePage(MemoryBlock page, MemoryConsumer consumer) {
    assert (page.pageNumber != -1) :
      "Called freePage() on memory that wasn't allocated with allocatePage()";
    assert(allocatedPages.get(page.pageNumber));
    pageTable[page.pageNumber] = null;
    synchronized (this) {
      allocatedPages.clear(page.pageNumber);
    }
    if (logger.isTraceEnabled()) {
      logger.trace("Freed page number {} ({} bytes)", page.pageNumber, page.size());
    }
    long pageSize = page.size();
    memoryManager.tungstenMemoryAllocator().free(page);
    releaseExecutionMemory(pageSize, consumer);
  }

  /**
   * Given a memory page and offset within that page, encode this address into a 64-bit long.
   * This address will remain valid as long as the corresponding page has not been freed.
   *
   * @param page a data page allocated by {@link TaskMemoryManager#allocatePage}/
   * @param offsetInPage an offset in this page which incorporates the base offset. In other words,
   *                     this should be the value that you would pass as the base offset into an
   *                     UNSAFE call (e.g. page.baseOffset() + something).
   * @return an encoded page address.
   */
  public long encodePageNumberAndOffset(MemoryBlock page, long offsetInPage) {
    if (!inHeap) {
      // In off-heap mode, an offset is an absolute address that may require a full 64 bits to
      // encode. Due to our page size limitation, though, we can convert this into an offset that's
      // relative to the page's base offset; this relative offset will fit in 51 bits.
      offsetInPage -= page.getBaseOffset();
    }
    return encodePageNumberAndOffset(page.pageNumber, offsetInPage);
  }

  @VisibleForTesting
  public static long encodePageNumberAndOffset(int pageNumber, long offsetInPage) {
    assert (pageNumber != -1) : "encodePageNumberAndOffset called with invalid page";
    return (((long) pageNumber) << OFFSET_BITS) | (offsetInPage & MASK_LONG_LOWER_51_BITS);
  }

  @VisibleForTesting
  public static int decodePageNumber(long pagePlusOffsetAddress) {
    return (int) ((pagePlusOffsetAddress & MASK_LONG_UPPER_13_BITS) >>> OFFSET_BITS);
  }

  private static long decodeOffset(long pagePlusOffsetAddress) {
    return (pagePlusOffsetAddress & MASK_LONG_LOWER_51_BITS);
  }

  /**
   * Get the page associated with an address encoded by
   * {@link TaskMemoryManager#encodePageNumberAndOffset(MemoryBlock, long)}
   */
  public Object getPage(long pagePlusOffsetAddress) {
    if (inHeap) {
      final int pageNumber = decodePageNumber(pagePlusOffsetAddress);
      assert (pageNumber >= 0 && pageNumber < PAGE_TABLE_SIZE);
      final MemoryBlock page = pageTable[pageNumber];
      assert (page != null);
      assert (page.getBaseObject() != null);
      return page.getBaseObject();
    } else {
      return null;
    }
  }

  /**
   * Get the offset associated with an address encoded by
   * {@link TaskMemoryManager#encodePageNumberAndOffset(MemoryBlock, long)}
   */
  public long getOffsetInPage(long pagePlusOffsetAddress) {
    final long offsetInPage = decodeOffset(pagePlusOffsetAddress);
    if (inHeap) {
      return offsetInPage;
    } else {
      // In off-heap mode, an offset is an absolute address. In encodePageNumberAndOffset, we
      // converted the absolute address into a relative address. Here, we invert that operation:
      final int pageNumber = decodePageNumber(pagePlusOffsetAddress);
      assert (pageNumber >= 0 && pageNumber < PAGE_TABLE_SIZE);
      final MemoryBlock page = pageTable[pageNumber];
      assert (page != null);
      return page.getBaseOffset() + offsetInPage;
    }
  }

  /**
   * Clean up all allocated memory and pages. Returns the number of bytes freed. A non-zero return
   * value can be used to detect memory leaks.
   */
  public long cleanUpAllAllocatedMemory() {
    long freedBytes = 0;
    for (MemoryBlock page : pageTable) {
      if (page != null) {
        freedBytes += page.size();
        freePage(page, null);
      }
    }
    consumers.clear();

    freedBytes += memoryManager.releaseAllExecutionMemoryForTask(taskAttemptId);

    return freedBytes;
  }

  /**
   * Returns the memory consumption, in bytes, for the current task
   */
  public long getMemoryConsumptionForThisTask() {
    return memoryManager.getExecutionMemoryUsageForTask(taskAttemptId);
  }
}
