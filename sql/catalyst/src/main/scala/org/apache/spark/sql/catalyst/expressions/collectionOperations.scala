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

import java.util.Comparator

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.TypeCheckResult
import org.apache.spark.sql.catalyst.expressions.codegen._
import org.apache.spark.sql.catalyst.util.{ArrayData, GenericArrayData, MapData, TypeUtils}
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.Platform
import org.apache.spark.unsafe.array.ByteArrayMethods

/**
 * Given an array or map, returns its size. Returns -1 if null.
 */
@ExpressionDescription(
  usage = "_FUNC_(expr) - Returns the size of an array or a map. Returns -1 if null.",
  examples = """
    Examples:
      > SELECT _FUNC_(array('b', 'd', 'c', 'a'));
       4
  """)
case class Size(child: Expression) extends UnaryExpression with ExpectsInputTypes {
  override def dataType: DataType = IntegerType
  override def inputTypes: Seq[AbstractDataType] = Seq(TypeCollection(ArrayType, MapType))
  override def nullable: Boolean = false

  override def eval(input: InternalRow): Any = {
    val value = child.eval(input)
    if (value == null) {
      -1
    } else child.dataType match {
      case _: ArrayType => value.asInstanceOf[ArrayData].numElements()
      case _: MapType => value.asInstanceOf[MapData].numElements()
    }
  }

  override def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val childGen = child.genCode(ctx)
    ev.copy(code = s"""
      boolean ${ev.isNull} = false;
      ${childGen.code}
      ${CodeGenerator.javaType(dataType)} ${ev.value} = ${childGen.isNull} ? -1 :
        (${childGen.value}).numElements();""", isNull = FalseLiteral)
  }
}

/**
 * Returns an unordered array containing the keys of the map.
 */
@ExpressionDescription(
  usage = "_FUNC_(map) - Returns an unordered array containing the keys of the map.",
  examples = """
    Examples:
      > SELECT _FUNC_(map(1, 'a', 2, 'b'));
       [1,2]
  """)
case class MapKeys(child: Expression)
  extends UnaryExpression with ExpectsInputTypes {

  override def inputTypes: Seq[AbstractDataType] = Seq(MapType)

  override def dataType: DataType = ArrayType(child.dataType.asInstanceOf[MapType].keyType)

  override def nullSafeEval(map: Any): Any = {
    map.asInstanceOf[MapData].keyArray()
  }

  override def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    nullSafeCodeGen(ctx, ev, c => s"${ev.value} = ($c).keyArray();")
  }

  override def prettyName: String = "map_keys"
}

/**
 * Returns an unordered array containing the values of the map.
 */
@ExpressionDescription(
  usage = "_FUNC_(map) - Returns an unordered array containing the values of the map.",
  examples = """
    Examples:
      > SELECT _FUNC_(map(1, 'a', 2, 'b'));
       ["a","b"]
  """)
case class MapValues(child: Expression)
  extends UnaryExpression with ExpectsInputTypes {

  override def inputTypes: Seq[AbstractDataType] = Seq(MapType)

  override def dataType: DataType = ArrayType(child.dataType.asInstanceOf[MapType].valueType)

  override def nullSafeEval(map: Any): Any = {
    map.asInstanceOf[MapData].valueArray()
  }

  override def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    nullSafeCodeGen(ctx, ev, c => s"${ev.value} = ($c).valueArray();")
  }

  override def prettyName: String = "map_values"
}

/**
 * Sorts the input array in ascending / descending order according to the natural ordering of
 * the array elements and returns it.
 */
// scalastyle:off line.size.limit
@ExpressionDescription(
  usage = "_FUNC_(array[, ascendingOrder]) - Sorts the input array in ascending or descending order according to the natural ordering of the array elements.",
  examples = """
    Examples:
      > SELECT _FUNC_(array('b', 'd', 'c', 'a'), true);
       ["a","b","c","d"]
  """)
