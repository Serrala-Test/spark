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
import numpy as np
# $example on$
from pyspark.mllib.stat import Statistics
# $example off$

if __name__ == "__main__":
    sc = SparkContext(appName="SummaryStatisticsExample")  # SparkContext

    # $example on$
    v1 = np.array([1.0, 2.0, 3.0])
    v2 = np.array([10.0, 20.0, 30.0])
    v3 = np.array([100.0, 200.0, 300.0])
    mat = sc.parallelize([v1, v2, v3])  # an RDD of Vectors

    # Compute column summary statistics.
    summary = Statistics.colStats(mat)
    print(summary.mean())  # a dense vector containing the mean value for each column
    print(summary.variance())  # column-wise variance
    print(summary.numNonzeros())  # number of nonzeros in each column
    # $example off$

    sc.stop()
