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
package org.apache.spark.graphx.api.java

import org.apache.spark.api.java.JavaRDD
import org.apache.spark.api.java.function.{Function => JFunction}
import org.apache.spark.graphx.{VertexId, VertexRDD}
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import org.apache.spark.{Partition, TaskContext}

import scala.language.implicitConversions
import scala.reflect._

/**
 * A Java-friendly interface to [[org.apache.spark.graphx.VertexRDD]], the vertex
 * RDD abstraction in Spark GraphX that represents a vertex class in a graph.
 * Vertices can be created from existing RDDs or it can be generated from transforming
 * existing VertexRDDs using operations such as `mapValues`, `pagerank`, etc.
 * For operations applicable to vertices in a graph in GraphX, please refer to
 * [[org.apache.spark.graphx.VertexRDD]]
 */

class JavaVertexRDD[VD](
    val vertices: RDD[(VertexId, VD)],
    val targetStorageLevel: StorageLevel = StorageLevel.MEMORY_ONLY)
    (implicit val classTag: ClassTag[VD])
  extends JavaVertexRDDLike[VD, JavaVertexRDD[VD], JavaRDD[(VertexId, VD)]] {

  override def vertexRDD = VertexRDD(vertices)

  override def wrapRDD(rdd: RDD[(VertexId, VD)]): JavaRDD[(VertexId, VD)] = {
    JavaRDD.fromRDD(rdd)
  }

  /** Persist RDDs of this DStream with the default storage level (MEMORY_ONLY_SER) */
  def cache(): JavaVertexRDD[VD] = vertices.cache().asInstanceOf[JavaVertexRDD[VD]]

  /** Persist RDDs of this DStream with the default storage level (MEMORY_ONLY_SER) */
  def persist(): JavaVertexRDD[VD] = vertices.persist().asInstanceOf[JavaVertexRDD[VD]]

  /** Persist the RDDs of this DStream with the given storage level */
  def persist(storageLevel: StorageLevel): JavaVertexRDD[VD] =
    vertices.persist(storageLevel).asInstanceOf[JavaVertexRDD[VD]]

  /** Generate a VertexRDD for the given duration */
  override def compute(part: Partition, context: TaskContext): Iterator[(VertexId, VD)] =
    vertexRDD.compute(part, context)

  /** Convert [[org.apache.spark.api.java.JavaRDD]] to
    * [[org.apache.spark.graphx.api.java.JavaVertexRDD]] instance */
  def asJavaVertexRDD = JavaRDD.fromRDD(this.vertexRDD)

  /** Return a new VertexRDD containing only the elements that satisfy a predicate. */
  def filter(f: JFunction[(VertexId, VD), Boolean]): JavaVertexRDD[VD] =
    JavaVertexRDD(vertexRDD.filter(x => f.call(x).booleanValue()))

  def toRDD : RDD[(VertexId, VD)] = vertices
}

object JavaVertexRDD {

  /**
   * Convert a scala [[org.apache.spark.graphx.VertexRDD]] to a Java-friendly
   * [[org.apache.spark.graphx.api.java.JavaVertexRDD]].
   */
  implicit def fromVertexRDD[VD: ClassTag](vertexRDD: VertexRDD[VD]): JavaVertexRDD[VD] =
    new JavaVertexRDD[VD](vertexRDD)

  implicit def apply[VD: ClassTag](vertices: JavaRDD[(VertexId, VD)]): JavaVertexRDD[VD] = {
    new JavaVertexRDD[VD](vertices)
  }
}


