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

package org.apache.spark.serializer

import org.apache.spark.util.Utils

import com.esotericsoftware.kryo.Kryo
import org.scalatest.FunSuite

import org.apache.spark.{LocalSparkContext, SparkConf, SparkContext, SparkEnv, TestUtils}
import org.apache.spark.serializer.KryoDistributedTest._
import org.apache.sparktest.TestTags.IntegrationTest

class KryoSerializerDistributedSuite extends FunSuite {

  test("kryo objects are serialised consistently in different processes", IntegrationTest) {
    val conf = new SparkConf(false)
    conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    conf.set("spark.kryo.registrator", classOf[AppJarRegistrator].getName)
    conf.set("spark.task.maxFailures", "1")

    val jar = TestUtils.createJarWithClasses(List(AppJarRegistrator.customClassName))
    conf.setJars(List(jar.getPath))

    val sc = new SparkContext("local-cluster[2,1,512]", "test", conf)
    val original = Thread.currentThread.getContextClassLoader
    val loader = new java.net.URLClassLoader(Array(jar), Utils.getContextOrSparkClassLoader)
    SparkEnv.get.serializer.setDefaultClassLoader(loader)

    val cachedRDD = sc.parallelize((0 until 10).map((_, new MyCustomClass)), 3).cache()

    // Randomly mix the keys so that the join below will require a shuffle with each partition
    // sending data to multiple other partitions.
    val shuffledRDD = cachedRDD.map { case (i, o) => (i * i * i - 10 * i * i, o)}

    // Join the two RDDs, and force evaluation
    assert(shuffledRDD.join(cachedRDD).collect().size == 1)

    LocalSparkContext.stop(sc)
  }
}

object KryoDistributedTest {
  class MyCustomClass

  class AppJarRegistrator extends KryoRegistrator {
    override def registerClasses(k: Kryo) {
      val classLoader = Thread.currentThread.getContextClassLoader
      k.register(Class.forName(AppJarRegistrator.customClassName, true, classLoader))
    }
  }

  object AppJarRegistrator {
    val customClassName = "KryoSerializerDistributedSuiteCustomClass"
  }
}
