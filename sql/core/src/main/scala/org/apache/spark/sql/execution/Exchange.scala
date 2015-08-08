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

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.rdd.RDD
import org.apache.spark.serializer.Serializer
import org.apache.spark.shuffle.hash.HashShuffleManager
import org.apache.spark.shuffle.sort.SortShuffleManager
import org.apache.spark.shuffle.unsafe.UnsafeShuffleManager
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.errors.attachTree
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.codegen.GenerateUnsafeProjection
import org.apache.spark.sql.catalyst.plans.physical._
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.util.MutablePair
import org.apache.spark.{HashPartitioner, Partitioner, RangePartitioner, SparkEnv}

/**
 * :: DeveloperApi ::
 * Performs a shuffle that will result in the desired `newPartitioning`.
 */
@DeveloperApi
case class Exchange(newPartitioning: Partitioning, child: SparkPlan) extends UnaryNode {

  override def nodeName: String = if (tungstenMode) "TungstenExchange" else "Exchange"

  /**
   * Returns true iff we can support the data type, and we are not doing range partitioning.
   */
  private lazy val tungstenMode: Boolean = {
    GenerateUnsafeProjection.canSupport(child.schema) &&
      !newPartitioning.isInstanceOf[RangePartitioning]
  }

  override def outputPartitioning: Partitioning = newPartitioning

  override def output: Seq[Attribute] = child.output

  // This setting is somewhat counterintuitive:
  // If the schema works with UnsafeRow, then we tell the planner that we don't support safe row,
  // so the planner inserts a converter to convert data into UnsafeRow if needed.
  override def outputsUnsafeRows: Boolean = tungstenMode
  override def canProcessSafeRows: Boolean = !tungstenMode
  override def canProcessUnsafeRows: Boolean = tungstenMode

  /**
   * Determines whether records must be defensively copied before being sent to the shuffle.
   * Several of Spark's shuffle components will buffer deserialized Java objects in memory. The
   * shuffle code assumes that objects are immutable and hence does not perform its own defensive
   * copying. In Spark SQL, however, operators' iterators return the same mutable `Row` object. In
   * order to properly shuffle the output of these operators, we need to perform our own copying
   * prior to sending records to the shuffle. This copying is expensive, so we try to avoid it
   * whenever possible. This method encapsulates the logic for choosing when to copy.
   *
   * In the long run, we might want to push this logic into core's shuffle APIs so that we don't
   * have to rely on knowledge of core internals here in SQL.
   *
   * See SPARK-2967, SPARK-4479, and SPARK-7375 for more discussion of this issue.
   *
   * @param partitioner the partitioner for the shuffle
   * @param serializer the serializer that will be used to write rows
   * @return true if rows should be copied before being shuffled, false otherwise
   */
  private def needToCopyObjectsBeforeShuffle(
      partitioner: Partitioner,
      serializer: Serializer): Boolean = {
    // Note: even though we only use the partitioner's `numPartitions` field, we require it to be
    // passed instead of directly passing the number of partitions in order to guard against
    // corner-cases where a partitioner constructed with `numPartitions` partitions may output
    // fewer partitions (like RangePartitioner, for example).
    val conf = child.sqlContext.sparkContext.conf
    val shuffleManager = SparkEnv.get.shuffleManager
    val sortBasedShuffleOn = shuffleManager.isInstanceOf[SortShuffleManager] ||
      shuffleManager.isInstanceOf[UnsafeShuffleManager]
    val bypassMergeThreshold = conf.getInt("spark.shuffle.sort.bypassMergeThreshold", 200)
    val serializeMapOutputs = conf.getBoolean("spark.shuffle.sort.serializeMapOutputs", true)
    if (sortBasedShuffleOn) {
      val bypassIsSupported = SparkEnv.get.shuffleManager.isInstanceOf[SortShuffleManager]
      if (bypassIsSupported && partitioner.numPartitions <= bypassMergeThreshold) {
        // If we're using the original SortShuffleManager and the number of output partitions is
        // sufficiently small, then Spark will fall back to the hash-based shuffle write path, which
        // doesn't buffer deserialized records.
        // Note that we'll have to remove this case if we fix SPARK-6026 and remove this bypass.
        false
      } else if (serializeMapOutputs && serializer.supportsRelocationOfSerializedObjects) {
        // SPARK-4550 extended sort-based shuffle to serialize individual records prior to sorting
        // them. This optimization is guarded by a feature-flag and is only applied in cases where
        // shuffle dependency does not specify an aggregator or ordering and the record serializer
        // has certain properties. If this optimization is enabled, we can safely avoid the copy.
        //
        // Exchange never configures its ShuffledRDDs with aggregators or key orderings, so we only
        // need to check whether the optimization is enabled and supported by our serializer.
        //
        // This optimization also applies to UnsafeShuffleManager (added in SPARK-7081).
        false
      } else {
        // Spark's SortShuffleManager uses `ExternalSorter` to buffer records in memory. This code
        // path is used both when SortShuffleManager is used and when UnsafeShuffleManager falls
        // back to SortShuffleManager to perform a shuffle that the new fast path can't handle. In
        // both cases, we must copy.
        true
      }
    } else if (shuffleManager.isInstanceOf[HashShuffleManager]) {
      // We're using hash-based shuffle, so we don't need to copy.
      false
    } else {
      // Catch-all case to safely handle any future ShuffleManager implementations.
      true
    }
  }

  @transient private lazy val sparkConf = child.sqlContext.sparkContext.getConf

  private val serializer: Serializer = {
    val rowDataTypes = child.output.map(_.dataType).toArray
    if (tungstenMode) {
      new UnsafeRowSerializer(child.output.size)
    } else {
      new SparkSqlSerializer(sparkConf)
    }
  }

  protected override def doExecute(): RDD[InternalRow] = attachTree(this , "execute") {
    val rdd = child.execute()
    val part: Partitioner = newPartitioning match {
      case HashPartitioning(expressions, numPartitions) => new HashPartitioner(numPartitions)
      case RangePartitioning(sortingExpressions, numPartitions) =>
        // Internally, RangePartitioner runs a job on the RDD that samples keys to compute
        // partition bounds. To get accurate samples, we need to copy the mutable keys.
        val rddForSampling = rdd.mapPartitions { iter =>
          val mutablePair = new MutablePair[InternalRow, Null]()
          iter.map(row => mutablePair.update(row.copy(), null))
        }
        // We need to use an interpreted ordering here because generated orderings cannot be
        // serialized and this ordering needs to be created on the driver in order to be passed into
        // Spark core code.
        implicit val ordering = new InterpretedOrdering(sortingExpressions, child.output)
        new RangePartitioner(numPartitions, rddForSampling, ascending = true)
      case SinglePartition =>
        new Partitioner {
          override def numPartitions: Int = 1
          override def getPartition(key: Any): Int = 0
        }
      case _ => sys.error(s"Exchange not implemented for $newPartitioning")
      // TODO: Handle BroadcastPartitioning.
    }
    def getPartitionKeyExtractor(): InternalRow => InternalRow = newPartitioning match {
      case HashPartitioning(expressions, _) => newMutableProjection(expressions, child.output)()
      case RangePartitioning(_, _) | SinglePartition => identity
      case _ => sys.error(s"Exchange not implemented for $newPartitioning")
    }
    val rddWithPartitionIds: RDD[Product2[Int, InternalRow]] = {
      if (needToCopyObjectsBeforeShuffle(part, serializer)) {
        rdd.mapPartitions { iter =>
          val getPartitionKey = getPartitionKeyExtractor()
          iter.map { row => (part.getPartition(getPartitionKey(row)), row.copy()) }
        }
      } else {
        rdd.mapPartitions { iter =>
          val getPartitionKey = getPartitionKeyExtractor()
          val mutablePair = new MutablePair[Int, InternalRow]()
          iter.map { row => mutablePair.update(part.getPartition(getPartitionKey(row)), row) }
        }
      }
    }
    new ShuffledRowRDD(rddWithPartitionIds, serializer, part.numPartitions)
  }
}

