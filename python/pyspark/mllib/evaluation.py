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

from pyspark.mllib.common import JavaModelWrapper
from pyspark.sql import SQLContext
from pyspark.sql.types import StructField, StructType, DoubleType


class BinaryClassificationMetrics(JavaModelWrapper):
    """
    Evaluator for binary classification.

    >>> scoreAndLabels = sc.parallelize([
    ...     (0.1, 0.0), (0.1, 1.0), (0.4, 0.0), (0.6, 0.0), (0.6, 1.0), (0.6, 1.0), (0.8, 1.0)], 2)
    >>> metrics = BinaryClassificationMetrics(scoreAndLabels)
    >>> metrics.areaUnderROC
    0.70...
    >>> metrics.areaUnderPR
    0.83...
    >>> metrics.unpersist()
    """

    def __init__(self, scoreAndLabels):
        """
        :param scoreAndLabels: an RDD of (score, label) pairs
        """
        sc = scoreAndLabels.ctx
        sql_ctx = SQLContext(sc)
        df = sql_ctx.createDataFrame(scoreAndLabels, schema=StructType([
            StructField("score", DoubleType(), nullable=False),
            StructField("label", DoubleType(), nullable=False)]))
        java_class = sc._jvm.org.apache.spark.mllib.evaluation.BinaryClassificationMetrics
        java_model = java_class(df._jdf)
        super(BinaryClassificationMetrics, self).__init__(java_model)

    @property
    def areaUnderROC(self):
        """
        Computes the area under the receiver operating characteristic
        (ROC) curve.
        """
        return self.call("areaUnderROC")

    @property
    def areaUnderPR(self):
        """
        Computes the area under the precision-recall curve.
        """
        return self.call("areaUnderPR")

    def unpersist(self):
        """
        Unpersists intermediate RDDs used in the computation.
        """
        self.call("unpersist")


class RegressionMetrics(JavaModelWrapper):
    """
    Evaluator for regression.

    >>> predictionAndObservations = sc.parallelize([
    ...     (2.5, 3.0), (0.0, -0.5), (2.0, 2.0), (8.0, 7.0)])
    >>> metrics = RegressionMetrics(predictionAndObservations)
    >>> metrics.explainedVariance
    0.95...
    >>> metrics.meanAbsoluteError
    0.5...
    >>> metrics.meanSquaredError
    0.37...
    >>> metrics.rootMeanSquaredError
    0.61...
    >>> metrics.r2
    0.94...
    """

    def __init__(self, predictionAndObservations):
        """
        :param predictionAndObservations: an RDD of (prediction, observation) pairs.
        """
        sc = predictionAndObservations.ctx
        sql_ctx = SQLContext(sc)
        df = sql_ctx.createDataFrame(predictionAndObservations, schema=StructType([
            StructField("prediction", DoubleType(), nullable=False),
            StructField("observation", DoubleType(), nullable=False)]))
        java_class = sc._jvm.org.apache.spark.mllib.evaluation.RegressionMetrics
        java_model = java_class(df._jdf)
        super(RegressionMetrics, self).__init__(java_model)

    @property
    def explainedVariance(self):
        """
        Returns the explained variance regression score.
        explainedVariance = 1 - variance(y - \hat{y}) / variance(y)
        """
        return self.call("explainedVariance")

    @property
    def meanAbsoluteError(self):
        """
        Returns the mean absolute error, which is a risk function corresponding to the
        expected value of the absolute error loss or l1-norm loss.
        """
        return self.call("meanAbsoluteError")

    @property
    def meanSquaredError(self):
        """
        Returns the mean squared error, which is a risk function corresponding to the
        expected value of the squared error loss or quadratic loss.
        """
        return self.call("meanSquaredError")

    @property
    def rootMeanSquaredError(self):
        """
        Returns the root mean squared error, which is defined as the square root of
        the mean squared error.
        """
        return self.call("rootMeanSquaredError")

    @property
    def r2(self):
        """
        Returns R^2^, the coefficient of determination.
        """
        return self.call("r2")


