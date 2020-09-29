#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

from pyspark.serializers import (  # noqa: F401
    Serializer as Serializer,
    UTF8Deserializer as UTF8Deserializer,
    read_int as read_int,
    write_int as write_int,
)
from typing import Any

class SpecialLengths:
    END_OF_DATA_SECTION: int = ...
    PYTHON_EXCEPTION_THROWN: int = ...
    TIMING_DATA: int = ...
    END_OF_STREAM: int = ...
    NULL: int = ...
    START_ARROW_STREAM: int = ...

class ArrowCollectSerializer(Serializer):
    serializer: Any = ...
    def __init__(self) -> None: ...
    def dump_stream(self, iterator: Any, stream: Any): ...
    def load_stream(self, stream: Any) -> None: ...

class ArrowStreamSerializer(Serializer):
    def dump_stream(self, iterator: Any, stream: Any) -> None: ...
    def load_stream(self, stream: Any) -> None: ...

class ArrowStreamPandasSerializer(ArrowStreamSerializer):
    def __init__(
        self, timezone: Any, safecheck: Any, assign_cols_by_name: Any
    ) -> None: ...
    def arrow_to_pandas(self, arrow_column: Any): ...
    def dump_stream(self, iterator: Any, stream: Any) -> None: ...
    def load_stream(self, stream: Any) -> None: ...

class ArrowStreamPandasUDFSerializer(ArrowStreamPandasSerializer):
    def __init__(
        self,
        timezone: Any,
        safecheck: Any,
        assign_cols_by_name: Any,
        df_for_struct: bool = ...,
    ) -> None: ...
    def arrow_to_pandas(self, arrow_column: Any): ...
    def dump_stream(self, iterator: Any, stream: Any): ...

class CogroupUDFSerializer(ArrowStreamPandasUDFSerializer):
    def load_stream(self, stream: Any) -> None: ...
