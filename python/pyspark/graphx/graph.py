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
Python bindings for GraphX.
"""
from pyspark import PickleSerializer, RDD, StorageLevel
from pyspark.graphx import VertexRDD, EdgeRDD

from pyspark.graphx.partitionstrategy import PartitionStrategy
from pyspark.rdd import PipelinedRDD
from pyspark.serializers import BatchedSerializer

__all__ = ["Graph"]

class Graph(object):
    def __init__(self, vertex_jrdd, edge_jrdd, partition_strategy=PartitionStrategy.EdgePartition1D):
        self._vertex_jrdd = VertexRDD(vertex_jrdd, vertex_jrdd.context, BatchedSerializer(PickleSerializer()))
        self._edge_jrdd = EdgeRDD(edge_jrdd, edge_jrdd.context, BatchedSerializer(PickleSerializer()))
        self._partition_strategy = partition_strategy

    def persist(self, storageLevel):
        self._vertex_jrdd.persist(storageLevel)
        self._edge_jrdd.persist(storageLevel)
        return

    def cache(self):
        self._vertex_jrdd.cache()
        self._edge_jrdd.cache()
        return

    def vertices(self):
        return self._vertex_jrdd

    def edges(self):
        return self._edge_jrdd

    def partitionBy(self, partitionStrategy):

        return

    def subgraph(self, condition):
        return

    def pagerank(self, num_iterations, reset_probability = 0.15):
        """
        Pagerank on the graph depends on valid vertex and edge RDDs
        Users can specify terminating conditions as number of
        iterations or the Random reset probability or alpha

        :param num_iterations:    Number of iterations for the
                                  algorithm to terminate
        :param reset_probability: Random reset probability
        :return:
        """

        return

    def connected_components(self):
        return

    def reverse(self):
        return

    def apply(self, f):

        return


