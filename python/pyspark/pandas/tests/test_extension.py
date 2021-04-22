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

import contextlib

import numpy as np
import pandas as pd

from pyspark import pandas as ps
from pyspark.pandas.testing.utils import assert_produces_warning, ReusedSQLTestCase
from pyspark.pandas.extensions import (
    register_dataframe_accessor,
    register_series_accessor,
    register_index_accessor,
)


@contextlib.contextmanager
def ensure_removed(obj, attr):
    """
    Ensure attribute attached to 'obj' during testing is removed in the end
    """
    try:
        yield

    finally:
        try:
            delattr(obj, attr)
        except AttributeError:
            pass


class CustomAccessor:
    def __init__(self, obj):
        self.obj = obj
        self.item = "item"

    @property
    def prop(self):
        return self.item

    def method(self):
        return self.item

    def check_length(self, col=None):
        if type(self.obj) == ps.DataFrame or col is not None:
            return len(self.obj[col])
        else:
            try:
                return len(self.obj)
            except Exception as e:
                raise ValueError(str(e))


class ExtensionTest(ReusedSQLTestCase):
    @property
    def pdf(self):
        return pd.DataFrame(
            {"a": [1, 2, 3, 4, 5, 6, 7, 8, 9], "b": [4, 5, 6, 3, 2, 1, 0, 0, 0]},
            index=np.random.rand(9),
        )

    @property
    def kdf(self):
        return ps.from_pandas(self.pdf)

    @property
    def accessor(self):
        return CustomAccessor(self.kdf)

    def test_setup(self):
        self.assertEqual("item", self.accessor.item)

    def test_dataframe_register(self):
        with ensure_removed(ps.DataFrame, "test"):
            register_dataframe_accessor("test")(CustomAccessor)
            assert self.kdf.test.prop == "item"
            assert self.kdf.test.method() == "item"
            assert len(self.kdf["a"]) == self.kdf.test.check_length("a")

    def test_series_register(self):
        with ensure_removed(ps.Series, "test"):
            register_series_accessor("test")(CustomAccessor)
            assert self.kdf.a.test.prop == "item"
            assert self.kdf.a.test.method() == "item"
            assert self.kdf.a.test.check_length() == len(self.kdf["a"])

    def test_index_register(self):
        with ensure_removed(ps.Index, "test"):
            register_index_accessor("test")(CustomAccessor)
            assert self.kdf.index.test.prop == "item"
            assert self.kdf.index.test.method() == "item"
            assert self.kdf.index.test.check_length() == self.kdf.index.size

    def test_accessor_works(self):
        register_series_accessor("test")(CustomAccessor)

        s = ps.Series([1, 2])
        assert s.test.obj is s
        assert s.test.prop == "item"
        assert s.test.method() == "item"

    def test_overwrite_warns(self):
        mean = ps.Series.mean
        try:
            with assert_produces_warning(UserWarning, raise_on_extra_warnings=False) as w:
                register_series_accessor("mean")(CustomAccessor)
                s = ps.Series([1, 2])
                assert s.mean.prop == "item"
            msg = str(w[0].message)
            assert "mean" in msg
            assert "CustomAccessor" in msg
            assert "Series" in msg
        finally:
            ps.Series.mean = mean

    def test_raises_attr_error(self):
        with ensure_removed(ps.Series, "bad"):

            class Bad:
                def __init__(self, data):
                    raise AttributeError("whoops")

            with self.assertRaises(AttributeError):
                ps.Series([1, 2], dtype=object).bad


if __name__ == "__main__":
    import unittest
    from pyspark.pandas.tests.test_extension import *  # noqa: F401

    try:
        import xmlrunner  # type: ignore[import]
        testRunner = xmlrunner.XMLTestRunner(output='target/test-reports', verbosity=2)
    except ImportError:
        testRunner = None
    unittest.main(testRunner=testRunner, verbosity=2)
