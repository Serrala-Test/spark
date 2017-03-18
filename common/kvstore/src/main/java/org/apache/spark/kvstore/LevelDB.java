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

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import org.fusesource.leveldbjni.JniDBFactory;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.WriteBatch;
import org.iq80.leveldb.WriteOptions;

/**
 * Implementation of KVStore that uses LevelDB as the underlying data store.
 */
public class LevelDB implements KVStore {

  @VisibleForTesting
  static final long STORE_VERSION = 1L;

  @VisibleForTesting
  static final byte[] STORE_VERSION_KEY = "__version__".getBytes(UTF_8);

  /** DB key where app metadata is stored. */
  private static final byte[] METADATA_KEY = "__meta__".getBytes(UTF_8);

  final AtomicReference<DB> _db;
  final KVStoreSerializer serializer;

  private final ConcurrentMap<Class<?>, LevelDBTypeInfo> types;

  public LevelDB(File path) throws IOException {
    this(path, new KVStoreSerializer());
  }

  public LevelDB(File path, KVStoreSerializer serializer) throws IOException {
    this.serializer = serializer;
    this.types = new ConcurrentHashMap<>();

    Options options = new Options();
    options.createIfMissing(!path.exists());
    this._db = new AtomicReference<>(JniDBFactory.factory.open(path, options));

    byte[] versionData = db().get(STORE_VERSION_KEY);
    if (versionData != null) {
      long version = serializer.deserializeLong(versionData);
      if (version != STORE_VERSION) {
        throw new UnsupportedStoreVersionException();
      }
    } else {
      db().put(STORE_VERSION_KEY, serializer.serialize(STORE_VERSION));
    }
  }

  @Override
  public <T> T getMetadata(Class<T> klass) throws Exception {
    try {
      return get(METADATA_KEY, klass);
    } catch (NoSuchElementException nsee) {
      return null;
    }
  }

  @Override
  public void setMetadata(Object value) throws Exception {
    if (value != null) {
      put(METADATA_KEY, value);
    } else {
      db().delete(METADATA_KEY);
    }
  }

  @Override
  public <T> T get(byte[] key, Class<T> klass) throws Exception {
    byte[] data = db().get(key);
    if (data == null) {
      throw new NoSuchElementException(new String(key, UTF_8));
    }
    return serializer.deserialize(data, klass);
  }

  @Override
  public void put(byte[] key, Object value) throws Exception {
    Preconditions.checkArgument(value != null, "Null values are not allowed.");
    db().put(key, serializer.serialize(value));
  }

  @Override
  public void delete(byte[] key) throws Exception {
    db().delete(key);
  }

  @Override
  public <T> KVStoreIterator<T> iterator(byte[] prefix, Class<T> klass) throws Exception {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T read(Class<T> klass, Object naturalKey) throws Exception {
    Preconditions.checkArgument(naturalKey != null, "Null keys are not allowed.");
    byte[] key = getTypeInfo(klass).naturalIndex().start(naturalKey);
    return get(key, klass);
  }

  @Override
  public void write(Object value) throws Exception {
    write(value, false);
  }

  public void write(Object value, boolean sync) throws Exception {
    Preconditions.checkArgument(value != null, "Null values are not allowed.");
    LevelDBTypeInfo<?> ti = getTypeInfo(value.getClass());

    LevelDBWriteBatch batch = new LevelDBWriteBatch(this);
    try {
      byte[] data = serializer.serialize(value);
      synchronized (ti) {
        try {
          Object existing = get(ti.naturalIndex().entityKey(value), value.getClass());
          removeInstance(ti, batch, existing);
        } catch (NoSuchElementException e) {
          // Ignore. No previous value.
        }
        for (LevelDBTypeInfo<?>.Index idx : ti.indices()) {
          idx.add(batch, value, data);
        }
        batch.write(sync);
      }
    } finally {
      batch.close();
    }
  }

  @Override
  public void delete(Class<?> type, Object naturalKey) throws Exception {
    delete(type, naturalKey, false);
  }

  public void delete(Class<?> type, Object naturalKey, boolean sync) throws Exception {
    Preconditions.checkArgument(naturalKey != null, "Null keys are not allowed.");
    LevelDBWriteBatch batch = new LevelDBWriteBatch(this);
    try {
      LevelDBTypeInfo<?> ti = getTypeInfo(type);
      byte[] key = ti.naturalIndex().start(naturalKey);
      byte[] data = db().get(key);
      if (data != null) {
        Object existing = serializer.deserialize(data, type);
        synchronized (ti) {
          removeInstance(ti, batch, existing);
          batch.write(sync);
        }
      }
    } finally {
      batch.close();
    }
  }

  @Override
  public <T> KVStoreView<T> view(Class<T> type) throws Exception {
    return new KVStoreView<T>(type) {
      @Override
      public Iterator<T> iterator() {
        try {
          return new LevelDBIterator<>(LevelDB.this, this);
        } catch (Exception e) {
          throw Throwables.propagate(e);
        }
      }
    };
  }

  @Override
  public long count(Class<?> type) throws Exception {
    LevelDBTypeInfo<?>.Index idx = getTypeInfo(type).naturalIndex();
    return idx.getCount(idx.end());
  }

  @Override
  public void close() throws IOException {
    DB _db = this._db.getAndSet(null);
    if (_db == null) {
      return;
    }

    try {
      _db.close();
    } catch (IOException ioe) {
      throw ioe;
    } catch (Exception e) {
      throw new IOException(e.getMessage(), e);
    }
  }

  /** Returns metadata about indices for the given type. */
  <T> LevelDBTypeInfo<T> getTypeInfo(Class<T> type) throws Exception {
    LevelDBTypeInfo<T> idx = types.get(type);
    if (idx == null) {
      LevelDBTypeInfo<T> tmp = new LevelDBTypeInfo<>(this, type);
      idx = types.putIfAbsent(type, tmp);
      if (idx == null) {
        idx = tmp;
      }
    }
    return idx;
  }

  /**
   * Try to avoid use-after close since that has the tendency of crashing the JVM. This doesn't
   * prevent methods that retrieved the instance from using it after close, but hopefully will
   * catch most cases; otherwise, we'll need some kind of locking.
   */
  DB db() {
    DB _db = this._db.get();
    if (_db == null) {
      throw new IllegalStateException("DB is closed.");
    }
    return _db;
  }

  private void removeInstance(LevelDBTypeInfo<?> ti, LevelDBWriteBatch batch, Object instance)
      throws Exception {
    for (LevelDBTypeInfo<?>.Index idx : ti.indices()) {
      idx.remove(batch, instance);
    }
  }

}
