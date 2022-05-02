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
A wrapper for ResampledData to behave similar to pandas Resampler.
"""
from abc import ABCMeta
from distutils.version import LooseVersion
import re
from functools import partial
from typing import (
    Any,
    Generic,
    List,
    Optional,
)

import numpy as np

import pandas as pd

if LooseVersion(pd.__version__) >= LooseVersion("1.3.0"):
    from pandas.core.common import _builtin_table  # type: ignore[attr-defined]
else:
    from pandas.core.base import SelectionMixin

    _builtin_table = SelectionMixin._builtin_table  # type: ignore[attr-defined]

from pyspark import SparkContext
from pyspark.sql import Column, functions as F
from pyspark.sql.types import (
    NumericType,
    StructField,
    TimestampType,
)

from pyspark import pandas as ps  # For running doctests and reference resolution in PyCharm.
from pyspark.pandas._typing import FrameLike
from pyspark.pandas.frame import DataFrame
from pyspark.pandas.internal import (
    InternalField,
    InternalFrame,
    SPARK_DEFAULT_INDEX_NAME,
)
from pyspark.pandas.missing.resample import (
    MissingPandasLikeDataFrameResampler,
    MissingPandasLikeSeriesResampler,
)
from pyspark.pandas.series import Series, first_series
from pyspark.pandas.utils import (
    scol_for,
    verify_temp_column_name,
)


class Resampler(Generic[FrameLike], metaclass=ABCMeta):
    """
    Class for resampling datetimelike data, a groupby-like operation.

    It's easiest to use obj.resample(...) to use Resampler.

    Parameters
    ----------
    psdf : DataFrame

    Returns
    -------
    a Resampler of the appropriate type

    Notes
    -----
    After resampling, see aggregate, apply, and transform functions.
    """

    def __init__(
        self,
        psdf: DataFrame,
        resamplekey: Optional[Series],
        rule: str,
        closed: Optional[str] = None,
        label: Optional[str] = None,
        agg_columns: List[Series] = [],
    ):
        self._psdf = psdf
        self._resamplekey = resamplekey

        parsed = re.findall(r"^([0-9]+)?([A-Za-z]+)$", rule)
        if len(parsed) != 1:
            raise ValueError("Unsupported freq {}".format(rule))

        offset_str, unit_str = parsed[0]
        self._freq_offset = 1
        if offset_str != "":
            freq_offset = int(offset_str)
            if not freq_offset > 0:
                raise ValueError("invalid rule: '{}'".format(rule))
            self._freq_offset = freq_offset

        unit_mapping = {
            "Y": "Y",
            "A": "Y",
            "M": "M",
            "D": "D",
            "H": "H",
            "T": "T",
            "MIN": "T",
            "S": "S",
        }
        if unit_str.upper() not in unit_mapping:
            raise ValueError("Unknown freq unit {}".format(unit_str))
        self._freq_unit = unit_mapping[unit_str.upper()]
        self._rule = rule

        if closed is not None and closed not in ["left", "right"]:
            raise ValueError("invalid closed: '{}'".format(closed))
        if closed is None:
            if self._freq_unit in ["Y", "M"]:
                self._closed = "right"
            else:
                self._closed = "left"
        else:
            self._closed = closed

        if label is not None and label not in ["left", "right"]:
            raise ValueError("invalid label: '{}'".format(label))
        if label is None:
            if self._freq_unit in ["Y", "M"]:
                self._label = "right"
            else:
                self._label = "left"
        else:
            self._label = label

        self._agg_columns = agg_columns

    @property
    def _resamplekey_scol(self) -> Column:
        if self._resamplekey is None:
            return self._psdf.index.spark.column
        else:
            return self._resamplekey.spark.column

    @property
    def _agg_columns_scols(self) -> List[Column]:
        return [s.spark.column for s in self._agg_columns]

    def _downsample(self, f: str) -> DataFrame:
        """
        Downsample the defined function.

        Parameters
        ----------
        how : string / mapped function
        **kwargs : kw args passed to how function
        """

        # a simple example to illustrate the computation:
        #   dates = [
        #         datetime.datetime(2012, 1, 2),
        #         datetime.datetime(2012, 5, 3),
        #         datetime.datetime(2022, 5, 3),
        #   ]
        #   index = pd.DatetimeIndex(dates)
        #   pdf = pd.DataFrame(np.array([1,2,3]), index=index, columns=['A'])
        #   pdf.resample('3Y').max()
        #                 A
        #   2012-12-31  2.0
        #   2015-12-31  NaN
        #   2018-12-31  NaN
        #   2021-12-31  NaN
        #   2024-12-31  3.0
        #
        # in this case:
        # 1, obtain one origin point to bin all timestamps, we can get one (2009-12-31)
        # from the minimum timestamp (2012-01-02);
        # 2, the default intervals for 'Y' are right-closed, so intervals are:
        # (2009-12-31, 2012-12-31], (2012-12-31, 2015-12-31], (2015-12-31, 2018-12-31], ...
        # 3, bin all timestamps, for example, 2022-05-03 belong to interval
        # (2021-12-31, 2024-12-31], since the default label is 'right', label it with the right
        # edge 2024-12-31;
        # 4, some intervals maybe too large for this down sampling, so we need to pad the results
        # to avoid missing some results, like: 2015-12-31, 2018-12-31 and 2021-12-31;
        # 5, union the binned dataframe and padded dataframe, and apply aggregation 'max' to get
        # the final results;

        # one action to obtain the range, in the future we may cache it in the index.
        ts_min, ts_max = (
            self._psdf._internal.spark_frame.select(
                F.min(self._resamplekey_scol), F.max(self._resamplekey_scol)
            )
            .toPandas()
            .iloc[0]
        )

        # the logic to obtain an origin point to bin the timestamps is too complex to follow,
        # here just use Pandas' resample on a 1-length series to get it.
        ts_origin = (
            pd.Series([0], index=[ts_min])
            .resample(self._rule, closed=self._closed, label="left")
            .sum()
            .index[0]
        )
        assert ts_origin <= ts_min

        sql_utils = SparkContext._active_spark_context._jvm.PythonSQLUtils
        bin_col_name = "__tmp_resample_bin_col__"
        bin_col_label = verify_temp_column_name(self._psdf, bin_col_name)
        bin_col_field = InternalField(
            dtype=np.dtype("datetime64[ns]"),
            struct_field=StructField(bin_col_name, TimestampType(), True),
        )
        bin_scol = Column(
            sql_utils.binTimeStamp(
                F.lit(ts_origin)._jc,
                self._freq_offset,
                self._freq_unit,
                self._closed == "left",
                self._label == "left",
                self._resamplekey_scol._jc,
            )
        )

        agg_columns = [
            psser for psser in self._agg_columns if (isinstance(psser.spark.data_type, NumericType))
        ]
        assert len(agg_columns) > 0

        # in the binning side, label the timestamps according to the origin and the freq(rule)
        bin_sdf = self._psdf._internal.spark_frame.select(
            F.col(SPARK_DEFAULT_INDEX_NAME),
            bin_scol.alias(bin_col_name),
            *[psser.spark.column for psser in agg_columns],
        ).where(~F.isnull(F.col(bin_col_name)))

        # in the padding side, insert necessary points
        # again, directly apply Pandas' resample on a 2-length series to obtain the indices
        pad_sdf = (
            ps.from_pandas(
                pd.Series([0, 0], index=[ts_min, ts_max])
                .resample(self._rule, closed=self._closed, label=self._label)
                .sum()
                .index
            )
            ._internal.spark_frame.select(F.col(SPARK_DEFAULT_INDEX_NAME).alias(bin_col_name))
            .where((ts_min <= F.col(bin_col_name)) & (F.col(bin_col_name) <= ts_max))
        )

        # union the above two spark dataframes.
        # something goes wrong and mess the computation logic in the padding side,
        # which may result in wrong results.
        # the conversion to/from rdd here is to work around via disabling optimizer.
        sdf = bin_sdf.unionByName(pad_sdf, allowMissingColumns=True)
        spark = sdf.sparkSession
        sdf = spark.createDataFrame(sdf.rdd, sdf.schema).where(~F.isnull(F.col(bin_col_name)))

        internal = InternalFrame(
            spark_frame=sdf,
            index_spark_columns=[scol_for(sdf, SPARK_DEFAULT_INDEX_NAME)],
            data_spark_columns=[F.col(bin_col_name)]
            + [scol_for(sdf, psser._internal.data_spark_column_names[0]) for psser in agg_columns],
            column_labels=[bin_col_label] + [psser._column_label for psser in agg_columns],
            data_fields=[bin_col_field]
            + [psser._internal.data_fields[0].copy(nullable=True) for psser in agg_columns],
            column_label_names=self._psdf._internal.column_label_names,
        )
        psdf: DataFrame = DataFrame(internal)

        groupby = psdf.groupby(psdf._psser_for(bin_col_label), dropna=False)
        downsampled = getattr(groupby, f)()
        downsampled.index.name = None

        return downsampled


class DataFrameResampler(Resampler[DataFrame]):
    def __init__(
        self,
        psdf: DataFrame,
        resamplekey: Optional[Series],
        rule: str,
        closed: Optional[str] = None,
        label: Optional[str] = None,
        agg_columns: List[Series] = [],
    ):
        super().__init__(
            psdf=psdf,
            resamplekey=resamplekey,
            rule=rule,
            closed=closed,
            label=label,
            agg_columns=agg_columns,
        )

    def __getattr__(self, item: str) -> Any:
        if hasattr(MissingPandasLikeDataFrameResampler, item):
            property_or_func = getattr(MissingPandasLikeDataFrameResampler, item)
            if isinstance(property_or_func, property):
                return property_or_func.fget(self)
            else:
                return partial(property_or_func, self)

    def min(self) -> DataFrame:
        return self._downsample("min")

    def max(self) -> DataFrame:
        return self._downsample("max")

    def sum(self) -> DataFrame:
        return self._downsample("sum").fillna(0.0)

    def mean(self) -> DataFrame:
        return self._downsample("mean")

    def std(self) -> DataFrame:
        return self._downsample("std")

    def var(self) -> DataFrame:
        return self._downsample("var")


class SeriesResampler(Resampler[Series]):
    def __init__(
        self,
        psdf: DataFrame,
        resamplekey: Optional[Series],
        rule: str,
        closed: Optional[str] = None,
        label: Optional[str] = None,
        agg_columns: List[Series] = [],
    ):
        super().__init__(
            psdf=psdf,
            resamplekey=resamplekey,
            rule=rule,
            closed=closed,
            label=label,
            agg_columns=agg_columns,
        )

    def __getattr__(self, item: str) -> Any:
        if hasattr(MissingPandasLikeSeriesResampler, item):
            property_or_func = getattr(MissingPandasLikeSeriesResampler, item)
            if isinstance(property_or_func, property):
                return property_or_func.fget(self)
            else:
                return partial(property_or_func, self)

    def min(self) -> Series:
        return first_series(self._downsample("min"))

    def max(self) -> Series:
        return first_series(self._downsample("max"))

    def sum(self) -> Series:
        return first_series(self._downsample("sum").fillna(0.0))

    def mean(self) -> Series:
        return first_series(self._downsample("mean"))

    def std(self) -> Series:
        return first_series(self._downsample("std"))

    def var(self) -> Series:
        return first_series(self._downsample("var"))
