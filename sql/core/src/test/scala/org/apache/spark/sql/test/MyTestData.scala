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

package org.apache.spark.sql.test

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, SQLContext}

/**
 * A collection of sample data used in SQL tests.
 */
private[spark] trait MyTestData {
  protected val sqlContext: SQLContext
  import sqlContext.implicits._

  // All test data should be lazy because the SQLContext is not set up yet

  lazy val testData: DataFrame = {
    val df = sqlContext.sparkContext.parallelize(
      (1 to 100).map(i => TestData(i, i.toString))).toDF()
    df.registerTempTable("testData")
    df
  }

  lazy val testData2: DataFrame = {
    val df = sqlContext.sparkContext.parallelize(
      TestData2(1, 1) ::
      TestData2(1, 2) ::
      TestData2(2, 1) ::
      TestData2(2, 2) ::
      TestData2(3, 1) ::
      TestData2(3, 2) :: Nil, 2).toDF()
    df.registerTempTable("testData2")
    df
  }

  lazy val testData3: DataFrame = {
    val df = sqlContext.sparkContext.parallelize(
      TestData3(1, None) ::
      TestData3(2, Some(2)) :: Nil).toDF()
    df.registerTempTable("testData3")
    df
  }

  lazy val negativeData: DataFrame = {
    val df = sqlContext.sparkContext.parallelize(
      (1 to 100).map(i => TestData(-i, (-i).toString))).toDF()
    df.registerTempTable("negativeData")
    df
  }

  lazy val largeAndSmallInts: DataFrame = {
    val df = sqlContext.sparkContext.parallelize(
      LargeAndSmallInts(2147483644, 1) ::
      LargeAndSmallInts(1, 2) ::
      LargeAndSmallInts(2147483645, 1) ::
      LargeAndSmallInts(2, 2) ::
      LargeAndSmallInts(2147483646, 1) ::
      LargeAndSmallInts(3, 2) :: Nil).toDF()
    df.registerTempTable("largeAndSmallInts")
    df
  }

  lazy val decimalData: DataFrame = {
    val df = sqlContext.sparkContext.parallelize(
      DecimalData(1, 1) ::
      DecimalData(1, 2) ::
      DecimalData(2, 1) ::
      DecimalData(2, 2) ::
      DecimalData(3, 1) ::
      DecimalData(3, 2) :: Nil).toDF()
    df.registerTempTable("decimalData")
    df
  }

  lazy val binaryData: DataFrame = {
    val df = sqlContext.sparkContext.parallelize(
      BinaryData("12".getBytes, 1) ::
      BinaryData("22".getBytes, 5) ::
      BinaryData("122".getBytes, 3) ::
      BinaryData("121".getBytes, 2) ::
      BinaryData("123".getBytes, 4) :: Nil).toDF()
    df.registerTempTable("binaryData")
    df
  }

  lazy val upperCaseData: DataFrame = {
    val df = sqlContext.sparkContext.parallelize(
      UpperCaseData(1, "A") ::
      UpperCaseData(2, "B") ::
      UpperCaseData(3, "C") ::
      UpperCaseData(4, "D") ::
      UpperCaseData(5, "E") ::
      UpperCaseData(6, "F") :: Nil).toDF()
    df.registerTempTable("upperCaseData")
    df
  }

  lazy val lowerCaseData: DataFrame = {
    val df = sqlContext.sparkContext.parallelize(
      LowerCaseData(1, "a") ::
      LowerCaseData(2, "b") ::
      LowerCaseData(3, "c") ::
      LowerCaseData(4, "d") :: Nil).toDF()
    df.registerTempTable("lowerCaseData")
    df
  }

  lazy val arrayData: RDD[ArrayData] = {
    val rdd = sqlContext.sparkContext.parallelize(
      ArrayData(Seq(1, 2, 3), Seq(Seq(1, 2, 3))) ::
      ArrayData(Seq(2, 3, 4), Seq(Seq(2, 3, 4))) :: Nil)
    rdd.toDF().registerTempTable("arrayData")
    rdd
  }

  lazy val mapData: RDD[MapData] = {
    val rdd = sqlContext.sparkContext.parallelize(
      MapData(Map(1 -> "a1", 2 -> "b1", 3 -> "c1", 4 -> "d1", 5 -> "e1")) ::
      MapData(Map(1 -> "a2", 2 -> "b2", 3 -> "c2", 4 -> "d2")) ::
      MapData(Map(1 -> "a3", 2 -> "b3", 3 -> "c3")) ::
      MapData(Map(1 -> "a4", 2 -> "b4")) ::
      MapData(Map(1 -> "a5")) :: Nil)
    rdd.toDF().registerTempTable("mapData")
    rdd
  }

  lazy val repeatedData: RDD[StringData] = {
    val rdd = sqlContext.sparkContext.parallelize(List.fill(2)(StringData("test")))
    rdd.toDF().registerTempTable("repeatedData")
    rdd
  }

  lazy val nullableRepeatedData: RDD[StringData] = {
    val rdd = sqlContext.sparkContext.parallelize(
      List.fill(2)(StringData(null)) ++
      List.fill(2)(StringData("test")))
    rdd.toDF().registerTempTable("nullableRepeatedData")
    rdd
  }

  lazy val nullInts: DataFrame = {
    val df = sqlContext.sparkContext.parallelize(
      NullInts(1) ::
      NullInts(2) ::
      NullInts(3) ::
      NullInts(null) :: Nil).toDF()
    df.registerTempTable("nullInts")
    df
  }

  lazy val allNulls: DataFrame = {
    val df = sqlContext.sparkContext.parallelize(
      NullInts(null) ::
      NullInts(null) ::
      NullInts(null) ::
      NullInts(null) :: Nil).toDF()
    df.registerTempTable("allNulls")
    df
  }

  lazy val nullStrings: DataFrame = {
    val df = sqlContext.sparkContext.parallelize(
      NullStrings(1, "abc") ::
      NullStrings(2, "ABC") ::
      NullStrings(3, null) :: Nil).toDF()
    df.registerTempTable("nullStrings")
    df
  }

  lazy val tableName: DataFrame = {
    val df = sqlContext.sparkContext.parallelize(TableName("test") :: Nil).toDF()
    df.registerTempTable("tableName")
    df
  }

  lazy val unparsedStrings: RDD[String] = {
    sqlContext.sparkContext.parallelize(
      "1, A1, true, null" ::
      "2, B2, false, null" ::
      "3, C3, true, null" ::
      "4, D4, true, 2147483644" :: Nil)
  }

  // An RDD with 4 elements and 8 partitions
  lazy val withEmptyParts: RDD[IntField] = {
    val rdd = sqlContext.sparkContext.parallelize((1 to 4).map(IntField), 8)
    rdd.toDF().registerTempTable("withEmptyParts")
    rdd
  }

  lazy val person: DataFrame = {
    val df = sqlContext.sparkContext.parallelize(
      Person(0, "mike", 30) ::
      Person(1, "jim", 20) :: Nil).toDF()
    df.registerTempTable("person")
    df
  }

  lazy val salary: DataFrame = {
    val df = sqlContext.sparkContext.parallelize(
      Salary(0, 2000.0) ::
      Salary(1, 1000.0) :: Nil).toDF()
    df.registerTempTable("salary")
    df
  }

  lazy val complexData: DataFrame = {
    val df = sqlContext.sparkContext.parallelize(
      ComplexData(Map("1" -> 1), TestData(1, "1"), Seq(1, 1, 1), true) ::
      ComplexData(Map("2" -> 2), TestData(2, "2"), Seq(2, 2, 2), false) ::
      Nil).toDF()
    df.registerTempTable("complexData")
    df
  }

  /* ------------------------------ *
 | Case classes used in test data |
 * ------------------------------ */

  private[spark] case class TestData(key: Int, value: String)
  private[spark] case class TestData2(a: Int, b: Int)
  private[spark] case class TestData3(a: Int, b: Option[Int])
  private[spark] case class LargeAndSmallInts(a: Int, b: Int)
  private[spark] case class DecimalData(a: BigDecimal, b: BigDecimal)
  private[spark] case class BinaryData(a: Array[Byte], b: Int)
  private[spark] case class UpperCaseData(N: Int, L: String)
  private[spark] case class LowerCaseData(n: Int, l: String)
  private[spark] case class ArrayData(data: Seq[Int], nestedData: Seq[Seq[Int]])
  private[spark] case class MapData(data: scala.collection.Map[Int, String])
  private[spark] case class StringData(s: String)
  private[spark] case class IntField(i: Int)
  private[spark] case class NullInts(a: Integer)
  private[spark] case class NullStrings(n: Int, s: String)
  private[spark] case class TableName(tableName: String)
  private[spark] case class Person(id: Int, name: String, age: Int)
  private[spark] case class Salary(personId: Int, salary: Double)
  private[spark] case class ComplexData(m: Map[String, Int], s: TestData, a: Seq[Int], b: Boolean)
}
