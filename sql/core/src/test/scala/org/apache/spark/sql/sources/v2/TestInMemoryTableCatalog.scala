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

package org.apache.spark.sql.sources.v2

import java.util
import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConverters._
import scala.collection.mutable

import org.apache.spark.sql.catalog.v2.{CatalogV2Implicits, Identifier, TableCatalog, TableChange, TestTableCatalog, TransactionalTableCatalog}
import org.apache.spark.sql.catalog.v2.expressions.Transform
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.{NoSuchTableException, TableAlreadyExistsException}
import org.apache.spark.sql.sources.v2.reader.{Batch, InputPartition, PartitionReader, PartitionReaderFactory, Scan, ScanBuilder}
import org.apache.spark.sql.sources.v2.writer.{BatchWrite, DataWriter, DataWriterFactory, SupportsTruncate, WriteBuilder, WriterCommitMessage}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.util.CaseInsensitiveStringMap

// this is currently in the spark-sql module because the read and write API is not in catalyst
// TODO(rdblue): when the v2 source API is in catalyst, merge with TestTableCatalog/InMemoryTable
class TestInMemoryTableCatalog extends TableCatalog {
  import CatalogV2Implicits._

  protected val tables: util.Map[Identifier, InMemoryTable] =
    new ConcurrentHashMap[Identifier, InMemoryTable]()
  private var _name: Option[String] = None

  override def initialize(name: String, options: CaseInsensitiveStringMap): Unit = {
    _name = Some(name)
  }

  override def name: String = _name.get

  override def listTables(namespace: Array[String]): Array[Identifier] = {
    tables.keySet.asScala.filter(_.namespace.sameElements(namespace)).toArray
  }

  override def loadTable(ident: Identifier): Table = {
    Option(tables.get(ident)) match {
      case Some(table) =>
        table
      case _ =>
        throw new NoSuchTableException(ident)
    }
  }

  override def createTable(
      ident: Identifier,
      schema: StructType,
      partitions: Array[Transform],
      properties: util.Map[String, String]): Table = {
    if (tables.containsKey(ident)) {
      throw new TableAlreadyExistsException(ident)
    }
    TestInMemoryTableCatalog.maybeSimulateFailedTableCreation(properties)
    if (partitions.nonEmpty) {
      throw new UnsupportedOperationException(
        s"Catalog $name: Partitioned tables are not supported")
    }

    val table = new InMemoryTable(s"$name.${ident.quoted}", schema, properties)

    tables.put(ident, table)

    table
  }

  override def alterTable(ident: Identifier, changes: TableChange*): Table = {
    Option(tables.get(ident)) match {
      case Some(table) =>
        val properties = TestTableCatalog.applyPropertiesChanges(table.properties, changes)
        val schema = TestTableCatalog.applySchemaChanges(table.schema, changes)
        val newTable = new InMemoryTable(table.name, schema, properties, table.data)

        tables.put(ident, newTable)

        newTable
      case _ =>
        throw new NoSuchTableException(ident)
    }
  }

  override def dropTable(ident: Identifier): Boolean = Option(tables.remove(ident)).isDefined

  def clearTables(): Unit = {
    tables.clear()
  }
}

/**
 * A simple in-memory table. Rows are stored as a buffered group produced by each output task.
 */
class InMemoryTable(
    val name: String,
    val schema: StructType,
    override val properties: util.Map[String, String])
  extends Table with SupportsRead with SupportsWrite {

  def this(
      name: String,
      schema: StructType,
      properties: util.Map[String, String],
      data: Array[BufferedRows]) = {
    this(name, schema, properties)
    replaceData(data)
  }

  def rows: Seq[InternalRow] = data.flatMap(_.rows)

  @volatile var data: Array[BufferedRows] = Array.empty

  def replaceData(buffers: Array[BufferedRows]): Unit = synchronized {
    data = buffers
  }

  override def capabilities: util.Set[TableCapability] = Set(
    TableCapability.BATCH_READ, TableCapability.BATCH_WRITE, TableCapability.TRUNCATE).asJava

  override def newScanBuilder(options: CaseInsensitiveStringMap): ScanBuilder = {
    () => new InMemoryBatchScan(data.map(_.asInstanceOf[InputPartition]))
  }

  class InMemoryBatchScan(data: Array[InputPartition]) extends Scan with Batch {
    override def readSchema(): StructType = schema

    override def toBatch: Batch = this

    override def planInputPartitions(): Array[InputPartition] = data

    override def createReaderFactory(): PartitionReaderFactory = BufferedRowsReaderFactory
  }

  override def newWriteBuilder(options: CaseInsensitiveStringMap): WriteBuilder = {
    TestInMemoryTableCatalog.maybeSimulateFailedTableWrite(options)
    new WriteBuilder with SupportsTruncate {
      private var shouldTruncate: Boolean = false

      override def truncate(): WriteBuilder = {
        shouldTruncate = true
        this
      }

      override def buildForBatch(): BatchWrite = {
        if (shouldTruncate) TruncateAndAppend else Append
      }
    }
  }

  private object TruncateAndAppend extends BatchWrite {
    override def createBatchWriterFactory(): DataWriterFactory = {
      BufferedRowsWriterFactory
    }

    override def commit(messages: Array[WriterCommitMessage]): Unit = {
      replaceData(messages.map(_.asInstanceOf[BufferedRows]))
    }

    override def abort(messages: Array[WriterCommitMessage]): Unit = {
    }
  }

  private object Append extends BatchWrite {
    override def createBatchWriterFactory(): DataWriterFactory = {
      BufferedRowsWriterFactory
    }

    override def commit(messages: Array[WriterCommitMessage]): Unit = {
      replaceData(data ++ messages.map(_.asInstanceOf[BufferedRows]))
    }

    override def abort(messages: Array[WriterCommitMessage]): Unit = {
    }
  }
}

