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

from io import StringIO
import unittest

from pyspark.testing.connectutils import (
    PlanOnlyTestFixture,
    should_test_connect,
    connect_requirement_message,
)
from pyspark.testing.utils import PySparkErrorTestUtils
from pyspark.sql.connect.shell.progress import Progress


@unittest.skipIf(not should_test_connect, connect_requirement_message)
class ProgressBarTest(unittest.TestCase, PySparkErrorTestUtils):
    def test_simple_progress(self):
        buffer = StringIO()
        p = Progress(output=buffer, enabled=True)
        p.update_ticks(100, 50, 999)
        val = buffer.getvalue()
        self.assertIn("50.00%", val, "Current progress is 50%")
        self.assertIn("****", val, "Should use the default char to print.")
        self.assertIn("Scanned 999.0 B", val, "Should contain the bytes scanned metric.")
        self.assertFalse(val.endswith("\r"), "Line should not be empty")
        p.finish()
        val = buffer.getvalue()
        self.assertTrue(val.endswith("\r"), "Line should be empty")

    def test_configure_char(self):
        buffer = StringIO()
        p = Progress(char="+", output=buffer, enabled=True)
        p.update_ticks(100, 50, 999)
        val = buffer.getvalue()
        self.assertIn("++++++", val, "Updating the char works.")

    def test_disabled_does_not_print(self):
        buffer = StringIO()
        p = Progress(char="+", output=buffer, enabled=False)
        p.update_ticks(100, 50, 999)
        p.update_ticks(100, 51, 999)
        val = buffer.getvalue()
        self.assertEqual(0, len(val), "If the printing is disabled, don't print.")

    def test_finish_progress(self):
        buffer = StringIO()
        p = Progress(char="+", output=buffer, enabled=True)
        p.update_ticks(100, 50, 999)
        p.finish()
        self.assertTrue(buffer.getvalue().endswith("\r"), "Last line should be empty")


if __name__ == "__main__":
    from pyspark.sql.tests.connect.shell.test_progress import *  # noqa: F401

    try:
        import xmlrunner  # type: ignore

        testRunner = xmlrunner.XMLTestRunner(output="target/test-reports", verbosity=2)
    except ImportError:
        testRunner = None
    unittest.main(testRunner=testRunner, verbosity=2)
