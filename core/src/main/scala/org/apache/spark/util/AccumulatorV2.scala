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

package org.apache.spark.util

import java.{lang => jl}
import java.io.ObjectInputStream
import java.util.{ArrayList, Collections}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

import scala.collection.mutable
import scala.collection.JavaConverters._

import org.apache.spark._
import org.apache.spark.annotation.Experimental
import org.apache.spark.scheduler.AccumulableInfo

/**
 * Metadata for the Accumulator
 *
 *
 * @param countFailedValues whether to accumulate values from failed tasks. This is set to true
 *                          for system and time metrics like serialization time or bytes spilled,
 *                          and false for things with absolute values like number of input rows.
 *                          This should be used for internal metrics only.

 * @param dataProperty Data property accumulators will only have values added once for each
 *                     RDD/Partition/Shuffle combination. This prevents double counting on
 *                     reevaluation. Partial evaluation of a partition will not increment a data
 *                     property accumulator. Data property accumulators are currently experimental
 *                     and the behaviour may change in future versions. Data Propert accumulators
 *                     are not currently supported in Datasets.
 *
 */
private[spark] case class AccumulatorMetadata(
    id: Long,
    name: Option[String],
    countFailedValues: Boolean,
    dataProperty: Boolean) extends Serializable


/**
 * The legacy base class for accumulators, that can accumulate inputs of type `IN`, and produce
 * output of type `OUT`.
 *
 * `OUT` should be a type that can be read atomically (e.g., Int, Long), or thread-safely
 * (e.g., synchronized collections) because it will be read from other threads.
 */
abstract class AccumulatorV2[IN, OUT] extends Serializable {
  private[spark] var metadata: AccumulatorMetadata = _
  private[spark] var atDriverSide = true

  private[spark] def dataProperty: Boolean = metadata.dataProperty

  private[spark] def register(
      sc: SparkContext,
      name: Option[String] = None,
      countFailedValues: Boolean = false,
      dataProperty: Boolean = false): Unit = {
    if (this.metadata != null) {
      throw new IllegalStateException("Cannot register an Accumulator twice.")
    }
    this.metadata = AccumulatorMetadata(AccumulatorContext.newId(), name, countFailedValues,
      dataProperty)
    AccumulatorContext.register(this)
    sc.cleaner.foreach(_.registerAccumulatorForCleanup(this))
  }

  /**
   * Returns true if this accumulator has been registered.  Note that all accumulators must be
   * registered before use, or it will throw exception.
   */
  final def isRegistered: Boolean =
    metadata != null && AccumulatorContext.get(metadata.id).isDefined

  private def assertMetadataNotNull(): Unit = {
    if (metadata == null) {
      throw new IllegalAccessError("The metadata of this accumulator has not been assigned yet.")
    }
  }

  /**
   * Returns the id of this accumulator, can only be called after registration.
   */
  final def id: Long = {
    assertMetadataNotNull()
    metadata.id
  }

  /**
   * Returns the name of this accumulator, can only be called after registration.
   */
  final def name: Option[String] = {
    assertMetadataNotNull()
    metadata.name
  }

  /**
   * Whether to accumulate values from failed tasks. This is set to true for system and time
   * metrics like serialization time or bytes spilled, and false for things with absolute values
   * like number of input rows.  This should be used for internal metrics only.
   */
  private[spark] final def countFailedValues: Boolean = {
    assertMetadataNotNull()
    metadata.countFailedValues
  }

  /**
   * Creates an [[AccumulableInfo]] representation of this [[AccumulatorV2]] with the provided
   * values.
   */
  private[spark] def toInfo(update: Option[Any], value: Option[Any]): AccumulableInfo = {
    val isInternal = name.exists(_.startsWith(InternalAccumulator.METRICS_PREFIX))
    new AccumulableInfo(id, name, update, value, isInternal, countFailedValues, dataProperty)
  }

  final private[spark] def isAtDriverSide: Boolean = atDriverSide

  /**
   * Returns if this accumulator is zero value or not. e.g. for a counter accumulator, 0 is zero
   * value; for a list accumulator, Nil is zero value.
   */
  def isZero: Boolean

