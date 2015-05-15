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

package org.apache.spark.sql.hive

import org.apache.spark.sql.{DataFrame, SaveMode}

package object orc {
  implicit class OrcContext(sqlContext: HiveContext) {
    @scala.annotation.varargs
    def orcFile(path: String, paths: String*): DataFrame = {
      val pathArray: Array[String] = {
        if (paths.isEmpty) {
          Array(path)
        } else {
         paths.toArray ++ Array(path)
        }
      }

      val orcRelation = OrcRelation(pathArray, Map.empty)(sqlContext)
      sqlContext.baseRelationToDataFrame(orcRelation)
    }
  }

  implicit class OrcDataFrame(dataFrame: DataFrame) {
    def saveAsOrcFile(path: String, mode: SaveMode = SaveMode.Overwrite): Unit = {
      dataFrame.save(path, source = classOf[DefaultSource].getCanonicalName, mode)
    }
  }

  // Flags for orc copression, predicates pushdown, etc.
  val orcDefaultCompressVar = "hive.exec.orc.default.compress"

  // This constant duplicates `OrcInputFormat.SARG_PUSHDOWN`, which is unfortunately not public.
  val SARG_PUSHDOWN = "sarg.pushdown"
}
