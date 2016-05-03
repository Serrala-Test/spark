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

package org.apache.spark.sql.execution.datasources

import scala.collection.mutable

import org.apache.hadoop.fs.{FileStatus, Path}

import org.apache.spark.internal.Logging
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.{expressions, InternalRow}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.types.{StringType, StructType}


/**
 * An abstract class that represents [[FileCatalog]]s that are aware of partitioned tables.
 * It provides the necessary methods to parse partition data based on a set of files.
 *
 * @param parameters as set of options to control partition discovery
 * @param partitionSchema an optional partition schema that will be use to provide types for the
 *                        discovered partitions
*/
abstract class PartitioningAwareFileCatalog(
    sparkSession: SparkSession,
    parameters: Map[String, String],
    partitionSchema: Option[StructType])
  extends FileCatalog with Logging {

  protected val hadoopConf = sparkSession.sessionState.newHadoopConfWithOptions(parameters)

  protected def leafFiles: mutable.LinkedHashMap[Path, FileStatus]

  protected def leafDirToChildrenFiles: Map[Path, Array[FileStatus]]

  override def listFiles(filters: Seq[Expression]): Seq[Partition] = {
    if (partitionSpec().partitionColumns.isEmpty) {
      Partition(InternalRow.empty, allFiles().filterNot(_.getPath.getName startsWith "_")) :: Nil
    } else {
      prunePartitions(filters, partitionSpec()).map {
        case PartitionDirectory(values, path) =>
          Partition(
            values,
            leafDirToChildrenFiles(path).filterNot(_.getPath.getName startsWith "_"))
      }
    }
  }

  override def allFiles(): Seq[FileStatus] = leafFiles.values.toSeq

  protected def inferPartitioning(): PartitionSpec = {
    // We use leaf dirs containing data files to discover the schema.
    val leafDirs = leafDirToChildrenFiles.keys.toSeq
    partitionSchema match {
      case Some(userProvidedSchema) if userProvidedSchema.nonEmpty =>
        val spec = PartitioningUtils.parsePartitions(
          leafDirs,
          PartitioningUtils.DEFAULT_PARTITION_NAME,
          typeInference = false,
          basePaths = basePaths)

        // Without auto inference, all of value in the `row` should be null or in StringType,
        // we need to cast into the data type that user specified.
        def castPartitionValuesToUserSchema(row: InternalRow) = {
          InternalRow((0 until row.numFields).map { i =>
            Cast(
              Literal.create(row.getUTF8String(i), StringType),
              userProvidedSchema.fields(i).dataType).eval()
          }: _*)
        }

        PartitionSpec(userProvidedSchema, spec.partitions.map { part =>
          part.copy(values = castPartitionValuesToUserSchema(part.values))
        })
      case _ =>
        PartitioningUtils.parsePartitions(
          leafDirs,
          PartitioningUtils.DEFAULT_PARTITION_NAME,
          typeInference = sparkSession.sessionState.conf.partitionColumnTypeInferenceEnabled(),
          basePaths = basePaths)
    }
  }

  private def prunePartitions(
      predicates: Seq[Expression],
      partitionSpec: PartitionSpec): Seq[PartitionDirectory] = {
    val PartitionSpec(partitionColumns, partitions) = partitionSpec
    val partitionColumnNames = partitionColumns.map(_.name).toSet
    val partitionPruningPredicates = predicates.filter {
      _.references.map(_.name).toSet.subsetOf(partitionColumnNames)
    }

    if (partitionPruningPredicates.nonEmpty) {
      val predicate = partitionPruningPredicates.reduce(expressions.And)

      val boundPredicate = InterpretedPredicate.create(predicate.transform {
        case a: AttributeReference =>
          val index = partitionColumns.indexWhere(a.name == _.name)
          BoundReference(index, partitionColumns(index).dataType, nullable = true)
      })

      val selected = partitions.filter {
        case PartitionDirectory(values, _) => boundPredicate(values)
      }
      logInfo {
        val total = partitions.length
        val selectedSize = selected.length
        val percentPruned = (1 - selectedSize.toDouble / total.toDouble) * 100
        s"Selected $selectedSize partitions out of $total, pruned $percentPruned% partitions."
      }

      selected
    } else {
      partitions
    }
  }

  /**
   * Contains a set of paths that are considered as the base dirs of the input datasets.
   * The partitioning discovery logic will make sure it will stop when it reaches any
   * base path. By default, the paths of the dataset provided by users will be base paths.
   * For example, if a user uses `sqlContext.read.parquet("/path/something=true/")`, the base path
   * will be `/path/something=true/`, and the returned DataFrame will not contain a column of
   * `something`. If users want to override the basePath. They can set `basePath` in the options
   * to pass the new base path to the data source.
   * For the above example, if the user-provided base path is `/path/`, the returned
   * DataFrame will have the column of `something`.
   */
  private def basePaths: Set[Path] = {
    val userDefinedBasePath = parameters.get("basePath").map(basePath => Set(new Path(basePath)))
    userDefinedBasePath.getOrElse {
      // If the user does not provide basePath, we will just use paths.
      paths.toSet
    }.map { hdfsPath =>
      // Make the path qualified (consistent with listLeafFiles and listLeafFilesInParallel).
      val fs = hdfsPath.getFileSystem(hadoopConf)
      hdfsPath.makeQualified(fs.getUri, fs.getWorkingDirectory)
    }
  }
}
