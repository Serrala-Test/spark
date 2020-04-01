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

import functools
import sys
import warnings

from pyspark import since
from pyspark.rdd import PythonEvalType
from pyspark.sql.pandas.typehints import infer_eval_type
from pyspark.sql.pandas.utils import require_minimum_pandas_version, require_minimum_pyarrow_version
from pyspark.sql.types import DataType
from pyspark.sql.udf import _create_udf
from pyspark.util import _get_argspec


class PandasUDFType(object):
    """Pandas UDF Types. See :meth:`pyspark.sql.functions.pandas_udf`.
    """
    SCALAR = PythonEvalType.SQL_SCALAR_PANDAS_UDF

    SCALAR_ITER = PythonEvalType.SQL_SCALAR_PANDAS_ITER_UDF

    GROUPED_MAP = PythonEvalType.SQL_GROUPED_MAP_PANDAS_UDF

    GROUPED_AGG = PythonEvalType.SQL_GROUPED_AGG_PANDAS_UDF


@since(2.3)
def pandas_udf(f=None, returnType=None, functionType=None):
    """
    Creates a pandas user defined function (a.k.a. vectorized user defined function).

    Pandas UDFs are user defined functions that are executed by Spark using Arrow to transfer
    data and Pandas to work with the data, which allows vectorized operations. A Pandas UDF
    is defined using the `pandas_udf` as a decorator or to wrap the function, and no
    additional configuration is required. A Pandas UDF behaves as a regular PySpark function
    API in general.

    :param f: user-defined function. A python function if used as a standalone function
    :param returnType: the return type of the user-defined function. The value can be either a
        :class:`pyspark.sql.types.DataType` object or a DDL-formatted type string.
    :param functionType: an enum value in :class:`pyspark.sql.functions.PandasUDFType`.
        Default: SCALAR.

        .. note:: This parameter exists for compatibility. Using Python type hints is encouraged.

    In order to use this API, customarily the below are imported:

    >>> import pandas as pd
    >>> from pyspark.sql.functions import pandas_udf

    From Spark 3.0 with Python 3.6+, `Python type hints <https://www.python.org/dev/peps/pep-0484>`_
    detect the function types as below:

    >>> @pandas_udf(IntegerType())
    ... def slen(s: pd.Series) -> pd.Series:
    ...     return s.str.len()

    Prior to Spark 3.0, the pandas UDF used `functionType` to decide the execution type as below:

    >>> from pyspark.sql.functions import PandasUDFType
    >>> from pyspark.sql.types import IntegerType
    >>> @pandas_udf(IntegerType(), PandasUDFType.SCALAR)
    ... def slen(s):
    ...     return s.str.len()

    It is preferred to specify type hints for the pandas UDF instead of specifying pandas UDF
    type via `functionType` which will be deprecated in the future releases.

    Note that the type hint should use `pandas.Series` in all cases but there is one variant
    that `pandas.DataFrame` should be used for its input or output type hint instead when the input
    or output column is of :class:`pyspark.sql.types.StructType`. The following example shows
    a Pandas UDF which takes long column, string column and struct column, and outputs a struct
    column. It requires the function to specify the type hints of `pandas.Series` and
    `pandas.DataFrame` as below:

    >>> @pandas_udf("col1 string, col2 long")
    >>> def func(s1: pd.Series, s2: pd.Series, s3: pd.DataFrame) -> pd.DataFrame:
    ...     s3['col2'] = s1 + s2.str.len()
    ...     return s3
    ...
    >>> # Create a Spark DataFrame that has three columns including a sturct column.
    ... df = spark.createDataFrame(
    ...     [[1, "a string", ("a nested string",)]],
    ...     "long_col long, string_col string, struct_col struct<col1:string>")
    >>> df.printSchema()
    root
    |-- long_column: long (nullable = true)
    |-- string_column: string (nullable = true)
    |-- struct_column: struct (nullable = true)
    |    |-- col1: string (nullable = true)
    >>> df.select(func("long_col", "string_col", "struct_col")).printSchema()
    |-- func(long_col, string_col, struct_col): struct (nullable = true)
    |    |-- col1: string (nullable = true)
    |    |-- col2: long (nullable = true)

    In the following sections, it describes the cominations of the supported type hints. For
    simplicity, `pandas.DataFrame` variant is omitted.

    * Series to Series
        `pandas.Series`, ... -> `pandas.Series`

        The function takes one or more `pandas.Series` and outputs one `pandas.Series`.
        The output of the function should always be of the same length as the input.

        >>> @pandas_udf("string")
        ... def to_upper(s: pd.Series) -> pd.Series:
        ...     return s.str.upper()
        ...
        >>> df = spark.createDataFrame([("John Doe",)], ("name",))
        >>> df.select(to_upper("name")).show()
        +--------------+
        |to_upper(name)|
        +--------------+
        |      JOHN DOE|
        +--------------+

        >>> @pandas_udf("first string, last string")
        ... def split_expand(s: pd.Series) -> pd.DataFrame:
        ...     return s.str.split(expand=True)
        ...
        >>> df = spark.createDataFrame([("John Doe",)], ("name",))
        >>> df.select(split_expand("name")).show()
        +------------------+
        |split_expand(name)|
        +------------------+
        |       [John, Doe]|
        +------------------+

        .. note:: The length of the input is not that of the whole input column, but is the
            length of an internal batch used for each call to the function.

    * Iterator of Series to Iterator of Series
        `Iterator[pandas.Series]` -> `Iterator[pandas.Series]`

        The function takes an iterator of `pandas.Series` and outputs an iterator of
        `pandas.Series`. In this case, the created pandas UDF instance requires one input
        column when this is called as a PySpark column. The output of each series from
        the function should always be of the same length as the input.

        It is useful when the UDF execution
        requires initializing some states although internally it works identically as
        Series to Series case. The pseudocode below illustrates the example.

        .. highlight:: python
        .. code-block:: python

            @pandas_udf("long")
            def calculate(iterator: Iterator[pd.Series]) -> Iterator[pd.Series]:
                # Do some expensive initialization with a state
                state = very_expensive_initialization()
                for x in iterator:
                    # Use that state for whole iterator.
                    yield calculate_with_state(x, state)

            df.select(calculate("value")).show()

        >>> from typing import Iterator
        >>> @pandas_udf("long")
        ... def plus_one(iterator: Iterator[pd.Series]) -> Iterator[pd.Series]:
        ...     for s in iterator:
        ...         yield s + 1
        ...
        >>> df = spark.createDataFrame(pd.DataFrame([1, 2, 3], columns=["v"]))
        >>> df.select(plus_one(df.v)).show()
        +-----------+
        |plus_one(v)|
        +-----------+
        |          2|
        |          3|
        |          4|
        +-----------+

        .. note:: The length of each series is the length of a batch internally used.

    * Iterator of Multiple Series to Iterator of Series
        `Iterator[Tuple[pandas.Series, ...]]` -> `Iterator[pandas.Series]`

        The function takes an iterator of a tuple of multiple `pandas.Series` and outputs an
        iterator of `pandas.Series`. In this case, the created pandas UDF instance requires
        input columns as many as the series when this is called as a PySpark column.
        It works identically as Iterator of Series to Iterator of Series case except
        the parameter difference. The output of each series from the function should always
        be of the same length as the input.

        >>> from typing import Iterator, Tuple
        >>> from pyspark.sql.functions import struct, col
        >>> @pandas_udf("long")
        ... def multiply(iterator: Iterator[Tuple[pd.Series, pd.DataFrame]]) -> Iterator[pd.Series]:
        ...     for s1, df in iterator:
        ...         yield s1 * df.v
        ...
        >>> df = spark.createDataFrame(pd.DataFrame([1, 2, 3], columns=["v"]))
        >>> df.withColumn('output', multiply(col("v"), struct(col("v")))).show()
        +---+------+
        |  v|output|
        +---+------+
        |  1|     1|
        |  2|     4|
        |  3|     9|
        +---+------+

        .. note:: The length of each series is the length of a batch internally used.

    * Series to Scalar
        `pandas.Series`, ... -> `Any`

        The function takes `pandas.Series` and returns a scalar value. The `returnType`
        should be a primitive data type, and the returned scalar can be either a python primitive
        type, e.g., int or float or a numpy data type, e.g., numpy.int64 or numpy.float64.
        `Any` should ideally be a specific scalar type accordingly.

        >>> @pandas_udf("double")
        ... def mean_udf(v: pd.Series) -> float:
        ...     return v.mean()
        ...
        >>> df = spark.createDataFrame(
        ...     [(1, 1.0), (1, 2.0), (2, 3.0), (2, 5.0), (2, 10.0)], ("id", "v"))
        >>> df.groupby("id").agg(mean_udf(df['v'])).show()
        +---+-----------+
        | id|mean_udf(v)|
        +---+-----------+
        |  1|        1.5|
        |  2|        6.0|
        +---+-----------+

        This UDF can also be used as window functions as below:

        >>> from pyspark.sql import Window
        >>> @pandas_udf("double")
        ... def mean_udf(v: pd.Series) -> float:
        ...     return v.mean()
        ...
        >>> df = spark.createDataFrame(
        ...     [(1, 1.0), (1, 2.0), (2, 3.0), (2, 5.0), (2, 10.0)], ("id", "v"))
        >>> w = Window.partitionBy('id').orderBy('v').rowsBetween(-1, 0)
        >>> df.withColumn('mean_v', mean_udf("v").over(w)).show()
        +---+----+------+
        | id|   v|mean_v|
        +---+----+------+
        |  1| 1.0|   1.0|
        |  1| 2.0|   1.5|
        |  2| 3.0|   3.0|
        |  2| 5.0|   4.0|
        |  2|10.0|   7.5|
        +---+----+------+

        .. note:: For performance reasons, the input series to window functions are not copied.
            Therefore, mutating the input series is not allowed and will cause incorrect results.
            For the same reason, users should also not rely on the index of the input series.

        .. seealso:: :meth:`pyspark.sql.GroupedData.agg` and :class:`pyspark.sql.Window`

    .. note:: The user-defined functions do not support conditional expressions or short circuiting
        in boolean expressions and it ends up with being executed all internally. If the functions
        can fail on special rows, the workaround is to incorporate the condition into the functions.

    .. note:: The user-defined functions do not take keyword arguments on the calling side.

    .. note:: The data type of returned `pandas.Series` from the user-defined functions should be
        matched with defined `returnType` (see :meth:`types.to_arrow_type` and
        :meth:`types.from_arrow_type`). When there is mismatch between them, Spark might do
        conversion on returned data. The conversion is not guaranteed to be correct and results
        should be checked for accuracy by users.

    .. note:: Currently,
        :class:`pyspark.sql.types.MapType`,
        :class:`pyspark.sql.types.ArrayType` of :class:`pyspark.sql.types.TimestampType` and
        nested :class:`pyspark.sql.types.StructType`
        are currently not supported as output types.

    .. seealso:: :meth:`pyspark.sql.DataFrame.mapInPandas`
    .. seealso:: :meth:`pyspark.sql.GroupedData.applyInPandas`
    .. seealso:: :meth:`pyspark.sql.PandasCogroupedOps.applyInPandas`
    .. seealso:: :meth:`pyspark.sql.UDFRegistration.register`
    """

    # The following table shows most of Pandas data and SQL type conversions in Pandas UDFs that
    # are not yet visible to the user. Some of behaviors are buggy and might be changed in the near
    # future. The table might have to be eventually documented externally.
    # Please see SPARK-28132's PR to see the codes in order to generate the table below.
    #
    # +-----------------------------+----------------------+------------------+------------------+------------------+--------------------+--------------------+------------------+------------------+------------------+------------------+--------------+--------------+--------------+-----------------------------------+-----------------------------------------------------+-----------------+--------------------+-----------------------------+--------------+-----------------+------------------+-----------+--------------------------------+  # noqa
    # |SQL Type \ Pandas Value(Type)|None(object(NoneType))|        True(bool)|           1(int8)|          1(int16)|            1(int32)|            1(int64)|          1(uint8)|         1(uint16)|         1(uint32)|         1(uint64)|  1.0(float16)|  1.0(float32)|  1.0(float64)|1970-01-01 00:00:00(datetime64[ns])|1970-01-01 00:00:00-05:00(datetime64[ns, US/Eastern])|a(object(string))|  1(object(Decimal))|[1 2 3](object(array[int32]))| 1.0(float128)|(1+0j)(complex64)|(1+0j)(complex128)|A(category)|1 days 00:00:00(timedelta64[ns])|  # noqa
    # +-----------------------------+----------------------+------------------+------------------+------------------+--------------------+--------------------+------------------+------------------+------------------+------------------+--------------+--------------+--------------+-----------------------------------+-----------------------------------------------------+-----------------+--------------------+-----------------------------+--------------+-----------------+------------------+-----------+--------------------------------+  # noqa
    # |                      boolean|                  None|              True|              True|              True|                True|                True|              True|              True|              True|              True|          True|          True|          True|                                  X|                                                    X|                X|                   X|                            X|             X|                X|                 X|          X|                               X|  # noqa
    # |                      tinyint|                  None|                 1|                 1|                 1|                   1|                   1|                 1|                 1|                 1|                 1|             1|             1|             1|                                  X|                                                    X|                X|                   1|                            X|             X|                X|                 X|          0|                               X|  # noqa
    # |                     smallint|                  None|                 1|                 1|                 1|                   1|                   1|                 1|                 1|                 1|                 1|             1|             1|             1|                                  X|                                                    X|                X|                   1|                            X|             X|                X|                 X|          X|                               X|  # noqa
    # |                          int|                  None|                 1|                 1|                 1|                   1|                   1|                 1|                 1|                 1|                 1|             1|             1|             1|                                  X|                                                    X|                X|                   1|                            X|             X|                X|                 X|          X|                               X|  # noqa
    # |                       bigint|                  None|                 1|                 1|                 1|                   1|                   1|                 1|                 1|                 1|                 1|             1|             1|             1|                                  0|                                       18000000000000|                X|                   1|                            X|             X|                X|                 X|          X|                               X|  # noqa
    # |                        float|                  None|               1.0|               1.0|               1.0|                 1.0|                 1.0|               1.0|               1.0|               1.0|               1.0|           1.0|           1.0|           1.0|                                  X|                                                    X|                X|                   X|                            X|             X|                X|                 X|          X|                               X|  # noqa
    # |                       double|                  None|               1.0|               1.0|               1.0|                 1.0|                 1.0|               1.0|               1.0|               1.0|               1.0|           1.0|           1.0|           1.0|                                  X|                                                    X|                X|                   X|                            X|             X|                X|                 X|          X|                               X|  # noqa
    # |                         date|                  None|                 X|                 X|                 X|datetime.date(197...|                   X|                 X|                 X|                 X|                 X|             X|             X|             X|               datetime.date(197...|                                 datetime.date(197...|                X|datetime.date(197...|                            X|             X|                X|                 X|          X|                               X|  # noqa
    # |                    timestamp|                  None|                 X|                 X|                 X|                   X|datetime.datetime...|                 X|                 X|                 X|                 X|             X|             X|             X|               datetime.datetime...|                                 datetime.datetime...|                X|datetime.datetime...|                            X|             X|                X|                 X|          X|                               X|  # noqa
    # |                       string|                  None|                ''|                ''|                ''|              '\x01'|              '\x01'|                ''|                ''|            '\x01'|            '\x01'|            ''|            ''|            ''|                                  X|                                                    X|              'a'|                   X|                            X|            ''|                X|                ''|          X|                               X|  # noqa
    # |                decimal(10,0)|                  None|                 X|                 X|                 X|                   X|                   X|                 X|                 X|                 X|                 X|             X|             X|             X|                                  X|                                                    X|                X|        Decimal('1')|                            X|             X|                X|                 X|          X|                               X|  # noqa
    # |                   array<int>|                  None|                 X|                 X|                 X|                   X|                   X|                 X|                 X|                 X|                 X|             X|             X|             X|                                  X|                                                    X|                X|                   X|                    [1, 2, 3]|             X|                X|                 X|          X|                               X|  # noqa
    # |              map<string,int>|                     X|                 X|                 X|                 X|                   X|                   X|                 X|                 X|                 X|                 X|             X|             X|             X|                                  X|                                                    X|                X|                   X|                            X|             X|                X|                 X|          X|                               X|  # noqa
    # |               struct<_1:int>|                     X|                 X|                 X|                 X|                   X|                   X|                 X|                 X|                 X|                 X|             X|             X|             X|                                  X|                                                    X|                X|                   X|                            X|             X|                X|                 X|          X|                               X|  # noqa
    # |                       binary|                  None|bytearray(b'\x01')|bytearray(b'\x01')|bytearray(b'\x01')|  bytearray(b'\x01')|  bytearray(b'\x01')|bytearray(b'\x01')|bytearray(b'\x01')|bytearray(b'\x01')|bytearray(b'\x01')|bytearray(b'')|bytearray(b'')|bytearray(b'')|                     bytearray(b'')|                                       bytearray(b'')|  bytearray(b'a')|                   X|                            X|bytearray(b'')|   bytearray(b'')|    bytearray(b'')|          X|                  bytearray(b'')|  # noqa
    # +-----------------------------+----------------------+------------------+------------------+------------------+--------------------+--------------------+------------------+------------------+------------------+------------------+--------------+--------------+--------------+-----------------------------------+-----------------------------------------------------+-----------------+--------------------+-----------------------------+--------------+-----------------+------------------+-----------+--------------------------------+  # noqa
    #
    # Note: DDL formatted string is used for 'SQL Type' for simplicity. This string can be
    #       used in `returnType`.
    # Note: The values inside of the table are generated by `repr`.
    # Note: Python 3.7.3, Pandas 0.24.2 and PyArrow 0.13.0 are used.
    # Note: Timezone is KST.
    # Note: 'X' means it throws an exception during the conversion.
    require_minimum_pandas_version()
    require_minimum_pyarrow_version()

    # decorator @pandas_udf(returnType, functionType)
    is_decorator = f is None or isinstance(f, (str, DataType))

    if is_decorator:
        # If DataType has been passed as a positional argument
        # for decorator use it as a returnType
        return_type = f or returnType

        if functionType is not None:
            # @pandas_udf(dataType, functionType=functionType)
            # @pandas_udf(returnType=dataType, functionType=functionType)
            eval_type = functionType
        elif returnType is not None and isinstance(returnType, int):
            # @pandas_udf(dataType, functionType)
            eval_type = returnType
        else:
            # @pandas_udf(dataType) or @pandas_udf(returnType=dataType)
            eval_type = None
    else:
        return_type = returnType

        if functionType is not None:
            eval_type = functionType
        else:
            eval_type = None

    if return_type is None:
        raise ValueError("Invalid return type: returnType can not be None")

    if eval_type not in [PythonEvalType.SQL_SCALAR_PANDAS_UDF,
                         PythonEvalType.SQL_SCALAR_PANDAS_ITER_UDF,
                         PythonEvalType.SQL_GROUPED_MAP_PANDAS_UDF,
                         PythonEvalType.SQL_GROUPED_AGG_PANDAS_UDF,
                         PythonEvalType.SQL_MAP_PANDAS_ITER_UDF,
                         PythonEvalType.SQL_COGROUPED_MAP_PANDAS_UDF,
                         None]:  # None means it should infer the type from type hints.

        raise ValueError("Invalid function type: "
                         "functionType must be one the values from PandasUDFType")

    if is_decorator:
        return functools.partial(_create_pandas_udf, returnType=return_type, evalType=eval_type)
    else:
        return _create_pandas_udf(f=f, returnType=return_type, evalType=eval_type)


