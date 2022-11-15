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
# source: spark/connect/relations.proto
"""Generated protocol buffer code."""
from google.protobuf.internal import builder as _builder
from google.protobuf import descriptor as _descriptor
from google.protobuf import descriptor_pool as _descriptor_pool
from google.protobuf import symbol_database as _symbol_database

# @@protoc_insertion_point(imports)

_sym_db = _symbol_database.Default()


from pyspark.sql.connect.proto import expressions_pb2 as spark_dot_connect_dot_expressions__pb2


DESCRIPTOR = _descriptor_pool.Default().AddSerializedFile(
    b'\n\x1dspark/connect/relations.proto\x12\rspark.connect\x1a\x1fspark/connect/expressions.proto"\xa6\x0b\n\x08Relation\x12\x35\n\x06\x63ommon\x18\x01 \x01(\x0b\x32\x1d.spark.connect.RelationCommonR\x06\x63ommon\x12)\n\x04read\x18\x02 \x01(\x0b\x32\x13.spark.connect.ReadH\x00R\x04read\x12\x32\n\x07project\x18\x03 \x01(\x0b\x32\x16.spark.connect.ProjectH\x00R\x07project\x12/\n\x06\x66ilter\x18\x04 \x01(\x0b\x32\x15.spark.connect.FilterH\x00R\x06\x66ilter\x12)\n\x04join\x18\x05 \x01(\x0b\x32\x13.spark.connect.JoinH\x00R\x04join\x12\x34\n\x06set_op\x18\x06 \x01(\x0b\x32\x1b.spark.connect.SetOperationH\x00R\x05setOp\x12)\n\x04sort\x18\x07 \x01(\x0b\x32\x13.spark.connect.SortH\x00R\x04sort\x12,\n\x05limit\x18\x08 \x01(\x0b\x32\x14.spark.connect.LimitH\x00R\x05limit\x12\x38\n\taggregate\x18\t \x01(\x0b\x32\x18.spark.connect.AggregateH\x00R\taggregate\x12&\n\x03sql\x18\n \x01(\x0b\x32\x12.spark.connect.SQLH\x00R\x03sql\x12\x45\n\x0elocal_relation\x18\x0b \x01(\x0b\x32\x1c.spark.connect.LocalRelationH\x00R\rlocalRelation\x12/\n\x06sample\x18\x0c \x01(\x0b\x32\x15.spark.connect.SampleH\x00R\x06sample\x12/\n\x06offset\x18\r \x01(\x0b\x32\x15.spark.connect.OffsetH\x00R\x06offset\x12>\n\x0b\x64\x65\x64uplicate\x18\x0e \x01(\x0b\x32\x1a.spark.connect.DeduplicateH\x00R\x0b\x64\x65\x64uplicate\x12,\n\x05range\x18\x0f \x01(\x0b\x32\x14.spark.connect.RangeH\x00R\x05range\x12\x45\n\x0esubquery_alias\x18\x10 \x01(\x0b\x32\x1c.spark.connect.SubqueryAliasH\x00R\rsubqueryAlias\x12>\n\x0brepartition\x18\x11 \x01(\x0b\x32\x1a.spark.connect.RepartitionH\x00R\x0brepartition\x12|\n#rename_columns_by_same_length_names\x18\x12 \x01(\x0b\x32-.spark.connect.RenameColumnsBySameLengthNamesH\x00R\x1erenameColumnsBySameLengthNames\x12w\n"rename_columns_by_name_to_name_map\x18\x13 \x01(\x0b\x32+.spark.connect.RenameColumnsByNameToNameMapH\x00R\x1crenameColumnsByNameToNameMap\x12<\n\x0bshow_string\x18\x14 \x01(\x0b\x32\x19.spark.connect.ShowStringH\x00R\nshowString\x12\x30\n\x07\x66ill_na\x18Z \x01(\x0b\x32\x15.spark.connect.NAFillH\x00R\x06\x66illNa\x12\x36\n\x07summary\x18\x64 \x01(\x0b\x32\x1a.spark.connect.StatSummaryH\x00R\x07summary\x12\x39\n\x08\x63rosstab\x18\x65 \x01(\x0b\x32\x1b.spark.connect.StatCrosstabH\x00R\x08\x63rosstab\x12\x33\n\x07unknown\x18\xe7\x07 \x01(\x0b\x32\x16.spark.connect.UnknownH\x00R\x07unknownB\n\n\x08rel_type"\t\n\x07Unknown"1\n\x0eRelationCommon\x12\x1f\n\x0bsource_info\x18\x01 \x01(\tR\nsourceInfo"\x1b\n\x03SQL\x12\x14\n\x05query\x18\x01 \x01(\tR\x05query"\x9a\x03\n\x04Read\x12\x41\n\x0bnamed_table\x18\x01 \x01(\x0b\x32\x1e.spark.connect.Read.NamedTableH\x00R\nnamedTable\x12\x41\n\x0b\x64\x61ta_source\x18\x02 \x01(\x0b\x32\x1e.spark.connect.Read.DataSourceH\x00R\ndataSource\x1a=\n\nNamedTable\x12/\n\x13unparsed_identifier\x18\x01 \x01(\tR\x12unparsedIdentifier\x1a\xbf\x01\n\nDataSource\x12\x16\n\x06\x66ormat\x18\x01 \x01(\tR\x06\x66ormat\x12\x16\n\x06schema\x18\x02 \x01(\tR\x06schema\x12\x45\n\x07options\x18\x03 \x03(\x0b\x32+.spark.connect.Read.DataSource.OptionsEntryR\x07options\x1a:\n\x0cOptionsEntry\x12\x10\n\x03key\x18\x01 \x01(\tR\x03key\x12\x14\n\x05value\x18\x02 \x01(\tR\x05value:\x02\x38\x01\x42\x0b\n\tread_type"u\n\x07Project\x12-\n\x05input\x18\x01 \x01(\x0b\x32\x17.spark.connect.RelationR\x05input\x12;\n\x0b\x65xpressions\x18\x03 \x03(\x0b\x32\x19.spark.connect.ExpressionR\x0b\x65xpressions"p\n\x06\x46ilter\x12-\n\x05input\x18\x01 \x01(\x0b\x32\x17.spark.connect.RelationR\x05input\x12\x37\n\tcondition\x18\x02 \x01(\x0b\x32\x19.spark.connect.ExpressionR\tcondition"\xc2\x03\n\x04Join\x12+\n\x04left\x18\x01 \x01(\x0b\x32\x17.spark.connect.RelationR\x04left\x12-\n\x05right\x18\x02 \x01(\x0b\x32\x17.spark.connect.RelationR\x05right\x12@\n\x0ejoin_condition\x18\x03 \x01(\x0b\x32\x19.spark.connect.ExpressionR\rjoinCondition\x12\x39\n\tjoin_type\x18\x04 \x01(\x0e\x32\x1c.spark.connect.Join.JoinTypeR\x08joinType\x12#\n\rusing_columns\x18\x05 \x03(\tR\x0cusingColumns"\xbb\x01\n\x08JoinType\x12\x19\n\x15JOIN_TYPE_UNSPECIFIED\x10\x00\x12\x13\n\x0fJOIN_TYPE_INNER\x10\x01\x12\x18\n\x14JOIN_TYPE_FULL_OUTER\x10\x02\x12\x18\n\x14JOIN_TYPE_LEFT_OUTER\x10\x03\x12\x19\n\x15JOIN_TYPE_RIGHT_OUTER\x10\x04\x12\x17\n\x13JOIN_TYPE_LEFT_ANTI\x10\x05\x12\x17\n\x13JOIN_TYPE_LEFT_SEMI\x10\x06"\xeb\x02\n\x0cSetOperation\x12\x36\n\nleft_input\x18\x01 \x01(\x0b\x32\x17.spark.connect.RelationR\tleftInput\x12\x38\n\x0bright_input\x18\x02 \x01(\x0b\x32\x17.spark.connect.RelationR\nrightInput\x12\x45\n\x0bset_op_type\x18\x03 \x01(\x0e\x32%.spark.connect.SetOperation.SetOpTypeR\tsetOpType\x12\x15\n\x06is_all\x18\x04 \x01(\x08R\x05isAll\x12\x17\n\x07\x62y_name\x18\x05 \x01(\x08R\x06\x62yName"r\n\tSetOpType\x12\x1b\n\x17SET_OP_TYPE_UNSPECIFIED\x10\x00\x12\x19\n\x15SET_OP_TYPE_INTERSECT\x10\x01\x12\x15\n\x11SET_OP_TYPE_UNION\x10\x02\x12\x16\n\x12SET_OP_TYPE_EXCEPT\x10\x03"L\n\x05Limit\x12-\n\x05input\x18\x01 \x01(\x0b\x32\x17.spark.connect.RelationR\x05input\x12\x14\n\x05limit\x18\x02 \x01(\x05R\x05limit"O\n\x06Offset\x12-\n\x05input\x18\x01 \x01(\x0b\x32\x17.spark.connect.RelationR\x05input\x12\x16\n\x06offset\x18\x02 \x01(\x05R\x06offset"\xd2\x01\n\tAggregate\x12-\n\x05input\x18\x01 \x01(\x0b\x32\x17.spark.connect.RelationR\x05input\x12L\n\x14grouping_expressions\x18\x02 \x03(\x0b\x32\x19.spark.connect.ExpressionR\x13groupingExpressions\x12H\n\x12result_expressions\x18\x03 \x03(\x0b\x32\x19.spark.connect.ExpressionR\x11resultExpressions"\x93\x04\n\x04Sort\x12-\n\x05input\x18\x01 \x01(\x0b\x32\x17.spark.connect.RelationR\x05input\x12>\n\x0bsort_fields\x18\x02 \x03(\x0b\x32\x1d.spark.connect.Sort.SortFieldR\nsortFields\x12\x1b\n\tis_global\x18\x03 \x01(\x08R\x08isGlobal\x1a\xbc\x01\n\tSortField\x12\x39\n\nexpression\x18\x01 \x01(\x0b\x32\x19.spark.connect.ExpressionR\nexpression\x12?\n\tdirection\x18\x02 \x01(\x0e\x32!.spark.connect.Sort.SortDirectionR\tdirection\x12\x33\n\x05nulls\x18\x03 \x01(\x0e\x32\x1d.spark.connect.Sort.SortNullsR\x05nulls"l\n\rSortDirection\x12\x1e\n\x1aSORT_DIRECTION_UNSPECIFIED\x10\x00\x12\x1c\n\x18SORT_DIRECTION_ASCENDING\x10\x01\x12\x1d\n\x19SORT_DIRECTION_DESCENDING\x10\x02"R\n\tSortNulls\x12\x1a\n\x16SORT_NULLS_UNSPECIFIED\x10\x00\x12\x14\n\x10SORT_NULLS_FIRST\x10\x01\x12\x13\n\x0fSORT_NULLS_LAST\x10\x02"\x8e\x01\n\x0b\x44\x65\x64uplicate\x12-\n\x05input\x18\x01 \x01(\x0b\x32\x17.spark.connect.RelationR\x05input\x12!\n\x0c\x63olumn_names\x18\x02 \x03(\tR\x0b\x63olumnNames\x12-\n\x13\x61ll_columns_as_keys\x18\x03 \x01(\x08R\x10\x61llColumnsAsKeys"]\n\rLocalRelation\x12L\n\nattributes\x18\x01 \x03(\x0b\x32,.spark.connect.Expression.QualifiedAttributeR\nattributes"\xc6\x01\n\x06Sample\x12-\n\x05input\x18\x01 \x01(\x0b\x32\x17.spark.connect.RelationR\x05input\x12\x1f\n\x0blower_bound\x18\x02 \x01(\x01R\nlowerBound\x12\x1f\n\x0bupper_bound\x18\x03 \x01(\x01R\nupperBound\x12)\n\x10with_replacement\x18\x04 \x01(\x08R\x0fwithReplacement\x12\x17\n\x04seed\x18\x05 \x01(\x03H\x00R\x04seed\x88\x01\x01\x42\x07\n\x05_seed"\x82\x01\n\x05Range\x12\x14\n\x05start\x18\x01 \x01(\x03R\x05start\x12\x10\n\x03\x65nd\x18\x02 \x01(\x03R\x03\x65nd\x12\x12\n\x04step\x18\x03 \x01(\x03R\x04step\x12*\n\x0enum_partitions\x18\x04 \x01(\x05H\x00R\rnumPartitions\x88\x01\x01\x42\x11\n\x0f_num_partitions"r\n\rSubqueryAlias\x12-\n\x05input\x18\x01 \x01(\x0b\x32\x17.spark.connect.RelationR\x05input\x12\x14\n\x05\x61lias\x18\x02 \x01(\tR\x05\x61lias\x12\x1c\n\tqualifier\x18\x03 \x03(\tR\tqualifier"}\n\x0bRepartition\x12-\n\x05input\x18\x01 \x01(\x0b\x32\x17.spark.connect.RelationR\x05input\x12%\n\x0enum_partitions\x18\x02 \x01(\x05R\rnumPartitions\x12\x18\n\x07shuffle\x18\x03 \x01(\x08R\x07shuffle"\xc2\x01\n\nShowString\x12-\n\x05input\x18\x01 \x01(\x0b\x32\x17.spark.connect.RelationR\x05input\x12\x1d\n\x07numRows\x18\x02 \x01(\x05H\x00R\x07numRows\x88\x01\x01\x12\x1f\n\x08truncate\x18\x03 \x01(\x05H\x01R\x08truncate\x88\x01\x01\x12\x1f\n\x08vertical\x18\x04 \x01(\x08H\x02R\x08vertical\x88\x01\x01\x42\n\n\x08_numRowsB\x0b\n\t_truncateB\x0b\n\t_vertical"\\\n\x0bStatSummary\x12-\n\x05input\x18\x01 \x01(\x0b\x32\x17.spark.connect.RelationR\x05input\x12\x1e\n\nstatistics\x18\x02 \x03(\tR\nstatistics"e\n\x0cStatCrosstab\x12-\n\x05input\x18\x01 \x01(\x0b\x32\x17.spark.connect.RelationR\x05input\x12\x12\n\x04\x63ol1\x18\x02 \x01(\tR\x04\x63ol1\x12\x12\n\x04\x63ol2\x18\x03 \x01(\tR\x04\x63ol2"\xef\x01\n\x06NAFill\x12-\n\x05input\x18\x01 \x01(\x0b\x32\x17.spark.connect.RelationR\x05input\x12\x12\n\x04\x63ols\x18\x02 \x03(\tR\x04\x63ols\x12\x32\n\x06values\x18\x03 \x03(\x0b\x32\x1a.spark.connect.NAFill.TypeR\x06values\x1an\n\x04Type\x12\x14\n\x04\x62ool\x18\x01 \x01(\x08H\x00R\x04\x62ool\x12\x14\n\x04long\x18\x02 \x01(\x03H\x00R\x04long\x12\x18\n\x06\x64ouble\x18\x03 \x01(\x01H\x00R\x06\x64ouble\x12\x18\n\x06string\x18\x04 \x01(\tH\x00R\x06stringB\x06\n\x04kind"r\n\x1eRenameColumnsBySameLengthNames\x12-\n\x05input\x18\x01 \x01(\x0b\x32\x17.spark.connect.RelationR\x05input\x12!\n\x0c\x63olumn_names\x18\x02 \x03(\tR\x0b\x63olumnNames"\x83\x02\n\x1cRenameColumnsByNameToNameMap\x12-\n\x05input\x18\x01 \x01(\x0b\x32\x17.spark.connect.RelationR\x05input\x12o\n\x12rename_columns_map\x18\x02 \x03(\x0b\x32\x41.spark.connect.RenameColumnsByNameToNameMap.RenameColumnsMapEntryR\x10renameColumnsMap\x1a\x43\n\x15RenameColumnsMapEntry\x12\x10\n\x03key\x18\x01 \x01(\tR\x03key\x12\x14\n\x05value\x18\x02 \x01(\tR\x05value:\x02\x38\x01\x42"\n\x1eorg.apache.spark.connect.protoP\x01\x62\x06proto3'
)

