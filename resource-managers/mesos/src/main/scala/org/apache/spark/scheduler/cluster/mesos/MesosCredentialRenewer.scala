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

package org.apache.spark.scheduler.cluster.mesos

import java.security.PrivilegedExceptionAction
import java.util.concurrent.{Executors, TimeUnit}

import scala.collection.JavaConverters._
import scala.util.Try

import org.apache.hadoop.security.UserGroupInformation

import org.apache.spark.SparkConf
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.deploy.security.HadoopDelegationTokenManager
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config
import org.apache.spark.rpc.RpcEndpointRef
import org.apache.spark.scheduler.cluster.CoarseGrainedClusterMessages.UpdateDelegationTokens
import org.apache.spark.util.ThreadUtils


/**
 * The MesosCredentialRenewer will update the Hadoop credentials for Spark drivers accessing
 * secured services using Kerberos authentication. It is modeled after the YARN AMCredential
 * renewer, and similarly will renew the Credentials when 75% of the renewal interval has passed.
 * The principal difference is that instead of writing the new credentials to HDFS and
 * incrementing the timestamp of the file, the new credentials (called Tokens when they are
 * serialized) are broadcast to all running executors. On the executor side, when new Tokens are
 * recieved they overwrite the current credentials.
 */
class MesosCredentialRenewer(
    conf: SparkConf,
    tokenManager: HadoopDelegationTokenManager,
    nextRenewal: Long,
    driverEndpoint: RpcEndpointRef) extends Logging {
  private val credentialRenewerThread =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor("Credential Renewal Thread")

  @volatile private var timeOfNextRenewal = nextRenewal

  private val principal = conf.get(config.PRINCIPAL).orNull

  private val (secretFile, mode) = getSecretFile(conf)

  private def getSecretFile(conf: SparkConf): (String, String) = {
    val keytab = conf.get(config.KEYTAB).orNull
    val tgt = conf.getenv("KRB5CCNAME")
    require(keytab != null || tgt != null, "A keytab or TGT required.")
    // if both Keytab and TGT are detected we use the Keytab.
    val (secretFile, mode) = if (keytab != null && tgt != null) {
      logWarning(s"Keytab and TGT were detected, using keytab, unset $keytab to use TGT")
      (keytab, "keytab")
    } else {
      val m = if (keytab != null) "keytab" else "tgt"
      val sf = if (keytab != null) keytab else tgt
      (sf, m)
    }
    logInfo(s"Logging in as $principal with mode $mode to retrieve Hadoop delegation tokens")
    logDebug(s"secretFile is $secretFile")
    (secretFile, mode)
  }

  def scheduleTokenRenewal(): Unit = {
    def scheduleRenewal(runnable: Runnable): Unit = {
      val remainingTime = timeOfNextRenewal - System.currentTimeMillis()
      if (remainingTime <= 0) {
        logInfo("Credentials have expired, creating new ones now.")
        runnable.run()
      } else {
        logInfo(s"Scheduling login from keytab in $remainingTime millis.")
        credentialRenewerThread.schedule(runnable, remainingTime, TimeUnit.MILLISECONDS)
      }
    }

    val credentialRenewerRunnable =
      new Runnable {
        override def run(): Unit = {
          try {
            val creds = getRenewedDelegationTokens(conf)
            broadcastDelegationTokens(creds)
          } catch {
            case e: Exception =>
              // Log the error and try to write new tokens back in an hour
              logWarning("Couldn't broadcast tokens, trying again in an hour", e)
              credentialRenewerThread.schedule(this, 1, TimeUnit.HOURS)
              return
          }
          scheduleRenewal(this)
        }
      }
    scheduleRenewal(credentialRenewerRunnable)
  }

  private def getRenewedDelegationTokens(conf: SparkConf): Array[Byte] = {
    logInfo(s"Attempting to login with ${conf.get(config.PRINCIPAL).orNull}")
    // Get new delegation tokens by logging in with a new UGI
    // inspired by AMCredentialRenewer.scala:L174
    val ugi = if (mode == "keytab") {
      UserGroupInformation.loginUserFromKeytabAndReturnUGI(principal, secretFile)
    } else {
      UserGroupInformation.getUGIFromTicketCache(secretFile, principal)
    }
    val tempCreds = ugi.getCredentials
    val hadoopConf = SparkHadoopUtil.get.newConfiguration(conf)
    var nextRenewalTime = Long.MaxValue
    ugi.doAs(new PrivilegedExceptionAction[Void] {
      override def run(): Void = {
        nextRenewalTime = tokenManager.obtainDelegationTokens(hadoopConf, tempCreds)
        null
      }
    })

    val currTime = System.currentTimeMillis()
    timeOfNextRenewal = if (nextRenewalTime <= currTime) {
      logWarning(s"Next credential renewal time ($nextRenewalTime) is earlier than " +
        s"current time ($currTime), which is unexpected, please check your credential renewal " +
        "related configurations in the target services.")
      currTime
    } else {
      MesosCredentialRenewer.getNextRenewalTime(nextRenewalTime)
    }
    logInfo(s"Time of next renewal is $timeOfNextRenewal")

    // Add the temp credentials back to the original ones.
    UserGroupInformation.getCurrentUser.addCredentials(tempCreds)
    SparkHadoopUtil.get.serialize(tempCreds)
  }

  private def broadcastDelegationTokens(tokens: Array[Byte]): Unit = {
    // send token to existing executors
    logInfo("Sending new tokens to all executors")
    driverEndpoint.send(UpdateDelegationTokens(tokens))
  }
}

object MesosCredentialRenewer extends Logging {
  def getTokenRenewalTime(bytes: Array[Byte], conf: SparkConf): Long = {
    val hadoopConf = SparkHadoopUtil.get.newConfiguration(conf)
    val creds = SparkHadoopUtil.get.deserialize(bytes)
    val renewalTimes = creds.getAllTokens.asScala.flatMap { t =>
      Try {
        t.renew(hadoopConf)
      }.toOption
    }
    if (renewalTimes.isEmpty) Long.MaxValue else renewalTimes.min
  }

  def getNextRenewalTime(t: Long): Long = {
    val ct = System.currentTimeMillis()
    (ct + (0.75 * (t - ct))).toLong
  }
}

