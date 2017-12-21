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

from __future__ import print_function

header = """#
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
#"""

# Code generator for shared params (shared.py). Run under this folder with:
# python _shared_params_code_gen.py > shared.py


def _gen_param_header(name, doc, defaultValueStr, typeConverter):
    """
    Generates the header part for shared variables

    :param name: param name
    :param doc: param doc
    """
    template = '''class Has$Name(Params):
    """
    Mixin for param $name: $doc
    """

    $name = Param(Params._dummy(), "$name", "$doc", typeConverter=$typeConverter)

    def __init__(self):
        super(Has$Name, self).__init__()'''

    if defaultValueStr is not None:
        template += '''
        self._setDefault($name=$defaultValueStr)'''

    Name = name[0].upper() + name[1:]
    if typeConverter is None:
        typeConverter = str(None)
    return template \
        .replace("$name", name) \
        .replace("$Name", Name) \
        .replace("$doc", doc) \
        .replace("$defaultValueStr", str(defaultValueStr)) \
        .replace("$typeConverter", typeConverter)


def _gen_param_code(name, doc, defaultValueStr):
    """
    Generates Python code for a shared param class.

    :param name: param name
    :param doc: param doc
    :param defaultValueStr: string representation of the default value
    :return: code string
    """
    # TODO: How to correctly inherit instance attributes?
    template = '''
    def set$Name(self, value):
        """
        Sets the value of :py:attr:`$name`.
        """
        return self._set($name=value)

    def get$Name(self):
        """
        Gets the value of $name or its default value.
        """
        return self.getOrDefault(self.$name)'''

    Name = name[0].upper() + name[1:]
    return template \
        .replace("$name", name) \
        .replace("$Name", Name) \
        .replace("$doc", doc) \
        .replace("$defaultValueStr", str(defaultValueStr))

