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

package org.apache.spark.launcher;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Launcher for internal Spark classes.
 * <p/>
 * This class handles building the command to launch all internal Spark classes except for
 * SparkSubmit (which is handled by the public {@link SparkLauncher} class.
 */
class SparkClassLauncher extends AbstractLauncher<SparkClassLauncher> {

  private final String className;
  private final List<String> classArgs;

  SparkClassLauncher(String className, List<String> classArgs) {
    this.className = className;
    this.classArgs = classArgs;
  }

  @Override
  protected List<String> buildLauncherCommand() throws IOException {
    List<String> javaOptsKeys = new ArrayList<String>();
    String memKey = null;
    String extraClassPath = null;

    // Master, Worker, and HistoryServer use SPARK_DAEMON_JAVA_OPTS (and specific opts) +
    // SPARK_DAEMON_MEMORY.
    if (className.equals("org.apache.spark.deploy.master.Master")) {
      javaOptsKeys.add("SPARK_DAEMON_JAVA_OPTS");
      javaOptsKeys.add("SPARK_MASTER_OPTS");
      memKey = "SPARK_DAEMON_MEMORY";
    } else if (className.equals("org.apache.spark.deploy.worker.Worker")) {
      javaOptsKeys.add("SPARK_DAEMON_JAVA_OPTS");
      javaOptsKeys.add("SPARK_WORKER_OPTS");
      memKey = "SPARK_DAEMON_MEMORY";
    } else if (className.equals("org.apache.spark.deploy.history.HistoryServer")) {
      javaOptsKeys.add("SPARK_DAEMON_JAVA_OPTS");
      javaOptsKeys.add("SPARK_HISTORY_OPTS");
      memKey = "SPARK_DAEMON_MEMORY";
    } else if (className.equals("org.apache.spark.executor.CoarseGrainedExecutorBackend")) {
      javaOptsKeys.add("SPARK_JAVA_OPTS");
      javaOptsKeys.add("SPARK_EXECUTOR_OPTS");
      memKey = "SPARK_EXECUTOR_MEMORY";
    } else if (className.equals("org.apache.spark.executor.MesosExecutorBackend")) {
      javaOptsKeys.add("SPARK_EXECUTOR_OPTS");
      memKey = "SPARK_EXECUTOR_MEMORY";
    } else if (className.startsWith("org.apache.spark.tools.")) {
      String sparkHome = getSparkHome();
      File toolsDir = new File(join(File.separator, sparkHome, "tools", "target",
        "scala-" + getScalaVersion()));
      checkState(toolsDir.isDirectory(), "Cannot find tools build directory.");

      Pattern re = Pattern.compile("spark-tools_.*\\.jar");
      for (File f : toolsDir.listFiles()) {
        if (re.matcher(f.getName()).matches()) {
          extraClassPath = f.getAbsolutePath();
          break;
        }
      }

      checkState(extraClassPath != null,
        "Failed to find Spark Tools Jar in %s. You need to build Spark before running %s.",
        toolsDir.getAbsolutePath(), className);

      javaOptsKeys.add("SPARK_JAVA_OPTS");
    } else {
      // Any classes not explicitly listed above are submitted using SparkSubmit.
      return buildSparkSubmitCommand();
    }

    List<String> cmd = createJavaCommand();
    for (String key : javaOptsKeys) {
      addOptionString(cmd, System.getenv(key));
    }

    String mem = first(memKey != null ? System.getenv(memKey) : null, DEFAULT_MEM);
    cmd.add("-Xms" + mem);
    cmd.add("-Xmx" + mem);
    cmd.add("-cp");
    cmd.add(join(File.pathSeparator, buildClassPath(extraClassPath)));
    cmd.add(className);
    cmd.addAll(classArgs);
    return prepareForOs(cmd, null, Collections.<String, String>emptyMap());
  }

  private List<String> buildSparkSubmitCommand() throws IOException {
    List<String> sparkSubmitArgs = new ArrayList<String>(classArgs);
    sparkSubmitArgs.add(SparkSubmitOptionParser.CLASS);
    sparkSubmitArgs.add(className);

    SparkSubmitCliLauncher launcher = new SparkSubmitCliLauncher(true, sparkSubmitArgs);
    launcher.setAppResource("spark-internal");
    return launcher.buildLauncherCommand();
  }

}
