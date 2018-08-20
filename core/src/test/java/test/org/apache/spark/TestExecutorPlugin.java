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

package test.org.apache.spark;

import org.apache.spark.ExecutorPlugin;
import org.apache.spark.ExecutorPluginSuite;

// A test-only sample plugin, used by ExecutorPluginSuite to verify that
// plugins are correctly loaded from the spark.executor.plugins conf
public class TestExecutorPlugin implements ExecutorPlugin {
  public TestExecutorPlugin() {
    ExecutorPluginSuite.numSuccessfulPlugins++;
  }
}