def _create_pandas_udf(f, returnType, evalType):
    argspec = _get_argspec(f)

    # pandas UDF by type hints.
    if sys.version_info >= (3, 6):
        from inspect import signature

        if evalType in [PythonEvalType.SQL_SCALAR_PANDAS_UDF,
                        PythonEvalType.SQL_SCALAR_PANDAS_ITER_UDF,
                        PythonEvalType.SQL_GROUPED_AGG_PANDAS_UDF]:
            warnings.warn(
                "In Python 3.6+ and Spark 3.0+, it is preferred to specify type hints for "
                "pandas UDF instead of specifying pandas UDF type which will be deprecated "
                "in the future releases. See SPARK-28264 for more details.", UserWarning)
        elif evalType in [PythonEvalType.SQL_GROUPED_MAP_PANDAS_UDF,
                          PythonEvalType.SQL_MAP_PANDAS_ITER_UDF,
                          PythonEvalType.SQL_COGROUPED_MAP_PANDAS_UDF]:
            # In case of 'SQL_GROUPED_MAP_PANDAS_UDF',  deprecation warning is being triggered
            # at `apply` instead.
            # In case of 'SQL_MAP_PANDAS_ITER_UDF' and 'SQL_COGROUPED_MAP_PANDAS_UDF', the
            # evaluation type will always be set.
            pass
        elif len(argspec.annotations) > 0:
            evalType = infer_eval_type(signature(f))
            assert evalType is not None

    if evalType is None:
        # Set default is scalar UDF.
        evalType = PythonEvalType.SQL_SCALAR_PANDAS_UDF

    if (evalType == PythonEvalType.SQL_SCALAR_PANDAS_UDF or
            evalType == PythonEvalType.SQL_SCALAR_PANDAS_ITER_UDF) and \
            len(argspec.args) == 0 and \
            argspec.varargs is None:
        raise ValueError(
            "Invalid function: 0-arg pandas_udfs are not supported. "
            "Instead, create a 1-arg pandas_udf and ignore the arg in your function."
        )

    if evalType == PythonEvalType.SQL_GROUPED_MAP_PANDAS_UDF \
            and len(argspec.args) not in (1, 2):
        raise ValueError(
            "Invalid function: pandas_udf with function type GROUPED_MAP or "
            "the function in groupby.applyInPandas "
            "must take either one argument (data) or two arguments (key, data).")

    if evalType == PythonEvalType.SQL_COGROUPED_MAP_PANDAS_UDF \
            and len(argspec.args) not in (2, 3):
        raise ValueError(
            "Invalid function: the function in cogroup.applyInPandas "
            "must take either two arguments (left, right) "
            "or three arguments (key, left, right).")

    return _create_udf(f, returnType, evalType)
