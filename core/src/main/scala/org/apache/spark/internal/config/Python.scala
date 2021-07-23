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
package org.apache.spark.internal.config

import java.util.concurrent.TimeUnit

import org.apache.spark.network.util.ByteUnit

private[spark] object Python {
  val PYTHON_WORKER_REUSE = ConfigBuilder("spark.python.worker.reuse")
    .version("1.2.0")
    .scope(EffectTiming.DEPLOYING_APPLICATION)
    .booleanConf
    .createWithDefault(true)

  val PYTHON_TASK_KILL_TIMEOUT = ConfigBuilder("spark.python.task.killTimeout")
    .version("2.2.2")
    .scope(EffectTiming.DEPLOYING_APPLICATION)
    .timeConf(TimeUnit.MILLISECONDS)
    .createWithDefaultString("2s")

  val PYTHON_USE_DAEMON = ConfigBuilder("spark.python.use.daemon")
    .version("2.3.0")
    .scope(EffectTiming.DEPLOYING_APPLICATION)
    .booleanConf
    .createWithDefault(true)

  val PYTHON_DAEMON_MODULE = ConfigBuilder("spark.python.daemon.module")
    .version("2.4.0")
    .scope(EffectTiming.DEPLOYING_APPLICATION)
    .stringConf
    .createOptional

  val PYTHON_WORKER_MODULE = ConfigBuilder("spark.python.worker.module")
    .version("2.4.0")
    .scope(EffectTiming.DEPLOYING_APPLICATION)
    .stringConf
    .createOptional

  val PYSPARK_EXECUTOR_MEMORY = ConfigBuilder("spark.executor.pyspark.memory")
    .version("2.4.0")
    .scope(EffectTiming.DEPLOYING_APPLICATION)
    .bytesConf(ByteUnit.MiB)
    .createOptional

  val PYTHON_AUTH_SOCKET_TIMEOUT = ConfigBuilder("spark.python.authenticate.socketTimeout")
    .internal()
    .version("3.1.0")
    .scope(EffectTiming.DEPLOYING_APPLICATION)
    .timeConf(TimeUnit.SECONDS)
    .createWithDefaultString("15s")

  val PYTHON_WORKER_FAULTHANLDER_ENABLED = ConfigBuilder("spark.python.worker.faulthandler.enabled")
    .doc("When true, Python workers set up the faulthandler for the case when the Python worker " +
      "exits unexpectedly (crashes), and shows the stack trace of the moment the Python worker " +
      "crashes in the error message if captured successfully.")
    .version("3.2.0")
    .booleanConf
    .createWithDefault(false)
}
