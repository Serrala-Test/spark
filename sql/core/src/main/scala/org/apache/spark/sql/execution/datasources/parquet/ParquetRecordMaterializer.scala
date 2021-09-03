/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources.parquet

import java.time.ZoneId

import org.apache.parquet.io.api.{GroupConverter, RecordMaterializer}
import org.apache.parquet.schema.MessageType

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.internal.SQLConf.LegacyBehaviorPolicy
import org.apache.spark.sql.types.StructType

/**
 * A [[RecordMaterializer]] for Catalyst rows.
 *
 * @param parquetSchema Parquet schema of the records to be read
 * @param catalystSchema Catalyst schema of the rows to be constructed
 * @param schemaConverter A Parquet-Catalyst schema converter that helps initializing row converters
 * @param convertTz the optional time zone to convert to int96 data
 * @param datetimeRebaseMode the mode of rebasing date/timestamp from Julian to Proleptic Gregorian
 *                           calendar
 * @param int96RebaseMode the mode of rebasing INT96 timestamp from Julian to Proleptic Gregorian
 *                           calendar
 * @param accessByColumnOrdinal when true, mapping parquet schema with catalyst schema by column
 *                              ordinals; otherwise, by column names
 */
private[parquet] class ParquetRecordMaterializer(
    parquetSchema: MessageType,
    catalystSchema: StructType,
    schemaConverter: ParquetToSparkSchemaConverter,
    convertTz: Option[ZoneId],
    datetimeRebaseMode: LegacyBehaviorPolicy.Value,
    int96RebaseMode: LegacyBehaviorPolicy.Value,
    accessByColumnOrdinal: Boolean)
  extends RecordMaterializer[InternalRow] {

  private val rootConverter = new ParquetRowConverter(
    schemaConverter,
    parquetSchema,
    catalystSchema,
    convertTz,
    datetimeRebaseMode,
    int96RebaseMode,
    NoopUpdater,
    accessByColumnOrdinal)

  override def getCurrentRecord: InternalRow = rootConverter.currentRecord

  override def getRootConverter: GroupConverter = rootConverter
}
