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

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.{FunctionIdentifier, TableIdentifier}
import org.apache.spark.sql.catalyst.expressions.{Expression, ExpressionInfo, Literal}
import org.apache.spark.sql.catalyst.parser.{CatalystSqlParser, ParserInterface}
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution.{SparkPlan, SparkStrategy}
import org.apache.spark.sql.types.{DataType, IntegerType, StructType}

/**
 * Test cases for the [[SparkSessionExtensions]].
 */
class SparkSessionExtensionSuite extends SparkFunSuite {
  type ExtensionsBuilder = SparkSessionExtensions => Unit
  private def create(builder: ExtensionsBuilder): Seq[ExtensionsBuilder] = Seq(builder)

  private def stop(spark: SparkSession): Unit = {
    spark.stop()
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
  }

  private def withSession(builders: Seq[ExtensionsBuilder])(f: SparkSession => Unit): Unit = {
    val builder = SparkSession.builder().master("local[1]")
    builders.foreach(builder.withExtensions)
    val spark = builder.getOrCreate()
    try f(spark) finally {
      stop(spark)
    }
  }

  test("inject analyzer rule") {
    withSession(Seq(_.injectResolutionRule(MyRule))) { session =>
      assert(session.sessionState.analyzer.extendedResolutionRules.contains(MyRule(session)))
    }
  }

  test("inject check analysis rule") {
    withSession(Seq(_.injectCheckRule(MyCheckRule))) { session =>
      assert(session.sessionState.analyzer.extendedCheckRules.contains(MyCheckRule(session)))
    }
  }

  test("inject optimizer rule") {
    withSession(Seq(_.injectOptimizerRule(MyRule))) { session =>
      assert(session.sessionState.optimizer.batches.flatMap(_.rules).contains(MyRule(session)))
    }
  }

  test("inject spark planner strategy") {
    withSession(Seq(_.injectPlannerStrategy(MySparkStrategy))) { session =>
      assert(session.sessionState.planner.strategies.contains(MySparkStrategy(session)))
    }
  }

  test("inject parser") {
    val extension = create { extensions =>
      extensions.injectParser((_, _) => CatalystSqlParser)
    }
    withSession(extension) { session =>
      assert(session.sessionState.sqlParser == CatalystSqlParser)
    }
  }

  test("inject multiple rules") {
    withSession(Seq(_.injectOptimizerRule(MyRule),
      _.injectPlannerStrategy(MySparkStrategy))) { session =>
      assert(session.sessionState.optimizer.batches.flatMap(_.rules).contains(MyRule(session)))
      assert(session.sessionState.planner.strategies.contains(MySparkStrategy(session)))
    }
  }

  test("inject stacked parsers") {
    val extension = create { extensions =>
      extensions.injectParser((_, _) => CatalystSqlParser)
      extensions.injectParser(MyParser)
      extensions.injectParser(MyParser)
    }
    withSession(extension) { session =>
      val parser = MyParser(session, MyParser(session, CatalystSqlParser))
      assert(session.sessionState.sqlParser == parser)
    }
  }

  test("inject function") {
    val extensions = create { extensions =>
      extensions.injectFunction(MyExtensions.myFunction)
    }
    withSession(extensions) { session =>
      assert(session.sessionState.functionRegistry
        .lookupFunction(MyExtensions.myFunction._1).isDefined)
    }
  }

  test("use custom class for extensions") {
    val session = SparkSession.builder()
      .master("local[1]")
      .config("spark.sql.extensions", classOf[MyExtensions].getCanonicalName)
      .getOrCreate()
    try {
      assert(session.sessionState.planner.strategies.contains(MySparkStrategy(session)))
      assert(session.sessionState.analyzer.extendedResolutionRules.contains(MyRule(session)))
      assert(session.sessionState.functionRegistry
        .lookupFunction(MyExtensions.myFunction._1).isDefined)
    } finally {
      stop(session)
    }
  }

  test("use multiple custom class for extensions") {
    val session = SparkSession.builder()
      .master("local[1]")
      .config("spark.sql.extensions", Seq(
        classOf[MyExtensions].getCanonicalName,
        classOf[MyExtensions2].getCanonicalName).mkString(","))
      .getOrCreate()
    try {
      assert(session.sessionState.planner.strategies.contains(MySparkStrategy(session)))
      assert(session.sessionState.analyzer.extendedResolutionRules.contains(MyRule(session)))
      assert(session.sessionState.functionRegistry
        .lookupFunction(MyExtensions.myFunction._1).isDefined)
      assert(session.sessionState.functionRegistry
        .lookupFunction(MyExtensions2.myFunction._1).isDefined)
    } finally {
      stop(session)
    }
  }
}

case class MyRule(spark: SparkSession) extends Rule[LogicalPlan] {
  override def apply(plan: LogicalPlan): LogicalPlan = plan
}

case class MyCheckRule(spark: SparkSession) extends (LogicalPlan => Unit) {
  override def apply(plan: LogicalPlan): Unit = { }
}

case class MySparkStrategy(spark: SparkSession) extends SparkStrategy {
  override def apply(plan: LogicalPlan): Seq[SparkPlan] = Seq.empty
}

case class MyParser(spark: SparkSession, delegate: ParserInterface) extends ParserInterface {
  override def parsePlan(sqlText: String): LogicalPlan =
    delegate.parsePlan(sqlText)

  override def parseExpression(sqlText: String): Expression =
    delegate.parseExpression(sqlText)

  override def parseTableIdentifier(sqlText: String): TableIdentifier =
    delegate.parseTableIdentifier(sqlText)

  override def parseFunctionIdentifier(sqlText: String): FunctionIdentifier =
    delegate.parseFunctionIdentifier(sqlText)

  override def parseTableSchema(sqlText: String): StructType =
    delegate.parseTableSchema(sqlText)

  override def parseDataType(sqlText: String): DataType =
    delegate.parseDataType(sqlText)
}

object MyExtensions {

  val myFunction = (FunctionIdentifier("myFunction"),
    new ExpressionInfo("noClass", "myDb", "myFunction", "usage", "extended usage"),
    (myArgs: Seq[Expression]) => Literal(5, IntegerType))

}

class MyExtensions extends (SparkSessionExtensions => Unit) {
  def apply(e: SparkSessionExtensions): Unit = {
    e.injectPlannerStrategy(MySparkStrategy)
    e.injectResolutionRule(MyRule)
    e.injectFunction(MyExtensions.myFunction)
  }
}

object MyExtensions2 {

  val myFunction = (FunctionIdentifier("myFunction2"),
    new ExpressionInfo("noClass", "myDb", "myFunction2", "usage", "extended usage" ),
    (myArgs: Seq[Expression]) => Literal(5, IntegerType))
}

class MyExtensions2 extends (SparkSessionExtensions => Unit) {
  def apply(e: SparkSessionExtensions): Unit = {
    e.injectFunction(MyExtensions2.myFunction)
  }
}
