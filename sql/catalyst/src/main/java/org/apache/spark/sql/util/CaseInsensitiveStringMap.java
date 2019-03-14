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

package org.apache.spark.sql.util;

import org.apache.spark.annotation.Experimental;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Case-insensitive map of string keys to string values.
 * <p>
 * This is used to pass options to v2 implementations to ensure consistent case insensitivity.
 * <p>
 * Methods that return keys in this map, like {@link #entrySet()} and {@link #keySet()}, return
 * keys converted to lower case. This map doesn't allow null key.
 */
@Experimental
public class CaseInsensitiveStringMap implements Map<String, String> {

  public static CaseInsensitiveStringMap empty() {
    return new CaseInsensitiveStringMap(new HashMap<>(0));
  }

  private final Map<String, String> delegate;

  public CaseInsensitiveStringMap(Map<String, String> originalMap) {
    this.delegate = new HashMap<>(originalMap.size());
    putAll(originalMap);
  }

  @Override
  public int size() {
    return delegate.size();
  }

  @Override
  public boolean isEmpty() {
    return delegate.isEmpty();
  }

  private String toLowerCase(Object key) {
    return key.toString().toLowerCase(Locale.ROOT);
  }

  @Override
  public boolean containsKey(Object key) {
    return delegate.containsKey(toLowerCase(key));
  }

  @Override
  public boolean containsValue(Object value) {
    return delegate.containsValue(value);
  }

  @Override
  public String get(Object key) {
    return delegate.get(toLowerCase(key));
  }

  @Override
  public String put(String key, String value) {
    return delegate.put(toLowerCase(key), value);
  }

  @Override
  public String remove(Object key) {
    return delegate.remove(toLowerCase(key));
  }

  @Override
  public void putAll(Map<? extends String, ? extends String> m) {
    for (Map.Entry<? extends String, ? extends String> entry : m.entrySet()) {
      put(entry.getKey(), entry.getValue());
    }
  }

  @Override
  public void clear() {
    delegate.clear();
  }

  @Override
  public Set<String> keySet() {
    return delegate.keySet();
  }

  @Override
  public Collection<String> values() {
    return delegate.values();
  }

  @Override
  public Set<Map.Entry<String, String>> entrySet() {
    return delegate.entrySet();
  }

  /**
   * Returns the boolean value to which the specified key is mapped,
   * or defaultValue if there is no mapping for the key. The key match is case-insensitive.
   */
  public boolean getBoolean(String key, boolean defaultValue) {
    String value = get(key);
    // We can't use `Boolean.parseBoolean` here, as it returns false for invalid strings.
    if (value == null) {
      return defaultValue;
    } else if (value.equalsIgnoreCase("true")) {
      return true;
    } else if (value.equalsIgnoreCase("false")) {
      return false;
    } else {
      throw new IllegalArgumentException(value + " is not a boolean string.");
    }
  }

  /**
   * Returns the integer value to which the specified key is mapped,
   * or defaultValue if there is no mapping for the key. The key match is case-insensitive.
   */
  public int getInt(String key, int defaultValue) {
    String value = get(key);
    return value == null ? defaultValue : Integer.parseInt(value);
  }

  /**
   * Returns the long value to which the specified key is mapped,
   * or defaultValue if there is no mapping for the key. The key match is case-insensitive.
   */
  public long getLong(String key, long defaultValue) {
    String value = get(key);
    return value == null ? defaultValue : Long.parseLong(value);
  }

  /**
   * Returns the double value to which the specified key is mapped,
   * or defaultValue if there is no mapping for the key. The key match is case-insensitive.
   */
  public double getDouble(String key, double defaultValue) {
    String value = get(key);
    return value == null ? defaultValue : Double.parseDouble(value);
  }
}
