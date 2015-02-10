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

package org.apache.spark.deploy.worker

import java.lang.management.ManagementFactory

import org.apache.spark.util.{IntParam, MemoryParam, Utils}
import org.apache.spark.SparkConf

/**
 * Arguments parser for the worker.
 */
private[spark] class WorkerArguments(args: Array[String], conf: SparkConf) {
  var host: String = null
  var port: Int = -1
  var webUiPort: Int = -1
  var cores: Int = -1
  var memory: Int = -1
  var masters: Array[String] = null
  var workDir: String = null
  var propertiesFile: String = null

  parse(args.toList)

  // This mutates the SparkConf, so all accesses to it must be made after this line
  propertiesFile = Utils.loadDefaultSparkProperties(conf, propertiesFile)

  loadEnvironmentArguments()

  /**
   * Load arguments from environment variables, Spark properties etc.
   */
  private def loadEnvironmentArguments(): Unit = {
    host = Option(host)
      .orElse(conf.getOption("spark.worker.host"))
      .getOrElse(Utils.localHostName())
    if (port < 0) {
      port = conf.getOption("spark.worker.port").map(_.toInt).
        orElse(sys.env.get("SPARK_WORKER_PORT").map(_.toInt)).
        getOrElse(0)
    }
    if (webUiPort < 0) {
      webUiPort = conf.getOption("spark.worker.ui.port").map(_.toInt)
        .orElse(sys.env.get("SPARK_WORKER_WEBUI_PORT").map(_.toInt))
        .getOrElse(8081)
    }
    if (cores <= 0) {
      cores = sys.env.get("SPARK_WORKER_CORES").map(_.toInt)
        .getOrElse(inferDefaultCores())
    }
    if (memory <= 0) {
      memory = sys.env.get("SPARK_WORKER_MEMORY").map(Utils.memoryStringToMb(_))
      .getOrElse(inferDefaultMemory())
    }
    workDir = Option(workDir)
      .orElse(sys.env.get("SPARK_WORKER_DIR"))
      .getOrElse(null)
  }

  checkWorkerMemory()

  def parse(args: List[String]): Unit = args match {
    case ("--ip" | "-i") :: value :: tail =>
      Utils.checkHost(value, "ip no longer supported, please use hostname " + value)
      host = value
      parse(tail)

    case ("--host" | "-h") :: value :: tail =>
      Utils.checkHost(value, "Please use hostname " + value)
      host = value
      parse(tail)

    case ("--port" | "-p") :: IntParam(value) :: tail =>
      port = value
      parse(tail)

    case ("--cores" | "-c") :: IntParam(value) :: tail =>
      cores = value
      parse(tail)

    case ("--memory" | "-m") :: MemoryParam(value) :: tail =>
      memory = value
      parse(tail)

    case ("--work-dir" | "-d") :: value :: tail =>
      workDir = value
      parse(tail)

    case "--webui-port" :: IntParam(value) :: tail =>
      webUiPort = value
      parse(tail)

    case ("--properties-file") :: value :: tail =>
      propertiesFile = value
      parse(tail)

    case ("--help") :: tail =>
      printUsageAndExit(0)

    case value :: tail =>
      if (masters != null) {  // Two positional arguments were given
        printUsageAndExit(1)
      }
      masters = value.stripPrefix("spark://").split(",").map("spark://" + _)
      parse(tail)

    case Nil =>
      if (masters == null) {  // No positional argument was given
        printUsageAndExit(1)
      }

    case _ =>
      printUsageAndExit(1)
  }

  /**
   * Print usage and exit JVM with the given exit code.
   */
  def printUsageAndExit(exitCode: Int) {
    System.err.println(
      """Usage: Worker [options] <master>
        |
        |Master must be a URL of the form spark://hostname:port
        |
        |Options:
        |  -c CORES, --cores CORES  Number of cores to use.
        |  -m MEM, --memory MEM     Amount of memory to use (e.g. 1000M, 2G).
        |  -d DIR, --work-dir DIR   Directory to run apps in (default: SPARK_HOME/work).
        |  -i HOST, --ip IP         Hostname to listen on (deprecated, please use --host or -h).
        |  -h HOST, --host HOST     Hostname to listen on.
        |  -p PORT, --port PORT     Port to listen on (default: random).
        |  --webui-port PORT        Port for web UI (default: 8081).
        |  --properties-file FILE   Path to a custom Spark properties file.
        |                           Default is conf/spark-defaults.conf.
      """.stripMargin)
    System.exit(exitCode)
  }

  def inferDefaultCores(): Int = {
    Runtime.getRuntime.availableProcessors()
  }

  def inferDefaultMemory(): Int = {
    val ibmVendor = System.getProperty("java.vendor").contains("IBM")
    var totalMb = 0
    try {
      val bean = ManagementFactory.getOperatingSystemMXBean()
      if (ibmVendor) {
        val beanClass = Class.forName("com.ibm.lang.management.OperatingSystemMXBean")
        val method = beanClass.getDeclaredMethod("getTotalPhysicalMemory")
        totalMb = (method.invoke(bean).asInstanceOf[Long] / 1024 / 1024).toInt
      } else {
        val beanClass = Class.forName("com.sun.management.OperatingSystemMXBean")
        val method = beanClass.getDeclaredMethod("getTotalPhysicalMemorySize")
        totalMb = (method.invoke(bean).asInstanceOf[Long] / 1024 / 1024).toInt
      }
    } catch {
      case e: Exception => {
        totalMb = 2*1024
        System.out.println("Failed to get total physical memory. Using " + totalMb + " MB")
      }
    }
    // Leave out 1 GB for the operating system, but don't return a negative memory size
    math.max(totalMb - 1024, 512)
  }

  def checkWorkerMemory(): Unit = {
    if (memory <= 0) {
      val message = "Memory can't be 0, missing a M or G on the end of the memory specification?"
      throw new IllegalStateException(message)
    }
  }
}
