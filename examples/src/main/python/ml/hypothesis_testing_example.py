#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

from __future__ import print_function

from pyspark import SparkContext
from pyspark.sql import SQLContext
import numpy as np
from pyspark.mllib.linalg import Vectors
# $example on$
from pyspark import SparkContext
from pyspark.mllib.linalg import Vectors, Matrices
from pyspark.mllib.regresssion import LabeledPoint
from pyspark.mllib.stat import Statistics
# $example off$

if __name__ == "__main__":
    sc = SparkContext(appName="HypothesisTestingExample") # SparkContext
    sqlContext = SQLContext(sc)

    # $example on$

    # @note: todo

    vec = Vectors.dense(...) # a vector composed of the frequencies of events

    # compute the goodness of fit. If a second vector to test against is not supplied as a parameter,
    # the test runs against a uniform distribution.
    goodnessOfFitTestResult = Statistics.chiSqTest(vec)
    print(goodnessOfFitTestResult) # summary of the test including the p-value, degrees of freedom,
    # test statistic, the method used, and the null hypothesis.

    mat = Matrices.dense(...) # a contingency matrix

    # conduct Pearson's independence test on the input contingency matrix
    independenceTestResult = Statistics.chiSqTest(mat)
    print(independenceTestResult)  # summary of the test including the p-value, degrees of freedom...

    obs = sc.parallelize(...)  # LabeledPoint(feature, label) .

    # The contingency table is constructed from an RDD of LabeledPoint and used to conduct
    # the independence test. Returns an array containing the ChiSquaredTestResult for every feature
    # against the label.
    featureTestResults = Statistics.chiSqTest(obs)

    for i, result in enumerate(featureTestResults):
        print("Column $d:" % (i + 1))
        print(result)

    # $example off$

    sc.stop()