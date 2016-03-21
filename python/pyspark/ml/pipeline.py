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

import sys

if sys.version > '3':
    basestring = str

from pyspark import SparkContext
from pyspark import since
from pyspark.ml import Estimator, Model, Transformer
from pyspark.ml.param import Param, Params
from pyspark.ml.util import keyword_only, JavaMLWriter, JavaMLReader, MLReadable, MLWritable
from pyspark.ml.wrapper import JavaWrapper
from pyspark.mllib.common import inherit_doc


@inherit_doc
class PipelineMLWriter(JavaMLWriter):
    """
    Private Pipeline utility class that can save ML instances through their Scala implementation.

    We can currently use JavaMLWriter, rather than MLWriter, since Pipeline implements
    _transfer_stage_to_java.
    """


@inherit_doc
class PipelineMLReader(JavaMLReader):
    """
    Private utility class that can load Pipeline instances through their Scala implementation.

    We can currently use JavaMLReader, rather than MLReader, since Pipeline implements
    _transfer_stage_from_java.
    """


@inherit_doc
class Pipeline(Estimator, MLReadable, MLWritable):
    """
    A simple pipeline, which acts as an estimator. A Pipeline consists
    of a sequence of stages, each of which is either an
    :py:class:`Estimator` or a :py:class:`Transformer`. When
    :py:meth:`Pipeline.fit` is called, the stages are executed in
    order. If a stage is an :py:class:`Estimator`, its
    :py:meth:`Estimator.fit` method will be called on the input
    dataset to fit a model. Then the model, which is a transformer,
    will be used to transform the dataset as the input to the next
    stage. If a stage is a :py:class:`Transformer`, its
    :py:meth:`Transformer.transform` method will be called to produce
    the dataset for the next stage. The fitted model from a
    :py:class:`Pipeline` is an :py:class:`PipelineModel`, which
    consists of fitted models and transformers, corresponding to the
    pipeline stages. If there are no stages, the pipeline acts as an
    identity transformer.

    .. versionadded:: 1.3.0
    """

    stages = Param(Params._dummy(), "stages", "pipeline stages")

    @keyword_only
    def __init__(self, stages=None):
        """
        __init__(self, stages=None)
        """
        if stages is None:
            stages = []
        super(Pipeline, self).__init__()
        kwargs = self.__init__._input_kwargs
        self.setParams(**kwargs)

    @since("1.3.0")
    def setStages(self, value):
        """
        Set pipeline stages.

        :param value: a list of transformers or estimators
        :return: the pipeline instance
        """
        self._paramMap[self.stages] = value
        return self

    @since("1.3.0")
    def getStages(self):
        """
        Get pipeline stages.
        """
        if self.stages in self._paramMap:
            return self._paramMap[self.stages]

    @keyword_only
    @since("1.3.0")
    def setParams(self, stages=None):
        """
        setParams(self, stages=None)
        Sets params for Pipeline.
        """
        if stages is None:
            stages = []
        kwargs = self.setParams._input_kwargs
        return self._set(**kwargs)

    def _fit(self, dataset):
        stages = self.getStages()
        for stage in stages:
            if not (isinstance(stage, Estimator) or isinstance(stage, Transformer)):
                raise TypeError(
                    "Cannot recognize a pipeline stage of type %s." % type(stage))
        indexOfLastEstimator = -1
        for i, stage in enumerate(stages):
            if isinstance(stage, Estimator):
                indexOfLastEstimator = i
        transformers = []
        for i, stage in enumerate(stages):
            if i <= indexOfLastEstimator:
                if isinstance(stage, Transformer):
                    transformers.append(stage)
                    dataset = stage.transform(dataset)
                else:  # must be an Estimator
                    model = stage.fit(dataset)
                    transformers.append(model)
                    if i < indexOfLastEstimator:
                        dataset = model.transform(dataset)
            else:
                transformers.append(stage)
        return PipelineModel(transformers)

    @since("1.4.0")
    def copy(self, extra=None):
        """
        Creates a copy of this instance.

        :param extra: extra parameters
        :returns: new instance
        """
        if extra is None:
            extra = dict()
        that = Params.copy(self, extra)
        stages = [stage.copy(extra) for stage in that.getStages()]
        return that.setStages(stages)

    @since("2.0.0")
    def write(self):
        """Returns an JavaMLWriter instance for this ML instance."""
        return PipelineMLWriter(self)

    @since("2.0.0")
    def save(self, path):
        """Save this ML instance to the given path, a shortcut of `write().save(path)`."""
        self.write().save(path)

    @classmethod
    @since("2.0.0")
    def read(cls):
        """Returns an MLReader instance for this class."""
        return PipelineMLReader(cls)

    @classmethod
    def _transfer_stage_from_java(cls, java_stage):
        """
        Given a Java Pipeline, create and return a Python wrapper of it.
        Used for ML persistence.
        """
        # Create a new instance of this stage.
        py_stage = cls()
        # Load information from java_stage to the instance.
        py_stages = [JavaWrapper._transfer_stage_from_java(s) for s in java_stage.getStages()]
        py_stage.setStages(py_stages)
        py_stage._resetUid(java_stage.uid())
        return py_stage

    def _transfer_stage_to_java(self):
        """
        Transfer this instance to a Java object.  Used for ML persistence.

        :return: Java object equivalent to this instance.
        """

        gateway = SparkContext._gateway
        cls = SparkContext._jvm.org.apache.spark.ml.PipelineStage
        java_stages = gateway.new_array(cls, len(self.getStages()))
        for idx, stage in enumerate(self.getStages()):
            java_stages[idx] = stage._transfer_stage_to_java()

        _java_obj = JavaWrapper._new_java_obj("org.apache.spark.ml.Pipeline", self.uid)
        _java_obj.setStages(java_stages)

        return _java_obj


