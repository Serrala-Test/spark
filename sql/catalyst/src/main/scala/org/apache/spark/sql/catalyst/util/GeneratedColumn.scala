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

package org.apache.spark.sql.catalyst.util

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.analysis.Analyzer
import org.apache.spark.sql.catalyst.expressions.{Alias, Expression}
import org.apache.spark.sql.catalyst.parser.{CatalystSqlParser, ParseException}
import org.apache.spark.sql.catalyst.plans.logical.{LocalRelation, Project}
import org.apache.spark.sql.catalyst.util.ResolveDefaultColumns.BuiltInFunctionCatalog
import org.apache.spark.sql.connector.catalog.CatalogManager
import org.apache.spark.sql.errors.QueryCompilationErrors.toSQLId
import org.apache.spark.sql.types.{StructField, StructType}

/**
 * This object contains utility methods and values for Generated Columns
 */
object GeneratedColumn {

  /** The metadata key for saving a generation expression in a generated column's metadata */
  val GENERATION_EXPRESSION_METADATA_KEY = "generationExpression"

  /** Parser for parsing generation expression SQL strings */
  private lazy val parser = new CatalystSqlParser()

  /**
   * Whether the given `field` is a generated column
   */
  def isGeneratedColumn(field: StructField): Boolean = {
    field.metadata.contains(GENERATION_EXPRESSION_METADATA_KEY)
  }

  /**
   * Returns the generation expression stored in the column metadata if it exists
   */
  def getGenerationExpression(field: StructField): Option[String] = {
    if (isGeneratedColumn(field)) {
      Some(field.metadata.getString(GENERATION_EXPRESSION_METADATA_KEY))
    } else {
      None
    }
  }

  /**
   * Whether the `schema` has one or more generated columns
   */
  def hasGeneratedColumns(schema: StructType): Boolean = {
    schema.exists(isGeneratedColumn)
  }

  /**
   * Parse and analyze `expressionStr` and perform verification. This means:
   * - The expression cannot refer to itself
   * - No user-defined expressions
   *
   * Throws an [[AnalysisException]] if the expression cannot be converted or is an invalid
   * generation expression according to the above rules.
   */
  private def analyzeAndVerifyExpression(
    expressionStr: String,
    fieldName: String,
    schema: StructType,
    statementType: String): Unit = {
    // Parse the expression string
    val parsed: Expression = try {
      parser.parseExpression(expressionStr)
    } catch {
      case ex: ParseException =>
        // Shouldn't be possible since we check that the expression is a valid catalyst expression
        // during parsing
        throw new AnalysisException(
          s"Failed to execute $statementType command because the column $fieldName has " +
            s"generation expression $expressionStr which fails to parse as a valid expression:" +
            s"\n${ex.getMessage}")
    }
    // Analyze the parse result
    // Generated column can't reference itself
    val relation = new LocalRelation(StructType(schema.filterNot(_.name == fieldName)).toAttributes)
    val plan = try {
      val analyzer: Analyzer = GeneratedColumnAnalyzer
      val analyzed = analyzer.execute(Project(Seq(Alias(parsed, fieldName)()), relation))
      analyzer.checkAnalysis(analyzed)
      analyzed
    } catch {
      case ex: AnalysisException =>
        // Improve error message if possible
        if (ex.getErrorClass == "UNRESOLVED_COLUMN.WITH_SUGGESTION") {
          ex.messageParameters.get("objectName").filter(_ == toSQLId(fieldName)).foreach { _ =>
            // Generation expression references itself
            throw new AnalysisException(
              errorClass = "UNSUPPORTED_EXPRESSION_GENERATED_COLUMN",
              messageParameters = Map(
                "fieldName" -> fieldName,
                "expressionStr" -> expressionStr,
                "reason" -> "generation expression cannot reference itself",
                "errorMessage" -> ex.getMessage))
          }
        }
        if (ex.getErrorClass == "UNRESOLVED_ROUTINE") {
          // Cannot resolve function using built-in catalog
          ex.messageParameters.get("routineName").foreach { fnName =>
            throw new AnalysisException(
              errorClass = "UNSUPPORTED_EXPRESSION_GENERATED_COLUMN",
              messageParameters = Map(
                "fieldName" -> fieldName,
                "expressionStr" -> expressionStr,
                "reason" -> s"failed to resolve $fnName to a built-in function",
                "errorMessage" -> ex.getMessage))
          }
        }
        throw new AnalysisException(
          errorClass = "UNSUPPORTED_EXPRESSION_GENERATED_COLUMN",
          messageParameters = Map(
            "fieldName" -> fieldName,
            "expressionStr" -> expressionStr,
            "reason" -> "the expression fails to resolve as a valid expression",
            "errorMessage" -> ex.getMessage))
    }
    val analyzed = plan.collectFirst {
      case Project(Seq(a: Alias), _: LocalRelation) => a.child
    }.get
    // todo: additional verifications?
  }

  /**
   * For any generated columns in `schema`, parse, analyze and verify the generation expression.
   */
  def verifyGeneratedColumns(schema: StructType, statementType: String): Unit = {
   schema.foreach { field =>
      getGenerationExpression(field).map { expressionStr =>
        analyzeAndVerifyExpression(expressionStr, field.name, schema, statementType)
      }
    }
  }
}

/**
 * Analyzer for processing generated column expressions using built-in functions only.
 */
object GeneratedColumnAnalyzer extends Analyzer(
  new CatalogManager(BuiltInFunctionCatalog, BuiltInFunctionCatalog.v1Catalog)) {
}
