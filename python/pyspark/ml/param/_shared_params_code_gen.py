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


def _gen_param_header(name, doc, defaultValueStr, friendlyDefault, typeConverter):
    """
    Generates the header part for shared variables

    :param name: param name
    :param doc: param doc
    :param defaultValueStr: string of the default value (or None for no default)
    :param friendlyDefault: human readable default value, uses defaultValueStr if None
    :param typeConverter: param type converter.
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
        doc += '''(Default $friendlyDefault)'''
        if friendlyDefault is None:
            friendlyDefault = defaultValueStr

    Name = name[0].upper() + name[1:]
    if typeConverter is None:
        typeConverter = str(None)
    return template \
        .replace("$name", name) \
        .replace("$Name", Name) \
        .replace("$doc", doc) \
        .replace("$defaultValueStr", str(defaultValueStr)) \
        .replace("$friendlyDefault", str(friendlyDefault)) \
        .replace("$typeConverter", typeConverter)


def _gen_param_code(name, doc):
    """
    Generates Python code for a shared param class.

    :param name: param name
    :param doc: param doc
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

if __name__ == "__main__":
    print(header)
    print("\n# DO NOT MODIFY THIS FILE! It was generated by _shared_params_code_gen.py.\n")
    print("from pyspark.ml.param import *\n\n")
    shared = [
        ("maxIter", "max number of iterations (>= 0).", None, None, "TypeConverters.toInt"),
        ("regParam", "regularization parameter (>= 0).", None, None, "TypeConverters.toFloat"),
        ("featuresCol", "features column name.", "'features'", None, "TypeConverters.toString"),
        ("labelCol", "label column name.", "'label'", None, "TypeConverters.toString"),
        ("predictionCol", "prediction column name.", "'prediction'", None,
         "TypeConverters.toString"),
        ("probabilityCol", "Column name for predicted class conditional probabilities. " +
         "Note: Not all models output well-calibrated probability estimates! These probabilities " +
         "should be treated as confidences, not precise probabilities.", "'probability'",
         None, "TypeConverters.toString"),
        ("rawPredictionCol", "raw prediction (a.k.a. confidence) column name.", "'rawPrediction'",
         None, "TypeConverters.toString"),
        ("inputCol", "input column name.", None, None, "TypeConverters.toString"),
        ("inputCols", "input column names.", None, None, "TypeConverters.toListString"),
        ("outputCol", "output column name.", "self.uid + '__output'", None,
         "TypeConverters.toString"),
        ("numFeatures", "number of features.", None, None, "TypeConverters.toInt"),
        ("checkpointInterval", "set checkpoint interval (>= 1) or disable checkpoint (-1). " +
         "E.g. 10 means that the cache will get checkpointed every 10 iterations.", None,
         None, "TypeConverters.toInt"),
        ("seed", "random seed.", "hash(type(self).__name__)", "hash of type name",
         "TypeConverters.toInt"),
        ("tol", "the convergence tolerance for iterative algorithms.", None,
         None, "TypeConverters.toFloat"),
        ("stepSize", "Step size to be used for each iteration of optimization.", None,
         None, "TypeConverters.toFloat"),
        ("handleInvalid", "how to handle invalid entries. Options are skip (which will filter " +
         "out rows with bad values), or error (which will throw an errror). More options may be " +
         "added later.", None, None, "TypeConverters.toString"),
        ("elasticNetParam", "the ElasticNet mixing parameter, in range [0, 1]. For alpha = 0, " +
         "the penalty is an L2 penalty. For alpha = 1, it is an L1 penalty.", "0.0",
         None, "TypeConverters.toFloat"),
        ("fitIntercept", "whether to fit an intercept term.", "True", None,
         "TypeConverters.toBoolean"),
        ("standardization", "whether to standardize the training features before fitting the " +
         "model.", "True", None, "TypeConverters.toBoolean"),
        ("thresholds", "Thresholds in multi-class classification to adjust the probability of " +
         "predicting each class. Array must have length equal to the number of classes, with " +
         "values >= 0. The class with largest value p/t is predicted, where p is the original " +
         "probability of that class and t is the class' threshold.", None,
         None, "TypeConverters.toListFloat"),
        ("weightCol", "weight column name. If this is not set or empty, we treat " +
         "all instance weights as 1.0.", None, None, "TypeConverters.toString"),
        ("solver", "the solver algorithm for optimization.", "'auto'", None,
         "TypeConverters.toString"),
        ("varianceCol", "column name for the biased sample variance of prediction.",
         None, None, "TypeConverters.toString")]

    code = []
    for name, doc, defaultValueStr, friendlyDefault, typeConverter in shared:
        param_code = _gen_param_header(name, doc, defaultValueStr, friendlyDefault, typeConverter)
        code.append(param_code + "\n" + _gen_param_code(name, doc))

    decisionTreeParams = [
        ("maxDepth", "Maximum depth of the tree. (>= 0) E.g., depth 0 means 1 leaf node; " +
         "depth 1 means 1 internal node + 2 leaf nodes.", "5", "TypeConverters.toInt"),
        ("maxBins", "Max number of bins for" +
         " discretizing continuous features.  Must be >=2 and >= number of categories for any" +
         " categorical feature.", "32", "TypeConverters.toInt"),
        ("minInstancesPerNode", "Minimum number of instances each child must have after split. " +
         "If a split causes the left or right child to have fewer than minInstancesPerNode, the " +
         "split will be discarded as invalid. Should be >= 1.", "1", "TypeConverters.toInt"),
        ("minInfoGain", "Minimum information gain for a split to be considered at a tree node.",
         "0.0", "TypeConverters.toFloat"),
        ("maxMemoryInMB", "Maximum memory in MB allocated to histogram aggregation. If too small," +
         " then 1 node will be split per iteration, and its aggregates may exceed this size.",
         "256", "TypeConverters.toInt"),
        ("cacheNodeIds", "If false, the algorithm will pass trees to executors to match " +
         "instances with nodes. If true, the algorithm will cache node IDs for each instance. " +
         "Caching can speed up training of deeper trees. Users can set how often should the " +
         "cache be checkpointed or disable it by setting checkpointInterval.", "False",
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
    for name, doc, defaultValueStr, typeConverterStr in decisionTreeParams:
        dtCode = decisionTreeCode
        if defaultValueStr is not None:
            dtCode += '''\n        self._setDefault($name=$defaultValueStr)'''
            dtCode = dtCode.replace("$name", name).replace("$defaultValueStr", defaultValueStr)
            doc += "(Default $defaultValueStr)"
            doc = doc.replace("$defaultValueStr", defaultValueStr)
        if typeConverterStr is None:
            typeConverterStr = str(None)
        variable = paramTemplate.replace("$name", name).replace("$doc", doc) \
            .replace("$typeConverterStr", typeConverterStr)
        dummyPlaceholders += variable.replace("$owner", "Params._dummy()") + "\n    "
        dtParamMethods += _gen_param_code(name, doc) + "\n"
    code.append(dtCode.replace("$dummyPlaceHolders", dummyPlaceholders) + "\n" +
                dtParamMethods)
    print("\n\n\n".join(code))
