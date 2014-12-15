#!/usr/bin/env bash

#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Usage: start-slave.sh <worker#> <master-spark-URL>
#   where <master-spark-URL> is like "spark://localhost:7077"

sbin="`dirname "$0"`"
sbin="`cd "$sbin"; pwd`"

. "$sbin/spark-config.sh"

. "$SPARK_PREFIX/bin/load-spark-env.sh"


# First argument should be the master; we need to store it aside because we may
# need to insert arguments between it and the other arguments
MASTER=$1
shift


# Determine desired worker port

# Start up the appropriate number of workers on this machine.
if [ "$SPARK_WORKER_WEBUI_PORT" = "" ]; then
  SPARK_WORKER_WEBUI_PORT=8081
fi

if [ "$SPARK_WORKER_INSTANCES" = "" ]; then
  if [ "$SPARK_WORKER_PORT" = "" ]; then
    PORT_FLAG=
    PORT_NUM=
  else
    PORT_FLAG="--port"
    PORT_NUM="$SPARK_WORKER_PORT"
  fi

  "$sbin"/spark-daemon.sh start org.apache.spark.deploy.worker.Worker 1 $MASTER --webui-port "$SPARK_WORKER_WEBUI_PORT" "$PORT_FLAG" "$PORT_NUM" "$@"
else
  for ((i=0; i<$SPARK_WORKER_INSTANCES; i++)); do
    if [ "$SPARK_WORKER_PORT" = "" ]; then
      PORT_FLAG=
      PORT_NUM=
    else
      PORT_FLAG="--port"
      PORT_NUM=$(( $SPARK_WORKER_PORT + $i ))
    fi

    "$sbin"/spark-daemon.sh start org.apache.spark.deploy.worker.Worker $(( 1 + $i )) $MASTER --webui-port $(( $SPARK_WORKER_WEBUI_PORT + $i )) "$PORT_FLAG" "$PORT_NUM" "$@"
  done
fi
