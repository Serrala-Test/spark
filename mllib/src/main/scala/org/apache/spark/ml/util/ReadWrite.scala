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

package org.apache.spark.ml.util

import java.{util => ju}
import java.io.IOException

import scala.annotation.varargs
import scala.collection.mutable
import scala.collection.JavaConverters._

import org.apache.hadoop.fs.{FileSystem, Path}
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._

import org.apache.spark.{Logging, SparkContext}
import org.apache.spark.annotation.{Experimental, Since}
import org.apache.spark.ml.param.{ParamPair, Params}
import org.apache.spark.sql.SQLContext
import org.apache.spark.util.Utils

/**
 * Trait for [[Writer]] and [[Reader]].
 */
private[util] sealed trait BaseReadWrite {
  private var optionSQLContext: Option[SQLContext] = None

  /**
   * Sets the SQL context to use for saving/loading.
   */
  @Since("1.6.0")
  def context(sqlContext: SQLContext): this.type = {
    optionSQLContext = Option(sqlContext)
    this
  }

  /**
   * Returns the user-specified SQL context or the default.
   */
  protected final def sqlContext: SQLContext = optionSQLContext.getOrElse {
    SQLContext.getOrCreate(SparkContext.getOrCreate())
  }
}

/**
 * Abstract class for utility classes that can save ML instances.
 */
@Experimental
@Since("1.6.0")
abstract class Writer extends BaseReadWrite {

  protected var shouldOverwrite: Boolean = false

  /**
   * Saves the ML instance to the input path.
   */
  @Since("1.6.0")
  @throws[IOException]("If the input path already exists but overwrite is not enabled.")
  def to(path: String): Unit

  /**
   * Saves the ML instances to the input path, the same as [[to()]].
   */
  @Since("1.6.0")
  @throws[IOException]("If the input path already exists but overwrite is not enabled.")
  def save(path: String): Unit = to(path)

  /**
   * Overwrites if the output path already exists.
   */
  def overwrite(): this.type = {
    shouldOverwrite = true
    this
  }
}

/**
 * Trait for classes that provide [[Writer]].
 */
@Since("1.6.0")
trait Writable {

  /**
   * Returns a [[Writer]] instance for this ML instance.
   */
  @Since("1.6.0")
  def write: Writer
}

/**
 * Abstract class for utility classes that can load ML instances.
 * @tparam T ML instance type
 */
@Experimental
@Since("1.6.0")
abstract class Reader[T] extends BaseReadWrite {

  /**
   * Loads the ML component from the input path.
   */
  @Since("1.6.0")
  def from(path: String): T

  /**
   * Loads the ML component from the input path, the same as [[from()]].
   */
  def load(path: String): T = from(path)
}

/**
 * Trait for objects that provide [[Reader]].
 * @tparam T ML instance type
 */
@Experimental
@Since("1.6.0")
trait Readable[T] {
  
  /**
   * Returns a [[Reader]] instance for this class.
   */
  @Since("1.6.0")
  def read: Reader[T]
}

/**
 * Default [[Writer]] implementation for non-meta transformers and estimators.
 * @param instance object to save
 */
private[ml] class DefaultParamsWriter(instance: Params) extends Writer with Logging {

  /**
   * Saves the ML component to the input path.
   */
  override def to(path: String): Unit = {
    val sc = sqlContext.sparkContext

    val hadoopConf = sc.hadoopConfiguration
    val fs = FileSystem.get(hadoopConf)
    val p = new Path(path)
    if (fs.exists(p)) {
      if (shouldOverwrite) {
        logInfo(s"Path $path already exists. It will be overwritten.")
        fs.delete(p, true)
      } else {
        throw new IOException(
          s"Path $path already exists. Please set overwrite=true to overwrite it.")
      }
    }

    val uid = instance.uid
    val cls = instance.getClass.getName
    val params = instance.extractParamMap().toSeq.asInstanceOf[Seq[ParamPair[Any]]]
    val jsonParams = params.map { case ParamPair(p, v) =>
      p.name -> parse(p.jsonEncode(v))
    }.toList
    val metadata = ("class" -> cls) ~
      ("timestamp" -> System.currentTimeMillis()) ~
      ("uid" -> uid) ~
      ("paramMap" -> jsonParams)
    val metadataPath = new Path(path, "metadata").toString
    val metadataJson = compact(render(metadata))
    sc.parallelize(Seq(metadataJson), 1).saveAsTextFile(metadataPath)
  }
}

/**
 * Default [[Reader]] implementation for non-meta transformers and estimators.
 * @tparam T ML instance type
 */
private[ml] class DefaultParamsReader[T] extends Reader[T] {

  /**
   * Loads the ML component from the input path.
   */
  override def from(path: String): T = {
    implicit val format = DefaultFormats
    val sc = sqlContext.sparkContext
    val metadataPath = new Path(path, "metadata").toString
    val metadataStr = sc.textFile(metadataPath, 1).first()
    val metadata = parse(metadataStr)
    val cls = Utils.classForName((metadata \ "class").extract[String])
    val uid = (metadata \ "uid").extract[String]
    val instance = cls.getConstructor(classOf[String]).newInstance(uid).asInstanceOf[Params]
    (metadata \ "paramMap") match {
      case JObject(pairs) =>
        pairs.foreach { case (paramName, jsonValue) =>
          val param = instance.getParam(paramName)
          val value = param.jsonDecode(compact(render(jsonValue)))
          instance.set(param, value)
        }
      case _ =>
        throw new IllegalArgumentException(s"Cannot recognize JSON metadata: $metadataStr.")
    }
    instance.asInstanceOf[T]
  }
}
