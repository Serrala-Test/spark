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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * Basic functionality for launchers - objects that encapsulate knowledge about how to build the
 * commands to run a Spark application or service. This class is not meant to be extended by user
 * code.
 */
public abstract class AbstractLauncher<T extends AbstractLauncher> extends LauncherCommon {

  private static final String ENV_SPARK_HOME = "SPARK_HOME";
  private static final String DEFAULT_PROPERTIES_FILE = "spark-defaults.conf";
  static final String DEFAULT_MEM = "512m";

  String javaHome;
  String sparkHome;
  String propertiesFile;
  final Map<String, String> conf;
  final Map<String, String> launcherEnv;

  AbstractLauncher() {
    this(Collections.<String, String>emptyMap());
  }

  protected AbstractLauncher(Map<String, String> env) {
    this.conf = new HashMap<String, String>();
    this.launcherEnv = new HashMap<String, String>(env);
  }

  @SuppressWarnings("unchecked")
  private final T THIS = (T) this;

  /**
   * Set a custom JAVA_HOME for launching the Spark application.
   *
   * @param javaHome Path to the JAVA_HOME to use.
   * @return This launcher.
   */
  public T setJavaHome(String javaHome) {
    checkNotNull(javaHome, "javaHome");
    this.javaHome = javaHome;
    return THIS;
  }

  /**
   * Set a custom Spark installation location for the application.
   *
   * @param sparkHome Path to the Spark installation to use.
   * @return This launcher.
   */
  public T setSparkHome(String sparkHome) {
    checkNotNull(sparkHome, "sparkHome");
    launcherEnv.put(ENV_SPARK_HOME, sparkHome);
    return THIS;
  }

  /**
   * Set a custom properties file with Spark configuration for the application.
   *
   * @param path Path to custom properties file to use.
   * @return This launcher.
   */
  public T setPropertiesFile(String path) {
    checkNotNull(path, "path");
    this.propertiesFile = path;
    return THIS;
  }

  /**
   * Set a single configuration value for the application.
   *
   * @param key Configuration key.
   * @param value The value to use.
   * @return This launcher.
   */
  public T setConf(String key, String value) {
    checkNotNull(key, "key");
    checkNotNull(value, "value");
    checkArgument(key.startsWith("spark."), "'key' must start with 'spark.'");
    conf.put(key, value);
    return THIS;
  }

  /**
   * Launchers should implement this to create the command to be executed. This method should
   * also update the environment map with any environment variables needed by the child process.
   * <p/>
   * Note that this method is a no-op in the base class, even though subclasses in this package
   * really must implement it. This approach was taken to allow this method to be package private
   * while still allowing CommandUtils.scala to extend this class for its use.
   *
   * @param env Map containing environment variables to set for the Spark job.
   */
  List<String> buildLauncherCommand(Map<String, String> env) throws IOException {
    throw new UnsupportedOperationException("Subclasses must implement this method.");
  }

  /**
   * Prepares the launcher command for execution from a shell script. This is used by the `Main`
   * class to service the scripts shipped with the Spark distribution.
   */
  List<String> buildShellCommand() throws IOException {
    Map<String, String> childEnv = new HashMap<String, String>(launcherEnv);
    List<String> cmd = buildLauncherCommand(childEnv);
    return isWindows() ? prepareForWindows(cmd, childEnv) : prepareForBash(cmd, childEnv);
  }

  /**
   * Loads the configuration file for the application, if it exists. This is either the
   * user-specified properties file, or the spark-defaults.conf file under the Spark configuration
   * directory.
   */
  Properties loadPropertiesFile() throws IOException {
    Properties props = new Properties();
    File propsFile;
    if (propertiesFile != null) {
      propsFile = new File(propertiesFile);
      checkArgument(propsFile.isFile(), "Invalid properties file '%s'.", propertiesFile);
    } else {
      propsFile = new File(getConfDir(), DEFAULT_PROPERTIES_FILE);
    }

    if (propsFile.isFile()) {
      FileInputStream fd = null;
      try {
        fd = new FileInputStream(propsFile);
        props.load(new InputStreamReader(fd, "UTF-8"));
      } finally {
        if (fd != null) {
          try {
            fd.close();
          } catch (IOException e) {
            // Ignore.
          }
        }
      }
    }

    return props;
  }

