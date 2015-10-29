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

import scala.collection.JavaConverters._
import scala.language.implicitConversions

import org.apache.spark.annotation.Experimental
import org.apache.spark.sql.catalyst.analysis.{UnresolvedAlias, UnresolvedAttribute, Star}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.logical.{Pivot, Rollup, Cube, Aggregate}
import org.apache.spark.sql.types.NumericType

/**
 * Companion object for GroupedData
 */
private[sql] object GroupedData {
  def apply(
      df: DataFrame,
      groupingExprs: Seq[Expression],
      groupType: GroupType): GroupedData = {
    new GroupedData(df, groupingExprs, groupType)
  }

  /**
   * The Grouping Type
   */
  private[sql] trait GroupType

  /**
   * To indicate it's the GroupBy
   */
  private[sql] object GroupByType extends GroupType

  /**
   * To indicate it's the CUBE
   */
  private[sql] object CubeType extends GroupType

  /**
   * To indicate it's the ROLLUP
   */
  private[sql] object RollupType extends GroupType

  /**
   * To indicate it's the PIVOT
   */
  private[sql] case class PivotType(pivotCol: Expression, values: Seq[String]) extends GroupType
}

/**
 * :: Experimental ::
 * A set of methods for aggregations on a [[DataFrame]], created by [[DataFrame.groupBy]].
 *
 * @since 1.3.0
 */
