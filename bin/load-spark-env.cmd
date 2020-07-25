@echo off

rem
rem Licensed to the Apache Software Foundation (ASF) under one or more
rem contributor license agreements.  See the NOTICE file distributed with
rem this work for additional information regarding copyright ownership.
rem The ASF licenses this file to You under the Apache License, Version 2.0
rem (the "License"); you may not use this file except in compliance with
rem the License.  You may obtain a copy of the License at
rem
rem    http://www.apache.org/licenses/LICENSE-2.0
rem
rem Unless required by applicable law or agreed to in writing, software
rem distributed under the License is distributed on an "AS IS" BASIS,
rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
rem See the License for the specific language governing permissions and
rem limitations under the License.
rem

rem This script loads spark-env.cmd if it exists, and ensures it is only loaded once.
rem spark-env.cmd is loaded from SPARK_CONF_DIR if set, or within the current directory's
rem conf\ subdirectory.

set SPARK_ENV_CMD=spark-env.cmd
if [%SPARK_ENV_LOADED%] == [] (
  set SPARK_ENV_LOADED=1

  if [%SPARK_CONF_DIR%] == [] (
    set SPARK_CONF_DIR=%~dp0..\conf
  )

  set SPARK_ENV_CMD=%SPARK_CONF_DIR%\%SPARK_ENV_CMD%
  if exist %SPARK_ENV_CMD% (
    call %SPARK_ENV_CMD%
  )
)

rem Setting SPARK_SCALA_VERSION if not already set.

if [%SPARK_SCALA_VERSION%] == [] (
  set SCALA_VERSION_1=2.13
  set SCALA_VERSION_2=2.12

  set ASSEMBLY_DIR1=%SPARK_HOME%\assembly\target\scala-%SCALA_VERSION_1%
  set ASSEMBLY_DIR2=%SPARK_HOME%\assembly\target\scala-%SCALA_VERSION_2%
  set ENV_VARIABLE_DOC=https://spark.apache.org/docs/latest/configuration.html#environment-variables
  if exist %ASSEMBLY_DIR2% if exist %ASSEMBLY_DIR1% (
    echo "Presence of build for multiple Scala versions detected (%ASSEMBLY_DIR1% and %ASSEMBLY_DIR2%)."
    echo "Remove one of them or, set SPARK_SCALA_VERSION=%SCALA_VERSION_1% in %SPARK_ENV_CMD%."
    echo "Visit %ENV_VARIABLE_DOC% for more details about setting environment variables in spark-env.cmd."
    echo "Either clean one of them or, set SPARK_SCALA_VERSION in spark-env.cmd."
    exit 1
  )
  if exist %ASSEMBLY_DIR1% (
    set SPARK_SCALA_VERSION=%SCALA_VERSION_1%
  ) else (
    set SPARK_SCALA_VERSION=%SCALA_VERSION_2%
  )
)
exit /b 0
