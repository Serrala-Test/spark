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

package org.apache.spark.graph.api

import scala.collection.JavaConverters

import org.apache.spark.sql.{functions, DataFrame, SparkSession}

object CypherSession {
  val ID_COLUMN = "$ID"
  val SOURCE_ID_COLUMN = "$SOURCE_ID"
  val TARGET_ID_COLUMN = "$TARGET_ID"
  val LABEL_COLUMN_PREFIX = ":"
}

/**
 * The entry point for using property graphs in Spark.
 *
 * Provides factory methods for creating [[PropertyGraph]] instances.
 *
 * Wraps a [[SparkSession]].
 *
 * @since 3.0.0
 */
trait CypherSession {

  def sparkSession: SparkSession

  /**
   * Creates a [[PropertyGraph]] from a sequence of [[NodeFrame]]s and [[RelationshipFrame]]s.
   * At least one [[NodeFrame]] has to be provided.
   *
   * For each label set and relationship type there can be at most one [[NodeFrame]] and at most one
   * [[RelationshipFrame]], respectively.
   *
   * @param nodes         [[NodeFrame]]s that define the nodes in the graph
   * @param relationships [[RelationshipFrame]]s that define the relationships in the graph
   * @since 3.0.0
   */
  def createGraph(nodes: Seq[NodeFrame], relationships: Seq[RelationshipFrame]): PropertyGraph

  /**
   * Creates a [[PropertyGraph]] from a sequence of [[NodeFrame]]s and [[RelationshipFrame]]s.
   * At least one [[NodeFrame]] has to be provided.
   *
   * For each label set and relationship type there can be at most one [[NodeFrame]] and at most one
   * [[RelationshipFrame]], respectively.
   *
   * @param nodes         [[NodeFrame]]s that define the nodes in the graph
   * @param relationships [[RelationshipFrame]]s that define the relationships in the graph
   * @since 3.0.0
   */
  def createGraph(
      nodes: java.util.List[NodeFrame],
      relationships: java.util.List[RelationshipFrame]): PropertyGraph = {
    createGraph(JavaConverters.asScalaBuffer(nodes), JavaConverters.asScalaBuffer(relationships))
  }

  /**
   * Creates a [[PropertyGraph]] from nodes and relationships.
   *
   * The given DataFrames need to adhere to the following column naming conventions:
   *
   * {{{
   *     Id column:        `$ID`            (nodes and relationships)
   *     SourceId column:  `$SOURCE_ID`     (relationships)
   *     TargetId column:  `$TARGET_ID`     (relationships)
   *
   *     Label columns:    `:{LABEL_NAME}`  (nodes)
   *     RelType columns:  `:{REL_TYPE}`    (relationships)
   *
   *     Property columns: `{Property_Key}` (nodes and relationships)
   * }}}
   *
   * @see [[CypherSession]]
   * @param nodes         node [[DataFrame]]
   * @param relationships relationship [[DataFrame]]
   * @since 3.0.0
   */
  def createGraph(nodes: DataFrame, relationships: DataFrame): PropertyGraph = {
    val idColumn = CypherSession.ID_COLUMN
    val sourceIdColumn = CypherSession.SOURCE_ID_COLUMN
    val targetIdColumn = CypherSession.TARGET_ID_COLUMN

    val labelColumns = nodes.columns.filter(_.startsWith(CypherSession.LABEL_COLUMN_PREFIX)).toSet
    val nodeProperties = (nodes.columns.toSet - idColumn -- labelColumns)
      .map(col => col -> col)
      .toMap

    val trueLit = functions.lit(true)
    val falseLit = functions.lit(false)

    val labelSets = labelColumns.subsets().toSet + Set.empty
    val nodeFrames = labelSets.map { labelSet =>
      val predicate = labelColumns
        .map {
          case labelColumn if labelSet.contains(labelColumn) => nodes.col(labelColumn) === trueLit
          case labelColumn => nodes.col(labelColumn) === falseLit
        }
        .reduce(_ && _)

      NodeFrame(nodes.filter(predicate), idColumn, labelSet.map(_.substring(1)), nodeProperties)
    }

    val relColumns = relationships.columns.toSet
    val relTypeColumns = relColumns.filter(_.startsWith(CypherSession.LABEL_COLUMN_PREFIX))
    val propertyColumns = relColumns - idColumn - sourceIdColumn - targetIdColumn -- relTypeColumns
    val relProperties = propertyColumns.map(col => col -> col).toMap
    val relFrames = relTypeColumns.map { relTypeColumn =>
      val predicate = relationships.col(relTypeColumn) === trueLit

      RelationshipFrame(
        relationships.filter(predicate),
        idColumn,
        sourceIdColumn,
        targetIdColumn,
        relTypeColumn.substring(1),
        relProperties)
    }

    createGraph(nodeFrames.toSeq, relFrames.toSeq)
  }
}
