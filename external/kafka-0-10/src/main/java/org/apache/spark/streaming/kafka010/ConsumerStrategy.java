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

package org.apache.spark.streaming.kafka010;

import scala.collection.Map$;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.spark.annotation.Experimental;

/**
 * :: Experimental :: Choice of how to create and configure underlying Kafka Consumers on driver and
 * executors. Kafka 0.10 consumers can require additional, sometimes complex, setup after object
 * instantiation. This interface encapsulates that process, and allows it to be checkpointed.
 */
@Experimental
public abstract class ConsumerStrategy<K, V> {

  /**
   * Kafka <a href="http://kafka.apache.org/documentation.htmll#newconsumerconfigs"> configuration
   * parameters</a> to be used on executors. Requires "bootstrap.servers" to be set with Kafka
   * broker(s) specified in host1:port1,host2:port2 form.
   */
  public abstract java.util.Map<String,Object> executorKafkaParams();

  /**
   * Must return a fully configured Kafka Consumer, including subscribed or assigned topics. This
   * consumer will be used on the driver to query for offsets only, not messages.
   *
   * @param currentOffsets A map from TopicPartition to offset, indicating how far the driver has
   *                       successfully read.  Will be empty on initial start, possibly non-empty on
   *                       restart from checkpoint.
   */
  public abstract Consumer<K,V> onStart(
      java.util.Map<TopicPartition, Long> currentOffsets);

  /**
   * :: Experimental ::
   * Subscribe to a collection of topics.
   *
   * @param topics      collection of topics to subscribe
   * @param kafkaParams Kafka <a
   *                    href="http://kafka.apache.org/documentation.htmll#newconsumerconfigs">
   *                    configuration parameters</a> to be used on driver. The same params will be
   *                    used on executors, with minor automatic modifications applied. Requires
   *                    "bootstrap.servers" to be set with Kafka broker(s) specified in
   *                    host1:port1,host2:port2 form.
   */
  @Experimental
  public static <Key, Value>  ConsumerStrategy<Key, Value> Subscribe(
      scala.collection.Iterable<String> topics,
      scala.collection.Map<String, Object> kafkaParams) {
    return Subscribe(topics, kafkaParams, Map$.MODULE$.<TopicPartition, scala.Long>empty());
  }

  /**
   * :: Experimental ::
   * Subscribe to a collection of topics.
   *
   * @param topics      collection of topics to subscribe
   * @param kafkaParams Kafka <a
   *                    href="http://kafka.apache.org/documentation.html#newconsumerconfigs">
   *                    configuration parameters</a> to be used on driver. The same params will be
   *                    used on executors, with minor automatic modifications applied. Requires
   *                    "bootstrap.servers" to be set with Kafka broker(s) specified in
   *                    host1:port1,host2:port2 form.
   * @param offsets:    offsets to begin at on initial startup.  If no offset is given for a
   *                    TopicPartition, the committed offset (if applicable) or kafka param
   *                    auto.offset.reset will be used.
   */
  @Experimental
  public static <Key, Value> ConsumerStrategy<Key, Value> Subscribe(
      scala.collection.Iterable<String> topics,
      scala.collection.Map<String, Object> kafkaParams,
      scala.collection.Map<TopicPartition, scala.Long> offsets) {
    return new SubscribeStrategy(topics, kafkaParams, offsets);
  }

  /**
   * :: Experimental ::
   * Subscribe to a collection of topics.
   *
   * @param topics      collection of topics to subscribe
   * @param kafkaParams Kafka <a
   *                    href="http://kafka.apache.org/documentation.html#newconsumerconfigs">
   *                    configuration parameters</a> to be used on driver. The same params will be
   *                    used on executors, with minor automatic modifications applied. Requires
   *                    "bootstrap.servers" to be set with Kafka broker(s) specified in
   *                    host1:port1,host2:port2 form.
   */
  @Experimental
  public static <Key, Value> ConsumerStrategy<Key, Value> Subscribe(
      java.util.Collection<String> topics,
      java.util.Map<String, Object> kafkaParams) {
    return Subscribe(topics, kafkaParams, java.util.Collections.<TopicPartition, Long>emptyMap());
  }

