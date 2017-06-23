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

package org.apache.spark.examples.ml

// $example on$
import org.apache.spark.ml.feature.FuncTransformer
import org.apache.spark.sql.functions.udf
// $example off$
import org.apache.spark.sql.SparkSession

/**
 * An example demonstrating creating a FuncTransformer.
 *
 * Run with
 * {{{
 * bin/run-example ml.FuncTransformerExample
 * }}}
 */
object FuncTransformerExample {

  def main(args: Array[String]) {
    val spark = SparkSession
      .builder()
      .appName("FuncTransformerExample")
      .getOrCreate()
    import spark.implicits._

    // $example on$
    val df = Seq(0.0, 1.0, 2.0, 3.0).toDF("input")

    val labelConverter = new FuncTransformer(udf { (i: Double) => if (i >= 1) 1 else 0 })
    labelConverter.transform(df).show()

    val shifter = new FuncTransformer(udf { (i: Double) => i + 1 })
    shifter.transform(df).show()
    // $example off$

    spark.stop()
  }
}
