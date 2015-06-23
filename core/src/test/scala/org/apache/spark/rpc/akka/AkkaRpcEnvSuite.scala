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

package org.apache.spark.rpc.akka

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.ask

import com.typesafe.config.{ConfigFactory, ConfigValueFactory}

import org.apache.spark.{SecurityManager, SparkConf}
import org.apache.spark.rpc._

class AkkaRpcEnvSuite extends RpcEnvSuite {

  override def createRpcEnv(conf: SparkConf, name: String, port: Int): RpcEnv = {
    new AkkaRpcEnvFactory().create(
      RpcEnvConfig(conf, name, "localhost", port, new SecurityManager(conf)))
  }

  test("setupEndpointRef: systemName, address, endpointName") {
    val ref = env.setupEndpoint("test_endpoint", new RpcEndpoint {
      override val rpcEnv = env

      override def receive = {
        case _ =>
      }
    })
    val conf = new SparkConf()
    val newRpcEnv = new AkkaRpcEnvFactory().create(
      RpcEnvConfig(conf, "test", "localhost", 12346, new SecurityManager(conf)))
    try {
      val newRef = newRpcEnv.setupEndpointRef("local", ref.address, "test_endpoint")
      assert(s"akka.tcp://local@${env.address}/user/test_endpoint" ===
        newRef.asInstanceOf[AkkaRpcEndpointRef].actorRef.path.toString)
    } finally {
      newRpcEnv.shutdown()
    }
  }

  test("timeout on ask Future with RpcTimeout") {

    class EchoActor(sleepDuration: Long) extends Actor {
      def receive: Receive = {
        case msg =>
          Thread.sleep(sleepDuration)
          sender() ! msg
      }
    }

    val akkaConf = ConfigFactory.empty().withValue("akka.log-dead-letters",
      ConfigValueFactory.fromAnyRef("off"))
    val system = ActorSystem("EchoSystem", akkaConf)
    val echoActor = system.actorOf(Props(new EchoActor(0)), name = "echo")
    val sleepyActor = system.actorOf(Props(new EchoActor(50)), name = "sleepy")

    val longProp = "spark.rpc.long.timeout"
    val longTimeout = new RpcTimeout(1 second, longProp)
    val shortProp = "spark.rpc.short.timeout"
    val shortTimeout = new RpcTimeout(10 millis, shortProp)

    try {

      // Ask with immediate response
      var fut = echoActor.ask("hello")(longTimeout.duration).mapTo[String].
        recover(longTimeout.addMessageIfTimeout)

      // This should complete successfully
      val result = longTimeout.awaitResult(fut)

      assert(result.nonEmpty)

      // Ask with a delayed response and wait for response immediately that should timeout
      fut = sleepyActor.ask("doh")(shortTimeout.duration).mapTo[String]
      val msg1 =
        intercept[RpcTimeoutException] {
          shortTimeout.awaitResult(fut)
        }.getMessage()

      assert(msg1.contains(shortProp))

      // Ask with delayed response using addMessageIfTimeout in recover callback
      fut = sleepyActor.ask("goodbye")(shortTimeout.duration).mapTo[String].
        recover(shortTimeout.addMessageIfTimeout)

      // Allow future to complete with failure using plain Await.result, this will return
      // once the future is complete to verify addMessageIfTimeout was invoked
      val msg2 =
        intercept[RpcTimeoutException] {
          Await.result(fut, 200 millis)
        }.getMessage()

      assert(msg2.contains(shortProp))

      // Use RpcTimeout.awaitResult to process Future, since it has already failed with
      // RpcTimeoutException, the same exception should be thrown
      val msg3 =
        intercept[RpcTimeoutException] {
          shortTimeout.awaitResult(fut)
        }.getMessage()

      // Ensure description is not in message twice after addMessageIfTimeout and awaitResult
      assert(shortProp.r.findAllIn(msg3).length === 1)

    } finally {
      system.shutdown()
    }
  }

}
