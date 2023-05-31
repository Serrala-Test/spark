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

from pyspark.mlv2.base import _PredictorParams

from pyspark.ml.param.shared import HasProbabilityCol

import logging
from typing import Any, Union, List, Tuple, Callable
import numpy as np
import pandas as pd
import math
from pyspark.mlv2.base import Estimator, Model, Predictor, PredictionModel

from pyspark.sql import DataFrame

from pyspark.ml.torch.distributor import TorchDistributor
from pyspark.ml.param.shared import (
    HasMaxIter,
    HasFitIntercept,
    HasTol,
    HasWeightCol,
)
from pyspark.mlv2.common_params import (
    HasNumTrainWorkers,
    HasBatchSize,
    HasLearningRate,
    HasMomentum,
)
from pyspark.sql.functions import lit, count, countDistinct

import torch
import torch.nn as torch_nn
import torch.nn.functional as torch_fn


class _LogisticRegressionParams(
    _PredictorParams,
    HasMaxIter,
    HasFitIntercept,
    HasTol,
    HasWeightCol,
    HasNumTrainWorkers,
    HasBatchSize,
    HasLearningRate,
    HasMomentum,
    HasProbabilityCol,
):
    """
    Params for :py:class:`LogisticRegression` and :py:class:`LogisticRegressionModel`.

    .. versionadded:: 3.0.0
    """

    pass


class _LinearNet(torch_nn.Module):
    def __init__(self, num_features, num_classes, bias) -> None:
        super(_LinearNet, self).__init__()
        output_dim = num_classes
        self.fc = torch_nn.Linear(num_features, output_dim, bias=bias, dtype=torch.float32)

    def forward(self, x: Any) -> Any:
        output = self.fc(x)
        return output


def _train_logistic_regression_model_worker_fn(
        num_samples_per_worker,
        num_features,
        batch_size,
        max_iter,
        num_classes,
        learning_rate,
        momentum,
        fit_intercept,
):
    from pyspark.ml.torch.distributor import _get_spark_partition_data_loader
    from torch.nn.parallel import DistributedDataParallel as DDP
    import torch.distributed
    import torch.optim as optim

    # TODO: support training on GPU
    # TODO: support L1 / L2 regularization
    torch.distributed.init_process_group("gloo")

    ddp_model = DDP(_LinearNet(
        num_features=num_features,
        num_classes=num_classes,
        bias=fit_intercept
    ))

    loss_fn = torch_nn.CrossEntropyLoss()

    optimizer = optim.SGD(ddp_model.parameters(), lr=learning_rate, momentum=momentum)
    data_loader = _get_spark_partition_data_loader(
        num_samples_per_worker, batch_size, num_workers=0
    )
    for i in range(max_iter):
        ddp_model.train()

        step_count = 0

        loss_sum = 0.0
        for x, target in data_loader:
            optimizer.zero_grad()
            output = ddp_model(x.to(torch.float32))
            loss = loss_fn(output, target.to(torch.long))
            loss.backward()
            loss_sum += loss.detach().numpy()
            optimizer.step()
            step_count += 1

        # TODO: early stopping
        #  When each epoch ends, computes loss on validation dataset and compare
        #  current epoch validation loss with last epoch validation loss, if
        #  less than provided `tol`, stop training.

        if torch.distributed.get_rank() == 0:
            print(f"Progress: train epoch {i + 1} completes, train loss = {loss_sum / step_count}")

    if torch.distributed.get_rank() == 0:
        return ddp_model.module.state_dict()

    return None


