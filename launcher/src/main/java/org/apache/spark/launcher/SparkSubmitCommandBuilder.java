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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.apache.spark.launcher.CommandBuilderUtils.*;

/**
 * Special launcher for handling a CLI invocation of SparkSubmit.
 * <p/>
 * This launcher extends SparkLauncher to add command line parsing compatible with
 * SparkSubmit. It handles setting driver-side options and special parsing needed
 * for the different specialClasses.
 * <p/>
 * This class has also some special features to aid PySparkLauncher.
 */
class SparkSubmitCommandBuilder extends AbstractCommandBuilder {

  /**
   * Name of the app resource used to identify the PySpark shell. The command line parser expects
   * the resource name to be the very first argument to spark-submit in this case.
   *
   * NOTE: this cannot be "pyspark-shell" since that identifies the PySpark shell to SparkSubmit
   * (see java_gateway.py), and can cause this code to enter into an infinite loop.
   */
  static final String PYSPARK_SHELL = "pyspark-shell-main";

  /**
   * This map must match the class names for available special classes, since this modifies the way
   * command line parsing works. This maps the class name to the resource to use when calling
   * spark-submit.
   */
  private static final Map<String, String> specialClasses = new HashMap<String, String>();
  static {
    specialClasses.put("org.apache.spark.repl.Main", "spark-shell");
    specialClasses.put("org.apache.spark.sql.hive.thriftserver.SparkSQLCLIDriver",
      "spark-internal");
  }

  private final List<String> driverArgs;
  private boolean hasMixedArguments;

  SparkSubmitCommandBuilder() {
    this.driverArgs = Collections.<String>emptyList();
  }

  SparkSubmitCommandBuilder(List<String> args) {
    this(false, args);
  }

  SparkSubmitCommandBuilder(boolean hasMixedArguments, List<String> args) {
    this.driverArgs = new ArrayList<String>();

    List<String> submitArgs = args;
    if (args.size() > 0 && args.get(0).equals(PYSPARK_SHELL)) {
      this.hasMixedArguments = true;
      appResource = PYSPARK_SHELL;
      submitArgs = args.subList(1, args.size());
    } else {
      this.hasMixedArguments = hasMixedArguments;
    }

    new OptionParser().parse(submitArgs);
  }

  @Override
  public List<String> buildCommand(Map<String, String> env) throws IOException {
    if (PYSPARK_SHELL.equals(appResource)) {
      return buildPySparkShellCommand(env);
    } else {
      return buildSparkSubmitCommand(env);
    }
  }

  List<String> buildSparkSubmitArgs() {
    List<String> args = new ArrayList<String>();
    SparkSubmitOptionParser parser = new SparkSubmitOptionParser();

    if (verbose) {
      args.add(parser.VERBOSE);
    }

    if (master != null) {
      args.add(parser.MASTER);
      args.add(master);
    }

    if (deployMode != null) {
      args.add(parser.DEPLOY_MODE);
      args.add(deployMode);
    }

    if (appName != null) {
      args.add(parser.NAME);
      args.add(appName);
    }

    for (Map.Entry<String, String> e : conf.entrySet()) {
      args.add(parser.CONF);
      args.add(String.format("%s=%s", e.getKey(), e.getValue()));
    }

    if (propertiesFile != null) {
      args.add(parser.PROPERTIES_FILE);
      args.add(propertiesFile);
    }

    if (!jars.isEmpty()) {
      args.add(parser.JARS);
      args.add(join(",", jars));
    }

    if (!files.isEmpty()) {
      args.add(parser.FILES);
      args.add(join(",", files));
    }

    if (!pyFiles.isEmpty()) {
      args.add(parser.PY_FILES);
      args.add(join(",", pyFiles));
    }

    if (mainClass != null) {
      args.add(parser.CLASS);
      args.add(mainClass);
    }

    args.addAll(sparkArgs);
    if (appResource != null) {
      args.add(appResource);
    }
    args.addAll(appArgs);

    return args;
  }

