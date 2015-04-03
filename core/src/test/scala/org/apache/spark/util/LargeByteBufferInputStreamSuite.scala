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
package org.apache.spark.util

import org.scalatest.{FunSuite, Matchers}

import org.apache.spark.network.buffer.WrappedLargeByteBuffer

class LargeByteBufferInputStreamSuite extends FunSuite with Matchers {

  test("read from large buffers") {
    pending
  }

  test("dispose") {
    pending
  }

  test("io stream roundtrip") {

    val out = new LargeByteBufferOutputStream(128)
    (0 until 200).foreach{idx => out.write(idx)}
    out.close()

    val lb = out.largeBuffer(128)
    //just make sure that we test reading from multiple chunks
    lb.asInstanceOf[WrappedLargeByteBuffer].underlying.size should be > 1

    val rawIn = new LargeByteBufferInputStream(lb)
    val arr = new Array[Byte](500)
    val nRead = rawIn.read(arr, 0, 500)
    nRead should be (200)
    (0 until 200).foreach{idx =>
      arr(idx) should be (idx.toByte)
    }

  }


}
