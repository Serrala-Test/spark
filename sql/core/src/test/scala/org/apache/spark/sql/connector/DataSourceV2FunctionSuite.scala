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

package org.apache.spark.sql.connector

import java.util
import java.util.Collections

import test.org.apache.spark.sql.connector.catalog.functions.{JavaAverage, JavaStrLen}
import test.org.apache.spark.sql.connector.catalog.functions.JavaStrLen._

import org.apache.spark.SparkException
import org.apache.spark.sql.{AnalysisException, Row}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.connector.catalog.{Identifier, SupportsNamespaces, V2InMemoryCatalog}
import org.apache.spark.sql.connector.catalog.functions._
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String

class DataSourceV2FunctionSuite extends DatasourceV2SQLBase {
  private val emptyProps: util.Map[String, String] = Collections.emptyMap[String, String]

  private def addFunction(ident: Identifier, fn: UnboundFunction): Unit = {
    catalog("testcat").asInstanceOf[V2InMemoryCatalog].createFunction(ident, fn)
  }

  test("scalar function: with default produceResult method") {
    catalog("testcat").asInstanceOf[SupportsNamespaces].createNamespace(Array("ns"), emptyProps)
    addFunction(Identifier.of(Array("ns"), "strlen"), StrLen(StrLenDefault))
    checkAnswer(sql("SELECT testcat.ns.strlen('abc')"), Row(3) :: Nil)
  }

  test("scalar function: lookup magic method") {
    catalog("testcat").asInstanceOf[SupportsNamespaces].createNamespace(Array("ns"), emptyProps)
    addFunction(Identifier.of(Array("ns"), "strlen"), StrLen(StrLenMagic))
    checkAnswer(sql("SELECT testcat.ns.strlen('abc')"), Row(3) :: Nil)
  }

  test("scalar function: bad magic method") {
    catalog("testcat").asInstanceOf[SupportsNamespaces].createNamespace(Array("ns"), emptyProps)
    addFunction(Identifier.of(Array("ns"), "strlen"), StrLen(StrLenBadMagic))
    assert(intercept[SparkException](sql("SELECT testcat.ns.strlen('abc')").collect())
      .getMessage.contains("Cannot find a compatible"))
  }

  test("scalar function: bad magic method with default impl") {
    catalog("testcat").asInstanceOf[SupportsNamespaces].createNamespace(Array("ns"), emptyProps)
    addFunction(Identifier.of(Array("ns"), "strlen"), StrLen(StrLenBadMagicWithDefault))
    checkAnswer(sql("SELECT testcat.ns.strlen('abc')"), Row(3) :: Nil)
  }

  test("scalar function: no implementation found") {
    catalog("testcat").asInstanceOf[SupportsNamespaces].createNamespace(Array("ns"), emptyProps)
    addFunction(Identifier.of(Array("ns"), "strlen"), StrLen(StrLenNoImpl))
    intercept[SparkException](sql("SELECT testcat.ns.strlen('abc')").collect())
  }

  test("scalar function: invalid parameter type or length") {
    catalog("testcat").asInstanceOf[SupportsNamespaces].createNamespace(Array("ns"), emptyProps)
    addFunction(Identifier.of(Array("ns"), "strlen"), StrLen(StrLenDefault))

    assert(intercept[AnalysisException](sql("SELECT testcat.ns.strlen(42)"))
      .getMessage.contains("cannot process input"))
    assert(intercept[AnalysisException](sql("SELECT testcat.ns.strlen('a', 'b')"))
      .getMessage.contains("cannot process input"))
  }

  test("scalar function: default produceResult in Java") {
    catalog("testcat").asInstanceOf[SupportsNamespaces].createNamespace(Array("ns"), emptyProps)
    addFunction(Identifier.of(Array("ns"), "strlen"),
      new JavaStrLen(new JavaStrLenDefault))
    checkAnswer(sql("SELECT testcat.ns.strlen('abc')"), Row(3) :: Nil)
  }

  test("scalar function: magic method in Java") {
    catalog("testcat").asInstanceOf[SupportsNamespaces].createNamespace(Array("ns"), emptyProps)
    addFunction(Identifier.of(Array("ns"), "strlen"),
      new JavaStrLen(new JavaStrLenMagic))
    checkAnswer(sql("SELECT testcat.ns.strlen('abc')"), Row(3) :: Nil)
  }