_builder.BuildMessageAndEnumDescriptors(DESCRIPTOR, globals())
_builder.BuildTopDescriptorsAndMessages(DESCRIPTOR, "spark.connect.relations_pb2", globals())
if _descriptor._USE_C_DESCRIPTORS == False:

    DESCRIPTOR._options = None
    DESCRIPTOR._serialized_options = b"\n\036org.apache.spark.connect.protoP\001"
    _READ_DATASOURCE_OPTIONSENTRY._options = None
    _READ_DATASOURCE_OPTIONSENTRY._serialized_options = b"8\001"
    _RENAMECOLUMNSBYNAMETONAMEMAP_RENAMECOLUMNSMAPENTRY._options = None
    _RENAMECOLUMNSBYNAMETONAMEMAP_RENAMECOLUMNSMAPENTRY._serialized_options = b"8\001"
    _RELATION._serialized_start = 82
    _RELATION._serialized_end = 1528
    _UNKNOWN._serialized_start = 1530
    _UNKNOWN._serialized_end = 1539
    _RELATIONCOMMON._serialized_start = 1541
    _RELATIONCOMMON._serialized_end = 1590
    _SQL._serialized_start = 1592
    _SQL._serialized_end = 1619
    _READ._serialized_start = 1622
    _READ._serialized_end = 2032
    _READ_NAMEDTABLE._serialized_start = 1764
    _READ_NAMEDTABLE._serialized_end = 1825
    _READ_DATASOURCE._serialized_start = 1828
    _READ_DATASOURCE._serialized_end = 2019
    _READ_DATASOURCE_OPTIONSENTRY._serialized_start = 1961
    _READ_DATASOURCE_OPTIONSENTRY._serialized_end = 2019
    _PROJECT._serialized_start = 2034
    _PROJECT._serialized_end = 2151
    _FILTER._serialized_start = 2153
    _FILTER._serialized_end = 2265
    _JOIN._serialized_start = 2268
    _JOIN._serialized_end = 2718
    _JOIN_JOINTYPE._serialized_start = 2531
    _JOIN_JOINTYPE._serialized_end = 2718
    _SETOPERATION._serialized_start = 2721
    _SETOPERATION._serialized_end = 3084
    _SETOPERATION_SETOPTYPE._serialized_start = 2970
    _SETOPERATION_SETOPTYPE._serialized_end = 3084
    _LIMIT._serialized_start = 3086
    _LIMIT._serialized_end = 3162
    _OFFSET._serialized_start = 3164
    _OFFSET._serialized_end = 3243
    _AGGREGATE._serialized_start = 3246
    _AGGREGATE._serialized_end = 3456
    _SORT._serialized_start = 3459
    _SORT._serialized_end = 3990
    _SORT_SORTFIELD._serialized_start = 3608
    _SORT_SORTFIELD._serialized_end = 3796
    _SORT_SORTDIRECTION._serialized_start = 3798
    _SORT_SORTDIRECTION._serialized_end = 3906
    _SORT_SORTNULLS._serialized_start = 3908
    _SORT_SORTNULLS._serialized_end = 3990
    _DEDUPLICATE._serialized_start = 3993
    _DEDUPLICATE._serialized_end = 4135
    _LOCALRELATION._serialized_start = 4137
    _LOCALRELATION._serialized_end = 4230
    _SAMPLE._serialized_start = 4233
    _SAMPLE._serialized_end = 4431
    _RANGE._serialized_start = 4434
    _RANGE._serialized_end = 4564
    _SUBQUERYALIAS._serialized_start = 4566
    _SUBQUERYALIAS._serialized_end = 4680
    _REPARTITION._serialized_start = 4682
    _REPARTITION._serialized_end = 4807
    _SHOWSTRING._serialized_start = 4810
    _SHOWSTRING._serialized_end = 5004
    _STATSUMMARY._serialized_start = 5006
    _STATSUMMARY._serialized_end = 5098
    _STATCROSSTAB._serialized_start = 5100
    _STATCROSSTAB._serialized_end = 5201
    _NAFILL._serialized_start = 5204
    _NAFILL._serialized_end = 5443
    _NAFILL_TYPE._serialized_start = 5333
    _NAFILL_TYPE._serialized_end = 5443
    _RENAMECOLUMNSBYSAMELENGTHNAMES._serialized_start = 5445
    _RENAMECOLUMNSBYSAMELENGTHNAMES._serialized_end = 5559
    _RENAMECOLUMNSBYNAMETONAMEMAP._serialized_start = 5562
    _RENAMECOLUMNSBYNAMETONAMEMAP._serialized_end = 5821
    _RENAMECOLUMNSBYNAMETONAMEMAP_RENAMECOLUMNSMAPENTRY._serialized_start = 5754
    _RENAMECOLUMNSBYNAMETONAMEMAP_RENAMECOLUMNSMAPENTRY._serialized_end = 5821
# @@protoc_insertion_point(module_scope)
