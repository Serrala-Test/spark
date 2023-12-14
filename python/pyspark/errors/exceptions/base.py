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
from enum import Enum
from typing import Dict, Optional, cast, Iterable, TYPE_CHECKING, Any, List

from pyspark.errors.utils import ErrorClassesReader
from pickle import PicklingError

if TYPE_CHECKING:
    from pyspark.sql.types import Row


class PySparkException(Exception):
    """
    Base Exception for handling errors generated from PySpark.
    """

    def __init__(
        self,
        message: Optional[str] = None,
        error_class: Optional[str] = None,
        message_parameters: Optional[Dict[str, str]] = None,
        query_contexts: List["QueryContext"] = [],
    ):
        self._error_reader = ErrorClassesReader()

        if message is None:
            self._message = self._error_reader.get_error_message(
                cast(str, error_class), cast(Dict[str, str], message_parameters)
            )
        else:
            self._message = message

        self._error_class = error_class
        self._message_parameters = message_parameters
        self._query_contexts = query_contexts

    def getErrorClass(self) -> Optional[str]:
        """
        Returns an error class as a string.

        .. versionadded:: 3.4.0

        See Also
        --------
        :meth:`PySparkException.getMessage`
        :meth:`PySparkException.getMessageParameters`
        :meth:`PySparkException.getQueryContext`
        :meth:`PySparkException.getSqlState`
        """
        return self._error_class

    def getMessageParameters(self) -> Optional[Dict[str, str]]:
        """
        Returns a message parameters as a dictionary.

        .. versionadded:: 3.4.0

        See Also
        --------
        :meth:`PySparkException.getErrorClass`
        :meth:`PySparkException.getMessage`
        :meth:`PySparkException.getQueryContext`
        :meth:`PySparkException.getSqlState`
        """
        return self._message_parameters

    def getSqlState(self) -> Optional[str]:
        """
        Returns an SQLSTATE as a string.

        Errors generated in Python have no SQLSTATE, so it always returns None.

        .. versionadded:: 3.4.0

        See Also
        --------
        :meth:`PySparkException.getErrorClass`
        :meth:`PySparkException.getMessage`
        :meth:`PySparkException.getMessageParameters`
        :meth:`PySparkException.getQueryContext`
        """
        return None

    def getMessage(self) -> str:
        """
        Returns full error message.

        .. versionadded:: 4.0.0

        See Also
        --------
        :meth:`PySparkException.getErrorClass`
        :meth:`PySparkException.getMessageParameters`
        :meth:`PySparkException.getQueryContext`
        :meth:`PySparkException.getSqlState`
        """
        return f"[{self.getErrorClass()}] {self._message}"

    def getQueryContext(self) -> List["QueryContext"]:
        """
        Returns full error message.

        .. versionadded:: 4.0.0

        See Also
        --------
        :meth:`PySparkException.getErrorClass`
        :meth:`PySparkException.getMessageParameters`
        :meth:`PySparkException.getMessage`
        :meth:`PySparkException.getSqlState`
        """
        return self._query_contexts

    def __str__(self) -> str:
        if self.getErrorClass() is not None:
            return self.getMessage()
        else:
            return self._message


class AnalysisException(PySparkException):
    """
    Failed to analyze a SQL query plan.
    """


class SessionNotSameException(PySparkException):
    """
    Performed the same operation on different SparkSession.
    """


class TempTableAlreadyExistsException(AnalysisException):
    """
    Failed to create temp view since it is already exists.
    """


class ParseException(AnalysisException):
    """
    Failed to parse a SQL command.
    """


class IllegalArgumentException(PySparkException):
    """
    Passed an illegal or inappropriate argument.
    """


class ArithmeticException(PySparkException):
    """
    Arithmetic exception thrown from Spark with an error class.
    """


class UnsupportedOperationException(PySparkException):
    """
    Unsupported operation exception thrown from Spark with an error class.
    """


class ArrayIndexOutOfBoundsException(PySparkException):
    """
    Array index out of bounds exception thrown from Spark with an error class.
    """


class DateTimeException(PySparkException):
    """
    Datetime exception thrown from Spark with an error class.
    """


