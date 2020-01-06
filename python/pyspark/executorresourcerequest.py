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


class ExecutorResourceRequest(object):
    """
    .. note:: Evolving

    An Executor resource request. This is used in conjunction with the ResourceProfile to
    programmatically specify the resources needed for an RDD that will be applied at the
    stage level.

    This is used to specify what the resource requirements are for an Executor and how
    Spark can find out specific details about those resources. Not all the parameters are
    required for every resource type. Resources like GPUs are supported and have same limitations
    as using the global spark configs spark.executor.resource.gpu.*. The amount, discoveryScript,
    and vendor parameters for resources are all the same parameters a user would specify through the
    configs: spark.executor.resource.{resourceName}.{amount, discoveryScript, vendor}.

    For instance, a user wants to allocate an Executor with GPU resources on YARN. The user has
    to specify the resource name (gpu), the amount or number of GPUs per Executor,
    the discovery script would be specified so that when the Executor starts up it can
    discovery what GPU addresses are available for it to use because YARN doesn't tell
    Spark that, then vendor would not be used because its specific for Kubernetes.

    See the configuration and cluster specific docs for more details.

    Use ExecutorResourceRequests class as a convenience API.

    :param resourceName: Name of the resource
    :param amount: Amount requesting
    :param discoveryScript: Optional script used to discover the resources. This is required on some
        cluster managers that don't tell Spark the addresses of the resources
        allocated. The script runs on Executors startup to discover the addresses
        of the resources available.
    :param vendor: Vendor, required for some cluster managers
    """

    def __init__(self, resourceName, amount, discoveryScript="", vendor=""):
        """Create a new ExecutorResourceRequest that wraps the underlying JVM object."""
        from pyspark.context import SparkContext
        self._jExecRequest = SparkContext._jvm.org.apache.spark.resource.ExecutorResourceRequest(
            resourceName, amount, discoveryScript, vendor)

    @property
    def resourceName(self):
        return self._jExecRequest.resourceName()

    @property
    def amount(self):
        return self._jExecRequest.amount()

    @property
    def discoveryScript(self):
        return self._jExecRequest.discoveryScript()

    @property
    def vendor(self):
        return self._jExecRequest.vendor()