// scalastyle:on line.size.limit
case class SortArray(base: Expression, ascendingOrder: Expression)
  extends BinaryExpression with ExpectsInputTypes with CodegenFallback {

  def this(e: Expression) = this(e, Literal(true))

  override def left: Expression = base
  override def right: Expression = ascendingOrder
  override def dataType: DataType = base.dataType
  override def inputTypes: Seq[AbstractDataType] = Seq(ArrayType, BooleanType)

  override def checkInputDataTypes(): TypeCheckResult = base.dataType match {
    case ArrayType(dt, _) if RowOrdering.isOrderable(dt) =>
      ascendingOrder match {
        case Literal(_: Boolean, BooleanType) =>
          TypeCheckResult.TypeCheckSuccess
        case _ =>
          TypeCheckResult.TypeCheckFailure(
            "Sort order in second argument requires a boolean literal.")
      }
    case ArrayType(dt, _) =>
      TypeCheckResult.TypeCheckFailure(
        s"$prettyName does not support sorting array of type ${dt.simpleString}")
    case _ =>
      TypeCheckResult.TypeCheckFailure(s"$prettyName only supports array input.")
  }

  @transient
  private lazy val lt: Comparator[Any] = {
    val ordering = base.dataType match {
      case _ @ ArrayType(n: AtomicType, _) => n.ordering.asInstanceOf[Ordering[Any]]
      case _ @ ArrayType(a: ArrayType, _) => a.interpretedOrdering.asInstanceOf[Ordering[Any]]
      case _ @ ArrayType(s: StructType, _) => s.interpretedOrdering.asInstanceOf[Ordering[Any]]
    }

    new Comparator[Any]() {
      override def compare(o1: Any, o2: Any): Int = {
        if (o1 == null && o2 == null) {
          0
        } else if (o1 == null) {
          -1
        } else if (o2 == null) {
          1
        } else {
          ordering.compare(o1, o2)
        }
      }
    }
  }

  @transient
  private lazy val gt: Comparator[Any] = {
    val ordering = base.dataType match {
      case _ @ ArrayType(n: AtomicType, _) => n.ordering.asInstanceOf[Ordering[Any]]
      case _ @ ArrayType(a: ArrayType, _) => a.interpretedOrdering.asInstanceOf[Ordering[Any]]
      case _ @ ArrayType(s: StructType, _) => s.interpretedOrdering.asInstanceOf[Ordering[Any]]
    }

    new Comparator[Any]() {
      override def compare(o1: Any, o2: Any): Int = {
        if (o1 == null && o2 == null) {
          0
        } else if (o1 == null) {
          1
        } else if (o2 == null) {
          -1
        } else {
          -ordering.compare(o1, o2)
        }
      }
    }
  }

  override def nullSafeEval(array: Any, ascending: Any): Any = {
    val elementType = base.dataType.asInstanceOf[ArrayType].elementType
    val data = array.asInstanceOf[ArrayData].toArray[AnyRef](elementType)
    if (elementType != NullType) {
      java.util.Arrays.sort(data, if (ascending.asInstanceOf[Boolean]) lt else gt)
    }
    new GenericArrayData(data.asInstanceOf[Array[Any]])
  }

  override def prettyName: String = "sort_array"
}

/**
 * Checks if the array (left) has the element (right)
 */
@ExpressionDescription(
  usage = "_FUNC_(array, value) - Returns true if the array contains the value.",
  examples = """
    Examples:
      > SELECT _FUNC_(array(1, 2, 3), 2);
       true
  """)
