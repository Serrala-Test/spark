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

from typing import Any, Callable, List, Optional, Type, TYPE_CHECKING, cast

import cProfile
import pstats
import os
import atexit
import sys

from pyspark.accumulators import AccumulatorParam

if TYPE_CHECKING:
    from pyspark.context import SparkContext


class ProfilerCollector:
    """
    This class keeps track of different profilers on a per
    stage/UDF basis. Also this is used to create new profilers for
    the different stages/UDFs.
    """

    def __init__(
        self,
        profiler_cls: Type["Profiler"],
        udf_profiler_cls: Type["Profiler"],
        dump_path: Optional[str] = None,
    ):
        self.profiler_cls: Type[Profiler] = profiler_cls
        self.udf_profiler_cls: Type[Profiler] = udf_profiler_cls
        self.profile_dump_path: Optional[str] = dump_path
        self.profilers: List[List[Any]] = []

    def new_profiler(self, ctx: "SparkContext") -> "Profiler":
        """Create a new profiler using class `profiler_cls`"""
        return self.profiler_cls(ctx)

    def new_udf_profiler(self, ctx: "SparkContext") -> "Profiler":
        """Create a new profiler using class `udf_profiler_cls`"""
        return self.udf_profiler_cls(ctx)

    def add_profiler(self, id: int, profiler: "Profiler") -> None:
        """Add a profiler for RDD/UDF `id`"""
        if not self.profilers:
            if self.profile_dump_path:
                atexit.register(self.dump_profiles, self.profile_dump_path)
            else:
                atexit.register(self.show_profiles)

        self.profilers.append([id, profiler, False])

    def dump_profiles(self, path: str) -> None:
        """Dump the profile stats into directory `path`"""
        for id, profiler, _ in self.profilers:
            profiler.dump(id, path)
        self.profilers = []

    def show_profiles(self) -> None:
        """Print the profile stats to stdout"""
        for i, (id, profiler, showed) in enumerate(self.profilers):
            if not showed and profiler:
                profiler.show(id)
                # mark it as showed
                self.profilers[i][2] = True


class Profiler:
    """
    PySpark supports custom profilers, this is to allow for different profilers to
    be used as well as outputting to different formats than what is provided in the
    BasicProfiler.

    A custom profiler has to define or inherit the following methods:
        profile - will produce a system profile of some sort.
        stats - return the collected stats.
        dump - dumps the profiles to a path
        add - adds a profile to the existing accumulated profile

    The profiler class is chosen when creating a SparkContext

    Examples
    --------
    >>> from pyspark import SparkConf, SparkContext
    >>> from pyspark import BasicProfiler
    >>> class MyCustomProfiler(BasicProfiler):
    ...     def show(self, id):
    ...         print("My custom profiles for RDD:%s" % id)
    ...
    >>> conf = SparkConf().set("spark.python.profile", "true")
    >>> sc = SparkContext('local', 'test', conf=conf, profiler_cls=MyCustomProfiler)
    >>> sc.parallelize(range(1000)).map(lambda x: 2 * x).take(10)
    [0, 2, 4, 6, 8, 10, 12, 14, 16, 18]
    >>> sc.parallelize(range(1000)).count()
    1000
    >>> sc.show_profiles()
    My custom profiles for RDD:1
    My custom profiles for RDD:3
    >>> sc.stop()

    Notes
    -----
    This API is a developer API.
    """

    def __init__(self, ctx: "SparkContext") -> None:
        pass

    def profile(self, func: Callable[..., Any], *args: Any, **kwargs: Any) -> Any:
        """Do profiling on the function `func`"""
        raise NotImplementedError

    def stats(self) -> pstats.Stats:
        """Return the collected profiling stats (pstats.Stats)"""
        raise NotImplementedError

    def show(self, id: int) -> None:
        """Print the profile stats to stdout, id is the RDD id"""
        stats = self.stats()
        if stats:
            print("=" * 60)
            print("Profile of RDD<id=%d>" % id)
            print("=" * 60)
            stats.sort_stats("time", "cumulative").print_stats()

    def dump(self, id: int, path: str) -> None:
        """Dump the profile into path, id is the RDD id"""
        if not os.path.exists(path):
            os.makedirs(path)
        stats = self.stats()
        if stats:
            p = os.path.join(path, "rdd_%d.pstats" % id)
            stats.dump_stats(p)


class PStatsParam(AccumulatorParam[Optional[pstats.Stats]]):
    """PStatsParam is used to merge pstats.Stats"""

    @staticmethod
    def zero(value: Optional[pstats.Stats]) -> None:
        return None

    @staticmethod
    def addInPlace(
        value1: Optional[pstats.Stats], value2: Optional[pstats.Stats]
    ) -> Optional[pstats.Stats]:
        if value1 is None:
            return value2
        value1.add(value2)
        return value1


class BasicProfiler(Profiler):
    """
    BasicProfiler is the default profiler, which is implemented based on
    cProfile and Accumulator
    """

    def __init__(self, ctx: "SparkContext") -> None:
        Profiler.__init__(self, ctx)
        # Creates a new accumulator for combining the profiles of different
        # partitions of a stage
        self._accumulator = ctx.accumulator(None, PStatsParam)  # type: ignore[arg-type]

    def profile(self, func: Callable[..., Any], *args: Any, **kwargs: Any) -> Any:
        """Runs and profiles the method to_profile passed in. A profile object is returned."""
        pr = cProfile.Profile()
        ret = pr.runcall(func, *args, **kwargs)
        st = pstats.Stats(pr)
        st.stream = None  # type: ignore[attr-defined]  # make it picklable
        st.strip_dirs()

        # Adds a new profile to the existing accumulated value
        self._accumulator.add(st)  # type: ignore[arg-type]

        return ret

    def stats(self) -> pstats.Stats:
        return cast(pstats.Stats, self._accumulator.value)


class UDFBasicProfiler(BasicProfiler):
    """
    UDFBasicProfiler is the profiler for Python/Pandas UDFs.
    """

    def show(self, id: int) -> None:
        """Print the profile stats to stdout, id is the PythonUDF id"""
        stats = self.stats()
        if stats:
            print("=" * 60)
            print("Profile of UDF<id=%d>" % id)
            print("=" * 60)
            stats.sort_stats("time", "cumulative").print_stats()

    def dump(self, id: int, path: str) -> None:
        """Dump the profile into path, id is the PythonUDF id"""
        if not os.path.exists(path):
            os.makedirs(path)
        stats = self.stats()
        if stats:
            p = os.path.join(path, "udf_%d.pstats" % id)
            stats.dump_stats(p)


if __name__ == "__main__":
    import doctest

    (failure_count, test_count) = doctest.testmod()
    if failure_count:
        sys.exit(-1)
