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

from pyspark.sql.connect.dataframe import DataFrame
from pyspark.ml import Estimator, Model, Predictor, PredictionModel
from pyspark.ml.wrapper import _PredictorParams
from pyspark.ml.util import MLWritable, MLWriter, MLReadable, MLReader
import pyspark.sql.connect.proto as pb2
import pyspark.sql.connect.proto.ml_pb2 as ml_pb2
import pyspark.sql.connect.proto.ml_common_pb2 as ml_common_pb2
from pyspark.sql.connect.ml.serializer import deserialize, serialize_ml_params
from pyspark.sql.connect.session import SparkSession
from pyspark.sql.connect.plan import LogicalPlan


class ClientEstimator(Estimator, metaclass=ABCMeta):

    def _algo_name(self): str

    @abstractmethod
    def _create_model(self, model_ref_id, model_uid):
        raise NotImplementedError()

    def _fit(self, dataset: DataFrame) -> Model:
        client = dataset.sparkSession.client
        dataset_relation = dataset._plan.plan(client)
        estimator_proto = ml_common_pb2.MlStage(
            name=self._algo_name(),
            params=serialize_ml_params(self, client),
            uid=self.uid,
            type=ml_common_pb2.MlStage.ESTIMATOR,
        )
        fit_command_proto = ml_pb2.MlCommand.Fit(
            estimator=estimator_proto,
            dataset=dataset_relation,
        )
        req = client._execute_plan_request_with_metadata()
        req.plan.ml_command.fit.CopyFrom(fit_command_proto)

        resp = client._execute_ml(req)
        model_ref_id, model_uid = deserialize(resp, client)
        model = self._create_model()
        # TODO: copy all estimator params to model
        model._resetUid(model_uid)
        model.ref_id = model_ref_id
        return model


class ClientPredictor(Predictor, ClientEstimator, _PredictorParams, metaclass=ABCMeta):
    pass


class ClientModel(Model, metaclass=ABCMeta):

    ref_id: str
    uid: str

    def _get_model_attr(self, name):
        client = SparkSession.getActiveSession().client
        model_attr_command_proto = ml_pb2.MlCommand.FetchModelAttr(
            model_ref_id=self.ref_id,
            name=name
        )
        req = client._execute_plan_request_with_metadata()
        req.plan.ml_command.fetch_model_attr.CopyFrom(model_attr_command_proto)

        resp = client._execute_ml(req)
        return deserialize(resp, client)

    def _get_model_attr_dataframe(self, name) -> DataFrame:
        session = SparkSession.getActiveSession()
        plan = _ModelAttrRelationPlan(
            self, name
        )
        return DataFrame.withPlan(plan, session)

    def _transform(self, dataset: DataFrame) -> DataFrame:
        session = dataset.sparkSession
        plan = _ModelTransformRelationPlan(dataset._plan, self)
        return DataFrame.withPlan(plan, session)


class ClientPredictionModel(PredictionModel, ClientModel, _PredictorParams):
    @property  # type: ignore[misc]
    def numFeatures(self) -> int:
        return self._get_model_attr("numFeatures")

    def predict(self, value) -> float:
        # TODO: support this.
        raise NotImplementedError()


class _ModelTransformRelationPlan(LogicalPlan):
    def __init__(self, child, model):
        super().__init__(child)
        self.model = model

    def plan(self, session: "SparkConnectClient") -> pb2.Relation:
        assert self._child is not None
        plan = self._create_proto_relation()
        plan.ml_relation.model_transform.input.CopyFrom(self._child.plan(session))
        plan.ml_relation.model_transform.model_ref_id = self.model.ref_id
        plan.ml_relation.model_transform.params.CopyFrom(serialize_ml_params(self.model, session))

        return plan


class _ModelAttrRelationPlan(LogicalPlan):
    def __init__(self, model, name):
        super().__init__(None)
        self.model = model
        self.name = name

    def plan(self, session: "SparkConnectClient") -> pb2.Relation:
        assert self._child is None
        plan = self._create_proto_relation()
        plan.ml_relation.model_attr.model_ref_id = self.model.ref_id
        plan.ml_relation.model_attr.name = self.name
        plan.ml_relation.model_attr.params.CopyFrom(serialize_ml_params(self.model, session))
        return plan


