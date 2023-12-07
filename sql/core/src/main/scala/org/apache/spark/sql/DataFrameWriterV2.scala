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

package org.apache.spark.sql

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import org.apache.spark.annotation.Experimental
import org.apache.spark.sql.catalyst.analysis.{CannotReplaceMissingTableException, NoSuchTableException, TableAlreadyExistsException, UnresolvedIdentifier, UnresolvedRelation}
import org.apache.spark.sql.catalyst.expressions.{Attribute, Bucket, Days, Expression, Hours, Literal, Months, Years}
import org.apache.spark.sql.catalyst.plans.logical.{AppendData, Assignment, CreateTableAsSelect, DeleteAction, InsertAction, InsertStarAction, LogicalPlan, MergeAction, MergeIntoTable, OptionList, OverwriteByExpression, OverwritePartitionsDynamic, ReplaceTableAsSelect, UnresolvedTableSpec, UpdateAction, UpdateStarAction}
import org.apache.spark.sql.connector.expressions.{LogicalExpressions, NamedReference, Transform}
import org.apache.spark.sql.errors.QueryCompilationErrors
import org.apache.spark.sql.execution.QueryExecution
import org.apache.spark.sql.functions.expr
import org.apache.spark.sql.types.IntegerType

/**
 * Interface used to write a [[org.apache.spark.sql.Dataset]] to external storage using the v2 API.
 *
 * @since 3.0.0
 */