case class ArrayContains(left: Expression, right: Expression)
  extends BinaryExpression with ImplicitCastInputTypes {

  override def dataType: DataType = BooleanType

  override def inputTypes: Seq[AbstractDataType] = right.dataType match {
    case NullType => Seq.empty
    case _ => left.dataType match {
      case n @ ArrayType(element, _) => Seq(n, element)
      case _ => Seq.empty
    }
  }

  override def checkInputDataTypes(): TypeCheckResult = {
    if (right.dataType == NullType) {
      TypeCheckResult.TypeCheckFailure("Null typed values cannot be used as arguments")
    } else if (!left.dataType.isInstanceOf[ArrayType]
      || left.dataType.asInstanceOf[ArrayType].elementType != right.dataType) {
      TypeCheckResult.TypeCheckFailure(
        "Arguments must be an array followed by a value of same type as the array members")
    } else {
      TypeCheckResult.TypeCheckSuccess
    }
  }

  override def nullable: Boolean = {
    left.nullable || right.nullable || left.dataType.asInstanceOf[ArrayType].containsNull
  }

  override def nullSafeEval(arr: Any, value: Any): Any = {
    var hasNull = false
    arr.asInstanceOf[ArrayData].foreach(right.dataType, (i, v) =>
      if (v == null) {
        hasNull = true
      } else if (v == value) {
        return true
      }
    )
    if (hasNull) {
      null
    } else {
      false
    }
  }

  override def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    nullSafeCodeGen(ctx, ev, (arr, value) => {
      val i = ctx.freshName("i")
      val getValue = CodeGenerator.getValue(arr, right.dataType, i)
      s"""
      for (int $i = 0; $i < $arr.numElements(); $i ++) {
        if ($arr.isNullAt($i)) {
          ${ev.isNull} = true;
        } else if (${ctx.genEqual(right.dataType, value, getValue)}) {
          ${ev.isNull} = false;
          ${ev.value} = true;
          break;
        }
      }
     """
    })
  }

  override def prettyName: String = "array_contains"
}

/**
 * Returns the minimum value in the array.
 */
@ExpressionDescription(
  usage = "_FUNC_(array) - Returns the minimum value in the array. NULL elements are skipped.",
  examples = """
    Examples:
      > SELECT _FUNC_(array(1, 20, null, 3));
       1
  """, since = "2.4.0")
case class ArrayMin(child: Expression) extends UnaryExpression with ImplicitCastInputTypes {

  override def nullable: Boolean = true

  override def inputTypes: Seq[AbstractDataType] = Seq(ArrayType)

  private lazy val ordering = TypeUtils.getInterpretedOrdering(dataType)

  override def checkInputDataTypes(): TypeCheckResult = {
    val typeCheckResult = super.checkInputDataTypes()
    if (typeCheckResult.isSuccess) {
      TypeUtils.checkForOrderingExpr(dataType, s"function $prettyName")
    } else {
      typeCheckResult
    }
  }

  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val childGen = child.genCode(ctx)
    val javaType = CodeGenerator.javaType(dataType)
    val i = ctx.freshName("i")
    val item = ExprCode("",
      isNull = JavaCode.isNullExpression(s"${childGen.value}.isNullAt($i)"),
      value = JavaCode.expression(CodeGenerator.getValue(childGen.value, dataType, i), dataType))
    ev.copy(code =
      s"""
         |${childGen.code}
         |boolean ${ev.isNull} = true;
         |$javaType ${ev.value} = ${CodeGenerator.defaultValue(dataType)};
         |if (!${childGen.isNull}) {
         |  for (int $i = 0; $i < ${childGen.value}.numElements(); $i ++) {
         |    ${ctx.reassignIfSmaller(dataType, ev, item)}
         |  }
         |}
      """.stripMargin)
  }

  override protected def nullSafeEval(input: Any): Any = {
    var min: Any = null
    input.asInstanceOf[ArrayData].foreach(dataType, (_, item) =>
      if (item != null && (min == null || ordering.lt(item, min))) {
        min = item
      }
    )
    min
  }

  override def dataType: DataType = child.dataType match {
    case ArrayType(dt, _) => dt
    case _ => throw new IllegalStateException(s"$prettyName accepts only arrays.")
  }

  override def prettyName: String = "array_min"
}

/**
 * Returns the maximum value in the array.
 */
@ExpressionDescription(
  usage = "_FUNC_(array) - Returns the maximum value in the array. NULL elements are skipped.",
  examples = """
    Examples:
      > SELECT _FUNC_(array(1, 20, null, 3));
       20
  """, since = "2.4.0")
