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

"""
Python package for Word2Vec in MLlib.
"""

from pyspark.mllib._common import \
    _serialize_double_vector, \
    _deserialize_double_vector, \
    _deserialize_string_seq, \
    _get_unmangled_string_seq_rdd

__all__ = ['Word2Vec', 'Word2VecModel']

class Word2VecModel(object):
    """
    class for Word2Vec model
    """
    def __init__(self, sc, java_model):
        """
        :param sc:  Spark context
        :param java_model:  Handle to Java model object
        """
        self._sc = sc
        self._java_model = java_model

    def __del__(self):
        self._sc._gateway.detach(self._java_model)

    def transform(self, word):
        pythonAPI = self._sc._jvm.PythonMLLibAPI()
        result = pythonAPI.Word2VecModelTransform(self._java_model, word)
        return _deserialize_double_vector(result)

    def findSynonyms(self, x, num):
        pythonAPI = self._sc._jvm.PythonMLLibAPI()
        if type(x) == str:
            result = pythonAPI.Word2VecModelSynonyms(self._java_model, x, num)
        else:
            xSer = _serialize_double_vector(x)
            result = pythonAPI.Word2VecModelSynonyms(self._java_model, xSer, num)
        words = _deserialize_string_seq(result[0])
        similarity = _deserialize_double_vector(result[1])
        return zip(words, similarity)

class Word2Vec(object):
    """
    Word2Vec creates vector representation of words in a text corpus.
    The algorithm first constructs a vocabulary from the corpus
    and then learns vector representation of words in the vocabulary.
    The vector representation can be used as features in
    natural language processing and machine learning algorithms.

    We used skip-gram model in our implementation and hierarchical softmax
    method to train the model. The variable names in the implementation
    matches the original C implementation.
    For original C implementation, see https://code.google.com/p/word2vec/
    For research papers, see
    Efficient Estimation of Word Representations in Vector Space
    and
    Distributed Representations of Words and Phrases and their Compositionality.
    """
    def __init__(self):
        self.vectorSize = 100
        self.startingAlpha = 0.025
        self.numPartitions = 1
        self.numIterations = 1

    def setVectorSize(self, vectorSize):
        self.vectorSize = vectorSize
        return self

    def setLearningRate(self, learningRate):
        self.startingAlpha = learningRate
        return self

    def setNumPartitions(self, numPartitions):
        self.numPartitions = numPartitions
        return self

    def setNumIterations(self, numIterations):
        self.numIterations = numIterations
        return self

    def fit(self, data):
        """
        :param data: Input RDD
        """
        sc = data.context
        dataBytes = _get_unmangled_string_seq_rdd(data)
        model = sc._jvm.PythonMLLibAPI().trainWord2Vec(dataBytes._jrdd)
        return Word2VecModel(sc, model)

def _test():
    import doctest
    from pyspark import SparkContext
    globs = globals().copy()
    globs['sc'] = SparkContext('local[4]', 'PythonTest', batchSize=2)
    (failure_count, test_count) = doctest.testmod(globs=globs, optionflags=doctest.ELLIPSIS)
    globs['sc'].stop()
    if failure_count:
        exit(-1)

if __name__ == "__main__":
    _test()
