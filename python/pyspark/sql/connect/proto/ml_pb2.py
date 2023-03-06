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
# -*- coding: utf-8 -*-
# Generated by the protocol buffer compiler.  DO NOT EDIT!
# source: spark/connect/ml.proto
"""Generated protocol buffer code."""
from google.protobuf import descriptor as _descriptor
from google.protobuf import descriptor_pool as _descriptor_pool
from google.protobuf import message as _message
from google.protobuf import reflection as _reflection
from google.protobuf import symbol_database as _symbol_database

# @@protoc_insertion_point(imports)

_sym_db = _symbol_database.Default()


from google.protobuf import any_pb2 as google_dot_protobuf_dot_any__pb2
from pyspark.sql.connect.proto import expressions_pb2 as spark_dot_connect_dot_expressions__pb2
from pyspark.sql.connect.proto import types_pb2 as spark_dot_connect_dot_types__pb2
from pyspark.sql.connect.proto import relations_pb2 as spark_dot_connect_dot_relations__pb2
from pyspark.sql.connect.proto import ml_common_pb2 as spark_dot_connect_dot_ml__common__pb2


DESCRIPTOR = _descriptor_pool.Default().AddSerializedFile(
    b'\n\x16spark/connect/ml.proto\x12\rspark.connect\x1a\x19google/protobuf/any.proto\x1a\x1fspark/connect/expressions.proto\x1a\x19spark/connect/types.proto\x1a\x1dspark/connect/relations.proto\x1a\x1dspark/connect/ml_common.proto"`\n\tEvaluator\x12\x12\n\x04name\x18\x01 \x01(\tR\x04name\x12-\n\x06params\x18\x02 \x01(\x0b\x32\x15.spark.connect.ParamsR\x06params\x12\x10\n\x03uid\x18\x03 \x01(\tR\x03uid"\xac\t\n\tMlCommand\x12\x30\n\x03\x66it\x18\x01 \x01(\x0b\x32\x1c.spark.connect.MlCommand.FitH\x00R\x03\x66it\x12\x43\n\nmodel_attr\x18\x02 \x01(\x0b\x32".spark.connect.MlCommand.ModelAttrH\x00R\tmodelAttr\x12Y\n\x12model_summary_attr\x18\x03 \x01(\x0b\x32).spark.connect.MlCommand.ModelSummaryAttrH\x00R\x10modelSummaryAttr\x12\x43\n\nload_model\x18\x04 \x01(\x0b\x32".spark.connect.MlCommand.LoadModelH\x00R\tloadModel\x12\x43\n\nsave_model\x18\x05 \x01(\x0b\x32".spark.connect.MlCommand.SaveModelH\x00R\tsaveModel\x12?\n\x08\x65valuate\x18\x06 \x01(\x0b\x32!.spark.connect.MlCommand.EvaluateH\x00R\x08\x65valuate\x1al\n\x03\x46it\x12\x32\n\testimator\x18\x01 \x01(\x0b\x32\x14.spark.connect.StageR\testimator\x12\x31\n\x07\x64\x61taset\x18\x02 \x01(\x0b\x32\x17.spark.connect.RelationR\x07\x64\x61taset\x1a\x42\n\x08\x45valuate\x12\x36\n\tevaluator\x18\x01 \x01(\x0b\x32\x18.spark.connect.EvaluatorR\tevaluator\x1a\x33\n\tLoadModel\x12\x12\n\x04name\x18\x01 \x01(\tR\x04name\x12\x12\n\x04path\x18\x02 \x01(\tR\x04path\x1a\xe6\x01\n\tSaveModel\x12 \n\x0cmodel_ref_id\x18\x01 \x01(\x03R\nmodelRefId\x12\x12\n\x04path\x18\x02 \x01(\tR\x04path\x12\x1c\n\toverwrite\x18\x03 \x01(\x08R\toverwrite\x12I\n\x07options\x18\x04 \x03(\x0b\x32/.spark.connect.MlCommand.SaveModel.OptionsEntryR\x07options\x1a:\n\x0cOptionsEntry\x12\x10\n\x03key\x18\x01 \x01(\tR\x03key\x12\x14\n\x05value\x18\x02 \x01(\tR\x05value:\x02\x38\x01\x1a\x41\n\tModelAttr\x12 \n\x0cmodel_ref_id\x18\x01 \x01(\x03R\nmodelRefId\x12\x12\n\x04name\x18\x02 \x01(\tR\x04name\x1a\xdb\x01\n\x10ModelSummaryAttr\x12 \n\x0cmodel_ref_id\x18\x01 \x01(\x03R\nmodelRefId\x12\x12\n\x04name\x18\x02 \x01(\tR\x04name\x12-\n\x06params\x18\x03 \x01(\x0b\x32\x15.spark.connect.ParamsR\x06params\x12K\n\x12\x65valuation_dataset\x18\x04 \x01(\x0b\x32\x17.spark.connect.RelationH\x00R\x11\x65valuationDataset\x88\x01\x01\x42\x15\n\x13_evaluation_datasetB\x11\n\x0fml_command_type"\xe9\x02\n\x11MlCommandResponse\x12=\n\x07literal\x18\x01 \x01(\x0b\x32!.spark.connect.Expression.LiteralH\x00R\x07literal\x12K\n\nmodel_info\x18\x02 \x01(\x0b\x32*.spark.connect.MlCommandResponse.ModelInfoH\x00R\tmodelInfo\x12/\n\x06vector\x18\x03 \x01(\x0b\x32\x15.spark.connect.VectorH\x00R\x06vector\x12/\n\x06matrix\x18\x04 \x01(\x0b\x32\x15.spark.connect.MatrixH\x00R\x06matrix\x1aJ\n\tModelInfo\x12 \n\x0cmodel_ref_id\x18\x01 \x01(\x03R\nmodelRefId\x12\x1b\n\tmodel_uid\x18\x02 \x01(\tR\x08modelUidB\x1a\n\x18ml_command_response_type"\xf0\x01\n\x06Vector\x12\x33\n\x05\x64\x65nse\x18\x01 \x01(\x0b\x32\x1b.spark.connect.Vector.DenseH\x00R\x05\x64\x65nse\x12\x36\n\x06sparse\x18\x02 \x01(\x0b\x32\x1c.spark.connect.Vector.SparseH\x00R\x06sparse\x1a\x1f\n\x05\x44\x65nse\x12\x16\n\x06values\x18\x01 \x03(\x01R\x06values\x1aN\n\x06Sparse\x12\x12\n\x04size\x18\x01 \x01(\x05R\x04size\x12\x18\n\x07indices\x18\x02 \x03(\x01R\x07indices\x12\x16\n\x06values\x18\x03 \x03(\x01R\x06valuesB\x08\n\x06one_of"\x8f\x03\n\x06Matrix\x12\x33\n\x05\x64\x65nse\x18\x01 \x01(\x0b\x32\x1b.spark.connect.Matrix.DenseH\x00R\x05\x64\x65nse\x12\x36\n\x06sparse\x18\x02 \x01(\x0b\x32\x1c.spark.connect.Matrix.SparseH\x00R\x06sparse\x1aU\n\x05\x44\x65nse\x12\x19\n\x08num_rows\x18\x01 \x01(\x05R\x07numRows\x12\x19\n\x08num_cols\x18\x02 \x01(\x05R\x07numCols\x12\x16\n\x06values\x18\x03 \x03(\x01R\x06values\x1a\xb6\x01\n\x06Sparse\x12\x19\n\x08num_rows\x18\x01 \x01(\x05R\x07numRows\x12\x19\n\x08num_cols\x18\x02 \x01(\x05R\x07numCols\x12\x18\n\x07\x63olptrs\x18\x03 \x03(\x01R\x07\x63olptrs\x12\x1f\n\x0brow_indices\x18\x04 \x03(\x01R\nrowIndices\x12\x16\n\x06values\x18\x05 \x03(\x01R\x06values\x12#\n\ris_transposed\x18\x06 \x01(\x08R\x0cisTransposedB\x08\n\x06one_ofB"\n\x1eorg.apache.spark.connect.protoP\x01\x62\x06proto3'
)


