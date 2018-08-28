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

package org.apache.spark;

import org.apache.spark.api.java.JavaSparkContext;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

// Tests loading plugins into executors
public class ExecutorPluginSuite {
  // Static value modified by testing plugin to ensure plugin loaded correctly.
  public static int numSuccessfulPlugins = 0;
  private JavaSparkContext sc;

  private String EXECUTOR_PLUGIN_CONF_NAME = "spark.executor.plugins";
  private String testPluginName = "org.apache.spark.ExecutorPluginSuite$TestExecutorPlugin";

  @Before
  public void setUp() {
    sc = null;
    numSuccessfulPlugins = 0;
  }

  private SparkConf initializeSparkConf(String pluginNames) {
    return new SparkConf()
        .setMaster("local")
        .setAppName("test")
        .set(EXECUTOR_PLUGIN_CONF_NAME, pluginNames);
  }

  @Test
  public void testPluginClassDoesNotExist() {
    SparkConf conf = initializeSparkConf("nonexistant.plugin");
    try {
      sc = new JavaSparkContext(conf);
    } catch (Exception e) {
      // We cannot catch ClassNotFoundException directly because Java doesn't think it'll be thrown
      assertTrue(e.toString().startsWith("java.lang.ClassNotFoundException"));
    } finally {
      if (sc != null) {
        sc.stop();
        sc = null;
      }
    }
  }

  @Test
  public void testAddPlugin() throws InterruptedException {
    // Load the sample TestExecutorPlugin, which will change the value of pluginExecutionSuccessful
    SparkConf conf = initializeSparkConf(testPluginName);

    try {
      sc = new JavaSparkContext(conf);
    } catch (Exception e) {
      fail("Failed to start SparkContext with exception " + e.toString());
    }

    // Wait a moment since plugins run on separate threads
    Thread.sleep(500);

    assertEquals(1, numSuccessfulPlugins);

    if (sc != null) {
      sc.stop();
      sc = null;
    }
  }

  @Test
  public void testAddMultiplePlugins() throws InterruptedException {
    // Load the sample TestExecutorPlugin twice
    SparkConf conf = initializeSparkConf(testPluginName + "," + testPluginName);

    try {
      sc = new JavaSparkContext(conf);
    } catch (Exception e) {
      fail("Failed to start SparkContext with exception " + e.toString());
    }

    // Wait a moment since plugins run on a separate thread
    Thread.sleep(500);

    assertEquals(2, numSuccessfulPlugins);

    if (sc != null) {
      sc.stop();
      sc = null;
    }
  }

  public static class TestExecutorPlugin implements ExecutorPlugin {
    public void init() {
      ExecutorPluginSuite.numSuccessfulPlugins++;
    }
  }
}
