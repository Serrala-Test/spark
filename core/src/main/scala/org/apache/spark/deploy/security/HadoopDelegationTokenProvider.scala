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
import org.apache.hadoop.security.Credentials

/**
 * Hadoop delegation token provider.
 */
private[spark] trait HadoopDelegationTokenProvider {

  /**
   * Name of the service to provide credentials. This name should be unique, Spark internally will
   * use this name to differentiate credential provider.
   */
  def serviceName: String

  /**
   * Returns true if credentials are required for this service. By default, it is based on whether
   * Hadoop security is enabled.
   */
  def credentialsRequired(hadoopConf: Configuration): Boolean

  /**
   * Obtain credentials for this service and get the time of the next renewal.
   * @param hadoopConf Configuration of current Hadoop Compatible system.
   * @param creds Credentials to add tokens and security keys to.
   * @return If this Credential is renewable and can be renewed, return the time of the next
   *         renewal, otherwise None should be returned.
   */
  def obtainCredentials(
    hadoopConf: Configuration,
    creds: Credentials): Option[Long]
}