  String getSparkHome() {
    String path = getenv(ENV_SPARK_HOME);
    checkState(path != null,
      "Spark home not found; set it explicitly or use the SPARK_HOME environment variable.");
    return path;
  }

  protected List<String> buildJavaCommand(String extraClassPath) throws IOException {
    List<String> cmd = new ArrayList<String>();
    if (javaHome == null) {
      cmd.add(join(File.separator, System.getProperty("java.home"), "bin", "java"));
    } else {
      cmd.add(join(File.separator, javaHome, "bin", "java"));
    }

    // Don't set MaxPermSize for Java 8 and later.
    String[] version = System.getProperty("java.version").split("\\.");
    if (Integer.parseInt(version[0]) == 1 && Integer.parseInt(version[1]) < 8) {
      cmd.add("-XX:MaxPermSize=128m");
    }

    // Load extra JAVA_OPTS from conf/java-opts, if it exists.
    File javaOpts = new File(join(File.separator, getSparkHome(), "conf", "java-opts"));
    if (javaOpts.isFile()) {
      BufferedReader br = new BufferedReader(new InputStreamReader(
          new FileInputStream(javaOpts), "UTF-8"));
      try {
        String line;
        while ((line = br.readLine()) != null) {
          addOptionString(cmd, line);
        }
      } finally {
        br.close();
      }
    }

    cmd.add("-cp");
    cmd.add(join(File.pathSeparator, buildClassPath(extraClassPath)));
    return cmd;
  }

  protected void addOptionString(List<String> cmd, String options) {
    if (!isEmpty(options)) {
      for (String opt : parseOptionString(options)) {
        cmd.add(opt);
      }
    }
  }

