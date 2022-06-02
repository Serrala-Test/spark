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

package org.apache.spark.sql.hive.execution.command

import org.apache.spark.sql.execution.command.v1

/**
 * The class contains tests for the `ALTER NAMESPACE ... SET LOCATION` command to check
 * V1 Hive external table catalog.
 */
class AlterNamespaceSetLocationSuite extends v1.AlterNamespaceSetLocationSuiteBase
    with CommandSuiteBase {
  override def commandVersion: String = super[AlterNamespaceSetLocationSuiteBase].commandVersion

  test("Hive catalog not supported by the built-in Hive client") {
    val ns = s"$catalog.$namespace"
    withNamespace(ns) {
      sql(s"CREATE NAMESPACE $ns LOCATION '/tmp/loc_1'")
      sql(s"ALTER DATABASE $ns SET LOCATION '/tmp/loc_2'")

      // Location should remain the same since the built-in Hive version doesn't support changing
      // database location
      assert(getLocation(ns).contains("/tmp/loc_1"))
    }
  }
}