  /**
   * Creates a new copy of this accumulator, which is zero value. i.e. call `isZero` on the copy
   * must return true.
   */
  def copyAndReset(): AccumulatorV2[IN, OUT] = {
    val copyAcc = copy()
    copyAcc.reset()
    copyAcc
  }

  /**
   * Creates a new copy of this accumulator.
   */
  def copy(): AccumulatorV2[IN, OUT]

  /**
   * Resets this accumulator, which is zero value. i.e. call `isZero` must
   * return true.
   */
  def reset(): Unit

  /**
   * Takes the inputs and accumulates. e.g. it can be a simple `+=` for counter accumulator.
   * Developers should extend this to customize the adding functionality.
   */
  def add(v: IN): Unit

  /**
   * Merges another same-type accumulator into this one and update its state, i.e. this should be
   * merge-in-place. This should not be called directly outside of Spark.
   */
  def merge(other: AccumulatorV2[IN, OUT]): Unit

  /**
   * Defines the current value of this accumulator
   */
  def value: OUT

  // Called by Java when serializing an object
  final protected def writeReplace(): Any = {
    if (atDriverSide) {
      if (!isRegistered) {
        throw new UnsupportedOperationException(
          "Accumulator must be registered before send to executor")
      }
      val copyAcc = copyAndReset()
      assert(copyAcc.isZero, "copyAndReset must return a zero value copy")
      copyAcc.metadata = metadata
      copyAcc
    } else {
      this
    }
  }

  // Called by Java when deserializing an object
  private def readObject(in: ObjectInputStream): Unit = Utils.tryOrIOException {
    in.defaultReadObject()
    if (atDriverSide) {
      atDriverSide = false

      // Automatically register the accumulator when it is deserialized with the task closure.
      // This is for external accumulators and internal ones that do not represent task level
      // metrics, e.g. internal SQL metrics, which are per-operator.
      val taskContext = TaskContext.get()
      if (taskContext != null) {
        taskContext.registerAccumulator(this)
      }
    } else {
      atDriverSide = true
    }
  }

  override def toString: String = {
    if (metadata == null) {
      "Un-registered Accumulator: " + getClass.getSimpleName
    } else {
      getClass.getSimpleName + s"(id: $id, name: $name, value: $value)"
    }
  }

  /**
   * Mark a specific rdd/shuffle/partition as completely processed. This is a noop for
   * non-data property accumuables. See [[TaskOutputId]] for an explanation of why this is
   * important for data property accumulators.
   */
  private[spark] def markFullyProcessed(taskOutputId: TaskOutputId): Unit = {
  }
}


/**
 * The base class for accumulators, that can accumulate inputs of type `IN`, and produce output
 *  of type `OUT`. [[DataAccumulatorV2]] extends [[AccumulatorV2]] to add support for data property
 * accumulators. Note that [[DataAccumulatorV2]] can be used for both data-property and "regular"
 * accumulation tasks depending on the accumulator metadata data property flag.
 *
 * `OUT` should be a type that can be read atomically (e.g., Int, Long), or thread-safely
 * (e.g., synchronized collections) because it will be read from other threads.
 */
@Experimental
abstract class DataAccumulatorV2[IN, OUT] extends AccumulatorV2[IN, OUT] {

  /**
   * The following values are used for data property [[AccumulatorV2]]s.
   * Data property [[AccumulatorV2]]s have only-once semantics. These semantics are implemented
   * by keeping track of which RDD id, shuffle id, and partition id the current function is
   * processing in. The driver keeps track of which rdd/shuffle/partitions
   * already have been applied, and only combines values into value_ if the rdd/shuffle/partition
   * has not already been aggregated on the driver program
   */
  // For data property accumulators pending and processed updates.
  // PendingAccumulatorUpdatesForTask and CompletedOutputsForTask are keyed by TaskOutputId
  private[spark] lazy val pendingAccumulatorUpdatesForTask =
    new mutable.HashMap[TaskOutputId, DataAccumulatorV2[IN, OUT]]()
  // CompletedOutputsForTask contains the set of TaskOutputId that have been
  // fully processed on the worker side. This is used to determine if the updates should
  // be merged on the driver for a particular rdd/shuffle/partition combination.
  // Some elements may be in pendingAccumulatorUpdatesForTask which are not completed if a
  // partition is only partially evaluated (e.g. `take(x)`).
  private[spark] lazy val completedOutputsForTask = new mutable.HashSet[TaskOutputId]()
  // rddProcessed is keyed by rdd id and the value is a bitset containing all partitions
  // for the given key which have been merged into the value. This is used on the driver.
  @transient private[spark] lazy val rddProcessed = new mutable.HashMap[Int, mutable.BitSet]()
  // shuffleProcessed is the same as rddProcessed except keyed by shuffle id.
  @transient private[spark] lazy val shuffleProcessed = new mutable.HashMap[Int, mutable.BitSet]()

