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

package org.apache.spark.ml.source.image

/**
 * `image` package implements Spark SQL data source API for loading IMAGE data as `DataFrame`.
 * The loaded `DataFrame` has one `StructType` column: `image`.
 * The schema of the `image` column is:
 *  - origin: String (represent the origin of image. If loaded from file, then it is file path)
 *  - height: Int (height of image)
 *  - width: Int (width of image)
 *  - nChannels: Int (number of image channels)
 *  - mode: Int (OpenCV-compatible type)
 *  - data: BinaryType (Image bytes in OpenCV-compatible order: row-wise BGR in most cases)
 *
 * To use IMAGE data source, you need to set "image" as the format in `DataFrameReader` and
 * optionally specify the datasource options, for example:
 * {{{
 *   // Scala
 *   val df = spark.read.format("image")
 *     .option("dropImageFailures", "true")
 *     .load("data/mllib/images/imagesWithPartitions")
 *
 *   // Java
 *   Dataset<Row> df = spark.read().format("image")
 *     .option("dropImageFailures", "true")
 *     .load("data/mllib/images/imagesWithPartitions");
 * }}}
 *
 * IMAGE data source supports the following options:
 *  - "dropImageFailures": Whether to drop the files that are not valid images from the result.
 *
 * @note This IMAGE data source does not support "write".
 *
 * @note This class is public for documentation purpose. Please don't use this class directly.
 * Rather, use the data source API as illustrated above.
 */
class ImageDataSource private() {}
