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

package org.apache.spark.sql.catalyst.expressions

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.codegen._
import org.apache.spark.sql.catalyst.util.CollatorFactory
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String

case class Collate(inputString: Expression, collation: Expression)
  extends BinaryExpression with CodegenFallback with ImplicitCastInputTypes {
  override def left: Expression = inputString
  override def right: Expression = collation

  @transient
  private lazy val collationId = {
    val collationName = right.eval().asInstanceOf[UTF8String].toString
    CollatorFactory.getInstance().collationNameToId(collationName)
  }

  override def dataType: DataType = {
    StringType(collationId)
  }

  // TODO: Can this be foldable?
  override def foldable: Boolean = false
  override def inputTypes: Seq[AbstractDataType] = Seq(StringType, StringType)

  override protected def withNewChildrenInternal(
    newLeft: Expression, newRight: Expression): Expression = copy(newLeft, newRight)

  // Just pass through.
  override def eval(row: InternalRow): Any = {
    // TODO: Proper codegen here.
    val input = left.eval(row).asInstanceOf[UTF8String]
    input.installCollationAwareComparator(collationId)
    input
  }
}

case class Collation(child: Expression) extends UnaryExpression with CodegenFallback {
  override def dataType: DataType = StringType

  override protected def withNewChildInternal(newChild: Expression): Expression = copy(newChild)

  override def eval(input: InternalRow): Any = child.dataType match {
    case st: StringType =>
      val collationName = CollatorFactory.getInfoForId(st.collationId).collationName
      UTF8String.fromString(collationName)
    case _ => throw new IllegalArgumentException("Collation expects StringType")
  }
}
