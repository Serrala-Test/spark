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

package org.apache.spark.sql.execution

import org.apache.commons.lang3.StringUtils

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.catalyst.{InternalRow, TableIdentifier}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.codegen.{CodegenContext, ExprCode}
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.plans.physical.{Partitioning, UnknownPartitioning}
import org.apache.spark.sql.execution.datasources.HadoopFsRelation
import org.apache.spark.sql.execution.datasources.parquet.{ParquetFileFormat => ParquetSource}
import org.apache.spark.sql.execution.metric.SQLMetrics
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.sources.BaseRelation
import org.apache.spark.sql.types.{DataType, StructType}
import org.apache.spark.util.Utils

private[sql] trait DataSourceScanExec extends LeafExecNode with CodegenSupport {
  val relation: BaseRelation
  val metastoreTableIdentifier: Option[TableIdentifier]
  val buildScan: () => RDD[InternalRow]

  override def inputRDDs(): Seq[RDD[InternalRow]] = {
    buildScan() :: Nil
  }

  override val nodeName: String = {
    s"Scan $relation ${metastoreTableIdentifier.map(_.unquotedString).getOrElse("")}"
  }

  // Ignore rdd when checking results
  override def sameResult(plan: SparkPlan): Boolean = plan match {
    case other: DataSourceScanExec => relation == other.relation && metadata == other.metadata
    case _ => false
  }
}

/** Physical plan node for scanning data from a relation. */
private[sql] case class RowDataSourceScanExec(
    output: Seq[Attribute],
    @transient buildScan: () => RDD[InternalRow],
    @transient relation: BaseRelation,
    override val outputPartitioning: Partitioning,
    override val metadata: Map[String, String],
    override val metastoreTableIdentifier: Option[TableIdentifier])
  extends DataSourceScanExec {

  private[sql] override lazy val metrics =
    Map("numOutputRows" -> SQLMetrics.createMetric(sparkContext, "number of output rows"))

  val outputUnsafeRows = relation match {
    case r: HadoopFsRelation if r.fileFormat.isInstanceOf[ParquetSource] =>
      !SparkSession.getActiveSession.get.sessionState.conf.getConf(
        SQLConf.PARQUET_VECTORIZED_READER_ENABLED)
    case _: HadoopFsRelation => true
    case _ => false
  }

  protected override def doExecute(): RDD[InternalRow] = {
    val unsafeRow = if (outputUnsafeRows) {
      buildScan()
    } else {
      buildScan().mapPartitionsInternal { iter =>
        val proj = UnsafeProjection.create(schema)
        iter.map(proj)
      }
    }

    val numOutputRows = longMetric("numOutputRows")
    unsafeRow.map { r =>
      numOutputRows += 1
      r
    }
  }

  override def simpleString: String = {
    val metadataEntries = for ((key, value) <- metadata.toSeq.sorted) yield {
      key + ": " + StringUtils.abbreviate(value, 100)
    }

    s"$nodeName${Utils.truncatedString(output, "[", ",", "]")}" +
      s"${Utils.truncatedString(metadataEntries, " ", ", ", "")}"
  }

  override protected def doProduce(ctx: CodegenContext): String = {
    val numOutputRows = metricTerm(ctx, "numOutputRows")
    // PhysicalRDD always just has one input
    val input = ctx.freshName("input")
    ctx.addMutableState("scala.collection.Iterator", input, s"$input = inputs[0];")
    val exprRows = output.zipWithIndex.map{ case (a, i) =>
      new BoundReference(i, a.dataType, a.nullable)
    }
    val row = ctx.freshName("row")
    ctx.INPUT_ROW = row
    ctx.currentVars = null
    val columnsRowInput = exprRows.map(_.genCode(ctx))
    val inputRow = if (outputUnsafeRows) row else null
    s"""
       |while ($input.hasNext()) {
       |  InternalRow $row = (InternalRow) $input.next();
       |  $numOutputRows.add(1);
       |  ${consume(ctx, columnsRowInput, inputRow).trim}
       |  if (shouldStop()) return;
       |}
     """.stripMargin
  }
}