@inherit_doc
class PipelineModelMLWriter(JavaMLWriter):
    """
    Private PipelineModel utility class that can save ML instances through their Scala
    implementation.

    We can (currently) use JavaMLWriter, rather than MLWriter, since PipelineModel implements
    _transfer_stage_to_java.
    """


@inherit_doc
class PipelineModelMLReader(JavaMLReader):
    """
    Private utility class that can load PipelineModel instances through their Scala implementation.

    We can currently use JavaMLReader, rather than MLReader, since PipelineModel implements
    _transfer_stage_from_java.
    """


@inherit_doc
class PipelineModel(Model, MLReadable, MLWritable):
    """
    Represents a compiled pipeline with transformers and fitted models.

    .. versionadded:: 1.3.0
    """

    def __init__(self, stages):
        super(PipelineModel, self).__init__()
        self.stages = stages

    def _transform(self, dataset):
        for t in self.stages:
            dataset = t.transform(dataset)
        return dataset

    @since("1.4.0")
    def copy(self, extra=None):
        """
        Creates a copy of this instance.

        :param extra: extra parameters
        :returns: new instance
        """
        if extra is None:
            extra = dict()
        stages = [stage.copy(extra) for stage in self.stages]
        return PipelineModel(stages)

    @since("2.0.0")
    def write(self):
        """Returns an JavaMLWriter instance for this ML instance."""
        return PipelineModelMLWriter(self)

    @since("2.0.0")
    def save(self, path):
        """Save this ML instance to the given path, a shortcut of `write().save(path)`."""
        self.write().save(path)

    @classmethod
    @since("2.0.0")
    def read(cls):
        """Returns an JavaMLReader instance for this class."""
        return PipelineModelMLReader(cls)

    @classmethod
    def _transfer_stage_from_java(cls, java_stage):
        """
        Given a Java PipelineModel, create and return a Python wrapper of it.
        Used for ML persistence.
        """
        # Load information from java_stage to the instance.
        py_stages = [JavaWrapper._transfer_stage_from_java(s) for s in java_stage.stages()]
        # Create a new instance of this stage.
        py_stage = cls(py_stages)
        py_stage._resetUid(java_stage.uid())
        return py_stage

    def _transfer_stage_to_java(self):
        """
        Transfer this instance to a Java object.  Used for ML persistence.

        :return: Java object equivalent to this instance.
        """

        gateway = SparkContext._gateway
        cls = SparkContext._jvm.org.apache.spark.ml.Transformer
        java_stages = gateway.new_array(cls, len(self.stages))
        for idx, stage in enumerate(self.stages):
            java_stages[idx] = stage._transfer_stage_to_java()

        _java_obj =\
            JavaWrapper._new_java_obj("org.apache.spark.ml.PipelineModel", self.uid, java_stages)

        return _java_obj
