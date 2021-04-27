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

object SplitTest {
  // scalastyle:off
  def main(args: Array[String]): Unit = {
    val goldenOutput = "-- Automatically generated by SQLQueryTestSuite\n-- Number of queries: 20\n\n\n-- !query\ncreate temporary view data as select * from values\n  (\"one\", array(11, 12, 13), array(array(111, 112, 113), array(121, 122, 123))),\n  (\"two\", array(21, 22, 23), array(array(211, 212, 213), array(221, 222, 223)))\n  as data(a, b, c)\n-- !query schema\nstruct<>\n-- !query output\n\n\n\n-- !query\nselect * from data\n-- !query schema\nstruct<a:string,b:array<int>,c:array<array<int>>>\n-- !query output\none\t[11,12,13]\t[[111,112,113],[121,122,123]]\ntwo\t[21,22,23]\t[[211,212,213],[221,222,223]]\n\n\n-- !query\nselect a, b[0], b[0] + b[1] from data\n-- !query schema\nstruct<a:string,b[0]:int,(b[0] + b[1]):int>\n-- !query output\none\t11\t23\ntwo\t21\t43\n\n\n-- !query\nselect a, c[0][0] + c[0][0 + 1] from data\n-- !query schema\nstruct<a:string,(c[0][0] + c[0][(0 + 1)]):int>\n-- !query output\none\t223\ntwo\t423\n\n\n-- !query\ncreate temporary view primitive_arrays as select * from values (\n  array(true),\n  array(2Y, 1Y),\n  array(2S, 1S),\n  array(2, 1),\n  array(2L, 1L),\n  array(9223372036854775809, 9223372036854775808),\n  array(2.0D, 1.0D),\n  array(float(2.0), float(1.0)),\n  array(date '2016-03-14', date '2016-03-13'),\n  array(timestamp '2016-11-15 20:54:00.000',  timestamp '2016-11-12 20:54:00.000')\n) as primitive_arrays(\n  boolean_array,\n  tinyint_array,\n  smallint_array,\n  int_array,\n  bigint_array,\n  decimal_array,\n  double_array,\n  float_array,\n  date_array,\n  timestamp_array\n)\n-- !query schema\nstruct<>\n-- !query output\n\n\n\n-- !query\nselect * from primitive_arrays\n-- !query schema\nstruct<boolean_array:array<boolean>,tinyint_array:array<tinyint>,smallint_array:array<smallint>,int_array:array<int>,bigint_array:array<bigint>,decimal_array:array<decimal(19,0)>,double_array:array<double>,float_array:array<float>,date_array:array<date>,timestamp_array:array<timestamp>>\n-- !query output\n[true]\t[2,1]\t[2,1]\t[2,1]\t[2,1]\t[9223372036854775809,9223372036854775808]\t[2.0,1.0]\t[2.0,1.0]\t[2016-03-14,2016-03-13]\t[2016-11-15 20:54:00,2016-11-12 20:54:00]\n\n\n-- !query\nselect\n  array_contains(boolean_array, true), array_contains(boolean_array, false),\n  array_contains(tinyint_array, 2Y), array_contains(tinyint_array, 0Y),\n  array_contains(smallint_array, 2S), array_contains(smallint_array, 0S),\n  array_contains(int_array, 2), array_contains(int_array, 0),\n  array_contains(bigint_array, 2L), array_contains(bigint_array, 0L),\n  array_contains(decimal_array, 9223372036854775809), array_contains(decimal_array, 1),\n  array_contains(double_array, 2.0D), array_contains(double_array, 0.0D),\n  array_contains(float_array, float(2.0)), array_contains(float_array, float(0.0)),\n  array_contains(date_array, date '2016-03-14'), array_contains(date_array, date '2016-01-01'),\n  array_contains(timestamp_array, timestamp '2016-11-15 20:54:00.000'), array_contains(timestamp_array, timestamp '2016-01-01 20:54:00.000')\nfrom primitive_arrays\n-- !query schema\nstruct<array_contains(boolean_array, true):boolean,array_contains(boolean_array, false):boolean,array_contains(tinyint_array, 2):boolean,array_contains(tinyint_array, 0):boolean,array_contains(smallint_array, 2):boolean,array_contains(smallint_array, 0):boolean,array_contains(int_array, 2):boolean,array_contains(int_array, 0):boolean,array_contains(bigint_array, 2):boolean,array_contains(bigint_array, 0):boolean,array_contains(decimal_array, 9223372036854775809):boolean,array_contains(decimal_array, 1):boolean,array_contains(double_array, 2.0):boolean,array_contains(double_array, 0.0):boolean,array_contains(float_array, 2.0):boolean,array_contains(float_array, 0.0):boolean,array_contains(date_array, DATE '2016-03-14'):boolean,array_contains(date_array, DATE '2016-01-01'):boolean,array_contains(timestamp_array, TIMESTAMP '2016-11-15 20:54:00'):boolean,array_contains(timestamp_array, TIMESTAMP '2016-01-01 20:54:00'):boolean>\n-- !query output\ntrue\tfalse\ttrue\tfalse\ttrue\tfalse\ttrue\tfalse\ttrue\tfalse\ttrue\tfalse\ttrue\tfalse\ttrue\tfalse\ttrue\tfalse\ttrue\tfalse\n\n\n-- !query\nselect array_contains(b, 11), array_contains(c, array(111, 112, 113)) from data\n-- !query schema\nstruct<array_contains(b, 11):boolean,array_contains(c, array(111, 112, 113)):boolean>\n-- !query output\nfalse\tfalse\ntrue\ttrue\n\n\n-- !query\nselect\n  sort_array(boolean_array),\n  sort_array(tinyint_array),\n  sort_array(smallint_array),\n  sort_array(int_array),\n  sort_array(bigint_array),\n  sort_array(decimal_array),\n  sort_array(double_array),\n  sort_array(float_array),\n  sort_array(date_array),\n  sort_array(timestamp_array)\nfrom primitive_arrays\n-- !query schema\nstruct<sort_array(boolean_array, true):array<boolean>,sort_array(tinyint_array, true):array<tinyint>,sort_array(smallint_array, true):array<smallint>,sort_array(int_array, true):array<int>,sort_array(bigint_array, true):array<bigint>,sort_array(decimal_array, true):array<decimal(19,0)>,sort_array(double_array, true):array<double>,sort_array(float_array, true):array<float>,sort_array(date_array, true):array<date>,sort_array(timestamp_array, true):array<timestamp>>\n-- !query output\n[true]\t[1,2]\t[1,2]\t[1,2]\t[1,2]\t[9223372036854775808,9223372036854775809]\t[1.0,2.0]\t[1.0,2.0]\t[2016-03-13,2016-03-14]\t[2016-11-12 20:54:00,2016-11-15 20:54:00]\n\n\n-- !query\nselect sort_array(array('b', 'd'), '1')\n-- !query schema\nstruct<>\n-- !query output\norg.apache.spark.sql.AnalysisException\ncannot resolve 'sort_array(array('b', 'd'), '1')' due to data type mismatch: Sort order in second argument requires a boolean literal.; line 1 pos 7\n\n\n-- !query\nselect sort_array(array('b', 'd'), cast(NULL as boolean))\n-- !query schema\nstruct<>\n-- !query output\norg.apache.spark.sql.AnalysisException\ncannot resolve 'sort_array(array('b', 'd'), CAST(NULL AS BOOLEAN))' due to data type mismatch: Sort order in second argument requires a boolean literal.; line 1 pos 7\n\n\n-- !query\nselect\n  size(boolean_array),\n  size(tinyint_array),\n  size(smallint_array),\n  size(int_array),\n  size(bigint_array),\n  size(decimal_array),\n  size(double_array),\n  size(float_array),\n  size(date_array),\n  size(timestamp_array)\nfrom primitive_arrays\n-- !query schema\nstruct<size(boolean_array):int,size(tinyint_array):int,size(smallint_array):int,size(int_array):int,size(bigint_array):int,size(decimal_array):int,size(double_array):int,size(float_array):int,size(date_array):int,size(timestamp_array):int>\n-- !query output\n1\t2\t2\t2\t2\t2\t2\t2\t2\t2\n\n\n-- !query\nselect element_at(array(1, 2, 3), 5)\n-- !query schema\nstruct<element_at(array(1, 2, 3), 5):int>\n-- !query output\nNULL\n\n\n-- !query\nselect element_at(array(1, 2, 3), -5)\n-- !query schema\nstruct<element_at(array(1, 2, 3), -5):int>\n-- !query output\nNULL\n\n\n-- !query\nselect element_at(array(1, 2, 3), 0)\n-- !query schema\nstruct<>\n-- !query output\njava.lang.ArrayIndexOutOfBoundsException\nSQL array indices start at 1\n\n\n-- !query\nselect elt(4, '123', '456')\n-- !query schema\nstruct<elt(4, 123, 456):string>\n-- !query output\nNULL\n\n\n-- !query\nselect elt(0, '123', '456')\n-- !query schema\nstruct<elt(0, 123, 456):string>\n-- !query output\nNULL\n\n\n-- !query\nselect elt(-1, '123', '456')\n-- !query schema\nstruct<elt(-1, 123, 456):string>\n-- !query output\nNULL\n\n\n-- !query\nselect array(1, 2, 3)[5]\n-- !query schema\nstruct<array(1, 2, 3)[5]:int>\n-- !query output\nNULL\n\n\n-- !query\nselect array(1, 2, 3)[-1]\n-- !query schema\nstruct<array(1, 2, 3)[-1]:int>\n-- !query output\nNULL"
    val segments = goldenOutput.split("-- !query.*\n")
    println(segments)
  }
  // scalastyle:on
}