class MulticlassMetrics(JavaModelWrapper):
    """
    Evaluator for multiclass classification.

    >>> predictionAndLabels = sc.parallelize([(0.0, 0.0), (0.0, 1.0), (0.0, 0.0),
    ...     (1.0, 0.0), (1.0, 1.0), (1.0, 1.0), (1.0, 1.0), (2.0, 2.0), (2.0, 0.0)])
    >>> metrics = MulticlassMetrics(predictionAndLabels)
    >>> metrics.falsePositiveRate(0.0)
    0.2
    >>> metrics.precision(1.0)
    0.75
    >>> metrics.recall(2.0)
    1.0
    >>> metrics.fMeasure(0.0, 2.0)
    0.52...
    >>> metrics.precision()
    0.66...
    >>> metrics.recall()
    0.66...
    >>> metrics.weightedFalsePositiveRate
    0.19...
    >>> metrics.weightedPrecision
    0.68...
    >>> metrics.weightedRecall
    0.66...
    >>> metrics.weightedFMeasure()
    0.66...
    """

    def __init__(self, predictionAndLabels):
        """
        :param predictionAndLabels an RDD of (prediction, label) pairs.
        """
        sc = predictionAndLabels.ctx
        sql_ctx = SQLContext(sc)
        df = sql_ctx.createDataFrame(predictionAndLabels, schema=StructType([
            StructField("prediction", DoubleType(), nullable=False),
            StructField("label", DoubleType(), nullable=False)]))
        java_class = sc._jvm.org.apache.spark.mllib.evaluation.MulticlassMetrics
        java_model = java_class(df._jdf)
        super(MulticlassMetrics, self).__init__(java_model)

    def truePositiveRate(self, label):
        """
        Returns true positive rate for a given label (category).
        """
        return self.call("truePositiveRate", label)

    def falsePositiveRate(self, label):
        """
        Returns false positive rate for a given label (category).
        """
        return self.call("falsePositiveRate", label)

    def precision(self, label=None):
        """
        Returns precision or precision for a given label (category) if specified.
        """
        if label is None:
            return self.call("precision")
        else:
            return self.call("precision", float(label))

    def recall(self, label=None):
        """
        Returns recall or recall for a given label (category) if specified.
        """
        if label is None:
            return self.call("recall")
        else:
            return self.call("recall", float(label))

    def fMeasure(self, label=None, beta=None):
        """
        Returns f-measure or f-measure for a given label (category) if specified.
        """
        if beta is None:
            if label is None:
                return self.call("fMeasure")
            else:
                return self.call("fMeasure", label)
        else:
            if label is None:
                raise Exception("If the beta parameter is specified, label can not be none")
            else:
                return self.call("fMeasure", label, beta)

    @property
    def weightedTruePositiveRate(self):
        """
        Returns weighted true positive rate.
        (equals to precision, recall and f-measure)
        """
        return self.call("weightedTruePositiveRate")

    @property
    def weightedFalsePositiveRate(self):
        """
        Returns weighted false positive rate.
        """
        return self.call("weightedFalsePositiveRate")

    @property
    def weightedRecall(self):
        """
        Returns weighted averaged recall.
        (equals to precision, recall and f-measure)
        """
        return self.call("weightedRecall")

    @property
    def weightedPrecision(self):
        """
        Returns weighted averaged precision.
        """
        return self.call("weightedPrecision")

    def weightedFMeasure(self, beta=None):
        """
        Returns weighted averaged f-measure.
        """
        if beta is None:
            return self.call("weightedFMeasure")
        else:
            return self.call("weightedFMeasure", beta)


def _test():
    import doctest
    from pyspark import SparkContext
    import pyspark.mllib.evaluation
    globs = pyspark.mllib.evaluation.__dict__.copy()
    globs['sc'] = SparkContext('local[4]', 'PythonTest')
    (failure_count, test_count) = doctest.testmod(globs=globs, optionflags=doctest.ELLIPSIS)
    globs['sc'].stop()
    if failure_count:
        exit(-1)


if __name__ == "__main__":
    _test()
