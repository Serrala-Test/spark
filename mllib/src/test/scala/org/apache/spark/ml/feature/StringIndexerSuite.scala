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

package org.apache.spark.ml.feature

import org.apache.spark.{SparkException, SparkFunSuite}
import org.apache.spark.ml.attribute.{Attribute, NominalAttribute}
import org.apache.spark.ml.param.ParamsSuite
import org.apache.spark.ml.util.{DefaultReadWriteTest, MLTestingUtils}
import org.apache.spark.mllib.util.MLlibTestSparkContext
import org.apache.spark.sql.Row
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.types.{DoubleType, StringType, StructField, StructType}

class StringIndexerSuite
  extends SparkFunSuite with MLlibTestSparkContext with DefaultReadWriteTest {

  import testImplicits._

  test("params") {
    ParamsSuite.checkParams(new StringIndexer)
    val model = new StringIndexerModel("indexer", Array(Array("a", "b")))
    val modelWithoutUid = new StringIndexerModel(Array("a", "b"))
    ParamsSuite.checkParams(model)
    ParamsSuite.checkParams(modelWithoutUid)
  }

  test("params: input/output columns") {
    val stringIndexerSingleCol = new StringIndexer()
      .setInputCol("in").setOutputCol("out")
    val inOutCols1 = stringIndexerSingleCol.getInOutCols()
    assert(inOutCols1._1 === Array("in"))
    assert(inOutCols1._2 === Array("out"))

    val stringIndexerMultiCol = new StringIndexer()
      .setInputCols(Array("in1", "in2")).setOutputCols(Array("out1", "out2"))
    val inOutCols2 = stringIndexerMultiCol.getInOutCols()
    assert(inOutCols2._1 === Array("in1", "in2"))
    assert(inOutCols2._2 === Array("out1", "out2"))

    intercept[IllegalArgumentException] {
      new StringIndexer().setInputCol("in").setOutputCols(Array("out1", "out2")).getInOutCols()
    }
    intercept[IllegalArgumentException] {
      new StringIndexer().setInputCols(Array("in1", "in2")).setOutputCol("out1").getInOutCols()
    }
    intercept[IllegalArgumentException] {
      new StringIndexer().setInputCols(Array("in1", "in2"))
        .setOutputCols(Array("out1", "out2", "out3"))
        .getInOutCols()
    }
  }

  test("StringIndexer") {
    val data = Seq((0, "a"), (1, "b"), (2, "c"), (3, "a"), (4, "a"), (5, "c"))
    val df = data.toDF("id", "label")
    val indexer = new StringIndexer()
      .setInputCol("label")
      .setOutputCol("labelIndex")
    val indexerModel = indexer.fit(df)

    MLTestingUtils.checkCopyAndUids(indexer, indexerModel)

    val transformed = indexerModel.transform(df)
    val attr = Attribute.fromStructField(transformed.schema("labelIndex"))
      .asInstanceOf[NominalAttribute]
    assert(attr.values.get === Array("a", "c", "b"))
    val output = transformed.select("id", "labelIndex").rdd.map { r =>
      (r.getInt(0), r.getDouble(1))
    }.collect().toSet
    // a -> 0, b -> 2, c -> 1
    val expected = Set((0, 0.0), (1, 2.0), (2, 1.0), (3, 0.0), (4, 0.0), (5, 1.0))
    assert(output === expected)
  }

  test("StringIndexerUnseen") {
    val data = Seq((0, "a"), (1, "b"), (4, "b"))
    val data2 = Seq((0, "a"), (1, "b"), (2, "c"), (3, "d"))
    val df = data.toDF("id", "label")
    val df2 = data2.toDF("id", "label")
    val indexer = new StringIndexer()
      .setInputCol("label")
      .setOutputCol("labelIndex")
      .fit(df)
    // Verify we throw by default with unseen values
    intercept[SparkException] {
      indexer.transform(df2).collect()
    }

    indexer.setHandleInvalid("skip")
    // Verify that we skip the c record
    val transformedSkip = indexer.transform(df2)
    val attrSkip = Attribute.fromStructField(transformedSkip.schema("labelIndex"))
      .asInstanceOf[NominalAttribute]
    assert(attrSkip.values.get === Array("b", "a"))
    val outputSkip = transformedSkip.select("id", "labelIndex").rdd.map { r =>
      (r.getInt(0), r.getDouble(1))
    }.collect().toSet
    // a -> 1, b -> 0
    val expectedSkip = Set((0, 1.0), (1, 0.0))
    assert(outputSkip === expectedSkip)

    indexer.setHandleInvalid("keep")
    // Verify that we keep the unseen records
    val transformedKeep = indexer.transform(df2)
    val attrKeep = Attribute.fromStructField(transformedKeep.schema("labelIndex"))
      .asInstanceOf[NominalAttribute]
    assert(attrKeep.values.get === Array("b", "a", "__unknown"))
    val outputKeep = transformedKeep.select("id", "labelIndex").rdd.map { r =>
      (r.getInt(0), r.getDouble(1))
    }.collect().toSet
    // a -> 1, b -> 0, c -> 2, d -> 3
    val expectedKeep = Set((0, 1.0), (1, 0.0), (2, 2.0), (3, 2.0))
    assert(outputKeep === expectedKeep)
  }

  test("StringIndexer with a numeric input column") {
    val data = Seq((0, 100), (1, 200), (2, 300), (3, 100), (4, 100), (5, 300))
    val df = data.toDF("id", "label")
    val indexer = new StringIndexer()
      .setInputCol("label")
      .setOutputCol("labelIndex")
      .fit(df)
    val transformed = indexer.transform(df)
    val attr = Attribute.fromStructField(transformed.schema("labelIndex"))
      .asInstanceOf[NominalAttribute]
    assert(attr.values.get === Array("100", "300", "200"))
    val output = transformed.select("id", "labelIndex").rdd.map { r =>
      (r.getInt(0), r.getDouble(1))
    }.collect().toSet
    // 100 -> 0, 200 -> 2, 300 -> 1
    val expected = Set((0, 0.0), (1, 2.0), (2, 1.0), (3, 0.0), (4, 0.0), (5, 1.0))
    assert(output === expected)
  }

  test("StringIndexer with NULLs") {
    val data: Seq[(Int, String)] = Seq((0, "a"), (1, "b"), (2, "b"), (3, null))
    val data2: Seq[(Int, String)] = Seq((0, "a"), (1, "b"), (3, null))
    val df = data.toDF("id", "label")
    val df2 = data2.toDF("id", "label")

    val indexer = new StringIndexer()
      .setInputCol("label")
      .setOutputCol("labelIndex")

    withClue("StringIndexer should throw error when setHandleInvalid=error " +
      "when given NULL values") {
      intercept[SparkException] {
        indexer.setHandleInvalid("error")
        indexer.fit(df).transform(df2).collect()
      }
    }

    indexer.setHandleInvalid("skip")
    val transformedSkip = indexer.fit(df).transform(df2)
    val attrSkip = Attribute
      .fromStructField(transformedSkip.schema("labelIndex"))
      .asInstanceOf[NominalAttribute]
    assert(attrSkip.values.get === Array("b", "a"))
    val outputSkip = transformedSkip.select("id", "labelIndex").rdd.map { r =>
      (r.getInt(0), r.getDouble(1))
    }.collect().toSet
    // a -> 1, b -> 0
    val expectedSkip = Set((0, 1.0), (1, 0.0))
    assert(outputSkip === expectedSkip)

    indexer.setHandleInvalid("keep")
    val transformedKeep = indexer.fit(df).transform(df2)
    val attrKeep = Attribute
      .fromStructField(transformedKeep.schema("labelIndex"))
      .asInstanceOf[NominalAttribute]
    assert(attrKeep.values.get === Array("b", "a", "__unknown"))
    val outputKeep = transformedKeep.select("id", "labelIndex").rdd.map { r =>
      (r.getInt(0), r.getDouble(1))
    }.collect().toSet
    // a -> 1, b -> 0, null -> 2
    val expectedKeep = Set((0, 1.0), (1, 0.0), (3, 2.0))
    assert(outputKeep === expectedKeep)
  }

  test("StringIndexerModel should keep silent if the input column does not exist.") {
    val indexerModel = new StringIndexerModel("indexer", Array(Array("a", "b", "c")))
      .setInputCol("label")
      .setOutputCol("labelIndex")
    val df = spark.range(0L, 10L).toDF()
    assert(indexerModel.transform(df).collect().toSet === df.collect().toSet)
  }

  test("StringIndexerModel can't overwrite output column") {
    val df = Seq((1, 2), (3, 4)).toDF("input", "output")
    intercept[IllegalArgumentException] {
      new StringIndexer()
        .setInputCol("input")
        .setOutputCol("output")
        .fit(df)
    }

    val indexer = new StringIndexer()
      .setInputCol("input")
      .setOutputCol("indexedInput")
      .fit(df)

    intercept[IllegalArgumentException] {
      indexer.setOutputCol("output").transform(df)
    }
  }

  test("StringIndexer read/write") {
    val t = new StringIndexer()
      .setInputCol("myInputCol")
      .setOutputCol("myOutputCol")
      .setHandleInvalid("skip")
    testDefaultReadWrite(t)
  }

  test("StringIndexerModel read/write") {
    val instance = new StringIndexerModel("myStringIndexerModel", Array(Array("a", "b", "c")))
      .setInputCol("myInputCol")
      .setOutputCol("myOutputCol")
      .setHandleInvalid("skip")
    val newInstance = testDefaultReadWrite(instance)
    assert(newInstance.labels === instance.labels)
  }

  test("IndexToString params") {
    val idxToStr = new IndexToString()
    ParamsSuite.checkParams(idxToStr)
  }

  test("IndexToString.transform") {
    val labels = Array("a", "b", "c")
    val df0 = Seq((0, "a"), (1, "b"), (2, "c"), (0, "a")).toDF("index", "expected")

    val idxToStr0 = new IndexToString()
      .setInputCol("index")
      .setOutputCol("actual")
      .setLabels(labels)
    idxToStr0.transform(df0).select("actual", "expected").collect().foreach {
      case Row(actual, expected) =>
        assert(actual === expected)
    }

    val attr = NominalAttribute.defaultAttr.withValues(labels)
    val df1 = df0.select(col("index").as("indexWithAttr", attr.toMetadata()), col("expected"))

    val idxToStr1 = new IndexToString()
      .setInputCol("indexWithAttr")
      .setOutputCol("actual")
    idxToStr1.transform(df1).select("actual", "expected").collect().foreach {
      case Row(actual, expected) =>
        assert(actual === expected)
    }
  }

  test("StringIndexer, IndexToString are inverses") {
    val data = Seq((0, "a"), (1, "b"), (2, "c"), (3, "a"), (4, "a"), (5, "c"))
    val df = data.toDF("id", "label")
    val indexer = new StringIndexer()
      .setInputCol("label")
      .setOutputCol("labelIndex")
      .fit(df)
    val transformed = indexer.transform(df)
    val idx2str = new IndexToString()
      .setInputCol("labelIndex")
      .setOutputCol("sameLabel")
      .setLabels(indexer.labels)
    idx2str.transform(transformed).select("label", "sameLabel").collect().foreach {
      case Row(a: String, b: String) =>
        assert(a === b)
    }
  }

  test("IndexToString.transformSchema (SPARK-10573)") {
    val idxToStr = new IndexToString().setInputCol("input").setOutputCol("output")
    val inSchema = StructType(Seq(StructField("input", DoubleType)))
    val outSchema = idxToStr.transformSchema(inSchema)
    assert(outSchema("output").dataType === StringType)
  }

  test("IndexToString read/write") {
    val t = new IndexToString()
      .setInputCol("myInputCol")
      .setOutputCol("myOutputCol")
      .setLabels(Array("a", "b", "c"))
    testDefaultReadWrite(t)
  }

  test("SPARK 18698: construct IndexToString with custom uid") {
    val uid = "customUID"
    val t = new IndexToString(uid)
    assert(t.uid == uid)
  }

  test("StringIndexer metadata") {
    val data = Seq((0, "a"), (1, "b"), (2, "c"), (3, "a"), (4, "a"), (5, "c"))
    val df = data.toDF("id", "label")
    val indexer = new StringIndexer()
      .setInputCol("label")
      .setOutputCol("labelIndex")
      .fit(df)
    val transformed = indexer.transform(df)
    val attrs =
      NominalAttribute.decodeStructField(transformed.schema("labelIndex"), preserveName = true)
    assert(attrs.name.nonEmpty && attrs.name.get === "labelIndex")
  }

  test("StringIndexer order types") {
    val data = Seq((0, "b"), (1, "b"), (2, "c"), (3, "a"), (4, "a"), (5, "b"))
    val df = data.toDF("id", "label")
    val indexer = new StringIndexer()
      .setInputCol("label")
      .setOutputCol("labelIndex")

    val expected = Seq(Set((0, 0.0), (1, 0.0), (2, 2.0), (3, 1.0), (4, 1.0), (5, 0.0)),
      Set((0, 2.0), (1, 2.0), (2, 0.0), (3, 1.0), (4, 1.0), (5, 2.0)),
      Set((0, 1.0), (1, 1.0), (2, 0.0), (3, 2.0), (4, 2.0), (5, 1.0)),
      Set((0, 1.0), (1, 1.0), (2, 2.0), (3, 0.0), (4, 0.0), (5, 1.0)))

    var idx = 0
    for (orderType <- StringIndexer.supportedStringOrderType) {
      val transformed = indexer.setStringOrderType(orderType).fit(df).transform(df)
      val output = transformed.select("id", "labelIndex").rdd.map { r =>
        (r.getInt(0), r.getDouble(1))
      }.collect().toSet
      assert(output === expected(idx))
      idx += 1
    }
  }

  test("SPARK-22446: StringIndexerModel's indexer UDF should not apply on filtered data") {
    val df = List(
         ("A", "London", "StrA"),
         ("B", "Bristol", null),
         ("C", "New York", "StrC")).toDF("ID", "CITY", "CONTENT")

    val dfNoBristol = df.filter($"CONTENT".isNotNull)

    val model = new StringIndexer()
      .setInputCol("CITY")
      .setOutputCol("CITYIndexed")
      .fit(dfNoBristol)

    val dfWithIndex = model.transform(dfNoBristol)
    assert(dfWithIndex.filter($"CITYIndexed" === 1.0).count == 1)
  }

  test("StringIndexer multiple input columns") {
    val data = Seq(
      Row("a", 0.0, "e", 1.0),
      Row("b", 2.0, "f", 0.0),
      Row("c", 1.0, "e", 1.0),
      Row("a", 0.0, "f", 0.0),
      Row("a", 0.0, "f", 0.0),
      Row("c", 1.0, "f", 0.0))

    val schema = StructType(Array(
        StructField("label1", StringType),
        StructField("expected1", DoubleType),
        StructField("label2", StringType),
        StructField("expected2", DoubleType)))

    val df = spark.createDataFrame(sc.parallelize(data), schema)

    val indexer = new StringIndexer()
      .setInputCols(Array("label1", "label2"))
      .setOutputCols(Array("labelIndex1", "labelIndex2"))
    val indexerModel = indexer.fit(df)

    MLTestingUtils.checkCopyAndUids(indexer, indexerModel)

    val transformed = indexerModel.transform(df)

    // Checks output attribute correctness.
    val attr1 = Attribute.fromStructField(transformed.schema("labelIndex1"))
      .asInstanceOf[NominalAttribute]
    assert(attr1.values.get === Array("a", "c", "b"))
    val attr2 = Attribute.fromStructField(transformed.schema("labelIndex2"))
      .asInstanceOf[NominalAttribute]
    assert(attr2.values.get === Array("f", "e"))

    transformed.select("labelIndex1", "expected1").rdd.map { r =>
     (r.getDouble(0), r.getDouble(1))
    }.collect().foreach { case (index, expected) =>
      assert(index == expected)
    }

    transformed.select("labelIndex2", "expected2").rdd.map { r =>
     (r.getDouble(0), r.getDouble(1))
    }.collect().foreach { case (index, expected) =>
      assert(index == expected)
    }
  }
}
