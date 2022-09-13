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

package org.apache.spark.sql.catalyst

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.analysis.TypeCheckResult.DataTypeMismatch
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.trees.TreeNode
import org.apache.spark.sql.errors.QueryErrorsBase
import org.apache.spark.sql.internal.SQLConf

/**
 * Provides a logical query plan [[Analyzer]] and supporting classes for performing analysis.
 * Analysis consists of translating [[UnresolvedAttribute]]s and [[UnresolvedRelation]]s
 * into fully typed objects using information in a schema [[Catalog]].
 */
package object analysis {

  /**
   * Resolver should return true if the first string refers to the same entity as the second string.
   * For example, by using case insensitive equality.
   */
  type Resolver = (String, String) => Boolean

  val caseInsensitiveResolution = (a: String, b: String) => a.equalsIgnoreCase(b)
  val caseSensitiveResolution = (a: String, b: String) => a == b

  implicit class AnalysisErrorAt(t: TreeNode[_]) extends QueryErrorsBase {
    /** Fails the analysis at the point where a specific tree node was parsed. */
    def failAnalysis(msg: String): Nothing = {
      throw new AnalysisException(msg, t.origin.line, t.origin.startPosition)
    }

    /** Fails the analysis at the point where a specific tree node was parsed with a given cause. */
    def failAnalysis(msg: String, cause: Throwable): Nothing = {
      throw new AnalysisException(msg, t.origin.line, t.origin.startPosition, cause = Some(cause))
    }

    /**
     * Fails the analysis at the point where a specific tree node was parsed using a provided
     * error class and message parameters.
     */
    def failAnalysis(errorClass: String, messageParameters: Map[String, String]): Nothing = {
      throw new AnalysisException(
        errorClass = errorClass,
        messageParameters = messageParameters,
        origin = t.origin)
    }

    /**
     * Fails the analysis at the point where a specific tree node was parsed using a provided
     * error class and subclass and message parameters.
     */
    def failAnalysis(
        errorClass: String,
        errorSubClass: String,
        messageParameters: Map[String, String] = Map.empty[String, String]): Nothing = {
      throw new AnalysisException(
        errorClass = errorClass,
        errorSubClass = errorSubClass,
        messageParameters = messageParameters,
        origin = t.origin)
    }

    /**
     * Fails the analysis at the point where a specific tree node was parsed using a provided
     * error class and subclass and one message parameter comprising a plan string. The plan string
     * will be printed in the error message if and only if the corresponding Spark configuration is
     * enabled.
     */
    def failAnalysis(
        errorClass: String,
        errorSubClass: String,
        planString: String): Nothing = {
      throw new AnalysisException(
        errorClass = errorClass,
        errorSubClass = errorSubClass,
        messageParameters =
          Map("planString" -> (if (SQLConf.get.includePlansInErrors) s": $planString" else "")),
        origin = t.origin)
    }

    def dataTypeMismatch(expr: Expression, mismatch: DataTypeMismatch): Nothing = {
      throw new AnalysisException(
        errorClass = "DATATYPE_MISMATCH",
        errorSubClass = mismatch.errorSubClass,
        messageParameters = mismatch.messageParameters + ("sqlExpr" -> toSQLExpr(expr)),
        origin = t.origin)
    }
  }

  /** Catches any AnalysisExceptions thrown by `f` and attaches `t`'s position if any. */
  def withPosition[A](t: TreeNode[_])(f: => A): A = {
    try f catch {
      case a: AnalysisException =>
        throw a.withPosition(t.origin)
    }
  }
}
