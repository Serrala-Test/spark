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

package org.apache.spark.examples.mllib;

import java.util.Arrays;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.mllib.linalg.Vectors;
// $example on$
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.mllib.linalg.distributed.IndexedRow;
import org.apache.spark.mllib.linalg.distributed.IndexedRowMatrix;
import org.apache.spark.mllib.linalg.distributed.RowMatrix;
// $example off$

public class JavaIndexedRowMatrixExample {
  public static void main(String[] args) {

    SparkConf conf = new SparkConf().setAppName("JavaIndexedRowMatrixExample");
    JavaSparkContext jsc = new JavaSparkContext(conf);

    IndexedRow r1 = new IndexedRow(1, Vectors.dense(1.0, 10.0, 100.0));
    IndexedRow r2 = new IndexedRow(2, Vectors.dense(2.0, 20.0, 200.0));
    IndexedRow r3 = new IndexedRow(3, Vectors.dense(5.0, 33.0, 366.0));

    // $example on$
    // a JavaRDD of indexed rows
    JavaRDD<IndexedRow> rows = jsc.parallelize(Arrays.asList(r1, r2, r3));

    // Create an IndexedRowMatrix from a JavaRDD<IndexedRow>.
    IndexedRowMatrix mat = new IndexedRowMatrix(rows.rdd());

    // Get its size.
    long m = mat.numRows();
    long n = mat.numCols();

    // Drop its row indices.
    RowMatrix rowMat = mat.toRowMatrix();
    // $example off$

    jsc.stop();
  }
}
