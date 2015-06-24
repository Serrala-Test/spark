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

import java.sql.Date
import java.text.SimpleDateFormat

import org.apache.spark.sql.catalyst.analysis.TypeCheckResult
import org.apache.spark.sql.catalyst.expressions.codegen.{GeneratedExpressionCode, CodeGenContext}
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String

case class DateFormatClass(left: Expression, right: Expression) extends BinaryExpression {

  override def dataType: DataType = StringType

  override def checkInputDataTypes(): TypeCheckResult =
    (left.dataType, right.dataType) match {
      case (null, _) => TypeCheckResult.TypeCheckSuccess
      case (_, null) => TypeCheckResult.TypeCheckSuccess
      case (_: DateType, _: StringType) => TypeCheckResult.TypeCheckSuccess
      case (_: TimestampType, _: StringType) => TypeCheckResult.TypeCheckSuccess
      case (_: StringType, _: StringType) => TypeCheckResult.TypeCheckSuccess
      case _ =>
        TypeCheckResult.TypeCheckFailure(s"DateFormat accepts date types as first argument, " +
          s"and string types as second, not ${left.dataType} and ${right.dataType}")
    }

  override def foldable: Boolean = left.foldable && right.foldable

  override def nullable: Boolean = true

  override def eval(input: InternalRow): Any = {
    val valueLeft = left.eval(input)
    if (valueLeft == null) {
      null
    } else {
      val valueRight = right.eval(input)
      if (valueRight == null) {
        null
      } else {
        val sdf = new SimpleDateFormat(valueRight.asInstanceOf[UTF8String].toString)
        left.dataType match {
          case TimestampType =>
            UTF8String.fromString(sdf.format(new Date(valueLeft.asInstanceOf[Long] / 10000)))
          case DateType =>
            UTF8String.fromString(sdf.format(DateTimeUtils.toJavaDate(valueLeft.asInstanceOf[Int])))
          case StringType =>
            UTF8String.fromString(
              sdf.format(DateTimeUtils.stringToTime(valueLeft.asInstanceOf[UTF8String].toString)))
        }
      }
    }
  }

  override def toString: String = s"DateFormat($left, $right)"

  override protected def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    val sdf = "java.text.SimpleDateFormat"
    val utf8 = "org.apache.spark.unsafe.types.UTF8String"
    val dtUtils = "org.apache.spark.sql.catalyst.util.DateTimeUtils"

    val eval1 = left.gen(ctx)
    val eval2 = right.gen(ctx)

    val calc = left.dataType match {
      case TimestampType =>
        s""""$utf8.fromString(sdf.format(new java.sql.Date(${eval1.primitive} / 10000)));"""
      case DateType =>
        s"""$utf8.fromString(
          sdf.format($dtUtils.toJavaDate(${eval1.primitive})));"""
      case StringType =>
        s"""
           $utf8.fromString(sdf.format(new java.sql.Date($dtUtils.stringToTime(${eval1.primitive}.toString()).getTime())));
         """
    }

    s"""
      ${eval1.code}
      boolean ${ev.isNull} = ${eval1.isNull};
      ${ctx.javaType(dataType)} ${ev.primitive} = ${ctx.defaultValue(dataType)};
      if (!${ev.isNull}) {
        ${eval2.code}
        if (!${eval2.isNull}) {
          $sdf sdf = new $sdf(${eval2.primitive}.toString());
          ${ev.primitive} = $calc
        } else {
          ${ev.isNull} = true;
        }
      }
    """
  }
}

case class Year(child: Expression) extends UnaryExpression with ExpectsInputTypes {

  override def dataType: DataType = IntegerType

  override def expectedChildTypes: Seq[DataType] = Seq(DateType, StringType, TimestampType)

  override def foldable: Boolean = child.foldable

  override def nullable: Boolean = true

  override def eval(input: InternalRow): Any = {
    DateFormatClass(child, Literal("y")).eval(input) match {
      case null => null
      case x: UTF8String => x.toString.toInt
    }
  }

  override def checkInputDataTypes(): TypeCheckResult =
    child.dataType match {
      case null => TypeCheckResult.TypeCheckSuccess
      case _: DateType => TypeCheckResult.TypeCheckSuccess
      case _: TimestampType => TypeCheckResult.TypeCheckSuccess
      case _: StringType => TypeCheckResult.TypeCheckSuccess
      case _ =>
        TypeCheckResult.TypeCheckFailure(s"Year accepts date types as argument, " +
          s" not ${child.dataType}")
    }

}

case class Month(child: Expression) extends UnaryExpression with ExpectsInputTypes {

  override def dataType: DataType = IntegerType

  override def expectedChildTypes: Seq[DataType] = Seq(DateType, StringType, TimestampType)

  override def foldable: Boolean = child.foldable

  override def nullable: Boolean = true

  override def eval(input: InternalRow): Any = {
    DateFormatClass(child, Literal("M")).eval(input) match {
      case null => null
      case x: UTF8String => x.toString.toInt
    }
  }

  override def checkInputDataTypes(): TypeCheckResult =
    child.dataType match {
      case null => TypeCheckResult.TypeCheckSuccess
      case _: DateType => TypeCheckResult.TypeCheckSuccess
      case _: TimestampType => TypeCheckResult.TypeCheckSuccess
      case _: StringType => TypeCheckResult.TypeCheckSuccess
      case _ =>
        TypeCheckResult.TypeCheckFailure(s"Month accepts date types as argument, " +
          s" not ${child.dataType}")
    }
}

