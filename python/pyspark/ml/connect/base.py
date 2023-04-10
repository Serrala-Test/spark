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

from abc import ABCMeta, abstractmethod

import copy
import threading

from typing import (
    Any,
    Callable,
    Generic,
    Iterator,
    List,
    Optional,
    Sequence,
    Tuple,
    TypeVar,
    Union,
    cast,
    overload,
    TYPE_CHECKING,
)

from pyspark import since
from pyspark.ml.param import P
from pyspark.ml.common import inherit_doc
from pyspark.ml.param.shared import (
    HasInputCol,
    HasOutputCol,
    HasLabelCol,
    HasFeaturesCol,
    HasPredictionCol,
    Params,
)
from pyspark.sql.dataframe import DataFrame
from pyspark.sql.functions import udf
from pyspark.sql.types import DataType, StructField, StructType

if TYPE_CHECKING:
    from pyspark.ml._typing import ParamMap

T = TypeVar("T")
M = TypeVar("M", bound="Transformer")


class _FitMultipleIterator(Generic[M]):
    """
    Used by default implementation of Estimator.fitMultiple to produce models in a thread safe
    iterator. This class handles the simple case of fitMultiple where each param map should be
    fit independently.

    Parameters
    ----------
    fitSingleModel : function
        Callable[[int], Transformer] which fits an estimator to a dataset.
        `fitSingleModel` may be called up to `numModels` times, with a unique index each time.
        Each call to `fitSingleModel` with an index should return the Model associated with
        that index.
    numModel : int
        Number of models this iterator should produce.

    Notes
    -----
    See :py:meth:`Estimator.fitMultiple` for more info.
    """

    def __init__(self, fitSingleModel: Callable[[int], M], numModels: int):
        """ """
        self.fitSingleModel = fitSingleModel
        self.numModel = numModels
        self.counter = 0
        self.lock = threading.Lock()

    def __iter__(self) -> Iterator[Tuple[int, M]]:
        return self

    def __next__(self) -> Tuple[int, M]:
        with self.lock:
            index = self.counter
            if index >= self.numModel:
                raise StopIteration("No models remaining.")
            self.counter += 1
        return index, self.fitSingleModel(index)

    def next(self) -> Tuple[int, M]:
        """For python2 compatibility."""
        return self.__next__()


@inherit_doc
class Estimator(Params, Generic[M], metaclass=ABCMeta):
    """
    Abstract class for estimators that fit models to data.

    .. versionadded:: 1.3.0
    """

    @abstractmethod
    def _fit(self, dataset: DataFrame) -> M:
        """
        Fits a model to the input dataset. This is called by the default implementation of fit.


        Parameters
        ----------
        dataset : :py:class:`pyspark.sql.DataFrame`
            input dataset

        Returns
        -------
        :class:`Transformer`
            fitted model
        """
        raise NotImplementedError()

    def fitMultiple(
            self, dataset: DataFrame, paramMaps: Sequence["ParamMap"]
    ) -> Iterator[Tuple[int, M]]:
        """
        Fits a model to the input dataset for each param map in `paramMaps`.

        .. versionadded:: 2.3.0

        Parameters
        ----------
        dataset : :py:class:`pyspark.sql.DataFrame`
            input dataset.
        paramMaps : :py:class:`collections.abc.Sequence`
            A Sequence of param maps.

        Returns
        -------
        :py:class:`_FitMultipleIterator`
            A thread safe iterable which contains one model for each param map. Each
            call to `next(modelIterator)` will return `(index, model)` where model was fit
            using `paramMaps[index]`. `index` values may not be sequential.
        """
        estimator = self.copy()

        def fitSingleModel(index: int) -> M:
            return estimator.fit(dataset, paramMaps[index])

        return _FitMultipleIterator(fitSingleModel, len(paramMaps))

    @overload
    def fit(self, dataset: DataFrame, params: Optional["ParamMap"] = ...) -> M:
        ...

    @overload
    def fit(
            self, dataset: DataFrame, params: Union[List["ParamMap"], Tuple["ParamMap"]]
    ) -> List[M]:
        ...

    def fit(
            self,
            dataset: DataFrame,
            params: Optional[Union["ParamMap", List["ParamMap"], Tuple["ParamMap"]]] = None,
    ) -> Union[M, List[M]]:
        """
        Fits a model to the input dataset with optional parameters.

        .. versionadded:: 1.3.0

        Parameters
        ----------
        dataset : :py:class:`pyspark.sql.DataFrame`
            input dataset.
        params : dict or list or tuple, optional
            an optional param map that overrides embedded params. If a list/tuple of
            param maps is given, this calls fit on each param map and returns a list of
            models.

        Returns
        -------
        :py:class:`Transformer` or a list of :py:class:`Transformer`
            fitted model(s)
        """
        if params is None:
            params = dict()
        if isinstance(params, (list, tuple)):
            models: List[Optional[M]] = [None] * len(params)
            for index, model in self.fitMultiple(dataset, params):
                models[index] = model
            return cast(List[M], models)
        elif isinstance(params, dict):
            if params:
                return self.copy(params)._fit(dataset)
            else:
                return self._fit(dataset)
        else:
            raise TypeError(
                "Params must be either a param map or a list/tuple of param maps, "
                "but got %s." % type(params)
            )


@inherit_doc
class Transformer(Params, metaclass=ABCMeta):
    """
    Abstract class for transformers that transform one dataset into another.

    .. versionadded:: 1.3.0
    """

    @abstractmethod
    def _transform(self, dataset: DataFrame) -> DataFrame:
        """
        Transforms the input dataset.

        Parameters
        ----------
        dataset : :py:class:`pyspark.sql.DataFrame`
            input dataset.

        Returns
        -------
        :py:class:`pyspark.sql.DataFrame`
            transformed dataset
        """
        raise NotImplementedError()

    def transform(self, dataset: DataFrame, params: Optional["ParamMap"] = None) -> DataFrame:
        """
        Transforms the input dataset with optional parameters.

        .. versionadded:: 1.3.0

        Parameters
        ----------
        dataset : :py:class:`pyspark.sql.DataFrame`
            input dataset
        params : dict, optional
            an optional param map that overrides embedded params.

        Returns
        -------
        :py:class:`pyspark.sql.DataFrame`
            transformed dataset
        """
        if params is None:
            params = dict()
        if isinstance(params, dict):
            if params:
                return self.copy(params)._transform(dataset)
            else:
                return self._transform(dataset)
        else:
            raise TypeError("Params must be a param map but got %s." % type(params))


@inherit_doc
class Model(Transformer, metaclass=ABCMeta):
    """
    Abstract class for models that are fitted by estimators.

    .. versionadded:: 1.4.0
    """

    pass
