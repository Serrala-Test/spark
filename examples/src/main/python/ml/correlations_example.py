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
from pyspark.mllib.stat import Statistics
# $example off$

if __name__ == "__main__":
    # $example on$
    sc = SparkContext(appName="CorrelationsExample") # SparkContext

    seriesX = sc.parallelize([1.0, 2.0, 3.0, 3.0, 5.0]) # a series
    seriesY = sc.parallelize([11.0, 22.0, 33.0, 33.0, 555.0]) # must have the same number of partitions and cardinality as seriesX

    # Compute the correlation using Pearson's method. Enter "spearman" for Spearman's method. If a
    # method is not specified, Pearson's method will be used by default.
    print(Statistics.corr(seriesX, seriesY, method="pearson"))

    v1 = np.array([1.0, 10.0, 100.0])
    v2 = np.array([2.0, 20.0, 200.0])
    v3 = np.array([5.0, 33.0, 366.0])
    data = sc.parallelize([v1, v2, v3]) # an RDD of Vectors

    # calculate the correlation matrix using Pearson's method. Use "spearman" for Spearman's method.
    # If a method is not specified, Pearson's method will be used by default.
    print(Statistics.corr(data, method="pearson"))

    # $example off$

    sc.stop()