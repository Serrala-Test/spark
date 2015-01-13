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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Command line interface for the Spark launcher. Used internally by Spark scripts.
 */
public class Main extends LauncherCommon {

  /**
   * Usage: Main [class] [class args]
   * <p/>
   * This CLI works in two different modes:
   * <ul>
   *   <li>"spark-submit": if <i>class</i> is "org.apache.spark.deploy.SparkSubmit", the
   *   {@link SparkLauncher} class is used to launch a Spark application.</li>
   *   <li>"spark-class": if another class is provided, an internal Spark class is run.</li>
   * </ul>
   *
   * The ultimate command will not be run in the same process. Instead, the command to be executed
   * will be printed to stdout. On Unix systems, this will be one argument per line. On Windows
   * systems, this will be a single line containing the command to be executed.
   */
  public static void main(String[] argsArray) throws Exception {
    checkArgument(argsArray.length > 0, "Not enough arguments: missing class name.");

    List<String> args = new ArrayList<String>(Arrays.asList(argsArray));
    String className = args.remove(0);

    boolean printLaunchCommand;
    AbstractLauncher<?> launcher;
    try {
      if (className.equals("org.apache.spark.deploy.SparkSubmit")) {
        launcher = new SparkSubmitCliLauncher(args);
      } else if (className.equals("pyspark")) {
        launcher = new PySparkLauncher(args);
      } else {
        launcher = new SparkClassLauncher(className, args);
      }
      printLaunchCommand = !isEmpty(System.getenv("SPARK_PRINT_LAUNCH_COMMAND"));
    } catch (IllegalArgumentException e) {
      launcher = new UsageLauncher();
      printLaunchCommand = false;
    }

    List<String> cmd = launcher.buildLauncherCommand();
    if (printLaunchCommand) {
      System.err.println("Spark Command: " + join(" ", cmd));
      System.err.println("========================================");
    }

    for (String c : cmd) {
      System.out.println(c);
    }
  }

  /**
   * Internal launcher used when command line parsing fails. This will behave differently depending
   * on the platform:
   *
   * - On Unix-like systems, it will print a call to the "usage" function with argument "1". The
   *   function is expected to print the command's usage and exit with the provided exit code.
   *   The script should use "export -f usage" after declaring a function called "usage", so that
   *   the function is available to downstream scripts.
   *
   * - On Windows it will set the variable "SPARK_LAUNCHER_USAGE_ERROR" to "1". The batch script
   *   should check for this variable and print its usage, since batch scripts don't really support
   *   the "export -f" functionality used in bash.
   */
  private static class UsageLauncher extends AbstractLauncher<UsageLauncher> {

    @Override
    protected List<String> buildLauncherCommand() {
      if (isWindows()) {
        return Arrays.asList("set SPARK_LAUNCHER_USAGE_ERROR=1");
      } else {
        return Arrays.asList("usage 1");
      }
    }

  }

}