if __name__ == "__main__":
    print(header)
    print("\n# DO NOT MODIFY THIS FILE! It was generated by _shared_params_code_gen.py.\n")
    print("from pyspark.ml.param import *\n\n")
    shared = [
        ("maxIter", "max number of iterations (>= 0).", None, "TypeConverters.toInt"),
        ("regParam", "regularization parameter (>= 0).", None, "TypeConverters.toFloat"),
        ("featuresCol", "features column name.", "'features'", "TypeConverters.toString"),
        ("labelCol", "label column name.", "'label'", "TypeConverters.toString"),
        ("predictionCol", "prediction column name.", "'prediction'", "TypeConverters.toString"),
        ("probabilityCol", "Column name for predicted class conditional probabilities. " +
         "Note: Not all models output well-calibrated probability estimates! These probabilities " +
         "should be treated as confidences, not precise probabilities.", "'probability'",
         "TypeConverters.toString"),
        ("rawPredictionCol", "raw prediction (a.k.a. confidence) column name.", "'rawPrediction'",
         "TypeConverters.toString"),
        ("inputCol", "input column name.", None, "TypeConverters.toString"),
        ("inputCols", "input column names.", None, "TypeConverters.toListString"),
        ("outputCol", "output column name.", "self.uid + '__output'", "TypeConverters.toString"),
        ("numFeatures", "number of features.", None, "TypeConverters.toInt"),
        ("checkpointInterval", "set checkpoint interval (>= 1) or disable checkpoint (-1). " +
         "E.g. 10 means that the cache will get checkpointed every 10 iterations.", None,
         "TypeConverters.toInt"),
        ("seed", "random seed.", "hash(type(self).__name__)", "TypeConverters.toInt"),
        ("tol", "the convergence tolerance for iterative algorithms (>= 0).", None,
         "TypeConverters.toFloat"),
        ("stepSize", "Step size to be used for each iteration of optimization (>= 0).", None,
         "TypeConverters.toFloat"),
        ("handleInvalid", "how to handle invalid entries. Options are skip (which will filter " +
         "out rows with bad values), or error (which will throw an error). More options may be " +
         "added later.", None, "TypeConverters.toString"),
        ("elasticNetParam", "the ElasticNet mixing parameter, in range [0, 1]. For alpha = 0, " +
         "the penalty is an L2 penalty. For alpha = 1, it is an L1 penalty.", "0.0",
         "TypeConverters.toFloat"),
        ("fitIntercept", "whether to fit an intercept term.", "True", "TypeConverters.toBoolean"),
        ("standardization", "whether to standardize the training features before fitting the " +
         "model.", "True", "TypeConverters.toBoolean"),
        ("thresholds", "Thresholds in multi-class classification to adjust the probability of " +
         "predicting each class. Array must have length equal to the number of classes, with " +
         "values > 0, excepting that at most one value may be 0. " +
         "The class with largest value p/t is predicted, where p is the original " +
         "probability of that class and t is the class's threshold.", None,
         "TypeConverters.toListFloat"),
        ("threshold", "threshold in binary classification prediction, in range [0, 1]",
         "0.5", "TypeConverters.toFloat"),
        ("weightCol", "weight column name. If this is not set or empty, we treat " +
         "all instance weights as 1.0.", None, "TypeConverters.toString"),
        ("solver", "the solver algorithm for optimization. If this is not set or empty, " +
         "default value is 'auto'.", "'auto'", "TypeConverters.toString"),
        ("varianceCol", "column name for the biased sample variance of prediction.",
         None, "TypeConverters.toString"),
        ("aggregationDepth", "suggested depth for treeAggregate (>= 2).", "2",
         "TypeConverters.toInt"),
        ("parallelism", "the number of threads to use when running parallel algorithms (>= 1).",
         "1", "TypeConverters.toInt"),
        ("loss", "the loss function to be optimized.", None, "TypeConverters.toString")]

    code = []
    for name, doc, defaultValueStr, typeConverter in shared:
        param_code = _gen_param_header(name, doc, defaultValueStr, typeConverter)
        code.append(param_code + "\n" + _gen_param_code(name, doc, defaultValueStr))

    decisionTreeParams = [
        ("maxDepth", "Maximum depth of the tree. (>= 0) E.g., depth 0 means 1 leaf node; " +
         "depth 1 means 1 internal node + 2 leaf nodes.", "TypeConverters.toInt"),
        ("maxBins", "Max number of bins for" +
         " discretizing continuous features.  Must be >=2 and >= number of categories for any" +
         " categorical feature.", "TypeConverters.toInt"),
        ("minInstancesPerNode", "Minimum number of instances each child must have after split. " +
         "If a split causes the left or right child to have fewer than minInstancesPerNode, the " +
         "split will be discarded as invalid. Should be >= 1.", "TypeConverters.toInt"),
        ("minInfoGain", "Minimum information gain for a split to be considered at a tree node.",
         "TypeConverters.toFloat"),
        ("maxMemoryInMB", "Maximum memory in MB allocated to histogram aggregation. If too small," +
         " then 1 node will be split per iteration, and its aggregates may exceed this size.",
         "TypeConverters.toInt"),
        ("cacheNodeIds", "If false, the algorithm will pass trees to executors to match " +
         "instances with nodes. If true, the algorithm will cache node IDs for each instance. " +
         "Caching can speed up training of deeper trees. Users can set how often should the " +
         "cache be checkpointed or disable it by setting checkpointInterval.",
         "TypeConverters.toBoolean")]

    decisionTreeCode = '''class DecisionTreeParams(Params):
    """
    Mixin for Decision Tree parameters.
    """

    $dummyPlaceHolders

    def __init__(self):
        super(DecisionTreeParams, self).__init__()'''
    dtParamMethods = ""
    dummyPlaceholders = ""
    paramTemplate = """$name = Param($owner, "$name", "$doc", typeConverter=$typeConverterStr)"""
    for name, doc, typeConverterStr in decisionTreeParams:
        if typeConverterStr is None:
            typeConverterStr = str(None)
        variable = paramTemplate.replace("$name", name).replace("$doc", doc) \
            .replace("$typeConverterStr", typeConverterStr)
        dummyPlaceholders += variable.replace("$owner", "Params._dummy()") + "\n    "
        dtParamMethods += _gen_param_code(name, doc, None) + "\n"
    code.append(decisionTreeCode.replace("$dummyPlaceHolders", dummyPlaceholders) + "\n" +
                dtParamMethods)
    print("\n\n\n".join(code))
