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

package org.apache.spark.sql.execution.datasources.parquet

import java.nio.ByteBuffer
import java.util.{List => JList, Map => JMap}

import scala.collection.JavaConversions._

import org.apache.avro.Schema
import org.apache.avro.generic.IndexedRecord
import org.apache.hadoop.fs.Path
import org.apache.parquet.avro.AvroParquetWriter

import org.apache.spark.sql.Row
import org.apache.spark.sql.execution.datasources.parquet.test.avro._
import org.apache.spark.sql.test.SharedSQLContext

class ParquetAvroCompatibilitySuite extends ParquetCompatibilityTest with SharedSQLContext {
  import ParquetCompatibilityTest._

  private def withWriter[T <: IndexedRecord]
      (path: String, schema: Schema)
      (f: AvroParquetWriter[T] => Unit): Unit = {
    val writer = new AvroParquetWriter[T](new Path(path), schema)
    try f(writer) finally writer.close()
  }

  test("Read Parquet file generated by parquet-avro") {
    withTempPath { dir =>
      val path = dir.getCanonicalPath

      withWriter[ParquetAvroCompat](path, ParquetAvroCompat.getClassSchema) { writer =>
        (0 until 10).foreach(i => writer.write(makeParquetAvroCompat(i)))
      }

      logInfo(
        s"""Schema of the Parquet file written by parquet-avro:
           |${readParquetSchema(path)}
       """.stripMargin)

      checkAnswer(sqlContext.read.parquet(path), (0 until 10).map { i =>
        def nullable[T <: AnyRef]: ( => T) => T = makeNullable[T](i)

        Row(
          i % 2 == 0,
          i,
          i.toLong * 10,
          i.toFloat + 0.1f,
          i.toDouble + 0.2d,
          s"val_$i".getBytes,
          s"val_$i",

          nullable(i % 2 == 0: java.lang.Boolean),
          nullable(i: Integer),
          nullable(i.toLong: java.lang.Long),
          nullable(i.toFloat + 0.1f: java.lang.Float),
          nullable(i.toDouble + 0.2d: java.lang.Double),
          nullable(s"val_$i".getBytes),
          nullable(s"val_$i"),

          Seq.tabulate(3)(n => s"arr_${i + n}"),
          Seq.tabulate(3)(n => n.toString -> (i + n: Integer)).toMap,
          Seq.tabulate(3) { n =>
            (i + n).toString -> Seq.tabulate(3) { m =>
              Row(Seq.tabulate(3)(j => i + j + m), s"val_${i + m}")
            }
          }.toMap)
      })
    }
  }

  def makeParquetAvroCompat(i: Int): ParquetAvroCompat = {
    def nullable[T <: AnyRef] = makeNullable[T](i) _

    def makeComplexColumn(i: Int): JMap[String, JList[Nested]] = {
      mapAsJavaMap(Seq.tabulate(3) { n =>
        (i + n).toString -> seqAsJavaList(Seq.tabulate(3) { m =>
          Nested
            .newBuilder()
            .setNestedIntsColumn(seqAsJavaList(Seq.tabulate(3)(j => i + j + m)))
            .setNestedStringColumn(s"val_${i + m}")
            .build()
        })
      }.toMap)
    }

    ParquetAvroCompat
      .newBuilder()
      .setBoolColumn(i % 2 == 0)
      .setIntColumn(i)
      .setLongColumn(i.toLong * 10)
      .setFloatColumn(i.toFloat + 0.1f)
      .setDoubleColumn(i.toDouble + 0.2d)
      .setBinaryColumn(ByteBuffer.wrap(s"val_$i".getBytes))
      .setStringColumn(s"val_$i")

      .setMaybeBoolColumn(nullable(i % 2 == 0: java.lang.Boolean))
      .setMaybeIntColumn(nullable(i: Integer))
      .setMaybeLongColumn(nullable(i.toLong: java.lang.Long))
      .setMaybeFloatColumn(nullable(i.toFloat + 0.1f: java.lang.Float))
      .setMaybeDoubleColumn(nullable(i.toDouble + 0.2d: java.lang.Double))
      .setMaybeBinaryColumn(nullable(ByteBuffer.wrap(s"val_$i".getBytes)))
      .setMaybeStringColumn(nullable(s"val_$i"))

      .setStringsColumn(Seq.tabulate(3)(n => s"arr_${i + n}"))
      .setStringToIntColumn(
        mapAsJavaMap(Seq.tabulate(3)(n => n.toString -> (i + n: Integer)).toMap))
      .setComplexColumn(makeComplexColumn(i))

      .build()
  }

  test("SPARK-9407 Don't push down predicates involving Parquet ENUM columns") {
    import testImplicits._

    withTempPath { dir =>
      val path = dir.getCanonicalPath

      withWriter[ParquetEnum](path, ParquetEnum.getClassSchema) { writer =>
        (0 until 4).foreach { i =>
          writer.write(ParquetEnum.newBuilder().setSuit(Suit.values.apply(i)).build())
        }
      }

      checkAnswer(sqlContext.read.parquet(path).filter('suit === "SPADES"), Row("SPADES"))
    }
  }
}
