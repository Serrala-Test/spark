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

package org.apache.spark.sql.execution.stat

import org.apache.spark.Logging
import org.apache.spark.sql.{Column, DataFrame, Row}
import org.apache.spark.sql.catalyst.plans.logical.LocalRelation
import org.apache.spark.sql.types.{ArrayType, StructField, StructType}

import scala.collection.mutable.{Map => MutableMap}

private[sql] object FrequentItems extends Logging {

  /** A helper class wrapping `MutableMap[Any, Long]` for simplicity. */
  private class FreqItemCounter(size: Int) extends Serializable {
    val baseMap: MutableMap[Any, Long] = MutableMap.empty[Any, Long]

    /**
     * Add a new example to the counts if it exists, otherwise deduct the count
     * from existing items.
     */
    def add(key: Any, count: Long): this.type = {
      if (baseMap.contains(key))  {
        baseMap(key) += count
      } else {
        if (baseMap.size < size) {
          baseMap += key -> count
        } else {
          // TODO: Make this more efficient... A flatMap?
          baseMap.retain((k, v) => v > count)
          baseMap.transform((k, v) => v - count)
        }
      }
      this
    }

    /**
     * Merge two maps of counts.
     * @param other The map containing the counts for that partition
     */
    def merge(other: FreqItemCounter): this.type = {
      other.toSeq.foreach { case (k, v) =>
        add(k, v)
      }
      this
    }
    
    def toSeq: Seq[(Any, Long)] = baseMap.toSeq
    
    def foldLeft[A, B](start: A)(f: (A, (Any, Long)) => A): A = baseMap.foldLeft(start)(f)
    
    def freqItems: Seq[Any] = baseMap.keys.toSeq
  }

  /**
   * Finding frequent items for columns, possibly with false positives. Using the 
   * frequent element count algorithm described in
   * [[http://dx.doi.org/10.1145/762471.762473, proposed by Karp, Schenker, and Papadimitriou]].
   * For Internal use only.
   *
   * @param df The input DataFrame
   * @param cols the names of the columns to search frequent items in
   * @param support The minimum frequency for an item to be considered `frequent`
   * @return A Local DataFrame with the Array of frequent items for each column.
   */
  private[sql] def singlePassFreqItems(
      df: DataFrame, 
      cols: Seq[String],
      support: Double): DataFrame = {
    if (support < 1e-6) {
      logWarning(s"The selected support ($support) is too small, and might cause memory problems.")
    }
    val numCols = cols.length
    // number of max items to keep counts for
    val sizeOfMap = (1 / support).toInt
    val countMaps = Seq.tabulate(numCols)(i => new FreqItemCounter(sizeOfMap))
    val originalSchema = df.schema
    val colInfo = cols.map { name =>
      val index = originalSchema.fieldIndex(name)
      (name, originalSchema.fields(index).dataType)
    }
    
    val freqItems = df.select(cols.map(Column(_)):_*).rdd.aggregate(countMaps)(
      seqOp = (counts, row) => {
        var i = 0
        while (i < numCols) {
          val thisMap = counts(i)
          val key = row.get(i)
          thisMap.add(key, 1L)
          i += 1
        }
        counts
      },
      combOp = (baseCounts, counts) => {
        var i = 0
        while (i < numCols) {
          baseCounts(i).merge(counts(i))
          i += 1
        }
        baseCounts
      }
    )
    val justItems = freqItems.map(m => m.freqItems)
    val resultRow = Row(justItems:_*)
    // append frequent Items to the column name for easy debugging
    val outputCols = colInfo.map{ v =>
      StructField(v._1 + "_freqItems", ArrayType(v._2, false))
    }
    val schema = StructType(outputCols).toAttributes
    new DataFrame(df.sqlContext, LocalRelation(schema, Seq(resultRow)))
  }
}