class _ModelSummaryAttrRelationPlan(LogicalPlan):
    def __init__(self, child, model, name):
        super().__init__(child)
        self.model = model
        self.name = name

    def plan(self, session: "SparkConnectClient") -> pb2.Relation:
        plan = self._create_proto_relation()
        if self._child is not None:
            plan.ml_relation.model_summary_attr.evaluation_dataset.CopyFrom(self._child.plan(session))
        plan.ml_relation.model_summary_attr.model_ref_id = self.model.ref_id
        plan.ml_relation.model_summary_attr.name = self.name
        plan.ml_relation.model_summary_attr.params.CopyFrom(serialize_ml_params(self.model, session))
        return plan


class ClientModelSummary(metaclass=ABCMeta):
    def __init__(self, model, dataset):
        self.model = model
        self.dataset = dataset

    def _get_summary_attr_dataframe(self, name):
        session = SparkSession.getActiveSession()
        plan = _ModelSummaryAttrRelationPlan(
            (self.dataset._plan if self.dataset is not None else None),
            self.model, name
        )
        return DataFrame.withPlan(plan, session)

    def _get_summary_attr(self, name):
        client = SparkSession.getActiveSession().client

        model_summary_attr_command_proto = ml_pb2.MlCommand.FetchModelSummaryAttr(
            model_ref_id=self.model.ref_id,
            name=name,
            params=serialize_ml_params(self.model, client),
            evaluation_dataset=(self.dataset._plan.plan(client) if self.dataset is not None else None)
        )
        req = client._execute_plan_request_with_metadata()
        req.plan.ml_command.fetch_model_summary_attr.CopyFrom(model_summary_attr_command_proto)

        resp = client._execute_ml(req)
        return deserialize(resp, client)


class HasTrainingSummary(ClientModel, metaclass=ABCMeta):

    def hasSummary(self) -> bool:
        return self._get_model_attr("hasSummary")

    @abstractmethod
    def summary(self):
        raise NotImplementedError()


class ClientMLWriter(MLWriter):

    def __init__(self, instance: "ClientMLWritable"):
        super(ClientMLWriter, self).__init__()
        self.instance = instance

    def save(self, path: str) -> None:
        client = SparkSession.getActiveSession().client
        req = client._execute_plan_request_with_metadata()

        if isinstance(self.instance, ClientModel):
            save_cmd_proto = ml_pb2.MlCommand.SaveModel(
                model_ref_id=self.instance.ref_id,
                path=path,
                overwrite=self.shouldOverwrite,
                options=self.optionMap
            )
            req.plan.ml_command.save_model.CopyFrom(save_cmd_proto)
        elif isinstance(self.instance, Estimator):
            save_cmd_proto = ml_pb2.MlCommand.SaveStage(
                ml_common_pb2.MlStage(
                    name=self._algo_name(),
                    params=serialize_ml_params(self, client),
                    uid=self.uid,
                    type=ml_common_pb2.MlStage.ESTIMATOR,
                )
            )
            req.plan.ml_command.save_stage.CopyFrom(save_cmd_proto)
        else:
            raise NotImplementedError()

        client._execute_ml(req)


class ClientMLWritable(MLWritable):
    """
    (Private) Mixin for ML instances that provide :py:class:`JavaMLWriter`.
    """

    def write(self) -> ClientMLWriter:
        """Returns an MLWriter instance for this ML instance."""
        return ClientMLWriter(self)


class ClientMLReader(MLReader):

    def __init__(self, clazz):
        self.clazz = clazz

    def load(self, path: str):
        client = SparkSession.getActiveSession().client
        req = client._execute_plan_request_with_metadata()

        name = self.clazz._algo_name()
        if issubclass(self.clazz, ClientModel):
            load_cmd_proto = ml_pb2.MlCommand.LoadModel(
                name=name,
                path=path
            )
        elif issubclass(self.clazz, ClientEstimator):
            load_cmd_proto = ml_pb2.MlCommand.LoadStage(
                name=name,
                path=path,
                type=ml_common_pb2.MlStage.ESTIMATOR
            )


class ClientMLReadable(MLReadable):
    @classmethod
    def read(cls) -> ClientMLReader:
        """Returns an MLReader instance for this class."""
        return ClientMLReader(cls)
