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

import java.nio.ByteBuffer

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

import org.apache.hadoop.hive.ql.exec._
import org.apache.hadoop.hive.ql.udf.{UDFType => HiveUDFType}
import org.apache.hadoop.hive.ql.udf.generic._
import org.apache.hadoop.hive.ql.udf.generic.GenericUDAFEvaluator.AggregationBuffer
import org.apache.hadoop.hive.ql.udf.generic.GenericUDF._
import org.apache.hadoop.hive.ql.udf.generic.GenericUDFUtils.ConversionHelper
import org.apache.hadoop.hive.serde2.objectinspector.{ConstantObjectInspector, ObjectInspector, ObjectInspectorFactory}
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory.ObjectInspectorOptions

import org.apache.spark.api.java.function.Function0
import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate._
import org.apache.spark.sql.catalyst.expressions.codegen.{CodegenContext, CodegenFallback, ExprCode}
import org.apache.spark.sql.hive.HiveShim._
import org.apache.spark.sql.types._


/** Utility trait for [[HiveSimpleUDF]] and [[HiveGenericUDF]]. */
private[hive] trait HiveUDFCodegen {
  self: Expression with HiveInspectors =>

  val funcWrapper: HiveFunctionWrapper
  val children: Seq[Expression]

  def getArgumentInspector(i: Int): ObjectInspector

  def getReturnInspector(): ObjectInspector

  @transient
  protected lazy val wrappers = children.zipWithIndex.map { case (expr, i) =>
    wrapperFor(getArgumentInspector(i), expr.dataType)
  }.toArray

  @transient
  protected lazy val unwrapper = unwrapperFor(getReturnInspector())

  lazy val udfErrorMessage = {
    val funcCls = funcWrapper.functionClassName
    val inputTypes = children.map(_.dataType.simpleString).mkString(", ")
    s"Failed to execute user defined function($funcCls: ($inputTypes) => ${dataType.simpleString})"
  }

  def getChildren(): Seq[Expression] = children

  def getWrapper(index: Int): (Any) => Any = wrappers(index)

  def getUnwrapper(): (Any) => Any = unwrapper

  def genCodeForWrapper(ctx: CodegenContext, evals: Seq[ExprCode], udf: String)
    : (Seq[String], Seq[String]) = {
    // Generate codes used to convert an argument to a Hive type
    val converterTerms = children.indices.map { index =>
      val converterClassName = classOf[Any => Any].getName
      val converterTerm = ctx.freshName("converter")
      ctx.addMutableState(converterClassName, converterTerm,
        s"this.$converterTerm = ($converterClassName) $udf.getWrapper($index);")
      converterTerm
    }

    converterTerms.zipWithIndex.map { case (converter, i) =>
      val eval = evals(i)
      val argTerm = ctx.freshName("arg")
      val convert = s"final Object $argTerm = ${eval.isNull} ? " +
        s"null : $converter.apply(${eval.value});"
      (convert, argTerm)
    }.unzip
  }

  def genCodeForUnwrapper(ctx: CodegenContext, ev: ExprCode, udf: String, callFunc: String)
    : String = {
    val resultTerm = ctx.freshName("result")

    // Generate codes used to convert the returned value of `HiveSimpleUDF` to Catalyst type
    val converterClassName = classOf[Any => Any].getName
    val catalystConverterTerm = ctx.freshName("catalystConverter")
    ctx.addMutableState(converterClassName, catalystConverterTerm,
      s"this.$catalystConverterTerm = ($converterClassName)$udf.getUnwrapper();")

    s"""
       ${ctx.boxedType(dataType)} $resultTerm = null;
       try {
         $resultTerm = (${ctx.boxedType(dataType)})$catalystConverterTerm.apply($callFunc);
       } catch (Exception e) {
         throw new org.apache.spark.SparkException($udf.udfErrorMessage(), e);
       }
       boolean ${ev.isNull} = $resultTerm == null;
       ${ctx.boxedType(dataType)} ${ev.value} = ${ctx.defaultValue(dataType)};
       if (!${ev.isNull}) {
         ${ev.value} = $resultTerm;
       }
     """
  }
}