  private List<String> buildSparkSubmitCommand(Map<String, String> env) throws IOException {
    // Load the properties file and check whether spark-submit will be running the app's driver
    // or just launching a cluster app. When running the driver, the JVM's argument will be
    // modified to cover the driver's configuration.
    Properties props = loadPropertiesFile();
    boolean isClientMode = isClientMode(props);
    String extraClassPath = isClientMode ?
      find(SparkLauncher.DRIVER_EXTRA_CLASSPATH, conf, props) : null;

    List<String> cmd = buildJavaCommand(extraClassPath);
    addOptionString(cmd, System.getenv("SPARK_SUBMIT_OPTS"));
    addOptionString(cmd, System.getenv("SPARK_JAVA_OPTS"));

    if (isClientMode) {
      // Figuring out where the memory value come from is a little tricky due to precedence.
      // Precedence is observed in the following order:
      // - explicit configuration (setConf()), which also covers --driver-memory cli argument.
      // - properties file.
      // - SPARK_DRIVER_MEMORY env variable
      // - SPARK_MEM env variable
      // - default value (512m)
      String memory = firstNonEmpty(find(SparkLauncher.DRIVER_MEMORY, conf, props),
        System.getenv("SPARK_DRIVER_MEMORY"), System.getenv("SPARK_MEM"), DEFAULT_MEM);
      cmd.add("-Xms" + memory);
      cmd.add("-Xmx" + memory);
      addOptionString(cmd, find(SparkLauncher.DRIVER_EXTRA_JAVA_OPTIONS, conf, props));
      mergeEnvPathList(env, getLibPathEnvName(),
        find(SparkLauncher.DRIVER_EXTRA_LIBRARY_PATH, conf, props));
    }

    addPermGenSizeOpt(cmd);
    cmd.add("org.apache.spark.deploy.SparkSubmit");
    cmd.addAll(buildSparkSubmitArgs());
    return cmd;
  }

  private List<String> buildPySparkShellCommand(Map<String, String> env) throws IOException {
    // For backwards compatibility, if a script is specified in
    // the pyspark command line, then run it using spark-submit.
    if (!appArgs.isEmpty() && appArgs.get(0).endsWith(".py")) {
      System.err.println(
        "WARNING: Running python applications through 'pyspark' is deprecated as of Spark 1.0.\n" +
        "Use ./bin/spark-submit <python file>");
      appResource = appArgs.get(0);
      appArgs.remove(0);
      return buildCommand(env);
    }

    // When launching the pyspark shell, the spark-submit arguments should be stored in the
    // PYSPARK_SUBMIT_ARGS env variable. The executable is the PYSPARK_DRIVER_PYTHON env variable
    // set by the pyspark script, followed by PYSPARK_DRIVER_PYTHON_OPTS.
    checkArgument(appArgs.isEmpty(), "pyspark does not support any application options.");

    Properties props = loadPropertiesFile();
    mergeEnvPathList(env, getLibPathEnvName(),
      find(SparkLauncher.DRIVER_EXTRA_LIBRARY_PATH, conf, props));

    StringBuilder submitArgs = new StringBuilder();
    for (String arg : sparkArgs) {
      if (submitArgs.length() > 0) {
        submitArgs.append(" ");
      }
      submitArgs.append(quote(arg));
    }
    for (String arg : driverArgs) {
      if (submitArgs.length() > 0) {
        submitArgs.append(" ");
      }
      submitArgs.append(quote(arg));
    }

    env.put("PYSPARK_SUBMIT_ARGS", submitArgs.toString());

    List<String> pyargs = new ArrayList<String>();
    pyargs.add(firstNonEmpty(System.getenv("PYSPARK_DRIVER_PYTHON"), "python"));
    String pyOpts = System.getenv("PYSPARK_DRIVER_PYTHON_OPTS");
    if (!isEmpty(pyOpts)) {
      pyargs.addAll(parseOptionString(pyOpts));
    }

    return pyargs;
  }

  private boolean isClientMode(Properties userProps) {
    String userMaster = firstNonEmpty(master, (String) userProps.get(SparkLauncher.SPARK_MASTER));
    return userMaster == null ||
      "client".equals(deployMode) ||
      "yarn-client".equals(userMaster) ||
      (deployMode == null && !userMaster.startsWith("yarn-"));
  }

