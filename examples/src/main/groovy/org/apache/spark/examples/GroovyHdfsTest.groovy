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
 *
 * Author: Constantin Ahlmann-Eltze
 * Affiliation: Parallel and Distributed Systems Group, Heidelberg
 * University, http://pvs.ifi.uni-heidelberg.de/home/
 * Date: July 4th, 2014
 */

package org.apache.spark.examples

import org.apache.spark.SparkConf
import org.apache.spark.api.java.JavaSparkContext


class GroovyHdfsTest {

    static void main(String[] args){
        def sparkConf = new SparkConf().setAppName("Groovy HdfsTest")
        def sc = new JavaSparkContext(sparkConf)
        def file = sc.textFile(args[0])
        def mapped = file.map({it.size()}).cache()

        for(iter in 1..10){
            def start = System.currentTimeMillis()
            for (x in mapped.collect()){ x + 2 }
            def end = System.currentTimeMillis()
            println("Iteration " + iter + " took " + (end-start) + " ms")
        }
        sc.stop()
    }

}