private[hive] case class HiveSimpleUDF(
    name: String, funcWrapper: HiveFunctionWrapper, children: Seq[Expression])
  extends Expression with HiveInspectors with HiveUDFCodegen with Logging {

  override def deterministic: Boolean = isUDFDeterministic

  override def nullable: Boolean = true

  @transient
  lazy val function = funcWrapper.createFunction[UDF]()

  @transient
  private lazy val method =
    function.getResolver.getEvalMethod(children.map(_.dataType.toTypeInfo).asJava)

  @transient
  private lazy val arguments = children.map(toInspector).toArray

  @transient
  private lazy val isUDFDeterministic = {
    val udfType = function.getClass.getAnnotation(classOf[HiveUDFType])
    udfType != null && udfType.deterministic() && !udfType.stateful()
  }

  override def foldable: Boolean = isUDFDeterministic && children.forall(_.foldable)

  // Create parameter converters
  @transient
  private lazy val conversionHelper = new ConversionHelper(method, arguments)

  override lazy val dataType = javaClassToDataType(method.getReturnType)

  def getArgumentInspector(i: Int): ObjectInspector = toInspector(children(i))

  def getReturnInspector(): ObjectInspector = {
    ObjectInspectorFactory.getReflectionObjectInspector(
      method.getGenericReturnType, ObjectInspectorOptions.JAVA)
  }

  @transient
  private lazy val cached: Array[AnyRef] = new Array[AnyRef](children.length)

  @transient
  private lazy val inputDataTypes: Array[DataType] = children.map(_.dataType).toArray

  @scala.annotation.varargs
  def callUdf(args: AnyRef*): AnyRef = {
    FunctionRegistry.invoke(
      method,
      function,
      conversionHelper.convertIfNecessary(args: _*): _*)
  }

  protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val hiveUDF = ctx.addReferenceObj("hiveUDF", this)

    // codegen for children expressions
    val evals = children.map(_.genCode(ctx))

    // Generate the codes for expressions and calling `HiveSimpleUDF`.
    // We need to get the boxedType of dataType's javaType here. Because for the dataType
    // such as IntegerType, its javaType is `int` and the returned type of the function  is Object.
    // Trying to convert an Object to `int` will cause casting exception.
    val evalCode = evals.map(_.code).mkString

    val (converters, funcArguments) = genCodeForWrapper(ctx, evals, hiveUDF)

    val callFunc = s"$hiveUDF.callUdf(${funcArguments.mkString(", ")})"

    ev.copy(code = s"""
      $evalCode
      ${converters.mkString("\n")}
      ${genCodeForUnwrapper(ctx, ev, hiveUDF, callFunc)}
      """)
  }

  // TODO: Finish input output types.
  override def eval(input: InternalRow): Any = {
    val inputs = wrap(children.map(_.eval(input)), wrappers, cached, inputDataTypes)
    val ret = FunctionRegistry.invoke(
      method,
      function,
      conversionHelper.convertIfNecessary(inputs : _*): _*)
    unwrapper(ret)
  }

  override def toString: String = {
    s"$nodeName#${funcWrapper.functionClassName}(${children.mkString(",")})"
  }

  override def prettyName: String = name

  override def sql: String = s"$name(${children.map(_.sql).mkString(", ")})"
}

// Adapter from Catalyst ExpressionResult to Hive DeferredObject
private[hive] class DeferredObjectAdapter extends DeferredObject {

  private var func: Function0[AnyRef] = _
  def set(func: Function0[AnyRef]): Unit = {
    this.func = func
  }
  override def prepare(i: Int): Unit = {}
  override def get(): AnyRef = func.call()
}

