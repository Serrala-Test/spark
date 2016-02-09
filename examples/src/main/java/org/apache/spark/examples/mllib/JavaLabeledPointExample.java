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

// $example on$
import org.apache.spark.mllib.linalg.Vectors;
import org.apache.spark.mllib.regression.LabeledPoint;
// $example off$

public class JavaLabeledPointExample {
    public static void main(String[] args) {

        // $example on$
        // Create a labeled point with a positive label and a dense feature vector.
        LabeledPoint pos = new LabeledPoint(1.0, Vectors.dense(1.0, 0.0, 3.0));

        // Create a labeled point with a negative label and a sparse feature vector.
        LabeledPoint neg = new LabeledPoint(
                0.0, Vectors.sparse(3, new int[] {0, 2}, new double[] {1.0, 3.0}));

        // $example off$

    }
}
