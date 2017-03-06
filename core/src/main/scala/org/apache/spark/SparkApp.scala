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

package org.apache.spark

/**
 * A client that SparkSubmit uses to launch spark Application.
 * This is currently supported only in YARN mode.
 */
private[spark] trait SparkApp {
  this: Singleton =>

  /**
   * The Client should implement this as entry method to provide application,
   * spark conf and system configuration.
   *
   * @param args    - all arguments for SparkApp.
   * @param conf    - Spark Configuration.
   * @param envvars - system environment Variables.
   */
  def sparkMain(
    args: Array[String],
    conf: Map[String, String],
    envvars: Map[String, String]): Unit

}