case class ArrayMax(child: Expression) extends UnaryExpression with ImplicitCastInputTypes {

  override def nullable: Boolean = true

  override def inputTypes: Seq[AbstractDataType] = Seq(ArrayType)

  private lazy val ordering = TypeUtils.getInterpretedOrdering(dataType)

  override def checkInputDataTypes(): TypeCheckResult = {
    val typeCheckResult = super.checkInputDataTypes()
    if (typeCheckResult.isSuccess) {
      TypeUtils.checkForOrderingExpr(dataType, s"function $prettyName")
    } else {
      typeCheckResult
    }
  }

  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val childGen = child.genCode(ctx)
    val javaType = CodeGenerator.javaType(dataType)
    val i = ctx.freshName("i")
    val item = ExprCode("",
      isNull = JavaCode.isNullExpression(s"${childGen.value}.isNullAt($i)"),
      value = JavaCode.expression(CodeGenerator.getValue(childGen.value, dataType, i), dataType))
    ev.copy(code =
      s"""
         |${childGen.code}
         |boolean ${ev.isNull} = true;
         |$javaType ${ev.value} = ${CodeGenerator.defaultValue(dataType)};
         |if (!${childGen.isNull}) {
         |  for (int $i = 0; $i < ${childGen.value}.numElements(); $i ++) {
         |    ${ctx.reassignIfGreater(dataType, ev, item)}
         |  }
         |}
      """.stripMargin)
  }

  override protected def nullSafeEval(input: Any): Any = {
    var max: Any = null
    input.asInstanceOf[ArrayData].foreach(dataType, (_, item) =>
      if (item != null && (max == null || ordering.gt(item, max))) {
        max = item
      }
    )
    max
  }

  override def dataType: DataType = child.dataType match {
    case ArrayType(dt, _) => dt
    case _ => throw new IllegalStateException(s"$prettyName accepts only arrays.")
  }

  override def prettyName: String = "array_max"
}

/**
 * Transforms an array of arrays into a single array.
 */
@ExpressionDescription(
  usage = "_FUNC_(arrayOfArrays) - Transforms an array of arrays into a single array.",
  examples = """
    Examples:
      > SELECT _FUNC_(array(array(1, 2), array(3, 4));
       [1,2,3,4]
  """,
  since = "2.4.0")
