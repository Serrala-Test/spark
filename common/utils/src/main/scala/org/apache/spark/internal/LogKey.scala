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
  val ACTUAL_NUM_FILES = Value
  val ACTUAL_PARTITION_COLUMN = Value
  val ALPHA = Value
  val ANALYSIS_ERROR = Value
  val APP_ATTEMPT_ID = Value
  val APP_DESC = Value
  val APP_ID = Value
  val APP_NAME = Value
  val APP_STATE = Value
  val ARGS = Value
  val BACKUP_FILE = Value
  val BATCH_ID = Value
  val BATCH_TIMESTAMP = Value
  val BATCH_WRITE = Value
  val BLOCK_ID = Value
  val BLOCK_MANAGER_ID = Value
  val BROADCAST_ID = Value
  val BUCKET = Value
  val BYTECODE_SIZE = Value
  val CACHED_TABLE_PARTITION_METADATA_SIZE = Value
  val CACHE_AUTO_REMOVED_SIZE = Value
  val CACHE_UNTIL_HIGHEST_CONSUMED_SIZE = Value
  val CACHE_UNTIL_LAST_PRODUCED_SIZE = Value
  val CALL_SITE_LONG_FORM = Value
  val CATALOG_NAME = Value
  val CATEGORICAL_FEATURES = Value
  val CHECKPOINT_FILE = Value
  val CHECKPOINT_LOCATION = Value
  val CHECKPOINT_PATH = Value
  val CHECKPOINT_ROOT = Value
  val CHECKPOINT_TIME = Value
  val CHECKSUM_FILE_NUM = Value
  val CHOSEN_WATERMARK = Value
  val CLASS_LOADER = Value
  val CLASS_NAME = Value
  val CLUSTER_CENTROIDS = Value
  val CLUSTER_ID = Value
  val CLUSTER_LABEL = Value
  val CLUSTER_LEVEL = Value
  val CLUSTER_WEIGHT = Value
  val CODEC_LEVEL = Value
  val CODEC_NAME = Value
  val CODEGEN_STAGE_ID = Value
  val COLUMN_DATA_TYPE_SOURCE = Value
  val COLUMN_DATA_TYPE_TARGET = Value
  val COLUMN_DEFAULT_VALUE = Value
  val COLUMN_NAME = Value
  val COMMAND = Value
  val COMMAND_OUTPUT = Value
  val COMMITTED_VERSION = Value
  val COMPACT_INTERVAL = Value
  val COMPONENT = Value
  val CONFIG = Value
  val CONFIG2 = Value
  val CONFIG3 = Value
  val CONFIG4 = Value
  val CONFIG5 = Value
  val CONSUMER = Value
  val CONTAINER = Value
  val CONTAINER_ID = Value
  val CONTAINER_STATE = Value
  val COST = Value
  val COUNT = Value
  val CROSS_VALIDATION_METRIC = Value
  val CROSS_VALIDATION_METRICS = Value
  val CSV_HEADER_COLUMN_NAME = Value
  val CSV_HEADER_COLUMN_NAMES = Value
  val CSV_HEADER_LENGTH = Value
  val CSV_SCHEMA_FIELD_NAME = Value
  val CSV_SCHEMA_FIELD_NAMES = Value
  val CSV_SOURCE = Value
  val CURRENT_BATCH_ID = Value
  val CURRENT_PATH = Value
  val DATA = Value
  val DATABASE_NAME = Value
  val DATAFRAME_CACHE_ENTRY = Value
  val DATAFRAME_ID = Value
  val DATA_FILE_NUM = Value
  val DATA_SOURCE = Value
  val DATA_SOURCES = Value
  val DATA_SOURCE_PROVIDER = Value
  val DEFAULT_COMPACT_INTERVAL = Value
  val DEFAULT_ISOLATION_LEVEL = Value
  val DEFAULT_VALUE = Value
  val DELAY = Value
  val DELEGATE = Value
  val DELTA = Value
  val DESCRIPTION = Value
  val DESIRED_PARTITIONS_SIZE = Value
  val DFS_FILE = Value
  val DIFF_DELTA = Value
  val DIVISIBLE_CLUSTER_INDICES_SIZE = Value
  val DRIVER_ID = Value
  val DROPPED_PARTITIONS = Value
  val DURATION = Value
  val EFFECTIVE_STORAGE_LEVEL = Value
  val ELAPSED_TIME = Value
  val ENCODING = Value
  val END_INDEX = Value
  val END_POINT = Value
  val END_VERSION = Value
  val ENGINE = Value
  val EPOCH = Value
  val ERROR = Value
  val ESTIMATOR_PARAMETER_MAP = Value
  val EVENT_LOOP = Value
  val EVENT_QUEUE = Value
  val EXECUTE_INFO = Value
  val EXECUTE_KEY = Value
  val EXECUTION_PLAN_LEAVES = Value
  val EXECUTOR_DESIRED_COUNT = Value
  val EXECUTOR_ENVS = Value
  val EXECUTOR_ENV_REGEX = Value
  val EXECUTOR_ID = Value
  val EXECUTOR_IDS = Value
  val EXECUTOR_LAUNCH_COMMANDS = Value
  val EXECUTOR_LAUNCH_COUNT = Value
  val EXECUTOR_RESOURCES = Value
  val EXECUTOR_STATE = Value
  val EXECUTOR_TARGET_COUNT = Value
  val EXISTING_FILE = Value
  val EXIT_CODE = Value
  val EXPECTED_NUM_FILES = Value
  val EXPECTED_PARTITION_COLUMN = Value
  val EXPIRY_TIMESTAMP = Value
  val EXPR = Value
  val EXPR_TERMS = Value
  val EXTENDED_EXPLAIN_GENERATOR = Value
  val FAILURES = Value
  val FALLBACK_VERSION = Value
  val FEATURE_COLUMN = Value
  val FEATURE_DIMENSION = Value
  val FIELD_NAME = Value
  val FILE_ABSOLUTE_PATH = Value
  val FILE_END_OFFSET = Value
  val FILE_FORMAT = Value
  val FILE_FORMAT2 = Value
  val FILE_MODIFICATION_TIME = Value
  val FILE_NAME = Value
  val FILE_START_OFFSET = Value
  val FILE_VERSION = Value
  val FINAL_PATH = Value
  val FINISH_TRIGGER_DURATION = Value
  val FROM_OFFSET = Value
  val FROM_TIME = Value
  val FUNCTION_NAME = Value
  val FUNCTION_PARAMETER = Value
  val GLOBAL_WATERMARK = Value
  val GROUP_ID = Value
  val HADOOP_VERSION = Value
  val HASH_JOIN_KEYS = Value
  val HEARTBEAT_INTERVAL = Value
  val HISTORY_DIR = Value
  val HIVE_CLIENT_VERSION = Value
  val HIVE_METASTORE_VERSION = Value
  val HIVE_OPERATION_STATE = Value
  val HIVE_OPERATION_TYPE = Value
  val HOST = Value
  val HOST_PORT = Value
  val IDENTIFIER = Value
  val INCOMPATIBLE_TYPES = Value
  val INDEX = Value
  val INDEX_FILE_NUM = Value
  val INDEX_NAME = Value
  val INFERENCE_MODE = Value
  val INITIAL_CAPACITY = Value
  val INITIAL_HEARTBEAT_INTERVAL = Value
  val INIT_MODE = Value
  val INTERVAL = Value
  val ISOLATION_LEVEL = Value
  val JOB_ID = Value
  val JOIN_CONDITION = Value
  val JOIN_CONDITION_SUB_EXPR = Value
  val K8S_CONTEXT = Value
  val KAFKA_PULLS_COUNT = Value
  val KAFKA_RECORDS_PULLED_COUNT = Value
  val KEY = Value
  val KEYTAB = Value
  val LABEL_COLUMN = Value
  val LARGEST_CLUSTER_INDEX = Value
  val LAST_ACCESS_TIME = Value
  val LAST_VALID_TIME = Value
  val LATEST_BATCH_ID = Value
  val LATEST_COMMITTED_BATCH_ID = Value
  val LEARNING_RATE = Value
  val LEFT_EXPR = Value
  val LINE = Value
  val LINE_NUM = Value
  val LISTENER = Value
  val LOADED_VERSION = Value
  val LOAD_FACTOR = Value
  val LOAD_TIME = Value
  val LOGICAL_PLAN_COLUMNS = Value
  val LOGICAL_PLAN_LEAVES = Value
  val LOG_ID = Value
  val LOG_OFFSET = Value
  val LOG_TYPE = Value
  val LOWER_BOUND = Value
  val MALFORMATTED_STIRNG = Value
  val MASTER_URL = Value
  val MAX_ATTEMPTS = Value
  val MAX_CACHE_UNTIL_HIGHEST_CONSUMED_SIZE = Value
  val MAX_CACHE_UNTIL_LAST_PRODUCED_SIZE = Value
  val MAX_CAPACITY = Value
  val MAX_CATEGORIES = Value
  val MAX_EXECUTOR_FAILURES = Value
  val MAX_FILE_VERSION = Value
  val MAX_MEMORY_SIZE = Value
  val MAX_PARTITIONS_SIZE = Value
  val MAX_SIZE = Value
  val MAX_TABLE_PARTITION_METADATA_SIZE = Value
  val MEMORY_SIZE = Value
  val MERGE_DIR_NAME = Value
  val MESSAGE = Value
  val METADATA_DIRECTORY = Value
  val METADATA_JSON = Value
  val METHOD_NAME = Value
  val METRICS_JSON = Value
  val MIN_COMPACTION_BATCH_ID = Value
  val MIN_FREQUENT_PATTERN_COUNT = Value
  val MIN_POINT_PER_CLUSTER = Value
  val MIN_SIZE = Value
  val MIN_VERSION_NUMBER = Value
  val MODEL_WEIGHTS = Value
  val NAMESPACE = Value
  val NEW_FEATURE_COLUMN_NAME = Value
  val NEW_LABEL_COLUMN_NAME = Value
  val NEW_PATH = Value
  val NEW_VALUE = Value
  val NODES = Value
  val NODE_LOCATION = Value
  val NORM = Value
  val NUM_BIN = Value
  val NUM_BYTES = Value
  val NUM_CLASSES = Value
  val NUM_COLUMNS = Value
  val NUM_EXAMPLES = Value
  val NUM_FEATURES = Value
  val NUM_FILES = Value
  val NUM_FILES_COPIED = Value
  val NUM_FILES_FAILED_TO_DELETE = Value
  val NUM_FILES_REUSED = Value
  val NUM_FREQUENT_ITEMS = Value
  val NUM_ITERATIONS = Value
  val NUM_LOCAL_FREQUENT_PATTERN = Value
  val NUM_PARTITION = Value
  val NUM_POINT = Value
  val NUM_PREFIXES = Value
  val NUM_SEQUENCES = Value
  val OBJECT_ID = Value
  val OFFSET = Value
  val OFFSETS = Value
  val OFFSET_SEQUENCE_METADATA = Value
  val OLD_BLOCK_MANAGER_ID = Value
  val OLD_VALUE = Value
  val OPTIMIZED_PLAN_COLUMNS = Value
  val OPTIMIZER_CLASS_NAME = Value
  val OPTIONS = Value
  val OP_ID = Value
  val OP_TYPE = Value
  val OVERHEAD_MEMORY_SIZE = Value
  val PARSE_MODE = Value
  val PARTITIONED_FILE_READER = Value
  val PARTITIONS_SIZE = Value
  val PARTITION_ID = Value
  val PARTITION_SPECIFICATION = Value
  val PARTITION_SPECS = Value
  val PATH = Value
  val PATHS = Value
  val PIPELINE_STAGE_UID = Value
  val POD_COUNT = Value
  val POD_ID = Value
  val POD_NAME = Value
  val POD_NAMESPACE = Value
  val POD_PHASE = Value
  val POD_SHARED_SLOT_COUNT = Value
  val POD_STATE = Value
  val POD_TARGET_COUNT = Value
  val POLICY = Value
  val PORT = Value
  val PRETTY_ID_STRING = Value
  val PRINCIPAL = Value
  val PROCESSING_TIME = Value
  val PRODUCER_ID = Value
  val PROVIDER = Value
  val PVC_METADATA_NAME = Value
  val QUERY_CACHE_VALUE = Value
  val QUERY_HINT = Value
  val QUERY_ID = Value
  val QUERY_PLAN = Value
  val QUERY_PLAN_COMPARISON = Value
  val QUERY_PLAN_LENGTH_ACTUAL = Value
  val QUERY_PLAN_LENGTH_MAX = Value
  val QUERY_RUN_ID = Value
  val RANGE = Value
  val RDD_ID = Value
  val READ_LIMIT = Value
  val REASON = Value
  val REATTACHABLE = Value
  val RECEIVED_BLOCK_INFO = Value
  val RECEIVED_BLOCK_TRACKER_LOG_EVENT = Value
  val RECEIVER_ID = Value
  val RECEIVER_IDS = Value
  val RECORDS = Value
  val REDACTED_STATEMENT = Value
  val REDUCE_ID = Value
  val RELATION_NAME = Value
  val RELATIVE_TOLERANCE = Value
  val REMAINING_PARTITIONS = Value
  val REPORT_DETAILS = Value
  val RESOURCE = Value
  val RESOURCE_NAME = Value
  val RESOURCE_PROFILE_ID = Value
  val RESOURCE_PROFILE_IDS = Value
  val RETRY_COUNT = Value
  val RETRY_INTERVAL = Value
  val RIGHT_EXPR = Value
  val RMSE = Value
  val ROCKS_DB_LOG_LEVEL = Value
  val ROCKS_DB_LOG_MESSAGE = Value
  val RPC_ENDPOINT_REF = Value
  val RULE_BATCH_NAME = Value
  val RULE_NAME = Value
  val RULE_NUMBER_OF_RUNS = Value
  val RUN_ID = Value
  val SCHEMA = Value
  val SCHEMA2 = Value
  val SERVICE_NAME = Value
  val SESSION_HOLD_INFO = Value
  val SESSION_ID = Value
  val SESSION_KEY = Value
  val SHARD_ID = Value
  val SHUFFLE_BLOCK_INFO = Value
  val SHUFFLE_ID = Value
  val SHUFFLE_MERGE_ID = Value
  val SHUFFLE_SERVICE_NAME = Value
  val SIZE = Value
  val SLEEP_TIME = Value
  val SLIDE_DURATION = Value
  val SMALLEST_CLUSTER_INDEX = Value
  val SNAPSHOT_VERSION = Value
  val SPARK_DATA_STREAM = Value
  val SPARK_PLAN_ID = Value
  val SQL_TEXT = Value
  val SRC_PATH = Value
  val STAGE_ATTEMPT = Value
  val STAGE_ID = Value
  val START_INDEX = Value
  val STATEMENT_ID = Value
  val STATE_STORE_ID = Value
  val STATE_STORE_PROVIDER = Value
  val STATE_STORE_VERSION = Value
  val STATUS = Value
  val STDERR = Value
  val STORAGE_LEVEL = Value
  val STORAGE_LEVEL_DESERIALIZED = Value
  val STORAGE_LEVEL_REPLICATION = Value
  val STORE_ID = Value
  val STREAMING_DATA_SOURCE_DESCRIPTION = Value
  val STREAMING_DATA_SOURCE_NAME = Value
  val STREAMING_OFFSETS_END = Value
  val STREAMING_OFFSETS_START = Value
  val STREAMING_QUERY_PROGRESS = Value
  val STREAMING_SOURCE = Value
  val STREAMING_TABLE = Value
  val STREAMING_WRITE = Value
  val STREAM_ID = Value
  val STREAM_NAME = Value
  val SUBMISSION_ID = Value
  val SUBSAMPLING_RATE = Value
  val SUB_QUERY = Value
  val TABLE_NAME = Value
  val TABLE_TYPES = Value
  val TARGET_PATH = Value
  val TASK_ATTEMPT_ID = Value
  val TASK_ID = Value
  val TASK_NAME = Value
  val TASK_SET_NAME = Value
  val TASK_STATE = Value
  val TEMP_FILE = Value
  val TEMP_PATH = Value
  val TEST_SIZE = Value
  val THREAD = Value
  val THREAD_NAME = Value
  val TID = Value
  val TIME = Value
  val TIMEOUT = Value
  val TIMER = Value
  val TIMER_LABEL = Value
  val TIME_UNITS = Value
  val TIP = Value
  val TOKEN_REGEX = Value
  val TOPIC = Value
  val TOPIC_PARTITION = Value
  val TOPIC_PARTITIONS = Value
  val TOPIC_PARTITION_OFFSET = Value
  val TOPIC_PARTITION_OFFSET_RANGE = Value
  val TOTAL = Value
  val TOTAL_EFFECTIVE_TIME = Value
  val TOTAL_RECORDS_READ = Value
  val TOTAL_TIME = Value
  val TOTAL_TIME_READ = Value
  val TO_TIME = Value
  val TRAINING_SIZE = Value
  val TRAIN_VALIDATION_SPLIT_METRIC = Value
  val TRAIN_VALIDATION_SPLIT_METRICS = Value
  val TRAIN_WORD_COUNT = Value
  val TREE_NODE = Value
  val TRIGGER_INTERVAL = Value
  val UI_FILTER = Value
  val UI_FILTER_PARAMS = Value
  val UI_PROXY_BASE = Value
  val UNSUPPORTED_EXPR = Value
  val UNSUPPORTED_HINT_REASON = Value
  val UNTIL_OFFSET = Value
  val UPPER_BOUND = Value
  val URI = Value
  val USER_ID = Value
  val USER_NAME = Value
  val VALUE = Value
  val VERSION_NUMBER = Value
  val VIRTUAL_CORES = Value
  val VOCAB_SIZE = Value
  val WAIT_RESULT_TIME = Value
  val WAIT_SEND_TIME = Value
  val WAIT_TIME = Value
  val WATERMARK_CONSTRAINT = Value
  val WEIGHTED_NUM = Value
  val WORKER_URL = Value
  val WRITE_AHEAD_LOG_INFO = Value
  val WRITE_JOB_UUID = Value
  val XSD_PATH = Value

  type LogKey = Value
}
