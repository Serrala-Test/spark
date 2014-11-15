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

from pyspark import SparkContext, RDD
from pyspark.mllib.common import callMLlibFunc, JavaModelWrapper
from pyspark.mllib.linalg import _convert_to_vector
from pyspark.mllib.regression import LabeledPoint

__all__ = ['DecisionTreeModel', 'DecisionTree']


class DecisionTreeModel(JavaModelWrapper):

    """
    A decision tree model for classification or regression.

    EXPERIMENTAL: This is an experimental API.
                  It will probably be modified in future.
    """
    def predict(self, x):
        """
        Predict the label of one or more examples.

        :param x:  Data point (feature vector),
                   or an RDD of data points (feature vectors).
        """
        if isinstance(x, RDD):
            return self.call("predict", x.map(_convert_to_vector))

        else:
            return self.call("predict", _convert_to_vector(x))

    def numNodes(self):
        return self._java_model.numNodes()

    def depth(self):
        return self._java_model.depth()

    def __repr__(self):
        """ Print summary of model. """
        return self._java_model.toString()

    def toDebugString(self):
        """ Print full model. """
        return self._java_model.toDebugString()


class DecisionTree(object):

    """
    Learning algorithm for a decision tree model
    for classification or regression.

    EXPERIMENTAL: This is an experimental API.
                  It will probably be modified for Spark v1.2.

    """

    @staticmethod
    def _train(data, type, numClasses, features, impurity="gini", maxDepth=5, maxBins=32,
               minInstancesPerNode=1, minInfoGain=0.0):
        first = data.first()
        assert isinstance(first, LabeledPoint), "the data should be RDD of LabeledPoint"
        model = callMLlibFunc("trainDecisionTreeModel", data, type, numClasses, features,
                              impurity, maxDepth, maxBins, minInstancesPerNode, minInfoGain)
        return DecisionTreeModel(model)

    @staticmethod
    def trainClassifier(data, numClasses, categoricalFeaturesInfo,
                        impurity="gini", maxDepth=5, maxBins=32, minInstancesPerNode=1,
                        minInfoGain=0.0):
        """
        Train a DecisionTreeModel for classification.

        :param data: Training data: RDD of LabeledPoint.
                     Labels are integers {0,1,...,numClasses}.
        :param numClasses: Number of classes for classification.
        :param categoricalFeaturesInfo: Map from categorical feature index
                                        to number of categories.
                                        Any feature not in this map
                                        is treated as continuous.
        :param impurity: Supported values: "entropy" or "gini"
        :param maxDepth: Max depth of tree.
                         E.g., depth 0 means 1 leaf node.
                         Depth 1 means 1 internal node + 2 leaf nodes.
        :param maxBins: Number of bins used for finding splits at each node.
        :param minInstancesPerNode: Min number of instances required at child nodes to create
                                    the parent split
        :param minInfoGain: Min info gain required to create a split
        :return: DecisionTreeModel

        Example usage:

        >>> from numpy import array
        >>> from pyspark.mllib.regression import LabeledPoint
        >>> from pyspark.mllib.tree import DecisionTree
        >>>
        >>> data = [
        ...     LabeledPoint(0.0, [0.0]),
        ...     LabeledPoint(1.0, [1.0]),
        ...     LabeledPoint(1.0, [2.0]),
        ...     LabeledPoint(1.0, [3.0])
        ... ]
        >>> model = DecisionTree.trainClassifier(sc.parallelize(data), 2, {})
        >>> print model,  # it already has newline
        DecisionTreeModel classifier of depth 1 with 3 nodes
        >>> print model.toDebugString(),  # it already has newline
        DecisionTreeModel classifier of depth 1 with 3 nodes
          If (feature 0 <= 0.0)
           Predict: 0.0
          Else (feature 0 > 0.0)
           Predict: 1.0
        >>> model.predict(array([1.0]))
        1.0
        >>> model.predict(array([0.0]))
        0.0
        >>> rdd = sc.parallelize([[1.0], [0.0]])
        >>> model.predict(rdd).collect()
        [1.0, 0.0]
        """
        return DecisionTree._train(data, "classification", numClasses, categoricalFeaturesInfo,
                                   impurity, maxDepth, maxBins, minInstancesPerNode, minInfoGain)

    @staticmethod
    def trainRegressor(data, categoricalFeaturesInfo,
                       impurity="variance", maxDepth=5, maxBins=32, minInstancesPerNode=1,
                       minInfoGain=0.0):
        """
        Train a DecisionTreeModel for regression.

        :param data: Training data: RDD of LabeledPoint.
                     Labels are real numbers.
        :param categoricalFeaturesInfo: Map from categorical feature index
                                        to number of categories.
                                        Any feature not in this map
                                        is treated as continuous.
        :param impurity: Supported values: "variance"
        :param maxDepth: Max depth of tree.
                         E.g., depth 0 means 1 leaf node.
                         Depth 1 means 1 internal node + 2 leaf nodes.
        :param maxBins: Number of bins used for finding splits at each node.
        :param minInstancesPerNode: Min number of instances required at child nodes to create
                                    the parent split
        :param minInfoGain: Min info gain required to create a split
        :return: DecisionTreeModel

        Example usage:

        >>> from numpy import array
        >>> from pyspark.mllib.regression import LabeledPoint
        >>> from pyspark.mllib.tree import DecisionTree
        >>> from pyspark.mllib.linalg import SparseVector
        >>>
        >>> sparse_data = [
        ...     LabeledPoint(0.0, SparseVector(2, {0: 0.0})),
        ...     LabeledPoint(1.0, SparseVector(2, {1: 1.0})),
        ...     LabeledPoint(0.0, SparseVector(2, {0: 0.0})),
        ...     LabeledPoint(1.0, SparseVector(2, {1: 2.0}))
        ... ]
        >>>
        >>> model = DecisionTree.trainRegressor(sc.parallelize(sparse_data), {})
        >>> model.predict(SparseVector(2, {1: 1.0}))
        1.0
        >>> model.predict(SparseVector(2, {1: 0.0}))
        0.0
        >>> rdd = sc.parallelize([[0.0, 1.0], [0.0, 0.0]])
        >>> model.predict(rdd).collect()
        [1.0, 0.0]
        """
        return DecisionTree._train(data, "regression", 0, categoricalFeaturesInfo,
                                   impurity, maxDepth, maxBins, minInstancesPerNode, minInfoGain)


def _test():
    import doctest
    globs = globals().copy()
    globs['sc'] = SparkContext('local[4]', 'PythonTest', batchSize=2)
    (failure_count, test_count) = doctest.testmod(globs=globs, optionflags=doctest.ELLIPSIS)
    globs['sc'].stop()
    if failure_count:
        exit(-1)

if __name__ == "__main__":
    _test()