/**
 * Ensures that the [[org.apache.spark.sql.catalyst.plans.physical.Partitioning Partitioning]]
 * of input data meets the
 * [[org.apache.spark.sql.catalyst.plans.physical.Distribution Distribution]] requirements for
 * each operator by inserting [[Exchange]] Operators where required.  Also ensure that the
 * input partition ordering requirements are met.
 */
private[sql] case class EnsureRequirements(sqlContext: SQLContext) extends Rule[SparkPlan] {
  // TODO: Determine the number of partitions.
  private def numPartitions: Int = sqlContext.conf.numShufflePartitions

  /**
   * Given a required distribution, returns a partitioning that satisfies that distribution.
   */
  private def canonicalPartitioning(requiredDistribution: Distribution): Partitioning = {
    requiredDistribution match {
      case AllTuples => SinglePartition
      case ClusteredDistribution(clustering) => HashPartitioning(clustering, numPartitions)
      case OrderedDistribution(ordering) => RangePartitioning(ordering, numPartitions)
      case dist => sys.error(s"Do not know how to satisfy distribution $dist")
    }
  }

  /**
   * Return true if all of the operator's children satisfy their output distribution requirements.
   */
  private def childPartitioningsSatisfyDistributionRequirements(operator: SparkPlan): Boolean = {
    operator.children.zip(operator.requiredChildDistribution).forall {
      case (child, distribution) => child.outputPartitioning.satisfies(distribution)
    }
  }

  /**
   * Given an operator, check whether the operator requires its children to have compatible
   * output partitionings and add Exchanges to fix any detected incompatibilities.
   */
  private def ensureChildPartitioningsAreCompatible(operator: SparkPlan): SparkPlan = {
    // If an operator has multiple children and the operator requires a specific child output
    // distribution then we need to ensure that all children have compatible output partitionings.
    if (operator.children.length > 1
        && operator.requiredChildDistribution.toSet != Set(UnspecifiedDistribution)) {
      if (!Partitioning.allCompatible(operator.children.map(_.outputPartitioning))) {
        val newChildren = operator.children.zip(operator.requiredChildDistribution).map {
          case (child, requiredDistribution) =>
            val targetPartitioning = canonicalPartitioning(requiredDistribution)
            if (child.outputPartitioning.compatibleWith(targetPartitioning)) {
              child
            } else {
              Exchange(targetPartitioning, child)
            }
        }
        val newOperator = operator.withNewChildren(newChildren)
        assert(childPartitioningsSatisfyDistributionRequirements(newOperator))
        newOperator
      } else {
        operator
      }
    } else {
      operator
    }
  }

  private def ensureDistributionAndOrdering(operator: SparkPlan): SparkPlan = {

    def addShuffleIfNecessary(child: SparkPlan, requiredDistribution: Distribution): SparkPlan = {
      // A pre-condition of ensureDistributionAndOrdering is that joins' children have compatible
      // partitionings. Thus, we only need to check whether the output partitionings satisfy
      // the required distribution. In the case where the children are all compatible, then they
      // will either all satisfy the required distribution or will all fail to satisfy it, since
      // A.guarantees(B) implies that A and B satisfy the same set of distributions.
      // Therefore, if all children are compatible then either all or none of them will shuffled to
      // ensure that the distribution requirements are met.
      //
      // Note that this reasoning implicitly assumes that operators which require compatible
      // child partitionings have equivalent required distributions for those children.
      if (child.outputPartitioning.satisfies(requiredDistribution)) {
        child
      } else {
        Exchange(canonicalPartitioning(requiredDistribution), child)
      }
    }

    def addSortIfNecessary(child: SparkPlan, requiredOrdering: Seq[SortOrder]): SparkPlan = {
      if (requiredOrdering.nonEmpty) {
        // If child.outputOrdering is [a, b] and requiredOrdering is [a], we do not need to sort.
        val minSize = Seq(requiredOrdering.size, child.outputOrdering.size).min
        if (minSize == 0 || requiredOrdering.take(minSize) != child.outputOrdering.take(minSize)) {
          sqlContext.planner.BasicOperators.getSortOperator(requiredOrdering, global = false, child)
        } else {
          child
        }
      } else {
        child
      }
    }

    val children = operator.children
    val requiredChildDistribution = operator.requiredChildDistribution
    val requiredChildOrdering = operator.requiredChildOrdering
    assert(children.length == requiredChildDistribution.length)
    assert(children.length == requiredChildOrdering.length)
    val newChildren = (children, requiredChildDistribution, requiredChildOrdering).zipped.map {
      case (child, requiredDistribution, requiredOrdering) =>
        addSortIfNecessary(addShuffleIfNecessary(child, requiredDistribution), requiredOrdering)
    }
    operator.withNewChildren(newChildren)
  }

  def apply(plan: SparkPlan): SparkPlan = plan.transformUp {
    case operator: SparkPlan =>
      ensureDistributionAndOrdering(ensureChildPartitioningsAreCompatible(operator))
  }
}
