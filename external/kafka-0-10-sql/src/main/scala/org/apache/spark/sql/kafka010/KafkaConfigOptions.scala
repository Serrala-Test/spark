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

import java.{util => ju}

import scala.collection.JavaConverters._

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer

import org.apache.spark.internal.Logging
import org.apache.spark.sql.kafka010.KafkaOffsetReader.{AssignStrategy, SubscribePatternStrategy, SubscribeStrategy}

private[kafka010] class KafkaConfigOptions(parameters: Map[String, String], uniqueGroupId: String)
  extends Logging {
  import KafkaConfigOptions._
  validateOptions(parameters)
  private val deserClassName = classOf[ByteArrayDeserializer].getName

  private val caseInsensitiveParams = parameters.map { case (k, v) => (k.toLowerCase, v) }
  private val specifiedKafkaParams =
    parameters
      .keySet
      .filter(_.toLowerCase.startsWith("kafka."))
      .map { k => k.drop(6).toString -> parameters(k) }
      .toMap

  val startingStreamOffsets =
    caseInsensitiveParams.get(STARTING_OFFSETS_OPTION_KEY).map(_.trim.toLowerCase) match {
      case Some("latest") => LatestOffsets
      case Some("earliest") => EarliestOffsets
      case Some(json) => SpecificOffsets(JsonUtils.partitionOffsets(json))
      case None => LatestOffsets
    }

  val startingRelationOffsets =
    caseInsensitiveParams.get(STARTING_OFFSETS_OPTION_KEY).map(_.trim.toLowerCase) match {
      case Some("latest") =>
        throw new IllegalArgumentException("Starting relation offset can't be latest.")
      case Some("earliest") => EarliestOffsets
      case Some(json) => SpecificOffsets(JsonUtils.partitionOffsets(json))
      case None => EarliestOffsets
    }

  val endingRelationOffsets =
    caseInsensitiveParams.get(ENDING_OFFSETS_OPTION_KEY).map(_.trim.toLowerCase) match {
      case Some("latest") => LatestOffsets
      case Some("earliest") =>
        throw new IllegalArgumentException("Ending relation offset can't be earliest.")
      case Some(json) => SpecificOffsets(JsonUtils.partitionOffsets(json))
      case None => LatestOffsets
    }

  val kafkaParamsForDriver =
    ConfigUpdater("source", specifiedKafkaParams)
      .set(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, deserClassName)
      .set(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, deserClassName)

      // Set to "earliest" to avoid exceptions. However, KafkaSource will fetch the initial
      // offsets by itself instead of counting on KafkaConsumer.
      .set(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

      // So that consumers in the driver does not commit offsets unnecessarily
      .set(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")

      // So that the driver does not pull too much data
      .set(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, new java.lang.Integer(1))

      // If buffer config is not set, set it to reasonable value to work around
      // buffer issues (see KAFKA-3135)
      .setIfUnset(ConsumerConfig.RECEIVE_BUFFER_CONFIG, 65536: java.lang.Integer)
      .build()

  val kafkaParamsForExecutors =
    ConfigUpdater("executor", specifiedKafkaParams)
      .set(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, deserClassName)
      .set(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, deserClassName)

      // Make sure executors do only what the driver tells them.
      .set(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none")

      // So that consumers in executors do not mess with any existing group id
      .set(ConsumerConfig.GROUP_ID_CONFIG, s"$uniqueGroupId-executor")

      // So that consumers in executors does not commit offsets unnecessarily
      .set(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")

      // If buffer config is not set, set it to reasonable value to work around
      // buffer issues (see KAFKA-3135)
      .setIfUnset(ConsumerConfig.RECEIVE_BUFFER_CONFIG, 65536: java.lang.Integer)
      .build()

  val strategy = caseInsensitiveParams.find(x => STRATEGY_OPTION_KEYS.contains(x._1)).get match {
    case ("assign", value) =>
      AssignStrategy(JsonUtils.partitions(value))
    case ("subscribe", value) =>
      SubscribeStrategy(value.split(",").map(_.trim()).filter(_.nonEmpty))
    case ("subscribepattern", value) =>
      SubscribePatternStrategy(value.trim())
    case _ =>
      // Should never reach here as we are already matching on
      // matched strategy names
      throw new IllegalArgumentException("Unknown option")
  }

  val failOnDataLoss =
    caseInsensitiveParams.getOrElse(FAIL_ON_DATA_LOSS_OPTION_KEY, "true").toBoolean
  private def validateOptions(parameters: Map[String, String]): Unit = {
    // Validate source options
    val caseInsensitiveParams = parameters.map { case (k, v) => (k.toLowerCase, v) }
    val specifiedStrategies =
      caseInsensitiveParams.filter { case (k, _) => STRATEGY_OPTION_KEYS.contains(k) }.toSeq
    if (specifiedStrategies.isEmpty) {
      throw new IllegalArgumentException(
        "One of the following options must be specified for Kafka source: "
          + STRATEGY_OPTION_KEYS.mkString(", ") + ". See the docs for more details.")
    } else if (specifiedStrategies.size > 1) {
      throw new IllegalArgumentException(
        "Only one of the following options can be specified for Kafka source: "
          + STRATEGY_OPTION_KEYS.mkString(", ") + ". See the docs for more details.")
    }

    val strategy = caseInsensitiveParams.find(x => STRATEGY_OPTION_KEYS.contains(x._1)).get match {
      case ("assign", value) =>
        if (!value.trim.startsWith("{")) {
          throw new IllegalArgumentException(
            "No topicpartitions to assign as specified value for option " +
              s"'assign' is '$value'")
        }

      case ("subscribe", value) =>
        val topics = value.split(",").map(_.trim).filter(_.nonEmpty)
        if (topics.isEmpty) {
          throw new IllegalArgumentException(
            "No topics to subscribe to as specified value for option " +
              s"'subscribe' is '$value'")
        }
      case ("subscribepattern", value) =>
        val pattern = caseInsensitiveParams("subscribepattern").trim()
        if (pattern.isEmpty) {
          throw new IllegalArgumentException(
            "Pattern to subscribe is empty as specified value for option " +
              s"'subscribePattern' is '$value'")
        }
      case _ =>
        // Should never reach here as we are already matching on
        // matched strategy names
        throw new IllegalArgumentException("Unknown option")
    }

    // Validate user-specified Kafka options

    if (caseInsensitiveParams.contains(s"kafka.${ConsumerConfig.GROUP_ID_CONFIG}")) {
      throw new IllegalArgumentException(
        s"Kafka option '${ConsumerConfig.GROUP_ID_CONFIG}' is not supported as " +
          s"user-specified consumer groups is not used to track offsets.")
    }

    if (caseInsensitiveParams.contains(s"kafka.${ConsumerConfig.AUTO_OFFSET_RESET_CONFIG}")) {
      throw new IllegalArgumentException(
        s"""
           |Kafka option '${ConsumerConfig.AUTO_OFFSET_RESET_CONFIG}' is not supported.
           |Instead set the source option '$STARTING_OFFSETS_OPTION_KEY' to 'earliest' or 'latest'
           |to specify where to start. Structured Streaming manages which offsets are consumed
           |internally, rather than relying on the kafkaConsumer to do it. This will ensure that no
           |data is missed when new topics/partitions are dynamically subscribed. Note that
           |'$STARTING_OFFSETS_OPTION_KEY' only applies when a new Streaming query is started, and
           |that resuming will always pick up from where the query left off. See the docs for more
           |details.
         """.stripMargin)
    }

    if (caseInsensitiveParams.contains(s"kafka.${ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG}")) {
      throw new IllegalArgumentException(
        s"Kafka option '${ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG}' is not supported as keys "
          + "are deserialized as byte arrays with ByteArrayDeserializer. Use DataFrame operations "
          + "to explicitly deserialize the keys.")
    }

    if (caseInsensitiveParams.contains(s"kafka.${ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG}"))
    {
      throw new IllegalArgumentException(
        s"Kafka option '${ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG}' is not supported as "
          + "value are deserialized as byte arrays with ByteArrayDeserializer. Use DataFrame "
          + "operations to explicitly deserialize the values.")
    }

    val otherUnsupportedConfigs = Seq(
      ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, // committing correctly requires new APIs in Source
      ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG) // interceptors can modify payload, so not safe

    otherUnsupportedConfigs.foreach { c =>
      if (caseInsensitiveParams.contains(s"kafka.$c")) {
        throw new IllegalArgumentException(s"Kafka option '$c' is not supported")
      }
    }

    if (!caseInsensitiveParams.contains(s"kafka.${ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG}")) {
      throw new IllegalArgumentException(
        s"Option 'kafka.${ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG}' must be specified for " +
          s"configuring Kafka consumer")
    }
  }

  /** Class to conveniently update Kafka config params, while logging the changes */
  private case class ConfigUpdater(module: String, kafkaParams: Map[String, String]) {
    private val map = new ju.HashMap[String, Object](kafkaParams.asJava)

    def set(key: String, value: Object): this.type = {
      map.put(key, value)
      logInfo(s"$module: Set $key to $value, earlier value: ${kafkaParams.get(key).getOrElse("")}")
      this
    }

    def setIfUnset(key: String, value: Object): ConfigUpdater = {
      if (!map.containsKey(key)) {
        map.put(key, value)
        logInfo(s"$module: Set $key to $value")
      }
      this
    }

    def build(): ju.Map[String, Object] = map
  }
}

private[kafka010] object KafkaConfigOptions {
  private val STRATEGY_OPTION_KEYS = Set("subscribe", "subscribepattern", "assign")
  private val STARTING_OFFSETS_OPTION_KEY = "startingoffsets"
  private val ENDING_OFFSETS_OPTION_KEY = "endingoffsets"
  private val FAIL_ON_DATA_LOSS_OPTION_KEY = "failondataloss"
}