_EVALUATOR = DESCRIPTOR.message_types_by_name["Evaluator"]
_MLCOMMAND = DESCRIPTOR.message_types_by_name["MlCommand"]
_MLCOMMAND_FIT = _MLCOMMAND.nested_types_by_name["Fit"]
_MLCOMMAND_EVALUATE = _MLCOMMAND.nested_types_by_name["Evaluate"]
_MLCOMMAND_LOADMODEL = _MLCOMMAND.nested_types_by_name["LoadModel"]
_MLCOMMAND_SAVEMODEL = _MLCOMMAND.nested_types_by_name["SaveModel"]
_MLCOMMAND_SAVEMODEL_OPTIONSENTRY = _MLCOMMAND_SAVEMODEL.nested_types_by_name["OptionsEntry"]
_MLCOMMAND_MODELATTR = _MLCOMMAND.nested_types_by_name["ModelAttr"]
_MLCOMMAND_MODELSUMMARYATTR = _MLCOMMAND.nested_types_by_name["ModelSummaryAttr"]
_MLCOMMANDRESPONSE = DESCRIPTOR.message_types_by_name["MlCommandResponse"]
_MLCOMMANDRESPONSE_MODELINFO = _MLCOMMANDRESPONSE.nested_types_by_name["ModelInfo"]
_VECTOR = DESCRIPTOR.message_types_by_name["Vector"]
_VECTOR_DENSE = _VECTOR.nested_types_by_name["Dense"]
_VECTOR_SPARSE = _VECTOR.nested_types_by_name["Sparse"]
_MATRIX = DESCRIPTOR.message_types_by_name["Matrix"]
_MATRIX_DENSE = _MATRIX.nested_types_by_name["Dense"]
_MATRIX_SPARSE = _MATRIX.nested_types_by_name["Sparse"]
Evaluator = _reflection.GeneratedProtocolMessageType(
    "Evaluator",
    (_message.Message,),
    {
        "DESCRIPTOR": _EVALUATOR,
        "__module__": "spark.connect.ml_pb2"
        # @@protoc_insertion_point(class_scope:spark.connect.Evaluator)
    },
)
_sym_db.RegisterMessage(Evaluator)