@Experimental
final class DataFrameWriterV2[T] private[sql](table: String, ds: Dataset[T])
    extends CreateTableWriter[T] {

  private val df: DataFrame = ds.toDF()

  private val sparkSession = ds.sparkSession

  private val tableName = sparkSession.sessionState.sqlParser.parseMultipartIdentifier(table)

  private val logicalPlan = df.queryExecution.logical

  private var provider: Option[String] = None

  private val options = new mutable.HashMap[String, String]()

  private val properties = new mutable.HashMap[String, String]()

  private var partitioning: Option[Seq[Transform]] = None

  private var on: Option[Column] = None
  var matchedActions: Seq[MergeAction] = Seq.empty[MergeAction]
  var notMatchedActions: Seq[MergeAction] = Seq.empty[MergeAction]
  var notMatchedBySourceActions: Seq[MergeAction] = Seq.empty[MergeAction]

  override def using(provider: String): CreateTableWriter[T] = {
    this.provider = Some(provider)
    this
  }

  override def option(key: String, value: String): DataFrameWriterV2[T] = {
    this.options.put(key, value)
    this
  }

  override def options(options: scala.collection.Map[String, String]): DataFrameWriterV2[T] = {
    options.foreach {
      case (key, value) =>
        this.options.put(key, value)
    }
    this
  }

  override def options(options: java.util.Map[String, String]): DataFrameWriterV2[T] = {
    this.options(options.asScala)
    this
  }

  override def tableProperty(property: String, value: String): CreateTableWriter[T] = {
    this.properties.put(property, value)
    this
  }

  @scala.annotation.varargs
  override def partitionedBy(column: Column, columns: Column*): CreateTableWriter[T] = {
    def ref(name: String): NamedReference = LogicalExpressions.parseReference(name)

    val asTransforms = (column +: columns).map(_.expr).map {
      case Years(attr: Attribute) =>
        LogicalExpressions.years(ref(attr.name))
      case Months(attr: Attribute) =>
        LogicalExpressions.months(ref(attr.name))
      case Days(attr: Attribute) =>
        LogicalExpressions.days(ref(attr.name))
      case Hours(attr: Attribute) =>
        LogicalExpressions.hours(ref(attr.name))
      case Bucket(Literal(numBuckets: Int, IntegerType), attr: Attribute) =>
        LogicalExpressions.bucket(numBuckets, Array(ref(attr.name)))
      case attr: Attribute =>
        LogicalExpressions.identity(ref(attr.name))
      case expr =>
        throw QueryCompilationErrors.invalidPartitionTransformationError(expr)
    }

    this.partitioning = Some(asTransforms)
    this
  }

  override def create(): Unit = {
    val tableSpec = UnresolvedTableSpec(
      properties = properties.toMap,
      provider = provider,
      optionExpression = OptionList(Seq.empty),
      location = None,
      comment = None,
      serde = None,
      external = false)
    runCommand(
      CreateTableAsSelect(
        UnresolvedIdentifier(tableName),
        partitioning.getOrElse(Seq.empty),
        logicalPlan,
        tableSpec,
        options.toMap,
        false))
  }

  override def replace(): Unit = {
    internalReplace(orCreate = false)
  }

  override def createOrReplace(): Unit = {
    internalReplace(orCreate = true)
  }


  /**
   * Append the contents of the data frame to the output table.
   *
   * If the output table does not exist, this operation will fail with
   * [[org.apache.spark.sql.catalyst.analysis.NoSuchTableException]]. The data frame will be
   * validated to ensure it is compatible with the existing table.
   *
   * @throws org.apache.spark.sql.catalyst.analysis.NoSuchTableException If the table does not exist
   */
  @throws(classOf[NoSuchTableException])
  def append(): Unit = {
    val append = AppendData.byName(UnresolvedRelation(tableName), logicalPlan, options.toMap)
    runCommand(append)
  }

  /**
   * Overwrite rows matching the given filter condition with the contents of the data frame in
   * the output table.
   *
   * If the output table does not exist, this operation will fail with
   * [[org.apache.spark.sql.catalyst.analysis.NoSuchTableException]].
   * The data frame will be validated to ensure it is compatible with the existing table.
   *
   * @throws org.apache.spark.sql.catalyst.analysis.NoSuchTableException If the table does not exist
   */
  @throws(classOf[NoSuchTableException])
  def overwrite(condition: Column): Unit = {
    val overwrite = OverwriteByExpression.byName(
      UnresolvedRelation(tableName), logicalPlan, condition.expr, options.toMap)
    runCommand(overwrite)
  }

  /**
   * Specifies the merge condition.
   *
   * Sets the condition, provided as a `String`, to be used for merging data. This condition
   * is converted internally to a `Column` and used to determine how rows from the source
   * DataFrame are matched with rows in the target table.
   *
   * @param condition a `String` representing the merge condition.
   * @return the current `DataFrameWriterV2` instance with the specified merge condition set.
   */
  def on(condition: String): DataFrameWriterV2[T] = {
    on(Column(condition))
  }

  /**
   * Specifies the merge condition.
   *
   * Sets the condition to be used for merging data. This condition is used to determine
   * how rows from the source DataFrame are matched with rows in the target table.
   *
   * @param condition a `Column` representing the merge condition.
   * @return the current `DataFrameWriterV2` instance with the specified merge condition set.
   */
  def on(condition: Column): DataFrameWriterV2[T] = {
    this.on = Some(condition)
    this
  }

  /**
   * Initialize a `WhenMatched` object without any condition.
   *
   * This `WhenMatched` can be followed by one of the following merge actions:
   *   - `updateAll`: Update all the target table fields with source dataset fields.
   *   - `update(Map)`: Update all the target table records while changing only
   *     a subset of fields based on the provided assignment.
   *   - `delete`: Delete all the target table records.
   *
   * @return a new `WhenMatched` object.
   */
  def whenMatched(): WhenMatched[T] = {
    new WhenMatched[T](this, None)
  }

  /**
   * Initialize a `WhenMatched` object with a condition.
   *
   * This `WhenMatched` action will be executed if and only if the specified `condition`
   * is satisfied.
   *
   * This `WhenMatched` can be followed by one of the following merge actions:
   *   - `updateAll`: Update all the target table fields with source dataset fields.
   *   - `update(Map)`: Update all the target table records while changing only
   *     a subset of fields based on the provided assignment.
   *   - `delete`: Delete all the target table records.
   *
   * @param condition a `Column` representing the condition to be evaluated for the action.
   * @return a new `WhenMatched` object configured with the specified condition.
   */
  def whenMatched(condition: Column): WhenMatched[T] = {
    new WhenMatched[T](this, Some(condition.expr))
  }

  /**
   * Initialize a `WhenMatched` object with a specified condition.
   *
   * This `WhenMatched` action will be executed if and only if the given `condition`
   * is satisfied. The condition is represented as a `String` and internally converted
   * to a `Column`.
   *
   * The `WhenMatched` instance can perform one of the following merge actions:
   *   - `updateAll`: Update all the target table fields with source dataset fields.
   *   - `update(Map)`: Update all the target table records while changing only
   *     a subset of fields based on the provided assignment.
   *   - `delete`: Delete all the target table records.
   *
   * @param condition a `String` representing the condition to be evaluated for the action.
   * @return a new `WhenMatched` object configured with the specified condition.
   */
  def whenMatched(condition: String): WhenMatched[T] = {
    whenMatched(Column(condition))
  }

  /**
   * Initialize a `WhenNotMatched` object without any condition.
   *
   * This `WhenNotMatched` can be followed by one of the following merge actions:
   *   - `updateAll`: Update all the target table fields with source dataset fields.
   *   - `update(Map)`: Update all the target table records while changing only
   *     a subset of fields based on the provided assignment.
   *   - `insertAll`: Insert all the target table with source dataset records.
   *   - `insert(Map)`: Insert all the target table records while changing only
   *     a subset of fields based on the provided assignment.
   *   - `delete`: Delete all the target table records.
   *
   * @return a new `WhenNotMatched` object.
   */
  def whenNotMatched(): WhenNotMatched[T] = {
    new WhenNotMatched[T](this, None)
  }

  /**
   * Initialize a `WhenNotMatched` object with a condition.
   *
   * This `WhenNotMatched` action will be executed if and only if the specified `condition`
   * is satisfied.
   *
   * This `WhenNotMatched` can be followed by one of the following merge actions:
   *   - `updateAll`: Update all the target table fields with source dataset fields.
   *   - `update(Map)`: Update all the target table records while changing only
   *     a subset of fields based on the provided assignment.
   *   - `insertAll`: Insert all the target table with source dataset records.
   *   - `insert(Map)`: Insert all the target table records while changing only
   *     a subset of fields based on the provided assignment.
   *   - `delete`: Delete all the target table records.
   *
   * @param condition a `Column` representing the condition to be evaluated for the action.
   * @return a new `WhenNotMatched` object configured with the specified condition.
   */
  def whenNotMatched(condition: Column): WhenNotMatched[T] = {
    new WhenNotMatched[T](this, Some(condition.expr))
  }

  /**
   * Initialize a `WhenNotMatched` object with a condition.
   *
   * This `WhenNotMatched` action will be executed if and only if the specified `condition`
   * is satisfied. The condition is represented as a `String` and internally converted
   * to a `Column`.
   *
   * This `WhenNotMatched` can be followed by one of the following merge actions:
   *   - `updateAll`: Update all the target table fields with source dataset fields.
   *   - `update(Map)`: Update all the target table records while changing only
   *     a subset of fields based on the provided assignment.
   *   - `insertAll`: Insert all the target table with source dataset records.
   *   - `insert(Map)`: Insert all the target table records while changing only
   *     a subset of fields based on the provided assignment.
   *   - `delete`: Delete all the target table records.
   *
   * @param condition a `String` representing the condition to be evaluated for the action.
   * @return a new `WhenNotMatched` object configured with the specified condition.
   */
  def whenNotMatched(condition: String): WhenNotMatched[T] = {
    whenNotMatched(Column(condition))
  }

  /**
   * Initialize a `WhenNotMatchedBySource` object without any condition.
   *
   * This `WhenNotMatchedBySource` can be followed by one of the following merge actions:
   *   - `updateAll`: Update all the target table fields with source dataset fields.
   *   - `update(Map)`: Update all the target table records while changing only
   *     a subset of fields based on the provided assignment.
   *   - `insertAll`: Insert all the target table with source dataset records.
   *   - `insert(Map)`: Insert all the target table records while changing only
   *     a subset of fields based on the provided assignment.
   *   - `delete`: Delete all the target table records.
   *
   * @return a new `WhenNotMatchedBySource` object.
   */
  def whenNotMatchedBySource(): WhenNotMatchedBySource[T] = {
    new WhenNotMatchedBySource[T](this, None)
  }

  /**
   * Initialize a `WhenNotMatchedBySource` object with a condition.
   *
   * This `WhenNotMatchedBySource` action will be executed if and only if the specified `condition`
   * is satisfied.
   *
   * This `WhenNotMatchedBySource` can be followed by one of the following merge actions:
   *   - `updateAll`: Update all the target table fields with source dataset fields.
   *   - `update(Map)`: Update all the target table records while changing only
   *     a subset of fields based on the provided assignment.
   *   - `insertAll`: Insert all the target table with source dataset records.
   *   - `insert(Map)`: Insert all the target table records while changing only
   *     a subset of fields based on the provided assignment.
   *   - `delete`: Delete all the target table records.
   *
   * @param condition a `Column` representing the condition to be evaluated for the action.
   * @return a new `WhenNotMatchedBySource` object configured with the specified condition.
   */
  def whenNotMatchedBySource(condition: Column): WhenNotMatchedBySource[T] = {
    new WhenNotMatchedBySource[T](this, Some(condition.expr))
  }

  /**
   * Initialize a `WhenNotMatchedBySource` object with a condition.
   *
   * This `WhenNotMatchedBySource` action will be executed if and only if the specified `condition`
   * is satisfied. The condition is represented as a `String` and internally converted
   * to a `Column`.
   *
   * This `WhenNotMatchedBySource` can be followed by one of the following merge actions:
   *   - `updateAll`: Update all the target table fields with source dataset fields.
   *   - `update(Map)`: Update all the target table records while changing only
   *     a subset of fields based on the provided assignment.
   *   - `insertAll`: Insert all the target table with source dataset records.
   *   - `insert(Map)`: Insert all the target table records while changing only
   *     a subset of fields based on the provided assignment.
   *   - `delete`: Delete all the target table records.
   *
   * @param condition a `String` representing the condition to be evaluated for the action.
   * @return a new `WhenNotMatchedBySource` object configured with the specified condition.
   */
  def whenNotMatchedBySource(condition: String): WhenNotMatchedBySource[T] = {
    whenNotMatchedBySource(Column(condition))
  }

  /**
   * Executes the merge operation.
   */
  def merge(): Unit = {
    val merge = MergeIntoTable(
      UnresolvedRelation(tableName),
      logicalPlan,
      on.get.expr,
      matchedActions,
      notMatchedActions,
      notMatchedBySourceActions)
    val qe = sparkSession.sessionState.executePlan(merge)
    qe.assertCommandExecuted()
  }

  /**
   * Overwrite all partition for which the data frame contains at least one row with the contents
   * of the data frame in the output table.
   *
   * This operation is equivalent to Hive's `INSERT OVERWRITE ... PARTITION`, which replaces
   * partitions dynamically depending on the contents of the data frame.
   *
   * If the output table does not exist, this operation will fail with
   * [[org.apache.spark.sql.catalyst.analysis.NoSuchTableException]]. The data frame will be
   * validated to ensure it is compatible with the existing table.
   *
   * @throws org.apache.spark.sql.catalyst.analysis.NoSuchTableException If the table does not exist
   */
  @throws(classOf[NoSuchTableException])
  def overwritePartitions(): Unit = {
    val dynamicOverwrite = OverwritePartitionsDynamic.byName(
      UnresolvedRelation(tableName), logicalPlan, options.toMap)
    runCommand(dynamicOverwrite)
  }

  /**
   * Wrap an action to track the QueryExecution and time cost, then report to the user-registered
   * callback functions.
   */
  private def runCommand(command: LogicalPlan): Unit = {
    val qe = new QueryExecution(sparkSession, command, df.queryExecution.tracker)
    qe.assertCommandExecuted()
  }

  private def internalReplace(orCreate: Boolean): Unit = {
    val tableSpec = UnresolvedTableSpec(
      properties = properties.toMap,
      provider = provider,
      optionExpression = OptionList(Seq.empty),
      location = None,
      comment = None,
      serde = None,
      external = false)
    runCommand(ReplaceTableAsSelect(
      UnresolvedIdentifier(tableName),
      partitioning.getOrElse(Seq.empty),
      logicalPlan,
      tableSpec,
      writeOptions = options.toMap,
      orCreate = orCreate))
  }
}