private[hive] case class HiveGenericUDF(
    name: String, funcWrapper: HiveFunctionWrapper, children: Seq[Expression])
  extends Expression with HiveInspectors with HiveUDFCodegen with Logging {

  override def nullable: Boolean = true

  override def deterministic: Boolean = isUDFDeterministic

  override def foldable: Boolean =
    isUDFDeterministic && returnInspector.isInstanceOf[ConstantObjectInspector]

  @transient
  lazy val function = funcWrapper.createFunction[GenericUDF]()

  @transient
  private lazy val argumentInspectors = children.map(toInspector)

  @transient
  private lazy val returnInspector = {
    function.initializeAndFoldConstants(argumentInspectors.toArray)
  }

  @transient
  private lazy val isUDFDeterministic = {
    val udfType = function.getClass.getAnnotation(classOf[HiveUDFType])
    udfType != null && udfType.deterministic() && !udfType.stateful()
  }

  @transient
  private lazy val deferredObjects = Seq.tabulate(children.size) { i =>
    new DeferredObjectAdapter()
  }.toArray[DeferredObject]

  override lazy val dataType: DataType = inspectorToDataType(returnInspector)

  def getArgumentInspector(i: Int): ObjectInspector = argumentInspectors(i)

  def getReturnInspector(): ObjectInspector = returnInspector

  def getDeferredObjects(): Array[DeferredObject] = deferredObjects

  def callUdf(args: Array[DeferredObject]): AnyRef = {
    function.evaluate(deferredObjects)
  }

  protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val hiveUDF = ctx.addReferenceObj("hiveUDF", this)

    // Generate codes used to convert input values into `DeferredObject`s
    val deferredObjectsClassName = classOf[DeferredObject].getName + "[]"
    val deferredObjectsTerm = ctx.freshName("deferredObjects")
    ctx.addMutableState(deferredObjectsClassName, deferredObjectsTerm,
      s"this.$deferredObjectsTerm = ($deferredObjectsClassName)$hiveUDF.getDeferredObjects();")

    // Make sure initialized
    val objectInspectorClassName = classOf[ObjectInspector].getName
    val objectInspectorTerm = ctx.freshName("objectInspector")
    ctx.addMutableState(objectInspectorClassName, objectInspectorTerm,
      s"this.$objectInspectorTerm = ($objectInspectorClassName)$hiveUDF.getReturnInspector();")

    // codegen for children expressions
    val evals = children.map(_.genCode(ctx))

    // Generate the codes for expressions and calling `HiveGenericUDF`.
    val evalCode = evals.map(_.code).mkString

    val (converters, funcArguments) = genCodeForWrapper(ctx, evals, hiveUDF)

    val funcClassName = classOf[Function0[AnyRef]].getName
    val deferredObjectAdapterClassName = classOf[DeferredObjectAdapter].getName
    val argsTerm = evals.zipWithIndex.map { case (eval, i) =>
      s"""
         (($deferredObjectAdapterClassName) this.$deferredObjectsTerm[$i])
           .set(new ${funcClassName}<Object>() {

             @Override
             public Object call() {
               return ${funcArguments(i)};
             }
           });
       """.stripMargin
    }

    val callFunc = s"$hiveUDF.callUdf($deferredObjectsTerm)"

    ev.copy(code = s"""
      $evalCode
      ${converters.mkString("\n")}
      ${argsTerm.mkString("\n")}
      ${genCodeForUnwrapper(ctx, ev, hiveUDF, callFunc)}
      """)
  }

  override def eval(input: InternalRow): Any = {
    returnInspector // Make sure initialized.

    var i = 0
    val length = children.length
    while (i < length) {
      val idx = i
      deferredObjects(i).asInstanceOf[DeferredObjectAdapter].set(new Function0[AnyRef]() {
        override def call(): AnyRef = {
          wrappers(idx)(children(idx).eval(input)).asInstanceOf[AnyRef]
        }
      })
      i += 1
    }
    unwrapper(function.evaluate(deferredObjects))
  }

  override def prettyName: String = name

  override def toString: String = {
    s"$nodeName#${funcWrapper.functionClassName}(${children.mkString(",")})"
  }
}

