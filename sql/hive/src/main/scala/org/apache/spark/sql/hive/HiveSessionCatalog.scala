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

package org.apache.spark.sql.hive

import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

import org.apache.hadoop.fs.Path
import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.ql.exec.{UDAF, UDF}
import org.apache.hadoop.hive.ql.exec.{FunctionRegistry => HiveFunctionRegistry}
import org.apache.hadoop.hive.ql.udf.generic.{AbstractGenericUDAFResolver, GenericUDF, GenericUDTF}

import org.apache.spark.sql.{AnalysisException, SparkSession}
import org.apache.spark.sql.catalyst.{FunctionIdentifier, TableIdentifier}
import org.apache.spark.sql.catalyst.analysis.FunctionRegistry
import org.apache.spark.sql.catalyst.analysis.FunctionRegistry.FunctionBuilder
import org.apache.spark.sql.catalyst.catalog.{CatalogTable, CatalogTableType, FunctionResourceLoader, SessionCatalog}
import org.apache.spark.sql.catalyst.expressions.{Expression, ExpressionInfo}
import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, SubqueryAlias}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.hive.HiveShim.HiveFunctionWrapper
import org.apache.spark.sql.hive.client.HiveClient
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.util.Utils


private[sql] class HiveSessionCatalog(
    externalCatalog: HiveExternalCatalog,
    client: HiveClient,
    sparkSession: SparkSession,
    functionResourceLoader: FunctionResourceLoader,
    functionRegistry: FunctionRegistry,
    conf: SQLConf,
    hiveconf: HiveConf)
  extends SessionCatalog(externalCatalog, functionResourceLoader, functionRegistry, conf) {

  override def setCurrentDatabase(db: String): Unit = {
    super.setCurrentDatabase(db)
    client.setCurrentDatabase(db)
  }

  override def lookupRelation(name: TableIdentifier, alias: Option[String]): LogicalPlan = {
    val table = formatTableName(name.table)
    if (name.database.isDefined || !tempTables.contains(table)) {
      val newName = name.copy(table = table)
      metastoreCatalog.lookupRelation(newName, alias)
    } else {
      val relation = tempTables(table)
      val tableWithQualifiers = SubqueryAlias(table, relation)
      // If an alias was specified by the lookup, wrap the plan in a subquery so that
      // attributes are properly qualified with this alias.
      alias.map(a => SubqueryAlias(a, tableWithQualifiers)).getOrElse(tableWithQualifiers)
    }
  }

  // ----------------------------------------------------------------
  // | Methods and fields for interacting with HiveMetastoreCatalog |
  // ----------------------------------------------------------------

  override def getDefaultDBPath(db: String): String = {
    val defaultPath = hiveconf.getVar(HiveConf.ConfVars.METASTOREWAREHOUSE)
    new Path(new Path(defaultPath), db + ".db").toString
  }

  // Catalog for handling data source tables. TODO: This really doesn't belong here since it is
  // essentially a cache for metastore tables. However, it relies on a lot of session-specific
  // things so it would be a lot of work to split its functionality between HiveSessionCatalog
  // and HiveCatalog. We should still do it at some point...
  private val metastoreCatalog = new HiveMetastoreCatalog(sparkSession)

  val ParquetConversions: Rule[LogicalPlan] = metastoreCatalog.ParquetConversions
  val OrcConversions: Rule[LogicalPlan] = metastoreCatalog.OrcConversions
  val CreateTables: Rule[LogicalPlan] = metastoreCatalog.CreateTables
  val PreInsertionCasts: Rule[LogicalPlan] = metastoreCatalog.PreInsertionCasts

  override def refreshTable(name: TableIdentifier): Unit = {
    metastoreCatalog.refreshTable(name)
  }

  override def invalidateTable(name: TableIdentifier): Unit = {
    metastoreCatalog.invalidateTable(name)
  }

  def invalidateCache(): Unit = {
    metastoreCatalog.cachedDataSourceTables.invalidateAll()
  }

  def hiveDefaultTableFilePath(name: TableIdentifier): String = {
    metastoreCatalog.hiveDefaultTableFilePath(name)
  }

  // For testing only
  private[hive] def getCachedDataSourceTable(table: TableIdentifier): LogicalPlan = {
    val key = metastoreCatalog.getQualifiedTableName(table)
    metastoreCatalog.cachedDataSourceTables.getIfPresent(key)
  }

  override def makeFunctionBuilder(funcName: String, className: String): FunctionBuilder = {
    makeFunctionBuilder(funcName, Utils.classForName(className))
  }

  /**
   * Construct a [[FunctionBuilder]] based on the provided class that represents a function.
   */
  private def makeFunctionBuilder(name: String, clazz: Class[_]): FunctionBuilder = {
    // When we instantiate hive UDF wrapper class, we may throw exception if the input
    // expressions don't satisfy the hive UDF, such as type mismatch, input number
    // mismatch, etc. Here we catch the exception and throw AnalysisException instead.
    (children: Seq[Expression]) => {
      try {
        if (classOf[UDF].isAssignableFrom(clazz)) {
          val udf = HiveSimpleUDF(name, new HiveFunctionWrapper(clazz.getName), children)
          udf.dataType // Force it to check input data types.
          udf
        } else if (classOf[GenericUDF].isAssignableFrom(clazz)) {
          val udf = HiveGenericUDF(name, new HiveFunctionWrapper(clazz.getName), children)
          udf.dataType // Force it to check input data types.
          udf
        } else if (classOf[AbstractGenericUDAFResolver].isAssignableFrom(clazz)) {
          val udaf = HiveUDAFFunction(name, new HiveFunctionWrapper(clazz.getName), children)
          udaf.dataType // Force it to check input data types.
          udaf
        } else if (classOf[UDAF].isAssignableFrom(clazz)) {
          val udaf = HiveUDAFFunction(
            name,
            new HiveFunctionWrapper(clazz.getName),
            children,
            isUDAFBridgeRequired = true)
          udaf.dataType  // Force it to check input data types.
          udaf
        } else if (classOf[GenericUDTF].isAssignableFrom(clazz)) {
          val udtf = HiveGenericUDTF(name, new HiveFunctionWrapper(clazz.getName), children)
          udtf.elementTypes // Force it to check input data types.
          udtf
        } else {
          throw new AnalysisException(s"No handler for Hive UDF '${clazz.getCanonicalName}'")
        }
      } catch {
        case ae: AnalysisException =>
          throw ae
        case NonFatal(e) =>
          val analysisException =
            new AnalysisException(s"No handler for Hive UDF '${clazz.getCanonicalName}': $e")
          analysisException.setStackTrace(e.getStackTrace)
          throw analysisException
      }
    }
  }

  // We have a list of Hive built-in functions that we do not support. So, we will check
  // Hive's function registry and lazily load needed functions into our own function registry.
  // Those Hive built-in functions are
  // assert_true, collect_list, collect_set, compute_stats, context_ngrams, create_union,
  // current_user ,elt, ewah_bitmap, ewah_bitmap_and, ewah_bitmap_empty, ewah_bitmap_or, field,
  // histogram_numeric, in_file, index, inline, java_method, map_keys, map_values,
  // matchpath, ngrams, noop, noopstreaming, noopwithmap, noopwithmapstreaming,
  // parse_url, parse_url_tuple, percentile, percentile_approx, posexplode, reflect, reflect2,
  // regexp, sentences, stack, std, str_to_map, windowingtablefunction, xpath, xpath_boolean,
  // xpath_double, xpath_float, xpath_int, xpath_long, xpath_number,
  // xpath_short, and xpath_string.
  override def lookupFunction(name: FunctionIdentifier, children: Seq[Expression]): Expression = {
    // TODO: Once lookupFunction accepts a FunctionIdentifier, we should refactor this method to
    // if (super.functionExists(name)) {
    //   super.lookupFunction(name, children)
    // } else {
    //   // This function is a Hive builtin function.
    //   ...
    // }
    Try(super.lookupFunction(name, children)) match {
      case Success(expr) => expr
      case Failure(error) =>
        if (functionRegistry.functionExists(name.unquotedString)) {
          // If the function actually exists in functionRegistry, it means that there is an
          // error when we create the Expression using the given children.
          // We need to throw the original exception.
          throw error
        } else {
          // This function is not in functionRegistry, let's try to load it as a Hive's
          // built-in function.
          // Hive is case insensitive.
          val functionName = name.unquotedString.toLowerCase
          // TODO: This may not really work for current_user because current_user is not evaluated
          // with session info.
          // We do not need to use executionHive at here because we only load
          // Hive's builtin functions, which do not need current db.
          val functionInfo = {
            try {
              Option(HiveFunctionRegistry.getFunctionInfo(functionName)).getOrElse(
                failFunctionLookup(name.unquotedString))
            } catch {
              // If HiveFunctionRegistry.getFunctionInfo throws an exception,
              // we are failing to load a Hive builtin function, which means that
              // the given function is not a Hive builtin function.
              case NonFatal(e) => failFunctionLookup(name.unquotedString)
            }
          }
          val className = functionInfo.getFunctionClass.getName
          val builder = makeFunctionBuilder(functionName, className)
          // Put this Hive built-in function to our function registry.
          val info = new ExpressionInfo(className, functionName)
          createTempFunction(functionName, info, builder, ignoreIfExists = false)
          // Now, we need to create the Expression.
          functionRegistry.lookupFunction(functionName, children)
        }
    }
  }

  // Pre-load a few commonly used Hive built-in functions.
  HiveSessionCatalog.preloadedHiveBuiltinFunctions.foreach {
    case (functionName, clazz) =>
      val builder = makeFunctionBuilder(functionName, clazz)
      val info = new ExpressionInfo(clazz.getCanonicalName, functionName)
      createTempFunction(functionName, info, builder, ignoreIfExists = false)
  }

  /**
   * Generate Create table DDL string for the specified tableIdentifier
   * that is from Hive metastore
   */
  override def generateHiveDDL(ct: CatalogTable): String = {
    val sb = new StringBuilder("CREATE ")
    val processedProperties = scala.collection.mutable.ArrayBuffer.empty[String]

    if (ct.tableType == CatalogTableType.VIRTUAL_VIEW) {
      sb.append(" VIEW " + ct.qualifiedName + " AS " + ct.viewOriginalText.getOrElse(""))
    } else {
      if (ct.tableType == CatalogTableType.EXTERNAL_TABLE) {
        processedProperties += "EXTERNAL"
        sb.append(" EXTERNAL TABLE " + ct.qualifiedName)
      } else {
        sb.append(" TABLE " + ct.qualifiedName)
      }
      // column list
      val cols = ct.schema map { col =>
        col.name + " " + col.dataType + (col.comment.getOrElse("") match {
          case cmt: String if cmt.length > 0 => " COMMENT '" + escapeHiveCommand(cmt) + "'"
          case _ => ""
        })
        // hive ddl does not honor NOT NULL, it is always default to be nullable
      }
      sb.append(cols.mkString("(", ", ", ")") + "\n")

      // table comment
      sb.append(" " +
        ct.properties.getOrElse("comment", new String) match {
        case tcmt: String if tcmt.trim.length > 0 =>
          processedProperties += "comment"
          " COMMENT '" + escapeHiveCommand(tcmt.trim) + "'\n"
        case _ => ""
      })

      // partitions
      val partCols = ct.partitionColumns map { col =>
        col.name + " " + col.dataType + (col.comment.getOrElse("") match {
          case cmt: String if cmt.length > 0 => " COMMENT '" + escapeHiveCommand(cmt) + "'"
          case _ => ""
        })
      }
      if (partCols != null && partCols.size > 0) {
        sb.append(" PARTITIONED BY ")
        sb.append(partCols.mkString("( ", ", ", " )") + "\n")
      }

      // sort bucket
      if (ct.bucketColumns.size > 0) {
        processedProperties += "SORTBUCKETCOLSPREFIX"
        sb.append(" CLUSTERED BY ")
        sb.append(ct.bucketColumns.mkString("( ", ", ", " )"))

        // TODO sort columns don't have the the right scala types yet. need to adapt to Hive Order
        if (ct.sortColumns.size > 0) {
          sb.append(" SORTED BY ")
          sb.append(ct.sortColumns.map(_.name).mkString("( ", ", ", " )"))
        }
        sb.append(" INTO " + ct.numBuckets + " BUCKETS\n")
      }

      // TODO CatalogTable does not implement skew spec yet
      // skew spec
      // TODO StorageHandler case is not handled yet, since CatalogTable does not have it yet
      // row format
      sb.append(" ROW FORMAT ")

      val serdeProps = ct.storage.serdeProperties
      val delimiterPrefixes =
        Seq("FIELDS TERMINATED BY",
          "COLLECTION ITEMS TERMINATED BY",
          "MAP KEYS TERMINATED BY",
          "LINES TERMINATED BY",
          "NULL DEFINED AS")

      val delimiters = Seq(
        serdeProps.get("field.delim"),
        serdeProps.get("colelction.delim"),
        serdeProps.get("mapkey.delim"),
        serdeProps.get("line.delim"),
        serdeProps.get("serialization.null.format")).zipWithIndex

      val delimiterStrs = delimiters collect {
        case (Some(ch), i) =>
          delimiterPrefixes(i) + " '" +
            escapeHiveCommand(ch) +
            "' "
      }
      if (delimiterStrs.size > 0) {
        sb.append("DELIMITED ")
        sb.append(delimiterStrs.mkString(" ") + "\n")
      } else {
        sb.append("SERDE '")
        sb.append(escapeHiveCommand(ct.storage.serde.getOrElse("")) + "' \n")
      }

      sb.append("STORED AS INPUTFORMAT '" +
        escapeHiveCommand(ct.storage.inputFormat.getOrElse("")) + "' \n")
      sb.append("OUTPUTFORMAT  '" +
        escapeHiveCommand(ct.storage.outputFormat.getOrElse("")) + "' \n")

      // table location
      sb.append("LOCATION '" +
        escapeHiveCommand(ct.storage.locationUri.getOrElse("")) + "' \n")

      // table properties
      val propertPairs = ct.properties collect {
        case (k, v) if !processedProperties.contains(k) =>
          "'" + escapeHiveCommand(k) + "'='" + escapeHiveCommand(v) + "'"
      }
      if (propertPairs.size>0) {
        sb.append("TBLPROPERTIES " + propertPairs.mkString("( ", ", \n", " )") + "\n")
      }
    }
    sb.toString()
  }

  private def generateDataSourceDDL(ct: CatalogTable): String = {
    val sb = new StringBuilder("CREATE TABLE " + ct.qualifiedName)
    // TODO will continue on generating Datasource syntax DDL
    // will remove generateHiveDDL once it is done.
    generateHiveDDL(ct)
  }

  /**
   * Generate Create table DDL string for the specified tableIdentifier
   * that is from Hive metastore
   */
  override def generateTableDDL(name: TableIdentifier): String = {
    val ct = this.getTable(name)
    if(ct.properties.get("spark.sql.sources.provider") == None) {
      // CREATE [TEMPORARY] TABLE <tablename> ... ROW FORMAT.. TBLPROPERTIES (...)
      generateHiveDDL(ct)
    } else {
      // CREATE [TEMPORARY] TABLE <tablename> .... USING .... OPTIONS (...)
      generateDataSourceDDL(ct)
    }
  }

  private def escapeHiveCommand(str: String): String = {
    str.map{c =>
      if (c == '\'' || c == ';') {
        '\\'
      } else {
        c
      }
    }
  }
}

private[sql] object HiveSessionCatalog {
  // This is the list of Hive's built-in functions that are commonly used and we want to
  // pre-load when we create the FunctionRegistry.
  val preloadedHiveBuiltinFunctions =
    ("collect_set", classOf[org.apache.hadoop.hive.ql.udf.generic.GenericUDAFCollectSet]) ::
    ("collect_list", classOf[org.apache.hadoop.hive.ql.udf.generic.GenericUDAFCollectList]) :: Nil
}