  /**
   * :: Experimental ::
   * Subscribe to a collection of topics.
   *
   * @param topics      collection of topics to subscribe
   * @param kafkaParams Kafka <a
   *                    href="http://kafka.apache.org/documentation.html#newconsumerconfigs">
   *                    configuration parameters</a> to be used on driver. The same params will be
   *                    used on executors, with minor automatic modifications applied. Requires
   *                    "bootstrap.servers" to be set with Kafka broker(s) specified in
   *                    host1:port1,host2:port2 form.
   * @param offsets:    offsets to begin at on initial startup.  If no offset is given for a
   *                    TopicPartition, the committed offset (if applicable) or kafka param
   *                    auto.offset.reset will be used.
   */
  @Experimental
  public static <Key, Value> ConsumerStrategy<Key, Value> Subscribe(
      java.util.Collection<String> topics,
      java.util.Map<String, Object> kafkaParams,
      java.util.Map<TopicPartition, Long> offsets) {
    return new SubscribeStrategy(topics, kafkaParams, offsets);
  }


  /**
   * :: Experimental :: Assign a fixed collection of TopicPartitions
   *
   * @param topicPartitions collection of TopicPartitions to assign
   * @param kafkaParams     Kafka <a
   *                        href="http://kafka.apache.org/documentation.htmll#newconsumerconfigs">
   *                        configuration parameters</a> to be used on driver. The same params will
   *                        be used on executors, with minor automatic modifications applied.
   *                        Requires "bootstrap.servers" to be set with Kafka broker(s) specified in
   *                        host1:port1,host2:port2 form.
   */
  @Experimental
  public static <Key, Value> ConsumerStrategy<Key, Value> Assign(
      scala.collection.Iterable<TopicPartition> topicPartitions,
      scala.collection.Map<String, Object> kafkaParams) {
    return Assign(topicPartitions, kafkaParams, Map$.MODULE$.<TopicPartition, scala.Long>empty());
  }

  /**
   * :: Experimental :: Assign a fixed collection of TopicPartitions
   *
   * @param topicPartitions collection of TopicPartitions to assign
   * @param kafkaParams     Kafka <a
   *                        href="http://kafka.apache.org/documentation.htmll#newconsumerconfigs">
   *                        configuration parameters</a> to be used on driver. The same params will
   *                        be used on executors, with minor automatic modifications applied.
   *                        Requires "bootstrap.servers" to be set with Kafka broker(s) specified in
   *                        host1:port1,host2:port2 form.
   * @param offsets:        offsets to begin at on initial startup.  If no offset is given for a
   *                        TopicPartition, the committed offset (if applicable) or kafka param
   *                        auto.offset.reset will be used.
   */
  @Experimental
  public static <Key, Value> ConsumerStrategy<Key, Value> Assign(
      scala.collection.Iterable<TopicPartition> topicPartitions,
      scala.collection.Map<String, Object> kafkaParams,
      scala.collection.Map<TopicPartition, scala.Long> offsets) {
    return new AssignStrategy(topicPartitions, kafkaParams, offsets);
  }

  /**
   * :: Experimental :: Assign a fixed collection of TopicPartitions
   *
   * @param topicPartitions collection of TopicPartitions to assign
   * @param kafkaParams     Kafka <a
   *                        href="http://kafka.apache.org/documentation.htmll#newconsumerconfigs">
   *                        configuration parameters</a> to be used on driver. The same params will
   *                        be used on executors, with minor automatic modifications applied.
   *                        Requires "bootstrap.servers" to be set with Kafka broker(s) specified in
   *                        host1:port1,host2:port2 form.
   */
  @Experimental
  public static <Key, Value> ConsumerStrategy<Key, Value> Assign(
      java.util.Collection<TopicPartition> topicPartitions,
      java.util.Map<String, Object> kafkaParams) {
    return Assign(topicPartitions, kafkaParams, java.util.Collections.<TopicPartition, Long>emptyMap());
  }

  /**
   * :: Experimental :: Assign a fixed collection of TopicPartitions
   *
   * @param topicPartitions collection of TopicPartitions to assign
   * @param kafkaParams     Kafka <a
   *                        href="http://kafka.apache.org/documentation.htmll#newconsumerconfigs">
   *                        configuration parameters</a> to be used on driver. The same params will
   *                        be used on executors, with minor automatic modifications applied.
   *                        Requires "bootstrap.servers" to be set with Kafka broker(s) specified in
   *                        host1:port1,host2:port2 form.
   * @param offsets:        offsets to begin at on initial startup.  If no offset is given for a
   *                        TopicPartition, the committed offset (if applicable) or kafka param
   *                        auto.offset.reset will be used.
   */
  @Experimental
  public static <Key, Value> ConsumerStrategy<Key, Value> Assign(
      java.util.Collection<TopicPartition> topicPartitions,
      java.util.Map<String, Object> kafkaParams,
      java.util.Map<TopicPartition, Long> offsets) {
    return new AssignStrategy(topicPartitions, kafkaParams, offsets);
  }

}