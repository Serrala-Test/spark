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
import unittest

from pyspark.pandas.tests.test_frame_spark import SparkFrameMethodsTestsMixin
from pyspark.testing.connectutils import ReusedConnectTestCase
from pyspark.testing.pandasutils import PandasOnSparkTestUtils, TestUtils


class SparkFrameMethodsParityTests(
    SparkFrameMethodsTestsMixin, TestUtils, PandasOnSparkTestUtils, ReusedConnectTestCase
):
    @unittest.skip("Test depends on checkpoint which is not supported from Spark Connect.")
    def test_checkpoint(self):
        super().test_checkpoint()

    @unittest.skip(
        "Test depends on RDD, and cannot use SQL expression due to Catalyst optimization"
    )
    def test_coalesce(self):
        super().test_coalesce()

    @unittest.skip("Test depends on localCheckpoint which is not supported from Spark Connect.")
    def test_local_checkpoint(self):
        super().test_local_checkpoint()

    @unittest.skip(
        "Test depends on RDD, and cannot use SQL expression due to Catalyst optimization"
    )
    def test_repartition(self):
        super().test_repartition()


if __name__ == "__main__":
    from pyspark.pandas.tests.connect.test_parity_frame_spark import *  # noqa: F401

    try:
        import xmlrunner  # type: ignore[import]

        testRunner = xmlrunner.XMLTestRunner(output="target/test-reports", verbosity=2)
    except ImportError:
        testRunner = None
    unittest.main(testRunner=testRunner, verbosity=2)