case class Day(child: Expression) extends UnaryExpression with ExpectsInputTypes {

  override def dataType: DataType = IntegerType

  override def expectedChildTypes: Seq[DataType] = Seq(DateType, StringType, TimestampType)

  override def foldable: Boolean = child.foldable

  override def nullable: Boolean = true

  override def eval(input: InternalRow): Any = {
    DateFormatClass(child, Literal("d")).eval(input) match {
      case null => null
      case x: UTF8String => x.toString.toInt
    }
  }

  override def checkInputDataTypes(): TypeCheckResult =
    child.dataType match {
      case null => TypeCheckResult.TypeCheckSuccess
      case _: DateType => TypeCheckResult.TypeCheckSuccess
      case _: TimestampType => TypeCheckResult.TypeCheckSuccess
      case _: StringType => TypeCheckResult.TypeCheckSuccess
      case _ =>
        TypeCheckResult.TypeCheckFailure(s"Day accepts date types as argument, " +
          s" not ${child.dataType}")
    }

}

case class Hour(child: Expression) extends UnaryExpression {

  override def dataType: DataType = IntegerType

  override def foldable: Boolean = child.foldable

  override def nullable: Boolean = true

  override def eval(input: InternalRow): Any = {
    DateFormatClass(child, Literal("H")).eval(input) match {
      case null => null
      case x: UTF8String => x.toString.toInt
    }
  }

  override def checkInputDataTypes(): TypeCheckResult =
    child.dataType match {
      case null => TypeCheckResult.TypeCheckSuccess
      case _: DateType => TypeCheckResult.TypeCheckSuccess
      case _: TimestampType => TypeCheckResult.TypeCheckSuccess
      case _: StringType => TypeCheckResult.TypeCheckSuccess
      case _ =>
        TypeCheckResult.TypeCheckFailure(s"Hour accepts date types as argument, " +
          s" not ${child.dataType}")
    }
}

case class Minute(child: Expression) extends UnaryExpression {

  override def dataType: DataType = IntegerType

  override def foldable: Boolean = child.foldable

  override def nullable: Boolean = true

  override def eval(input: InternalRow): Any = {
    DateFormatClass(child, Literal("m")).eval(input) match {
      case null => null
      case x: UTF8String => x.toString.toInt
    }
  }

  override def checkInputDataTypes(): TypeCheckResult =
    child.dataType match {
      case null => TypeCheckResult.TypeCheckSuccess
      case _: DateType => TypeCheckResult.TypeCheckSuccess
      case _: TimestampType => TypeCheckResult.TypeCheckSuccess
      case _: StringType => TypeCheckResult.TypeCheckSuccess
      case _ =>
        TypeCheckResult.TypeCheckFailure(s"Minute accepts date types as argument, " +
          s" not ${child.dataType}")
    }
}

case class Second(child: Expression) extends UnaryExpression {

  override def dataType: DataType = IntegerType

  override def foldable: Boolean = child.foldable

  override def nullable: Boolean = true

  override def eval(input: InternalRow): Any = {
    DateFormatClass(child, Literal("s")).eval(input) match {
      case null => null
      case x: UTF8String => x.toString.toInt
    }
  }

  override def checkInputDataTypes(): TypeCheckResult =
    child.dataType match {
      case null => TypeCheckResult.TypeCheckSuccess
      case _: DateType => TypeCheckResult.TypeCheckSuccess
      case _: TimestampType => TypeCheckResult.TypeCheckSuccess
      case _: StringType => TypeCheckResult.TypeCheckSuccess
      case _ =>
        TypeCheckResult.TypeCheckFailure(s"Second accepts date types as argument, " +
          s" not ${child.dataType}")
    }
}

case class WeekOfYear(child: Expression) extends UnaryExpression {

  override def dataType: DataType = IntegerType

  override def foldable: Boolean = child.foldable

  override def nullable: Boolean = true

  override def eval(input: InternalRow): Any = {
    DateFormatClass(child, Literal("w")).eval(input) match {
      case null => null
      case x: UTF8String => x.toString.toInt
    }
  }

  override def checkInputDataTypes(): TypeCheckResult =
    child.dataType match {
      case null => TypeCheckResult.TypeCheckSuccess
      case _: DateType => TypeCheckResult.TypeCheckSuccess
      case _: TimestampType => TypeCheckResult.TypeCheckSuccess
      case _: StringType => TypeCheckResult.TypeCheckSuccess
      case _ =>
        TypeCheckResult.TypeCheckFailure(s"WeekOfYear accepts date types as argument, " +
          s" not ${child.dataType}")
    }
}