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

package org.apache.spark.examples.mllib;

import java.util.Arrays;

import org.apache.spark.SparkConf;
// $example on$
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.mllib.linalg.Matrices;
import org.apache.spark.mllib.linalg.Matrix;
import org.apache.spark.mllib.linalg.Vector;
import org.apache.spark.mllib.linalg.Vectors;
import org.apache.spark.mllib.regression.LabeledPoint;
import org.apache.spark.mllib.stat.Statistics;
import org.apache.spark.mllib.stat.test.ChiSqTestResult;
// $example off$

public class JavaHypothesisTestingExample {
  public static void main(String[] args) {

    SparkConf conf = new SparkConf().setAppName("JavaHypothesisTestingExample");
    JavaSparkContext jsc = new JavaSparkContext(conf);

    // $example on$
    Vector vec = Vectors.dense(0.1, 0.15, 0.2, 0.3, 0.25); // a vector composed of the frequencies of events

    // compute the goodness of fit. If a second vector to test against is not supplied as a parameter,
    // the test runs against a uniform distribution.
    ChiSqTestResult goodnessOfFitTestResult = Statistics.chiSqTest(vec);
    // summary of the test including the p-value, degrees of freedom, test statistic, the method used,
    // and the null hypothesis.
    System.out.println(goodnessOfFitTestResult);

    // Create a dense matrix ((1.0, 2.0), (3.0, 4.0), (5.0, 6.0))
    Matrix mat = Matrices.dense(3, 2, new double[]{1.0, 3.0, 5.0, 2.0, 4.0, 6.0}); // a contingency matrix

    // conduct Pearson's independence test on the input contingency matrix
    ChiSqTestResult independenceTestResult = Statistics.chiSqTest(mat);
    // summary of the test including the p-value, degrees of freedom...
    System.out.println(independenceTestResult);

    LabeledPoint p1 = new LabeledPoint(1.0, Vectors.dense(1.0, 0.0, 3.0));
    LabeledPoint p2 = new LabeledPoint(1.0, Vectors.dense(1.0, 2.0, 0.0));
    LabeledPoint p3 = new LabeledPoint(-1.0, Vectors.dense(-1.0, 0.0, -0.5));
    JavaRDD<LabeledPoint> obs = jsc.parallelize(Arrays.asList(p1, p2, p3)); // an RDD of labeled points

    // The contingency table is constructed from the raw (feature, label) pairs and used to conduct
    // the independence test. Returns an array containing the ChiSquaredTestResult for every feature
    // against the label.
    ChiSqTestResult[] featureTestResults = Statistics.chiSqTest(obs.rdd());
    int i = 1;
    for (ChiSqTestResult result : featureTestResults) {
      System.out.println("Column " + i + ":");
      System.out.println(result); // summary of the test
      i++;
    }
    // $example off$

    jsc.stop();
  }
}
