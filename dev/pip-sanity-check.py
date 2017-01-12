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

import os
import sys

from pyspark.sql import SparkSession

if sys.version >= "3":
    from io import StringIO
else:
    from StringIO import StringIO

if __name__ == "__main__":
    gateway_already_started = "PYSPARK_GATEWAY_PORT" in os.environ
    if not gateway_already_started:
        _old_stdout = sys.stdout
        _old_stderr = sys.stderr
        # Verify stdout/stderr overwrite support for jupyter
        sys.stdout = new_stdout = StringIO()
        sys.stderr = new_stderr = StringIO()

    spark = SparkSession\
        .builder\
        .appName("PipSanityCheck")\
        .getOrCreate()
    print("Spark context created")
    sc = spark.sparkContext
    rdd = sc.parallelize(range(100), 10)
    value = rdd.reduce(lambda x, y: x + y)

    if (value != 4950):
        print("Value {0} did not match expected value.".format(value), file=sys.stderr)
        sys.exit(-1)

    if not gateway_already_started:
        try:
            rdd2 = rdd.map(lambda x: str(x).startsWith("expected error"))
            rdd2.collect()
        except:
            pass
        sys.stdout = _old_stdout
        sys.stderr = _old_stderr
        logs = new_stderr.getvalue() + new_stdout.getvalue()

        if logs.find("'str' object has no attribute 'startsWith'") == -1:
            print("Failed to find helpful error message, redirect failed?")
            sys.exit(-1)
    print("Successfully ran pip sanity check")

    spark.stop()
