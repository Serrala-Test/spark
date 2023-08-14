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
package org.apache.spark.sql.connect.client

import java.util.UUID
import java.util.concurrent.TimeUnit

import io.grpc.{CallOptions, Channel, ClientCall, ClientInterceptor, MethodDescriptor, Server, Status, StatusRuntimeException}
import io.grpc.netty.NettyServerBuilder
import org.scalatest.BeforeAndAfterEach

import org.apache.spark.SparkException
import org.apache.spark.connect.proto
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.connect.client.util.{ConnectFunSuite, DummySparkConnectService}
import org.apache.spark.sql.connect.common.config.ConnectCommon

class SparkConnectClientSuite extends ConnectFunSuite with BeforeAndAfterEach {

  private var client: SparkConnectClient = _
  private var service: DummySparkConnectService = _
  private var server: Server = _

  private def startDummyServer(port: Int): Unit = {
    service = new DummySparkConnectService
    server = NettyServerBuilder
      .forPort(port)
      .addService(service)
      .build()
    server.start()
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    client = null
    server = null
    service = null
  }

  override def afterEach(): Unit = {
    if (server != null) {
      server.shutdownNow()
      assert(server.awaitTermination(5, TimeUnit.SECONDS), "server failed to shutdown")
    }

    if (client != null) {
      client.shutdown()
    }
  }

  test("Placeholder test: Create SparkConnectClient") {
    client = SparkConnectClient.builder().userId("abc123").build()
    assert(client.userId == "abc123")
  }

  // Use 0 to start the server at a random port
  private def testClientConnection(serverPort: Int = 0)(
      clientBuilder: Int => SparkConnectClient): Unit = {
    startDummyServer(serverPort)
    client = clientBuilder(server.getPort)
    val request = proto.AnalyzePlanRequest
      .newBuilder()
      .setSessionId("abc123")
      .build()

    val response = client.analyze(request)
    assert(response.getSessionId === "abc123")
  }

  test("Test connection") {
    testClientConnection() { testPort => SparkConnectClient.builder().port(testPort).build() }
  }

  test("Test connection string") {
    testClientConnection() { testPort =>
      SparkConnectClient.builder().connectionString(s"sc://localhost:$testPort").build()
    }
  }

  test("Test encryption") {
    startDummyServer(0)
    client = SparkConnectClient
      .builder()
      .connectionString(s"sc://localhost:${server.getPort}/;use_ssl=true")
      .retryPolicy(GrpcRetryHandler.RetryPolicy(maxRetries = 0))
      .build()

    val request = proto.AnalyzePlanRequest.newBuilder().setSessionId("abc123").build()

    // Failed the ssl handshake as the dummy server does not have any server credentials installed.
    assertThrows[SparkException] {
      client.analyze(request)
    }
  }

  test("SparkSession initialisation with connection string") {
    startDummyServer(0)
    client = SparkConnectClient
      .builder()
      .connectionString(s"sc://localhost:${server.getPort}")
      .build()

    val session = SparkSession.builder().client(client).create()
    val df = session.range(10)
    df.analyze // Trigger RPC
    assert(df.plan === service.getAndClearLatestInputPlan())
  }

  test("Custom Interceptor") {
    startDummyServer(0)
    client = SparkConnectClient
      .builder()
      .connectionString(s"sc://localhost:${server.getPort}")
      .interceptor(new ClientInterceptor {
        override def interceptCall[ReqT, RespT](
            methodDescriptor: MethodDescriptor[ReqT, RespT],
            callOptions: CallOptions,
            channel: Channel): ClientCall[ReqT, RespT] = {
          throw new RuntimeException("Blocked")
        }
      })
      .build()

    val session = SparkSession.builder().client(client).create()

    assertThrows[RuntimeException] {
      session.range(10).count()
    }
  }

  private case class TestPackURI(
      connectionString: String,
      isCorrect: Boolean,
      extraChecks: SparkConnectClient => Unit = _ => {})

