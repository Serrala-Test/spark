package org.apache.spark.sql
package execution

import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext

import catalyst.errors._
import catalyst.expressions._
import catalyst.plans.physical.{UnspecifiedDistribution, OrderedDistribution}
import catalyst.plans.logical.LogicalPlan

case class Project(projectList: Seq[NamedExpression], child: SparkPlan) extends UnaryNode {
  def output = projectList.map(_.toAttribute)

  def execute() = child.execute().map { row =>
    buildRow(projectList.map(Evaluate(_, Vector(row))))
  }
}

case class Filter(condition: Expression, child: SparkPlan) extends UnaryNode {
  def output = child.output
  def execute() = child.execute().filter { row =>
    Evaluate(condition, Vector(row)).asInstanceOf[Boolean]
  }
}

case class Sample(fraction: Double, withReplacement: Boolean, seed: Int, child: SparkPlan)
    extends UnaryNode {

  def output = child.output

  // TODO: How to pick seed?
  def execute() = child.execute().sample(withReplacement, fraction, seed)
}

case class Union(children: Seq[SparkPlan])(@transient sc: SparkContext) extends SparkPlan {
  // TODO: attributes output by union should be distinct for nullability purposes
  def output = children.head.output
  def execute() = sc.union(children.map(_.execute()))

  override def otherCopyArgs = sc :: Nil
}

case class StopAfter(limit: Int, child: SparkPlan)(@transient sc: SparkContext) extends UnaryNode {
  override def otherCopyArgs = sc :: Nil

  def output = child.output

  override def executeCollect() = child.execute().take(limit)

  // TODO: Terminal split should be implemented differently from non-terminal split.
  // TODO: Pick num splits based on |limit|.
  def execute() = sc.makeRDD(executeCollect(), 1)
}

case class TopK(limit: Int, sortOrder: Seq[SortOrder], child: SparkPlan)(@transient sc: SparkContext) extends UnaryNode {
  override def otherCopyArgs = sc :: Nil

  def output = child.output

  @transient
  lazy val ordering = new RowOrdering(sortOrder)

  override def executeCollect() = child.execute().takeOrdered(limit)(ordering)

  // TODO: Terminal split should be implemented differently from non-terminal split.
  // TODO: Pick num splits based on |limit|.
  def execute() = sc.makeRDD(executeCollect(), 1)
}


case class Sort(
    sortOrder: Seq[SortOrder],
    global: Boolean,
    child: SparkPlan)
  extends UnaryNode {
  override def requiredChildDistribution =
    if (global) OrderedDistribution(sortOrder) :: Nil else UnspecifiedDistribution :: Nil

  @transient
  lazy val ordering = new RowOrdering(sortOrder)

  def execute() = attachTree(this, "sort") {
    // TODO: Optimize sorting operation?
    child.execute()
      .mapPartitions(
        iterator => iterator.toArray.sorted(ordering).iterator,
        preservesPartitioning = true)
  }

  def output = child.output
}

case class ExistingRdd(output: Seq[Attribute], rdd: RDD[Row]) extends LeafNode {
  def execute() = rdd
}

