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

package org.apache.spark.examples

import java.io.File
import scala.io.Source._
import org.apache.spark.{SparkContext, SparkConf}
import org.apache.spark.SparkContext._

/**
  *  Simple test for reading and writing to a distributed
  *  file system.  This example does the following:
  *  
  *    1. Reads local file
  *    2. Computes word count on local file
  *    3. Writes local file to a DFS
  *    4. Reads the file back from the DFS
  *    5. Computes word count on the file using Spark
  *    6. Compares the word count results
  */
object DFSReadWriteTest {
  
  private var localFilePath: File = new File(".")
  private var dfsDirPath: File = new File(".")

  private val NPARAMS = 2

  private def readFile(filename: String): List[String] = {
    val lineIter: Iterator[String] = fromFile(filename).getLines()
    val lineList: List[String] = lineIter.toList
    lineList
  }

  private def printUsage() {
    val usage: String = "DFS Read-Write Test\n" +
    "\n" +
    "Usage: localFile dfsDir\n" +
    "\n" +
    "localFile - (string) local file to use in test\n" +
    "dfsDir - (string) DFS directory for read/write tests\n"

    println(usage)
  }

  private def parseArgs(args: Array[String]) {
    if(args.length != NPARAMS) {
      printUsage()
      System.exit(1)
    }

    var i = 0

    localFilePath = new File(args(i))
    if(!localFilePath.exists) {
      System.err.println("Given path (" + args(i) + ") does not exist.\n")
      printUsage()
      System.exit(1)
    }

    if(!localFilePath.isFile) {
      System.err.println("Given path (" + args(i) + ") is not a file.\n")
      printUsage()
      System.exit(1)
    }

    i += 1
    dfsDirPath = new File(args(i))
    if(!dfsDirPath.exists) {
      System.err.println("Given path (" + args(i) + ") does not exist.\n")
      printUsage()
      System.exit(1)
    }

    if(dfsDirPath.isDirectory) {
      System.err.println("Given path (" + args(i) + ") is not a directory.\n")
      printUsage()
      System.exit(1)
    }
  }

  def runLocalWordCount(fileContents: List[String]): Int = {
    fileContents.flatMap(_.split(" "))
      .flatMap(_.split("\t"))
      .filter(_.size > 0)
      .groupBy(w => w)
      .mapValues(_.size)
      .values
      .sum
  }

  def main(args: Array[String]) {
    parseArgs(args)

    println("Performing local word count")
    val fileContents = readFile(localFilePath.toString())
    val localWordCount = runLocalWordCount(fileContents)

    println("Creating SparkConf")
    val conf = new SparkConf().setAppName("DFS Read Write Test")

    println("Creating SparkContext")
    val sc = new SparkContext(conf)

    println("Writing local file to DFS")
    val dfsFilename = dfsDirPath.toString() + "/dfs_read_write_test"
    val fileRDD = sc.parallelize(fileContents)
    fileRDD.saveAsTextFile(dfsFilename)

    println("Reading file from DFS and running Word Count")
    val readFileRDD = sc.textFile(dfsFilename)

    val dfsWordCount = readFileRDD
      .flatMap(_.split(" "))
      .flatMap(_.split("\t"))
      .filter(_.size > 0)
      .map(w => (w, 1))
      .countByKey()
      .values
      .sum

    sc.stop()

    if(localWordCount == dfsWordCount) {
      println(s"Success! Local Word Count ($localWordCount) " +
        "and DFS Word Count ($dfsWordCount) agree.")
    } else {
      println(s"Failure! Local Word Count ($localWordCount) " +
        "and DFS Word Count ($dfsWordCount) disagree.")
    }

  }
}
