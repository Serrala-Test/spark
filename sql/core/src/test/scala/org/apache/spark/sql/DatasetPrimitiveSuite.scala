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

import scala.collection.immutable.Queue
import scala.collection.mutable.ArrayBuffer

import org.apache.spark.sql.test.SharedSQLContext

case class IntClass(value: Int)

case class SeqCC(s: Seq[Int])

case class ListCC(l: List[Int])

case class QueueCC(q: Queue[Int])

case class ComplexCC(seq: SeqCC, list: ListCC, queue: QueueCC)

package object packageobject {
  case class PackageClass(value: Int)
}

class DatasetPrimitiveSuite extends QueryTest with SharedSQLContext {
  import testImplicits._

  test("toDS") {
    val data = Seq(1, 2, 3, 4, 5, 6)
    checkDataset(
      data.toDS(),
      data: _*)
  }

  test("as case class / collect") {
    val ds = Seq(1, 2, 3).toDS().as[IntClass]
    checkDataset(
      ds,
      IntClass(1), IntClass(2), IntClass(3))

    assert(ds.collect().head == IntClass(1))
  }

  test("map") {
    val ds = Seq(1, 2, 3).toDS()
    checkDataset(
      ds.map(_ + 1),
      2, 3, 4)
  }

  test("filter") {
    val ds = Seq(1, 2, 3, 4).toDS()
    checkDataset(
      ds.filter(_ % 2 == 0),
      2, 4)
  }

  test("foreach") {
    val ds = Seq(1, 2, 3).toDS()
    val acc = sparkContext.longAccumulator
    ds.foreach(acc.add(_))
    assert(acc.value == 6)
  }

  test("foreachPartition") {
    val ds = Seq(1, 2, 3).toDS()
    val acc = sparkContext.longAccumulator
    ds.foreachPartition(_.foreach(acc.add(_)))
    assert(acc.value == 6)
  }

  test("reduce") {
    val ds = Seq(1, 2, 3).toDS()
    assert(ds.reduce(_ + _) == 6)
  }

  test("groupBy function, keys") {
    val ds = Seq(1, 2, 3, 4, 5).toDS()
    val grouped = ds.groupByKey(_ % 2)
    checkDatasetUnorderly(
      grouped.keys,
      0, 1)
  }

  test("groupBy function, map") {
    val ds = Seq(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11).toDS()
    val grouped = ds.groupByKey(_ % 2)
    val agged = grouped.mapGroups { case (g, iter) =>
      val name = if (g == 0) "even" else "odd"
      (name, iter.size)
    }

    checkDatasetUnorderly(
      agged,
      ("even", 5), ("odd", 6))
  }

  test("groupBy function, flatMap") {
    val ds = Seq("a", "b", "c", "xyz", "hello").toDS()
    val grouped = ds.groupByKey(_.length)
    val agged = grouped.flatMapGroups { case (g, iter) => Iterator(g.toString, iter.mkString) }

    checkDatasetUnorderly(
      agged,
      "1", "abc", "3", "xyz", "5", "hello")
  }

  test("Arrays and Lists") {
    checkDataset(Seq(Seq(1)).toDS(), Seq(1))
    checkDataset(Seq(Seq(1.toLong)).toDS(), Seq(1.toLong))
    checkDataset(Seq(Seq(1.toDouble)).toDS(), Seq(1.toDouble))
    checkDataset(Seq(Seq(1.toFloat)).toDS(), Seq(1.toFloat))
    checkDataset(Seq(Seq(1.toByte)).toDS(), Seq(1.toByte))
    checkDataset(Seq(Seq(1.toShort)).toDS(), Seq(1.toShort))
    checkDataset(Seq(Seq(true)).toDS(), Seq(true))
    checkDataset(Seq(Seq("test")).toDS(), Seq("test"))
    checkDataset(Seq(Seq(Tuple1(1))).toDS(), Seq(Tuple1(1)))

    checkDataset(Seq(Array(1)).toDS(), Array(1))
    checkDataset(Seq(Array(1.toLong)).toDS(), Array(1.toLong))
    checkDataset(Seq(Array(1.toDouble)).toDS(), Array(1.toDouble))
    checkDataset(Seq(Array(1.toFloat)).toDS(), Array(1.toFloat))
    checkDataset(Seq(Array(1.toByte)).toDS(), Array(1.toByte))
    checkDataset(Seq(Array(1.toShort)).toDS(), Array(1.toShort))
    checkDataset(Seq(Array(true)).toDS(), Array(true))
    checkDataset(Seq(Array("test")).toDS(), Array("test"))
    checkDataset(Seq(Array(Tuple1(1))).toDS(), Array(Tuple1(1)))
  }