  /**
   * Creates a new copy of this accumulator, which is zero value. i.e. call `isZero` on the copy
   * must return true.
   */
  override def copyAndReset(): DataAccumulatorV2[IN, OUT] = {
    val copyAcc = copy()
    copyAcc.reset()
    copyAcc
  }

  /**
   * Creates a new copy of this accumulator.
   */
  override def copy(): DataAccumulatorV2[IN, OUT]

  /**
   * Takes the inputs and accumulates. e.g. it can be a simple `+=` for counter accumulator.
   * Developers should extend `addImpl` to customize the adding functionality.
   * If you overload `add` directly you must ensure you dispatch to correct add implenentation (e.g.
   * dataPropertyAdd or addImpl).
   */
  override def add(v: IN): Unit = {
    if (metadata != null && metadata.dataProperty) {
      dataPropertyAdd(v)
    } else {
      addImpl(v)
    }
  }

  protected final def dataPropertyAdd(v: IN): Unit = {
    // To allow the user to be able to access the current accumulated value from their process
    // worker side then we need to perform a "normal" add as well as the data property add.
    addImpl(v)
    // Add to the pending updates for data property
    val taskOutputInfo = TaskContext.get().getTaskOutputInfo()
    val updateForTask = pendingAccumulatorUpdatesForTask.getOrElse(taskOutputInfo, copyAndReset())
    // Since we may have constructed a new accumulator, set atDriverSide to false as the default
    // new accumulators will have atDriverSide equal to true.
    updateForTask.atDriverSide = false
    updateForTask.addImpl(v)
    pendingAccumulatorUpdatesForTask(taskOutputInfo) = updateForTask
  }

  /**
   * Mark a specific rdd/shuffle/partition as completely processed. This is a noop for
   * non-data property accumuables. See [[TaskOutputId]] for an explanation of why this is
   * important for data property accumulators.
   */
  private[spark] override def markFullyProcessed(taskOutputId: TaskOutputId): Unit = {
    if (metadata.dataProperty) {
      completedOutputsForTask += taskOutputId
    }
  }

  /**
   * Takes the inputs and accumulates. e.g. it can be a simple `+=` for counter accumulator.
   * Developers should extend this to customize the adding functionality.
   */
  protected def addImpl(v: IN)

  /**
   * Merges another same-type accumulator into this one and update its state, i.e. this should be
   * merge-in-place. Developers should extend `mergeImpl` to customize the merge functionality.
   * When merging data property accumulators, the merge function must always be called on the local
   * (that is created driver side) accumulator with the accumulator passed in being created on the
   * workers.
   *
   * If you override merge you must ensure you dispatch to the correct merge function (e.g.
   * `dataPropertyMerge` or `mergeImpl`).
   */
  override def merge(other: AccumulatorV2[IN, OUT]): Unit = {
    assert(isAtDriverSide)
    // Handle data property accumulators
    if (metadata != null && metadata.dataProperty) {
      dataPropertyMerge(other)
    } else {
      mergeImpl(other)
    }
  }

  /**
   * Merges another same-type accumulator into this one and update its state, i.e. this should be
   * merge-in-place. Developers should extend this to customize the merge functionality.
   */
  protected def mergeImpl(other: AccumulatorV2[IN, OUT]): Unit


