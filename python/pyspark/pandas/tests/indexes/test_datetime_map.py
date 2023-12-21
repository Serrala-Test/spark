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

import datetime

import pandas as pd

import pyspark.pandas as ps
from pyspark.testing.pandasutils import PandasOnSparkTestCase
from pyspark.pandas.tests.indexes.test_datetime import DatetimeIndexTestingFuncMixin


class DatetimeIndexMapMixin(DatetimeIndexTestingFuncMixin):
    def test_map(self):
        for psidx, pidx in self.idx_pairs:
            self.assert_eq(psidx.map(lambda x: x.normalize()), pidx.map(lambda x: x.normalize()))
            self.assert_eq(
                psidx.map(lambda x: x.strftime("%B %d, %Y, %r")),
                pidx.map(lambda x: x.strftime("%B %d, %Y, %r")),
            )

        pidx = pd.date_range(start="2020-08-08", end="2020-08-10")
        psidx = ps.from_pandas(pidx)
        mapper_dict = {
            datetime.datetime(2020, 8, 8): datetime.datetime(2021, 8, 8),
            datetime.datetime(2020, 8, 9): datetime.datetime(2021, 8, 9),
        }
        self.assert_eq(psidx.map(mapper_dict), pidx.map(mapper_dict))

        mapper_pser = pd.Series([1, 2, 3], index=pidx)
        self.assert_eq(psidx.map(mapper_pser), pidx.map(mapper_pser))


class DatetimeIndexMapTests(
    DatetimeIndexMapMixin,
    PandasOnSparkTestCase,
):
    pass


if __name__ == "__main__":
    import unittest
    from pyspark.pandas.tests.indexes.test_datetime_map import *  # noqa: F401

    try:
        import xmlrunner

        testRunner = xmlrunner.XMLTestRunner(output="target/test-reports", verbosity=2)
    except ImportError:
        testRunner = None
    unittest.main(testRunner=testRunner, verbosity=2)
