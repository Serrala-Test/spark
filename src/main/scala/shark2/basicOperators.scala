package catalyst
package shark2

import org.apache.hadoop.hive.ql.plan.{FileSinkDesc, TableDesc}
import org.apache.hadoop.hive.serde2.objectinspector.{PrimitiveObjectInspector, StructObjectInspector}
import shark.execution.HadoopTableReader
import shark.{SharkContext, SharkEnv}

import errors._
import expressions._
import types._

import collection.JavaConversions._
import org.apache.spark.SparkContext._
import org.apache.hadoop.hive.ql.exec.OperatorFactory
import org.apache.hadoop.hive.ql.io.HiveFileFormatUtils
import org.apache.hadoop.hive.serde2.Serializer
import org.apache.hadoop.mapred.JobConf
import org.apache.hadoop.fs.Path
;

case class Project(projectList: Seq[NamedExpression], child: SharkPlan) extends UnaryNode {
  def output = projectList.map(_.toAttribute)

  def execute() = child.execute().map { row =>
    projectList.map(Evaluate(_, Vector(row))).toIndexedSeq
  }
}

case class Filter(condition: Expression, child: SharkPlan) extends UnaryNode {
  def output = child.output
  def execute() = child.execute().filter { row =>
    Evaluate(condition, Vector(row)).asInstanceOf[Boolean]
  }
}

case class Union(left: SharkPlan, right: SharkPlan)(@transient sc: SharkContext) extends BinaryNode {
  // TODO: attributes output by union should be distinct for nullability purposes
  def output = left.output
  // TODO: is it more efficient to union a bunch of rdds at once? should union be variadic?
  def execute() = sc.union(left.execute(), right.execute())
}

case class StopAfter(limit: Int, child: SharkPlan)(@transient sc: SharkContext) extends UnaryNode {
  def output = child.output
  // TODO: Pick num splits based on |limit|.
  def execute() = sc.makeRDD(child.execute().take(limit),1)
}

case class Aggregate(groupingExpressions: Seq[Expression],
                     aggregateExpressions: Seq[NamedExpression],
                     child: SharkPlan) extends UnaryNode {
  def output = aggregateExpressions.map(_.toAttribute)
  def execute() = attachTree(this, "execute") {
    val grouped = child.execute().map(row => (groupingExpressions.map(Evaluate(_, Vector(row))), row)).groupByKey()
    grouped.map {
      case (group, rows) =>
        // TODO: Handle other types of expressions that build on aggregate expressions.
        aggregateExpressions.map {
          case BoundReference(0, idx, _) => rows.head(idx) // Assume that this is an 'Any'
          case Alias(Sum(e), _) => rows.map(r => Evaluate(e, Vector(r)).asInstanceOf[Int]).sum
          case Alias(Count(e), _) => rows.map(r => Evaluate(e, Vector(r))).filter(_ != null).size
          case Alias(CountDistinct(exprs), _) => rows.map(r => exprs.map(Evaluate(_, Vector(r)))).filter(_.map(_ != null).reduceLeft(_ && _)).distinct.size
        }.toIndexedSeq
    }
  }
}

/**
 * Uses spark Accumulators to perform global aggregation.
 *
 * Currently supports only COUNT().
 */
case class SparkAggregate(aggregateExprs: Seq[NamedExpression], child: SharkPlan)
                         (@transient sc: SharkContext) extends UnaryNode {
  def output = aggregateExprs.map(_.toAttribute)
  override def otherCopyArgs = Seq(sc)

  abstract class AggregateFunction extends Serializable {
    def apply(input: Seq[Seq[Any]]): Unit
    def result: Any
  }

  case class AverageFunction(expr: Expression) extends AggregateFunction {
    def this() = this(null) // Required for serialization.

    val count  = sc.accumulable(0)
    val sum = sc.accumulable(0)
    def result: Any = sum.value.toDouble / count.value.toDouble

    def apply(input: Seq[Seq[Any]]): Unit = {
      count += 1
      // TODO: Support all types here...
      sum += Evaluate(expr, input).asInstanceOf[Int]
    }
  }

  case class CountFunction(expr: Expression) extends AggregateFunction {
    def this() = this(null) // Required for serialization.

    val count = sc.accumulable(0)

    def apply(input: Seq[Seq[Any]]): Unit =
      if(Evaluate(expr, input) != null)
        count += 1

    def result: Any = count.value.toLong
  }

  def execute() = attachTree(this, "SparkAggregate") {
    val aggFunctions = aggregateExprs.map {
        case Alias(Average(expr), _) => new AverageFunction(expr)
        case Alias(Count(expr), _) => new CountFunction(expr)
        case unsupported => throw new OptimizationException(unsupported, "Aggregate not implemented in SparkAggregate")
      }

    //TOOD: println(s"Running aggregates: $aggFunctions")
    child.execute().foreach { row =>
      val input = Vector(row)
      aggFunctions.foreach(_.apply(input))
    }
    sc.makeRDD(Seq(aggFunctions.map(_.result).toIndexedSeq), 1)
  }
}

