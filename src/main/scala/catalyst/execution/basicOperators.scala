package catalyst
package execution

import catalyst.errors._
import catalyst.expressions._
import catalyst.types._

/* Implicits */
import org.apache.spark.rdd.SharkOrderedRDDFunctions._

case class Project(projectList: Seq[NamedExpression], child: SharkPlan) extends UnaryNode {
  def output = projectList.map(_.toAttribute)

  def execute() = child.execute().map { row =>
    buildRow(projectList.map(Evaluate(_, Vector(row))))
  }
}

case class Filter(condition: Expression, child: SharkPlan) extends UnaryNode {
  def output = child.output
  def execute() = child.execute().filter { row =>
    Evaluate(condition, Vector(row)).asInstanceOf[Boolean]
  }
}

case class Union(left: SharkPlan, right: SharkPlan)(@transient sc: SharkContext)
  extends BinaryNode {
  // TODO: attributes output by union should be distinct for nullability purposes
  def output = left.output
  // TODO: is it more efficient to union a bunch of rdds at once? should union be variadic?
  def execute() = sc.union(left.execute(), right.execute())

  override def otherCopyArgs = sc :: Nil
}

case class StopAfter(limit: Int, child: SharkPlan)(@transient sc: SharkContext) extends UnaryNode {
  override def otherCopyArgs = sc :: Nil

  def output = child.output

  override def executeCollect() = child.execute().take(limit)

  // TODO: Terminal split should be implemented differently from non-terminal split.
  // TODO: Pick num splits based on |limit|.
  def execute() = sc.makeRDD(executeCollect(), 1)
}

case class Sort(
    sortExprs: Seq[SortOrder],
    child: SharkPlan)
    (override val outputDataProperty: DataProperty =
       SortProperty(sortExprs)) extends UnaryNode {
  val numPartitions = 1 // TODO: Set with input cardinality

  override val requiredDataProperty: DataProperty = SortProperty(sortExprs)
  override def otherCopyArgs = outputDataProperty :: Nil

  private final val directions = sortExprs.map(_.direction).toIndexedSeq
  private final val dataTypes = sortExprs.map(_.dataType).toIndexedSeq

  // TODO: This SortKey and the one in [[catalyst.execute.Exchange]] are the same.
  // We should just define it only once.
  private class SortKey(val keyValues: IndexedSeq[Any]) extends Ordered[SortKey] with Serializable {
    def compare(other: SortKey): Int = {
      var i = 0
      while (i < keyValues.size) {
        val left = keyValues(i)
        val right = other.keyValues(i)
        val curDirection = directions(i)
        val curDataType = dataTypes(i)

        logger.debug(s"Comparing $left, $right as $curDataType order $curDirection")
        // TODO: Use numeric here too?
        val comparison =
          if (left == null && right == null) {
            0
          } else if (left == null) {
            if (curDirection == Ascending) -1 else 1
          } else if (right == null) {
            if (curDirection == Ascending) 1 else -1
          } else if (curDataType == IntegerType) {
            if (curDirection == Ascending) {
              left.asInstanceOf[Int] compare right.asInstanceOf[Int]
            } else {
              right.asInstanceOf[Int] compare left.asInstanceOf[Int]
            }
          } else if (curDataType == DoubleType) {
            if (curDirection == Ascending) {
              left.asInstanceOf[Double] compare right.asInstanceOf[Double]
            } else {
              right.asInstanceOf[Double] compare left.asInstanceOf[Double]
            }
          } else if (curDataType == LongType) {
            if (curDirection == Ascending) {
              left.asInstanceOf[Long] compare right.asInstanceOf[Long]
            } else {
              right.asInstanceOf[Long] compare left.asInstanceOf[Long]
            }
          } else if (curDataType == StringType) {
            if (curDirection == Ascending) {
              left.asInstanceOf[String] compare right.asInstanceOf[String]
            } else {
              right.asInstanceOf[String] compare left.asInstanceOf[String]
            }
          } else {
            sys.error(s"Comparison not yet implemented for: $curDataType")
          }

        if (comparison != 0) return comparison
        i += 1
      }
      return 0
    }
  }

  // TODO: Don't include redundant expressions in both sortKey and row.
  def execute() = attachTree(this, "sort") {
    // TODO: If the child of it is an [[catalyst.execution.Exchange]],
    // do not evaluate the sortKey again since we have evaluated it
    // in the [[catalyst.execution.Exchange]].
    child.execute().map { row =>
      val input = Vector(row)
      val sortKey = new SortKey(
        sortExprs.map(s => Evaluate(s.child, input)).toIndexedSeq)

      (sortKey, row)
    }.sortByKeyLocally(ascending = true).map(_._2)
  }

  def output = child.output
}

// TODO: Rename: SchemaRDD
case class LocalRelation(output: Seq[Attribute], data: Seq[IndexedSeq[Any]])
                        (@transient sc: SharkContext) extends LeafNode {

  // Since LocalRelation is used for unit tests, set the defaultParallelism to 2
  // to make sure we can cover bugs appearing in a distributed environment.
  def execute() = sc.makeRDD(data.map(buildRow), 2)
}

