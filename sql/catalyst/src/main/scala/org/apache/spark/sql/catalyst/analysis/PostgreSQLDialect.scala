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

package org.apache.spark.sql.catalyst.analysis

import org.apache.spark.sql.catalyst.expressions.Cast
import org.apache.spark.sql.catalyst.expressions.postgreSQL._
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._

object PostgreSQLDialect {
  val postgreSQLDialectRules: Seq[Rule[LogicalPlan]] = Seq(
    PostgresCast
  )

  object PostgresCast extends Rule[LogicalPlan] {
    override def apply(plan: LogicalPlan): LogicalPlan = {
      if (SQLConf.get.usePostgreSQLDialect) {
        plan.transformExpressions {
          case Cast(child, dataType, timeZoneId)
            if dataType == BooleanType && child.dataType != BooleanType =>
            PostgreCastToBoolean(child, timeZoneId)
          case Cast(child, dataType, timeZoneId)
            if dataType == TimestampType && child.dataType != TimestampType =>
            PostgreCastToTimestamp(child, timeZoneId)
        }
      } else {
        plan
      }
    }
  }
}