  test("scalar function: no implementation found in Java") {
    catalog("testcat").asInstanceOf[SupportsNamespaces].createNamespace(Array("ns"), emptyProps)
    addFunction(Identifier.of(Array("ns"), "strlen"),
      new JavaStrLen(new JavaStrLenNoImpl))
    assert(intercept[AnalysisException](sql("SELECT testcat.ns.strlen('abc')").collect())
      .getMessage.contains("neither implement magic method nor override 'produceResult'"))
  }

  test("bad bound function (neither scalar nor aggregate)") {
    catalog("testcat").asInstanceOf[SupportsNamespaces].createNamespace(Array("ns"), emptyProps)
    addFunction(Identifier.of(Array("ns"), "strlen"), StrLen(BadBoundFunction))

    assert(intercept[AnalysisException](sql("SELECT testcat.ns.strlen('abc')"))
      .getMessage.contains("does not implement ScalarFunction or AggregateFunction"))
  }

  test("aggregate function: lookup int average") {
    import testImplicits._
    val t = "testcat.ns.t"
    withTable(t) {
      addFunction(Identifier.of(Array("ns"), "avg"), IntegralAverage)

      (1 to 100).toDF("i").write.saveAsTable(t)
      checkAnswer(sql(s"SELECT testcat.ns.avg(i) from $t"), Row(50) :: Nil)
    }
  }

  test("aggregate function: lookup long average") {
    import testImplicits._
    val t = "testcat.ns.t"
    withTable(t) {
      addFunction(Identifier.of(Array("ns"), "avg"), IntegralAverage)

      (1L to 100L).toDF("i").write.saveAsTable(t)
      checkAnswer(sql(s"SELECT testcat.ns.avg(i) from $t"), Row(50) :: Nil)
    }
  }

  test("aggregate function: unsupported input type") {
    import testImplicits._
    val t = "testcat.ns.t"
    withTable(t) {
      addFunction(Identifier.of(Array("ns"), "avg"), IntegralAverage)

      Seq(1.toShort, 2.toShort).toDF("i").write.saveAsTable(t)
      assert(intercept[AnalysisException](sql(s"SELECT testcat.ns.avg(i) from $t"))
        .getMessage.contains("Unsupported non-integral type: ShortType"))
    }
  }

  test("aggregate function: doesn't implement update should throw runtime error") {
    import testImplicits._
    val t = "testcat.ns.t"
    withTable(t) {
      addFunction(Identifier.of(Array("ns"), "avg"), IntegralAverage)

      Seq(1.toByte, 2.toByte).toDF("i").write.saveAsTable(t)
      assert(intercept[SparkException](sql(s"SELECT testcat.ns.avg(i) from $t").collect())
        .getMessage.contains("Cannot find a compatible AggregateFunction"))
    }
  }

  test("aggregate function: doesn't implement update in Java should throw analysis error") {
    import testImplicits._
    val t = "testcat.ns.t"
    withTable(t) {
      addFunction(Identifier.of(Array("ns"), "avg"), new JavaAverage)

      (1 to 100).toDF("i").write.saveAsTable(t)
      assert(intercept[AnalysisException](sql(s"SELECT testcat.ns.avg(i) from $t").collect())
        .getMessage.contains("neither implement magic method nor override 'update'"))
    }
  }

  private case class StrLen(impl: BoundFunction) extends UnboundFunction {
    override def description(): String =
      """strlen: returns the length of the input string
        |  strlen(string) -> int""".stripMargin
    override def name(): String = "strlen"

    override def bind(inputType: StructType): BoundFunction = {
      if (inputType.fields.length != 1) {
        throw new UnsupportedOperationException("Expect exactly one argument");
      }
      inputType.fields(0).dataType match {
        case StringType => impl
        case _ =>
          throw new UnsupportedOperationException("Expect StringType")
      }
    }
  }

  private case object StrLenDefault extends ScalarFunction[Int] {
    override def inputTypes(): Array[DataType] = Array(StringType)
    override def resultType(): DataType = IntegerType
    override def name(): String = "strlen_default"

    override def produceResult(input: InternalRow): Int = {
      val s = input.getString(0)
      s.length
    }
  }

  private case object StrLenMagic extends ScalarFunction[Int] {
    override def inputTypes(): Array[DataType] = Array(StringType)
    override def resultType(): DataType = IntegerType
    override def name(): String = "strlen_magic"

    def invoke(input: UTF8String): Int = {
      input.toString.length
    }
  }

