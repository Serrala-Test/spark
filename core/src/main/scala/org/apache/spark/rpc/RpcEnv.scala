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

package org.apache.spark.rpc

import java.util.concurrent.ConcurrentHashMap

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

import com.google.common.annotations.VisibleForTesting

import org.apache.spark.{SecurityManager, SparkConf}
import org.apache.spark.util.Utils

/**
 * An RPC environment.
 */
trait RpcEnv {

  /**
   * A lookup table to search a [[RpcEndpointRef]] for a [[RpcEndpoint]]. We need it to make
   * [[RpcEndpoint.self]] work.
   */
  private val endpointToRef = new ConcurrentHashMap[RpcEndpoint, RpcEndpointRef]()

  /**
   * Need this map to remove `RpcEndpoint` from `endpointToRef` via a `RpcEndpointRef`
   */
  private val refToEndpoint = new ConcurrentHashMap[RpcEndpointRef, RpcEndpoint]()

  protected def registerEndpoint(endpoint: RpcEndpoint, endpointRef: RpcEndpointRef): Unit = {
    endpointToRef.put(endpoint, endpointRef)
    refToEndpoint.put(endpointRef, endpoint)
  }

  protected def unregisterEndpoint(endpointRef: RpcEndpointRef): Unit = {
    val endpoint = refToEndpoint.remove(endpointRef)
    if (endpoint != null) {
      endpointToRef.remove(endpoint)
    }
  }

  /**
   * Retrieve the [[RpcEndpointRef]] of `endpoint`.
   */
  def endpointRef(endpoint: RpcEndpoint): RpcEndpointRef = {
    val endpointRef = endpointToRef.get(endpoint)
    require(endpointRef != null, s"Cannot find RpcEndpointRef of ${endpoint} in ${this}")
    endpointRef
  }

  /**
   * Return the port that [[RpcEnv]] is listening to.
   */
  def boundPort: Int

  /**
   * Register a [[RpcEndpoint]] with a name and return its [[RpcEndpointRef]].
   */
  def setupEndpoint(name: String, endpointCreator: => RpcEndpoint): RpcEndpointRef

  /**
   * Retrieve a [[RpcEndpointRef]] which is located in the driver via its name.
   */
  def setupDriverEndpointRef(name: String): RpcEndpointRef

  /**
   * Retrieve the [[RpcEndpointRef]] represented by `url`.
   */
  def setupEndpointRefByUrl(url: String): RpcEndpointRef

  /**
   * Stop [[RpcEndpoint]] specified by `endpoint`.
   */
  def stop(endpoint: RpcEndpointRef): Unit

  /**
   * Shutdown this [[RpcEnv]] asynchronously. If need to make sure [[RpcEnv]] exits successfully,
   * call [[awaitTermination()]] straight after [[stopAll()]].
   */
  def stopAll(): Unit

  /**
   * Wait until [[RpcEnv]] exits.
   *
   * TODO do we need a timeout parameter?
   */
  def awaitTermination(): Unit
}

private[rpc] case class RpcEnvConfig(
    conf: SparkConf,
    name: String,
    host: String,
    port: Int,
    securityManager: SecurityManager)

/**
 * A RpcEnv implementation must have a companion object with an
 * `apply(config: RpcEnvConfig): RpcEnv` method so that it can be created via Reflection.
 *
 * {{{
 * object MyCustomRpcEnv {
 *   def apply(config: RpcEnvConfig): RpcEnv = {
 *     ...
 *   }
 * }
 * }}}
 */
object RpcEnv {

  private def getRpcEnvCompanion(conf: SparkConf): AnyRef = {
    // Add more RpcEnv implementations here
    val rpcEnvNames = Map("akka" -> "org.apache.spark.rpc.akka.AkkaRpcEnv")
    val rpcEnvName = conf.get("spark.rpc", "akka")
    val rpcEnvClassName = rpcEnvNames.getOrElse(rpcEnvName.toLowerCase, rpcEnvName)
    val companion = Class.forName(
      rpcEnvClassName + "$", true, Utils.getContextOrSparkClassLoader).getField("MODULE$").get(null)
    companion
  }

  def create(
       name: String,
       host: String,
       port: Int,
       conf: SparkConf,
       securityManager: SecurityManager): RpcEnv = {
    // Using Reflection to create the RpcEnv to avoid to depend on Akka directly
    val config = RpcEnvConfig(conf, name, host, port, securityManager)
    val companion = getRpcEnvCompanion(conf)
    companion.getClass.getMethod("apply", classOf[RpcEnvConfig]).
      invoke(companion, config).asInstanceOf[RpcEnv]
  }

  // TODO Remove it
  @VisibleForTesting
  def create(name: String, conf: SparkConf): RpcEnv = {
    val companion = getRpcEnvCompanion(conf)
    companion.getClass.getMethod("apply", classOf[String], classOf[SparkConf]).
      invoke(companion, name, conf).asInstanceOf[RpcEnv]
  }

}

/**
 * An end point for the RPC that defines what functions to trigger given a message.
 *
 * RpcEndpoint will be guaranteed that `onStart`, `receive` and `onStop` will
 * be called in sequence.
 *
 * The lift-cycle will be:
 *
 * constructor onStart receive* onStop
 *
 * If any error is thrown from one of RpcEndpoint methods except `onError`, [[RpcEndpoint.onError)]]
 * will be invoked with the cause. If onError throws an error, it will force [[RpcEndpoint]] to
 * restart by creating a new one.
 */
trait RpcEndpoint {

  /**
   * The [[RpcEnv]] that this [[RpcEndpoint]] is registered to.
   */
  val rpcEnv: RpcEnv