class LogisticRegression(Predictor["LogisticRegressionModel"], _LogisticRegressionParams):

    def __init__(
            self,
            *,
            maxIter=100,
            tol=1e-6,
            numTrainWorkers=1,
            batchSize=32,
            learningRate=0.001,
            momentum=0.9,
    ):
        super(_LogisticRegressionParams, self).__init__()
        self._set(
            maxIter=maxIter,
            tol=tol,
            numTrainWorkers=numTrainWorkers,
            batchSize=batchSize,
            learningRate=learningRate,
            momentum=momentum,
        )

    def _fit(self, dataset: Union[DataFrame, pd.DataFrame]) -> "LogisticRegressionModel":
        if isinstance(dataset, pd.DataFrame):
            # TODO: support pandas dataframe fitting
            raise NotImplementedError("Fitting pandas dataframe is not supported yet.")

        num_train_workers = self.getNumTrainWorkers()
        batch_size = self.getBatchSize()

        # Q: Shall we persist the shuffled dataset ?
        # shuffling results are already cached
        dataset = (
            dataset
                .select(self.getFeaturesCol(), self.getLabelCol())
                .repartition(num_train_workers)
                .persist()
        )

        # TODO: check label values are in range of [0, num_classes)
        num_rows, num_classes = dataset.agg(count(lit(1)), countDistinct(self.getLabelCol())).head()

        num_batches_per_worker = math.ceil(num_rows / num_train_workers / batch_size)
        num_samples_per_worker = num_batches_per_worker * batch_size

        num_features = len(dataset.select(self.getFeaturesCol()).head()[0])

        if num_classes < 2:
            raise ValueError("Training dataset distinct labels must >= 2.")

        # TODO: support GPU.
        distributor = TorchDistributor(
            local_mode=False, use_gpu=False, num_processes=num_train_workers
        )
        model_state_dict = distributor._train_on_dataframe(
            _train_logistic_regression_model_worker_fn,
            dataset,
            num_samples_per_worker=num_samples_per_worker,
            num_features=num_features,
            batch_size=batch_size,
            max_iter=self.getMaxIter(),
            num_classes=num_classes,
            learning_rate=self.getLearningRate(),
            momentum=self.getMomentum(),
            fit_intercept=self.getFitIntercept(),
        )

        dataset.unpersist()

        torch_model = _LinearNet(
            num_features=num_features, num_classes=num_classes, bias=self.getFitIntercept()
        )
        torch_model.load_state_dict(model_state_dict)

        lor_model = LogisticRegressionModel(
            torch_model, num_features=num_features, num_classes=num_classes
        )
        lor_model._resetUid(self.uid)
        return self._copyValues(lor_model)


class LogisticRegressionModel(PredictionModel, _LogisticRegressionParams):

    def __init__(self, torch_model, num_features, num_classes):
        super().__init__()
        self.torch_model = torch_model
        self.num_features = num_features
        self.num_classes = num_classes

    def numFeatures(self) -> int:
        return self.num_features

    def numClasses(self) -> int:
        return self.num_classes

    def _input_column_name(self) -> str:
        return self.getOrDefault(self.featuresCol)

    def _output_columns(self) -> List[Tuple[str, str]]:
        output_cols = [(self.getOrDefault(self.predictionCol), "bigint")]
        prob_col = self.getOrDefault(self.probabilityCol)
        if prob_col:
            output_cols += [(prob_col, "array<double>")]
        return output_cols

    def _get_transform_fn(self) -> Callable[["pd.Series"], Any]:
        model_state_dict = self.torch_model.state_dict()
        num_features = self.num_features
        num_classes = self.num_classes
        fit_intercept = self.getFitIntercept()

        def transform_fn(input_series: "pd.Series") -> Any:
            torch_model = _LinearNet(
                num_features=num_features, num_classes=num_classes,
                bias=fit_intercept
            )
            # TODO: Use spark broadast for `model_state_dict`,
            #  it can improve performance when model is large.
            torch_model.load_state_dict(model_state_dict)

            input_array = np.stack(input_series.values)

            with torch.inference_mode():
                result = torch_model(torch.tensor(input_array, dtype=torch.float32))
                predictions = torch.argmax(result, dim=1).numpy()

            if self.getProbabilityCol():
                probabilities = torch.softmax(result, dim=1).numpy()

                return pd.DataFrame({
                    self.getPredictionCol(): list(predictions),
                    self.getProbabilityCol(): list(probabilities),
                }, index=input_series.index.copy())
            else:
                return pd.Series(data=list(predictions), index=input_series.index.copy())

        return transform_fn