/**
 * Configuration methods common to create/replace operations and insert/overwrite operations.
 * @tparam R builder type to return
 * @since 3.0.0
 */
trait WriteConfigMethods[R] {
  /**
   * Add a write option.
   *
   * @since 3.0.0
   */
  def option(key: String, value: String): R

  /**
   * Add a boolean output option.
   *
   * @since 3.0.0
   */
  def option(key: String, value: Boolean): R = option(key, value.toString)

  /**
   * Add a long output option.
   *
   * @since 3.0.0
   */
  def option(key: String, value: Long): R = option(key, value.toString)

  /**
   * Add a double output option.
   *
   * @since 3.0.0
   */
  def option(key: String, value: Double): R = option(key, value.toString)

  /**
   * Add write options from a Scala Map.
   *
   * @since 3.0.0
   */
  def options(options: scala.collection.Map[String, String]): R

  /**
   * Add write options from a Java Map.
   *
   * @since 3.0.0
   */
  def options(options: java.util.Map[String, String]): R
}

/**
 * Trait to restrict calls to create and replace operations.
 *
 * @since 3.0.0
 */
trait CreateTableWriter[T] extends WriteConfigMethods[CreateTableWriter[T]] {
  /**
   * Create a new table from the contents of the data frame.
   *
   * The new table's schema, partition layout, properties, and other configuration will be
   * based on the configuration set on this writer.
   *
   * If the output table exists, this operation will fail with
   * [[org.apache.spark.sql.catalyst.analysis.TableAlreadyExistsException]].
   *
   * @throws org.apache.spark.sql.catalyst.analysis.TableAlreadyExistsException
   *         If the table already exists
   */
  @throws(classOf[TableAlreadyExistsException])
  def create(): Unit

