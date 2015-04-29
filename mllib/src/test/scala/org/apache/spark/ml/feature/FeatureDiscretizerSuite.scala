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

package org.apache.spark.ml.feature

import scala.util.Random

import org.scalatest.FunSuite

import org.apache.spark.ml.attribute.{Attribute, NominalAttribute}
import org.apache.spark.mllib.util.MLlibTestSparkContext
import org.apache.spark.sql.{Row, SQLContext}

class FeatureDiscretizerSuite extends FunSuite with MLlibTestSparkContext {

  test("Test feature discretizer") {
    val sqlContext = new SQLContext(sc)
    import sqlContext.implicits._

    val random = new Random(47)
    val data = Array.fill[Double](10)(random.nextDouble())
    val result = Array[Double](2, 1, 0, 0, 1, 1, 1, 0, 2, 2)

    val df = sc.parallelize(data.zip(result)).toDF("data", "expected")

    val discretizer = new FeatureDiscretizer()
      .setInputCol("data")
      .setOutputCol("result")
      .setNumBins(3)

    val res = discretizer.transform(df)
    res.select("expected", "result").collect().foreach {
      case Row(expected: Double, result: Double) => assert(expected == result)
    }

    val attr = Attribute.fromStructField(res.schema("result")).asInstanceOf[NominalAttribute]
    assert(attr.values.get === Array("0.18847866977771732","0.5309454508634242"))
  }
}