MlCommand = _reflection.GeneratedProtocolMessageType(
    "MlCommand",
    (_message.Message,),
    {
        "Fit": _reflection.GeneratedProtocolMessageType(
            "Fit",
            (_message.Message,),
            {
                "DESCRIPTOR": _MLCOMMAND_FIT,
                "__module__": "spark.connect.ml_pb2"
                # @@protoc_insertion_point(class_scope:spark.connect.MlCommand.Fit)
            },
        ),
        "Evaluate": _reflection.GeneratedProtocolMessageType(
            "Evaluate",
            (_message.Message,),
            {
                "DESCRIPTOR": _MLCOMMAND_EVALUATE,
                "__module__": "spark.connect.ml_pb2"
                # @@protoc_insertion_point(class_scope:spark.connect.MlCommand.Evaluate)
            },
        ),
        "LoadModel": _reflection.GeneratedProtocolMessageType(
            "LoadModel",
            (_message.Message,),
            {
                "DESCRIPTOR": _MLCOMMAND_LOADMODEL,
                "__module__": "spark.connect.ml_pb2"
                # @@protoc_insertion_point(class_scope:spark.connect.MlCommand.LoadModel)
            },
        ),
        "SaveModel": _reflection.GeneratedProtocolMessageType(
            "SaveModel",
            (_message.Message,),
            {
                "OptionsEntry": _reflection.GeneratedProtocolMessageType(
                    "OptionsEntry",
                    (_message.Message,),
                    {
                        "DESCRIPTOR": _MLCOMMAND_SAVEMODEL_OPTIONSENTRY,
                        "__module__": "spark.connect.ml_pb2"
                        # @@protoc_insertion_point(class_scope:spark.connect.MlCommand.SaveModel.OptionsEntry)
                    },
                ),
                "DESCRIPTOR": _MLCOMMAND_SAVEMODEL,
                "__module__": "spark.connect.ml_pb2"
                # @@protoc_insertion_point(class_scope:spark.connect.MlCommand.SaveModel)
            },
        ),
        "ModelAttr": _reflection.GeneratedProtocolMessageType(
            "ModelAttr",
            (_message.Message,),
            {
                "DESCRIPTOR": _MLCOMMAND_MODELATTR,
                "__module__": "spark.connect.ml_pb2"
                # @@protoc_insertion_point(class_scope:spark.connect.MlCommand.ModelAttr)
            },
        ),
        "ModelSummaryAttr": _reflection.GeneratedProtocolMessageType(
            "ModelSummaryAttr",
            (_message.Message,),
            {
                "DESCRIPTOR": _MLCOMMAND_MODELSUMMARYATTR,
                "__module__": "spark.connect.ml_pb2"
                # @@protoc_insertion_point(class_scope:spark.connect.MlCommand.ModelSummaryAttr)
            },
        ),
        "DESCRIPTOR": _MLCOMMAND,
        "__module__": "spark.connect.ml_pb2"
        # @@protoc_insertion_point(class_scope:spark.connect.MlCommand)
    },
)
_sym_db.RegisterMessage(MlCommand)
_sym_db.RegisterMessage(MlCommand.Fit)
_sym_db.RegisterMessage(MlCommand.Evaluate)
_sym_db.RegisterMessage(MlCommand.LoadModel)
_sym_db.RegisterMessage(MlCommand.SaveModel)
_sym_db.RegisterMessage(MlCommand.SaveModel.OptionsEntry)
_sym_db.RegisterMessage(MlCommand.ModelAttr)
_sym_db.RegisterMessage(MlCommand.ModelSummaryAttr)