/** Physical plan node for scanning data from a batched relation. */
private[sql] case class BatchedDataSourceScanExec(
    output: Seq[Attribute],
    @transient buildScan: () => RDD[InternalRow],
    @transient relation: HadoopFsRelation,
    override val outputPartitioning: Partitioning,
    override val metadata: Map[String, String],
    override val metastoreTableIdentifier: Option[TableIdentifier])
  extends DataSourceScanExec {

  private[sql] override lazy val metrics =
    Map("numOutputRows" -> SQLMetrics.createMetric(sparkContext, "number of output rows"),
      "scanTime" -> SQLMetrics.createTimingMetric(sparkContext, "scan time"))

  protected override def doExecute(): RDD[InternalRow] = {
    // in the case of fallback, this batched scan should never fail because of:
    // 1) only primitive types are supported
    // 2) the number of columns should be smaller than spark.sql.codegen.maxFields
    WholeStageCodegenExec(this).execute()
  }

  override def simpleString: String = {
    val metadataEntries = for ((key, value) <- metadata.toSeq.sorted) yield {
      key + ": " + StringUtils.abbreviate(value, 100)
    }
    val metadataStr = Utils.truncatedString(metadataEntries, " ", ", ", "")
    s"Batched$nodeName${Utils.truncatedString(output, "[", ",", "]")}$metadataStr"
  }

  private def genCodeColumnVector(ctx: CodegenContext, columnVar: String, ordinal: String,
    dataType: DataType, nullable: Boolean): ExprCode = {
    val javaType = ctx.javaType(dataType)
    val value = ctx.getValue(columnVar, dataType, ordinal)
    val isNullVar = if (nullable) { ctx.freshName("isNull") } else { "false" }
    val valueVar = ctx.freshName("value")
    val str = s"columnVector[$columnVar, $ordinal, ${dataType.simpleString}]"
    val code = s"${ctx.registerComment(str)}\n" + (if (nullable) {
      s"""
        boolean ${isNullVar} = ${columnVar}.isNullAt($ordinal);
        $javaType ${valueVar} = ${isNullVar} ? ${ctx.defaultValue(dataType)} : ($value);
      """
    } else {
      s"$javaType ${valueVar} = $value;"
    }).trim
    ExprCode(code, isNullVar, valueVar)
  }

  // Support codegen so that we can avoid the UnsafeRow conversion in all cases. Codegen
  // never requires UnsafeRow as input.
  override protected def doProduce(ctx: CodegenContext): String = {
    val input = ctx.freshName("input")
    // PhysicalRDD always just has one input
    ctx.addMutableState("scala.collection.Iterator", input, s"$input = inputs[0];")

    // metrics
    val numOutputRows = metricTerm(ctx, "numOutputRows")
    val scanTimeMetric = metricTerm(ctx, "scanTime")
    val scanTimeTotalNs = ctx.freshName("scanTime")
    ctx.addMutableState("long", scanTimeTotalNs, s"$scanTimeTotalNs = 0;")

    val columnarBatchClz = "org.apache.spark.sql.execution.vectorized.ColumnarBatch"
    val batch = ctx.freshName("batch")
    ctx.addMutableState(columnarBatchClz, batch, s"$batch = null;")

    val columnVectorClz = "org.apache.spark.sql.execution.vectorized.ColumnVector"
    val idx = ctx.freshName("batchIdx")
    ctx.addMutableState("int", idx, s"$idx = 0;")
    val colVars = output.indices.map(i => ctx.freshName("colInstance" + i))
    val columnAssigns = colVars.zipWithIndex.map { case (name, i) =>
      ctx.addMutableState(columnVectorClz, name, s"$name = null;")
      s"$name = $batch.column($i);"
    }

    val nextBatch = ctx.freshName("nextBatch")
    ctx.addNewFunction(nextBatch,
      s"""
         |private void $nextBatch() throws java.io.IOException {
         |  long getBatchStart = System.nanoTime();
         |  if ($input.hasNext()) {
         |    $batch = ($columnarBatchClz)$input.next();
         |    $numOutputRows.add($batch.numRows());
         |    $idx = 0;
         |    ${columnAssigns.mkString("", "\n", "\n")}
         |  }
         |  $scanTimeTotalNs += System.nanoTime() - getBatchStart;
         |}""".stripMargin)

    ctx.currentVars = null
    val rowidx = ctx.freshName("rowIdx")
    val columnsBatchInput = (output zip colVars).map { case (attr, colVar) =>
      genCodeColumnVector(ctx, colVar, rowidx, attr.dataType, attr.nullable)
    }
    s"""
       |if ($batch == null) {
       |  $nextBatch();
       |}
       |while ($batch != null) {
       |  int numRows = $batch.numRows();
       |  while ($idx < numRows) {
       |    int $rowidx = $idx++;
       |    ${consume(ctx, columnsBatchInput).trim}
       |    if (shouldStop()) return;
       |  }
       |  $batch = null;
       |  $nextBatch();
       |}
       |$scanTimeMetric.add($scanTimeTotalNs / (1000 * 1000));
       |$scanTimeTotalNs = 0;
     """.stripMargin
  }
}

private[sql] object DataSourceScanExec {
  // Metadata keys
  val INPUT_PATHS = "InputPaths"
  val PUSHED_FILTERS = "PushedFilters"

  def createFileScan(
      output: Seq[Attribute],
      buildScan: () => RDD[InternalRow],
      relation: HadoopFsRelation,
      outputPartitioning: Partitioning,
      metadata: Map[String, String] = Map.empty,
      metastoreTableIdentifier: Option[TableIdentifier] = None): DataSourceScanExec = {
    if (relation.fileFormat.supportBatch(
        relation.sparkSession, StructType.fromAttributes(output))) {
      BatchedDataSourceScanExec(
        output, buildScan, relation, outputPartitioning, metadata, metastoreTableIdentifier)
    } else {
      RowDataSourceScanExec(
        output, buildScan, relation, outputPartitioning, metadata, metastoreTableIdentifier)
    }
  }

  def create(
      output: Seq[Attribute],
      rdd: RDD[InternalRow],
      relation: BaseRelation,
      metadata: Map[String, String] = Map.empty,
      metastoreTableIdentifier: Option[TableIdentifier] = None): DataSourceScanExec = {
    assert(!relation.isInstanceOf[HadoopFsRelation], "Should use createFileScan instead")
    RowDataSourceScanExec(
      output, () => rdd, relation, UnknownPartitioning(0), metadata, metastoreTableIdentifier)
  }
}
