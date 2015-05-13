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

package org.apache.spark.shuffle.unsafe

import org.apache.spark.ShuffleSuite
import org.scalatest.BeforeAndAfterAll

class UnsafeShuffleSuite extends ShuffleSuite with BeforeAndAfterAll {

  // This test suite should run all tests in ShuffleSuite with unsafe-based shuffle.

  override def beforeAll() {
    conf.set("spark.shuffle.manager", "tungsten-sort")
    // UnsafeShuffleManager requires at least 128 MB of memory per task in order to be able to sort
    // shuffle records.
    conf.set("spark.shuffle.memoryFraction", "0.5")
  }
}