  /**
   * Quotes a string so that it can be used in a command string and be parsed back into a single
   * argument by python's "shlex.split()" function.
   */
  private String quote(String s) {
    StringBuilder quoted = new StringBuilder().append('"');
    for (int i = 0; i < s.length(); i++) {
      int cp = s.codePointAt(i);
      if (cp == '"' || cp == '\\') {
        quoted.appendCodePoint('\\');
      }
      quoted.appendCodePoint(cp);
    }
    return quoted.append('"').toString();
  }

  private class OptionParser extends SparkSubmitOptionParser {

    private final List<String> driverJvmKeys = Arrays.asList(
      SparkLauncher.DRIVER_EXTRA_CLASSPATH,
      SparkLauncher.DRIVER_EXTRA_JAVA_OPTIONS,
      SparkLauncher.DRIVER_EXTRA_LIBRARY_PATH,
      SparkLauncher.DRIVER_MEMORY);

    @Override
    protected boolean handle(String opt, String value) {
      if (opt.equals(MASTER)) {
        master = value;
        driverArgs.add(opt);
        driverArgs.add(value);
      } else if (opt.equals(DEPLOY_MODE)) {
        deployMode = value;
        driverArgs.add(opt);
        driverArgs.add(value);
      } else if (opt.equals(PROPERTIES_FILE)) {
        propertiesFile = value;
        driverArgs.add(opt);
        driverArgs.add(value);
      } else if (opt.equals(DRIVER_MEMORY)) {
        conf.put(SparkLauncher.DRIVER_MEMORY, value);
        driverArgs.add(opt);
        driverArgs.add(value);
      } else if (opt.equals(DRIVER_JAVA_OPTIONS)) {
        conf.put(SparkLauncher.DRIVER_EXTRA_JAVA_OPTIONS, value);
        driverArgs.add(opt);
        driverArgs.add(value);
      } else if (opt.equals(DRIVER_LIBRARY_PATH)) {
        conf.put(SparkLauncher.DRIVER_EXTRA_LIBRARY_PATH, value);
        driverArgs.add(opt);
        driverArgs.add(value);
      } else if (opt.equals(DRIVER_CLASS_PATH)) {
        conf.put(SparkLauncher.DRIVER_EXTRA_CLASSPATH, value);
        driverArgs.add(opt);
        driverArgs.add(value);
      } else if (opt.equals(CONF)) {
        String[] setConf = value.split("=", 2);
        checkArgument(setConf.length == 2, "Invalid argument to %s: %s", CONF, value);
        if (driverJvmKeys.contains(setConf[0])) {
          conf.put(setConf[0], setConf[1]);
        }
        driverArgs.add(opt);
        driverArgs.add(value);
      } else if (opt.equals(CLASS)) {
        // The special classes require some special command line handling, since they allow
        // mixing spark-submit arguments with arguments that should be propagated to the shell
        // itself. Note that for this to work, the "--class" argument must come before any
        // non-spark-submit arguments.
        mainClass = value;
        if (specialClasses.containsKey(value)) {
          hasMixedArguments = true;
          appResource = specialClasses.get(value);
        }
      } else if (opt.equals(PYSPARK_SHELL)) {
        hasMixedArguments = true;
        appResource = opt;
      } else {
        addSparkArgs(opt, value);
      }
      return true;
    }

    @Override
    protected boolean handleUnknown(String opt) {
      // When mixing arguments, add unrecognized parameters directly to the user arguments list.
      // In normal mode, any unrecognized parameter triggers the end of command line parsing.
      // The remaining params will be appended to the list of SparkSubmit arguments.
      if (hasMixedArguments) {
        appArgs.add(opt);
        return true;
      } else {
        addSparkArgs(opt);
        return false;
      }
    }

    @Override
    protected void handleExtraArgs(List<String> extra) {
      for (String arg : extra) {
        addSparkArgs(arg);
      }
    }

  }

}