  /**
   * `dataPropertyMerge` must only be called on an accumulator created on the driver side, and
   * `other` must be an accumulator created on a worker.
   */
  final private[spark] def dataPropertyMerge(other: AccumulatorV2[IN, OUT]) = {
    val otherDataProperty = other.asInstanceOf[DataAccumulatorV2[IN, OUT]]
    def processAccumUpdate(
      processed: mutable.HashMap[Int, mutable.BitSet],
      outputId: TaskOutputId,
      accumUpdate: AccumulatorV2[IN, OUT]
    ): Unit = {
      val partitionsAlreadyMerged = processed.getOrElseUpdate(outputId.id, new mutable.BitSet())
      // Only take updates for task outputs which were completed (e.g. skip partial evaluations)
      if (otherDataProperty.completedOutputsForTask.contains(outputId)) {
        // Only take updates for task outputs we haven't seen before.
        // So if this task computed two rdds, but one of them had been computed previously, only
        // take the accumulator updates from the other one.
        if (!partitionsAlreadyMerged.contains(outputId.partition)) {
          partitionsAlreadyMerged += outputId.partition
          mergeImpl(accumUpdate)
        }
      }
    }
    otherDataProperty.pendingAccumulatorUpdatesForTask.foreach {
      // Apply all foreach partitions regardless - they can only be fully evaluated
      case (ForeachOutputId, v) =>
        mergeImpl(v)
      // For RDDs & shuffles, apply the accumulator updates as long as the output is complete
      // and its the first time we're seeing it (just slightly different bookkeeping between
      // RDDs and shuffles).
      case (outputId: RDDOutputId, v) =>
        processAccumUpdate(rddProcessed, outputId, v)
      case (outputId: ShuffleMapOutputId, v) =>
        processAccumUpdate(shuffleProcessed, outputId, v)
    }
 }
}

/**
 * An internal class used to track accumulators by Spark itself.
 */
private[spark] object AccumulatorContext {

  /**
   * This global map holds the original accumulator objects that are created on the driver.
   * It keeps weak references to these objects so that accumulators can be garbage-collected
   * once the RDDs and user-code that reference them are cleaned up.
   * TODO: Don't use a global map; these should be tied to a SparkContext (SPARK-13051).
   */
  private val originals = new ConcurrentHashMap[Long, jl.ref.WeakReference[AccumulatorV2[_, _]]]

  private[this] val nextId = new AtomicLong(0L)

  /**
   * Returns a globally unique ID for a new [[AccumulatorV2]].
   * Note: Once you copy the [[AccumulatorV2]] the ID is no longer unique.
   */
  def newId(): Long = nextId.getAndIncrement

  /** Returns the number of accumulators registered. Used in testing. */
  def numAccums: Int = originals.size

  /**
   * Registers an [[AccumulatorV2]] created on the driver such that it can be used on the executors.
   *
   * All accumulators registered here can later be used as a container for accumulating partial
   * values across multiple tasks. This is what [[org.apache.spark.scheduler.DAGScheduler]] does.
   * Note: if an accumulator is registered here, it should also be registered with the active
   * context cleaner for cleanup so as to avoid memory leaks.
   *
   * If an [[AccumulatorV2]] with the same ID was already registered, this does nothing instead
   * of overwriting it. We will never register same accumulator twice, this is just a sanity check.
   */
  def register(a: AccumulatorV2[_, _]): Unit = {
    originals.putIfAbsent(a.id, new jl.ref.WeakReference[AccumulatorV2[_, _]](a))
  }

  /**
   * Unregisters the [[AccumulatorV2]] with the given ID, if any.
   */
  def remove(id: Long): Unit = {
    originals.remove(id)
  }

  /**
   * Returns the [[AccumulatorV2]] registered with the given ID, if any.
   */
  def get(id: Long): Option[AccumulatorV2[_, _]] = {
    Option(originals.get(id)).map { ref =>
      // Since we are storing weak references, we must check whether the underlying data is valid.
      val acc = ref.get
      if (acc eq null) {
        throw new IllegalAccessError(s"Attempted to access garbage collected accumulator $id")
      }
      acc
    }
  }

  /**
   * Clears all registered [[AccumulatorV2]]s. For testing only.
   */
  def clear(): Unit = {
    originals.clear()
  }

  /**
   * Looks for a registered accumulator by accumulator name.
   */
  private[spark] def lookForAccumulatorByName(name: String): Option[AccumulatorV2[_, _]] = {
    originals.values().asScala.find { ref =>
      val acc = ref.get
      acc != null && acc.name.isDefined && acc.name.get == name
    }.map(_.get)
  }

  // Identifier for distinguishing SQL metrics from other accumulators
  private[spark] val SQL_ACCUM_IDENTIFIER = "sql"
}

