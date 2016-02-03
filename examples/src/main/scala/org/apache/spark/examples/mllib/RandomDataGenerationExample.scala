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

// scalastyle:off println
package org.apache.spark.examples.mllib

// $example on$
import org.apache.spark.mllib.random.RandomRDDs._

// $example off$
import org.apache.spark.sql.SQLContext
import org.apache.spark.{SparkConf, SparkContext}

object RandomDataGenerationExample {

  def main(args: Array[String]) {

    val conf = new SparkConf().setAppName("RandomDataGenerationExample").setMaster("local[*]")
    val sc = new SparkContext(conf)
    val sqlContext = new SQLContext(sc)

    // $example on$

    // @note: todo

    // Generate a random double RDD that contains 1 million i.i.d. values drawn from the
    // standard normal distribution `N(0, 1)`, evenly distributed in 10 partitions.
    val u = normalRDD(sc, 1000000L, 10)
    // Apply a transform to get a random double RDD following `N(1, 4)`.
    val v = u.map(x => 1.0 + 2.0 * x)

    // $example off$

    sc.stop()
  }
}
// scalastyle:on println