/**
 * Converts a Hive Generic User Defined Table Generating Function (UDTF) to a
 * `Generator`. Note that the semantics of Generators do not allow
 * Generators to maintain state in between input rows.  Thus UDTFs that rely on partitioning
 * dependent operations like calls to `close()` before producing output will not operate the same as
 * in Hive.  However, in practice this should not affect compatibility for most sane UDTFs
 * (e.g. explode or GenericUDTFParseUrlTuple).
 *
 * Operators that require maintaining state in between input rows should instead be implemented as
 * user defined aggregations, which have clean semantics even in a partitioned execution.
 */
private[hive] case class HiveGenericUDTF(
    name: String,
    funcWrapper: HiveFunctionWrapper,
    children: Seq[Expression])
  extends Generator with HiveInspectors with CodegenFallback {

  @transient
  protected lazy val function: GenericUDTF = {
    val fun: GenericUDTF = funcWrapper.createFunction()
    fun.setCollector(collector)
    fun
  }

  @transient
  protected lazy val inputInspectors = children.map(toInspector)

  @transient
  protected lazy val outputInspector = function.initialize(inputInspectors.toArray)

  @transient
  protected lazy val udtInput = new Array[AnyRef](children.length)

  @transient
  protected lazy val collector = new UDTFCollector

  override lazy val elementSchema = StructType(outputInspector.getAllStructFieldRefs.asScala.map {
    field => StructField(field.getFieldName, inspectorToDataType(field.getFieldObjectInspector),
      nullable = true)
  })

  @transient
  private lazy val inputDataTypes: Array[DataType] = children.map(_.dataType).toArray

  @transient
  private lazy val wrappers = children.map(x => wrapperFor(toInspector(x), x.dataType)).toArray

  @transient
  private lazy val unwrapper = unwrapperFor(outputInspector)

  override def eval(input: InternalRow): TraversableOnce[InternalRow] = {
    outputInspector // Make sure initialized.

    val inputProjection = new InterpretedProjection(children)

    function.process(wrap(inputProjection(input), wrappers, udtInput, inputDataTypes))
    collector.collectRows()
  }

  protected class UDTFCollector extends Collector {
    var collected = new ArrayBuffer[InternalRow]

    override def collect(input: java.lang.Object) {
      // We need to clone the input here because implementations of
      // GenericUDTF reuse the same object. Luckily they are always an array, so
      // it is easy to clone.
      collected += unwrapper(input).asInstanceOf[InternalRow]
    }

    def collectRows(): Seq[InternalRow] = {
      val toCollect = collected
      collected = new ArrayBuffer[InternalRow]
      toCollect
    }
  }

  override def terminate(): TraversableOnce[InternalRow] = {
    outputInspector // Make sure initialized.
    function.close()
    collector.collectRows()
  }

  override def toString: String = {
    s"$nodeName#${funcWrapper.functionClassName}(${children.mkString(",")})"
  }

  override def prettyName: String = name
}

/**
 * While being evaluated by Spark SQL, the aggregation state of a Hive UDAF may be in the following
 * three formats:
 *
 *  1. An instance of some concrete `GenericUDAFEvaluator.AggregationBuffer` class
 *
 *     This is the native Hive representation of an aggregation state. Hive `GenericUDAFEvaluator`
 *     methods like `iterate()`, `merge()`, `terminatePartial()`, and `terminate()` use this format.
 *     We call these methods to evaluate Hive UDAFs.
 *
 *  2. A Java object that can be inspected using the `ObjectInspector` returned by the
 *     `GenericUDAFEvaluator.init()` method.
 *
 *     Hive uses this format to produce a serializable aggregation state so that it can shuffle
 *     partial aggregation results. Whenever we need to convert a Hive `AggregationBuffer` instance
 *     into a Spark SQL value, we have to convert it to this format first and then do the conversion
 *     with the help of `ObjectInspector`s.
 *
 *  3. A Spark SQL value
 *
 *     We use this format for serializing Hive UDAF aggregation states on Spark side. To be more
 *     specific, we convert `AggregationBuffer`s into equivalent Spark SQL values, write them into
 *     `UnsafeRow`s, and then retrieve the byte array behind those `UnsafeRow`s as serialization
 *     results.
 *
 * We may use the following methods to convert the aggregation state back and forth:
 *
 *  - `wrap()`/`wrapperFor()`: from 3 to 1
 *  - `unwrap()`/`unwrapperFor()`: from 1 to 3
 *  - `GenericUDAFEvaluator.terminatePartial()`: from 2 to 3
 */