class NumberFormatException(IllegalArgumentException):
    """
    Number format exception thrown from Spark with an error class.
    """


class StreamingQueryException(PySparkException):
    """
    Exception that stopped a :class:`StreamingQuery`.
    """


class QueryExecutionException(PySparkException):
    """
    Failed to execute a query.
    """


class PythonException(PySparkException):
    """
    Exceptions thrown from Python workers.
    """


class SparkRuntimeException(PySparkException):
    """
    Runtime exception thrown from Spark with an error class.
    """


class SparkUpgradeException(PySparkException):
    """
    Exception thrown because of Spark upgrade.
    """


class SparkNoSuchElementException(PySparkException):
    """
    Exception thrown for `java.util.NoSuchElementException`.
    """


class UnknownException(PySparkException):
    """
    None of the other exceptions.
    """


class PySparkValueError(PySparkException, ValueError):
    """
    Wrapper class for ValueError to support error classes.
    """


class PySparkTypeError(PySparkException, TypeError):
    """
    Wrapper class for TypeError to support error classes.
    """


class PySparkIndexError(PySparkException, IndexError):
    """
    Wrapper class for IndexError to support error classes.
    """


class PySparkAttributeError(PySparkException, AttributeError):
    """
    Wrapper class for AttributeError to support error classes.
    """


class PySparkRuntimeError(PySparkException, RuntimeError):
    """
    Wrapper class for RuntimeError to support error classes.
    """


class PySparkAssertionError(PySparkException, AssertionError):
    """
    Wrapper class for AssertionError to support error classes.
    """

    def __init__(
        self,
        message: Optional[str] = None,
        error_class: Optional[str] = None,
        message_parameters: Optional[Dict[str, str]] = None,
        data: Optional[Iterable["Row"]] = None,
    ):
        super().__init__(message, error_class, message_parameters)
        self.data = data


class PySparkNotImplementedError(PySparkException, NotImplementedError):
    """
    Wrapper class for NotImplementedError to support error classes.
    """


class PySparkPicklingError(PySparkException, PicklingError):
    """
    Wrapper class for pickle.PicklingError to support error classes.
    """


class RetriesExceeded(PySparkException):
    """
    Represents an exception which is considered retriable, but retry limits
    were exceeded
    """


class PySparkKeyError(PySparkException, KeyError):
    """
    Wrapper class for KeyError to support error classes.
    """


class PySparkImportError(PySparkException, ImportError):
    """
    Wrapper class for ImportError to support error classes.
    """


class QueryContextType(Enum):
    SQL = 0
    DataFrame = 1


class QueryContext:
    def __init__(self, q: Any):
        self._q = q

    def contextType(self) -> QueryContextType:
        if hasattr(self._q, "contextType"):
            context_type = self._q.contextType().toString()
            if context_type == "SQL":
                context_type = 0
            else:
                context_type = 1
        else:
            context_type = self._q.context_type

        if int(context_type) == QueryContextType.DataFrame.value:
            return QueryContextType.SQL
        else:
            return QueryContextType.DataFrame

    def objectType(self) -> str:
        if hasattr(self._q, "objectType"):
            return str(self._q.objectType())
        else:
            return str(self._q.object_type)

    def objectName(self) -> str:
        if hasattr(self._q, "objectName"):
            return str(self._q.objectName())
        else:
            return str(self._q.object_name)

    def startIndex(self) -> int:
        if hasattr(self._q, "startIndex"):
            return int(self._q.startIndex())
        else:
            return int(self._q.start_index)

    def stopIndex(self) -> int:
        if hasattr(self._q, "stopIndex"):
            return int(self._q.stopIndex())
        else:
            return int(self._q.stop_index)

    def fragment(self) -> str:
        if callable(self._q.fragment):
            return str(self._q.fragment())
        else:
            return str(self._q.fragment)

    def callSite(self) -> str:
        if callable(self._q.callSite):
            return str(self._q.callSite())
        else:
            return str(self._q.callSite)

    def summary(self) -> str:
        if callable(self._q.summary):
            return str(self._q.summary())
        else:
            return str(self._q.summary)