@Experimental
class GroupedData protected[sql](
    df: DataFrame,
    groupingExprs: Seq[Expression],
    private val groupType: GroupedData.GroupType) {

  private[this] def toDF(aggExprs: Seq[Expression]): DataFrame = {
    val aggregates = if (df.sqlContext.conf.dataFrameRetainGroupColumns) {
      groupingExprs ++ aggExprs
    } else {
      aggExprs
    }

    val aliasedAgg = aggregates.map(alias)

    groupType match {
      case GroupedData.GroupByType =>
        DataFrame(
          df.sqlContext, Aggregate(groupingExprs, aliasedAgg, df.logicalPlan))
      case GroupedData.RollupType =>
        DataFrame(
          df.sqlContext, Rollup(groupingExprs, df.logicalPlan, aliasedAgg))
      case GroupedData.CubeType =>
        DataFrame(
          df.sqlContext, Cube(groupingExprs, df.logicalPlan, aliasedAgg))
      case GroupedData.PivotType(pivotCol, values) =>
        val aliasedGrps = groupingExprs.map(alias)
        DataFrame(
          df.sqlContext, Pivot(aliasedGrps, pivotCol, values, aggExprs, df.logicalPlan))
    }
  }

  // Wrap UnresolvedAttribute with UnresolvedAlias, as when we resolve UnresolvedAttribute, we
  // will remove intermediate Alias for ExtractValue chain, and we need to alias it again to
  // make it a NamedExpression.
  private[this] def alias(expr: Expression): NamedExpression = expr match {
    case u: UnresolvedAttribute => UnresolvedAlias(u)
    case expr: NamedExpression => expr
    case expr: Expression => Alias(expr, expr.prettyString)()
  }

  private[this] def aggregateNumericColumns(colNames: String*)(f: Expression => Expression)
    : DataFrame = {

    val columnExprs = if (colNames.isEmpty) {
      // No columns specified. Use all numeric columns.
      df.numericColumns
    } else {
      // Make sure all specified columns are numeric.
      colNames.map { colName =>
        val namedExpr = df.resolve(colName)
        if (!namedExpr.dataType.isInstanceOf[NumericType]) {
          throw new AnalysisException(
            s""""$colName" is not a numeric column. """ +
            "Aggregation function can only be applied on a numeric column.")
        }
        namedExpr
      }
    }
    toDF(columnExprs.map(f))
  }

  private[this] def strToExpr(expr: String): (Expression => Expression) = {
    expr.toLowerCase match {
      case "avg" | "average" | "mean" => Average
      case "max" => Max
      case "min" => Min
      case "stddev" => Stddev
      case "stddev_pop" => StddevPop
      case "stddev_samp" => StddevSamp
      case "variance" => Variance
      case "var_pop" => VariancePop
      case "var_samp" => VarianceSamp
      case "sum" => Sum
      case "skewness" => Skewness
      case "kurtosis" => Kurtosis
      case "count" | "size" =>
        // Turn count(*) into count(1)
        (inputExpr: Expression) => inputExpr match {
          case s: Star => Count(Literal(1))
          case _ => Count(inputExpr)
        }
    }
  }

  /**
   * (Scala-specific) Compute aggregates by specifying a map from column name to
   * aggregate methods. The resulting [[DataFrame]] will also contain the grouping columns.
   *
   * The available aggregate methods are `avg`, `max`, `min`, `sum`, `count`.
   * {{{
   *   // Selects the age of the oldest employee and the aggregate expense for each department
   *   df.groupBy("department").agg(
   *     "age" -> "max",
   *     "expense" -> "sum"
   *   )
   * }}}
   *
   * @since 1.3.0
   */
  def agg(aggExpr: (String, String), aggExprs: (String, String)*): DataFrame = {
    agg((aggExpr +: aggExprs).toMap)
  }

  /**
   * (Scala-specific) Compute aggregates by specifying a map from column name to
   * aggregate methods. The resulting [[DataFrame]] will also contain the grouping columns.
   *
   * The available aggregate methods are `avg`, `max`, `min`, `sum`, `count`.
   * {{{
   *   // Selects the age of the oldest employee and the aggregate expense for each department
   *   df.groupBy("department").agg(Map(
   *     "age" -> "max",
   *     "expense" -> "sum"
   *   ))
   * }}}
   *
   * @since 1.3.0
   */
  def agg(exprs: Map[String, String]): DataFrame = {
    toDF(exprs.map { case (colName, expr) =>
      strToExpr(expr)(df(colName).expr)
    }.toSeq)
  }

  /**
   * (Java-specific) Compute aggregates by specifying a map from column name to
   * aggregate methods. The resulting [[DataFrame]] will also contain the grouping columns.
   *
   * The available aggregate methods are `avg`, `max`, `min`, `sum`, `count`.
   * {{{
   *   // Selects the age of the oldest employee and the aggregate expense for each department
   *   import com.google.common.collect.ImmutableMap;
   *   df.groupBy("department").agg(ImmutableMap.of("age", "max", "expense", "sum"));
   * }}}
   *
   * @since 1.3.0
   */
  def agg(exprs: java.util.Map[String, String]): DataFrame = {
    agg(exprs.asScala.toMap)
  }

  /**
   * Compute aggregates by specifying a series of aggregate columns. Note that this function by
   * default retains the grouping columns in its output. To not retain grouping columns, set
   * `spark.sql.retainGroupColumns` to false.
   *
   * The available aggregate methods are defined in [[org.apache.spark.sql.functions]].
   *
   * {{{
   *   // Selects the age of the oldest employee and the aggregate expense for each department
   *
   *   // Scala:
   *   import org.apache.spark.sql.functions._
   *   df.groupBy("department").agg(max("age"), sum("expense"))
   *
   *   // Java:
   *   import static org.apache.spark.sql.functions.*;
   *   df.groupBy("department").agg(max("age"), sum("expense"));
   * }}}
   *
   * Note that before Spark 1.4, the default behavior is to NOT retain grouping columns. To change
   * to that behavior, set config variable `spark.sql.retainGroupColumns` to `false`.
   * {{{
   *   // Scala, 1.3.x:
   *   df.groupBy("department").agg($"department", max("age"), sum("expense"))
   *
   *   // Java, 1.3.x:
   *   df.groupBy("department").agg(col("department"), max("age"), sum("expense"));
   * }}}
   *
   * @since 1.3.0
   */
  @scala.annotation.varargs
  def agg(expr: Column, exprs: Column*): DataFrame = {
    toDF((expr +: exprs).map(_.expr))
  }

  /**
   * Count the number of rows for each group.
   * The resulting [[DataFrame]] will also contain the grouping columns.
   *
   * @since 1.3.0
   */
  def count(): DataFrame = toDF(Seq(Alias(Count(Literal(1)), "count")()))

  /**
   * Compute the average value for each numeric columns for each group. This is an alias for `avg`.
   * The resulting [[DataFrame]] will also contain the grouping columns.
   * When specified columns are given, only compute the average values for them.
   *
   * @since 1.3.0
   */
  @scala.annotation.varargs
  def mean(colNames: String*): DataFrame = {
    aggregateNumericColumns(colNames : _*)(Average)
  }

  /**
   * Compute the skewness for each numeric columns for each group.
   * The resulting [[DataFrame]] will also contain the grouping columns.
   * When specified columns are given, only compute the skewness values for them.
   *
   * @since 1.6.0
   */
  @scala.annotation.varargs
  def skewness(colNames: String*): DataFrame = {
    aggregateNumericColumns(colNames : _*)(Skewness)
  }

  /**
   * Compute the kurtosis for each numeric columns for each group.
   * The resulting [[DataFrame]] will also contain the grouping columns.
   * When specified columns are given, only compute the kurtosis values for them.
   *
   * @since 1.6.0
   */
  @scala.annotation.varargs
  def kurtosis(colNames: String*): DataFrame = {
    aggregateNumericColumns(colNames : _*)(Kurtosis)
  }

  /**
   * Compute the max value for each numeric columns for each group.
   * The resulting [[DataFrame]] will also contain the grouping columns.
   * When specified columns are given, only compute the max values for them.
   *
   * @since 1.3.0
   */
  @scala.annotation.varargs
  def max(colNames: String*): DataFrame = {
    aggregateNumericColumns(colNames : _*)(Max)
  }

  /**
   * Compute the mean value for each numeric columns for each group.
   * The resulting [[DataFrame]] will also contain the grouping columns.
   * When specified columns are given, only compute the mean values for them.
   *
   * @since 1.3.0
   */
  @scala.annotation.varargs
  def avg(colNames: String*): DataFrame = {
    aggregateNumericColumns(colNames : _*)(Average)
  }

  /**
   * Compute the min value for each numeric column for each group.
   * The resulting [[DataFrame]] will also contain the grouping columns.
   * When specified columns are given, only compute the min values for them.
   *
   * @since 1.3.0
   */
  @scala.annotation.varargs
  def min(colNames: String*): DataFrame = {
    aggregateNumericColumns(colNames : _*)(Min)
  }

  /**
   * Compute the sample standard deviation for each numeric columns for each group.
   * The resulting [[DataFrame]] will also contain the grouping columns.
   * When specified columns are given, only compute the stddev for them.
   *
   * @since 1.6.0
   */
  @scala.annotation.varargs
  def stddev(colNames: String*): DataFrame = {
    aggregateNumericColumns(colNames : _*)(Stddev)
  }

  /**
   * Compute the population standard deviation for each numeric columns for each group.
   * The resulting [[DataFrame]] will also contain the grouping columns.
   * When specified columns are given, only compute the stddev for them.
   *
   * @since 1.6.0
   */
  @scala.annotation.varargs
  def stddev_pop(colNames: String*): DataFrame = {
    aggregateNumericColumns(colNames : _*)(StddevPop)
  }

  /**
   * Compute the sample standard deviation for each numeric columns for each group.
   * The resulting [[DataFrame]] will also contain the grouping columns.
   * When specified columns are given, only compute the stddev for them.
   *
   * @since 1.6.0
   */
  @scala.annotation.varargs
  def stddev_samp(colNames: String*): DataFrame = {
    aggregateNumericColumns(colNames : _*)(StddevSamp)
  }

  /**
   * Compute the sum for each numeric columns for each group.
   * The resulting [[DataFrame]] will also contain the grouping columns.
   * When specified columns are given, only compute the sum for them.
   *
   * @since 1.3.0
   */
  @scala.annotation.varargs
  def sum(colNames: String*): DataFrame = {
    aggregateNumericColumns(colNames : _*)(Sum)
  }

  /**
   * Compute the sample variance for each numeric columns for each group.
   * The resulting [[DataFrame]] will also contain the grouping columns.
   * When specified columns are given, only compute the variance for them.
   *
   * @since 1.6.0
   */
  @scala.annotation.varargs
  def variance(colNames: String*): DataFrame = {
    aggregateNumericColumns(colNames : _*)(Variance)
  }

  /**
   * Compute the population variance for each numeric columns for each group.
   * The resulting [[DataFrame]] will also contain the grouping columns.
   * When specified columns are given, only compute the variance for them.
   *
   * @since 1.6.0
   */
  @scala.annotation.varargs
  def var_pop(colNames: String*): DataFrame = {
    aggregateNumericColumns(colNames : _*)(VariancePop)
  }

  /**
   * Compute the sample variance for each numeric columns for each group.
   * The resulting [[DataFrame]] will also contain the grouping columns.
   * When specified columns are given, only compute the variance for them.
   *
   * @since 1.6.0
   */
  @scala.annotation.varargs
  def var_samp(colNames: String*): DataFrame = {
    aggregateNumericColumns(colNames : _*)(VarianceSamp)
  }

  /**
   * (Scala-specific) Pivots a column of the current [[DataFrame]] and preform the specified
   * aggregation.
   * {{{
   *   // Compute the sum of earnings for each year by course with each course as a separate column.
   *   df.groupBy($"year").pivot($"course", "dotNET", "Java").agg(sum($"earnings"))
   * }}}
   * @param pivotColumn Column to pivot
   * @param values Values of pivotColumn that will be translated to columns in the output data
   *                    frame.
   * @since 1.6.0
   */
  @scala.annotation.varargs
  def pivot(pivotColumn: Column, values: String*): GroupedData = groupType match {
    case _: GroupedData.PivotType =>
      throw new UnsupportedOperationException("repeated pivots are not supported")
    case GroupedData.GroupByType =>
      new GroupedData(df, groupingExprs, GroupedData.PivotType(pivotColumn.expr, values.toSeq))
    case _ =>
      throw new UnsupportedOperationException("pivot is only supported after a groupBy")
  }

  /**
   * Pivots a column of the current [[DataFrame]] and preform the specified aggregation.
   * {{{
   *   // Compute the sum of earnings for each year by course with each course as a separate column.
   *   df.groupBy("year").pivot("course", "dotNET", "Java").sum("earnings")
   * }}}
   * @param pivotColumn Column to pivot
   * @param values Values of pivotColumn that will be translated to columns in the output data
   *                    frame.
   * @since 1.6.0
   */
  @scala.annotation.varargs
  def pivot(pivotColumn: String, values: String*): GroupedData = {
    val resolvedPivotColumn = Column(df.resolve(pivotColumn))
    pivot(resolvedPivotColumn, values: _*)
  }
}
