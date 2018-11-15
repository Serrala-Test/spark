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

import java.text.SimpleDateFormat
import java.util.Properties

import org.apache.hadoop.io.Text
import org.apache.hadoop.security.token.{Token, TokenIdentifier}
import org.apache.hadoop.security.token.delegation.AbstractDelegationTokenIdentifier
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.admin.{AdminClient, CreateDelegationTokenOptions}
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.security.token.delegation.DelegationToken

import org.apache.spark.SparkConf
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config._

private[spark] object KafkaTokenUtil extends Logging {
  val TOKEN_KIND = new Text("KAFKA_DELEGATION_TOKEN")
  val TOKEN_SERVICE = new Text("kafka.server.delegation.token")

  private[spark] class KafkaDelegationTokenIdentifier extends AbstractDelegationTokenIdentifier {
    override def getKind: Text = TOKEN_KIND
  }

  private[security] def obtainToken(sparkConf: SparkConf): (Token[_ <: TokenIdentifier], Long) = {
    val adminClient = AdminClient.create(createAdminClientProperties(sparkConf))
    val createDelegationTokenOptions = new CreateDelegationTokenOptions()
    val createResult = adminClient.createDelegationToken(createDelegationTokenOptions)
    val token = createResult.delegationToken().get()
    printToken(token)

    (new Token[KafkaDelegationTokenIdentifier](
      token.tokenInfo.tokenId.getBytes,
      token.hmacAsBase64String.getBytes,
      TOKEN_KIND,
      TOKEN_SERVICE
    ), token.tokenInfo.expiryTimestamp)
  }

  private[security] def createAdminClientProperties(sparkConf: SparkConf): Properties = {
    val adminClientProperties = new Properties

    val bootstrapServers = sparkConf.get(KAFKA_BOOTSTRAP_SERVERS)
    require(bootstrapServers.nonEmpty, s"Tried to obtain kafka delegation token but bootstrap " +
      "servers not configured.")
    adminClientProperties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers.get)

    val protocol = sparkConf.get(KAFKA_SECURITY_PROTOCOL)
    adminClientProperties.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, protocol)
    if (protocol.endsWith("SSL")) {
      logDebug("SSL protocol detected.")
      sparkConf.get(KAFKA_TRUSTSTORE_LOCATION).foreach { truststoreLocation =>
        adminClientProperties.put("ssl.truststore.location", truststoreLocation)
      }
      sparkConf.get(KAFKA_TRUSTSTORE_PASSWORD).foreach { truststorePassword =>
        adminClientProperties.put("ssl.truststore.password", truststorePassword)
      }
    } else {
      logWarning("Obtaining kafka delegation token through plain communication channel. Please " +
        "consider the security impact.")
    }

    // There are multiple possibilities to log in:
    // - Keytab is provided -> try to log in with kerberos module using kafka's dynamic JAAS
    //   configuration.
    // - Keytab not provided -> try to log in with JVM global security configuration
    //   which can be configured for example with 'java.security.auth.login.config'.
    //   For this no additional parameter needed.
    getKeytabJaasParams(sparkConf).foreach { jaasParams =>
      logInfo("Keytab detected, using it for login.")
      adminClientProperties.put(SaslConfigs.SASL_MECHANISM, SaslConfigs.GSSAPI_MECHANISM)
      adminClientProperties.put(SaslConfigs.SASL_JAAS_CONFIG, jaasParams)
    }

    adminClientProperties
  }

  private[security] def getKeytabJaasParams(sparkConf: SparkConf): Option[String] = {
    val keytab = sparkConf.get(KEYTAB)
    if (keytab.isDefined) {
      val serviceName = sparkConf.get(KAFKA_KERBEROS_SERVICE_NAME)
      require(serviceName.nonEmpty, "Kerberos service name must be defined")
      val principal = sparkConf.get(PRINCIPAL)
      require(principal.nonEmpty, "Principal must be defined")

      val params =
        s"""
        |${getKrb5LoginModuleName} required
        | useKeyTab=true
        | serviceName="${serviceName.get}"
        | keyTab="${keytab.get}"
        | principal="${principal.get}";
        """.stripMargin.replace("\n", "")
      logDebug(s"Krb JAAS params: $params")
      Some(params)
    } else {
      None
    }
  }

  /**
   * Krb5LoginModule package vary in different JVMs.
   * Please see Hadoop UserGroupInformation for further details.
   */
  private def getKrb5LoginModuleName(): String = {
    if (System.getProperty("java.vendor").contains("IBM")) {
      "com.ibm.security.auth.module.Krb5LoginModule"
    } else {
      "com.sun.security.auth.module.Krb5LoginModule"
    }
  }

  private def printToken(token: DelegationToken): Unit = {
    if (log.isDebugEnabled) {
      val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm")
      logDebug("%-15s %-30s %-15s %-25s %-15s %-15s %-15s".format(
        "TOKENID", "HMAC", "OWNER", "RENEWERS", "ISSUEDATE", "EXPIRYDATE", "MAXDATE"))
      val tokenInfo = token.tokenInfo
      logDebug("%-15s [hidden] %-15s %-25s %-15s %-15s %-15s".format(
        tokenInfo.tokenId,
        tokenInfo.owner,
        tokenInfo.renewersAsString,
        dateFormat.format(tokenInfo.issueTimestamp),
        dateFormat.format(tokenInfo.expiryTimestamp),
        dateFormat.format(tokenInfo.maxTimestamp)))
    }
  }
}
