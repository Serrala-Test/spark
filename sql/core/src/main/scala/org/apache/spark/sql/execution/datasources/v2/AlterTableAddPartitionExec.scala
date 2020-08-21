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

package org.apache.spark.sql.execution.datasources.v2

import scala.collection.JavaConverters._

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.PartitionsAlreadyExistException
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.connector.catalog.{SupportsAtomicPartitionManagement, SupportsPartitionManagement}

/**
 * Physical plan node for adding partitions of table.
 */
case class AlterTableAddPartitionExec(
    table: SupportsPartitionManagement,
    partitions: Seq[(InternalRow, Map[String, String])],
    ignoreIfExists: Boolean) extends V2CommandExec {
  import DataSourceV2Implicits._

  override def output: Seq[Attribute] = Seq.empty

  override protected def run(): Seq[InternalRow] = {
    val existsPartIdents = partitions.map(_._1).filter(table.partitionExists)
    if (existsPartIdents.nonEmpty && !ignoreIfExists) {
      throw new PartitionsAlreadyExistException(
        table.name(), existsPartIdents, table.partitionSchema())
    }

    val notExistsPartitions =
      partitions.filterNot(part => existsPartIdents.contains(part._1))
    notExistsPartitions match {
      case Seq() => // Nothing will be done
      case Seq((partIdent, properties)) =>
        table.createPartition(partIdent, properties.asJava)
      case Seq(_ *) if table.isInstanceOf[SupportsAtomicPartitionManagement] =>
        table.asAtomicPartitionable
          .createPartitions(
            notExistsPartitions.map(_._1).toArray,
            notExistsPartitions.map(_._2.asJava).toArray)
      case _ =>
        throw new UnsupportedOperationException(
          s"Nonatomic partition table ${table.name()} can not add multiple partitions.")
    }
    Seq.empty
  }
}