case class Sort(sortExprs: Seq[SortOrder], child: SharkPlan) extends UnaryNode {
  val numPartitions = 1 // TODO: Set with input cardinality

  private final val directions = sortExprs.map(_.direction).toIndexedSeq
  private final val dataTypes = sortExprs.map(_.dataType).toIndexedSeq

  private class SortKey(val keyValues: IndexedSeq[Any]) extends Ordered[SortKey] with Serializable {
    def compare(other: SortKey): Int = {
      var i = 0
      while(i < keyValues.size) {
        val left = keyValues(i)
        val right = other.keyValues(i)
        val curDirection = directions(i)
        val curDataType = dataTypes(i)

        // TODO: Configure logging...
        // println(s"Comparing $left, $right as $curDataType order $curDirection")

        val comparison =
          if(curDataType == IntegerType)
            if(curDirection == Ascending)
              left.asInstanceOf[Int] compare right.asInstanceOf[Int]
            else
              right.asInstanceOf[Int] compare left.asInstanceOf[Int]
          else if(curDataType == DoubleType)
            if(curDirection == Ascending)
              left.asInstanceOf[Double] compare right.asInstanceOf[Double]
            else
              right.asInstanceOf[Double] compare left.asInstanceOf[Double]
          else
            sys.error(s"Comparison not yet implemented for: $curDataType")

        if(comparison != 0) return comparison
        i += 1
      }
      return 0
    }
  }

  // TODO: Don't include redundant expressions in both sortKey and row.
  def execute() = attachTree(this, "sort") {
    child.execute().map { row =>
      val input = Vector(row)
      val sortKey = new SortKey(sortExprs.map(s => Evaluate(s.child, input)).toIndexedSeq)

      (sortKey, row)
    }.sortByKey(true, numPartitions).map(_._2)
  }

  def output = child.output
}

case class HiveTableScan(attributes: Seq[Attribute], relation: MetastoreRelation) extends LeafNode {
  @transient
  val hadoopReader = new HadoopTableReader(relation.tableDesc, SharkContext.hiveconf)

  /**
    * The hive object inspector for this table, which can be used to extract values from the
    * serialized row representation.
    */
  @transient
  lazy val objectInspector =
    relation.tableDesc.getDeserializer.getObjectInspector.asInstanceOf[StructObjectInspector]

  /**
   * The hive struct field references that correspond to the attributes to be read from this table.
   */
  @transient
  lazy val refs = attributes.map { a =>
    objectInspector.getAllStructFieldRefs
      .find(_.getFieldName == a.name)
      .getOrElse(sys.error(s"Invalid attribute ${a.name} referenced in table $relation"))
  }

  def execute() = {
    hadoopReader.makeRDDForTable(relation.hiveQlTable).map {
      case struct: org.apache.hadoop.hive.serde2.`lazy`.LazyStruct =>
        refs.map { ref =>
          val data = objectInspector.getStructFieldData(struct, ref)
          ref.getFieldObjectInspector.asInstanceOf[PrimitiveObjectInspector].getPrimitiveJavaObject(data)
        }.toIndexedSeq
    }
  }

  def output = attributes
}

case class InsertIntoHiveTable(table: MetastoreRelation, child: SharkPlan)
                              (@transient sc: SharkContext) extends UnaryNode {
  /**
   * This file sink / record writer code is only the first step towards implementing this operator correctly and is not
   * actually used yet.
   */
  val desc = new FileSinkDesc("./", table.tableDesc, false)
  val outputClass = {
    val serializer = table.tableDesc.getDeserializerClass.newInstance().asInstanceOf[Serializer]
    serializer.initialize(null, table.tableDesc.getProperties)
    serializer.getSerializedClass
  }

  lazy val conf = new JobConf();
  lazy val writer = HiveFileFormatUtils.getHiveRecordWriter(
    conf,
    table.tableDesc,
    outputClass,
    desc,
    new Path((new org.apache.hadoop.fs.RawLocalFileSystem).getWorkingDirectory(), "test.out"),
    null)

  override def otherCopyArgs = sc :: Nil

  def output = child.output
  def execute() = {
    val childRdd = child.execute()

    // TODO: write directly to hive
    val tempDir = java.io.File.createTempFile("data", "tsv")
    tempDir.delete()
    tempDir.mkdir()
    childRdd.map(_.map(_.toString).mkString("\001")).saveAsTextFile(tempDir.getCanonicalPath)
    sc.runSql(s"LOAD DATA LOCAL INPATH '${tempDir.getCanonicalPath}/*' INTO TABLE ${table.tableName}")

    // It would be nice to just return the childRdd unchanged so insert operations could be chained,
    // however for now we return an empty list to simplify compatibility checks with hive, which
    // does not return anything for insert operations.
    // TODO: implement hive compatibility as rules.
    sc.makeRDD(Nil, 1)
  }
}

case class LocalRelation(output: Seq[Attribute], data: Seq[IndexedSeq[Any]])
                        (@transient sc: SharkContext) extends LeafNode {
  def execute() = sc.makeRDD(data, 1)
}

