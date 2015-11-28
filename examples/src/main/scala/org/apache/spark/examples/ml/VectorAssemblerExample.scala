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

import org.apache.spark.sql.SQLContext
import org.apache.spark.{SparkContext, SparkConf}
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.ml.feature.VectorAssembler

/**
 * An example runner for vector assembler. Run with
 * {{{
 * ./bin/run-example ml.VectorAssemblerExample [options]
 * }}}
 */
object VectorAssemblerExample {

  val conf = new SparkConf().setAppName("OneHotEncoderExample")
  val sc = new SparkContext(conf)
  val sqlContext = new SQLContext(sc)

  val dataset = sqlContext.createDataFrame(
    Seq((0, 18, 1.0, Vectors.dense(0.0, 10.0, 0.5), 1.0))
  ).toDF("id", "hour", "mobile", "userFeatures", "clicked")
  val assembler = new VectorAssembler()
    .setInputCols(Array("hour", "mobile", "userFeatures"))
    .setOutputCol("features")
  val output = assembler.transform(dataset)
  println(output.select("features", "clicked").first())
}