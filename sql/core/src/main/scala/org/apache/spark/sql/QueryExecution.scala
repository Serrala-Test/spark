package org.apache.spark.sql

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.rules.RuleExecutor
import org.apache.spark.sql.execution.{EnsureRequirements, SparkPlan}


/**
 * :: DeveloperApi ::
 * The primary workflow for executing relational queries using Spark.  Designed to allow easy
 * access to the intermediate phases of query execution for developers.
 */
@DeveloperApi
protected[sql] class QueryExecution(val sqlContext: SQLContext, val logical: LogicalPlan) {
  def assertAnalyzed(): Unit = sqlContext.analyzer.checkAnalysis(analyzed)

  lazy val analyzed: LogicalPlan = sqlContext.analyzer(logical)
  lazy val withCachedData: LogicalPlan = {
    assertAnalyzed()
    sqlContext.cacheManager.useCachedData(analyzed)
  }
  lazy val optimizedPlan: LogicalPlan = sqlContext.optimizer(withCachedData)

  // TODO: Don't just pick the first one...
  lazy val sparkPlan: SparkPlan = {
    SparkPlan.currentContext.set(sqlContext)
    sqlContext.planner(optimizedPlan).next()
  }


  /**
   * Prepares a planned SparkPlan for execution by inserting shuffle operations as needed.
   */
  @transient
  protected[sql] val prepareForExecution = new RuleExecutor[SparkPlan] {
    val batches =
      Batch("Add exchange", Once, EnsureRequirements(sqlContext)) :: Nil
  }

  // executedPlan should not be used to initialize any SparkPlan. It should be
  // only used for execution.
  lazy val executedPlan: SparkPlan = prepareForExecution(sparkPlan)

  /** Internal version of the RDD. Avoids copies and has no schema */
  lazy val toRdd: RDD[Row] = executedPlan.execute()

  protected def stringOrError[A](f: => A): String =
    try f.toString catch { case e: Throwable => e.toString }

  def simpleString: String =
    s"""== Physical Plan ==
       |${stringOrError(executedPlan)}
      """.stripMargin.trim

  override def toString: String =
  // TODO previously will output RDD details by run (${stringOrError(toRdd.toDebugString)})
  // however, the `toRdd` will cause the real execution, which is not what we want.
  // We need to think about how to avoid the side effect.
    s"""== Parsed Logical Plan ==
       |${stringOrError(logical)}
        |== Analyzed Logical Plan ==
        |${stringOrError(analyzed)}
        |== Optimized Logical Plan ==
        |${stringOrError(optimizedPlan)}
        |== Physical Plan ==
        |${stringOrError(executedPlan)}
        |Code Generation: ${stringOrError(executedPlan.codegenEnabled)}
        |== RDD ==
      """.stripMargin.trim
}
