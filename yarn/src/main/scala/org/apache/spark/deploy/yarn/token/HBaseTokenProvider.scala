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

package org.apache.spark.deploy.yarn.token

import scala.reflect.runtime.universe
import scala.util.control.NonFatal

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.security.Credentials
import org.apache.hadoop.security.token.{Token, TokenIdentifier}

import org.apache.spark.SparkConf
import org.apache.spark.internal.Logging

private[yarn] class HBaseTokenProvider extends ServiceTokenProvider with Logging {

  override def serviceName: String = "hbase"

  override def obtainTokensFromService(
      sparkConf: SparkConf,
      serviceConf: Configuration,
      creds: Credentials): Array[Token[_ <: TokenIdentifier]] = {
    try {
      val mirror = universe.runtimeMirror(getClass.getClassLoader)
      val obtainToken = mirror.classLoader.
        loadClass("org.apache.hadoop.hbase.security.token.TokenUtil").
        getMethod("obtainToken", classOf[Configuration])

      logDebug("Attempting to fetch HBase security token.")
      val token = obtainToken.invoke(null, hbaseConf(serviceConf))
        .asInstanceOf[Token[_ <: TokenIdentifier]]
      creds.addToken(token.getService, token)
      Array(token)
    } catch {
      case NonFatal(e) =>
        logWarning(s"Failed to get token from service $serviceName", e)
        Array.empty
    }
  }

  override def isTokenRequired(conf: Configuration): Boolean = {
    hbaseConf(conf).get("hbase.security.authentication") == "kerberos"
  }

  private def hbaseConf(conf: Configuration): Configuration = {
    val mirror = universe.runtimeMirror(getClass.getClassLoader)
    val confCreate = mirror.classLoader.
      loadClass("org.apache.hadoop.hbase.HBaseConfiguration").
      getMethod("create", classOf[Configuration])
    confCreate.invoke(null, conf).asInstanceOf[Configuration]
  }
}
