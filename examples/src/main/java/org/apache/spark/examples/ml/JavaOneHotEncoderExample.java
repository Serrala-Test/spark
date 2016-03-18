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

package org.apache.spark.examples.ml;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.SQLContext;

// $example on$
import java.util.Arrays;

import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.ml.feature.OneHotEncoder;
import org.apache.spark.ml.feature.StringIndexer;
import org.apache.spark.ml.feature.StringIndexerModel;
<<<<<<< HEAD
import org.apache.spark.sql.Dataset;
=======
import org.apache.spark.sql.DataFrame;
>>>>>>> 022e06d18471bf54954846c815c8a3666aef9fc3
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.Metadata;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
// $example off$

public class JavaOneHotEncoderExample {
  public static void main(String[] args) {
    SparkConf conf = new SparkConf().setAppName("JavaOneHotEncoderExample");
    JavaSparkContext jsc = new JavaSparkContext(conf);
    SQLContext sqlContext = new SQLContext(jsc);

    // $example on$
    JavaRDD<Row> jrdd = jsc.parallelize(Arrays.asList(
      RowFactory.create(0, "a"),
      RowFactory.create(1, "b"),
      RowFactory.create(2, "c"),
      RowFactory.create(3, "a"),
      RowFactory.create(4, "a"),
      RowFactory.create(5, "c")
    ));

    StructType schema = new StructType(new StructField[]{
      new StructField("id", DataTypes.DoubleType, false, Metadata.empty()),
      new StructField("category", DataTypes.StringType, false, Metadata.empty())
    });

<<<<<<< HEAD
    Dataset<Row> df = sqlContext.createDataFrame(jrdd, schema);
=======
    DataFrame df = sqlContext.createDataFrame(jrdd, schema);
>>>>>>> 022e06d18471bf54954846c815c8a3666aef9fc3

    StringIndexerModel indexer = new StringIndexer()
      .setInputCol("category")
      .setOutputCol("categoryIndex")
      .fit(df);
<<<<<<< HEAD
    Dataset<Row> indexed = indexer.transform(df);
=======
    DataFrame indexed = indexer.transform(df);
>>>>>>> 022e06d18471bf54954846c815c8a3666aef9fc3

    OneHotEncoder encoder = new OneHotEncoder()
      .setInputCol("categoryIndex")
      .setOutputCol("categoryVec");
<<<<<<< HEAD
    Dataset<Row> encoded = encoder.transform(indexed);
=======
    DataFrame encoded = encoder.transform(indexed);
>>>>>>> 022e06d18471bf54954846c815c8a3666aef9fc3
    encoded.select("id", "categoryVec").show();
    // $example off$
    jsc.stop();
  }
}

