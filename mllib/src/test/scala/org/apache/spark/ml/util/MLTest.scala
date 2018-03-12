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

package org.apache.spark.ml.util

import java.io.File

import org.scalatest.Suite

import org.apache.spark.SparkContext
import org.apache.spark.ml.{PredictionModel, Transformer}
import org.apache.spark.ml.linalg.Vector
import org.apache.spark.sql.{DataFrame, Dataset, Encoder, Row}
import org.apache.spark.sql.execution.streaming.MemoryStream
import org.apache.spark.sql.streaming.StreamTest
import org.apache.spark.sql.test.TestSparkSession
import org.apache.spark.util.Utils

trait MLTest extends StreamTest with TempDirectory { self: Suite =>

  @transient var sc: SparkContext = _
  @transient var checkpointDir: String = _

  protected override def createSparkSession: TestSparkSession = {
    new TestSparkSession(new SparkContext("local[2]", "MLlibUnitTest", sparkConf))
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    sc = spark.sparkContext
    checkpointDir = Utils.createDirectory(tempDir.getCanonicalPath, "checkpoints").toString
    sc.setCheckpointDir(checkpointDir)
  }

  override def afterAll() {
    try {
      Utils.deleteRecursively(new File(checkpointDir))
    } finally {
      super.afterAll()
    }
  }

  private[util] def testTransformerOnStreamData[A : Encoder](
      dataframe: DataFrame,
      transformer: Transformer,
      firstResultCol: String,
      otherResultCols: String*)
      (globalCheckFunction: Seq[Row] => Unit): Unit = {

    val columnNames = dataframe.schema.fieldNames
    val stream = MemoryStream[A]
    val streamDF = stream.toDS().toDF(columnNames: _*)

    val data = dataframe.as[A].collect()

    val streamOutput = transformer.transform(streamDF)
      .select(firstResultCol, otherResultCols: _*)
    testStream(streamOutput) (
      AddData(stream, data: _*),
      CheckAnswer(globalCheckFunction)
    )
  }

  private[util] def testTransformerOnDF(
      dataframe: DataFrame,
      transformer: Transformer,
      firstResultCol: String,
      otherResultCols: String*)
      (globalCheckFunction: Seq[Row] => Unit): Unit = {
    val dfOutput = transformer.transform(dataframe)
    val outputs = dfOutput.select(firstResultCol, otherResultCols: _*).collect()
    globalCheckFunction(outputs)
  }

  def testTransformer[A : Encoder](
      dataframe: DataFrame,
      transformer: Transformer,
      firstResultCol: String,
      otherResultCols: String*)
      (checkFunction: Row => Unit): Unit = {
    testTransformerByGlobalCheckFunc(
      dataframe,
      transformer,
      firstResultCol,
      otherResultCols: _*) { rows: Seq[Row] => rows.foreach(checkFunction(_)) }
  }

  def testTransformerByGlobalCheckFunc[A : Encoder](
      dataframe: DataFrame,
      transformer: Transformer,
      firstResultCol: String,
      otherResultCols: String*)
      (globalCheckFunction: Seq[Row] => Unit): Unit = {
    testTransformerOnStreamData(dataframe, transformer, firstResultCol,
      otherResultCols: _*)(globalCheckFunction)
    testTransformerOnDF(dataframe, transformer, firstResultCol,
      otherResultCols: _*)(globalCheckFunction)
  }

  def testPredictorModelSinglePrediction(model: PredictionModel[Vector, _],
      dataset: Dataset[_]): Unit = {

    model.transform(dataset).select(model.getFeaturesCol, model.getPredictionCol)
      .collect().foreach {
      case Row(features: Vector, prediction: Double) =>
        assert(prediction === model.predict(features))
    }
  }
}