  /**
   * Replace an existing table with the contents of the data frame.
   *
   * The existing table's schema, partition layout, properties, and other configuration will be
   * replaced with the contents of the data frame and the configuration set on this writer.
   *
   * If the output table does not exist, this operation will fail with
   * [[org.apache.spark.sql.catalyst.analysis.CannotReplaceMissingTableException]].
   *
   * @throws org.apache.spark.sql.catalyst.analysis.CannotReplaceMissingTableException
   *         If the table does not exist
   */
  @throws(classOf[CannotReplaceMissingTableException])
  def replace(): Unit

  /**
   * Create a new table or replace an existing table with the contents of the data frame.
   *
   * The output table's schema, partition layout, properties, and other configuration will be based
   * on the contents of the data frame and the configuration set on this writer. If the table
   * exists, its configuration and data will be replaced.
   */
  def createOrReplace(): Unit

  /**
   * Partition the output table created by `create`, `createOrReplace`, or `replace` using
   * the given columns or transforms.
   *
   * When specified, the table data will be stored by these values for efficient reads.
   *
   * For example, when a table is partitioned by day, it may be stored in a directory layout like:
   * <ul>
   * <li>`table/day=2019-06-01/`</li>
   * <li>`table/day=2019-06-02/`</li>
   * </ul>
   *
   * Partitioning is one of the most widely used techniques to optimize physical data layout.
   * It provides a coarse-grained index for skipping unnecessary data reads when queries have
   * predicates on the partitioned columns. In order for partitioning to work well, the number
   * of distinct values in each column should typically be less than tens of thousands.
   *
   * @since 3.0.0
   */
  def partitionedBy(column: Column, columns: Column*): CreateTableWriter[T]

