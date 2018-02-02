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

package org.apache.spark.sql.execution.datasources.v2

import org.apache.spark.sql.Strategy
import org.apache.spark.sql.catalyst.plans.logical.{InsertIntoTable, LogicalPlan}
import org.apache.spark.sql.execution.SparkPlan

object DataSourceV2Strategy extends Strategy {
  override def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {
    case relation: DataSourceV2Relation =>
      DataSourceV2ScanExec(relation.output, relation.reader) :: Nil

    case relation: StreamingDataSourceV2Relation =>
      DataSourceV2ScanExec(relation.fullOutput, relation.reader) :: Nil

    case InsertIntoTable(relation: DataSourceV2Relation, _, query, overwrite, ifNotExists) =>
      val mode = DataSourceV2Utils.saveMode(overwrite, ifNotExists)
      WriteToDataSourceV2Exec(relation.writer(query.schema, mode).get, planLater(query)) :: Nil

    case WriteToDataSourceV2(writer, query) =>
      WriteToDataSourceV2Exec(writer, planLater(query)) :: Nil

    case _ => Nil
  }
}
