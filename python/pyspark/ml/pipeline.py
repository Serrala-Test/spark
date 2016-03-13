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
from pyspark.ml.util import keyword_only, JavaMLWriter, JavaMLReader
from pyspark.ml.wrapper import JavaWrapper
from pyspark.mllib.common import inherit_doc


def _stages_java2py(java_stages):
    """
    Transforms the parameter Python stages from a list of Java stages.
    :param java_stages: An array of Java stages.
    :return: An array of Python stages.
    """

    for stage in java_stages:
        assert(stage.getClass().getName() != "org.apache.spark.Pipeline" or
               stage.getClass().getName() != "org.apache.spark.PipelineModel",
               "Nested Pipeline and PipelineModel are not supported for save/load now.")
    return map(JavaWrapper._transfer_stage_from_java, java_stages)


def _stages_py2java(py_stages):
    """
    Transforms the parameter of Python stages to a Java array of Java stages.
    :param py_stages: An array of Python stages.
    :return: A Java array of Java Stages.
    """

    for stage in py_stages:
        assert(isinstance(stage, JavaWrapper),
               "Nested Pipeline and PipelineModel are not supported for save/load now.")
    gateway = SparkContext._gateway
    jvm = SparkContext._jvm
    java_stages = gateway.new_array(jvm.org.apache.spark.ml.PipelineStage, len(py_stages))
    for idx, stage in enumerate(py_stages):
        java_stages[idx] = stage._transfer_stage_to_java()
    return java_stages


@inherit_doc
class PipelineMLWriter(JavaMLWriter, JavaWrapper):
    """
    .. note:: Experimental

    Pipeline utility class that can save ML instances through their Scala implementation.

    .. versionadded:: 2.0.0
    """

    def __init__(self, instance):
        self._java_obj = self._new_java_obj("org.apache.spark.ml.Pipeline", instance.uid)
        jparam = self._java_obj.getParam(instance.stages.name)
        jstages = _stages_py2java(instance.getStages())
        self._java_obj.set(jparam.w(jstages))
        self._jwrite = self._java_obj.write()


@inherit_doc
class PipelineMLReader(JavaMLReader):
    """
    .. note:: Experimental

    Utility class that can load Pipeline instances through their Scala implementation.

    .. versionadded:: 2.0.0
    """

    def load(self, path):
        """Load the Pipeline instance from the input path."""
        if not isinstance(path, basestring):
            raise TypeError("path should be a basestring, got type %s" % type(path))

        sc = SparkContext._active_spark_context
        java_obj = self._jread.load(path)
        instance = self._clazz()
        instance._resetUid(java_obj.uid())
        jparam = java_obj.getParam(instance.stages.name)
        if java_obj.isDefined(jparam):
            java_stages = java_obj.getOrDefault(jparam)
            instance._paramMap[instance.stages] = _stages_java2py(java_stages)

        return instance