  /**
   * Specifies a provider for the underlying output data source. Spark's default catalog supports
   * "parquet", "json", etc.
   *
   * @since 3.0.0
   */
  def using(provider: String): CreateTableWriter[T]

  /**
   * Add a table property.
   */
  def tableProperty(property: String, value: String): CreateTableWriter[T]
}

case class WhenMatched[T] (dfWriter: DataFrameWriterV2[T], condition: Option[Expression]) {
  def updateAll(): DataFrameWriterV2[T] = {
    dfWriter.matchedActions = dfWriter.matchedActions :+ UpdateStarAction(condition)
    this.dfWriter
  }

  def update(set: Map[String, Column]): DataFrameWriterV2[T] = {
    dfWriter.matchedActions = dfWriter.matchedActions :+
      UpdateAction(condition, set.map(x => Assignment(expr(x._1).expr, x._2.expr)).toSeq)
    this.dfWriter
  }

  def delete(): DataFrameWriterV2[T] = {
    dfWriter.matchedActions = dfWriter.matchedActions :+ DeleteAction(condition)
    this.dfWriter
  }
}

case class WhenNotMatched[T] (dfWriter: DataFrameWriterV2[T], condition: Option[Expression]) {
  def updateAll(): DataFrameWriterV2[T] = {
    dfWriter.notMatchedActions = dfWriter.notMatchedActions :+ UpdateStarAction(condition)
    this.dfWriter
  }

  def update(set: Map[String, Column]): DataFrameWriterV2[T] = {
    dfWriter.notMatchedActions = dfWriter.notMatchedActions :+
      UpdateAction(condition, set.map(x => Assignment(expr(x._1).expr, x._2.expr)).toSeq)
    this.dfWriter
  }

  def insertAll(): DataFrameWriterV2[T] = {
    dfWriter.notMatchedActions = dfWriter.notMatchedActions :+ InsertStarAction(condition)
    this.dfWriter
  }

  def insert(set: Map[String, Column]): DataFrameWriterV2[T] = {
    dfWriter.notMatchedActions = dfWriter.notMatchedActions :+
      InsertAction(condition, set.map(x => Assignment(expr(x._1).expr, x._2.expr)).toSeq)
    this.dfWriter
  }

  def delete(): DataFrameWriterV2[T] = {
    dfWriter.notMatchedActions = dfWriter.notMatchedActions :+ DeleteAction(condition)
    this.dfWriter
  }
}

