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
package org.apache.spark.internal

/**
 * Various keys used for mapped diagnostic contexts(MDC) in logging.
 * All structured logging keys should be defined here for standardization.
 */
object LogKey extends Enumeration {
  val ACCUMULATOR_ID = Value
  val ANALYSIS_ERROR = Value
  val APP_DESC = Value
  val APP_ID = Value
  val APP_STATE = Value
  val BLOCK_ID = Value
  val BLOCK_MANAGER_ID = Value
  val BROADCAST_ID = Value
  val BUCKET = Value
  val BYTECODE_SIZE = Value
  val CATEGORICAL_FEATURES = Value
  val CLASS_LOADER = Value
  val CLASS_NAME = Value
  val COLUMN_DATA_TYPE_SOURCE = Value
  val COLUMN_DATA_TYPE_TARGET = Value
  val COLUMN_DEFAULT_VALUE = Value
  val COLUMN_NAME = Value
  val COMMAND = Value
  val COMMAND_OUTPUT = Value
  val COMPONENT = Value
  val CONFIG = Value
  val CONFIG2 = Value
  val CONTAINER_ID = Value
  val COUNT = Value
  val CSV_HEADER_COLUMN_NAME = Value
  val CSV_HEADER_COLUMN_NAMES = Value
  val CSV_HEADER_LENGTH = Value
  val CSV_SCHEMA_FIELD_NAME = Value
  val CSV_SCHEMA_FIELD_NAMES = Value
  val CSV_SOURCE = Value
  val DRIVER_ID = Value
  val END_POINT = Value
  val ERROR = Value
  val EVENT_LOOP = Value
  val EVENT_QUEUE = Value
  val EXECUTOR_ID = Value
  val EXECUTOR_STATE = Value
  val EXIT_CODE = Value
  val EXPRESSION_TERMS = Value
  val FAILURES = Value
  val FUNCTION_NAME = Value
  val FUNCTION_PARAMETER = Value
  val GROUP_ID = Value
  val HIVE_OPERATION_STATE = Value
  val HIVE_OPERATION_TYPE = Value
  val HOST = Value
  val JOB_ID = Value
  val JOIN_CONDITION = Value
  val JOIN_CONDITION_SUB_EXPRESSION = Value
  val LEARNING_RATE = Value
  val LINE = Value
  val LINE_NUM = Value
  val LISTENER = Value
  val LOG_TYPE = Value
  val MASTER_URL = Value
  val MAX_ATTEMPTS = Value
  val MAX_CATEGORIES = Value
  val MAX_EXECUTOR_FAILURES = Value
  val MAX_SIZE = Value
  val MERGE_DIR_NAME = Value
  val METHOD_NAME = Value
  val MIN_SIZE = Value
  val NUM_COLUMNS = Value
  val NUM_ITERATIONS = Value
  val OBJECT_ID = Value
  val OLD_BLOCK_MANAGER_ID = Value
  val OPTIMIZER_CLASS_NAME = Value
  val OP_TYPE = Value
  val PARSE_MODE = Value
  val PARTITION_ID = Value
  val PARTITION_SPECIFICATION = Value
  val PATH = Value
  val PATHS = Value
  val POD_ID = Value
  val PORT = Value
  val QUERY_HINT = Value
  val QUERY_PLAN = Value
  val QUERY_PLAN_LENGTH_ACTUAL = Value
  val QUERY_PLAN_LENGTH_MAX = Value
  val RANGE = Value
  val RDD_ID = Value
  val REASON = Value
  val REDUCE_ID = Value
  val RELATION_NAME = Value
  val REMOTE_ADDRESS = Value
  val RETRY_COUNT = Value
  val RETRY_INTERVAL = Value
  val RPC_ADDRESS = Value
  val RULE_BATCH_NAME = Value
  val RULE_NAME = Value
  val RULE_NUMBER_OF_RUNS = Value
  val SESSION_ID = Value
  val SHARD_ID = Value
  val SHUFFLE_BLOCK_INFO = Value
  val SHUFFLE_ID = Value
  val SHUFFLE_MERGE_ID = Value
  val SIZE = Value
  val SLEEP_TIME = Value
  val SQL_TEXT = Value
  val STAGE_ID = Value
  val STATEMENT_ID = Value
  val SUBMISSION_ID = Value
  val SUBSAMPLING_RATE = Value
  val TASK_ATTEMPT_ID = Value
  val TASK_ID = Value
  val TASK_NAME = Value
  val TASK_SET_NAME = Value
  val TASK_STATE = Value
  val THREAD = Value
  val THREAD_NAME = Value
  val TID = Value
  val TIMEOUT = Value
  val TIME_UNITS = Value
  val TOTAL_EFFECTIVE_TIME = Value
  val TOTAL_TIME = Value
  val UNSUPPORTED_EXPRESSION = Value
  val UNSUPPORTED_HINT_REASON = Value
  val URI = Value
  val USER_ID = Value
  val USER_NAME = Value
  val WATERMARK_CONSTRAINT = Value
  val WORKER_URL = Value
  val XSD_PATH = Value

  type LogKey = Value
}
