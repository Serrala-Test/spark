..  Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

..    http://www.apache.org/licenses/LICENSE-2.0

..  Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.

.. PySpark documentation master file

=================
PySpark Overview
=================

**Date**: |today| **Version**: |release|

**Useful links**:
|binder|_ | `GitHub <https://github.com/apache/spark>`_ | `Issues <https://issues.apache.org/jira/projects/SPARK/issues>`_ | |examples|_ | `Community <https://spark.apache.org/community.html>`_ | `Stack Overflow <https://stackoverflow.com/questions/tagged/pyspark>`_ | `Dev Mailing List <https://lists.apache.org/list.html?dev@spark.apache.org>`_ | `User Mailing List <https://lists.apache.org/list.html?user@spark.apache.org>`_

PySpark is the Python API for Apache Spark. It enables you to perform real-time,
large-scale data processing in a distributed environment using Python. It also provides a PySpark
shell for interactively analyzing your data.

PySpark combines Python's learnability and ease of use with the power of Apache Spark
to enable processing and analysis of data at any size for everyone familiar with Python.

PySpark supports all of Spark's features such as Spark SQL,
DataFrames, Structured Streaming, Machine Learning (MLlib) and Spark Core.

.. list-table::
   :widths: 10 20 20 20 20 10
   :header-rows: 0
   :class: borderless spec_table

   * -
     - .. image:: ../../../docs/img/pyspark-dataframe_api.png
          :target: reference/pyspark.sql/index.html
          :width: 100%
          :alt: DataFrame API
     - .. image:: ../../../docs/img/pyspark-pandas_api_on_spark.png
          :target: reference/pyspark.pandas/index.html
          :width: 100%
          :alt: Pandas API on Spark
     - .. image:: ../../../docs/img/pyspark-structured_streaming.png
          :target: reference/pyspark.ss/index.html
          :width: 100%
          :alt: Streaming
     - .. image:: ../../../docs/img/pyspark-machine_learning.png
          :target: reference/pyspark.ml.html
          :width: 100%
          :alt: Machine Learning
     -

.. list-table::
   :widths: 10 80 10
   :header-rows: 0
   :class: borderless spec_table

   * -
     - .. image:: ../../../docs/img/pyspark-spark_core_and_rdds.png
          :target: reference/pyspark.html
          :width: 100%
          :alt: Spark Core and RDDs
     -

.. _Index Page - DataFrame API:

**DataFrame API**

The DataFrame API is a core module of Apache Spark, optimized for working with structured data.
This API enables seamless integration of SQL queries with Spark programs.
Leveraging PySpark DataFrames, you can efficiently read, write, transform, and analyze data using both Python and SQL.
Whether you opt for Python or SQL, the same underlying execution engine is utilized,
ensuring full exploitation of Spark's capabilities.
This approach empowers robust and efficient data processing and analysis, offering compatibility with a wide range of data sources.

- :ref:`/getting_started/quickstart_df.ipynb`
- |binder_df|_
- :ref:`DataFrame API Reference</reference/pyspark.dataframe/index.rst>`

**Pandas API on Spark**

Pandas API on Spark allows you to scale your pandas workload to any size
by running it distributed across multiple nodes. If you are already familiar
with pandas and want to leverage Spark for big data, pandas API on Spark makes
you immediately productive and lets you migrate your applications without modifying the code.
You can have a single codebase that works both with pandas (tests, smaller datasets)
and with Spark (production, distributed datasets) and you can switch between the
pandas API and the Pandas API on Spark easily and without overhead.

Pandas API on Spark aims to make the transition from pandas to Spark easy but
if you are new to Spark or deciding which API to use, we recommend using PySpark
(see :ref:`DataFrame API <Index Page - DataFrame API>`).

- :ref:`/getting_started/quickstart_ps.ipynb`
- |binder_ps|_
- :ref:`Pandas API on Spark Reference</reference/pyspark.pandas/index.rst>`

.. _Index Page - Structured Streaming:

**Structured Streaming**

Structured Streaming is a scalable and fault-tolerant stream processing engine built on the Spark SQL engine.
You can express your streaming computation the same way you would express a batch computation on static data.
The Spark SQL engine will take care of running it incrementally and continuously and updating the final result
as streaming data continues to arrive.

- `Structured Streaming Programming Guide <https://spark.apache.org/docs/latest/structured-streaming-programming-guide.html>`_
- :ref:`Structured Streaming API Reference</reference/pyspark.ss/index.rst>`

**Machine Learning (MLlib)**

Built on top of Spark, MLlib is a scalable machine learning library that provides
a uniform set of high-level APIs that help users create and tune practical machine
learning pipelines.

- `Machine Learning Library (MLlib) Programming Guide <https://spark.apache.org/docs/latest/ml-guide.html>`_
- :ref:`Machine Learning (MLlib) API Reference</reference/pyspark.ml.rst>`

**Spark Core and RDDs**

Spark Core is the underlying general execution engine for the Spark platform that all
other functionality is built on top of. It provides RDDs (Resilient Distributed Datasets)
and in-memory computing capabilities.

Note that the RDD API is a low-level API which can be difficult to use and you do not get
the benefit of Spark's automatic query optimization capabilities.
We recommend using DataFrames (see :ref:`DataFrame API <Index Page - DataFrame API>` above)
instead of RDDs as it allows you to express what you want more easily and lets Spark automatically
construct the most efficient query for you.

- :ref:`Spark Core API Reference</reference/pyspark.rst>`

**Spark Streaming (Legacy)**

Spark Streaming is an extension of the core Spark API that enables scalable,
high-throughput, fault-tolerant stream processing of live data streams.

Note that Spark Streaming is the previous generation of Spark's streaming engine.
It is a legacy project and it is no longer being updated.
There is a newer and easier to use streaming engine in Spark called
:ref:`Structured Streaming <Index Page - Structured Streaming>` which you
should use for your streaming applications and pipelines.

- `Spark Streaming Programming Guide (Legacy) <https://spark.apache.org/docs/latest/streaming-programming-guide.html>`_
- :ref:`Spark Streaming API Reference (Legacy)</reference/pyspark.streaming.rst>`

.. toctree::
    :maxdepth: 2
    :hidden:

    Overview <self>
    getting_started/index
    user_guide/index
    reference/index
    development/index
    migration_guide/index
