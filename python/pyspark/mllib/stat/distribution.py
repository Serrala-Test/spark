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

from collections import namedtuple
import sys
try:
    import xmlrunner
except ImportError:
    xmlrunner = None
if sys.version_info[:2] <= (2, 6):
    try:
        import unittest2 as unittest
    except ImportError:
        sys.stderr.write('Please install unittest2 to test with Python 2.6 or earlier')
        sys.exit(1)
else:
    import unittest

__all__ = ['MultivariateGaussian']


class MultivariateGaussian(namedtuple('MultivariateGaussian', ['mu', 'sigma'])):

    """Represents a (mu, sigma) tuple

    >>> m = MultivariateGaussian(Vectors.dense([11,12]),DenseMatrix(2, 2, (1.0, 3.0, 5.0, 2.0)))
    >>> (m.mu, m.sigma.toArray())
    (DenseVector([11.0, 12.0]), array([[ 1., 5.],[ 3., 2.]]))
    >>> (m[0], m[1])
    (DenseVector([11.0, 12.0]), array([[ 1., 5.],[ 3., 2.]]))
    """


def _test():
    import doctest
    from pyspark import SparkContext
    globs = globals().copy()
    globs['sc'] = SparkContext('local[4]', 'PythonTest', batchSize=2)
    t = doctest.DocTestSuite(globs=globs, optionflags=doctest.ELLIPSIS)
    if xmlrunner:
        result = xmlrunner.XMLTestRunner(output='target/test-reports',
                                         verbosity=3).run(t)
    else:
        result = unittest.TextTestRunner(verbosity=3).run(t)
    globs['sc'].stop()
    if not result.wasSuccessful():
        exit(-1)


if __name__ == "__main__":
    _test()