case class Flatten(child: Expression) extends UnaryExpression {

  private val MAX_ARRAY_LENGTH = ByteArrayMethods.MAX_ROUNDED_ARRAY_LENGTH

  private lazy val childDataType: ArrayType = child.dataType.asInstanceOf[ArrayType]

  override def nullable: Boolean = child.nullable || childDataType.containsNull

  override def dataType: DataType = childDataType.elementType

  lazy val elementType: DataType = dataType.asInstanceOf[ArrayType].elementType

  override def checkInputDataTypes(): TypeCheckResult = child.dataType match {
    case ArrayType(_: ArrayType, _) =>
      TypeCheckResult.TypeCheckSuccess
    case _ =>
      TypeCheckResult.TypeCheckFailure(
        s"The argument should be an array of arrays, " +
        s"but '${child.sql}' is of ${child.dataType.simpleString} type."
      )
  }

  override def nullSafeEval(child: Any): Any = {
    val elements = child.asInstanceOf[ArrayData].toObjectArray(dataType)

    if (elements.contains(null)) {
      null
    } else {
      val arrayData = elements.map(_.asInstanceOf[ArrayData])
      val numberOfElements = arrayData.foldLeft(0L)((sum, e) => sum + e.numElements())
      if (numberOfElements > MAX_ARRAY_LENGTH) {
        throw new RuntimeException("Unsuccessful try to flatten an array of arrays with " +
          s" $numberOfElements elements due to exceeding the array size limit $MAX_ARRAY_LENGTH.")
      }
      val flattenedData = new Array(numberOfElements.toInt)
      var position = 0
      for (ad <- arrayData) {
        val arr = ad.toObjectArray(elementType)
        Array.copy(arr, 0, flattenedData, position, arr.length)
        position += arr.length
      }
      new GenericArrayData(flattenedData)
    }
  }

  override def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    nullSafeCodeGen(ctx, ev, c => {
      val code = if (CodeGenerator.isPrimitiveType(elementType)) {
        genCodeForFlattenOfPrimitiveElements(ctx, c, ev.value)
      } else {
        genCodeForFlattenOfNonPrimitiveElements(ctx, c, ev.value)
      }
      nullElementsProtection(ev, c, code)
    })
  }

  private def nullElementsProtection(
      ev: ExprCode,
      childVariableName: String,
      coreLogic: String): String = {
    s"""
    |for (int z=0; !${ev.isNull} && z < $childVariableName.numElements(); z++) {
    |  ${ev.isNull} |= $childVariableName.isNullAt(z);
    |}
    |if (!${ev.isNull}) {
    |  $coreLogic
    |}
    """.stripMargin
  }

  private def genCodeForNumberOfElements(
      ctx: CodegenContext,
      childVariableName: String) : (String, String) = {
    val variableName = ctx.freshName("numElements")
    val code = s"""
      |long $variableName = 0;
      |for (int z=0; z < $childVariableName.numElements(); z++) {
      |  $variableName += $childVariableName.getArray(z).numElements();
      |}
      |if ($variableName > ${MAX_ARRAY_LENGTH}) {
      |  throw new RuntimeException("Unsuccessful try to flatten an array of arrays with" +
      |    " $variableName elements due to exceeding the array size limit $MAX_ARRAY_LENGTH.");
      |}
      """.stripMargin
    (code, variableName)
  }

  private def genCodeForFlattenOfPrimitiveElements(
      ctx: CodegenContext,
      childVariableName: String,
      arrayDataName: String): String = {
    val arrayName = ctx.freshName("array")
    val arraySizeName = ctx.freshName("size")
    val counter = ctx.freshName("counter")
    val tempArrayDataName = ctx.freshName("tempArrayData")

    val (numElemCode, numElemName) = genCodeForNumberOfElements(ctx, childVariableName)

    val unsafeArraySizeInBytes = s"""
      |long $arraySizeName = UnsafeArrayData.calculateSizeOfUnderlyingByteArray(
      |  $numElemName,
      |  ${elementType.defaultSize});
      |if ($arraySizeName > $MAX_ARRAY_LENGTH) {
      |  throw new RuntimeException("Unsuccessful try to flatten an array of arrays with" +
      |    " $arraySizeName bytes of data due to exceeding the limit $MAX_ARRAY_LENGTH" +
      |    " bytes for UnsafeArrayData.");
      |}
      """.stripMargin
    val baseOffset = Platform.BYTE_ARRAY_OFFSET

    val primitiveValueTypeName = CodeGenerator.primitiveTypeName(elementType)

    s"""
    |$numElemCode
    |$unsafeArraySizeInBytes
    |byte[] $arrayName = new byte[(int)$arraySizeName];
    |UnsafeArrayData $tempArrayDataName = new UnsafeArrayData();
    |Platform.putLong($arrayName, $baseOffset, $numElemName);
    |$tempArrayDataName.pointTo($arrayName, $baseOffset, (int)$arraySizeName);
    |int $counter = 0;
    |for (int k=0; k < $childVariableName.numElements(); k++) {
    |  ArrayData arr = $childVariableName.getArray(k);
    |  for (int l = 0; l < arr.numElements(); l++) {
    |   if (arr.isNullAt(l)) {
    |     $tempArrayDataName.setNullAt($counter);
    |   } else {
    |     $tempArrayDataName.set$primitiveValueTypeName(
    |       $counter,
    |       ${CodeGenerator.getValue("arr", elementType, "l")}
    |     );
    |   }
    |   $counter++;
    | }
    |}
    |$arrayDataName = $tempArrayDataName;
    """.stripMargin
  }

  private def genCodeForFlattenOfNonPrimitiveElements(
      ctx: CodegenContext,
      childVariableName: String,
      arrayDataName: String): String = {
    val genericArrayClass = classOf[GenericArrayData].getName
    val arrayName = ctx.freshName("arrayObject")
    val counter = ctx.freshName("counter")
    val (numElemCode, numElemName) = genCodeForNumberOfElements(ctx, childVariableName)

    s"""
    |$numElemCode
    |Object[] $arrayName = new Object[(int)$numElemName];
    |int $counter = 0;
    |for (int k=0; k < $childVariableName.numElements(); k++) {
    |  ArrayData arr = $childVariableName.getArray(k);
    |  for (int l = 0; l < arr.numElements(); l++) {
    |    $arrayName[$counter] = ${CodeGenerator.getValue("arr", elementType, "l")};
    |    $counter++;
    |  }
    |}
    |$arrayDataName = new $genericArrayClass($arrayName);
    """.stripMargin
  }

  override def prettyName: String = "flatten"
}
