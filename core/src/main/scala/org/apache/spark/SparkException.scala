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

package org.apache.spark

class SparkException(
    message: String,
    cause: Throwable,
    errorClass: Option[String],
    messageParameters: Array[String])
  extends Exception(message, cause) with SparkThrowable {

  def this(message: String, cause: Throwable) =
    this(message = message, cause = cause, errorClass = None, messageParameters = Array.empty)

  def this(message: String) =
    this(message = message, cause = null)

  def this(errorClass: String, messageParameters: Array[String], cause: Throwable) =
    this(
      message = SparkThrowableHelper.getMessage(errorClass, messageParameters),
      cause = cause,
      errorClass = Some(errorClass),
      messageParameters = messageParameters)

  override def getErrorClass: String = errorClass.orNull
  override def getMessageParameters: Array[String] = messageParameters
  override def getSqlState: String = SparkThrowableHelper.getSqlState(errorClass.orNull)
}

/**
 * Exception thrown when execution of some user code in the driver process fails, e.g.
 * accumulator update fails or failure in takeOrdered (user supplies an Ordering implementation
 * that can be misbehaving.
 */
private[spark] class SparkDriverExecutionException(cause: Throwable)
  extends SparkException("Execution error", cause)

/**
 * Exception thrown when the main user code is run as a child process (e.g. pyspark) and we want
 * the parent SparkSubmit process to exit with the same exit code.
 */
private[spark] case class SparkUserAppException(exitCode: Int)
  extends SparkException(s"User application exited with $exitCode")

/**
 * Exception thrown when the relative executor to access is dead.
 */
private[spark] case class ExecutorDeadException(message: String)
  extends SparkException(message)

/**
 * Exception thrown when Spark returns different result after upgrading to a new version.
 */
private[spark] class SparkUpgradeException(version: String, message: String, cause: Throwable)
  extends RuntimeException("You may get a different result due to the upgrading of Spark" +
    s" $version: $message", cause)

/**
 * Arithmetic exception thrown from Spark with an error class.
 */
class SparkArithmeticException(errorClass: String, messageParameters: Array[String])
  extends ArithmeticException(SparkThrowableHelper.getMessage(errorClass, messageParameters))
    with SparkThrowable {

  override def getErrorClass: String = errorClass
  override def getMessageParameters: Array[String] = messageParameters
  override def getSqlState: String = SparkThrowableHelper.getSqlState(errorClass)
}