  /**
   * Builds the classpath for the application. Returns a list with one classpath entry per element;
   * each entry is formatted in the way expected by <i>java.net.URLClassLoader</i> (more
   * specifically, with trailing slashes for directories).
   */
  List<String> buildClassPath(String appClassPath) throws IOException {
    String sparkHome = getSparkHome();
    String scala = getScalaVersion();

    List<String> cp = new ArrayList<String>();
    addToClassPath(cp, getenv("SPARK_CLASSPATH"));
    addToClassPath(cp, appClassPath);

    addToClassPath(cp, getConfDir());

    boolean prependClasses = !isEmpty(getenv("SPARK_PREPEND_CLASSES"));
    boolean isTesting = "1".equals(getenv("SPARK_TESTING"));
    if (prependClasses || isTesting) {
      List<String> projects = Arrays.asList("core", "repl", "mllib", "bagel", "graphx",
        "streaming", "tools", "sql/catalyst", "sql/core", "sql/hive", "sql/hive-thriftserver",
        "yarn", "launcher");
      if (prependClasses) {
        System.err.println(
          "NOTE: SPARK_PREPEND_CLASSES is set, placing locally compiled Spark classes ahead of " +
          "assembly.");
        for (String project : projects) {
          addToClassPath(cp, String.format("%s/%s/target/scala-%s/classes", sparkHome, project,
            scala));
        }
      }
      if (isTesting) {
        for (String project : projects) {
          addToClassPath(cp, String.format("%s/%s/target/scala-%s/test-classes", sparkHome,
            project, scala));
        }
      }
      addToClassPath(cp, String.format("%s/core/target/jars/*", sparkHome));
    }

    String assembly = findAssembly(scala);
    addToClassPath(cp, assembly);

    // When Hive support is needed, Datanucleus jars must be included on the classpath. Datanucleus
    // jars do not work if only included in the uber jar as plugin.xml metadata is lost. Both sbt
    // and maven will populate "lib_managed/jars/" with the datanucleus jars when Spark is built
    // with Hive, so first check if the datanucleus jars exist, and then ensure the current Spark
    // assembly is built for Hive, before actually populating the CLASSPATH with the jars.
    //
    // This block also serves as a check for SPARK-1703, when the assembly jar is built with
    // Java 7 and ends up with too many files, causing issues with other JDK versions.
    boolean needsDataNucleus = false;
    JarFile assemblyJar = null;
    try {
      assemblyJar = new JarFile(assembly);
      needsDataNucleus = assemblyJar.getEntry("org/apache/hadoop/hive/ql/exec/") != null;
    } catch (IOException ioe) {
      if (ioe.getMessage().indexOf("invalid CEN header") > 0) {
        System.err.println(
          "Loading Spark jar failed.\n" +
          "This is likely because Spark was compiled with Java 7 and run\n" +
          "with Java 6 (see SPARK-1703). Please use Java 7 to run Spark\n" +
          "or build Spark with Java 6.");
        System.exit(1);
      } else {
        throw ioe;
      }
    } finally {
      if (assemblyJar != null) {
        try {
          assemblyJar.close();
        } catch (IOException e) {
          // Ignore.
        }
      }
    }

    if (needsDataNucleus) {
      System.err.println("Spark assembly has been built with Hive, including Datanucleus jars " +
        "in classpath.");
      File libdir;
      if (new File(sparkHome, "RELEASE").isFile()) {
        libdir = new File(sparkHome, "lib");
      } else {
        libdir = new File(sparkHome, "lib_managed/jars");
      }

      checkState(libdir.isDirectory(), "Library directory '%s' does not exist.",
        libdir.getAbsolutePath());
      for (File jar : libdir.listFiles()) {
        if (jar.getName().startsWith("datanucleus-")) {
          addToClassPath(cp, jar.getAbsolutePath());
        }
      }
    }

    addToClassPath(cp, getenv("HADOOP_CONF_DIR"));
    addToClassPath(cp, getenv("YARN_CONF_DIR"));
    addToClassPath(cp, getenv("SPARK_DIST_CLASSPATH"));
    return cp;
  }

  /**
   * Adds entries to the classpath.
   *
   * @param cp List where to appended the new classpath entries.
   * @param entries New classpath entries (separated by File.pathSeparator).
   */
  private void addToClassPath(List<String> cp, String entries) {
    if (isEmpty(entries)) {
      return;
    }
    String[] split = entries.split(Pattern.quote(File.pathSeparator));
    for (String entry : split) {
      if (!isEmpty(entry)) {
        if (new File(entry).isDirectory() && !entry.endsWith(File.separator)) {
          entry += File.separator;
        }
        cp.add(entry);
      }
    }
  }

  String getScalaVersion() {
    String scala = getenv("SPARK_SCALA_VERSION");
    if (scala != null) {
      return scala;
    }

    String sparkHome = getSparkHome();
    File scala210 = new File(sparkHome, "assembly/target/scala-2.10");
    File scala211 = new File(sparkHome, "assembly/target/scala-2.11");
    if (scala210.isDirectory() && scala211.isDirectory()) {
      checkState(false,
        "Presence of build for both scala versions (2.10 and 2.11) detected.\n" +
        "Either clean one of them or set SPARK_SCALA_VERSION in your environment.");
    } else if (scala210.isDirectory()) {
      return "2.10";
    } else {
      checkState(scala211.isDirectory(), "Cannot find any assembly build directories.");
      return "2.11";
    }

    throw new IllegalStateException("Should not reach here.");
  }

