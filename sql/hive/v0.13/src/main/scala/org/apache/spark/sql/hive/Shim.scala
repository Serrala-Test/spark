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

import org.apache.hadoop.hive.ql.processors.CommandProcessorFactory
import scala.language.implicitConversions
import org.apache.hadoop.hive.ql.plan.{FileSinkDesc, TableDesc}
import org.apache.hadoop.hive.common.`type`.{HiveDecimal}
import scala.collection.JavaConversions._
import org.apache.spark.Logging
import org.apache.hadoop.hive.serde2.ColumnProjectionUtils
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.hive.ql.Context
import org.apache.hadoop.hive.ql.metadata.Partition
import org.apache.hadoop.{io => hadoopIo}
import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.common.StatsSetupConst
import org.apache.hadoop.mapred.InputFormat
import java.util.Properties
import org.apache.hadoop.hive.serde2.Deserializer

/*hive-0.13.1 support shimmer layer*/
object HiveShim {
  val version = "0.13.1"
  /*
   * hive-0.13 support DECIMAL(precision, scale), DECIMAL in hive-0.12 is actually DECIMAL(10,0)
   * Full support of new decimal feature need to be fixed in seperate PR.
   */
  val metastoreDecimal = "decimal(10,0)"
  def getTableDesc(serdeClass: Class[_ <: Deserializer], inputFormatClass: Class[_ <: InputFormat[_, _]], outputFormatClass: Class[_], properties: Properties) = {
    new TableDesc(inputFormatClass, outputFormatClass, properties)
  }
  def getStatsSetupConstTotalSize = StatsSetupConst.TOTAL_SIZE
  def createDefaultDBIfNeeded(context: HiveContext) ={
    context.runSqlHive("CREATE DATABASE default")
    context.runSqlHive("USE default")
  }
  /*handle the difference in HiveQuerySuite*/
  def getEmptyCommentsFieldValue = ""

  def convertCatalystString2Hive(s: String) = s

  def getCommandProcessor(cmd: Array[String], conf: HiveConf) =  {
    CommandProcessorFactory.get(cmd, conf)
  }

  def createDecimal(bd: java.math.BigDecimal): HiveDecimal = {
    HiveDecimal.create(bd)
  }

  /*
   * This function in hive-0.13 become private, but we have to do this to walkaround hive bug
   * */
  private def appendReadColumnNames(conf: Configuration, cols: Seq[String]) {
    val old: String = conf.get(ColumnProjectionUtils.READ_COLUMN_NAMES_CONF_STR, "")
    val result: StringBuilder = new StringBuilder(old)
    var first: Boolean = old.isEmpty

    for (col <- cols) {
      if (first) {
        first = false
      }
      else {
        result.append(',')
      }
      result.append(col)
    }
    conf.set(ColumnProjectionUtils.READ_COLUMN_NAMES_CONF_STR, result.toString)
  }

  /*
   * Cannot use ColumnProjectionUtils.appendReadColumns directly, if ids is null or empty
   * */
  def appendReadColumns(conf: Configuration, ids: Seq[Integer], names: Seq[String]) {
    if (ids != null && ids.size > 0) {
      ColumnProjectionUtils.appendReadColumns(conf, ids)
    } else {
      appendReadColumnNames(conf, names)
    }
  }

  /*
   * Bug introdiced in hive-0.13. FileSinkDesc is serilizable, but its member path is not.
   * Fix it through wrapper.
   * */
  implicit def wrapperToFileSinkDesc(w: ShimFileSinkDesc): FileSinkDesc = {
    var f = new FileSinkDesc(new Path(w.dir), w.tableInfo, w.compressed)
    f.setCompressed(w.compressed)
    f.setCompressCodec(w.compressCodec)
    f.setCompressType(w.compressType)
    f.setTableInfo(w.tableInfo)
    f
  }

  implicit class wrapperToPartition(p: Partition) {
    def getDataLocationPath: Path = p.getDataLocation
  }
}

class ShimContext(conf: Configuration) extends Context(conf) {
  def  getExternalTmpPath(path: Path): Path = {
    super.getExternalTmpPath (path.toUri)
  }
}

class ShimFileSinkDesc(var dir: String, var tableInfo: TableDesc, var compressed: Boolean)
  extends Serializable with Logging {
  var compressCodec: String = _
  var compressType: String = _
  var destTableId: Int = _

  def setCompressed(compressed: Boolean) {
    this.compressed = compressed
  }
  def getDirName = dir
  def setDestTableId(destTableId: Int) {
    this.destTableId = destTableId
  }

  def setTableInfo(tableInfo: TableDesc) {
    this.tableInfo = tableInfo
  }

  def setCompressCodec(intermediateCompressorCodec: String) {
    compressCodec = intermediateCompressorCodec
  }

  def setCompressType(intermediateCompressType: String) {
    compressType = intermediateCompressType
  }
}
