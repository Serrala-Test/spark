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

package org.apache.spark.kvstore;

import java.io.Closeable;
import java.util.Iterator;
import java.util.Map;

/**
 * Abstraction for a local key/value store for storing app data.
 *
 * <p>
 * Use {@link KVStoreBuilder} to create an instance. There are two main features provided by the
 * implementations of this interface:
 * </p>
 *
 * <ul>
 *   <li>serialization: this feature is not optional; data will be serialized to and deserialized
 *   from the underlying data store using a {@link KVStoreSerializer}, which can be customized by
 *   the application. The serializer is based on Jackson, so it supports all the Jackson annotations
 *   for controlling the serialization of app-defined types.</li>
 *
 *   <li>key management: by using {@link #read(Class, Object)} and {@link #write(Class, Object)},
 *   applications can leave key management to the implementation. For applications that want to
 *   manage their own keys, the {@link #get(byte[], Class)} and {@link #set(byte[], Object)} methods
 *   are available.</li>
 * </ul>
 *
 * <h3>Automatic Key Management</h3>
 *
 * <p>
 * When using the built-in key management, the implementation will automatically create unique
 * keys for each type written to the store. Keys are based on the type name, and always start
 * with the "+" prefix character (so that it's easy to use both manual and automatic key
 * management APIs without conflicts).
 * </p>
 *
 * <p>
 * Another feature of automatic key management is indexing; by annotating fields or methods of
 * objects written to the store with {@link KVIndex}, indices are created to sort the data
 * by the values of those properties. This makes it possible to provide sorting without having
 * to load all instances of those types from the store.
 * </p>
 *
 * <p>
 * KVStore instances are thread-safe for both reads and writes.
 * </p>
 */
public interface KVStore extends Closeable {

  /**
   * Returns app-specific metadata from the store, or null if it's not currently set.
   *
   * <p>
   * The metadata type is application-specific. This is a convenience method so that applications
   * don't need to define their own keys for this information.
   * </p>
   */
  <T> T getMetadata(Class<T> klass) throws Exception;

  /**
   * Writes the given value in the store metadata key.
   */
  void setMetadata(Object value) throws Exception;

  /**
   * Returns the value of a specific key, deserialized to the given type.
   */
  <T> T get(byte[] key, Class<T> klass) throws Exception;

  /**
   * Write a single key directly to the store, atomically.
   */
  void put(byte[] key, Object value) throws Exception;

  /**
   * Removes a key from the store.
   */
  void delete(byte[] key) throws Exception;

  /**
   * Returns an iterator that will only list values with keys starting with the given prefix.
   */
  <T> KVStoreIterator<T> iterator(byte[] prefix, Class<T> klass) throws Exception;

  /**
   * Read a specific instance of an object.
   */
  <T> T read(Class<T> klass, Object naturalKey) throws Exception;

  /**
   * Writes the given object to the store, including indexed fields. Indices are updated based
   * on the annotated fields of the object's class.
   *
   * <p>
   * Writes may be slower when the object already exists in the store, since it will involve
   * updating existing indices.
   * </p>
   *
   * @param value The object to write.
   */
  void write(Object value) throws Exception;

  /**
   * Removes an object and all data related to it, like index entries, from the store.
   *
   * @param type The object's type.
   * @param naturalKey The object's "natural key", which uniquely identifies it.
   */
  void delete(Class<?> type, Object naturalKey) throws Exception;

  /**
   * Returns a configurable view for iterating over entities of the given type.
   */
  <T> KVStoreView<T> view(Class<T> type) throws Exception;

  /**
   * Returns the number of items of the given type currently in the store.
   */
  long count(Class<?> type) throws Exception;

}