/**
 * An [[AccumulatorV2 accumulator]] for computing sum, count, and averages for 64-bit integers.
 *
 * @since 2.0.0
 */
class LongAccumulator extends DataAccumulatorV2[jl.Long, jl.Long] {
  private var _sum = 0L
  private var _count = 0L

  /**
   * Adds v to the accumulator, i.e. increment sum by v and count by 1.
   * @since 2.0.0
   */
  override def isZero: Boolean = _sum == 0L && _count == 0

  override def copy(): LongAccumulator = {
    val newAcc = new LongAccumulator
    newAcc._count = this._count
    newAcc._sum = this._sum
    newAcc
  }

  override def reset(): Unit = {
    _sum = 0L
    _count = 0L
  }

  /**
   * Adds v to the accumulator, i.e. increment sum by v and count by 1.
   * Added for simplicity with adding non java Longs & boxing.
   * @since 2.1.0
   */
  def add(v: Long): Unit = {
    // Note: This is based on the add method in [[AccumulatorV2]] but copied to avoid boxing.
    if (metadata != null && metadata.dataProperty) {
      dataPropertyAdd(v: jl.Long)
    } else {
      addImpl(v)
    }
  }

  /**
   * Adds v to the accumulator, i.e. increment sum by v and count by 1.
   * Added for binary compatability.
   * @since 2.1.0
   */
  override def add(v: jl.Long): Unit = {
    super.add(v)
  }

  /**
   * Adds v to the accumulator, i.e. increment sum by v and count by 1.
   * Added for boxing.
   * @since 2.1.0
   */
  def addImpl(v: Long): Unit = {
    _sum += v
    _count += 1
  }

  /**
   * Internally Adds v to the accumulator, i.e. increment sum by v and count by 1.
   * @since 2.0.0
   */
  override def addImpl(v: jl.Long): Unit = {
    _sum += v
    _count += 1
  }

  /**
   * Returns the number of elements added to the accumulator.
   * @since 2.0.0
   */
  def count: Long = _count

  /**
   * Returns the sum of elements added to the accumulator.
   * @since 2.0.0
   */
  def sum: Long = _sum

  /**
   * Returns the average of elements added to the accumulator.
   * @since 2.0.0
   */
  def avg: Double = _sum.toDouble / _count

  override def mergeImpl(other: AccumulatorV2[jl.Long, jl.Long]): Unit = other match {
    case o: LongAccumulator =>
      _sum += o.sum
      _count += o.count
    case _ =>
      throw new UnsupportedOperationException(
        s"Cannot merge ${this.getClass.getName} with ${other.getClass.getName}")
  }

  private[spark] def setValue(newValue: Long): Unit = _sum = newValue

  override def value: jl.Long = _sum
}


/**
 * An [[AccumulatorV2 accumulator]] for computing sum, count, and averages for double precision
 * floating numbers.
 *
 * @since 2.0.0
 */
class DoubleAccumulator extends DataAccumulatorV2[jl.Double, jl.Double] {
  private var _sum = 0.0
  private var _count = 0L

  override def isZero: Boolean = _sum == 0.0 && _count == 0

  override def copy(): DoubleAccumulator = {
    val newAcc = new DoubleAccumulator
    newAcc._count = this._count
    newAcc._sum = this._sum
    newAcc
  }

  override def reset(): Unit = {
    _sum = 0.0
    _count = 0L
  }

