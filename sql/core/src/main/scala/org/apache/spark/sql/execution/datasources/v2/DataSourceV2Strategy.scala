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

import scala.collection.JavaConverters._

import org.apache.spark.sql.{AnalysisException, SparkSession, Strategy}
import org.apache.spark.sql.catalyst.analysis.{ResolvedNamespace, ResolvedTable}
import org.apache.spark.sql.catalyst.expressions.{And, Expression, NamedExpression, PredicateHelper, SubqueryExpression}
import org.apache.spark.sql.catalyst.planning.PhysicalOperation
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.connector.catalog.{StagingTableCatalog, SupportsNamespaces, TableCapability, TableCatalog, TableChange}
import org.apache.spark.sql.connector.read.V1Scan
import org.apache.spark.sql.connector.read.streaming.{ContinuousStream, MicroBatchStream}
import org.apache.spark.sql.execution.{FilterExec, LeafExecNode, ProjectExec, RowDataSourceScanExec, SparkPlan}
import org.apache.spark.sql.execution.datasources.DataSourceStrategy
import org.apache.spark.sql.execution.streaming.continuous.{ContinuousCoalesceExec, WriteToContinuousDataSource, WriteToContinuousDataSourceExec}
import org.apache.spark.sql.sources.TableScan
import org.apache.spark.sql.util.CaseInsensitiveStringMap

class DataSourceV2Strategy(session: SparkSession) extends Strategy with PredicateHelper {

  import DataSourceV2Implicits._
  import org.apache.spark.sql.connector.catalog.CatalogV2Implicits._

  private def withProjectAndFilter(
      project: Seq[NamedExpression],
      filters: Seq[Expression],
      scan: LeafExecNode,
      needsUnsafeConversion: Boolean): SparkPlan = {
    val filterCondition = filters.reduceLeftOption(And)
    val withFilter = filterCondition.map(FilterExec(_, scan)).getOrElse(scan)

    if (withFilter.output != project || needsUnsafeConversion) {
      ProjectExec(project, withFilter)
    } else {
      withFilter
    }
  }

