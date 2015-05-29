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

package org.apache.spark.sql.catalyst.expressions.codegen

import org.apache.spark.sql.BaseMutableRow
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.types._


abstract class BaseProject extends Projection {

}


/**
 * Generates bytecode that produces a new [[Row]] object based on a fixed set of input
 * [[Expression Expressions]] and a given input [[Row]].  The returned [[Row]] object is custom
 * generated based on the output types of the [[Expression]] to avoid boxing of primitive values.
 */
object GenerateProjection extends CodeGenerator[Seq[Expression], Projection] {
  import scala.reflect.runtime.universe._
  import scala.reflect.runtime.{universe => ru}

  protected def canonicalize(in: Seq[Expression]): Seq[Expression] =
    in.map(ExpressionCanonicalizer.execute)

  protected def bind(in: Seq[Expression], inputSchema: Seq[Attribute]): Seq[Expression] =
    in.map(BindReferences.bindReference(_, inputSchema))

  // Make Mutablility optional...
  protected def create(expressions: Seq[Expression]): Projection = {
    /* TODO: Configurable...
    val nullFunctions =
      s"""
        private final val nullSet = new org.apache.spark.util.collection.BitSet(length)
        final def setNullAt(i: Int) = nullSet.set(i)
        final def isNullAt(i: Int) = nullSet.get(i)
      """
     */

    val ctx = newCodeGenContext()
    val columns = expressions.zipWithIndex.map {
      case (e, i) =>
        s"private ${primitiveForType(e.dataType)} c$i = ${defaultPrimitive(e.dataType)};\n"
    }.mkString("\n")

    val tupleElements = expressions.zipWithIndex.map {
      case (e, i) =>
        val evaluatedExpression = expressionEvaluator(e, ctx)

        s"""
        {
          ${evaluatedExpression.code}
          if(${evaluatedExpression.nullTerm}) {
            setNullAt($i);
          } else {
            nullBits[$i] = false;
            c$i = ${evaluatedExpression.primitiveTerm};
          }
        }
        """
    }.mkString("\n")

    val getCases = (0 until expressions.size).map { i =>
      s"""if(i == $i) return c$i;\n"""
    }.mkString("\n")
    val updateCases = expressions.zipWithIndex.map {case (e, i) =>
      s"""
      if(i == $i) {
        c$i = (${termForType(e.dataType)})value;
        return;
      }"""
    }.mkString("\n")

    val specificAccessorFunctions = nativeTypes.map { dataType =>
      val ifStatements = expressions.zipWithIndex.map {
        // getString() is not used by expressions
        case (e, i) if e.dataType == dataType && dataType != StringType =>
          // TODO: The string of ifs gets pretty inefficient as the row grows in size.
          s"if(i == $i) return c$i;"
        case _ => ""
      }.mkString("\n")
      if (ifStatements.count(_ != '\n') > 0) {
        s"""
          @Override
          public ${primitiveForType(dataType)} ${accessorForType(dataType)}(int i) {
            if (isNullAt(i)) {
              return ${defaultPrimitive(dataType)};
            }
            $ifStatements
            return ${defaultPrimitive(dataType)};
          }"""
      } else {
        ""
      }
    }.mkString("\n")

    val specificMutatorFunctions = nativeTypes.map { dataType =>
      val ifStatements = expressions.zipWithIndex.map {
        // setString() is not used by expressions
        case (e, i) if e.dataType == dataType && dataType != StringType =>
          // TODO: The string of ifs gets pretty inefficient as the row grows in size.
          s"if(i == $i) { c$i = value; return; }"
        case _ => ""
      }.mkString("\n")
      if (ifStatements.count(_ != '\n') > 0) {
        s"""
        @Override
        public void ${mutatorForType(dataType)}(int i, ${primitiveForType(dataType)} value) {
          nullBits[i] = false;
          $ifStatements
        }"""
      } else {
        ""
      }
    }.mkString("\n")

    val hashValues = expressions.zipWithIndex.map { case (e,i) =>
      val elementName = newTermName(s"c$i")
      val nonNull = e.dataType match {
        case BooleanType => s"$elementName? 0 : 1"
        case ByteType | ShortType | IntegerType => s"$elementName"
        case LongType => s"$elementName ^ ($elementName >>> 32)"
        case FloatType => s"Float.floatToIntBits($elementName)"
        case DoubleType =>
          s"Double.doubleToLongBits($elementName) ^ (Double.doubleToLongBits($elementName) >>>32)"
        case _ => s"$elementName.hashCode()"
      }
      s"isNullAt($i) ? 0 : ($nonNull)"
    }

    val hashUpdates: String = hashValues.map(v =>
      s"""
      result *= 37;
      result += $v;
      """
    ).mkString("\n")

    val columnChecks = (0 until expressions.size).map { i =>
      val elementName = newTermName(s"c$i")
      s"if (this.$elementName != specificType.$elementName) return false;\n"
    }.mkString("\n")

    val copyColumns = (0 until expressions.size).map { i =>
      val elementName = newTermName(s"c$i")
      s"if (!isNullAt($i)) arr[$i] = (Object)$elementName;\n"
    }.mkString("\n")

    val code = s"""
      import org.apache.spark.sql.Row;
      import scala.collection.Seq;
      import scala.collection.mutable.ArraySeq;

      public SpecificProjection generate($exprType[] expr) {
        return new SpecificProjection(expr);
      }

      class SpecificProjection extends ${typeOf[BaseProject]} {
        private $exprType[] expressions = null;

        public SpecificProjection($exprType[] expr) {
          expressions = expr;
        }

        @Override
        public Object apply(Object r) {
          return new SpecificRow(expressions, (Row)r);
        }
      }

      final class SpecificRow extends ${typeOf[BaseMutableRow]} {

        $columns

        public SpecificRow($exprType[] expressions, Row i) {
          $tupleElements
        }

        public int size() { return ${expressions.length};}

        private boolean[] nullBits = new boolean[${expressions.length}];
        public void setNullAt(int i) { nullBits[i] = true; }
        public boolean isNullAt(int i) { return nullBits[i]; }

        public Object get(int i) {
          if (isNullAt(i)) return null;
          $getCases
          return null;
        }
        public void update(int i, Object value) {
          if (value == null) {
            setNullAt(i);
            return;
          }
          nullBits[i] = false;
          $updateCases
        }
        $specificAccessorFunctions
        $specificMutatorFunctions

        @Override
        public int hashCode() {
          int result = 37;
          $hashUpdates
          return result;
        }

        @Override
        public boolean equals(Object other) {
          if (other instanceof SpecificRow) {
            SpecificRow specificType = (SpecificRow) other;
            $columnChecks
            return true;
          }
          return super.equals(other);
        }

        @Override
        public Row copy() {
          Object[] arr = new Object[${expressions.length}];
          $copyColumns
          return new ${typeOf[GenericRow]}(arr);
        }

        @Override
        public Seq<Object> toSeq() {
          final ArraySeq<Object> values = new ArraySeq<Object>(${expressions.length});
          for (int i = 0; i < ${expressions.length}; i++) {
            values.update(i, get(i));
          }
          return values;
        }
      }
    """

    logWarning(
      s"MutableRow, initExprs: ${expressions.mkString(",")} code:\n${code}")

    val c = compile(code)
    val m = c.getDeclaredMethods()(0)
    m.invoke(c.newInstance(), ctx.borrowed.toArray).asInstanceOf[Projection]
  }
}
