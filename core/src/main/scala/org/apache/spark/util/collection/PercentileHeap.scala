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

package org.apache.spark.util.collection

import scala.collection.mutable.PriorityQueue

/**
 * PercentileHeap tracks the percentile of a collection of numbers.
 *
 * Insertion is O(log n), Lookup is O(1).
 *
 * The implementation keeps two heaps: a small heap (`smallHeap`) and a large heap (`largeHeap`).
 * The small heap stores all the numbers below the percentile and the large heap stores the ones
 * above the percentile. During insertion the relative sizes of the heaps are adjusted to match
 * the target percentile.
 */
private[spark] class PercentileHeap(percentage: Double = 0.5) {
  assert(percentage > 0 && percentage < 1)

  private[this] val largeHeap = PriorityQueue.empty[Double](Ordering[Double].reverse)
  private[this] val smallHeap = PriorityQueue.empty[Double](Ordering[Double])

  def isEmpty(): Boolean = smallHeap.isEmpty && largeHeap.isEmpty

  def size(): Int = smallHeap.size + largeHeap.size

  /**
   * Returns percentile of the inserted elements as if the inserted elements were sorted and we
   * returned `sorted((sorted.length * percentage).toInt)`.
   */
  def percentile(): Double = {
    if (isEmpty) throw new NoSuchElementException("empty")
    largeHeap.head
  }

  def insert(x: Double): Unit = {
    if (isEmpty) {
      largeHeap.enqueue(x)
    } else {
      val p = largeHeap.head
      val growBot = ((size + 1) * percentage).toInt > smallHeap.size
      if (growBot) {
        if (x < p) {
          smallHeap.enqueue(x)
        } else {
          largeHeap.enqueue(x)
          smallHeap.enqueue(largeHeap.dequeue)
        }
      } else {
        if (x < p) {
          smallHeap.enqueue(x)
          largeHeap.enqueue(smallHeap.dequeue())
        } else {
          largeHeap.enqueue(x)
        }
      }
    }
  }
}
