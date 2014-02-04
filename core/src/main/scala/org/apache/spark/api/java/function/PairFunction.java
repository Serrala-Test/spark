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

package org.apache.spark.api.java.function;

import scala.Tuple2;
import scala.reflect.ClassTag;
import scala.reflect.ClassTag$;

import java.io.Serializable;

/**
 * A function that returns key-value pairs (Tuple2<K, V>), and can be used to construct PairRDDs.
 */
// PairFunction does not extend Function because some UDF functions, like map,
// are overloaded for both Function and PairFunction.
public abstract class PairFunction<T, K, V> extends WrappedFunction1<T, Tuple2<K, V>>
  implements Serializable {

  public ClassTag<K> keyType() {
    return (ClassTag<K>) ClassTag$.MODULE$.apply(Object.class);
  }

  public ClassTag<V> valueType() {
    return (ClassTag<V>) ClassTag$.MODULE$.apply(Object.class);
  }
}
