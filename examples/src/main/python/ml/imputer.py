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

# $example on$
from pyspark.ml.feature import Imputer
# $example off$
from pyspark.sql import SparkSession

if __name__ == "__main__":
    spark = SparkSession\
        .builder\
        .appName("imputer example")\
        .getOrCreate()

    # $example on$
    dataFrame = spark.createDataFrame([
        (1.0, float("nan")),
        (2.0, float("nan")),
        (float("nan"), 3.0),
        (4.0, 4.0),
        (5.0, 5.0)
    ], ["a", "b"])

    imputer = Imputer(inputCols=["a", "b"], outputCols=["out_a", "out_b"])

    imputerModel = imputer.fit(dataFrame)

    imputedData = imputerModel.transform(dataFrame)
    imputedData.select("a", "b", "out_a", "out_b").show()
    # $example off$

    spark.stop()
