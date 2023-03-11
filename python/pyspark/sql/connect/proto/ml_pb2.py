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


from pyspark.sql.connect.proto import expressions_pb2 as spark_dot_connect_dot_expressions__pb2
from pyspark.sql.connect.proto import relations_pb2 as spark_dot_connect_dot_relations__pb2
from pyspark.sql.connect.proto import ml_common_pb2 as spark_dot_connect_dot_ml__common__pb2


DESCRIPTOR = _descriptor_pool.Default().AddSerializedFile(
    b'\n\x16spark/connect/ml.proto\x12\rspark.connect\x1a\x1fspark/connect/expressions.proto\x1a\x1dspark/connect/relations.proto\x1a\x1dspark/connect/ml_common.proto"d\n\x0bMlEvaluator\x12\x12\n\x04name\x18\x01 \x01(\tR\x04name\x12/\n\x06params\x18\x02 \x01(\x0b\x32\x17.spark.connect.MlParamsR\x06params\x12\x10\n\x03uid\x18\x03 \x01(\tR\x03uid"\xf4\x10\n\tMlCommand\x12\x30\n\x03\x66it\x18\x01 \x01(\x0b\x32\x1c.spark.connect.MlCommand.FitH\x00R\x03\x66it\x12S\n\x10\x66\x65tch_model_attr\x18\x02 \x01(\x0b\x32\'.spark.connect.MlCommand.FetchModelAttrH\x00R\x0e\x66\x65tchModelAttr\x12i\n\x18\x66\x65tch_model_summary_attr\x18\x03 \x01(\x0b\x32..spark.connect.MlCommand.FetchModelSummaryAttrH\x00R\x15\x66\x65tchModelSummaryAttr\x12\x43\n\nload_model\x18\x04 \x01(\x0b\x32".spark.connect.MlCommand.LoadModelH\x00R\tloadModel\x12\x43\n\nsave_model\x18\x05 \x01(\x0b\x32".spark.connect.MlCommand.SaveModelH\x00R\tsaveModel\x12?\n\x08\x65valuate\x18\x06 \x01(\x0b\x32!.spark.connect.MlCommand.EvaluateH\x00R\x08\x65valuate\x12\x43\n\nsave_stage\x18\x07 \x01(\x0b\x32".spark.connect.MlCommand.SaveStageH\x00R\tsaveStage\x12\x43\n\nload_stage\x18\x08 \x01(\x0b\x32".spark.connect.MlCommand.LoadStageH\x00R\tloadStage\x12O\n\x0esave_evaluator\x18\t \x01(\x0b\x32&.spark.connect.MlCommand.SaveEvaluatorH\x00R\rsaveEvaluator\x12O\n\x0eload_evaluator\x18\n \x01(\x0b\x32&.spark.connect.MlCommand.LoadEvaluatorH\x00R\rloadEvaluator\x1an\n\x03\x46it\x12\x34\n\testimator\x18\x01 \x01(\x0b\x32\x16.spark.connect.MlStageR\testimator\x12\x31\n\x07\x64\x61taset\x18\x02 \x01(\x0b\x32\x17.spark.connect.RelationR\x07\x64\x61taset\x1a\x44\n\x08\x45valuate\x12\x38\n\tevaluator\x18\x01 \x01(\x0b\x32\x1a.spark.connect.MlEvaluatorR\tevaluator\x1a\x33\n\tLoadModel\x12\x12\n\x04name\x18\x01 \x01(\tR\x04name\x12\x12\n\x04path\x18\x02 \x01(\tR\x04path\x1a\xe6\x01\n\tSaveModel\x12 \n\x0cmodel_ref_id\x18\x01 \x01(\x03R\nmodelRefId\x12\x12\n\x04path\x18\x02 \x01(\tR\x04path\x12\x1c\n\toverwrite\x18\x03 \x01(\x08R\toverwrite\x12I\n\x07options\x18\x04 \x03(\x0b\x32/.spark.connect.MlCommand.SaveModel.OptionsEntryR\x07options\x1a:\n\x0cOptionsEntry\x12\x10\n\x03key\x18\x01 \x01(\tR\x03key\x12\x14\n\x05value\x18\x02 \x01(\tR\x05value:\x02\x38\x01\x1a\x33\n\tLoadStage\x12\x12\n\x04name\x18\x01 \x01(\tR\x04name\x12\x12\n\x04path\x18\x02 \x01(\tR\x04path\x1a\xf2\x01\n\tSaveStage\x12,\n\x05stage\x18\x01 \x01(\x0b\x32\x16.spark.connect.MlStageR\x05stage\x12\x12\n\x04path\x18\x02 \x01(\tR\x04path\x12\x1c\n\toverwrite\x18\x03 \x01(\x08R\toverwrite\x12I\n\x07options\x18\x04 \x03(\x0b\x32/.spark.connect.MlCommand.SaveStage.OptionsEntryR\x07options\x1a:\n\x0cOptionsEntry\x12\x10\n\x03key\x18\x01 \x01(\tR\x03key\x12\x14\n\x05value\x18\x02 \x01(\tR\x05value:\x02\x38\x01\x1a\x37\n\rLoadEvaluator\x12\x12\n\x04name\x18\x01 \x01(\tR\x04name\x12\x12\n\x04path\x18\x02 \x01(\tR\x04path\x1a\x86\x02\n\rSaveEvaluator\x12\x38\n\tevaluator\x18\x01 \x01(\x0b\x32\x1a.spark.connect.MlEvaluatorR\tevaluator\x12\x12\n\x04path\x18\x02 \x01(\tR\x04path\x12\x1c\n\toverwrite\x18\x03 \x01(\x08R\toverwrite\x12M\n\x07options\x18\x04 \x03(\x0b\x32\x33.spark.connect.MlCommand.SaveEvaluator.OptionsEntryR\x07options\x1a:\n\x0cOptionsEntry\x12\x10\n\x03key\x18\x01 \x01(\tR\x03key\x12\x14\n\x05value\x18\x02 \x01(\tR\x05value:\x02\x38\x01\x1a\x46\n\x0e\x46\x65tchModelAttr\x12 \n\x0cmodel_ref_id\x18\x01 \x01(\x03R\nmodelRefId\x12\x12\n\x04name\x18\x02 \x01(\tR\x04name\x1a\xe2\x01\n\x15\x46\x65tchModelSummaryAttr\x12 \n\x0cmodel_ref_id\x18\x01 \x01(\x03R\nmodelRefId\x12\x12\n\x04name\x18\x02 \x01(\tR\x04name\x12/\n\x06params\x18\x03 \x01(\x0b\x32\x17.spark.connect.MlParamsR\x06params\x12K\n\x12\x65valuation_dataset\x18\x04 \x01(\x0b\x32\x17.spark.connect.RelationH\x00R\x11\x65valuationDataset\x88\x01\x01\x42\x15\n\x13_evaluation_datasetB\x11\n\x0fml_command_type"\xe9\x02\n\x11MlCommandResponse\x12=\n\x07literal\x18\x01 \x01(\x0b\x32!.spark.connect.Expression.LiteralH\x00R\x07literal\x12K\n\nmodel_info\x18\x02 \x01(\x0b\x32*.spark.connect.MlCommandResponse.ModelInfoH\x00R\tmodelInfo\x12/\n\x06vector\x18\x03 \x01(\x0b\x32\x15.spark.connect.VectorH\x00R\x06vector\x12/\n\x06matrix\x18\x04 \x01(\x0b\x32\x15.spark.connect.MatrixH\x00R\x06matrix\x1aJ\n\tModelInfo\x12 \n\x0cmodel_ref_id\x18\x01 \x01(\x03R\nmodelRefId\x12\x1b\n\tmodel_uid\x18\x02 \x01(\tR\x08modelUidB\x1a\n\x18ml_command_response_type"\xe8\x01\n\x06Vector\x12\x33\n\x05\x64\x65nse\x18\x01 \x01(\x0b\x32\x1b.spark.connect.Vector.DenseH\x00R\x05\x64\x65nse\x12\x36\n\x06sparse\x18\x02 \x01(\x0b\x32\x1c.spark.connect.Vector.SparseH\x00R\x06sparse\x1a\x1d\n\x05\x44\x65nse\x12\x14\n\x05value\x18\x01 \x03(\x01R\x05value\x1aH\n\x06Sparse\x12\x12\n\x04size\x18\x01 \x01(\x05R\x04size\x12\x14\n\x05index\x18\x02 \x03(\x01R\x05index\x12\x14\n\x05value\x18\x03 \x03(\x01R\x05valueB\x08\n\x06one_of"\xaa\x03\n\x06Matrix\x12\x33\n\x05\x64\x65nse\x18\x01 \x01(\x0b\x32\x1b.spark.connect.Matrix.DenseH\x00R\x05\x64\x65nse\x12\x36\n\x06sparse\x18\x02 \x01(\x0b\x32\x1c.spark.connect.Matrix.SparseH\x00R\x06sparse\x1ax\n\x05\x44\x65nse\x12\x19\n\x08num_rows\x18\x01 \x01(\x05R\x07numRows\x12\x19\n\x08num_cols\x18\x02 \x01(\x05R\x07numCols\x12\x14\n\x05value\x18\x03 \x03(\x01R\x05value\x12#\n\ris_transposed\x18\x04 \x01(\x08R\x0cisTransposed\x1a\xae\x01\n\x06Sparse\x12\x19\n\x08num_rows\x18\x01 \x01(\x05R\x07numRows\x12\x19\n\x08num_cols\x18\x02 \x01(\x05R\x07numCols\x12\x16\n\x06\x63olptr\x18\x03 \x03(\x01R\x06\x63olptr\x12\x1b\n\trow_index\x18\x04 \x03(\x01R\x08rowIndex\x12\x14\n\x05value\x18\x05 \x03(\x01R\x05value\x12#\n\ris_transposed\x18\x06 \x01(\x08R\x0cisTransposedB\x08\n\x06one_ofB"\n\x1eorg.apache.spark.connect.protoP\x01\x62\x06proto3'
)


