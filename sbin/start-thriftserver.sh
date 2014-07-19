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

#
# Shell script for starting the Spark SQL Thrift server

SCALA_VERSION=2.10

cygwin=false
case "`uname`" in
    CYGWIN*) cygwin=true;;
esac

# Enter posix mode for bash
set -o posix

## Global script variables
FWDIR="$(cd `dirname $0`/..; pwd)"

if [[ "$@" = *--help ]] || [[ "$@" = *-h ]]; then
  echo "Usage: ./sbin/start-thriftserver [options]"
  $FWDIR/bin/spark-submit --help 2>&1 | grep -v Usage 1>&2
  exit 0
fi

# Figure out where Spark is installed
FWDIR="$(cd `dirname $0`/..; pwd)"

ASSEMBLY_DIR="$FWDIR/assembly/target/scala-$SCALA_VERSION"

if [ -n "$JAVA_HOME" ]; then
  JAR_CMD="$JAVA_HOME/bin/jar"
else
  JAR_CMD="jar"
fi

# Use spark-assembly jar from either RELEASE or assembly directory
if [ -f "$FWDIR/RELEASE" ]; then
  assembly_folder="$FWDIR"/lib
else
  assembly_folder="$ASSEMBLY_DIR"
fi

num_jars=$(ls "$assembly_folder" | grep "spark-assembly.*hadoop.*\.jar" | wc -l)
if [ "$num_jars" -eq "0" ]; then
  echo "Failed to find Spark assembly in $assembly_folder"
  echo "You need to build Spark before running this program."
  exit 1
fi
if [ "$num_jars" -gt "1" ]; then
  jars_list=$(ls "$assembly_folder" | grep "spark-assembly.*hadoop.*.jar")
  echo "Found multiple Spark assembly jars in $assembly_folder:"
  echo "$jars_list"
  echo "Please remove all but one jar."
  exit 1
fi

ASSEMBLY_JAR=$(ls "$assembly_folder"/spark-assembly*hadoop*.jar 2>/dev/null)

# Verify that versions of java used to build the jars and run Spark are compatible
jar_error_check=$("$JAR_CMD" -tf "$ASSEMBLY_JAR" nonexistent/class/path 2>&1)
if [[ "$jar_error_check" =~ "invalid CEN header" ]]; then
  echo "Loading Spark jar with '$JAR_CMD' failed. " 1>&2
  echo "This is likely because Spark was compiled with Java 7 and run " 1>&2
  echo "with Java 6. (see SPARK-1703). Please use Java 7 to run Spark " 1>&2
  echo "or build Spark with Java 6." 1>&2
  exit 1
fi

CLASS="org.apache.spark.sql.hive.thriftserver.HiveThriftServer2"
exec "$FWDIR"/bin/spark-submit --class $CLASS $@ $ASSEMBLY_JAR