object TestInMemoryTableCatalog {
  val SIMULATE_FAILED_WRITE_OPTION = "spark.sql.test.simulateFailedWrite"
  val SIMULATE_FAILED_CREATE_PROPERTY = "spark.sql.test.simulateFailedCreate"

  def maybeSimulateFailedTableCreation(tableProperties: util.Map[String, String]): Unit = {
    if (tableProperties.containsKey(TestInMemoryTableCatalog.SIMULATE_FAILED_CREATE_PROPERTY)
      && tableProperties.get(TestInMemoryTableCatalog.SIMULATE_FAILED_CREATE_PROPERTY)
      .equalsIgnoreCase("true")) {
      throw new IllegalStateException("Manual create table failure.")
    }
  }

  def maybeSimulateFailedTableWrite(tableOptions: CaseInsensitiveStringMap): Unit = {
    if (tableOptions.containsKey(TestInMemoryTableCatalog.SIMULATE_FAILED_WRITE_OPTION)
      && tableOptions.get(TestInMemoryTableCatalog.SIMULATE_FAILED_WRITE_OPTION)
      .equalsIgnoreCase("true")) {
      throw new IllegalStateException("Manual write to table failure.")
    }
  }
}

class TestTransactionalInMemoryCatalog
  extends TestInMemoryTableCatalog with TransactionalTableCatalog {

  override def stageCreate(
      ident: Identifier,
      schema: StructType,
      partitions: Array[Transform],
      properties: util.Map[String, String]): StagedTable = {
    newStagedTable(ident, schema, partitions, properties, false)
  }

  override def stageReplace(
      ident: Identifier,
      schema: StructType,
      partitions: Array[Transform],
      properties: util.Map[String, String]): StagedTable = {
    newStagedTable(ident, schema, partitions, properties, true)
  }

  private def newStagedTable(
      ident: Identifier,
      schema: StructType,
      partitions: Array[Transform],
      properties: util.Map[String, String],
      replaceIfExists: Boolean): StagedTable = {
    import CatalogV2Implicits.IdentifierHelper
    if (partitions.nonEmpty) {
      throw new UnsupportedOperationException(
        s"Catalog $name: Partitioned tables are not supported")
    }

    TestInMemoryTableCatalog.maybeSimulateFailedTableCreation(properties)

    new TestStagedTable(
      ident,
      new InMemoryTable(s"$name.${ident.quoted}", schema, properties),
      replaceIfExists)
  }

  private class TestStagedTable(
      ident: Identifier,
      delegateTable: InMemoryTable,
      replaceIfExists: Boolean)
    extends StagedTable with SupportsWrite with SupportsRead {

    override def commitStagedChanges(): Unit = {
      if (replaceIfExists) {
        tables.put(ident, delegateTable)
      } else {
        val maybePreCommittedTable = tables.putIfAbsent(ident, delegateTable)
        if (maybePreCommittedTable != null) {
          throw new TableAlreadyExistsException(
            s"Table with identifier $ident and name $name was already created.")
        }
      }
    }

    override def abortStagedChanges(): Unit = {}

    override def name(): String = delegateTable.name

    override def schema(): StructType = delegateTable.schema

    override def capabilities(): util.Set[TableCapability] = delegateTable.capabilities

    override def newWriteBuilder(options: CaseInsensitiveStringMap): WriteBuilder = {
      delegateTable.newWriteBuilder(options)
    }

    override def newScanBuilder(options: CaseInsensitiveStringMap): ScanBuilder = {
      delegateTable.newScanBuilder(options)
    }
  }
}

class BufferedRows extends WriterCommitMessage with InputPartition with Serializable {
  val rows = new mutable.ArrayBuffer[InternalRow]()
}

private object BufferedRowsReaderFactory extends PartitionReaderFactory {
  override def createReader(partition: InputPartition): PartitionReader[InternalRow] = {
    new BufferedRowsReader(partition.asInstanceOf[BufferedRows])
  }
}

private class BufferedRowsReader(partition: BufferedRows) extends PartitionReader[InternalRow] {
  private var index: Int = -1

  override def next(): Boolean = {
    index += 1
    index < partition.rows.length
  }

  override def get(): InternalRow = partition.rows(index)

  override def close(): Unit = {}
}

private object BufferedRowsWriterFactory extends DataWriterFactory {
  override def createWriter(partitionId: Int, taskId: Long): DataWriter[InternalRow] = {
    new BufferWriter
  }
}

private class BufferWriter extends DataWriter[InternalRow] {
  private val buffer = new BufferedRows

  override def write(row: InternalRow): Unit = buffer.rows.append(row.copy())

  override def commit(): WriterCommitMessage = buffer

  override def abort(): Unit = {}
}