@inherit_doc
class Pipeline(Estimator):
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

    >>> from pyspark.ml.feature import HashingTF
    >>> from pyspark.ml.feature import PCA
    >>> df = sqlContext.createDataFrame([(["a", "b", "c"],), (["c", "d", "e"],)], ["words"])
    >>> hashingTF = HashingTF(numFeatures=10, inputCol="words", outputCol="features")
    >>> pca = PCA(k=2, inputCol="features", outputCol="pca_features")
    >>> pl = Pipeline(stages=[hashingTF, pca])
    >>> model = pl.fit(df)
    >>> transformed = model.transform(df)
    >>> transformed.head().words == ["a", "b", "c"]
    True
    >>> transformed.head().features
    SparseVector(10, {7: 1.0, 8: 1.0, 9: 1.0})
    >>> transformed.head().pca_features
    DenseVector([1.0, 0.5774])
    >>> featurePath = temp_path + "/feature-transformer"
    >>> pl.save(featurePath)
    >>> loadedPipeline = Pipeline.load(featurePath)
    >>> loadedPipeline.uid == pl.uid
    True
    >>> len(loadedPipeline.getStages())
    2
    >>> [loadedHT, loadedPCA] = loadedPipeline.getStages()
    >>> type(loadedHT)
    <class 'pyspark.ml.feature.HashingTF'>
    >>> type(loadedPCA)
    <class 'pyspark.ml.feature.PCA'>
    >>> loadedHT.uid == hashingTF.uid
    True
    >>> param = loadedHT.getParam("numFeatures")
    >>> loadedHT.getOrDefault(param) == hashingTF.getOrDefault(param)
    True
    >>> loadedPCA.uid == pca.uid
    True
    >>> loadedPCA.getK() == pca.getK()
    True
    >>> modelPath = temp_path + "/feature-model"
    >>> model.save(modelPath)
    >>> loadedModel = PipelineModel.load(modelPath)
    >>> [hashingTFinModel, pcaModel] = model.stages
    >>> [loadedHTinModel, loadedPCAModel] = loadedModel.stages
    >>> hashingTFinModel.uid == loadedHTinModel.uid
    True
    >>> hashingTFinModel.getOrDefault(param) == loadedHTinModel.getOrDefault(param)
    True
    >>> pcaModel.uid == loadedPCAModel.uid
    True
    >>> pcaModel.pc == loadedPCAModel.pc
    True
    >>> pcaModel.explainedVariance == loadedPCAModel.explainedVariance
    True

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
        """Returns an JavaMLReader instance for this class."""
        return PipelineMLReader(cls)

    @classmethod
    @since("2.0.0")
    def load(cls, path):
        """Reads an ML instance from the input path, a shortcut of `read().load(path)`."""
        return cls.read().load(path)


@inherit_doc
class PipelineModelMLWriter(JavaMLWriter, JavaWrapper):
    """
    .. note:: Experimental

    PipelineModel utility class that can save ML instances through their Scala implementation.

    .. versionadded:: 2.0.0
    """

    def __init__(self, instance):
        self._java_obj = self._new_java_obj("org.apache.spark.ml.PipelineModel",
                                            instance.uid,
                                            _stages_py2java(instance.stages))
        self._jwrite = self._java_obj.write()


@inherit_doc
class PipelineModelMLReader(JavaMLReader):
    """
    .. note:: Experimental

    Utility class that can load PipelineModel instances through their Scala implementation.

    .. versionadded:: 2.0.0
    """

    def load(self, path):
        """Load the PipelineModel instance from the input path."""
        if not isinstance(path, basestring):
            raise TypeError("path should be a basestring, got type %s" % type(path))
        java_obj = self._jread.load(path)
        instance = self._clazz(_stages_java2py(java_obj.stages()))
        instance._resetUid(java_obj.uid())
        return instance


@inherit_doc
class PipelineModel(Model):
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
    @since("2.0.0")
    def load(cls, path):
        """Reads an ML instance from the input path, a shortcut of `read().load(path)`."""
        return cls.read().load(path)


if __name__ == "__main__":
    import doctest
    import tempfile

    import pyspark.ml
    import pyspark.ml.feature
    from pyspark.sql import SQLContext
    globs = pyspark.ml.__dict__.copy()
    globs_feature = pyspark.ml.feature.__dict__.copy()
    globs.update(globs_feature)
    # The small batch size here ensures that we see multiple batches,
    # even in these small test examples:
    sc = SparkContext("local[2]", "ml.pipeline tests")
    sqlContext = SQLContext(sc)
    globs['sc'] = sc
    globs['sqlContext'] = sqlContext
    temp_path = tempfile.mkdtemp()
    globs['temp_path'] = temp_path
    failure_count = False
    try:
        (failure_count, test_count) = doctest.testmod(globs=globs, optionflags=doctest.ELLIPSIS)
        sc.stop()
    finally:
        from shutil import rmtree
        try:
            rmtree(temp_path)
        except OSError:
            pass
    if failure_count:
        exit(-1)