  private String findAssembly(String scalaVersion) {
    String sparkHome = getSparkHome();
    File libdir;
    if (new File(sparkHome, "RELEASE").isFile()) {
      libdir = new File(sparkHome, "lib");
      checkState(libdir.isDirectory(), "Library directory '%s' does not exist.",
          libdir.getAbsolutePath());
    } else {
      libdir = new File(sparkHome, String.format("assembly/target/scala-%s", scalaVersion));
    }

    final Pattern re = Pattern.compile("spark-assembly.*hadoop.*\\.jar");
    FileFilter filter = new FileFilter() {
      @Override
      public boolean accept(File file) {
        return file.isFile() && re.matcher(file.getName()).matches();
      }
    };
    File[] assemblies = libdir.listFiles(filter);
    checkState(assemblies != null && assemblies.length > 0, "No assemblies found in '%s'.", libdir);
    checkState(assemblies.length == 1, "Multiple assemblies found in '%s'.", libdir);
    return assemblies[0].getAbsolutePath();
  }

  private String getenv(String key) {
    return firstNonEmpty(launcherEnv.get(key), System.getenv(key));
  }

  private String getConfDir() {
    String confDir = getenv("SPARK_CONF_DIR");
    return confDir != null ? confDir : join(File.separator, getSparkHome(), "conf");
  }

  /**
   * Prepare the command for execution from a bash script. The final command will have commands to
   * set up any needed environment variables needed by the child process.
   */
  private List<String> prepareForBash(List<String> cmd, Map<String, String> childEnv) {
    if (childEnv.isEmpty()) {
      return cmd;
    }

    List<String> newCmd = new ArrayList<String>();
    newCmd.add("env");

    for (Map.Entry<String, String> e : childEnv.entrySet()) {
      newCmd.add(String.format("%s=%s", e.getKey(), e.getValue()));
    }
    newCmd.addAll(cmd);
    return newCmd;
  }

  /**
   * Prepare a command line for execution from a Windows batch script.
   *
   * Two things need to be done:
   *
   * - If a custom library path is needed, extend PATH to add it. Based on:
   *   http://superuser.com/questions/223104/setting-environment-variable-for-just-one-command-in-windows-cmd-exe
   *
   * - Quote all arguments so that spaces are handled as expected. Quotes within arguments are
   *   "double quoted" (which is batch for escaping a quote). This page has more details about
   *   quoting and other batch script fun stuff: http://ss64.com/nt/syntax-esc.html
   *
   * The command is executed using "cmd /c" and formatted as single line, since that's the
   * easiest way to consume this from a batch script (see spark-class2.cmd).
   */
  private List<String> prepareForWindows(List<String> cmd, Map<String, String> childEnv) {
    StringBuilder cmdline = new StringBuilder("cmd /c \"");
    for (Map.Entry<String, String> e : childEnv.entrySet()) {
      if (cmdline.length() > 0) {
        cmdline.append(" ");
      }
      cmdline.append(String.format("set %s=%s", e.getKey(), e.getValue()));
      cmdline.append(" &&");
    }
    for (String arg : cmd) {
      if (cmdline.length() > 0) {
        cmdline.append(" ");
      }
      cmdline.append(quoteForBatchScript(arg));
    }
    cmdline.append("\"");
    return Arrays.asList(cmdline.toString());
  }

  /**
   * Quote a command argument for a command to be run by a Windows batch script, if the argument
   * needs quoting. Arguments only seem to need quotes in batch scripts if they have whitespace.
   */
  private String quoteForBatchScript(String arg) {
    boolean needsQuotes = false;
    for (int i = 0; i < arg.length(); i++) {
      if (Character.isWhitespace(arg.codePointAt(i))) {
        needsQuotes = true;
        break;
      }
    }
    if (!needsQuotes) {
      return arg;
    }
    StringBuilder quoted = new StringBuilder();
    quoted.append("\"");
    for (int i = 0; i < arg.length(); i++) {
      int cp = arg.codePointAt(i);
      if (cp == '\"') {
        quoted.append("\"");
      }
      quoted.appendCodePoint(cp);
    }
    quoted.append("\"");
    return quoted.toString();
  }

}
