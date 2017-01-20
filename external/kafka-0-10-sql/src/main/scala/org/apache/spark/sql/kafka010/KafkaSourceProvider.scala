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

package org.apache.spark.sql.kafka010

import java.util.UUID

import org.apache.spark.internal.Logging
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.execution.streaming.Source
import org.apache.spark.sql.sources.{DataSourceRegister, StreamSourceProvider}
import org.apache.spark.sql.types.StructType

/**
 * The provider class for the [[KafkaSource]]. This provider is designed such that it throws
 * IllegalArgumentException when the Kafka Dataset is created, so that it can catch
 * missing options even before the query is started.
 */
private[kafka010] class KafkaSourceProvider extends StreamSourceProvider
  with DataSourceRegister with Logging {

  /**
   * Returns the name and schema of the source. In addition, it also verifies whether the options
   * are correct and sufficient to create the [[KafkaSource]] when the query is started.
   */
  override def sourceSchema(
      sqlContext: SQLContext,
      schema: Option[StructType],
      providerName: String,
      parameters: Map[String, String]): (String, StructType) = {
    require(schema.isEmpty, "Kafka source has a fixed schema and cannot be set with a custom one")
    (shortName(), KafkaOffsetReader.kafkaSchema)
  }

  override def createSource(
      sqlContext: SQLContext,
      metadataPath: String,
      schema: Option[StructType],
      providerName: String,
      parameters: Map[String, String]): Source = {
    // Each running query should use its own group id. Otherwise, the query may be only assigned
    // partial data since Kafka will assign partitions to multiple consumers having the same group
    // id. Hence, we should generate a unique id for each query.
    val uniqueGroupId = s"spark-kafka-source-${UUID.randomUUID}-${metadataPath.hashCode}"
    val kafkaConfigOptions = new KafkaConfigOptions(parameters, uniqueGroupId)
    val kafkaOffsetReader = new KafkaOffsetReader(kafkaConfigOptions.strategy,
      kafkaConfigOptions.kafkaParamsForDriver, parameters,
      driverGroupIdPrefix = s"$uniqueGroupId-driver")

    new KafkaSource(
      sqlContext,
      kafkaOffsetReader,
      kafkaConfigOptions.kafkaParamsForExecutors,
      parameters,
      metadataPath,
      kafkaConfigOptions.startingStreamOffsets,
      kafkaConfigOptions.failOnDataLoss)
  }

  override def shortName(): String = "kafka"
}