_MLEVALUATOR = DESCRIPTOR.message_types_by_name["MlEvaluator"]
_MLCOMMAND = DESCRIPTOR.message_types_by_name["MlCommand"]
_MLCOMMAND_FIT = _MLCOMMAND.nested_types_by_name["Fit"]
_MLCOMMAND_EVALUATE = _MLCOMMAND.nested_types_by_name["Evaluate"]
_MLCOMMAND_LOADMODEL = _MLCOMMAND.nested_types_by_name["LoadModel"]
_MLCOMMAND_SAVEMODEL = _MLCOMMAND.nested_types_by_name["SaveModel"]
_MLCOMMAND_SAVEMODEL_OPTIONSENTRY = _MLCOMMAND_SAVEMODEL.nested_types_by_name["OptionsEntry"]
_MLCOMMAND_LOADSTAGE = _MLCOMMAND.nested_types_by_name["LoadStage"]
_MLCOMMAND_SAVESTAGE = _MLCOMMAND.nested_types_by_name["SaveStage"]
_MLCOMMAND_SAVESTAGE_OPTIONSENTRY = _MLCOMMAND_SAVESTAGE.nested_types_by_name["OptionsEntry"]
_MLCOMMAND_LOADEVALUATOR = _MLCOMMAND.nested_types_by_name["LoadEvaluator"]
_MLCOMMAND_SAVEEVALUATOR = _MLCOMMAND.nested_types_by_name["SaveEvaluator"]
_MLCOMMAND_SAVEEVALUATOR_OPTIONSENTRY = _MLCOMMAND_SAVEEVALUATOR.nested_types_by_name[
    "OptionsEntry"
]
_MLCOMMAND_FETCHMODELATTR = _MLCOMMAND.nested_types_by_name["FetchModelAttr"]
_MLCOMMAND_FETCHMODELSUMMARYATTR = _MLCOMMAND.nested_types_by_name["FetchModelSummaryAttr"]
_MLCOMMANDRESPONSE = DESCRIPTOR.message_types_by_name["MlCommandResponse"]
_MLCOMMANDRESPONSE_MODELINFO = _MLCOMMANDRESPONSE.nested_types_by_name["ModelInfo"]
_VECTOR = DESCRIPTOR.message_types_by_name["Vector"]
_VECTOR_DENSE = _VECTOR.nested_types_by_name["Dense"]
_VECTOR_SPARSE = _VECTOR.nested_types_by_name["Sparse"]
_MATRIX = DESCRIPTOR.message_types_by_name["Matrix"]
_MATRIX_DENSE = _MATRIX.nested_types_by_name["Dense"]
_MATRIX_SPARSE = _MATRIX.nested_types_by_name["Sparse"]
MlEvaluator = _reflection.GeneratedProtocolMessageType(
    "MlEvaluator",
    (_message.Message,),
    {
        "DESCRIPTOR": _MLEVALUATOR,
        "__module__": "spark.connect.ml_pb2"
        # @@protoc_insertion_point(class_scope:spark.connect.MlEvaluator)
    },
)
_sym_db.RegisterMessage(MlEvaluator)

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
        "LoadStage": _reflection.GeneratedProtocolMessageType(
            "LoadStage",
            (_message.Message,),
            {
                "DESCRIPTOR": _MLCOMMAND_LOADSTAGE,
                "__module__": "spark.connect.ml_pb2"
                # @@protoc_insertion_point(class_scope:spark.connect.MlCommand.LoadStage)
            },
        ),
        "SaveStage": _reflection.GeneratedProtocolMessageType(
            "SaveStage",
            (_message.Message,),
            {
                "OptionsEntry": _reflection.GeneratedProtocolMessageType(
                    "OptionsEntry",
                    (_message.Message,),
                    {
                        "DESCRIPTOR": _MLCOMMAND_SAVESTAGE_OPTIONSENTRY,
                        "__module__": "spark.connect.ml_pb2"
                        # @@protoc_insertion_point(class_scope:spark.connect.MlCommand.SaveStage.OptionsEntry)
                    },
                ),
                "DESCRIPTOR": _MLCOMMAND_SAVESTAGE,
                "__module__": "spark.connect.ml_pb2"
                # @@protoc_insertion_point(class_scope:spark.connect.MlCommand.SaveStage)
            },
        ),
        "LoadEvaluator": _reflection.GeneratedProtocolMessageType(
            "LoadEvaluator",
            (_message.Message,),
            {
                "DESCRIPTOR": _MLCOMMAND_LOADEVALUATOR,
                "__module__": "spark.connect.ml_pb2"
                # @@protoc_insertion_point(class_scope:spark.connect.MlCommand.LoadEvaluator)
            },
        ),
        "SaveEvaluator": _reflection.GeneratedProtocolMessageType(
            "SaveEvaluator",
            (_message.Message,),
            {
                "OptionsEntry": _reflection.GeneratedProtocolMessageType(
                    "OptionsEntry",
                    (_message.Message,),
                    {
                        "DESCRIPTOR": _MLCOMMAND_SAVEEVALUATOR_OPTIONSENTRY,
                        "__module__": "spark.connect.ml_pb2"
                        # @@protoc_insertion_point(class_scope:spark.connect.MlCommand.SaveEvaluator.OptionsEntry)
                    },
                ),
                "DESCRIPTOR": _MLCOMMAND_SAVEEVALUATOR,
                "__module__": "spark.connect.ml_pb2"
                # @@protoc_insertion_point(class_scope:spark.connect.MlCommand.SaveEvaluator)
            },
        ),
        "FetchModelAttr": _reflection.GeneratedProtocolMessageType(
            "FetchModelAttr",
            (_message.Message,),
            {
                "DESCRIPTOR": _MLCOMMAND_FETCHMODELATTR,
                "__module__": "spark.connect.ml_pb2"
                # @@protoc_insertion_point(class_scope:spark.connect.MlCommand.FetchModelAttr)
            },
        ),
        "FetchModelSummaryAttr": _reflection.GeneratedProtocolMessageType(
            "FetchModelSummaryAttr",
            (_message.Message,),
            {
                "DESCRIPTOR": _MLCOMMAND_FETCHMODELSUMMARYATTR,
                "__module__": "spark.connect.ml_pb2"
                # @@protoc_insertion_point(class_scope:spark.connect.MlCommand.FetchModelSummaryAttr)
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
_sym_db.RegisterMessage(MlCommand.LoadStage)
_sym_db.RegisterMessage(MlCommand.SaveStage)
_sym_db.RegisterMessage(MlCommand.SaveStage.OptionsEntry)
_sym_db.RegisterMessage(MlCommand.LoadEvaluator)
_sym_db.RegisterMessage(MlCommand.SaveEvaluator)
_sym_db.RegisterMessage(MlCommand.SaveEvaluator.OptionsEntry)
_sym_db.RegisterMessage(MlCommand.FetchModelAttr)
_sym_db.RegisterMessage(MlCommand.FetchModelSummaryAttr)

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
    _MLCOMMAND_SAVESTAGE_OPTIONSENTRY._options = None
    _MLCOMMAND_SAVESTAGE_OPTIONSENTRY._serialized_options = b"8\001"
    _MLCOMMAND_SAVEEVALUATOR_OPTIONSENTRY._options = None
    _MLCOMMAND_SAVEEVALUATOR_OPTIONSENTRY._serialized_options = b"8\001"
    _MLEVALUATOR._serialized_start = 136
    _MLEVALUATOR._serialized_end = 236
    _MLCOMMAND._serialized_start = 239
    _MLCOMMAND._serialized_end = 2403
    _MLCOMMAND_FIT._serialized_start = 997
    _MLCOMMAND_FIT._serialized_end = 1107
    _MLCOMMAND_EVALUATE._serialized_start = 1109
    _MLCOMMAND_EVALUATE._serialized_end = 1177
    _MLCOMMAND_LOADMODEL._serialized_start = 1179
    _MLCOMMAND_LOADMODEL._serialized_end = 1230
    _MLCOMMAND_SAVEMODEL._serialized_start = 1233
    _MLCOMMAND_SAVEMODEL._serialized_end = 1463
    _MLCOMMAND_SAVEMODEL_OPTIONSENTRY._serialized_start = 1405
    _MLCOMMAND_SAVEMODEL_OPTIONSENTRY._serialized_end = 1463
    _MLCOMMAND_LOADSTAGE._serialized_start = 1465
    _MLCOMMAND_LOADSTAGE._serialized_end = 1516
    _MLCOMMAND_SAVESTAGE._serialized_start = 1519
    _MLCOMMAND_SAVESTAGE._serialized_end = 1761
    _MLCOMMAND_SAVESTAGE_OPTIONSENTRY._serialized_start = 1405
    _MLCOMMAND_SAVESTAGE_OPTIONSENTRY._serialized_end = 1463
    _MLCOMMAND_LOADEVALUATOR._serialized_start = 1763
    _MLCOMMAND_LOADEVALUATOR._serialized_end = 1818
    _MLCOMMAND_SAVEEVALUATOR._serialized_start = 1821
    _MLCOMMAND_SAVEEVALUATOR._serialized_end = 2083
    _MLCOMMAND_SAVEEVALUATOR_OPTIONSENTRY._serialized_start = 1405
    _MLCOMMAND_SAVEEVALUATOR_OPTIONSENTRY._serialized_end = 1463
    _MLCOMMAND_FETCHMODELATTR._serialized_start = 2085
    _MLCOMMAND_FETCHMODELATTR._serialized_end = 2155
    _MLCOMMAND_FETCHMODELSUMMARYATTR._serialized_start = 2158
    _MLCOMMAND_FETCHMODELSUMMARYATTR._serialized_end = 2384
    _MLCOMMANDRESPONSE._serialized_start = 2406
    _MLCOMMANDRESPONSE._serialized_end = 2767
    _MLCOMMANDRESPONSE_MODELINFO._serialized_start = 2665
    _MLCOMMANDRESPONSE_MODELINFO._serialized_end = 2739
    _VECTOR._serialized_start = 2770
    _VECTOR._serialized_end = 3002
    _VECTOR_DENSE._serialized_start = 2889
    _VECTOR_DENSE._serialized_end = 2918
    _VECTOR_SPARSE._serialized_start = 2920
    _VECTOR_SPARSE._serialized_end = 2992
    _MATRIX._serialized_start = 3005
    _MATRIX._serialized_end = 3431
    _MATRIX_DENSE._serialized_start = 3124
    _MATRIX_DENSE._serialized_end = 3244
    _MATRIX_SPARSE._serialized_start = 3247
    _MATRIX_SPARSE._serialized_end = 3421
# @@protoc_insertion_point(module_scope)