MlCommandResponse = _reflection.GeneratedProtocolMessageType(
    "MlCommandResponse",
    (_message.Message,),
    {
        "ModelInfo": _reflection.GeneratedProtocolMessageType(
            "ModelInfo",
            (_message.Message,),
            {
                "DESCRIPTOR": _MLCOMMANDRESPONSE_MODELINFO,
                "__module__": "spark.connect.ml_pb2"
                # @@protoc_insertion_point(class_scope:spark.connect.MlCommandResponse.ModelInfo)
            },
        ),
        "DESCRIPTOR": _MLCOMMANDRESPONSE,
        "__module__": "spark.connect.ml_pb2"
        # @@protoc_insertion_point(class_scope:spark.connect.MlCommandResponse)
    },
)
_sym_db.RegisterMessage(MlCommandResponse)
_sym_db.RegisterMessage(MlCommandResponse.ModelInfo)

Vector = _reflection.GeneratedProtocolMessageType(
    "Vector",
    (_message.Message,),
    {
        "Dense": _reflection.GeneratedProtocolMessageType(
            "Dense",
            (_message.Message,),
            {
                "DESCRIPTOR": _VECTOR_DENSE,
                "__module__": "spark.connect.ml_pb2"
                # @@protoc_insertion_point(class_scope:spark.connect.Vector.Dense)
            },
        ),
        "Sparse": _reflection.GeneratedProtocolMessageType(
            "Sparse",
            (_message.Message,),
            {
                "DESCRIPTOR": _VECTOR_SPARSE,
                "__module__": "spark.connect.ml_pb2"
                # @@protoc_insertion_point(class_scope:spark.connect.Vector.Sparse)
            },
        ),
        "DESCRIPTOR": _VECTOR,
        "__module__": "spark.connect.ml_pb2"
        # @@protoc_insertion_point(class_scope:spark.connect.Vector)
    },
)
_sym_db.RegisterMessage(Vector)
_sym_db.RegisterMessage(Vector.Dense)
_sym_db.RegisterMessage(Vector.Sparse)