  private val URIs = Seq[TestPackURI](
    TestPackURI("sc://host", isCorrect = true),
    TestPackURI(
      "sc://localhost/",
      isCorrect = true,
      client => testClientConnection(ConnectCommon.CONNECT_GRPC_BINDING_PORT)(_ => client)),
    TestPackURI(
      "sc://localhost:1234/",
      isCorrect = true,
      client => {
        assert(client.configuration.host == "localhost")
        assert(client.configuration.port == 1234)
        assert(client.sessionId != null)
        // Must be able to parse the UUID
        assert(UUID.fromString(client.sessionId) != null)
      }),
    TestPackURI(
      "sc://localhost/;",
      isCorrect = true,
      client => {
        assert(client.configuration.host == "localhost")
        assert(client.configuration.port == ConnectCommon.CONNECT_GRPC_BINDING_PORT)
      }),
    TestPackURI("sc://host:123", isCorrect = true),
    TestPackURI(
      "sc://host:123/;user_id=a94",
      isCorrect = true,
      client => assert(client.userId == "a94")),
    TestPackURI(
      "sc://host:123/;user_agent=a945",
      isCorrect = true,
      client => assert(client.userAgent == "a945")),
    TestPackURI("scc://host:12", isCorrect = false),
    TestPackURI("http://host", isCorrect = false),
    TestPackURI("sc:/host:1234/path", isCorrect = false),
    TestPackURI("sc://host/path", isCorrect = false),
    TestPackURI("sc://host/;parm1;param2", isCorrect = false),
    TestPackURI("sc://host:123;user_id=a94", isCorrect = false),
    TestPackURI("sc:///user_id=123", isCorrect = false),
    TestPackURI("sc://host:-4", isCorrect = false),
    TestPackURI("sc://:123/", isCorrect = false),
    TestPackURI("sc://host:123/;use_ssl=true", isCorrect = true),
    TestPackURI("sc://host:123/;token=mySecretToken", isCorrect = true),
    TestPackURI("sc://host:123/;token=", isCorrect = false),
    TestPackURI("sc://host:123/;session_id=", isCorrect = false),
    TestPackURI("sc://host:123/;session_id=abcdefgh", isCorrect = false),
    TestPackURI(s"sc://host:123/;session_id=${UUID.randomUUID().toString}", isCorrect = true),
    TestPackURI("sc://host:123/;use_ssl=true;token=mySecretToken", isCorrect = true),
    TestPackURI("sc://host:123/;token=mySecretToken;use_ssl=true", isCorrect = true),
    TestPackURI("sc://host:123/;use_ssl=false;token=mySecretToken", isCorrect = false),
    TestPackURI("sc://host:123/;token=mySecretToken;use_ssl=false", isCorrect = false),
    TestPackURI("sc://host:123/;param1=value1;param2=value2", isCorrect = true))

  private def checkTestPack(testPack: TestPackURI): Unit = {
    val client = SparkConnectClient.builder().connectionString(testPack.connectionString).build()
    testPack.extraChecks(client)
  }

  URIs.foreach { testPack =>
    test(s"Check URI: ${testPack.connectionString}, isCorrect: ${testPack.isCorrect}") {
      if (!testPack.isCorrect) {
        assertThrows[IllegalArgumentException](checkTestPack(testPack))
      } else {
        checkTestPack(testPack)
      }
    }
  }

  private class DummyFn(val e: Throwable) {
    var counter = 0
    def fn(): Int = {
      if (counter < 3) {
        counter += 1
        throw e
      } else {
        42
      }
    }
  }

  test("SPARK-44275: retry actually retries") {
    val dummyFn = new DummyFn(new StatusRuntimeException(Status.UNAVAILABLE))
    val retryPolicy = GrpcRetryHandler.RetryPolicy()
    val retryHandler = new GrpcRetryHandler(retryPolicy)
    val result = retryHandler.retry { dummyFn.fn() }

    assert(result == 42)
    assert(dummyFn.counter == 3)
  }

  test("SPARK-44275: default retryException retries only on UNAVAILABLE") {
    val dummyFn = new DummyFn(new StatusRuntimeException(Status.ABORTED))
    val retryPolicy = GrpcRetryHandler.RetryPolicy()
    val retryHandler = new GrpcRetryHandler(retryPolicy)

    assertThrows[StatusRuntimeException] {
      retryHandler.retry { dummyFn.fn() }
    }
    assert(dummyFn.counter == 1)
  }

  test("SPARK-44275: retry uses canRetry to filter exceptions") {
    val dummyFn = new DummyFn(new StatusRuntimeException(Status.UNAVAILABLE))
    val retryPolicy = GrpcRetryHandler.RetryPolicy(canRetry = _ => false)
    val retryHandler = new GrpcRetryHandler(retryPolicy)

    assertThrows[StatusRuntimeException] {
      retryHandler.retry { dummyFn.fn() }
    }
    assert(dummyFn.counter == 1)
  }

  test("SPARK-44275: retry does not exceed maxRetries") {
    val dummyFn = new DummyFn(new StatusRuntimeException(Status.UNAVAILABLE))
    val retryPolicy = GrpcRetryHandler.RetryPolicy(canRetry = _ => true, maxRetries = 1)
    val retryHandler = new GrpcRetryHandler(retryPolicy)

    assertThrows[StatusRuntimeException] {
      retryHandler.retry { dummyFn.fn() }
    }
    assert(dummyFn.counter == 2)
  }
}