  private case object StrLenBadMagic extends ScalarFunction[Int] {
    override def inputTypes(): Array[DataType] = Array(StringType)
    override def resultType(): DataType = IntegerType
    override def name(): String = "strlen_bad_magic"

    def invoke(input: String): Int = {
      input.length
    }
  }

  private case object StrLenBadMagicWithDefault extends ScalarFunction[Int] {
    override def inputTypes(): Array[DataType] = Array(StringType)
    override def resultType(): DataType = IntegerType
    override def name(): String = "strlen_bad_magic"

    def invoke(input: String): Int = {
      input.length
    }

    override def produceResult(input: InternalRow): Int = {
      val s = input.getString(0)
      s.length
    }
  }

  private case object StrLenNoImpl extends ScalarFunction[Int] {
    override def inputTypes(): Array[DataType] = Array(StringType)
    override def resultType(): DataType = IntegerType
    override def name(): String = "strlen_noimpl"
  }

  private case object BadBoundFunction extends BoundFunction {
    override def inputTypes(): Array[DataType] = Array(StringType)
    override def resultType(): DataType = IntegerType
    override def name(): String = "bad_bound_func"
  }

  object IntegralAverage extends UnboundFunction {
    override def name(): String = "iavg"

    override def bind(inputType: StructType): BoundFunction = {
      if (inputType.fields.length > 1) {
        throw new UnsupportedOperationException("Too many arguments")
      }

      inputType.fields(0).dataType match {
        case _: ByteType => ByteAverage
        case _: IntegerType => IntAverage
        case _: LongType => LongAverage
        case dataType =>
          throw new UnsupportedOperationException(s"Unsupported non-integral type: $dataType")
      }
    }

    override def description(): String =
      """iavg: produces an average using integer division, ignoring nulls
        |  iavg(int) -> int
        |  iavg(bigint) -> bigint""".stripMargin
  }

  object IntAverage extends AggregateFunction[(Int, Int), Int] {
    override def name(): String = "iavg"
    override def inputTypes(): Array[DataType] = Array(IntegerType)
    override def resultType(): DataType = IntegerType

    override def newAggregationState(): (Int, Int) = (0, 0)

    override def update(state: (Int, Int), input: InternalRow): (Int, Int) = {
      if (input.isNullAt(0)) {
        state
      } else {
        val i = input.getInt(0)
        state match {
          case (_, 0) =>
            (i, 1)
          case (total, count) =>
            (total + i, count + 1)
        }
      }
    }

    override def merge(leftState: (Int, Int), rightState: (Int, Int)): (Int, Int) = {
      (leftState._1 + rightState._1, leftState._2 + rightState._2)
    }

    override def produceResult(state: (Int, Int)): Int = state._1 / state._2
  }

  object LongAverage extends AggregateFunction[(Long, Long), Long] {
    override def name(): String = "iavg"
    override def inputTypes(): Array[DataType] = Array(LongType)
    override def resultType(): DataType = LongType

    override def newAggregationState(): (Long, Long) = (0L, 0L)

    override def update(state: (Long, Long), input: InternalRow): (Long, Long) = {
      if (input.isNullAt(0)) {
        state
      } else {
        val l = input.getLong(0)
        state match {
          case (_, 0L) =>
            (l, 1)
          case (total, count) =>
            (total + l, count + 1L)
        }
      }
    }

    override def merge(leftState: (Long, Long), rightState: (Long, Long)): (Long, Long) = {
      (leftState._1 + rightState._1, leftState._2 + rightState._2)
    }

    override def produceResult(state: (Long, Long)): Long = state._1 / state._2
  }

  /** Bad implementation which doesn't override `produceResult` */
  object ByteAverage extends AggregateFunction[(Long, Long), Long] {
    override def name(): String = "iavg"
    override def inputTypes(): Array[DataType] = Array(LongType)
    override def resultType(): DataType = LongType

    override def newAggregationState(): (Long, Long) = (0L, 0L)

    override def merge(leftState: (Long, Long), rightState: (Long, Long)): (Long, Long) = {
      (leftState._1 + rightState._1, leftState._2 + rightState._2)
    }

    override def produceResult(state: (Long, Long)): Long = state._1 / state._2
  }
}