  override def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {
    case PhysicalOperation(project, filters,
        relation @ DataSourceV2ScanRelation(_, v1Scan: V1Scan, output)) =>
      val pushedFilters = relation.getTagValue(V2ScanRelationPushDown.PUSHED_FILTERS_TAG)
        .getOrElse(Seq.empty)
      val v1Relation = v1Scan.toV1TableScan(session.sqlContext)
      if (v1Relation.schema != v1Scan.readSchema()) {
        throw new IllegalArgumentException(
          "The fallback v1 relation reports inconsistent schema:\n" +
            "Schema of v2 scan:     " + v1Scan.readSchema() + "\n" +
            "Schema of v1 relation: " + v1Relation.schema)
      }
      val rdd = v1Relation match {
        case s: TableScan => s.buildScan()
        case _ =>
          throw new IllegalArgumentException(
            "`V1Scan.toV1Relation` must return a `TableScan` instance.")
      }
      val unsafeRowRDD = DataSourceStrategy.toCatalystRDD(v1Relation, output, rdd)
      val originalOutputNames = relation.table.schema().map(_.name)
      val requiredColumnsIndex = output.map(_.name).map(originalOutputNames.indexOf)
      val dsScan = RowDataSourceScanExec(
        output,
        requiredColumnsIndex,
        pushedFilters.toSet,
        pushedFilters.toSet,
        unsafeRowRDD,
        v1Relation,
        tableIdentifier = None)
      withProjectAndFilter(project, filters, dsScan, needsUnsafeConversion = false) :: Nil

    case PhysicalOperation(project, filters, relation: DataSourceV2ScanRelation) =>
      // projection and filters were already pushed down in the optimizer.
      // this uses PhysicalOperation to get the projection and ensure that if the batch scan does
      // not support columnar, a projection is added to convert the rows to UnsafeRow.
      val batchExec = BatchScanExec(relation.output, relation.scan)
      withProjectAndFilter(project, filters, batchExec, !batchExec.supportsColumnar) :: Nil

    case r: StreamingDataSourceV2Relation if r.startOffset.isDefined && r.endOffset.isDefined =>
      val microBatchStream = r.stream.asInstanceOf[MicroBatchStream]
      val scanExec = MicroBatchScanExec(
        r.output, r.scan, microBatchStream, r.startOffset.get, r.endOffset.get)

      val withProjection = if (scanExec.supportsColumnar) {
        scanExec
      } else {
        // Add a Project here to make sure we produce unsafe rows.
        ProjectExec(r.output, scanExec)
      }

      withProjection :: Nil

    case r: StreamingDataSourceV2Relation if r.startOffset.isDefined && r.endOffset.isEmpty =>
      val continuousStream = r.stream.asInstanceOf[ContinuousStream]
      val scanExec = ContinuousScanExec(r.output, r.scan, continuousStream, r.startOffset.get)

      val withProjection = if (scanExec.supportsColumnar) {
        scanExec
      } else {
        // Add a Project here to make sure we produce unsafe rows.
        ProjectExec(r.output, scanExec)
      }

      withProjection :: Nil

    case WriteToDataSourceV2(writer, query) =>
      WriteToDataSourceV2Exec(writer, planLater(query)) :: Nil

    case CreateV2Table(catalog, ident, schema, parts, props, ifNotExists) =>
      CreateTableExec(catalog, ident, schema, parts, props, ifNotExists) :: Nil

    case CreateTableAsSelect(catalog, ident, parts, query, props, options, ifNotExists) =>
      val writeOptions = new CaseInsensitiveStringMap(options.asJava)
      catalog match {
        case staging: StagingTableCatalog =>
          AtomicCreateTableAsSelectExec(
            staging, ident, parts, query, planLater(query), props, writeOptions, ifNotExists) :: Nil
        case _ =>
          CreateTableAsSelectExec(
            catalog, ident, parts, query, planLater(query), props, writeOptions, ifNotExists) :: Nil
      }

    case RefreshTable(catalog, ident) =>
      RefreshTableExec(catalog, ident) :: Nil

    case ReplaceTable(catalog, ident, schema, parts, props, orCreate) =>
      catalog match {
        case staging: StagingTableCatalog =>
          AtomicReplaceTableExec(staging, ident, schema, parts, props, orCreate = orCreate) :: Nil
        case _ =>
          ReplaceTableExec(catalog, ident, schema, parts, props, orCreate = orCreate) :: Nil
      }

    case ReplaceTableAsSelect(catalog, ident, parts, query, props, options, orCreate) =>
      val writeOptions = new CaseInsensitiveStringMap(options.asJava)
      catalog match {
        case staging: StagingTableCatalog =>
          AtomicReplaceTableAsSelectExec(
            staging,
            ident,
            parts,
            query,
            planLater(query),
            props,
            writeOptions,
            orCreate = orCreate) :: Nil
        case _ =>
          ReplaceTableAsSelectExec(
            catalog,
            ident,
            parts,
            query,
            planLater(query),
            props,
            writeOptions,
            orCreate = orCreate) :: Nil
      }

    case AppendData(r: DataSourceV2Relation, query, writeOptions, _) =>
      r.table.asWritable match {
        case v1 if v1.supports(TableCapability.V1_BATCH_WRITE) =>
          AppendDataExecV1(v1, writeOptions.asOptions, query) :: Nil
        case v2 =>
          AppendDataExec(v2, writeOptions.asOptions, planLater(query)) :: Nil
      }

    case OverwriteByExpression(r: DataSourceV2Relation, deleteExpr, query, writeOptions, _) =>
      // fail if any filter cannot be converted. correctness depends on removing all matching data.
      val filters = splitConjunctivePredicates(deleteExpr).map {
        filter => DataSourceStrategy.translateFilter(deleteExpr).getOrElse(
          throw new AnalysisException(s"Cannot translate expression to source filter: $filter"))
      }.toArray
      r.table.asWritable match {
        case v1 if v1.supports(TableCapability.V1_BATCH_WRITE) =>
          OverwriteByExpressionExecV1(v1, filters, writeOptions.asOptions, query) :: Nil
        case v2 =>
          OverwriteByExpressionExec(v2, filters, writeOptions.asOptions, planLater(query)) :: Nil
      }

    case OverwritePartitionsDynamic(r: DataSourceV2Relation, query, writeOptions, _) =>
      OverwritePartitionsDynamicExec(
        r.table.asWritable, writeOptions.asOptions, planLater(query)) :: Nil

    case DeleteFromTable(relation, condition) =>
      relation match {
        case DataSourceV2ScanRelation(table, _, output) =>
          if (condition.exists(SubqueryExpression.hasSubquery)) {
            throw new AnalysisException(
              s"Delete by condition with subquery is not supported: $condition")
          }
          // fail if any filter cannot be converted.
          // correctness depends on removing all matching data.
          val filters = DataSourceStrategy.normalizeExprs(condition.toSeq, output)
              .flatMap(splitConjunctivePredicates(_).map {
                f => DataSourceStrategy.translateFilter(f).getOrElse(
                  throw new AnalysisException(s"Exec update failed:" +
                      s" cannot translate expression to source filter: $f"))
              }).toArray
          DeleteFromTableExec(table.asDeletable, filters) :: Nil
        case _ =>
          throw new AnalysisException("DELETE is only supported with v2 tables.")
      }

    case WriteToContinuousDataSource(writer, query) =>
      WriteToContinuousDataSourceExec(writer, planLater(query)) :: Nil

    case Repartition(1, false, child) =>
      val isContinuous = child.find {
        case r: StreamingDataSourceV2Relation => r.stream.isInstanceOf[ContinuousStream]
        case _ => false
      }.isDefined

      if (isContinuous) {
        ContinuousCoalesceExec(1, planLater(child)) :: Nil
      } else {
        Nil
      }

    case desc @ DescribeNamespace(ResolvedNamespace(catalog, ns), extended) =>
      DescribeNamespaceExec(desc.output, catalog, ns, extended) :: Nil

    case desc @ DescribeRelation(ResolvedTable(_, _, table), partitionSpec, isExtended) =>
      if (partitionSpec.nonEmpty) {
        throw new AnalysisException("DESCRIBE does not support partition for v2 tables.")
      }
      DescribeTableExec(desc.output, table, isExtended) :: Nil

    case DropTable(catalog, ident, ifExists) =>
      DropTableExec(catalog, ident, ifExists) :: Nil

    case AlterTable(catalog, ident, _, changes) =>
      AlterTableExec(catalog, ident, changes) :: Nil

    case RenameTable(catalog, oldIdent, newIdent) =>
      RenameTableExec(catalog, oldIdent, newIdent) :: Nil

    case AlterNamespaceSetProperties(ResolvedNamespace(catalog, ns), properties) =>
      AlterNamespaceSetPropertiesExec(catalog, ns, properties) :: Nil

    case AlterNamespaceSetLocation(ResolvedNamespace(catalog, ns), location) =>
      AlterNamespaceSetPropertiesExec(
        catalog,
        ns,
        Map(SupportsNamespaces.PROP_LOCATION -> location)) :: Nil

    case CommentOnNamespace(ResolvedNamespace(catalog, ns), comment) =>
      AlterNamespaceSetPropertiesExec(
        catalog,
        ns,
        Map(SupportsNamespaces.PROP_COMMENT -> comment)) :: Nil

    case CommentOnTable(ResolvedTable(catalog, identifier, _), comment) =>
      val changes = TableChange.setProperty(TableCatalog.PROP_COMMENT, comment)
      AlterTableExec(catalog, identifier, Seq(changes)) :: Nil

    case CreateNamespace(catalog, namespace, ifNotExists, properties) =>
      CreateNamespaceExec(catalog, namespace, ifNotExists, properties) :: Nil

    case DropNamespace(ResolvedNamespace(catalog, ns), ifExists, cascade) =>
      DropNamespaceExec(catalog, ns, ifExists, cascade) :: Nil

    case r @ ShowNamespaces(ResolvedNamespace(catalog, ns), pattern) =>
      ShowNamespacesExec(r.output, catalog, ns, pattern) :: Nil

    case r @ ShowTables(ResolvedNamespace(catalog, ns), pattern) =>
      ShowTablesExec(r.output, catalog.asTableCatalog, ns, pattern) :: Nil

    case SetCatalogAndNamespace(catalogManager, catalogName, ns) =>
      SetCatalogAndNamespaceExec(catalogManager, catalogName, ns) :: Nil

    case r: ShowCurrentNamespace =>
      ShowCurrentNamespaceExec(r.output, r.catalogManager) :: Nil

    case r @ ShowTableProperties(DataSourceV2Relation(table, _, _), propertyKey) =>
      ShowTablePropertiesExec(r.output, table, propertyKey) :: Nil

    case AlterNamespaceSetOwner(ResolvedNamespace(catalog, namespace), name, typ) =>
      val properties =
        Map(SupportsNamespaces.PROP_OWNER_NAME -> name, SupportsNamespaces.PROP_OWNER_TYPE -> typ)
      AlterNamespaceSetPropertiesExec(catalog, namespace, properties) :: Nil

    case _ => Nil
  }
}