  test("arbitrary sequences") {
    checkDataset(Seq(Queue(1)).toDS(), Queue(1))
    checkDataset(Seq(Queue(1.toLong)).toDS(), Queue(1.toLong))
    checkDataset(Seq(Queue(1.toDouble)).toDS(), Queue(1.toDouble))
    checkDataset(Seq(Queue(1.toFloat)).toDS(), Queue(1.toFloat))
    checkDataset(Seq(Queue(1.toByte)).toDS(), Queue(1.toByte))
    checkDataset(Seq(Queue(1.toShort)).toDS(), Queue(1.toShort))
    checkDataset(Seq(Queue(true)).toDS(), Queue(true))
    checkDataset(Seq(Queue("test")).toDS(), Queue("test"))
    checkDataset(Seq(Queue(Tuple1(1))).toDS(), Queue(Tuple1(1)))

    checkDataset(Seq(ArrayBuffer(1)).toDS(), ArrayBuffer(1))
    checkDataset(Seq(ArrayBuffer(1.toLong)).toDS(), ArrayBuffer(1.toLong))
    checkDataset(Seq(ArrayBuffer(1.toDouble)).toDS(), ArrayBuffer(1.toDouble))
    checkDataset(Seq(ArrayBuffer(1.toFloat)).toDS(), ArrayBuffer(1.toFloat))
    checkDataset(Seq(ArrayBuffer(1.toByte)).toDS(), ArrayBuffer(1.toByte))
    checkDataset(Seq(ArrayBuffer(1.toShort)).toDS(), ArrayBuffer(1.toShort))
    checkDataset(Seq(ArrayBuffer(true)).toDS(), ArrayBuffer(true))
    checkDataset(Seq(ArrayBuffer("test")).toDS(), ArrayBuffer("test"))
    checkDataset(Seq(ArrayBuffer(Tuple1(1))).toDS(), ArrayBuffer(Tuple1(1)))
  }

  test("sequence and product combinations") {
    // Case classes
    checkDataset(Seq(SeqCC(Seq(1))).toDS(), SeqCC(Seq(1)))
    checkDataset(Seq(Seq(SeqCC(Seq(1)))).toDS(), Seq(SeqCC(Seq(1))))
    checkDataset(Seq(List(SeqCC(Seq(1)))).toDS(), List(SeqCC(Seq(1))))
    checkDataset(Seq(Queue(SeqCC(Seq(1)))).toDS(), Queue(SeqCC(Seq(1))))

    checkDataset(Seq(ListCC(List(1))).toDS(), ListCC(List(1)))
    checkDataset(Seq(Seq(ListCC(List(1)))).toDS(), Seq(ListCC(List(1))))
    checkDataset(Seq(List(ListCC(List(1)))).toDS(), List(ListCC(List(1))))
    checkDataset(Seq(Queue(ListCC(List(1)))).toDS(), Queue(ListCC(List(1))))

    checkDataset(Seq(QueueCC(Queue(1))).toDS(), QueueCC(Queue(1)))
    checkDataset(Seq(Seq(QueueCC(Queue(1)))).toDS(), Seq(QueueCC(Queue(1))))
    checkDataset(Seq(List(QueueCC(Queue(1)))).toDS(), List(QueueCC(Queue(1))))
    checkDataset(Seq(Queue(QueueCC(Queue(1)))).toDS(), Queue(QueueCC(Queue(1))))

    val complexCC = ComplexCC(SeqCC(Seq(1)), ListCC(List(2)), QueueCC(Queue(3)))
    checkDataset(Seq(complexCC).toDS(), complexCC)
    checkDataset(Seq(Seq(complexCC)).toDS(), Seq(complexCC))
    checkDataset(Seq(List(complexCC)).toDS(), List(complexCC))
    checkDataset(Seq(Queue(complexCC)).toDS(), Queue(complexCC))

    // Tuples
    checkDataset(Seq(Seq(1) -> Seq(2)).toDS(), Seq(1) -> Seq(2))
    checkDataset(Seq(List(1) -> Queue(2)).toDS(), List(1) -> Queue(2))
    checkDataset(Seq(List(Seq("test1") -> List(Queue("test2")))).toDS(),
      List(Seq("test1") -> List(Queue("test2"))))

    // Complex
    checkDataset(Seq(ListCC(List(1)) -> Queue("test" -> SeqCC(Seq(2)))).toDS(),
      ListCC(List(1)) -> Queue("test" -> SeqCC(Seq(2))))
  }

  test("package objects") {
    import packageobject._
    checkDataset(Seq(PackageClass(1)).toDS(), PackageClass(1))
  }

}
