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

package org.apache.spark.deploy.security

import java.lang.reflect.UndeclaredThrowableException
import java.security.PrivilegedExceptionAction

import scala.util.control.NonFatal

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hdfs.security.token.delegation.DelegationTokenIdentifier
import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.ql.metadata.Hive
import org.apache.hadoop.io.Text
import org.apache.hadoop.security.{Credentials, UserGroupInformation}
import org.apache.hadoop.security.token.Token

import org.apache.spark.internal.Logging
import org.apache.spark.util.Utils

private[security] class HiveCredentialProvider extends HadoopDelegationTokenProvider with Logging {

  override def serviceName: String = "hive"

  private val classNotFoundErrorStr = "You are attempting to use the HiveCredentialProvider," +
    "but your Spark distribution is not built with Hive libraries."

  private def hiveConf(hadoopConf: Configuration): Configuration = {
    try {
      new HiveConf(hadoopConf, classOf[HiveConf])
    } catch {
      case NonFatal(e) =>
        logDebug("Fail to create Hive Configuration", e)
        hadoopConf
      case e: NoClassDefFoundError =>
        logError(classNotFoundErrorStr)
        throw e
    }
  }

  override def credentialsRequired(hadoopConf: Configuration): Boolean = {
    UserGroupInformation.isSecurityEnabled &&
      hiveConf(hadoopConf).getTrimmed("hive.metastore.uris", "").nonEmpty
  }

  override def obtainCredentials(
      hadoopConf: Configuration,
      creds: Credentials): Option[Long] = {
    try {
      val conf = hiveConf(hadoopConf)

      val principalKey = "hive.metastore.kerberos.principal"
      val principal = conf.getTrimmed(principalKey, "")
      require(principal.nonEmpty, s"Hive principal $principalKey undefined")
      val metastoreUri = conf.getTrimmed("hive.metastore.uris", "")
      require(metastoreUri.nonEmpty, "Hive metastore uri undefined")

      val currentUser = UserGroupInformation.getCurrentUser()
      logDebug(s"Getting Hive delegation token for ${currentUser.getUserName()} against " +
        s"$principal at $metastoreUri")

      try {
        doAsRealUser {
          val hive = Hive.get(conf, classOf[HiveConf])
          val tokenStr = hive.getDelegationToken(currentUser.getUserName(), principal)

          val hive2Token = new Token[DelegationTokenIdentifier]()
          hive2Token.decodeFromUrlString(tokenStr)
          logInfo(s"Get Token from hive metastore: ${hive2Token.toString}")
          creds.addToken(new Text("hive.server2.delegation.token"), hive2Token)
        }
      } catch {
        case NonFatal(e) =>
          logDebug(s"Fail to get token from service $serviceName", e)
      } finally {
        Utils.tryLogNonFatalError {
          Hive.closeCurrent()
        }
      }

      None
    } catch {
      case e: NoClassDefFoundError =>
        logError(classNotFoundErrorStr)
        throw e
    }
  }

  /**
   * Run some code as the real logged in user (which may differ from the current user, for
   * example, when using proxying).
   */
  private def doAsRealUser[T](fn: => T): T = {
    val currentUser = UserGroupInformation.getCurrentUser()
    val realUser = Option(currentUser.getRealUser()).getOrElse(currentUser)

   // For some reason the Scala-generated anonymous class ends up causing an
   // UndeclaredThrowableException, even if you annotate the method with @throws.
   try {
      realUser.doAs(new PrivilegedExceptionAction[T]() {
        override def run(): T = fn
      })
    } catch {
      case e: UndeclaredThrowableException => throw Option(e.getCause()).getOrElse(e)
    }
  }
}