Matrix = _reflection.GeneratedProtocolMessageType(
    "Matrix",
    (_message.Message,),
    {
        "Dense": _reflection.GeneratedProtocolMessageType(
            "Dense",
            (_message.Message,),
            {
                "DESCRIPTOR": _MATRIX_DENSE,
                "__module__": "spark.connect.ml_pb2"
                # @@protoc_insertion_point(class_scope:spark.connect.Matrix.Dense)
            },
        ),
        "Sparse": _reflection.GeneratedProtocolMessageType(
            "Sparse",
            (_message.Message,),
            {
                "DESCRIPTOR": _MATRIX_SPARSE,
                "__module__": "spark.connect.ml_pb2"
                # @@protoc_insertion_point(class_scope:spark.connect.Matrix.Sparse)
            },
        ),
        "DESCRIPTOR": _MATRIX,
        "__module__": "spark.connect.ml_pb2"
        # @@protoc_insertion_point(class_scope:spark.connect.Matrix)
    },
)
_sym_db.RegisterMessage(Matrix)
_sym_db.RegisterMessage(Matrix.Dense)
_sym_db.RegisterMessage(Matrix.Sparse)

if _descriptor._USE_C_DESCRIPTORS == False:

    DESCRIPTOR._options = None
    DESCRIPTOR._serialized_options = b"\n\036org.apache.spark.connect.protoP\001"
    _MLCOMMAND_SAVEMODEL_OPTIONSENTRY._options = None
    _MLCOMMAND_SAVEMODEL_OPTIONSENTRY._serialized_options = b"8\001"
    _EVALUATOR._serialized_start = 190
    _EVALUATOR._serialized_end = 286
    _MLCOMMAND._serialized_start = 289
    _MLCOMMAND._serialized_end = 1485
    _MLCOMMAND_FIT._serialized_start = 715
    _MLCOMMAND_FIT._serialized_end = 823
    _MLCOMMAND_EVALUATE._serialized_start = 825
    _MLCOMMAND_EVALUATE._serialized_end = 891
    _MLCOMMAND_LOADMODEL._serialized_start = 893
    _MLCOMMAND_LOADMODEL._serialized_end = 944
    _MLCOMMAND_SAVEMODEL._serialized_start = 947
    _MLCOMMAND_SAVEMODEL._serialized_end = 1177
    _MLCOMMAND_SAVEMODEL_OPTIONSENTRY._serialized_start = 1119
    _MLCOMMAND_SAVEMODEL_OPTIONSENTRY._serialized_end = 1177
    _MLCOMMAND_MODELATTR._serialized_start = 1179
    _MLCOMMAND_MODELATTR._serialized_end = 1244
    _MLCOMMAND_MODELSUMMARYATTR._serialized_start = 1247
    _MLCOMMAND_MODELSUMMARYATTR._serialized_end = 1466
    _MLCOMMANDRESPONSE._serialized_start = 1488
    _MLCOMMANDRESPONSE._serialized_end = 1849
    _MLCOMMANDRESPONSE_MODELINFO._serialized_start = 1747
    _MLCOMMANDRESPONSE_MODELINFO._serialized_end = 1821
    _VECTOR._serialized_start = 1852
    _VECTOR._serialized_end = 2092
    _VECTOR_DENSE._serialized_start = 1971
    _VECTOR_DENSE._serialized_end = 2002
    _VECTOR_SPARSE._serialized_start = 2004
    _VECTOR_SPARSE._serialized_end = 2082
    _MATRIX._serialized_start = 2095
    _MATRIX._serialized_end = 2494
    _MATRIX_DENSE._serialized_start = 2214
    _MATRIX_DENSE._serialized_end = 2299
    _MATRIX_SPARSE._serialized_start = 2302
    _MATRIX_SPARSE._serialized_end = 2484
# @@protoc_insertion_point(module_scope)
