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

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.Text
import org.apache.hadoop.security.Credentials
import org.apache.hadoop.security.token.Token
import org.scalatest.{BeforeAndAfter, Matchers}

import org.apache.spark.{SparkConf, SparkFunSuite}

class ConfigurableCredentialManagerSuite extends SparkFunSuite with Matchers with BeforeAndAfter {
  private var credentialManager: ConfigurableCredentialManager = null
  private var sparkConf: SparkConf = null
  private var hadoopConf: Configuration = null

  override def beforeAll(): Unit = {
    super.beforeAll()

    sparkConf = new SparkConf()
    hadoopConf = new Configuration()
  }

  test("Correctly load default credential providers") {
    credentialManager = new ConfigurableCredentialManager(sparkConf, hadoopConf)

    credentialManager.getServiceCredentialProvider("hadoopfs") should not be (None)
    credentialManager.getServiceCredentialProvider("hbase") should not be (None)
    credentialManager.getServiceCredentialProvider("hive") should not be (None)
    credentialManager.getServiceCredentialProvider("bogus") should be (None)
  }

  test("disable hive credential provider") {
    sparkConf.set("spark.security.credentials.hive.enabled", "false")
    credentialManager = new ConfigurableCredentialManager(sparkConf, hadoopConf)

    credentialManager.getServiceCredentialProvider("hadoopfs") should not be (None)
    credentialManager.getServiceCredentialProvider("hbase") should not be (None)
    credentialManager.getServiceCredentialProvider("hive") should be (None)
  }

  test("using deprecated configurations") {
    sparkConf.set("spark.yarn.security.tokens.hadoopfs.enabled", "false")
    sparkConf.set("spark.yarn.security.credentials.hive.enabled", "false")
    credentialManager = new ConfigurableCredentialManager(sparkConf, hadoopConf)

    credentialManager.getServiceCredentialProvider("hadoopfs") should be (None)
    credentialManager.getServiceCredentialProvider("hive") should be (None)
    credentialManager.getServiceCredentialProvider("hbase") should not be (None)
  }

  test("verify no credentials are obtained") {
    credentialManager = new ConfigurableCredentialManager(sparkConf, hadoopConf)
    val creds = new Credentials()

    // Tokens cannot be obtained from HDFS, Hive, HBase in unit tests.
    credentialManager.obtainCredentials(hadoopConf, creds)
    val tokens = creds.getAllTokens
    tokens.size() should be (0)
  }

  test("obtain tokens For HiveMetastore") {
    val hadoopConf = new Configuration()
    hadoopConf.set("hive.metastore.kerberos.principal", "bob")
    // thrift picks up on port 0 and bails out, without trying to talk to endpoint
    hadoopConf.set("hive.metastore.uris", "http://localhost:0")

    val hiveCredentialProvider = new HiveCredentialProvider()
    val credentials = new Credentials()
    hiveCredentialProvider.obtainCredentials(
      hadoopConf,
      new DefaultHadoopAccessManager(hadoopConf),
      credentials)

    credentials.getAllTokens.size() should be (0)
  }

  test("Obtain tokens For HBase") {
    val hadoopConf = new Configuration()
    hadoopConf.set("hbase.security.authentication", "kerberos")

    val hbaseTokenProvider = new HBaseCredentialProvider()
    val creds = new Credentials()
    hbaseTokenProvider.obtainCredentials(
      hadoopConf,
      new DefaultHadoopAccessManager(hadoopConf),
      creds)

    creds.getAllTokens.size should be (0)
  }
}
