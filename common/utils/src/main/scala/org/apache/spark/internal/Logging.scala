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

package org.apache.spark.internal

import org.apache.logging.log4j.Level
import org.slf4j.{Logger, LoggerFactory}

import org.apache.spark.util.SparkClassUtils

/**
 * Utility trait for classes that want to log data. Creates a SLF4J logger for the class and allows
 * logging messages at different levels using methods that only evaluate parameters lazily if the
 * log level is enabled.
 */
trait Logging {

  // Make the log field transient so that objects with Logging can
  // be serialized and used on another machine
  @transient private var log_ : Logger = null

  // Method to get the logger name for this object
  protected def logName = {
    // Ignore trailing $'s in the class names for Scala objects
    this.getClass.getName.stripSuffix("$")
  }

  // Method to get or create the logger for this object
  protected def log: Logger = {
    if (log_ == null) {
      initializeLogIfNecessary(false)
      log_ = LoggerFactory.getLogger(logName)
    }
    log_
  }

  // Log methods that take only a String
  protected def logInfo(msg: => String): Unit = {
    if (log.isInfoEnabled) log.info(msg)
  }

  protected def logDebug(msg: => String): Unit = {
    if (log.isDebugEnabled) log.debug(msg)
  }

  protected def logTrace(msg: => String): Unit = {
    if (log.isTraceEnabled) log.trace(msg)
  }

  protected def logWarning(msg: => String): Unit = {
    if (log.isWarnEnabled) log.warn(msg)
  }

  protected def logError(msg: => String): Unit = {
    if (log.isErrorEnabled) log.error(msg)
  }

  // Log methods that take Throwables (Exceptions/Errors) too
  protected def logInfo(msg: => String, throwable: Throwable): Unit = {
    if (log.isInfoEnabled) log.info(msg, throwable)
  }

  protected def logDebug(msg: => String, throwable: Throwable): Unit = {
    if (log.isDebugEnabled) log.debug(msg, throwable)
  }

  protected def logTrace(msg: => String, throwable: Throwable): Unit = {
    if (log.isTraceEnabled) log.trace(msg, throwable)
  }

  protected def logWarning(msg: => String, throwable: Throwable): Unit = {
    if (log.isWarnEnabled) log.warn(msg, throwable)
  }

  protected def logError(msg: => String, throwable: Throwable): Unit = {
    if (log.isErrorEnabled) log.error(msg, throwable)
  }

  protected def isTraceEnabled(): Boolean = {
    log.isTraceEnabled
  }

  protected def initializeLogIfNecessary(isInterpreter: Boolean): Unit = {
    initializeLogIfNecessary(isInterpreter, silent = false)
  }

  protected def initializeLogIfNecessary(
      isInterpreter: Boolean,
      silent: Boolean = false): Boolean = {
    if (!Logging.initialized) {
      Logging.initLock.synchronized {
        if (!Logging.initialized) {
          initializeLogging(isInterpreter, silent)
          return true
        }
      }
    }
    false
  }

  // For testing
  private[spark] def initializeForcefully(isInterpreter: Boolean, silent: Boolean): Unit = {
    initializeLogging(isInterpreter, silent)
  }

  private def initializeLogging(isInterpreter: Boolean, silent: Boolean): Unit = {
    if (Logging.isLog4j2()) {
      Log4j2Utils.initializeLogging(isInterpreter, silent, logName)
    }
    Logging.initialized = true

    // Force a call into slf4j to initialize it. Avoids this happening from multiple threads
    // and triggering this: http://mailman.qos.ch/pipermail/slf4j-dev/2010-April/002956.html
    log
  }
}

private[spark] object Logging {
  @volatile private[internal] var initialized = false
  @volatile private[spark] var sparkShellThresholdLevel: Level = null
  @volatile private[spark] var setLogLevelPrinted: Boolean = false

  val initLock = new Object()
  try {
    // We use reflection here to handle the case where users remove the
    // slf4j-to-jul bridge order to route their logs to JUL.
    val bridgeClass = SparkClassUtils.classForName("org.slf4j.bridge.SLF4JBridgeHandler")
    bridgeClass.getMethod("removeHandlersForRootLogger").invoke(null)
    val installed = bridgeClass.getMethod("isInstalled").invoke(null).asInstanceOf[Boolean]
    if (!installed) {
      bridgeClass.getMethod("install").invoke(null)
    }
  } catch {
    case e: ClassNotFoundException => // can't log anything yet so just fail silently
  }

  /**
   * configure a new logger level
   */
  def setLogLevel(l: Level): Unit = {
    if (isLog4j2()) {
      Log4j2Utils.setLogLevel(l)
    }
    // Setting threshold to null as rootLevel will define log level for spark-shell
    sparkShellThresholdLevel = null
  }

  /**
   * Marks the logging system as not initialized. This does a best effort at resetting the
   * logging system to its initial state so that the next class to use logging triggers
   * initialization again.
   */
  def uninitialize(): Unit = initLock.synchronized {
    if (isLog4j2()) {
      Log4j2Utils.uninitialize()
    }
    this.initialized = false
  }

  private[spark] def isLog4j2(): Boolean = {
    // This distinguishes the log4j 1.2 binding, currently
    // org.slf4j.impl.Log4jLoggerFactory, from the log4j 2.0 binding, currently
    // org.apache.logging.slf4j.Log4jLoggerFactory
    "org.apache.logging.slf4j.Log4jLoggerFactory"
      .equals(LoggerFactory.getILoggerFactory.getClass.getName)
  }
}