private[hive] case class HiveUDAFFunction(
    name: String,
    funcWrapper: HiveFunctionWrapper,
    children: Seq[Expression],
    isUDAFBridgeRequired: Boolean = false,
    mutableAggBufferOffset: Int = 0,
    inputAggBufferOffset: Int = 0)
  extends TypedImperativeAggregate[GenericUDAFEvaluator.AggregationBuffer] with HiveInspectors {

  override def withNewMutableAggBufferOffset(newMutableAggBufferOffset: Int): ImperativeAggregate =
    copy(mutableAggBufferOffset = newMutableAggBufferOffset)

  override def withNewInputAggBufferOffset(newInputAggBufferOffset: Int): ImperativeAggregate =
    copy(inputAggBufferOffset = newInputAggBufferOffset)

  // Hive `ObjectInspector`s for all child expressions (input parameters of the function).
  @transient
  private lazy val inputInspectors = children.map(toInspector).toArray

  // Spark SQL data types of input parameters.
  @transient
  private lazy val inputDataTypes: Array[DataType] = children.map(_.dataType).toArray

  private def newEvaluator(): GenericUDAFEvaluator = {
    val resolver = if (isUDAFBridgeRequired) {
      new GenericUDAFBridge(funcWrapper.createFunction[UDAF]())
    } else {
      funcWrapper.createFunction[AbstractGenericUDAFResolver]()
    }

    val parameterInfo = new SimpleGenericUDAFParameterInfo(inputInspectors, false, false)
    resolver.getEvaluator(parameterInfo)
  }

  // The UDAF evaluator used to consume raw input rows and produce partial aggregation results.
  @transient
  private lazy val partial1ModeEvaluator = newEvaluator()

  // Hive `ObjectInspector` used to inspect partial aggregation results.
  @transient
  private val partialResultInspector = partial1ModeEvaluator.init(
    GenericUDAFEvaluator.Mode.PARTIAL1,
    inputInspectors
  )

  // The UDAF evaluator used to merge partial aggregation results.
  @transient
  private lazy val partial2ModeEvaluator = {
    val evaluator = newEvaluator()
    evaluator.init(GenericUDAFEvaluator.Mode.PARTIAL2, Array(partialResultInspector))
    evaluator
  }

  // Spark SQL data type of partial aggregation results
  @transient
  private lazy val partialResultDataType = inspectorToDataType(partialResultInspector)

  // The UDAF evaluator used to compute the final result from a partial aggregation result objects.
  @transient
  private lazy val finalModeEvaluator = newEvaluator()

  // Hive `ObjectInspector` used to inspect the final aggregation result object.
  @transient
  private val returnInspector = finalModeEvaluator.init(
    GenericUDAFEvaluator.Mode.FINAL,
    Array(partialResultInspector)
  )

  // Wrapper functions used to wrap Spark SQL input arguments into Hive specific format.
  @transient
  private lazy val inputWrappers = children.map(x => wrapperFor(toInspector(x), x.dataType)).toArray

  // Unwrapper function used to unwrap final aggregation result objects returned by Hive UDAFs into
  // Spark SQL specific format.
  @transient
  private lazy val resultUnwrapper = unwrapperFor(returnInspector)

  @transient
  private lazy val cached: Array[AnyRef] = new Array[AnyRef](children.length)

  @transient
  private lazy val aggBufferSerDe: AggregationBufferSerDe = new AggregationBufferSerDe

  override def nullable: Boolean = true

  override lazy val dataType: DataType = inspectorToDataType(returnInspector)

  override def prettyName: String = name

  override def sql(isDistinct: Boolean): String = {
    val distinct = if (isDistinct) "DISTINCT " else " "
    s"$name($distinct${children.map(_.sql).mkString(", ")})"
  }

  override def createAggregationBuffer(): AggregationBuffer =
    partial1ModeEvaluator.getNewAggregationBuffer

  @transient
  private lazy val inputProjection = UnsafeProjection.create(children)

  override def update(buffer: AggregationBuffer, input: InternalRow): AggregationBuffer = {
    partial1ModeEvaluator.iterate(
      buffer, wrap(inputProjection(input), inputWrappers, cached, inputDataTypes))
    buffer
  }

  override def merge(buffer: AggregationBuffer, input: AggregationBuffer): AggregationBuffer = {
    // The 2nd argument of the Hive `GenericUDAFEvaluator.merge()` method is an input aggregation
    // buffer in the 3rd format mentioned in the ScalaDoc of this class. Originally, Hive converts
    // this `AggregationBuffer`s into this format before shuffling partial aggregation results, and
    // calls `GenericUDAFEvaluator.terminatePartial()` to do the conversion.
    partial2ModeEvaluator.merge(buffer, partial1ModeEvaluator.terminatePartial(input))
    buffer
  }

  override def eval(buffer: AggregationBuffer): Any = {
    resultUnwrapper(finalModeEvaluator.terminate(buffer))
  }

  override def serialize(buffer: AggregationBuffer): Array[Byte] = {
    // Serializes an `AggregationBuffer` that holds partial aggregation results so that we can
    // shuffle it for global aggregation later.
    aggBufferSerDe.serialize(buffer)
  }

  override def deserialize(bytes: Array[Byte]): AggregationBuffer = {
    // Deserializes an `AggregationBuffer` from the shuffled partial aggregation phase to prepare
    // for global aggregation by merging multiple partial aggregation results within a single group.
    aggBufferSerDe.deserialize(bytes)
  }

  // Helper class used to de/serialize Hive UDAF `AggregationBuffer` objects
  private class AggregationBufferSerDe {
    private val partialResultUnwrapper = unwrapperFor(partialResultInspector)

    private val partialResultWrapper = wrapperFor(partialResultInspector, partialResultDataType)

    private val projection = UnsafeProjection.create(Array(partialResultDataType))

    private val mutableRow = new GenericInternalRow(1)

    def serialize(buffer: AggregationBuffer): Array[Byte] = {
      // `GenericUDAFEvaluator.terminatePartial()` converts an `AggregationBuffer` into an object
      // that can be inspected by the `ObjectInspector` returned by `GenericUDAFEvaluator.init()`.
      // Then we can unwrap it to a Spark SQL value.
      mutableRow.update(0, partialResultUnwrapper(partial1ModeEvaluator.terminatePartial(buffer)))
      val unsafeRow = projection(mutableRow)
      val bytes = ByteBuffer.allocate(unsafeRow.getSizeInBytes)
      unsafeRow.writeTo(bytes)
      bytes.array()
    }

    def deserialize(bytes: Array[Byte]): AggregationBuffer = {
      // `GenericUDAFEvaluator` doesn't provide any method that is capable to convert an object
      // returned by `GenericUDAFEvaluator.terminatePartial()` back to an `AggregationBuffer`. The
      // workaround here is creating an initial `AggregationBuffer` first and then merge the
      // deserialized object into the buffer.
      val buffer = partial2ModeEvaluator.getNewAggregationBuffer
      val unsafeRow = new UnsafeRow(1)
      unsafeRow.pointTo(bytes, bytes.length)
      val partialResult = unsafeRow.get(0, partialResultDataType)
      partial2ModeEvaluator.merge(buffer, partialResultWrapper(partialResult))
      buffer
    }
  }
}
