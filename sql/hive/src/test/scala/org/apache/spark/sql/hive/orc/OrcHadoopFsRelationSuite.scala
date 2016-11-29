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

package org.apache.spark.sql.hive.orc

import java.io.File

import org.apache.hadoop.fs.{FileSystem, Path}

import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.sql.{Row, SaveMode}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.sources.HadoopFsRelationTest
import org.apache.spark.sql.types._

class OrcHadoopFsRelationSuite extends HadoopFsRelationTest {
  import testImplicits._

  override val dataSourceName: String = classOf[OrcFileFormat].getCanonicalName

  // ORC does not play well with NullType and UDT.
  override protected def supportsDataType(dataType: DataType): Boolean = dataType match {
    case _: NullType => false
    case _: CalendarIntervalType => false
    case _: UserDefinedType[_] => false
    case _ => true
  }

  test("save()/load() - partitioned table - simple queries - partition columns in data") {
    withTempDir { file =>
      val basePath = new Path(file.getCanonicalPath)
      val fs = basePath.getFileSystem(SparkHadoopUtil.get.conf)
      val qualifiedBasePath = fs.makeQualified(basePath)

      for (p1 <- 1 to 2) {
        val p1Dir = new Path(qualifiedBasePath, s"p1=$p1")
        val df = Seq("foo", "bar").flatMap { p2 =>
          for (i <- 1 to 3) yield (i, s"val_$i", p2)
        }.toDF("a", "b", "p2")

        df.write.mode(SaveMode.Append).partitionBy("p2").orc(p1Dir.toString)
      }

      checkQueries(spark.read.options(
        Map("path" -> file.getCanonicalPath)).format(dataSourceName).load())
    }
  }

  test("SPARK-12218: 'Not' is included in ORC filter pushdown") {
    import testImplicits._

    withSQLConf(SQLConf.ORC_FILTER_PUSHDOWN_ENABLED.key -> "true") {
      withTempPath { dir =>
        val path = s"${dir.getCanonicalPath}/table1"
        (1 to 5).map(i => (i, (i % 2).toString)).toDF("a", "b").write.orc(path)

        checkAnswer(
          spark.read.orc(path).where("not (a = 2) or not(b in ('1'))"),
          (1 to 5).map(i => Row(i, (i % 2).toString)))

        checkAnswer(
          spark.read.orc(path).where("not (a = 2 and b in ('1'))"),
          (1 to 5).map(i => Row(i, (i % 2).toString)))
      }
    }
  }

  test("SPARK-13543: Support for specifying compression codec for ORC via option()") {
    withTempPath { dir =>
      val path = s"${dir.getCanonicalPath}/table1"
      val df = (1 to 5).map(i => (i, (i % 2).toString)).toDF("a", "b")
      df.write
        .option("compression", "ZlIb")
        .orc(path)

      // Check if this is compressed as ZLIB.
      val conf = spark.sessionState.newHadoopConf()
      val fs = FileSystem.getLocal(conf)
      val maybeOrcFile = new File(path).listFiles().find(_.getName.endsWith(".zlib.orc"))
      assert(maybeOrcFile.isDefined)
      val orcFilePath = maybeOrcFile.get.toPath.toString
      val expectedCompressionKind =
        OrcFileOperator.getFileReader(orcFilePath).get.getCompression
      assert("ZLIB" === expectedCompressionKind.name())

      val copyDf = spark
        .read
        .orc(path)
      checkAnswer(df, copyDf)
    }
  }

  test("Default compression codec is snappy for ORC compression") {
    withTempPath { file =>
      spark.range(0, 10).write
        .orc(file.getCanonicalPath)
      val expectedCompressionKind =
        OrcFileOperator.getFileReader(file.getCanonicalPath).get.getCompression
      assert("SNAPPY" === expectedCompressionKind.name())
    }
  }
}
