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
package org.apache.spark.deploy.k8s

import java.util.NoSuchElementException

import scala.util.{Failure, Success, Try}

import org.apache.spark.SparkConf

private[spark] object KubernetesTolerationsUtils {
  /**
   * Extract toleration configuration properties with a given name prefix.
   *
   * @param sparkConf Spark configuration
   * @param prefix the given property name prefix
   * @return a Seq of KubernetesTolerationSpec
   */
  def parseTolerationsWithPrefix(
    sparkConf: SparkConf,
    prefix: String): Iterable[Try[
      KubernetesTolerationSpec[_ <: KubernetesTolerationSpecificConf]]
  ] = {
    val properties = sparkConf.getAllWithPrefix(prefix).toMap

    (for (toleration <- getTolerationsConfig(properties).values) yield {
      // Everything is the same as before
      val effect = toleration.get("effect") match {
        case None => null
        case Some(x) => x
      }
      val key = toleration.get("key") match {
        case None => null
        case Some(x) => x
      }
      val operator = toleration.get("operator") match {
        case None => null
        case Some(x) => x
      }
      val tolerationSeconds = toleration.get("tolerationSeconds") match {
        case None => null
        case Some(x) => x
      }
      val value = toleration.get("value") match {
        case None => null
        case Some(x) => x
      }


      Try {
        KubernetesTolerationSpec(
          effect,
          key,
          operator,
          tolerationSeconds,
          value)
      }
    }).toList

  }

  private def getTolerationsConfig(
    properties: Map[String, String]
  ): Map[String, Map[String, String]] = {

    var tolerationsConfig = Map[String, Map[String, String]]()

    var a = properties.keys foreach { k =>
      val l = k.split('.').toList
      var m = tolerationsConfig.getOrElse(l.head, Map[String, String]())
      m = m + {l.last -> properties(k)}
      tolerationsConfig = tolerationsConfig + { l.head -> m }

    }

    return tolerationsConfig

  }


  /**
   * Convenience wrapper to accumulate key lookup errors
   */
  implicit private class MapOps[A, B](m: Map[A, B]) {
    def getTry(key: A): Try[B] = {
      m
        .get(key)
        .fold[Try[B]](Failure(new NoSuchElementException(key.toString)))(Success(_))
    }
  }
}
