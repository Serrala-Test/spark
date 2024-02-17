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

"""Implementation of a progress bar that is displayed while a query is running."""

import os, time
import sys

from IPython.utils.terminal import get_terminal_size
from pyspark.sql.connect.shell import progress_bar_enabled


class Progress:
    """This is a small helper class to visualize a textual progress bar. The interface is very simple and assumes
    that nothing else prints to the standard output."""

    SI_BYTE_SIZES = (1 << 60, 1 << 50, 1 << 40, 1 << 30, 1 << 20, 1 << 10, 1)
    SI_BYTE_SUFFIXES = ("EiB", "PiB", "TiB", "GiB", "MiB", "KiB", "B")

    def __init__(self, char="*", min_width=80, output=sys.stdout, enabled=False):
        """
        Constructs a new Progress bar. The progress bar is typically used in the blocking query execution path
        to process the execution progress methods from the server.
        Parameters
        ----------
        char str the Default character to be used for printing the bar.
        min_width numeric The minimum width of the progress bar
        output file The output device to write the progress bar to.
        """
        self._ticks = 0
        self._tick = 0
        x, y = get_terminal_size()
        self._min_width = min_width
        self._char = char
        self._width = max(min(min_width, x), self._min_width)
        self._max_printed = 0
        self._started = time.time()
        self._enabled = enabled or progress_bar_enabled()
        self._bytes_read = 0
        self._out = output

    def update_ticks(self, ticks: int, current: int, bytes_read: int) -> None:
        """This method is called from the execution to update the progress bar with a new total
        tick counter and the current position. This is necessary in case new stages get added with
        new tasks and so the total task number will be udpated as well."""
        if ticks > 0 and current != self._tick:
            self._ticks = ticks
            self._tick = current
            self._bytes_read = bytes_read
            if self._tick > 0:
                self.output()

    def finish(self):
        """Clear the last line"""
        if self._enabled:
            print("\r" + " " * self._max_printed, end="", flush=True, file=self._out)
            print("\r", end="", flush=True, file=self._out)

    def output(self):
        """Writes the progress bar out."""
        if self._enabled:
            val = int((self._tick / float(self._ticks)) * self._width)
            bar = self._char * val + "-" * (self._width - val)
            percent_complete = (self._tick / self._ticks) * 100
            elapsed = int(time.time() - self._started)
            scanned = self._bytes_to_string(self._bytes_read)
            buffer = f"\r[{bar}] {percent_complete:.2f}% Complete ({elapsed}s, Scanned {scanned})"
            self._max_printed = max(len(buffer), self._max_printed)
            print(buffer, end="", flush=True, file=self._out)

    @staticmethod
    def _bytes_to_string(size: int) -> str:
        """Helper method to convert a numeric bytes value into a human readable representation"""
        i = 0
        while i < len(Progress.SI_BYTE_SIZES) - 1 and size < 2 * Progress.SI_BYTE_SIZES[i]:
            i += 1
        result = float(size) / Progress.SI_BYTE_SIZES[i]
        return f"{result:.1f} {Progress.SI_BYTE_SUFFIXES[i]}"
