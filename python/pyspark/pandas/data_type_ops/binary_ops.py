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

from typing import TYPE_CHECKING, Union

from pandas.api.types import CategoricalDtype

from pyspark.pandas.base import column_op, IndexOpsMixin
from pyspark.pandas.data_type_ops.base import (
    DataTypeOps,
    _as_bool_type,
    _as_categorical_type,
    _as_other_type,
    _as_string_type,
)
from pyspark.pandas.typedef import Dtype, pandas_on_spark_type
from pyspark.sql import functions as F
from pyspark.sql.types import BinaryType, BooleanType, StringType

if TYPE_CHECKING:
    from pyspark.pandas.indexes import Index  # noqa: F401 (SPARK-34943)
    from pyspark.pandas.series import Series  # noqa: F401 (SPARK-34943)


class BinaryOps(DataTypeOps):
    """
    The class for binary operations of pandas-on-Spark objects with BinaryType.
    """

    @property
    def pretty_name(self) -> str:
        return "binaries"

    def add(self, left, right) -> Union["Series", "Index"]:
        if isinstance(right, IndexOpsMixin) and isinstance(right.spark.data_type, BinaryType):
            return column_op(F.concat)(left, right)
        elif isinstance(right, bytes):
            return column_op(F.concat)(left, F.lit(right))
        else:
            raise TypeError(
                "Concatenation can not be applied to %s and the given type." % self.pretty_name
            )

    def radd(self, left, right) -> Union["Series", "Index"]:
        if isinstance(right, bytes):
            return left._with_new_scol(F.concat(F.lit(right), left.spark.column))
        else:
            raise TypeError(
                "Concatenation can not be applied to %s and the given type." % self.pretty_name
            )

    def astype(
        self, index_ops: Union["Index", "Series"], dtype: Union[str, type, Dtype]
    ) -> Union["Index", "Series"]:
        dtype, spark_type = pandas_on_spark_type(dtype)

        if isinstance(dtype, CategoricalDtype):
            return _as_categorical_type(index_ops, dtype, spark_type)
        elif isinstance(spark_type, BooleanType):
            return _as_bool_type(index_ops, dtype)
        elif isinstance(spark_type, StringType):
            return _as_string_type(index_ops, dtype)
        else:
            return _as_other_type(index_ops, dtype, spark_type)