  /**
   * Provide the implicit sender. `self` will become valid when `onStart` is called.
   *
   * Note: Because before `onStart`, [[RpcEndpoint]] has not yet been registered and there is not
   * valid [[RpcEndpointRef]] for it. So don't call `self` before `onStart` is called. In the other
   * words, don't call [[RpcEndpointRef.send]] in the constructor of [[RpcEndpoint]].
   */
  implicit final def self: RpcEndpointRef = {
    require(rpcEnv != null, "rpcEnv has not been initialized")
    rpcEnv.endpointRef(this)
  }

  /**
   * Same assumption like Actor: messages sent to a RpcEndpoint will be delivered in sequence, and
   * messages from the same RpcEndpoint will be delivered in order.
   *
   * @param sender
   * @return
   */
  def receive(sender: RpcEndpointRef): PartialFunction[Any, Unit]

  /**
   * Call onError when any exception is thrown during handling messages.
   *
   * @param cause
   */
  def onError(cause: Throwable): Unit = {
    // By default, throw e and let RpcEnv handle it
    throw cause
  }

  /**
   * Invoked before [[RpcEndpoint]] starts to handle any message.
   */
  def onStart(): Unit = {
    // By default, do nothing.
  }

  /**
   * Invoked when [[RpcEndpoint]] is stopping.
   */
  def onStop(): Unit = {
    // By default, do nothing.
  }

  /**
   * An convenient method to stop [[RpcEndpoint]].
   */
  final def stop(): Unit = {
    rpcEnv.stop(self)
  }
}

/**
 * A RpcEndoint interested in network events.
 *
 * [[NetworkRpcEndpoint]] will be guaranteed that `onStart`, `receive` , `onConnected`,
 * `onDisconnected`, `onNetworkError` and `onStop` will be called in sequence.
 *
 * The lift-cycle will be:
 *
 * constructor onStart (receive|onConnected|onDisconnected|onNetworkError)* onStop
 *
 * If any error is thrown from `onConnected`, `onDisconnected` or `onNetworkError`,
 * [[RpcEndpoint.onError)]] will be invoked with the cause. If onError throws an error, it will
 * force [[RpcEndpoint]] to restart by creating a new one.
 */
trait NetworkRpcEndpoint extends RpcEndpoint {

  /**
   * Invoked when `remoteAddress` is connected to the current node.
   */
  def onConnected(remoteAddress: RpcAddress): Unit = {
    // By default, do nothing.
  }

  /**
   * Invoked when `remoteAddress` is lost.
   */
  def onDisconnected(remoteAddress: RpcAddress): Unit = {
    // By default, do nothing.
  }

  /**
   * Invoked when some network error happens in the connection between the current node and
   * `remoteAddress`.
   */
  def onNetworkError(cause: Throwable, remoteAddress: RpcAddress): Unit = {
    // By default, do nothing.
  }
}

object RpcEndpoint {
  final val noSender: RpcEndpointRef = null
}

/**
 * A reference for a remote [[RpcEndpoint]]. [[RpcEndpointRef]] is thread-safe.
 */
trait RpcEndpointRef {

  /**
   * return the address for the [[RpcEndpointRef]]
   */
  def address: RpcAddress

  /**
   * Send a message to the corresponding [[RpcEndpoint]] and return a `Future` to receive the reply
   * within a default timeout.
   */
  def ask[T: ClassTag](message: Any): Future[T]

  /**
   * Send a message to the corresponding [[RpcEndpoint]] and return a `Future` to receive the reply
   * within the specified timeout.
   */
  def ask[T: ClassTag](message: Any, timeout: FiniteDuration): Future[T]

  /**
   * Send a message to the corresponding [[RpcEndpoint]] and get its result within a default
   * timeout, or throw a SparkException if this fails even after the default number of retries.
   *
   * Note: this is a blocking action which may cost a lot of time,  so don't call it in an message
   * loop of [[RpcEndpoint]].
   *
   * @param message the message to send
   * @tparam T type of the reply message
   * @return the reply message from the corresponding [[RpcEndpoint]]
   */
  def askWithReply[T](message: Any): T

  /**
   * Send a message to the corresponding [[RpcEndpoint]] and get its result within a specified
   * timeout, throw a SparkException if this fails even after the specified number of retries.
   *
   * Note: this is a blocking action which may cost a lot of time, so don't call it in an message
   * loop of [[RpcEndpoint]].
   *
   * @param message the message to send
   * @param timeout the timeout duration
   * @tparam T type of the reply message
   * @return the reply message from the corresponding [[RpcEndpoint]]
   */
  def askWithReply[T](message: Any, timeout: FiniteDuration): T

  /**
   * Sends a one-way asynchronous message. Fire-and-forget semantics.
   *
   * If invoked from within an [[RpcEndpoint]] then `self` is implicitly passed on as the implicit
   * 'sender' argument. If not then no sender is available.
   *
   * This `sender` reference is then available in the receiving [[RpcEndpoint]] as the `sender`
   * parameter of [[RpcEndpoint.receive]]
   */
  def send(message: Any)(implicit sender: RpcEndpointRef = RpcEndpoint.noSender): Unit
}

/**
 * Represent a host with a port
 */
case class RpcAddress(host: String, port: Int) {
  // TODO do we need to add the type of RpcEnv in the address?

  val hostPort: String = host + ":" + port

  override val toString: String = hostPort
}

object RpcAddress {

  /**
   * Return the [[RpcAddress]] represented by `uri`.
   */
  def fromURIString(uri: String): RpcAddress = {
    val u = new java.net.URI(uri)
    RpcAddress(u.getHost, u.getPort)
  }

}
