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

package org.apache.spark.sql.execution.datasources.jdbc.connection

import java.sql.{Driver, DriverManager}
import javax.security.auth.login.Configuration

import scala.collection.JavaConverters._

import org.scalatest.BeforeAndAfterEach

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.execution.datasources.jdbc.{DriverRegistry, JDBCOptions}

abstract class ConnectionProviderSuiteBase extends SparkFunSuite with BeforeAndAfterEach {
  protected def registerDriver(driverClass: String): Driver = {
    DriverRegistry.register(driverClass)
    DriverManager.getDrivers.asScala.collectFirst {
      case d if d.getClass.getCanonicalName == driverClass => d
    }.get
  }

  protected def options(url: String) = new JDBCOptions(Map[String, String](
    JDBCOptions.JDBC_URL -> url,
    JDBCOptions.JDBC_TABLE_NAME -> "table",
    JDBCOptions.JDBC_KEYTAB -> "/path/to/keytab",
    JDBCOptions.JDBC_PRINCIPAL -> "principal"
  ))

  override def afterEach(): Unit = {
    try {
      Configuration.setConfiguration(null)
    } finally {
      super.afterEach()
    }
  }

  protected def testSecureConnectionProvider(provider: SecureConnectionProvider): Unit = {
    // Make sure no authentication for the database is set
    assert(Configuration.getConfiguration.getAppConfigurationEntry(provider.appEntry) == null)

    // Make sure the first call sets authentication properly
    val savedConfig = Configuration.getConfiguration
    provider.setAuthenticationConfigIfNeeded()
    val config = Configuration.getConfiguration
    assert(savedConfig != config)
    val appEntry = config.getAppConfigurationEntry(provider.appEntry)
    assert(appEntry != null)

    // Make sure a second call is not modifying the existing authentication
    provider.setAuthenticationConfigIfNeeded()
    assert(config.getAppConfigurationEntry(provider.appEntry) === appEntry)
  }
}
