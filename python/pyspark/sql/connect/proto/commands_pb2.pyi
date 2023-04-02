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
@generated by mypy-protobuf.  Do not edit manually!
isort:skip_file

Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements.  See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License.  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
"""
import builtins
import collections.abc
import google.protobuf.any_pb2
import google.protobuf.descriptor
import google.protobuf.internal.containers
import google.protobuf.internal.enum_type_wrapper
import google.protobuf.message
import pyspark.sql.connect.proto.expressions_pb2
import pyspark.sql.connect.proto.relations_pb2
import sys
import typing

if sys.version_info >= (3, 10):
    import typing as typing_extensions
else:
    import typing_extensions

DESCRIPTOR: google.protobuf.descriptor.FileDescriptor

class Command(google.protobuf.message.Message):
    """A [[Command]] is an operation that is executed by the server that does not directly consume or
    produce a relational result.
    """

    DESCRIPTOR: google.protobuf.descriptor.Descriptor

    REGISTER_FUNCTION_FIELD_NUMBER: builtins.int
    WRITE_OPERATION_FIELD_NUMBER: builtins.int
    CREATE_DATAFRAME_VIEW_FIELD_NUMBER: builtins.int
    WRITE_OPERATION_V2_FIELD_NUMBER: builtins.int
    SQL_COMMAND_FIELD_NUMBER: builtins.int
    EXTENSION_FIELD_NUMBER: builtins.int
    @property
    def register_function(
        self,
    ) -> pyspark.sql.connect.proto.expressions_pb2.CommonInlineUserDefinedFunction: ...
    @property
    def write_operation(self) -> global___WriteOperation: ...
    @property
    def create_dataframe_view(self) -> global___CreateDataFrameViewCommand: ...
    @property
    def write_operation_v2(self) -> global___WriteOperationV2: ...
    @property
    def sql_command(self) -> global___SqlCommand: ...
    @property
    def extension(self) -> google.protobuf.any_pb2.Any:
        """This field is used to mark extensions to the protocol. When plugins generate arbitrary
        Commands they can add them here. During the planning the correct resolution is done.
        """
    def __init__(
        self,
        *,
        register_function: pyspark.sql.connect.proto.expressions_pb2.CommonInlineUserDefinedFunction
        | None = ...,
        write_operation: global___WriteOperation | None = ...,
        create_dataframe_view: global___CreateDataFrameViewCommand | None = ...,
        write_operation_v2: global___WriteOperationV2 | None = ...,
        sql_command: global___SqlCommand | None = ...,
        extension: google.protobuf.any_pb2.Any | None = ...,
    ) -> None: ...
    def HasField(
        self,
        field_name: typing_extensions.Literal[
            "command_type",
            b"command_type",
            "create_dataframe_view",
            b"create_dataframe_view",
            "extension",
            b"extension",
            "register_function",
            b"register_function",
            "sql_command",
            b"sql_command",
            "write_operation",
            b"write_operation",
            "write_operation_v2",
            b"write_operation_v2",
        ],
    ) -> builtins.bool: ...
    def ClearField(
        self,
        field_name: typing_extensions.Literal[
            "command_type",
            b"command_type",
            "create_dataframe_view",
            b"create_dataframe_view",
            "extension",
            b"extension",
            "register_function",
            b"register_function",
            "sql_command",
            b"sql_command",
            "write_operation",
            b"write_operation",
            "write_operation_v2",
            b"write_operation_v2",
        ],
    ) -> None: ...
    def WhichOneof(
        self, oneof_group: typing_extensions.Literal["command_type", b"command_type"]
    ) -> typing_extensions.Literal[
        "register_function",
        "write_operation",
        "create_dataframe_view",
        "write_operation_v2",
        "sql_command",
        "extension",
    ] | None: ...

global___Command = Command

class SqlCommand(google.protobuf.message.Message):
    """A SQL Command is used to trigger the eager evaluation of SQL commands in Spark.

    When the SQL provide as part of the message is a command it will be immediately evaluated
    and the result will be collected and returned as part of a LocalRelation. If the result is
    not a command, the operation will simply return a SQL Relation. This allows the client to be
    almost oblivious to the server-side behavior.
    """

    DESCRIPTOR: google.protobuf.descriptor.Descriptor

    class ArgsEntry(google.protobuf.message.Message):
        DESCRIPTOR: google.protobuf.descriptor.Descriptor

        KEY_FIELD_NUMBER: builtins.int
        VALUE_FIELD_NUMBER: builtins.int
        key: builtins.str
        @property
        def value(self) -> pyspark.sql.connect.proto.expressions_pb2.Expression.Literal: ...
        def __init__(
            self,
            *,
            key: builtins.str = ...,
            value: pyspark.sql.connect.proto.expressions_pb2.Expression.Literal | None = ...,
        ) -> None: ...
        def HasField(
            self, field_name: typing_extensions.Literal["value", b"value"]
        ) -> builtins.bool: ...
        def ClearField(
            self, field_name: typing_extensions.Literal["key", b"key", "value", b"value"]
        ) -> None: ...

    SQL_FIELD_NUMBER: builtins.int
    ARGS_FIELD_NUMBER: builtins.int
    sql: builtins.str
    """(Required) SQL Query."""
    @property
    def args(
        self,
    ) -> google.protobuf.internal.containers.MessageMap[
        builtins.str, pyspark.sql.connect.proto.expressions_pb2.Expression.Literal
    ]:
        """(Optional) A map of parameter names to literal expressions."""
    def __init__(
        self,
        *,
        sql: builtins.str = ...,
        args: collections.abc.Mapping[
            builtins.str, pyspark.sql.connect.proto.expressions_pb2.Expression.Literal
        ]
        | None = ...,
    ) -> None: ...
    def ClearField(
        self, field_name: typing_extensions.Literal["args", b"args", "sql", b"sql"]
    ) -> None: ...

global___SqlCommand = SqlCommand

class CreateDataFrameViewCommand(google.protobuf.message.Message):
    """A command that can create DataFrame global temp view or local temp view."""

    DESCRIPTOR: google.protobuf.descriptor.Descriptor

    INPUT_FIELD_NUMBER: builtins.int
    NAME_FIELD_NUMBER: builtins.int
    IS_GLOBAL_FIELD_NUMBER: builtins.int
    REPLACE_FIELD_NUMBER: builtins.int
    @property
    def input(self) -> pyspark.sql.connect.proto.relations_pb2.Relation:
        """(Required) The relation that this view will be built on."""
    name: builtins.str
    """(Required) View name."""
    is_global: builtins.bool
    """(Required) Whether this is global temp view or local temp view."""
    replace: builtins.bool
    """(Required)

    If true, and if the view already exists, updates it; if false, and if the view
    already exists, throws exception.
    """
    def __init__(
        self,
        *,
        input: pyspark.sql.connect.proto.relations_pb2.Relation | None = ...,
        name: builtins.str = ...,
        is_global: builtins.bool = ...,
        replace: builtins.bool = ...,
    ) -> None: ...
    def HasField(
        self, field_name: typing_extensions.Literal["input", b"input"]
    ) -> builtins.bool: ...
    def ClearField(
        self,
        field_name: typing_extensions.Literal[
            "input", b"input", "is_global", b"is_global", "name", b"name", "replace", b"replace"
        ],
    ) -> None: ...

global___CreateDataFrameViewCommand = CreateDataFrameViewCommand

class WriteOperation(google.protobuf.message.Message):
    """As writes are not directly handled during analysis and planning, they are modeled as commands."""

    DESCRIPTOR: google.protobuf.descriptor.Descriptor

    class _SaveMode:
        ValueType = typing.NewType("ValueType", builtins.int)
        V: typing_extensions.TypeAlias = ValueType

    class _SaveModeEnumTypeWrapper(
        google.protobuf.internal.enum_type_wrapper._EnumTypeWrapper[
            WriteOperation._SaveMode.ValueType
        ],
        builtins.type,
    ):  # noqa: F821
        DESCRIPTOR: google.protobuf.descriptor.EnumDescriptor
        SAVE_MODE_UNSPECIFIED: WriteOperation._SaveMode.ValueType  # 0
        SAVE_MODE_APPEND: WriteOperation._SaveMode.ValueType  # 1
        SAVE_MODE_OVERWRITE: WriteOperation._SaveMode.ValueType  # 2
        SAVE_MODE_ERROR_IF_EXISTS: WriteOperation._SaveMode.ValueType  # 3
        SAVE_MODE_IGNORE: WriteOperation._SaveMode.ValueType  # 4

    class SaveMode(_SaveMode, metaclass=_SaveModeEnumTypeWrapper): ...
    SAVE_MODE_UNSPECIFIED: WriteOperation.SaveMode.ValueType  # 0
    SAVE_MODE_APPEND: WriteOperation.SaveMode.ValueType  # 1
    SAVE_MODE_OVERWRITE: WriteOperation.SaveMode.ValueType  # 2
    SAVE_MODE_ERROR_IF_EXISTS: WriteOperation.SaveMode.ValueType  # 3
    SAVE_MODE_IGNORE: WriteOperation.SaveMode.ValueType  # 4

    class OptionsEntry(google.protobuf.message.Message):
        DESCRIPTOR: google.protobuf.descriptor.Descriptor

        KEY_FIELD_NUMBER: builtins.int
        VALUE_FIELD_NUMBER: builtins.int
        key: builtins.str
        value: builtins.str
        def __init__(
            self,
            *,
            key: builtins.str = ...,
            value: builtins.str = ...,
        ) -> None: ...
        def ClearField(
            self, field_name: typing_extensions.Literal["key", b"key", "value", b"value"]
        ) -> None: ...

    class SaveTable(google.protobuf.message.Message):
        DESCRIPTOR: google.protobuf.descriptor.Descriptor

        class _TableSaveMethod:
            ValueType = typing.NewType("ValueType", builtins.int)
            V: typing_extensions.TypeAlias = ValueType

        class _TableSaveMethodEnumTypeWrapper(
            google.protobuf.internal.enum_type_wrapper._EnumTypeWrapper[
                WriteOperation.SaveTable._TableSaveMethod.ValueType
            ],
            builtins.type,
        ):  # noqa: F821
            DESCRIPTOR: google.protobuf.descriptor.EnumDescriptor
            TABLE_SAVE_METHOD_UNSPECIFIED: WriteOperation.SaveTable._TableSaveMethod.ValueType  # 0
            TABLE_SAVE_METHOD_SAVE_AS_TABLE: WriteOperation.SaveTable._TableSaveMethod.ValueType  # 1
            TABLE_SAVE_METHOD_INSERT_INTO: WriteOperation.SaveTable._TableSaveMethod.ValueType  # 2

        class TableSaveMethod(_TableSaveMethod, metaclass=_TableSaveMethodEnumTypeWrapper): ...
        TABLE_SAVE_METHOD_UNSPECIFIED: WriteOperation.SaveTable.TableSaveMethod.ValueType  # 0
        TABLE_SAVE_METHOD_SAVE_AS_TABLE: WriteOperation.SaveTable.TableSaveMethod.ValueType  # 1
        TABLE_SAVE_METHOD_INSERT_INTO: WriteOperation.SaveTable.TableSaveMethod.ValueType  # 2

        TABLE_NAME_FIELD_NUMBER: builtins.int
        SAVE_METHOD_FIELD_NUMBER: builtins.int
        table_name: builtins.str
        """(Required) The table name."""
        save_method: global___WriteOperation.SaveTable.TableSaveMethod.ValueType
        """(Required) The method to be called to write to the table."""
        def __init__(
            self,
            *,
            table_name: builtins.str = ...,
            save_method: global___WriteOperation.SaveTable.TableSaveMethod.ValueType = ...,
        ) -> None: ...
        def ClearField(
            self,
            field_name: typing_extensions.Literal[
                "save_method", b"save_method", "table_name", b"table_name"
            ],
        ) -> None: ...

    class BucketBy(google.protobuf.message.Message):
        DESCRIPTOR: google.protobuf.descriptor.Descriptor

        BUCKET_COLUMN_NAMES_FIELD_NUMBER: builtins.int
        NUM_BUCKETS_FIELD_NUMBER: builtins.int
        @property
        def bucket_column_names(
            self,
        ) -> google.protobuf.internal.containers.RepeatedScalarFieldContainer[builtins.str]: ...
        num_buckets: builtins.int
        def __init__(
            self,
            *,
            bucket_column_names: collections.abc.Iterable[builtins.str] | None = ...,
            num_buckets: builtins.int = ...,
        ) -> None: ...
        def ClearField(
            self,
            field_name: typing_extensions.Literal[
                "bucket_column_names", b"bucket_column_names", "num_buckets", b"num_buckets"
            ],
        ) -> None: ...

    INPUT_FIELD_NUMBER: builtins.int
    SOURCE_FIELD_NUMBER: builtins.int
    PATH_FIELD_NUMBER: builtins.int
    TABLE_FIELD_NUMBER: builtins.int
    MODE_FIELD_NUMBER: builtins.int
    SORT_COLUMN_NAMES_FIELD_NUMBER: builtins.int
    PARTITIONING_COLUMNS_FIELD_NUMBER: builtins.int
    BUCKET_BY_FIELD_NUMBER: builtins.int
    OPTIONS_FIELD_NUMBER: builtins.int
    @property
    def input(self) -> pyspark.sql.connect.proto.relations_pb2.Relation:
        """(Required) The output of the `input` relation will be persisted according to the options."""
    source: builtins.str
    """(Optional) Format value according to the Spark documentation. Examples are: text, parquet, delta."""
    path: builtins.str
    @property
    def table(self) -> global___WriteOperation.SaveTable: ...
    mode: global___WriteOperation.SaveMode.ValueType
    """(Required) the save mode."""
    @property
    def sort_column_names(
        self,
    ) -> google.protobuf.internal.containers.RepeatedScalarFieldContainer[builtins.str]:
        """(Optional) List of columns to sort the output by."""
    @property
    def partitioning_columns(
        self,
    ) -> google.protobuf.internal.containers.RepeatedScalarFieldContainer[builtins.str]:
        """(Optional) List of columns for partitioning."""
    @property
    def bucket_by(self) -> global___WriteOperation.BucketBy:
        """(Optional) Bucketing specification. Bucketing must set the number of buckets and the columns
        to bucket by.
        """
    @property
    def options(self) -> google.protobuf.internal.containers.ScalarMap[builtins.str, builtins.str]:
        """(Optional) A list of configuration options."""
    def __init__(
        self,
        *,
        input: pyspark.sql.connect.proto.relations_pb2.Relation | None = ...,
        source: builtins.str | None = ...,
        path: builtins.str = ...,
        table: global___WriteOperation.SaveTable | None = ...,
        mode: global___WriteOperation.SaveMode.ValueType = ...,
        sort_column_names: collections.abc.Iterable[builtins.str] | None = ...,
        partitioning_columns: collections.abc.Iterable[builtins.str] | None = ...,
        bucket_by: global___WriteOperation.BucketBy | None = ...,
        options: collections.abc.Mapping[builtins.str, builtins.str] | None = ...,
    ) -> None: ...
    def HasField(
        self,
        field_name: typing_extensions.Literal[
            "_source",
            b"_source",
            "bucket_by",
            b"bucket_by",
            "input",
            b"input",
            "path",
            b"path",
            "save_type",
            b"save_type",
            "source",
            b"source",
            "table",
            b"table",
        ],
    ) -> builtins.bool: ...
    def ClearField(
        self,
        field_name: typing_extensions.Literal[
            "_source",
            b"_source",
            "bucket_by",
            b"bucket_by",
            "input",
            b"input",
            "mode",
            b"mode",
            "options",
            b"options",
            "partitioning_columns",
            b"partitioning_columns",
            "path",
            b"path",
            "save_type",
            b"save_type",
            "sort_column_names",
            b"sort_column_names",
            "source",
            b"source",
            "table",
            b"table",
        ],
    ) -> None: ...
    @typing.overload
    def WhichOneof(
        self, oneof_group: typing_extensions.Literal["_source", b"_source"]
    ) -> typing_extensions.Literal["source"] | None: ...
    @typing.overload
    def WhichOneof(
        self, oneof_group: typing_extensions.Literal["save_type", b"save_type"]
    ) -> typing_extensions.Literal["path", "table"] | None: ...

global___WriteOperation = WriteOperation

class WriteOperationV2(google.protobuf.message.Message):
    """As writes are not directly handled during analysis and planning, they are modeled as commands."""

    DESCRIPTOR: google.protobuf.descriptor.Descriptor

    class _Mode:
        ValueType = typing.NewType("ValueType", builtins.int)
        V: typing_extensions.TypeAlias = ValueType

    class _ModeEnumTypeWrapper(
        google.protobuf.internal.enum_type_wrapper._EnumTypeWrapper[
            WriteOperationV2._Mode.ValueType
        ],
        builtins.type,
    ):  # noqa: F821
        DESCRIPTOR: google.protobuf.descriptor.EnumDescriptor
        MODE_UNSPECIFIED: WriteOperationV2._Mode.ValueType  # 0
        MODE_CREATE: WriteOperationV2._Mode.ValueType  # 1
        MODE_OVERWRITE: WriteOperationV2._Mode.ValueType  # 2
        MODE_OVERWRITE_PARTITIONS: WriteOperationV2._Mode.ValueType  # 3
        MODE_APPEND: WriteOperationV2._Mode.ValueType  # 4
        MODE_REPLACE: WriteOperationV2._Mode.ValueType  # 5
        MODE_CREATE_OR_REPLACE: WriteOperationV2._Mode.ValueType  # 6

    class Mode(_Mode, metaclass=_ModeEnumTypeWrapper): ...
    MODE_UNSPECIFIED: WriteOperationV2.Mode.ValueType  # 0
    MODE_CREATE: WriteOperationV2.Mode.ValueType  # 1
    MODE_OVERWRITE: WriteOperationV2.Mode.ValueType  # 2
    MODE_OVERWRITE_PARTITIONS: WriteOperationV2.Mode.ValueType  # 3
    MODE_APPEND: WriteOperationV2.Mode.ValueType  # 4
    MODE_REPLACE: WriteOperationV2.Mode.ValueType  # 5
    MODE_CREATE_OR_REPLACE: WriteOperationV2.Mode.ValueType  # 6

    class OptionsEntry(google.protobuf.message.Message):
        DESCRIPTOR: google.protobuf.descriptor.Descriptor

        KEY_FIELD_NUMBER: builtins.int
        VALUE_FIELD_NUMBER: builtins.int
        key: builtins.str
        value: builtins.str
        def __init__(
            self,
            *,
            key: builtins.str = ...,
            value: builtins.str = ...,
        ) -> None: ...
        def ClearField(
            self, field_name: typing_extensions.Literal["key", b"key", "value", b"value"]
        ) -> None: ...

    class TablePropertiesEntry(google.protobuf.message.Message):
        DESCRIPTOR: google.protobuf.descriptor.Descriptor

        KEY_FIELD_NUMBER: builtins.int
        VALUE_FIELD_NUMBER: builtins.int
        key: builtins.str
        value: builtins.str
        def __init__(
            self,
            *,
            key: builtins.str = ...,
            value: builtins.str = ...,
        ) -> None: ...
        def ClearField(
            self, field_name: typing_extensions.Literal["key", b"key", "value", b"value"]
        ) -> None: ...

    INPUT_FIELD_NUMBER: builtins.int
    TABLE_NAME_FIELD_NUMBER: builtins.int
    PROVIDER_FIELD_NUMBER: builtins.int
    PARTITIONING_COLUMNS_FIELD_NUMBER: builtins.int
    OPTIONS_FIELD_NUMBER: builtins.int
    TABLE_PROPERTIES_FIELD_NUMBER: builtins.int
    MODE_FIELD_NUMBER: builtins.int
    OVERWRITE_CONDITION_FIELD_NUMBER: builtins.int
    @property
    def input(self) -> pyspark.sql.connect.proto.relations_pb2.Relation:
        """(Required) The output of the `input` relation will be persisted according to the options."""
    table_name: builtins.str
    """(Required) The destination of the write operation must be either a path or a table."""
    provider: builtins.str
    """(Optional) A provider for the underlying output data source. Spark's default catalog supports
    "parquet", "json", etc.
    """
    @property
    def partitioning_columns(
        self,
    ) -> google.protobuf.internal.containers.RepeatedCompositeFieldContainer[
        pyspark.sql.connect.proto.expressions_pb2.Expression
    ]:
        """(Optional) List of columns for partitioning for output table created by `create`,
        `createOrReplace`, or `replace`
        """
    @property
    def options(self) -> google.protobuf.internal.containers.ScalarMap[builtins.str, builtins.str]:
        """(Optional) A list of configuration options."""
    @property
    def table_properties(
        self,
    ) -> google.protobuf.internal.containers.ScalarMap[builtins.str, builtins.str]:
        """(Optional) A list of table properties."""
    mode: global___WriteOperationV2.Mode.ValueType
    """(Required) Write mode."""
    @property
    def overwrite_condition(self) -> pyspark.sql.connect.proto.expressions_pb2.Expression:
        """(Optional) A condition for overwrite saving mode"""
    def __init__(
        self,
        *,
        input: pyspark.sql.connect.proto.relations_pb2.Relation | None = ...,
        table_name: builtins.str = ...,
        provider: builtins.str | None = ...,
        partitioning_columns: collections.abc.Iterable[
            pyspark.sql.connect.proto.expressions_pb2.Expression
        ]
        | None = ...,
        options: collections.abc.Mapping[builtins.str, builtins.str] | None = ...,
        table_properties: collections.abc.Mapping[builtins.str, builtins.str] | None = ...,
        mode: global___WriteOperationV2.Mode.ValueType = ...,
        overwrite_condition: pyspark.sql.connect.proto.expressions_pb2.Expression | None = ...,
    ) -> None: ...
    def HasField(
        self,
        field_name: typing_extensions.Literal[
            "_provider",
            b"_provider",
            "input",
            b"input",
            "overwrite_condition",
            b"overwrite_condition",
            "provider",
            b"provider",
        ],
    ) -> builtins.bool: ...
    def ClearField(
        self,
        field_name: typing_extensions.Literal[
            "_provider",
            b"_provider",
            "input",
            b"input",
            "mode",
            b"mode",
            "options",
            b"options",
            "overwrite_condition",
            b"overwrite_condition",
            "partitioning_columns",
            b"partitioning_columns",
            "provider",
            b"provider",
            "table_name",
            b"table_name",
            "table_properties",
            b"table_properties",
        ],
    ) -> None: ...
    def WhichOneof(
        self, oneof_group: typing_extensions.Literal["_provider", b"_provider"]
    ) -> typing_extensions.Literal["provider"] | None: ...

global___WriteOperationV2 = WriteOperationV2
