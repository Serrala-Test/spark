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

package org.apache.spark.deploy

import java.io.File

import scala.collection.JavaConversions._
import scala.collection._
import org.apache.spark.util.{RedirectThread, Utils}
import org.apache.spark.deploy.ConfigConstants._

/**
 * Launch an application through Spark submit in client mode with the appropriate classpath,
 * library paths, java options and memory. These properties of the JVM must be set before the
 * driver JVM is launched. The sole purpose of this class is to avoid handling the complexity
 * of parsing the properties file for such relevant configs in Bash.
 *
 * Usage: org.apache.spark.deploy.SparkSubmitDriverBootstrapper <submit args>
 */
private[spark] object SparkSubmitDriverBootstrapper {

  // Note: This class depends on the behavior of `bin/spark-class` and `bin/spark-submit`.
  // Any changes made there must be reflected in this file.

  def main(args: Array[String]): Unit = {

    // This should be called only from `bin/spark-class`
    if (!sys.env.contains("SPARK_CLASS")) {
      System.err.println("SparkSubmitDriverBootstrapper must be called from `bin/spark-class`!")
      System.exit(1)
    }

    val submitArgs = args
    val runner = sys.env("RUNNER")
    val classpath = sys.env("CLASSPATH")
    val javaOpts = sys.env("JAVA_OPTS")
    val defaultDriverMemory = sys.env("OUR_JAVA_MEM")

    // SPARK_SUBMIT_BOOTSTRAP_DRIVER is used for runtime validation
    val bootstrapDriver = sys.env("SPARK_SUBMIT_BOOTSTRAP_DRIVER")

    // list of environment variables that override differently named properties
    val envOverides = Map( "OUR_JAVA_MEM" -> SparkDriverMemory,
      "SPARK_SUBMIT_DEPLOY_MODE" -> SparkDeployMode,
      "SPARK_SUBMIT_PROPERTIES_FILE" -> SparkPropertiesFile,
      "SPARK_SUBMIT_DRIVER_MEMORY" -> SparkDriverMemory,
      "SPARK_SUBMIT_LIBRARY_PATH" -> SparkDriverExtraLibraryPath,
      "SPARK_SUBMIT_CLASSPATH" -> SparkDriverExtraClassPath,
      "SPARK_SUBMIT_OPTS" -> SparkDriverExtraJavaOptions
    )

    // SPARK_SUBMIT environment variables are treated as the highest priority source
    //  of config information for their respective config variable (as listed in envOverrides)
    val submitEnvVars = new mutable.HashMap() ++ envOverides
      .map( {case(varName, propName) => (sys.env.get(varName), propName) })
      .filter( {case(varVariable, _) => varVariable.isDefined} )
      .map( {case(varVariable, propName) => (propName->varVariable.get)})

     /* See docco for SparkSubmitArguments to see the various config sources and their priority.
      * Of note here is that we are explicitly treating SPARK_SUBMIT* environment vars
      * as the highest priority source
      * Followed by any property files located at SPARK_SUBMIT_PROPERTIES_FILE
      * Followed by the standard priorities specified by the docco un SparkSubmitArguments
      */
    val conf = SparkSubmitArguments.mergeSparkProperties(Vector(submitEnvVars,
        Map(SparkPropertiesFile->sys.env.get("SPARK_SUBMIT_PROPERTIES_FILE").get)))

    assume(runner != null, "RUNNER must be set")
    assume(classpath != null, "CLASSPATH must be set")
    assume(javaOpts != null, "JAVA_OPTS must be set")
    assume(defaultDriverMemory != null, "OUR_JAVA_MEM must be set")
    assume(conf.getOrElse(SparkDeployMode, "") == "client",
      "SPARK_SUBMIT_DEPLOY_MODE must be \"client\"!")
    assume(conf.getOrElse(SparkPropertiesFile, null) != null, "" +
      "SPARK_SUBMIT_PROPERTIES_FILE must be set")
    assume(bootstrapDriver != null, "SPARK_SUBMIT_BOOTSTRAP_DRIVER must be set")

    val confDriverMemory = conf.get(SparkDriverMemory)
    val confLibraryPath = conf.get(SparkDriverExtraLibraryPath)
    val confClasspath = conf.get(SparkDriverExtraClassPath)
    val confJavaOpts = conf.get(SparkDriverExtraClassPath)

    val filteredJavaOpts = Utils.splitCommandString(confJavaOpts.getOrElse(""))
      .filterNot(_.startsWith("-Xms"))
      .filterNot(_.startsWith("-Xmx"))

    val newDriverMemory = conf.get(SparkDriverMemory).get

    // Build up command
    val command: Seq[String] =
      Seq(runner) ++
      Seq("-cp", confClasspath.getOrElse("")) ++
      Seq(confLibraryPath.getOrElse("")) ++
      filteredJavaOpts ++
      Seq(s"-Xms$newDriverMemory", s"-Xmx$newDriverMemory") ++
      Seq("org.apache.spark.deploy.SparkSubmit") ++
      submitArgs

    // Print the launch command. This follows closely the format used in `bin/spark-class`.
    if (sys.env.contains("SPARK_PRINT_LAUNCH_COMMAND")) {
      System.err.print("Spark Command: ")
      System.err.println(command.mkString(" "))
      System.err.println("========================================\n")
    }

    // Start the driver JVM
    val filteredCommand = command.filter(_.nonEmpty)
    val builder = new ProcessBuilder(filteredCommand)
    val process = builder.start()

    // Redirect stdout and stderr from the child JVM
    val stdoutThread = new RedirectThread(process.getInputStream, System.out, "redirect stdout")
    val stderrThread = new RedirectThread(process.getErrorStream, System.err, "redirect stderr")
    stdoutThread.start()
    stderrThread.start()

    // Redirect stdin to child JVM only if we're not running Windows. This is because the
    // subprocess there already reads directly from our stdin, so we should avoid spawning a
    // thread that contends with the subprocess in reading from System.in.
    val isWindows = Utils.isWindows
    val isPySparkShell = sys.env.contains("PYSPARK_SHELL")
    if (!isWindows) {
      val stdinThread = new RedirectThread(System.in, process.getOutputStream, "redirect stdin")
      stdinThread.start()
      // For the PySpark shell, Spark submit itself runs as a python subprocess, and so this JVM
      // should terminate on broken pipe, which signals that the parent process has exited. In
      // Windows, the termination logic for the PySpark shell is handled in java_gateway.py
      if (isPySparkShell) {
        stdinThread.join()
        process.destroy()
      }
    }
    process.waitFor()
  }

}
