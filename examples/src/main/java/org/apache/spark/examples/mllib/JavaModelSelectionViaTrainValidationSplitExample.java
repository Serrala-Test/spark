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

//$example on$
import org.apache.spark.SparkConf;
import org.apache.spark.SparkContext;
import org.apache.spark.ml.evaluation.RegressionEvaluator;
import org.apache.spark.ml.param.ParamMap;
import org.apache.spark.ml.regression.LinearRegression;
import org.apache.spark.ml.tuning.ParamGridBuilder;
import org.apache.spark.ml.tuning.TrainValidationSplit;
import org.apache.spark.ml.tuning.TrainValidationSplitModel;
import org.apache.spark.sql.DataFrame;
import org.apache.spark.sql.SQLContext;
//$example off$

/**
 * Java example for Model Selection via Train Validation Split.
 */
public class JavaModelSelectionViaTrainValidationSplitExample {
  public static void main(String[] args) {
    SparkConf conf = new SparkConf()
        .setAppName("JavaModelSelectionViaTrainValidationSplitExample");
    SparkContext sc = new SparkContext(conf);
    SQLContext jsql = new SQLContext(sc);

    // $example on$
    DataFrame data = jsql.read().format("libsvm")
        .load("data/mllib/sample_libsvm_data.txt");

    // Prepare training and test data.
    DataFrame[] splits = data.randomSplit(new double[] { 0.9, 0.1 }, 12345);
    DataFrame training = splits[0];
    DataFrame test = splits[1];

    LinearRegression lr = new LinearRegression();

    // We use a ParamGridBuilder to construct a grid of parameters to search
    // over.
    // TrainValidationSplit will try all combinations of values and determine
    // best model using
    // the evaluator.
    ParamMap[] paramGrid = new ParamGridBuilder()
        .addGrid(lr.regParam(), new double[] { 0.1, 0.01 })
        .addGrid(lr.fitIntercept())
        .addGrid(lr.elasticNetParam(), new double[] { 0.0, 0.5, 1.0 }).build();

    // In this case the estimator is simply the linear regression.
    // A TrainValidationSplit requires an Estimator, a set of Estimator
    // ParamMaps, and an Evaluator.
    TrainValidationSplit trainValidationSplit = new TrainValidationSplit()
        .setEstimator(lr).setEvaluator(new RegressionEvaluator())
        // 80% for training and the remaining 20% for validation
        .setEstimatorParamMaps(paramGrid).setTrainRatio(0.8);

    // Run train validation split, and choose the best set of parameters.
    TrainValidationSplitModel model = trainValidationSplit.fit(training);

    // Make predictions on test data. model is the model with combination of
    // parameters
    // that performed best.
    model.transform(test).select("features", "label", "prediction").show();
    // $example off$

    sc.stop();
  }
}
