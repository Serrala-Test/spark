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

import com.google.common.base.Objects

import org.apache.spark.SparkException
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.QueryPlan
import org.apache.spark.sql.catalyst.plans.physical.{KeyGroupedPartitioning, Partitioning, SinglePartition}
import org.apache.spark.sql.catalyst.util.{truncatedString, InternalRowComparableWrapper}
import org.apache.spark.sql.connector.catalog.Table
import org.apache.spark.sql.connector.read._
import org.apache.spark.sql.execution.joins.ProxyBroadcastVarAndStageIdentifier



/**
 * Physical plan node for scanning a batch of data from a data source v2.
 */
case class BatchScanExec(
    output: Seq[AttributeReference],
    @transient scan: Scan,
    runtimeFilters: Seq[Expression],
    ordering: Option[Seq[SortOrder]] = None,
    @transient table: Table,
    spjParams: StoragePartitionJoinParams = StoragePartitionJoinParams(),
    @transient proxyForPushedBroadcastVar: Option[Seq[ProxyBroadcastVarAndStageIdentifier]] = None
  ) extends DataSourceV2ScanExecBase {

  @transient
  @volatile private var _batch: Batch = _

  @transient
  @volatile private var _inputPartitions: Seq[InputPartition] = _

  @transient
  @volatile private var _readerFactory: PartitionReaderFactory = _

  private def getOrCreate[T](createAndInit: () => T, nullChecker: () => Option[T]): T =
    nullChecker().getOrElse(createAndInit())


  def getBatch: Batch = getOrCreate(() => {
    val temp = this.scan.toBatch
    this._batch = temp
    temp
  }, () => Option(this._batch))


  override def inputPartitions: Seq[InputPartition] = getOrCreate(() => {
    val temp = this.getBatch.planInputPartitions().to[Seq]
    this._inputPartitions = temp
    temp
  }, () => Option(this._inputPartitions))

  override def readerFactory: PartitionReaderFactory = getOrCreate(() => {
    val temp = this.getBatch.createReaderFactory()
    this._readerFactory = temp
    temp
  }, () => Option(this._readerFactory))


  @transient @volatile private var filteredPartitions: Seq[Seq[InputPartition]] = _
  @transient @volatile private var inputRDDCached: RDD[InternalRow] = _

  // TODO: unify the equal/hashCode implementation for all data source v2 query plans.
  override def equals(other: Any): Boolean = other match {
    case other: BatchScanExec =>
      val commonEquality = this.runtimeFilters == other.runtimeFilters &&
        this.proxyForPushedBroadcastVar == other.proxyForPushedBroadcastVar &&
        this.spjParams == other.spjParams
      if (commonEquality) {
        (this.scan, other.scan) match {
          case (sr1: SupportsRuntimeV2Filtering, sr2: SupportsRuntimeV2Filtering) =>
            sr1.equalToIgnoreRuntimeFilters(sr2)

          case _ if this.getBatch != null => this.getBatch == other.getBatch
        }
      } else {
        false
      }

    case _ => false
  }

  override def hashCode(): Int = {
    val batchHashCode = scan match {
      case sr: SupportsRuntimeV2Filtering => sr.hashCodeIgnoreRuntimeFilters()

      case _ => this.getBatch.hashCode()
    }
    Objects.hashCode(Integer.valueOf(batchHashCode), runtimeFilters,
      this.proxyForPushedBroadcastVar)
  }

  private def initFilteredPartitions(): Unit = {
    val dataSourceFilters = runtimeFilters.flatMap {
      case DynamicPruningExpression(e) => DataSourceV2Strategy.translateRuntimeFilterV2(e)
      case _ => None
    }

    val pushFiltersAndRefreshIter = dataSourceFilters.nonEmpty ||
      (scan.isInstanceOf[SupportsRuntimeV2Filtering] &&
        scan.asInstanceOf[SupportsRuntimeV2Filtering].hasPushedBroadCastFilter)

    if (pushFiltersAndRefreshIter) {
      val originalPartitioning = outputPartitioning
      // the cast is safe as runtime filters are only assigned if the scan can be filtered
      val filterableScan = scan.asInstanceOf[SupportsRuntimeV2Filtering]

      if (dataSourceFilters.nonEmpty) {
        filterableScan.filter(dataSourceFilters.toArray)
      }
      filterableScan.callbackBeforeOpeningIterator()
      // call toBatch again to get filtered partitions
      val newPartitions = scan.toBatch.planInputPartitions()

      val newGroupedPartitions = originalPartitioning match {
        case p: KeyGroupedPartitioning =>
          if (newPartitions.exists(!_.isInstanceOf[HasPartitionKey])) {
            throw new SparkException("Data source must have preserved the original partitioning " +
              "during runtime filtering: not all partitions implement HasPartitionKey after " +
              "filtering")
          }
          val newPartitionValues = newPartitions.map(partition =>
            InternalRowComparableWrapper(partition.asInstanceOf[HasPartitionKey], p.expressions))
            .toSet
          val oldPartitionValues = p.partitionValues
            .map(partition => InternalRowComparableWrapper(partition, p.expressions)).toSet
          // We require the new number of partition values to be equal or less than the old number
          // of partition values here. In the case of less than, empty partitions will be added for
          // those missing values that are not present in the new input partitions.
          if (oldPartitionValues.size < newPartitionValues.size) {
            throw new SparkException("During runtime filtering, data source must either report " +
              "the same number of partition values, or a subset of partition values from the " +
              s"original. Before: ${oldPartitionValues.size} partition values. " +
              s"After: ${newPartitionValues.size} partition values")
          }

          if (!newPartitionValues.forall(oldPartitionValues.contains)) {
            throw new SparkException("During runtime filtering, data source must not report new " +
              "partition values that are not present in the original partitioning.")
          }

          groupPartitions(newPartitions.to[Seq]).get.groupedParts.map(_.parts)

        case _ =>
          // no validation is needed as the data source did not report any specific partitioning
          newPartitions.map(Seq(_)).toSeq
      }
      this.filteredPartitions = newGroupedPartitions.to[Seq]
    } else {
      this.filteredPartitions = partitions
    }
  }


  override def outputPartitioning: Partitioning = {
    super.outputPartitioning match {
      case k: KeyGroupedPartitioning if spjParams.commonPartitionValues.isDefined =>
        // We allow duplicated partition values if
        // `spark.sql.sources.v2.bucketing.partiallyClusteredDistribution.enabled` is true
        val newPartValues = spjParams.commonPartitionValues.get.flatMap {
          case (partValue, numSplits) => Seq.fill(numSplits)(partValue)
        }
        k.copy(numPartitions = newPartValues.length, partitionValues = newPartValues)
      case p => p
    }
  }


  override def inputRDD: RDD[InternalRow] = {
    var local = inputRDDCached
    if (local eq null) {
      local = this.synchronized {
        if (inputRDDCached eq null) {
          if (this.filteredPartitions eq null) {
            this.initFilteredPartitions()
          }
          this.inputRDDCached = if (filteredPartitions.isEmpty &&
            outputPartitioning == SinglePartition) {
            // return an empty RDD with 1 partition if dynamic filtering removed the only split
            sparkContext.parallelize(Array.empty[InternalRow].to[Seq], 1)
          } else {
            var finalPartitions = filteredPartitions

            outputPartitioning match {
              case p: KeyGroupedPartitioning =>
                val groupedPartitions = filteredPartitions.map(splits => {
                  assert(splits.nonEmpty && splits.head.isInstanceOf[HasPartitionKey])
                  (splits.head.asInstanceOf[HasPartitionKey].partitionKey(), splits)
                })

                // When partially clustered, the input partitions are not grouped by partition
                // values. Here we'll need to check `commonPartitionValues` and decide how to group
                // and replicate splits within a partition.
                if (spjParams.commonPartitionValues.isDefined &&
                  spjParams.applyPartialClustering) {
                  // A mapping from the common partition values to how many splits the partition
                  // should contain.
                  val commonPartValuesMap = spjParams.commonPartitionValues
                    .get
                    .map(t => (InternalRowComparableWrapper(t._1, p.expressions), t._2))
                    .toMap
                  val nestGroupedPartitions = groupedPartitions.map { case (partValue, splits) =>
                    // `commonPartValuesMap` should contain the part value
                    // since it's the super set.
                    val numSplits = commonPartValuesMap
                      .get(InternalRowComparableWrapper(partValue, p.expressions))
                    assert(numSplits.isDefined, s"Partition value $partValue does not exist in " +
                      "common partition values from Spark plan")

                    val newSplits = if (spjParams.replicatePartitions) {
                      // We need to also replicate partitions according to the other side of join
                      Seq.fill(numSplits.get)(splits)
                    } else {
                      // Not grouping by partition values: this could be the side with partially
                      // clustered distribution. Because of dynamic filtering, we'll need to check
                      // if the final number of splits of a partition is smaller than the original
                      // number, and fill with empty splits if so. This is necessary so that both
                      // sides of a join will have the same number of partitions & splits.
                      splits.map(Seq(_)).padTo(numSplits.get, Seq.empty)
                    }
                    (InternalRowComparableWrapper(partValue, p.expressions), newSplits)
                  }

                  // Now fill missing partition keys with empty partitions
                  val partitionMapping = nestGroupedPartitions.toMap
                  finalPartitions = spjParams.commonPartitionValues.get.flatMap {
                    case (partValue, numSplits) =>
                      // Use empty partition for those partition values that are not present.
                      partitionMapping.getOrElse(
                        InternalRowComparableWrapper(partValue, p.expressions),
                        Seq.fill(numSplits)(Seq.empty))
                  }
                } else {
                  // either `commonPartitionValues` is not defined, or it is defined but
                  // `applyPartialClustering` is false.
                  val partitionMapping = groupedPartitions.map { case (partValue, splits) =>
                    InternalRowComparableWrapper(partValue, p.expressions) -> splits
                  }.toMap

                  // In case `commonPartitionValues` is not defined (e.g., SPJ is not used), there
                  // could exist duplicated partition values, as partition grouping is not done
                  // at the beginning and postponed to this method. It is important to use unique
                  // partition values here so that grouped partitions won't get duplicated.
                  finalPartitions = p.uniquePartitionValues.map { partValue =>
                    // Use empty partition for those partition values that are not present
                    partitionMapping.getOrElse(
                      InternalRowComparableWrapper(partValue, p.expressions), Seq.empty)
                  }
                }

              case _ =>
            }

            new DataSourceRDD(
              sparkContext, finalPartitions, readerFactory, supportsColumnar, customMetrics)
          }
          postDriverMetrics()
          this.inputRDDCached
        } else {
          inputRDDCached
        }
      }
    }
    local
  }

  override def keyGroupedPartitioning: Option[Seq[Expression]] =
    spjParams.keyGroupedPartitioning

  override def doCanonicalize(): BatchScanExec = {
    this.copy(
      proxyForPushedBroadcastVar = proxyForPushedBroadcastVar.map(_.map(_.canonicalized)),
      output = output.map(QueryPlan.normalizeExpressions(_, output)),
      runtimeFilters = QueryPlan.normalizePredicates(
        runtimeFilters.filterNot(_ == DynamicPruningExpression(Literal.TrueLiteral)),
        output))
  }

  override def simpleString(maxFields: Int): String = {
    val truncatedOutputString = truncatedString(output, "[", ", ", "]", maxFields)
    val runtimeFiltersString = s"RuntimeFilters: ${runtimeFilters.mkString("[", ",", "]")}"
    val broadcastVarFiltersString = this.proxyForPushedBroadcastVar.fold("")(proxies =>
      proxies.map(proxy => {
        val joinKeysStr = proxy.joiningKeysData.map(jkd => s"build side join" +
          s" key index= ${jkd.joinKeyIndexInJoiningKeys} and stream side join key index at leaf =" +
          s" ${jkd.streamsideLeafJoinAttribIndex}").mkString(";")
        s"for this buildleg : join col and streaming col = $joinKeysStr"
      }).mkString("\n"))
    val result = s"$nodeName$truncatedOutputString ${scan.description()} $runtimeFiltersString" +
      s" $broadcastVarFiltersString"
    redact(result)
  }

  override def nodeName: String = {
    s"BatchScan ${table.name()}".trim
  }

  def resetFilteredPartitionsAndInputRdd(): Unit = {
    this.filteredPartitions = null
    this.inputRDDCached = null
    this._inputPartitions = null
    this._batch = null
    this._readerFactory = null
  }
}

case class StoragePartitionJoinParams(
    keyGroupedPartitioning: Option[Seq[Expression]] = None,
    commonPartitionValues: Option[Seq[(InternalRow, Int)]] = None,
    applyPartialClustering: Boolean = false,
    replicatePartitions: Boolean = false) {
  override def equals(other: Any): Boolean = other match {
    case other: StoragePartitionJoinParams =>
      this.commonPartitionValues == other.commonPartitionValues &&
      this.replicatePartitions == other.replicatePartitions &&
      this.applyPartialClustering == other.applyPartialClustering
    case _ =>
      false
  }

  override def hashCode(): Int = Objects.hashCode(
    commonPartitionValues: Option[Seq[(InternalRow, Int)]],
    applyPartialClustering: java.lang.Boolean,
    replicatePartitions: java.lang.Boolean)
}

