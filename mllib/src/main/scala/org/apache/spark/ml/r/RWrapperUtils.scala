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

package org.apache.spark.ml.r

import org.apache.spark.internal.Logging
import org.apache.spark.ml.feature.RFormula
import org.apache.spark.sql.Dataset

object RWrapperUtils extends Logging {

  /**
   * DataFrame column check.
   * When loading data, default columns "features" and "label" will be added. And these two names
   * would conflict with RFormula default feature and label column names.
   * Here is to change the column name to avoid "column already exists" error.
   *
   * @param rFormula RFormula instance
   * @param data Input dataset
   * @return Unit
   */
  def checkDataColumns(rFormula: RFormula, data: Dataset[_]): Unit = {
    if (data.schema.fieldNames.contains(rFormula.getLabelCol)) {
      logWarning("data containing 'label' column, so change its name to avoid conflict")
      rFormula.setLabelCol(rFormula.getLabelCol + "_output")
    }

    if (data.schema.fieldNames.contains(rFormula.getFeaturesCol)) {
      logWarning("data containing 'features' column, so change its name to avoid conflict")
      rFormula.setFeaturesCol(rFormula.getFeaturesCol + "_output")
    }
  }
}
