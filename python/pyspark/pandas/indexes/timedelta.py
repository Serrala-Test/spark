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
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either F.express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
from typing import cast, no_type_check, Any
from functools import partial

import pandas as pd
from pandas.api.types import is_hashable

from pyspark import pandas as ps
from pyspark._globals import _NoValue
from pyspark.pandas.frame import DataFrame
from pyspark.pandas.indexes.base import Index
from pyspark.pandas.internal import SPARK_DEFAULT_INDEX_NAME
from pyspark.pandas.missing.indexes import MissingPandasLikeTimedeltaIndex
from pyspark.pandas.series import Series, first_series
from pyspark.pandas.utils import scol_for, verify_temp_column_name
from pyspark.sql import functions as F


class TimedeltaIndex(Index):
    """
    Immutable ndarray-like of timedelta64 data, represented internally as int64, and
    which can be boxed to timedelta objects.

    Parameters
    ----------
    data  : array-like (1-dimensional), optional
        Optional timedelta-like data to construct index with.
    unit : unit of the arg (D,h,m,s,ms,us,ns) denote the unit, optional
        Which is an integer/float number.
    freq : str or pandas offset object, optional
        One of pandas date offset strings or corresponding objects. The string
        'infer' can be passed in order to set the frequency of the index as the
        inferred frequency upon creation.
    copy  : bool
        Make a copy of input ndarray.
    name : object
        Name to be stored in the index.

    See Also
    --------
    Index : The base pandas Index type.

    Examples
    --------
    >>> from datetime import timedelta
    >>> ps.TimedeltaIndex([timedelta(1), timedelta(microseconds=2)])
    TimedeltaIndex(['1 days 00:00:00', '0 days 00:00:00.000002'], dtype='timedelta64[ns]', freq=None)

    From an Series:

    >>> s = ps.Series([timedelta(1), timedelta(microseconds=2)], index=[10, 20])
    >>> ps.TimedeltaIndex(s)
    TimedeltaIndex(['1 days 00:00:00', '0 days 00:00:00.000002'], dtype='timedelta64[ns]', freq=None)

    From an Index:

    >>> idx = ps.TimedeltaIndex([timedelta(1), timedelta(microseconds=2)])
    >>> ps.TimedeltaIndex(idx)
    TimedeltaIndex(['1 days 00:00:00', '0 days 00:00:00.000002'], dtype='timedelta64[ns]', freq=None)
    """

    @no_type_check
    def __new__(
        cls,
        data=None,
        unit=None,
        freq=_NoValue,
        closed=None,
        dtype=None,
        copy=False,
        name=None,
    ) -> "TimedeltaIndex":
        if not is_hashable(name):
            raise TypeError("Index.name must be a hashable type")

        if isinstance(data, (Series, Index)):
            if dtype is None:
                dtype = "timedelta64[ns]"
            return cast(TimedeltaIndex, Index(data, dtype=dtype, copy=copy, name=name))

        kwargs = dict(
            data=data,
            unit=unit,
            closed=closed,
            dtype=dtype,
            copy=copy,
            name=name,
        )
        if freq is not _NoValue:
            kwargs["freq"] = freq

        return cast(TimedeltaIndex, ps.from_pandas(pd.TimedeltaIndex(**kwargs)))

    def __getattr__(self, item: str) -> Any:
        if hasattr(MissingPandasLikeTimedeltaIndex, item):
            property_or_func = getattr(MissingPandasLikeTimedeltaIndex, item)
            if isinstance(property_or_func, property):
                return property_or_func.fget(self)
            else:
                return partial(property_or_func, self)

        raise AttributeError("'TimedeltaIndex' object has no attribute '{}'".format(item))

    @property
    def days(self) -> Index:
        """
        Number of days for each element.
        """
        sdf = self._internal.spark_frame.select(
            F.expr("date_part('DAY', %s)" % SPARK_DEFAULT_INDEX_NAME)
        )
        return Index(first_series(DataFrame(sdf)).rename(self.name))

    @property
    def seconds(self) -> Index:
        """
        Number of seconds (>= 0 and less than 1 day) for each element.
        """
        sdf = self._internal.spark_frame
        hour_scol_name = verify_temp_column_name(sdf, "__hour_column__")
        minute_scol_name = verify_temp_column_name(sdf, "__minute_column__")
        second_scol_name = verify_temp_column_name(sdf, "__second_column__")

        # Extract the hours part, minutes part, seconds part and its fractional part with microseconds
        sdf = sdf.select(
            F.expr("date_part('HOUR', %s)" % SPARK_DEFAULT_INDEX_NAME),
            F.expr("date_part('MINUTE', %s)" % SPARK_DEFAULT_INDEX_NAME),
            F.expr("date_part('SECOND', %s)" % SPARK_DEFAULT_INDEX_NAME),
        ).toDF(hour_scol_name, minute_scol_name, second_scol_name)

        # Transfer to microseconds
        sdf = sdf.select(
            scol_for(sdf, hour_scol_name) * 3600
            + scol_for(sdf, minute_scol_name) * 60
            + scol_for(sdf, second_scol_name)
        )

        return Index(first_series(DataFrame(sdf))).astype(int).rename(self.name)

    @property
    def microseconds(self) -> Index:
        """
        Number of microseconds (>= 0 and less than 1 second) for each element.
        """
        sdf = self._internal.spark_frame
        second_scol_name = verify_temp_column_name(sdf, "__second_column__")
        less_than_second_scol_name = verify_temp_column_name(sdf, "__less_than_second_column__")

        # Extract the seconds part and its fractional part with microseconds per element
        sdf = sdf.select(F.expr("date_part('SECOND', %s)" % SPARK_DEFAULT_INDEX_NAME)).toDF(
            second_scol_name
        )

        # Add a column to keep elements >= 0 and less than 1 second only
        sdf = sdf.withColumn(
            less_than_second_scol_name,
            F.when(scol_for(sdf, second_scol_name) <= 1, scol_for(sdf, second_scol_name)).otherwise(
                0
            ),
        )

        # Transfer the seconds to microseconds
        sdf = sdf.select(scol_for(sdf, less_than_second_scol_name) * 1000000)

        return Index(first_series(DataFrame(sdf))).astype(int).rename(self.name)
