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

package org.apache.spark.graphx

/**
 * Represents a subset of the fields of an [[EdgeTriplet]] or [[EdgeContext]]. This allows the
 * system to populate only those fields for efficiency.
 */
class TripletFields private (
    val useSrc: Boolean,
    val useDst: Boolean,
    val useEdge: Boolean)
  extends Serializable {
  private def this() = this(true, true, true)
}

/**
 * Exposes all possible [[TripletFields]] objects.
 */
object TripletFields {
  final val None = new TripletFields(useSrc = false, useDst = false, useEdge = false)
  final val EdgeOnly = new TripletFields(useSrc = false, useDst = false, useEdge = true)
  final val SrcOnly = new TripletFields(useSrc = true, useDst = false, useEdge = false)
  final val DstOnly = new TripletFields(useSrc = false, useDst = true, useEdge = false)
  final val SrcDstOnly = new TripletFields(useSrc = true, useDst = true, useEdge = false)
  final val SrcAndEdge = new TripletFields(useSrc = true, useDst = false, useEdge = true)
  final val Src = SrcAndEdge
  final val DstAndEdge = new TripletFields(useSrc = false, useDst = true, useEdge = true)
  final val Dst = DstAndEdge
  final val All = new TripletFields(useSrc = true, useDst = true, useEdge = true)

  /** Returns the appropriate [[TripletFields]] object. */
  private[graphx] def apply(useSrc: Boolean, useDst: Boolean, useEdge: Boolean) =
    (useSrc, useDst, useEdge) match {
      case (false, false, false) => TripletFields.None
      case (false, false, true) => EdgeOnly
      case (true, false, false) => SrcOnly
      case (false, true, false) => DstOnly
      case (true, true, false) => SrcDstOnly
      case (true, false, true) => SrcAndEdge
      case (false, true, true) => DstAndEdge
      case (true, true, true) => All
    }
}
