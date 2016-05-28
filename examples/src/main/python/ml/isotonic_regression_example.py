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

"""
Isotonic Regression Example.
"""
from __future__ import print_function

from pyspark.sql import SparkSession
from pyspark.sql.types import StructType, StructField, DoubleType

# $example on$
from pyspark.ml.regression import IsotonicRegression, IsotonicRegressionModel
# $example off$

if __name__ == "__main__":

    spark = SparkSession\
        .builder\
        .appName("PythonIsotonicRegressionExample")\
        .getOrCreate()

    # $example on$
    dataReader = spark.read
    dataReader.schema(
        StructType([StructField("label", DoubleType()),
                    StructField("features", DoubleType())]))

    data = dataReader.format("csv").load("data/mllib/sample_isotonic_regression_data.txt")
    # Split data into training (60%) and test (40%) sets.
    training, test = data.randomSplit([0.6, 0.4], 11)
    model = IsotonicRegression().fit(training)
    result = model.transform(test)
    result.show
    # $example off$

    spark.stop
