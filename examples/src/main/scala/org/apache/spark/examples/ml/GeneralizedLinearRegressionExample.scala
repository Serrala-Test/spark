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
package org.apache.spark.examples.ml

import org.apache.spark.{SparkConf, SparkContext}
// $example on$
import org.apache.spark.ml.regression.GeneralizedLinearRegression
// $example off$
import org.apache.spark.sql.SQLContext

object GeneralizedLinearRegressionExample {

  def main(args: Array[String]): Unit = {
    val conf = new SparkConf().setAppName("GeneralizedLinearRegressionExample")
    val sc = new SparkContext(conf)
    val sqlContext = new SQLContext(sc)

    // $example on$
    // Load training data
    val training = sqlContext.read.format("libsvm")
      .load("data/mllib/sample_linear_regression_data.txt")

    val glr = new GeneralizedLinearRegression()
      .setFamily("gaussian")
      .setLink("identity")
      .setMaxIter(10)
      .setRegParam(0.3)

    // Fit the model
    val model = glr.fit(training)

    // Print the coefficients and intercept for generalized linear regression model
    println(s"Coefficients: ${model.coefficients} Intercept: ${model.intercept}")

    // Summarize the model over the training set and print out some metrics
    val summary = model.summary
    println(s"Coefficient Standard Errors: ${summary.coefficientStandardErrors.mkString(",")}")
    println(s"T Values: ${summary.tValues.mkString(",")}")
    println(s"P Values: ${summary.pValues.mkString(",")}")
    println(s"Dispersion: ${summary.dispersion}")
    println(s"Null Deviance: ${summary.nullDeviance}")
    println(s"Residual Degree Of Freedom Null: ${summary.residualDegreeOfFreedomNull}")
    println(s"Deviance: ${summary.deviance}")
    println(s"Residual Degree Of Freedom: ${summary.residualDegreeOfFreedom}")
    println(s"AIC: ${summary.aic}")
    println("DevianceResiduals: ")
    summary.residuals().show()
    // $example off$

    sc.stop()
  }
}
// scalastyle:on println