case class WhenNotMatchedBySource[T] (
    dfWriter: DataFrameWriterV2[T],
    condition: Option[Expression]) {
  def updateAll(): DataFrameWriterV2[T] = {
    dfWriter.notMatchedBySourceActions =
      dfWriter.notMatchedBySourceActions :+ UpdateStarAction(condition)
    this.dfWriter
  }

  def update(set: Map[String, Column]): DataFrameWriterV2[T] = {
    dfWriter.notMatchedBySourceActions = dfWriter.notMatchedBySourceActions :+
      UpdateAction(condition, set.map(x => Assignment(expr(x._1).expr, x._2.expr)).toSeq)
    this.dfWriter
  }

  def insertAll(): DataFrameWriterV2[T] = {
    dfWriter.notMatchedBySourceActions =
      dfWriter.notMatchedBySourceActions :+ InsertStarAction(condition)
    this.dfWriter
  }

  def insert(set: Map[String, Column]): DataFrameWriterV2[T] = {
    dfWriter.notMatchedBySourceActions = dfWriter.notMatchedBySourceActions :+
      InsertAction(condition, set.map(x => Assignment(expr(x._1).expr, x._2.expr)).toSeq)
    this.dfWriter
  }

  def delete(): DataFrameWriterV2[T] = {
    dfWriter.notMatchedBySourceActions =
      dfWriter.notMatchedBySourceActions :+ DeleteAction(condition)
    this.dfWriter
  }
}