  /**
   * Adds v to the accumulator, i.e. increment sum by v and count by 1.
   * Added for boxing.
   * @since 2.1.0
   */
  def add(v: Double): Unit = {
    // Note: This is based on the add method in [[AccumulatorV2]] but copied to avoid boxing.
    if (metadata != null && metadata.dataProperty) {
      dataPropertyAdd(v: jl.Double)
    } else {
      addImpl(v)
    }
  }

  /**
   * Adds v to the accumulator, i.e. increment sum by v and count by 1.
   * Added for boxing.
   * @since 2.1.0
   */
  def addImpl(v: Double): Unit = {
    _sum += v
    _count += 1
  }

  /**
   * Adds v to the accumulator, i.e. increment sum by v and count by 1.
   * Added for binary compatability.
   * @since 2.1.0
   */
  override def add(v: jl.Double): Unit = {
    super.add(v)
  }

  /**
   * Adds v to the accumulator, i.e. increment sum by v and count by 1.
   * @since 2.0.0
   */
  override def addImpl(v: jl.Double): Unit = {
    _sum += v
    _count += 1
  }

  /**
   * Returns the number of elements added to the accumulator.
   * @since 2.0.0
   */
  def count: Long = _count

  /**
   * Returns the sum of elements added to the accumulator.
   * @since 2.0.0
   */
  def sum: Double = _sum

  /**
   * Returns the average of elements added to the accumulator.
   * @since 2.0.0
   */
  def avg: Double = _sum / _count

  override def mergeImpl(other: AccumulatorV2[jl.Double, jl.Double]): Unit = other match {
    case o: DoubleAccumulator =>
      _sum += o.sum
      _count += o.count
    case _ =>
      throw new UnsupportedOperationException(
        s"Cannot merge ${this.getClass.getName} with ${other.getClass.getName}")
  }

  private[spark] def setValue(newValue: Double): Unit = _sum = newValue

  override def value: jl.Double = _sum
}


/**
 * An [[AccumulatorV2 accumulator]] for collecting a list of elements.
 *
 * @since 2.0.0
 */
class CollectionAccumulator[T] extends DataAccumulatorV2[T, java.util.List[T]] {
  private val _list: java.util.List[T] = Collections.synchronizedList(new ArrayList[T]())

  override def isZero: Boolean = _list.isEmpty

  override def copyAndReset(): CollectionAccumulator[T] = new CollectionAccumulator

  override def copy(): CollectionAccumulator[T] = {
    val newAcc = new CollectionAccumulator[T]
    _list.synchronized {
      newAcc._list.addAll(_list)
    }
    newAcc
  }

  override def reset(): Unit = _list.clear()

  override def addImpl(v: T): Unit = _list.add(v)

  override def mergeImpl(other: AccumulatorV2[T, java.util.List[T]]): Unit = other match {
    case o: CollectionAccumulator[T] => _list.addAll(o.value)
    case _ => throw new UnsupportedOperationException(
      s"Cannot merge ${this.getClass.getName} with ${other.getClass.getName}")
  }

  override def value: java.util.List[T] = _list.synchronized {
    java.util.Collections.unmodifiableList(new ArrayList[T](_list))
  }

  private[spark] def setValue(newValue: java.util.List[T]): Unit = {
    _list.clear()
    _list.addAll(newValue)
  }
}


class LegacyAccumulatorWrapper[R, T](
    initialValue: R,
    param: org.apache.spark.AccumulableParam[R, T]) extends DataAccumulatorV2[T, R] {
  private[spark] var _value = initialValue  // Current value on driver

  override def isZero: Boolean = _value == param.zero(initialValue)

  override def copy(): LegacyAccumulatorWrapper[R, T] = {
    val acc = new LegacyAccumulatorWrapper(initialValue, param)
    acc._value = _value
    acc
  }

  override def reset(): Unit = {
    _value = param.zero(initialValue)
  }

  override def addImpl(v: T): Unit = _value = param.addAccumulator(_value, v)

  override def mergeImpl(other: AccumulatorV2[T, R]): Unit = other match {
    case o: LegacyAccumulatorWrapper[R, T] => _value = param.addInPlace(_value, o.value)
    case _ => throw new UnsupportedOperationException(
      s"Cannot merge ${this.getClass.getName} with ${other.getClass.getName}")
  }

  override def value: R = _value
}
