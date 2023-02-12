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
import google.protobuf.descriptor
import google.protobuf.internal.containers
import google.protobuf.message
import pyspark.sql.connect.proto.relations_pb2
import sys

if sys.version_info >= (3, 8):
    import typing as typing_extensions
else:
    import typing_extensions

DESCRIPTOR: google.protobuf.descriptor.FileDescriptor

@typing_extensions.final
class MlCommand(google.protobuf.message.Message):
    DESCRIPTOR: google.protobuf.descriptor.Descriptor

    @typing_extensions.final
    class ParamValue(google.protobuf.message.Message):
        DESCRIPTOR: google.protobuf.descriptor.Descriptor

        @typing_extensions.final
        class DoubleArray(google.protobuf.message.Message):
            DESCRIPTOR: google.protobuf.descriptor.Descriptor

            ELEMENT_FIELD_NUMBER: builtins.int
            @property
            def element(
                self,
            ) -> google.protobuf.internal.containers.RepeatedScalarFieldContainer[
                builtins.float
            ]: ...
            def __init__(
                self,
                *,
                element: collections.abc.Iterable[builtins.float] | None = ...,
            ) -> None: ...
            def ClearField(
                self, field_name: typing_extensions.Literal["element", b"element"]
            ) -> None: ...

        @typing_extensions.final
        class FloatArray(google.protobuf.message.Message):
            DESCRIPTOR: google.protobuf.descriptor.Descriptor

            ELEMENT_FIELD_NUMBER: builtins.int
            @property
            def element(
                self,
            ) -> google.protobuf.internal.containers.RepeatedScalarFieldContainer[
                builtins.float
            ]: ...
            def __init__(
                self,
                *,
                element: collections.abc.Iterable[builtins.float] | None = ...,
            ) -> None: ...
            def ClearField(
                self, field_name: typing_extensions.Literal["element", b"element"]
            ) -> None: ...

        @typing_extensions.final
        class LongArray(google.protobuf.message.Message):
            DESCRIPTOR: google.protobuf.descriptor.Descriptor

            ELEMENT_FIELD_NUMBER: builtins.int
            @property
            def element(
                self,
            ) -> google.protobuf.internal.containers.RepeatedScalarFieldContainer[builtins.int]: ...
            def __init__(
                self,
                *,
                element: collections.abc.Iterable[builtins.int] | None = ...,
            ) -> None: ...
            def ClearField(
                self, field_name: typing_extensions.Literal["element", b"element"]
            ) -> None: ...

        @typing_extensions.final
        class IntArray(google.protobuf.message.Message):
            DESCRIPTOR: google.protobuf.descriptor.Descriptor

            ELEMENT_FIELD_NUMBER: builtins.int
            @property
            def element(
                self,
            ) -> google.protobuf.internal.containers.RepeatedScalarFieldContainer[builtins.int]: ...
            def __init__(
                self,
                *,
                element: collections.abc.Iterable[builtins.int] | None = ...,
            ) -> None: ...
            def ClearField(
                self, field_name: typing_extensions.Literal["element", b"element"]
            ) -> None: ...

        @typing_extensions.final
        class StringArray(google.protobuf.message.Message):
            DESCRIPTOR: google.protobuf.descriptor.Descriptor

            ELEMENT_FIELD_NUMBER: builtins.int
            @property
            def element(
                self,
            ) -> google.protobuf.internal.containers.RepeatedScalarFieldContainer[builtins.str]: ...
            def __init__(
                self,
                *,
                element: collections.abc.Iterable[builtins.str] | None = ...,
            ) -> None: ...
            def ClearField(
                self, field_name: typing_extensions.Literal["element", b"element"]
            ) -> None: ...

        @typing_extensions.final
        class BoolArray(google.protobuf.message.Message):
            DESCRIPTOR: google.protobuf.descriptor.Descriptor

            ELEMENT_FIELD_NUMBER: builtins.int
            @property
            def element(
                self,
            ) -> google.protobuf.internal.containers.RepeatedScalarFieldContainer[
                builtins.bool
            ]: ...
            def __init__(
                self,
                *,
                element: collections.abc.Iterable[builtins.bool] | None = ...,
            ) -> None: ...
            def ClearField(
                self, field_name: typing_extensions.Literal["element", b"element"]
            ) -> None: ...

        LONG_VAL_FIELD_NUMBER: builtins.int
        INT_VAL_FIELD_NUMBER: builtins.int
        DOUBLE_VAL_FIELD_NUMBER: builtins.int
        FLOAT_VAL_FIELD_NUMBER: builtins.int
        BOOL_VAL_FIELD_NUMBER: builtins.int
        STR_VAL_FIELD_NUMBER: builtins.int
        DOUBLE_ARRAY_FIELD_NUMBER: builtins.int
        FLOAT_ARRAY_FIELD_NUMBER: builtins.int
        LONG_ARRAY_FIELD_NUMBER: builtins.int
        INT_ARRAY_FIELD_NUMBER: builtins.int
        STR_ARRAY_FIELD_NUMBER: builtins.int
        BOOL_ARRAY_FIELD_NUMBER: builtins.int
        long_val: builtins.int
        int_val: builtins.int
        double_val: builtins.float
        float_val: builtins.float
        bool_val: builtins.bool
        str_val: builtins.str
        @property
        def double_array(self) -> global___MlCommand.ParamValue.DoubleArray: ...
        @property
        def float_array(self) -> global___MlCommand.ParamValue.FloatArray: ...
        @property
        def long_array(self) -> global___MlCommand.ParamValue.LongArray: ...
        @property
        def int_array(self) -> global___MlCommand.ParamValue.IntArray: ...
        @property
        def str_array(self) -> global___MlCommand.ParamValue.StringArray: ...
        @property
        def bool_array(self) -> global___MlCommand.ParamValue.BoolArray: ...
        def __init__(
            self,
            *,
            long_val: builtins.int = ...,
            int_val: builtins.int = ...,
            double_val: builtins.float = ...,
            float_val: builtins.float = ...,
            bool_val: builtins.bool = ...,
            str_val: builtins.str = ...,
            double_array: global___MlCommand.ParamValue.DoubleArray | None = ...,
            float_array: global___MlCommand.ParamValue.FloatArray | None = ...,
            long_array: global___MlCommand.ParamValue.LongArray | None = ...,
            int_array: global___MlCommand.ParamValue.IntArray | None = ...,
            str_array: global___MlCommand.ParamValue.StringArray | None = ...,
            bool_array: global___MlCommand.ParamValue.BoolArray | None = ...,
        ) -> None: ...
        def HasField(
            self,
            field_name: typing_extensions.Literal[
                "bool_array",
                b"bool_array",
                "bool_val",
                b"bool_val",
                "double_array",
                b"double_array",
                "double_val",
                b"double_val",
                "float_array",
                b"float_array",
                "float_val",
                b"float_val",
                "int_array",
                b"int_array",
                "int_val",
                b"int_val",
                "long_array",
                b"long_array",
                "long_val",
                b"long_val",
                "str_array",
                b"str_array",
                "str_val",
                b"str_val",
                "value",
                b"value",
            ],
        ) -> builtins.bool: ...
        def ClearField(
            self,
            field_name: typing_extensions.Literal[
                "bool_array",
                b"bool_array",
                "bool_val",
                b"bool_val",
                "double_array",
                b"double_array",
                "double_val",
                b"double_val",
                "float_array",
                b"float_array",
                "float_val",
                b"float_val",
                "int_array",
                b"int_array",
                "int_val",
                b"int_val",
                "long_array",
                b"long_array",
                "long_val",
                b"long_val",
                "str_array",
                b"str_array",
                "str_val",
                b"str_val",
                "value",
                b"value",
            ],
        ) -> None: ...
        def WhichOneof(
            self, oneof_group: typing_extensions.Literal["value", b"value"]
        ) -> typing_extensions.Literal[
            "long_val",
            "int_val",
            "double_val",
            "float_val",
            "bool_val",
            "str_val",
            "double_array",
            "float_array",
            "long_array",
            "int_array",
            "str_array",
            "bool_array",
        ] | None: ...

    @typing_extensions.final
    class Params(google.protobuf.message.Message):
        DESCRIPTOR: google.protobuf.descriptor.Descriptor

        @typing_extensions.final
        class ParamsEntry(google.protobuf.message.Message):
            DESCRIPTOR: google.protobuf.descriptor.Descriptor

            KEY_FIELD_NUMBER: builtins.int
            VALUE_FIELD_NUMBER: builtins.int
            key: builtins.str
            @property
            def value(self) -> global___MlCommand.ParamValue: ...
            def __init__(
                self,
                *,
                key: builtins.str = ...,
                value: global___MlCommand.ParamValue | None = ...,
            ) -> None: ...
            def HasField(
                self, field_name: typing_extensions.Literal["value", b"value"]
            ) -> builtins.bool: ...
            def ClearField(
                self, field_name: typing_extensions.Literal["key", b"key", "value", b"value"]
            ) -> None: ...

        @typing_extensions.final
        class DefaultParamsEntry(google.protobuf.message.Message):
            DESCRIPTOR: google.protobuf.descriptor.Descriptor

            KEY_FIELD_NUMBER: builtins.int
            VALUE_FIELD_NUMBER: builtins.int
            key: builtins.str
            @property
            def value(self) -> global___MlCommand.ParamValue: ...
            def __init__(
                self,
                *,
                key: builtins.str = ...,
                value: global___MlCommand.ParamValue | None = ...,
            ) -> None: ...
            def HasField(
                self, field_name: typing_extensions.Literal["value", b"value"]
            ) -> builtins.bool: ...
            def ClearField(
                self, field_name: typing_extensions.Literal["key", b"key", "value", b"value"]
            ) -> None: ...

        PARAMS_FIELD_NUMBER: builtins.int
        DEFAULT_PARAMS_FIELD_NUMBER: builtins.int
        @property
        def params(
            self,
        ) -> google.protobuf.internal.containers.MessageMap[
            builtins.str, global___MlCommand.ParamValue
        ]: ...
        @property
        def default_params(
            self,
        ) -> google.protobuf.internal.containers.MessageMap[
            builtins.str, global___MlCommand.ParamValue
        ]: ...
        def __init__(
            self,
            *,
            params: collections.abc.Mapping[builtins.str, global___MlCommand.ParamValue]
            | None = ...,
            default_params: collections.abc.Mapping[builtins.str, global___MlCommand.ParamValue]
            | None = ...,
        ) -> None: ...
        def ClearField(
            self,
            field_name: typing_extensions.Literal[
                "default_params", b"default_params", "params", b"params"
            ],
        ) -> None: ...

    @typing_extensions.final
    class ConstructStage(google.protobuf.message.Message):
        DESCRIPTOR: google.protobuf.descriptor.Descriptor

        UID_FIELD_NUMBER: builtins.int
        CLASS_NAME_FIELD_NUMBER: builtins.int
        uid: builtins.str
        """stage (estimator / transformer) uid."""
        class_name: builtins.str
        def __init__(
            self,
            *,
            uid: builtins.str = ...,
            class_name: builtins.str = ...,
        ) -> None: ...
        def ClearField(
            self, field_name: typing_extensions.Literal["class_name", b"class_name", "uid", b"uid"]
        ) -> None: ...

    @typing_extensions.final
    class DestructObject(google.protobuf.message.Message):
        DESCRIPTOR: google.protobuf.descriptor.Descriptor

        ID_FIELD_NUMBER: builtins.int
        id: builtins.int
        """unique id of java object in server side"""
        def __init__(
            self,
            *,
            id: builtins.int = ...,
        ) -> None: ...
        def ClearField(self, field_name: typing_extensions.Literal["id", b"id"]) -> None: ...

    @typing_extensions.final
    class Fit(google.protobuf.message.Message):
        DESCRIPTOR: google.protobuf.descriptor.Descriptor

        ID_FIELD_NUMBER: builtins.int
        INPUT_FIELD_NUMBER: builtins.int
        id: builtins.int
        """id of server side estimator java object"""
        @property
        def input(self) -> pyspark.sql.connect.proto.relations_pb2.Relation: ...
        def __init__(
            self,
            *,
            id: builtins.int = ...,
            input: pyspark.sql.connect.proto.relations_pb2.Relation | None = ...,
        ) -> None: ...
        def HasField(
            self, field_name: typing_extensions.Literal["input", b"input"]
        ) -> builtins.bool: ...
        def ClearField(
            self, field_name: typing_extensions.Literal["id", b"id", "input", b"input"]
        ) -> None: ...

    @typing_extensions.final
    class Transform(google.protobuf.message.Message):
        DESCRIPTOR: google.protobuf.descriptor.Descriptor

        ID_FIELD_NUMBER: builtins.int
        INPUT_FIELD_NUMBER: builtins.int
        id: builtins.int
        """id of server side transformer / model java object"""
        @property
        def input(self) -> pyspark.sql.connect.proto.relations_pb2.Relation: ...
        def __init__(
            self,
            *,
            id: builtins.int = ...,
            input: pyspark.sql.connect.proto.relations_pb2.Relation | None = ...,
        ) -> None: ...
        def HasField(
            self, field_name: typing_extensions.Literal["input", b"input"]
        ) -> builtins.bool: ...
        def ClearField(
            self, field_name: typing_extensions.Literal["id", b"id", "input", b"input"]
        ) -> None: ...

    @typing_extensions.final
    class TransferParamsToServer(google.protobuf.message.Message):
        DESCRIPTOR: google.protobuf.descriptor.Descriptor

        ID_FIELD_NUMBER: builtins.int
        PARAMS_FIELD_NUMBER: builtins.int
        id: builtins.int
        """id of server side estimator / transformer / model java object"""
        @property
        def params(self) -> global___MlCommand.Params: ...
        def __init__(
            self,
            *,
            id: builtins.int = ...,
            params: global___MlCommand.Params | None = ...,
        ) -> None: ...
        def HasField(
            self, field_name: typing_extensions.Literal["params", b"params"]
        ) -> builtins.bool: ...
        def ClearField(
            self, field_name: typing_extensions.Literal["id", b"id", "params", b"params"]
        ) -> None: ...

    @typing_extensions.final
    class TransferParamsFromServer(google.protobuf.message.Message):
        DESCRIPTOR: google.protobuf.descriptor.Descriptor

        ID_FIELD_NUMBER: builtins.int
        id: builtins.int
        def __init__(
            self,
            *,
            id: builtins.int = ...,
        ) -> None: ...
        def ClearField(self, field_name: typing_extensions.Literal["id", b"id"]) -> None: ...

    CONSTRUCT_STAGE_FIELD_NUMBER: builtins.int
    DESTRUCT_OBJECT_FIELD_NUMBER: builtins.int
    FIT_FIELD_NUMBER: builtins.int
    TRANSFORM_FIELD_NUMBER: builtins.int
    TRANSFER_PARAMS_TO_SERVER_FIELD_NUMBER: builtins.int
    TRANSFER_PARAMS_FROM_SERVER_FIELD_NUMBER: builtins.int
    @property
    def construct_stage(self) -> global___MlCommand.ConstructStage: ...
    @property
    def destruct_object(self) -> global___MlCommand.DestructObject: ...
    @property
    def fit(self) -> global___MlCommand.Fit: ...
    @property
    def transform(self) -> global___MlCommand.Transform: ...
    @property
    def transfer_params_to_server(self) -> global___MlCommand.TransferParamsToServer: ...
    @property
    def transfer_params_from_server(self) -> global___MlCommand.TransferParamsFromServer: ...
    def __init__(
        self,
        *,
        construct_stage: global___MlCommand.ConstructStage | None = ...,
        destruct_object: global___MlCommand.DestructObject | None = ...,
        fit: global___MlCommand.Fit | None = ...,
        transform: global___MlCommand.Transform | None = ...,
        transfer_params_to_server: global___MlCommand.TransferParamsToServer | None = ...,
        transfer_params_from_server: global___MlCommand.TransferParamsFromServer | None = ...,
    ) -> None: ...
    def HasField(
        self,
        field_name: typing_extensions.Literal[
            "construct_stage",
            b"construct_stage",
            "destruct_object",
            b"destruct_object",
            "fit",
            b"fit",
            "op_type",
            b"op_type",
            "transfer_params_from_server",
            b"transfer_params_from_server",
            "transfer_params_to_server",
            b"transfer_params_to_server",
            "transform",
            b"transform",
        ],
    ) -> builtins.bool: ...
    def ClearField(
        self,
        field_name: typing_extensions.Literal[
            "construct_stage",
            b"construct_stage",
            "destruct_object",
            b"destruct_object",
            "fit",
            b"fit",
            "op_type",
            b"op_type",
            "transfer_params_from_server",
            b"transfer_params_from_server",
            "transfer_params_to_server",
            b"transfer_params_to_server",
            "transform",
            b"transform",
        ],
    ) -> None: ...
    def WhichOneof(
        self, oneof_group: typing_extensions.Literal["op_type", b"op_type"]
    ) -> typing_extensions.Literal[
        "construct_stage",
        "destruct_object",
        "fit",
        "transform",
        "transfer_params_to_server",
        "transfer_params_from_server",
    ] | None: ...

global___MlCommand = MlCommand
