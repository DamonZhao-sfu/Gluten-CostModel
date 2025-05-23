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
package org.apache.gluten

import org.apache.spark.internal.Logging
import org.apache.spark.network.util.ByteUnit
import org.apache.spark.sql.internal.SQLConf

import com.google.common.collect.ImmutableList
import org.apache.hadoop.security.UserGroupInformation

import java.util
import java.util.Locale
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._

case class GlutenNumaBindingInfo(
    enableNumaBinding: Boolean,
    totalCoreRange: Array[String] = null,
    numCoresPerExecutor: Int = -1) {}

class GlutenConfig(conf: SQLConf) extends Logging {
  import GlutenConfig._

  def enableAnsiMode: Boolean = conf.ansiEnabled

  def enableGluten: Boolean = conf.getConf(GLUTEN_ENABLED)

  // FIXME the option currently controls both JVM and native validation against a Substrait plan.
  def enableNativeValidation: Boolean = conf.getConf(NATIVE_VALIDATION_ENABLED)

  def enableColumnarBatchScan: Boolean = conf.getConf(COLUMNAR_BATCHSCAN_ENABLED)

  def enableColumnarFileScan: Boolean = conf.getConf(COLUMNAR_FILESCAN_ENABLED)

  def enableColumnarHiveTableScan: Boolean = conf.getConf(COLUMNAR_HIVETABLESCAN_ENABLED)

  def enableVanillaVectorizedReaders: Boolean = conf.getConf(VANILLA_VECTORIZED_READERS_ENABLED)

  def enableColumnarHashAgg: Boolean = conf.getConf(COLUMNAR_HASHAGG_ENABLED)

  def forceToUseHashAgg: Boolean = conf.getConf(COLUMNAR_FORCE_HASHAGG_ENABLED)

  def enableColumnarProject: Boolean = conf.getConf(COLUMNAR_PROJECT_ENABLED)

  def enableColumnarFilter: Boolean = conf.getConf(COLUMNAR_FILTER_ENABLED)

  def enableColumnarSort: Boolean = conf.getConf(COLUMNAR_SORT_ENABLED)

  def enableColumnarWindow: Boolean = conf.getConf(COLUMNAR_WINDOW_ENABLED)

  def enableColumnarWindowGroupLimit: Boolean = conf.getConf(COLUMNAR_WINDOW_GROUP_LIMIT_ENABLED)

  def veloxColumnarWindowType: String = conf.getConfString(COLUMNAR_VELOX_WINDOW_TYPE.key)

  def enableColumnarShuffledHashJoin: Boolean = conf.getConf(COLUMNAR_SHUFFLED_HASH_JOIN_ENABLED)

  def enableNativeColumnarToRow: Boolean = conf.getConf(COLUMNAR_COLUMNAR_TO_ROW_ENABLED)

  def forceShuffledHashJoin: Boolean = conf.getConf(COLUMNAR_FPRCE_SHUFFLED_HASH_JOIN_ENABLED)

  def enableColumnarSortMergeJoin: Boolean = conf.getConf(COLUMNAR_SORTMERGEJOIN_ENABLED)

  def enableColumnarUnion: Boolean = conf.getConf(COLUMNAR_UNION_ENABLED)

  def enableColumnarExpand: Boolean = conf.getConf(COLUMNAR_EXPAND_ENABLED)

  def enableColumnarBroadcastExchange: Boolean = conf.getConf(COLUMNAR_BROADCAST_EXCHANGE_ENABLED)

  def enableColumnarBroadcastJoin: Boolean = conf.getConf(COLUMNAR_BROADCAST_JOIN_ENABLED)

  def enableColumnarSample: Boolean = conf.getConf(COLUMNAR_SAMPLE_ENABLED)

  def enableColumnarArrowUDF: Boolean = conf.getConf(COLUMNAR_ARROW_UDF_ENABLED)

  def enableColumnarCoalesce: Boolean = conf.getConf(COLUMNAR_COALESCE_ENABLED)

  def columnarTableCacheEnabled: Boolean = conf.getConf(COLUMNAR_TABLE_CACHE_ENABLED)

  def enableRewriteDateTimestampComparison: Boolean =
    conf.getConf(ENABLE_REWRITE_DATE_TIMESTAMP_COMPARISON)

  def enableCHRewriteDateConversion: Boolean =
    conf.getConf(ENABLE_CH_REWRITE_DATE_CONVERSION)

  def enableCommonSubexpressionEliminate: Boolean =
    conf.getConf(ENABLE_COMMON_SUBEXPRESSION_ELIMINATE)

  def enableCountDistinctWithoutExpand: Boolean =
    conf.getConf(ENABLE_COUNT_DISTINCT_WITHOUT_EXPAND)

  def veloxOrcScanEnabled: Boolean =
    conf.getConf(VELOX_ORC_SCAN_ENABLED)

  def forceComplexTypeScanFallbackEnabled: Boolean =
    conf.getConf(VELOX_FORCE_COMPLEX_TYPE_SCAN_FALLBACK)

  def forceOrcCharTypeScanFallbackEnabled: Boolean =
    conf.getConf(VELOX_FORCE_ORC_CHAR_TYPE_SCAN_FALLBACK)

  def forceParquetTimestampTypeScanFallbackEnabled: Boolean =
    conf.getConf(VELOX_FORCE_PARQUET_TIMESTAMP_TYPE_SCAN_FALLBACK)

  def scanFileSchemeValidationEnabled: Boolean =
    conf.getConf(VELOX_SCAN_FILE_SCHEME_VALIDATION_ENABLED)

  // whether to use ColumnarShuffleManager
  def isUseColumnarShuffleManager: Boolean =
    conf
      .getConfString("spark.shuffle.manager", "sort")
      .equals("org.apache.spark.shuffle.sort.ColumnarShuffleManager")

  // whether to use CelebornShuffleManager
  def isUseCelebornShuffleManager: Boolean =
    conf
      .getConfString("spark.shuffle.manager", "sort")
      .contains("celeborn")

  def isUseUniffleShuffleManager: Boolean =
    conf
      .getConfString("spark.shuffle.manager", "sort")
      .contains("UniffleShuffleManager")

  def celebornShuffleWriterType: String =
    conf
      .getConfString("spark.celeborn.client.spark.shuffle.writer", GLUTEN_HASH_SHUFFLE_WRITER)
      .toLowerCase(Locale.ROOT)

  def enableColumnarShuffle: Boolean = conf.getConf(COLUMNAR_SHUFFLE_ENABLED)

  def enablePreferColumnar: Boolean = conf.getConf(COLUMNAR_PREFER_ENABLED)

  def enableOneRowRelationColumnar: Boolean = conf.getConf(COLUMNAR_ONE_ROW_RELATION_ENABLED)

  def physicalJoinOptimizationThrottle: Integer =
    conf.getConf(COLUMNAR_PHYSICAL_JOIN_OPTIMIZATION_THROTTLE)

  def enablePhysicalJoinOptimize: Boolean =
    conf.getConf(COLUMNAR_PHYSICAL_JOIN_OPTIMIZATION_ENABLED)

  def logicalJoinOptimizationThrottle: Integer =
    conf.getConf(COLUMNAR_LOGICAL_JOIN_OPTIMIZATION_THROTTLE)

  def enableScanOnly: Boolean = conf.getConf(COLUMNAR_SCAN_ONLY_ENABLED)

  def tmpFile: Option[String] = conf.getConf(COLUMNAR_TEMP_DIR)

  @deprecated def broadcastCacheTimeout: Int = conf.getConf(COLUMNAR_BROADCAST_CACHE_TIMEOUT)

  def columnarShuffleSortPartitionsThreshold: Int =
    conf.getConf(COLUMNAR_SHUFFLE_SORT_PARTITIONS_THRESHOLD)

  def columnarShuffleSortColumnsThreshold: Int =
    conf.getConf(COLUMNAR_SHUFFLE_SORT_COLUMNS_THRESHOLD)

  def columnarShuffleReallocThreshold: Double = conf.getConf(COLUMNAR_SHUFFLE_REALLOC_THRESHOLD)

  def columnarShuffleMergeThreshold: Double = conf.getConf(SHUFFLE_WRITER_MERGE_THRESHOLD)

  def columnarShuffleCodec: Option[String] = conf.getConf(COLUMNAR_SHUFFLE_CODEC)

  def columnarShuffleCompressionMode: String =
    conf.getConf(COLUMNAR_SHUFFLE_COMPRESSION_MODE)

  def columnarShuffleCodecBackend: Option[String] = conf
    .getConf(COLUMNAR_SHUFFLE_CODEC_BACKEND)
    .filter(Set(GLUTEN_QAT_BACKEND_NAME, GLUTEN_IAA_BACKEND_NAME).contains(_))

  def columnarShuffleEnableQat: Boolean =
    columnarShuffleCodecBackend.contains(GlutenConfig.GLUTEN_QAT_BACKEND_NAME)

  def columnarShuffleEnableIaa: Boolean =
    columnarShuffleCodecBackend.contains(GlutenConfig.GLUTEN_IAA_BACKEND_NAME)

  def columnarShuffleCompressionThreshold: Int =
    conf.getConf(COLUMNAR_SHUFFLE_COMPRESSION_THRESHOLD)

  def maxBatchSize: Int = conf.getConf(COLUMNAR_MAX_BATCH_SIZE)

  def columnarToRowMemThreshold: Long =
    conf.getConf(GLUTEN_COLUMNAR_TO_ROW_MEM_THRESHOLD)

  def shuffleWriterBufferSize: Int = conf
    .getConf(SHUFFLE_WRITER_BUFFER_SIZE)
    .getOrElse(maxBatchSize)

  def enableColumnarLimit: Boolean = conf.getConf(COLUMNAR_LIMIT_ENABLED)

  def enableColumnarGenerate: Boolean = conf.getConf(COLUMNAR_GENERATE_ENABLED)

  def enableTakeOrderedAndProject: Boolean =
    conf.getConf(COLUMNAR_TAKE_ORDERED_AND_PROJECT_ENABLED)

  def enableNativeBloomFilter: Boolean = conf.getConf(COLUMNAR_NATIVE_BLOOMFILTER_ENABLED)

  def enableNativeHyperLogLogAggregateFunction: Boolean =
    conf.getConf(COLUMNAR_NATIVE_HYPERLOGLOG_AGGREGATE_ENABLED)

  def columnarParquetWriteBlockSize: Long =
    conf.getConf(COLUMNAR_PARQUET_WRITE_BLOCK_SIZE)

  def columnarParquetWriteBlockRows: Long =
    conf.getConf(COLUMNAR_PARQUET_WRITE_BLOCK_ROWS)

  def wholeStageFallbackThreshold: Int = conf.getConf(COLUMNAR_WHOLESTAGE_FALLBACK_THRESHOLD)

  def queryFallbackThreshold: Int = conf.getConf(COLUMNAR_QUERY_FALLBACK_THRESHOLD)

  def fallbackIgnoreRowToColumnar: Boolean = conf.getConf(COLUMNAR_FALLBACK_IGNORE_ROW_TO_COLUMNAR)

  def fallbackExpressionsThreshold: Int = conf.getConf(COLUMNAR_FALLBACK_EXPRESSIONS_THRESHOLD)

  def fallbackPreferColumnar: Boolean = conf.getConf(COLUMNAR_FALLBACK_PREFER_COLUMNAR)

  def numaBindingInfo: GlutenNumaBindingInfo = {
    val enableNumaBinding: Boolean = conf.getConf(COLUMNAR_NUMA_BINDING_ENABLED)
    if (!enableNumaBinding) {
      GlutenNumaBindingInfo(enableNumaBinding = false)
    } else {
      val tmp = conf.getConf(COLUMNAR_NUMA_BINDING_CORE_RANGE)
      if (tmp.isEmpty) {
        GlutenNumaBindingInfo(enableNumaBinding = false)
      } else {
        val numCores = conf.getConfString("spark.executor.cores", "1").toInt
        val coreRangeList: Array[String] = tmp.get.split('|').map(_.trim)
        GlutenNumaBindingInfo(enableNumaBinding = true, coreRangeList, numCores)
      }

    }
  }

  def memoryIsolation: Boolean = conf.getConf(COLUMNAR_MEMORY_ISOLATION)

  def memoryBacktraceAllocation: Boolean = conf.getConf(COLUMNAR_MEMORY_BACKTRACE_ALLOCATION)

  def numTaskSlotsPerExecutor: Int = {
    val numSlots = conf.getConf(NUM_TASK_SLOTS_PER_EXECUTOR)
    assert(numSlots > 0, s"Number of task slot not found. This should not happen.")
    numSlots
  }

  def offHeapMemorySize: Long = conf.getConf(COLUMNAR_OFFHEAP_SIZE_IN_BYTES)

  def taskOffHeapMemorySize: Long = conf.getConf(COLUMNAR_TASK_OFFHEAP_SIZE_IN_BYTES)

  def memoryOverAcquiredRatio: Double = conf.getConf(COLUMNAR_MEMORY_OVER_ACQUIRED_RATIO)

  def memoryReservationBlockSize: Long = conf.getConf(COLUMNAR_MEMORY_RESERVATION_BLOCK_SIZE)

  def conservativeTaskOffHeapMemorySize: Long =
    conf.getConf(COLUMNAR_CONSERVATIVE_TASK_OFFHEAP_SIZE_IN_BYTES)

  // Options used by RAS.
  def enableRas: Boolean = conf.getConf(RAS_ENABLED)

  def rasCostModel: String = conf.getConf(RAS_COST_MODEL)

  def enableVeloxCache: Boolean = conf.getConf(COLUMNAR_VELOX_CACHE_ENABLED)

  def veloxMemCacheSize: Long = conf.getConf(COLUMNAR_VELOX_MEM_CACHE_SIZE)

  def veloxSsdCachePath: String = conf.getConf(COLUMNAR_VELOX_SSD_CACHE_PATH)

  def veloxSsdCacheSize: Long = conf.getConf(COLUMNAR_VELOX_SSD_CACHE_SIZE)

  def veloxSsdCacheShards: Integer = conf.getConf(COLUMNAR_VELOX_SSD_CACHE_SHARDS)

  def veloxSsdCacheIOThreads: Integer = conf.getConf(COLUMNAR_VELOX_SSD_CACHE_IO_THREADS)

  def veloxSsdODirectEnabled: Boolean = conf.getConf(COLUMNAR_VELOX_SSD_ODIRECT_ENABLED)

  def veloxConnectorIOThreads: Int = {
    conf.getConf(COLUMNAR_VELOX_CONNECTOR_IO_THREADS).getOrElse(numTaskSlotsPerExecutor)
  }

  def veloxSplitPreloadPerDriver: Integer = conf.getConf(COLUMNAR_VELOX_SPLIT_PRELOAD_PER_DRIVER)

  def veloxSpillStrategy: String = conf.getConf(COLUMNAR_VELOX_SPILL_STRATEGY)

  def veloxMaxSpillLevel: Int = conf.getConf(COLUMNAR_VELOX_MAX_SPILL_LEVEL)

  def veloxMaxSpillFileSize: Long = conf.getConf(COLUMNAR_VELOX_MAX_SPILL_FILE_SIZE)

  def veloxSpillFileSystem: String = conf.getConf(COLUMNAR_VELOX_SPILL_FILE_SYSTEM)

  def veloxMaxSpillRunRows: Long = conf.getConf(COLUMNAR_VELOX_MAX_SPILL_RUN_ROWS)

  def veloxMaxSpillBytes: Long = conf.getConf(COLUMNAR_VELOX_MAX_SPILL_BYTES)

  def veloxMaxWriteBufferSize: Long = conf.getConf(COLUMNAR_VELOX_MAX_SPILL_WRITE_BUFFER_SIZE)

  def veloxBloomFilterExpectedNumItems: Long =
    conf.getConf(COLUMNAR_VELOX_BLOOM_FILTER_EXPECTED_NUM_ITEMS)

  def veloxBloomFilterNumBits: Long = conf.getConf(COLUMNAR_VELOX_BLOOM_FILTER_NUM_BITS)

  def veloxBloomFilterMaxNumBits: Long = conf.getConf(COLUMNAR_VELOX_BLOOM_FILTER_MAX_NUM_BITS)

  case class ResizeRange(min: Int, max: Int) {
    assert(max >= min)
    assert(min > 0, "Min batch size should be larger than 0")
    assert(max > 0, "Max batch size should be larger than 0")
  }

  private object ResizeRange {
    def parse(pattern: String): ResizeRange = {
      assert(pattern.count(_ == '~') == 1, s"Invalid range pattern for batch resizing: $pattern")
      val splits = pattern.split('~')
      assert(splits.length == 2)
      ResizeRange(splits(0).toInt, splits(1).toInt)
    }
  }

  def veloxResizeBatchesShuffleInput: Boolean =
    conf.getConf(COLUMNAR_VELOX_RESIZE_BATCHES_SHUFFLE_INPUT)

  def veloxResizeBatchesShuffleInputRange: ResizeRange = {
    val standardSize = conf.getConf(COLUMNAR_MAX_BATCH_SIZE)
    val defaultMinSize: Int = (0.25 * standardSize).toInt.max(1)
    val minSize = conf
      .getConf(COLUMNAR_VELOX_RESIZE_BATCHES_SHUFFLE_INPUT_MIN_SIZE)
      .getOrElse(defaultMinSize)
    ResizeRange(minSize, Int.MaxValue)
  }

  def chColumnarShuffleSpillThreshold: Long = {
    val threshold = conf.getConf(COLUMNAR_CH_SHUFFLE_SPILL_THRESHOLD)
    if (threshold == 0) {
      (conf.getConf(COLUMNAR_TASK_OFFHEAP_SIZE_IN_BYTES) * 0.9).toLong
    } else {
      threshold
    }
  }

  def chColumnarMaxSortBufferSize: Long = conf.getConf(COLUMNAR_CH_MAX_SORT_BUFFER_SIZE)

  def chColumnarForceMemorySortShuffle: Boolean =
    conf.getConf(COLUMNAR_CH_FORCE_MEMORY_SORT_SHUFFLE)

  def cartesianProductTransformerEnabled: Boolean =
    conf.getConf(CARTESIAN_PRODUCT_TRANSFORMER_ENABLED)

  def broadcastNestedLoopJoinTransformerTransformerEnabled: Boolean =
    conf.getConf(BROADCAST_NESTED_LOOP_JOIN_TRANSFORMER_ENABLED)

  def transformPlanLogLevel: String = conf.getConf(TRANSFORM_PLAN_LOG_LEVEL)

  def substraitPlanLogLevel: String = conf.getConf(SUBSTRAIT_PLAN_LOG_LEVEL)

  def validationLogLevel: String = conf.getConf(VALIDATION_LOG_LEVEL)

  def softAffinityLogLevel: String = conf.getConf(SOFT_AFFINITY_LOG_LEVEL)

  // A comma-separated list of classes for the extended columnar pre rules
  def extendedColumnarTransformRules: String = conf.getConf(EXTENDED_COLUMNAR_TRANSFORM_RULES)

  // A comma-separated list of classes for the extended columnar post rules
  def extendedColumnarPostRules: String = conf.getConf(EXTENDED_COLUMNAR_POST_RULES)

  def extendedExpressionTransformer: String = conf.getConf(EXTENDED_EXPRESSION_TRAN_CONF)

  def expressionBlacklist: Set[String] = {
    val blacklist = conf.getConf(EXPRESSION_BLACK_LIST)
    val blacklistSet: Set[String] = if (blacklist.isDefined) {
      blacklist.get.toLowerCase(Locale.ROOT).trim.split(",").toSet
    } else {
      Set.empty
    }

    if (conf.getConf(FALLBACK_REGEXP_EXPRESSIONS)) {
      val regexpList = "rlike,regexp_replace,regexp_extract,regexp_extract_all,split"
      regexpList.trim.split(",").toSet ++ blacklistSet
    } else {
      blacklistSet
    }
  }

  def printStackOnValidationFailure: Boolean =
    conf.getConf(VALIDATION_PRINT_FAILURE_STACK_)

  def enableFallbackReport: Boolean = conf.getConf(FALLBACK_REPORTER_ENABLED)

  def enableVeloxUserExceptionStacktrace: Boolean =
    conf.getConf(COLUMNAR_VELOX_ENABLE_USER_EXCEPTION_STACKTRACE)

  def memoryUseHugePages: Boolean =
    conf.getConf(COLUMNAR_VELOX_MEMORY_USE_HUGE_PAGES)

  def debug: Boolean = conf.getConf(DEBUG_ENABLED)
  def debugKeepJniWorkspace: Boolean = conf.getConf(DEBUG_KEEP_JNI_WORKSPACE)
  def taskStageId: Int = conf.getConf(BENCHMARK_TASK_STAGEID)
  def taskPartitionId: Int = conf.getConf(BENCHMARK_TASK_PARTITIONID)
  def taskId: Long = conf.getConf(BENCHMARK_TASK_TASK_ID)
  def textInputMaxBlockSize: Long = conf.getConf(TEXT_INPUT_ROW_MAX_BLOCK_SIZE)
  def textIputEmptyAsDefault: Boolean = conf.getConf(TEXT_INPUT_EMPTY_AS_DEFAULT)
  def enableParquetRowGroupMaxMinIndex: Boolean =
    conf.getConf(ENABLE_PARQUET_ROW_GROUP_MAX_MIN_INDEX)

  def enableVeloxFlushablePartialAggregation: Boolean =
    conf.getConf(VELOX_FLUSHABLE_PARTIAL_AGGREGATION_ENABLED)
  def maxFlushableAggregationMemoryRatio: Double =
    conf.getConf(MAX_PARTIAL_AGGREGATION_MEMORY_RATIO)
  def maxExtendedFlushableAggregationMemoryRatio: Double =
    conf.getConf(MAX_PARTIAL_AGGREGATION_MEMORY_RATIO)
  def abandonFlushableAggregationMinPct: Int =
    conf.getConf(ABANDON_PARTIAL_AGGREGATION_MIN_PCT)
  def abandonFlushableAggregationMinRows: Int =
    conf.getConf(ABANDON_PARTIAL_AGGREGATION_MIN_ROWS)

  // Please use `BackendsApiManager.getSettings.enableNativeWriteFiles()` instead
  def enableNativeWriter: Option[Boolean] = conf.getConf(NATIVE_WRITER_ENABLED)

  def enableNativeArrowReader: Boolean = conf.getConf(NATIVE_ARROW_READER_ENABLED)

  def directorySizeGuess: Long =
    conf.getConf(DIRECTORY_SIZE_GUESS)
  def filePreloadThreshold: Long =
    conf.getConf(FILE_PRELOAD_THRESHOLD)
  def prefetchRowGroups: Int =
    conf.getConf(PREFETCH_ROW_GROUPS)
  def loadQuantum: Long =
    conf.getConf(LOAD_QUANTUM)
  def maxCoalescedDistanceBytes: Long =
    conf.getConf(MAX_COALESCED_DISTANCE_BYTES)
  def maxCoalescedBytes: Long =
    conf.getConf(MAX_COALESCED_BYTES)
  def cachePrefetchMinPct: Int =
    conf.getConf(CACHE_PREFETCH_MINPCT)

  def enableColumnarProjectCollapse: Boolean = conf.getConf(ENABLE_COLUMNAR_PROJECT_COLLAPSE)

  def awsSdkLogLevel: String = conf.getConf(AWS_SDK_LOG_LEVEL)

  def awsS3RetryMode: String = conf.getConf(AWS_S3_RETRY_MODE)

  def awsConnectionTimeout: String = conf.getConf(AWS_S3_CONNECT_TIMEOUT)

  def enableCastAvgAggregateFunction: Boolean = conf.getConf(COLUMNAR_NATIVE_CAST_AGGREGATE_ENABLED)

  def enableGlutenCostEvaluator: Boolean = conf.getConf(COST_EVALUATOR_ENABLED)

  def dynamicOffHeapSizingEnabled: Boolean =
    conf.getConf(DYNAMIC_OFFHEAP_SIZING_ENABLED)

  def enableHiveFileFormatWriter: Boolean = conf.getConf(NATIVE_HIVEFILEFORMAT_WRITER_ENABLED)

  def enableCelebornFallback: Boolean = conf.getConf(CELEBORN_FALLBACK_ENABLED)
}

object GlutenConfig {
  import SQLConf._

  var GLUTEN_ENABLE_BY_DEFAULT = true
  val GLUTEN_ENABLE_KEY = "spark.gluten.enabled"
  val GLUTEN_LIB_NAME = "spark.gluten.sql.columnar.libname"
  val GLUTEN_LIB_PATH = "spark.gluten.sql.columnar.libpath"
  val GLUTEN_EXECUTOR_LIB_PATH = "spark.gluten.sql.columnar.executor.libpath"

  // Hive configurations.
  val SPARK_PREFIX = "spark."
  val HIVE_EXEC_ORC_STRIPE_SIZE = "hive.exec.orc.stripe.size"
  val SPARK_HIVE_EXEC_ORC_STRIPE_SIZE: String = SPARK_PREFIX + HIVE_EXEC_ORC_STRIPE_SIZE
  val HIVE_EXEC_ORC_ROW_INDEX_STRIDE = "hive.exec.orc.row.index.stride"
  val SPARK_HIVE_EXEC_ORC_ROW_INDEX_STRIDE: String = SPARK_PREFIX + HIVE_EXEC_ORC_ROW_INDEX_STRIDE
  val HIVE_EXEC_ORC_COMPRESS = "hive.exec.orc.compress"
  val SPARK_HIVE_EXEC_ORC_COMPRESS: String = SPARK_PREFIX + HIVE_EXEC_ORC_COMPRESS
  val SPARK_SQL_PARQUET_COMPRESSION_CODEC: String = "spark.sql.parquet.compression.codec"
  val PARQUET_BLOCK_SIZE: String = "parquet.block.size"
  val PARQUET_BLOCK_ROWS: String = "parquet.block.rows"
  val PARQUET_GZIP_WINDOW_SIZE: String = "parquet.gzip.windowSize"
  // Hadoop config
  val HADOOP_PREFIX = "spark.hadoop."

  // S3 config
  val S3A_PREFIX = "fs.s3a."
  val S3_ACCESS_KEY = "fs.s3a.access.key"
  val SPARK_S3_ACCESS_KEY: String = HADOOP_PREFIX + S3_ACCESS_KEY
  val S3_SECRET_KEY = "fs.s3a.secret.key"
  val SPARK_S3_SECRET_KEY: String = HADOOP_PREFIX + S3_SECRET_KEY
  val S3_ENDPOINT = "fs.s3a.endpoint"
  val SPARK_S3_ENDPOINT: String = HADOOP_PREFIX + S3_ENDPOINT
  val S3_CONNECTION_SSL_ENABLED = "fs.s3a.connection.ssl.enabled"
  val SPARK_S3_CONNECTION_SSL_ENABLED: String = HADOOP_PREFIX + S3_CONNECTION_SSL_ENABLED
  val S3_PATH_STYLE_ACCESS = "fs.s3a.path.style.access"
  val SPARK_S3_PATH_STYLE_ACCESS: String = HADOOP_PREFIX + S3_PATH_STYLE_ACCESS
  val S3_USE_INSTANCE_CREDENTIALS = "fs.s3a.use.instance.credentials"
  val SPARK_S3_USE_INSTANCE_CREDENTIALS: String = HADOOP_PREFIX + S3_USE_INSTANCE_CREDENTIALS
  val S3_IAM_ROLE = "fs.s3a.iam.role"
  val SPARK_S3_IAM: String = HADOOP_PREFIX + S3_IAM_ROLE
  val S3_IAM_ROLE_SESSION_NAME = "fs.s3a.iam.role.session.name"
  val SPARK_S3_IAM_SESSION_NAME: String = HADOOP_PREFIX + S3_IAM_ROLE_SESSION_NAME
  val S3_RETRY_MAX_ATTEMPTS = "fs.s3a.retry.limit"
  val SPARK_S3_RETRY_MAX_ATTEMPTS: String = HADOOP_PREFIX + S3_RETRY_MAX_ATTEMPTS
  val S3_CONNECTION_MAXIMUM = "fs.s3a.connection.maximum"
  val SPARK_S3_CONNECTION_MAXIMUM: String = HADOOP_PREFIX + S3_CONNECTION_MAXIMUM

  // Hardware acceleraters backend
  val GLUTEN_SHUFFLE_CODEC_BACKEND = "spark.gluten.sql.columnar.shuffle.codecBackend"
  // ABFS config
  val ABFS_ACCOUNT_KEY = "hadoop.fs.azure.account.key"
  val SPARK_ABFS_ACCOUNT_KEY: String = "spark." + ABFS_ACCOUNT_KEY

  // GCS config
  val GCS_PREFIX = "fs.gs."
  val GCS_STORAGE_ROOT_URL = "fs.gs.storage.root.url"
  val SPARK_GCS_STORAGE_ROOT_URL: String = HADOOP_PREFIX + GCS_STORAGE_ROOT_URL
  val GCS_AUTH_TYPE = "fs.gs.auth.type"
  val SPARK_GCS_AUTH_TYPE: String = HADOOP_PREFIX + GCS_AUTH_TYPE
  val GCS_AUTH_SERVICE_ACCOUNT_JSON_KEYFILE = "fs.gs.auth.service.account.json.keyfile"
  val SPARK_GCS_AUTH_SERVICE_ACCOUNT_JSON_KEYFILE: String =
    HADOOP_PREFIX + GCS_AUTH_SERVICE_ACCOUNT_JSON_KEYFILE

  // QAT config
  val GLUTEN_QAT_BACKEND_NAME = "qat"
  val GLUTEN_QAT_SUPPORTED_CODEC: Set[String] = Set("gzip", "zstd")
  // IAA config
  val GLUTEN_IAA_BACKEND_NAME = "iaa"
  val GLUTEN_IAA_SUPPORTED_CODEC: Set[String] = Set("gzip")

  val GLUTEN_CONFIG_PREFIX = "spark.gluten.sql.columnar.backend."

  // Private Spark configs.
  val SPARK_ONHEAP_SIZE_KEY = "spark.executor.memory"
  val SPARK_OVERHEAD_SIZE_KEY = "spark.executor.memoryOverhead"
  val SPARK_OVERHEAD_FACTOR_KEY = "spark.executor.memoryOverheadFactor"
  val SPARK_OFFHEAP_SIZE_KEY = "spark.memory.offHeap.size"
  val SPARK_OFFHEAP_ENABLED = "spark.memory.offHeap.enabled"
  val SPARK_REDACTION_REGEX = "spark.redaction.regex"

  // For Soft Affinity Scheduling
  // Enable Soft Affinity Scheduling, default value is false
  val GLUTEN_SOFT_AFFINITY_ENABLED = "spark.gluten.soft-affinity.enabled"
  val GLUTEN_SOFT_AFFINITY_ENABLED_DEFAULT_VALUE = false
  // Calculate the number of the replications for scheduling to the target executors per file
  val GLUTEN_SOFT_AFFINITY_REPLICATIONS_NUM = "spark.gluten.soft-affinity.replications.num"
  val GLUTEN_SOFT_AFFINITY_REPLICATIONS_NUM_DEFAULT_VALUE = 2
  // For on HDFS, if there are already target hosts,
  // and then prefer to use the original target hosts to schedule
  val GLUTEN_SOFT_AFFINITY_MIN_TARGET_HOSTS = "spark.gluten.soft-affinity.min.target-hosts"
  val GLUTEN_SOFT_AFFINITY_MIN_TARGET_HOSTS_DEFAULT_VALUE = 1

  // Enable Soft Affinity duplicate reading detection, defalut value is true
  val GLUTEN_SOFT_AFFINITY_DUPLICATE_READING_DETECT_ENABLED =
    "spark.gluten.soft-affinity.duplicateReadingDetect.enabled"
  val GLUTEN_SOFT_AFFINITY_DUPLICATE_READING_DETECT_ENABLED_DEFAULT_VALUE = true
  // Enable Soft Affinity duplicate reading detection, defalut value is 10000
  val GLUTEN_SOFT_AFFINITY_MAX_DUPLICATE_READING_RECORDS =
    "spark.gluten.soft-affinity.maxDuplicateReading.records"
  val GLUTEN_SOFT_AFFINITY_MAX_DUPLICATE_READING_RECORDS_DEFAULT_VALUE = 10000

  // Pass through to native conf
  val GLUTEN_SAVE_DIR = "spark.gluten.saveDir"

  val GLUTEN_DEBUG_MODE = "spark.gluten.sql.debug"
  val GLUTEN_DEBUG_KEEP_JNI_WORKSPACE = "spark.gluten.sql.debug.keepJniWorkspace"
  val GLUTEN_DEBUG_KEEP_JNI_WORKSPACE_DIR = "spark.gluten.sql.debug.keepJniWorkspaceDir"

  // Added back to Spark Conf during executor initialization
  val GLUTEN_NUM_TASK_SLOTS_PER_EXECUTOR_KEY = "spark.gluten.numTaskSlotsPerExecutor"
  val GLUTEN_OVERHEAD_SIZE_IN_BYTES_KEY = "spark.gluten.memoryOverhead.size.in.bytes"
  val GLUTEN_OFFHEAP_SIZE_IN_BYTES_KEY = "spark.gluten.memory.offHeap.size.in.bytes"
  val GLUTEN_TASK_OFFHEAP_SIZE_IN_BYTES_KEY = "spark.gluten.memory.task.offHeap.size.in.bytes"
  val GLUTEN_CONSERVATIVE_TASK_OFFHEAP_SIZE_IN_BYTES_KEY =
    "spark.gluten.memory.conservative.task.offHeap.size.in.bytes"

  // Batch size.
  val GLUTEN_MAX_BATCH_SIZE_KEY = "spark.gluten.sql.columnar.maxBatchSize"

  // Shuffle writer type.
  val GLUTEN_HASH_SHUFFLE_WRITER = "hash"
  val GLUTEN_SORT_SHUFFLE_WRITER = "sort"
  val GLUTEN_RSS_SORT_SHUFFLE_WRITER = "rss_sort"

  // Shuffle Writer buffer size.
  val GLUTEN_SHUFFLE_WRITER_BUFFER_SIZE = "spark.gluten.shuffleWriter.bufferSize"

  val GLUTEN_SHUFFLE_WRITER_MERGE_THRESHOLD = "spark.gluten.sql.columnar.shuffle.merge.threshold"

  // Controls whether to load DLL from jars. User can get dependent native libs packed into a jar
  // by executing dev/package.sh. Then, with that jar configured, Gluten can load the native libs
  // at runtime. This config is just for velox backend. And it is NOT applicable to the situation
  // where deployed gluten jar is generated through static build (e.g., Gluten's release jar).
  val GLUTEN_LOAD_LIB_FROM_JAR = "spark.gluten.loadLibFromJar"
  val GLUTEN_LOAD_LIB_FROM_JAR_DEFAULT = false
  val GLUTEN_LOAD_LIB_OS = "spark.gluten.loadLibOS"
  val GLUTEN_LOAD_LIB_OS_VERSION = "spark.gluten.loadLibOSVersion"

  // Expired time of execution with resource relation has cached
  val GLUTEN_RESOURCE_RELATION_EXPIRED_TIME = "spark.gluten.execution.resource.expired.time"
  // unit: SECONDS, default 1 day
  val GLUTEN_RESOURCE_RELATION_EXPIRED_TIME_DEFAULT: Int = 86400

  // Supported hive/python/scala udf names
  val GLUTEN_SUPPORTED_HIVE_UDFS = "spark.gluten.supported.hive.udfs"
  val GLUTEN_SUPPORTED_PYTHON_UDFS = "spark.gluten.supported.python.udfs"
  val GLUTEN_SUPPORTED_SCALA_UDFS = "spark.gluten.supported.scala.udfs"

  // FIXME: This only works with CH backend.
  val GLUTEN_EXTENDED_EXPRESSION_TRAN_CONF =
    "spark.gluten.sql.columnar.extended.expressions.transformer"

  // This is an internal config property set by Gluten. It is used to hold default session timezone
  // and will be really used by Gluten only if `spark.sql.session.timeZone` is not set.
  val GLUTEN_DEFAULT_SESSION_TIMEZONE_KEY = "spark.gluten.sql.session.timeZone.default"

  // Principal of current user
  val GLUTEN_UGI_USERNAME = "spark.gluten.ugi.username"
  // Tokens of current user, split by `\0`
  val GLUTEN_UGI_TOKENS = "spark.gluten.ugi.tokens"

  val GLUTEN_UI_ENABLED = "spark.gluten.ui.enabled"

  val GLUTEN_DYNAMIC_OFFHEAP_SIZING_ENABLED = "spark.gluten.memory.dynamic.offHeap.sizing.enabled"
  val GLUTEN_DYNAMIC_OFFHEAP_SIZING_MEMORY_FRACTION =
    "spark.gluten.memory.dynamic.offHeap.sizing.memory.fraction"

  val GLUTEN_COST_EVALUATOR_ENABLED = "spark.gluten.sql.adaptive.costEvaluator.enabled"

  var ins: GlutenConfig = _

  def getConf: GlutenConfig = {
    new GlutenConfig(SQLConf.get)
  }

  @deprecated
  def getTempFile: String = synchronized {
    if (ins != null && ins.tmpFile.nonEmpty) {
      ins.tmpFile.get
    } else {
      System.getProperty("java.io.tmpdir")
    }
  }

  /** Get dynamic configs. */
  def getNativeSessionConf(
      backendPrefix: String,
      conf: scala.collection.Map[String, String]): util.Map[String, String] = {
    val nativeConfMap = new util.HashMap[String, String]()
    val keys = Set(
      GLUTEN_DEBUG_MODE,
      GLUTEN_SAVE_DIR,
      GLUTEN_TASK_OFFHEAP_SIZE_IN_BYTES_KEY,
      GLUTEN_MAX_BATCH_SIZE_KEY,
      GLUTEN_SHUFFLE_WRITER_BUFFER_SIZE,
      SQLConf.SESSION_LOCAL_TIMEZONE.key,
      GLUTEN_DEFAULT_SESSION_TIMEZONE_KEY,
      SQLConf.LEGACY_SIZE_OF_NULL.key,
      "spark.io.compression.codec",
      "spark.sql.decimalOperations.allowPrecisionLoss",
      COLUMNAR_VELOX_BLOOM_FILTER_EXPECTED_NUM_ITEMS.key,
      COLUMNAR_VELOX_BLOOM_FILTER_NUM_BITS.key,
      COLUMNAR_VELOX_BLOOM_FILTER_MAX_NUM_BITS.key,
      // s3 config
      SPARK_S3_ACCESS_KEY,
      SPARK_S3_SECRET_KEY,
      SPARK_S3_ENDPOINT,
      SPARK_S3_CONNECTION_SSL_ENABLED,
      SPARK_S3_PATH_STYLE_ACCESS,
      SPARK_S3_USE_INSTANCE_CREDENTIALS,
      SPARK_S3_IAM,
      SPARK_S3_IAM_SESSION_NAME,
      SPARK_S3_RETRY_MAX_ATTEMPTS,
      SPARK_S3_CONNECTION_MAXIMUM,
      AWS_S3_CONNECT_TIMEOUT.key,
      AWS_S3_RETRY_MODE.key,
      AWS_SDK_LOG_LEVEL.key,
      // gcs config
      SPARK_GCS_STORAGE_ROOT_URL,
      SPARK_GCS_AUTH_TYPE,
      SPARK_GCS_AUTH_SERVICE_ACCOUNT_JSON_KEYFILE,
      SPARK_REDACTION_REGEX
    )
    nativeConfMap.putAll(conf.filter(e => keys.contains(e._1)).asJava)

    val keyWithDefault = ImmutableList.of(
      (SQLConf.CASE_SENSITIVE.key, SQLConf.CASE_SENSITIVE.defaultValueString),
      (SQLConf.IGNORE_MISSING_FILES.key, SQLConf.IGNORE_MISSING_FILES.defaultValueString),
      (
        COLUMNAR_MEMORY_BACKTRACE_ALLOCATION.key,
        COLUMNAR_MEMORY_BACKTRACE_ALLOCATION.defaultValueString),
      (
        GLUTEN_COLUMNAR_TO_ROW_MEM_THRESHOLD.key,
        GLUTEN_COLUMNAR_TO_ROW_MEM_THRESHOLD.defaultValue.get.toString)
    )
    keyWithDefault.forEach(e => nativeConfMap.put(e._1, conf.getOrElse(e._1, e._2)))

    // Backend's dynamic session conf only.
    conf
      .filter(entry => entry._1.startsWith(backendPrefix) && !SQLConf.isStaticConfigKey(entry._1))
      .foreach(entry => nativeConfMap.put(entry._1, entry._2))

    // Pass the latest tokens to native
    nativeConfMap.put(
      GLUTEN_UGI_TOKENS,
      UserGroupInformation.getCurrentUser.getTokens.asScala
        .map(_.encodeToUrlString)
        .mkString("\u0000"))
    nativeConfMap.put(GLUTEN_UGI_USERNAME, UserGroupInformation.getCurrentUser.getUserName)

    // return
    nativeConfMap
  }

  /**
   * Get static and dynamic configs. Some of the config is dynamic in spark, but is static in
   * gluten, these will be used to construct HiveConnector which intends reused in velox
   */
  def getNativeBackendConf(
      backendPrefix: String,
      conf: scala.collection.Map[String, String]): util.Map[String, String] = {

    val nativeConfMap = new util.HashMap[String, String]()

    // some configs having default values
    val keyWithDefault = ImmutableList.of(
      (SPARK_S3_ACCESS_KEY, ""),
      (SPARK_S3_SECRET_KEY, ""),
      (SPARK_S3_ENDPOINT, "localhost:9000"),
      (SPARK_S3_CONNECTION_SSL_ENABLED, "false"),
      (SPARK_S3_PATH_STYLE_ACCESS, "true"),
      (SPARK_S3_USE_INSTANCE_CREDENTIALS, "false"),
      (SPARK_S3_IAM, ""),
      (SPARK_S3_IAM_SESSION_NAME, ""),
      (SPARK_S3_RETRY_MAX_ATTEMPTS, "20"),
      (SPARK_S3_CONNECTION_MAXIMUM, "15"),
      (AWS_S3_CONNECT_TIMEOUT.key, AWS_S3_CONNECT_TIMEOUT.defaultValueString),
      (AWS_S3_RETRY_MODE.key, AWS_S3_RETRY_MODE.defaultValueString),
      (
        COLUMNAR_VELOX_CONNECTOR_IO_THREADS.key,
        conf.getOrElse(
          NUM_TASK_SLOTS_PER_EXECUTOR.key,
          NUM_TASK_SLOTS_PER_EXECUTOR.defaultValueString)),
      (COLUMNAR_SHUFFLE_CODEC.key, ""),
      (COLUMNAR_SHUFFLE_CODEC_BACKEND.key, ""),
      ("spark.hadoop.input.connect.timeout", "180000"),
      ("spark.hadoop.input.read.timeout", "180000"),
      ("spark.hadoop.input.write.timeout", "180000"),
      ("spark.hadoop.dfs.client.log.severity", "INFO"),
      ("spark.sql.orc.compression.codec", "snappy"),
      ("spark.sql.decimalOperations.allowPrecisionLoss", "true"),
      (
        COLUMNAR_VELOX_FILE_HANDLE_CACHE_ENABLED.key,
        COLUMNAR_VELOX_FILE_HANDLE_CACHE_ENABLED.defaultValueString),
      (AWS_SDK_LOG_LEVEL.key, AWS_SDK_LOG_LEVEL.defaultValueString)
    )
    keyWithDefault.forEach(e => nativeConfMap.put(e._1, conf.getOrElse(e._1, e._2)))

    val keys = Set(
      GLUTEN_DEBUG_MODE,
      // datasource config
      SPARK_SQL_PARQUET_COMPRESSION_CODEC,
      // datasource config end

      GLUTEN_OVERHEAD_SIZE_IN_BYTES_KEY,
      GLUTEN_OFFHEAP_SIZE_IN_BYTES_KEY,
      GLUTEN_TASK_OFFHEAP_SIZE_IN_BYTES_KEY,
      SPARK_OFFHEAP_ENABLED,
      SESSION_LOCAL_TIMEZONE.key,
      DECIMAL_OPERATIONS_ALLOW_PREC_LOSS.key,
      SPARK_REDACTION_REGEX
    )
    nativeConfMap.putAll(conf.filter(e => keys.contains(e._1)).asJava)

    conf
      .filter(_._1.startsWith(backendPrefix))
      .foreach(entry => nativeConfMap.put(entry._1, entry._2))

    // put in all S3 configs
    conf
      .filter(_._1.startsWith(HADOOP_PREFIX + S3A_PREFIX))
      .foreach(entry => nativeConfMap.put(entry._1, entry._2))

    conf
      .filter(_._1.startsWith(SPARK_ABFS_ACCOUNT_KEY))
      .foreach(entry => nativeConfMap.put(entry._1, entry._2))

    // return
    nativeConfMap
  }

  val GLUTEN_ENABLED =
    buildConf(GLUTEN_ENABLE_KEY)
      .internal()
      .doc("Whether to enable gluten. Default value is true. Just an experimental property." +
        " Recommend to enable/disable Gluten through the setting for spark.plugins.")
      .booleanConf
      .createWithDefault(GLUTEN_ENABLE_BY_DEFAULT)

  // FIXME the option currently controls both JVM and native validation against a Substrait plan.
  val NATIVE_VALIDATION_ENABLED =
    buildConf("spark.gluten.sql.enable.native.validation")
      .internal()
      .doc(
        "This is tmp config to specify whether to enable the native validation based on " +
          "Substrait plan. After the validations in all backends are correctly implemented, " +
          "this config should be removed.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_BATCHSCAN_ENABLED =
    buildConf("spark.gluten.sql.columnar.batchscan")
      .internal()
      .doc("Enable or disable columnar batchscan.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_FILESCAN_ENABLED =
    buildConf("spark.gluten.sql.columnar.filescan")
      .internal()
      .doc("Enable or disable columnar filescan.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_HIVETABLESCAN_ENABLED =
    buildConf("spark.gluten.sql.columnar.hivetablescan")
      .internal()
      .doc("Enable or disable columnar hivetablescan.")
      .booleanConf
      .createWithDefault(true)

  val VANILLA_VECTORIZED_READERS_ENABLED =
    buildConf("spark.gluten.sql.columnar.enableVanillaVectorizedReaders")
      .internal()
      .doc("Enable or disable vanilla vectorized scan.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_HASHAGG_ENABLED =
    buildConf("spark.gluten.sql.columnar.hashagg")
      .internal()
      .doc("Enable or disable columnar hashagg.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_FORCE_HASHAGG_ENABLED =
    buildConf("spark.gluten.sql.columnar.force.hashagg")
      .internal()
      .doc("Whether to force to use gluten's hash agg for replacing vanilla spark's sort agg.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_PROJECT_ENABLED =
    buildConf("spark.gluten.sql.columnar.project")
      .internal()
      .doc("Enable or disable columnar project.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_FILTER_ENABLED =
    buildConf("spark.gluten.sql.columnar.filter")
      .internal()
      .doc("Enable or disable columnar filter.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_SORT_ENABLED =
    buildConf("spark.gluten.sql.columnar.sort")
      .internal()
      .doc("Enable or disable columnar sort.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_WINDOW_ENABLED =
    buildConf("spark.gluten.sql.columnar.window")
      .internal()
      .doc("Enable or disable columnar window.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_WINDOW_GROUP_LIMIT_ENABLED =
    buildConf("spark.gluten.sql.columnar.window.group.limit")
      .internal()
      .doc("Enable or disable columnar window group limit.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_VELOX_WINDOW_TYPE =
    buildConf("spark.gluten.sql.columnar.backend.velox.window.type")
      .internal()
      .doc(
        "Velox backend supports both SortWindow and" +
          " StreamingWindow operators." +
          " The StreamingWindow operator skips the sorting step" +
          " in the input but does not support spill." +
          " On the other hand, the SortWindow operator is " +
          "responsible for sorting the input data within the" +
          " Window operator and also supports spill.")
      .stringConf
      .checkValues(Set("streaming", "sort"))
      .createWithDefault("streaming")

  val COLUMNAR_PREFER_STREAMING_AGGREGATE =
    buildConf("spark.gluten.sql.columnar.preferStreamingAggregate")
      .internal()
      .doc(
        "Velox backend supports `StreamingAggregate`. `StreamingAggregate` uses the less " +
          "memory as it does not need to hold all groups in memory, so it could avoid spill. " +
          "When true and the child output ordering satisfies the grouping key then " +
          "Gluten will choose `StreamingAggregate` as the native operator.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_FPRCE_SHUFFLED_HASH_JOIN_ENABLED =
    buildConf("spark.gluten.sql.columnar.forceShuffledHashJoin")
      .internal()
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_SHUFFLED_HASH_JOIN_ENABLED =
    buildConf("spark.gluten.sql.columnar.shuffledHashJoin")
      .internal()
      .doc("Enable or disable columnar shuffledHashJoin.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_COLUMNAR_TO_ROW_ENABLED =
    buildConf("spark.gluten.sql.columnar.columnarToRow")
      .internal()
      .doc("Enable or disable columnar columnarToRow.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_SORTMERGEJOIN_ENABLED =
    buildConf("spark.gluten.sql.columnar.sortMergeJoin")
      .internal()
      .doc("Enable or disable columnar sortMergeJoin. " +
        "This should be set with preferSortMergeJoin=false.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_UNION_ENABLED =
    buildConf("spark.gluten.sql.columnar.union")
      .internal()
      .doc("Enable or disable columnar union.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_EXPAND_ENABLED =
    buildConf("spark.gluten.sql.columnar.expand")
      .internal()
      .doc("Enable or disable columnar expand.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_BROADCAST_EXCHANGE_ENABLED =
    buildConf("spark.gluten.sql.columnar.broadcastExchange")
      .internal()
      .doc("Enable or disable columnar broadcastExchange.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_BROADCAST_JOIN_ENABLED =
    buildConf("spark.gluten.sql.columnar.broadcastJoin")
      .internal()
      .doc("Enable or disable columnar broadcastJoin.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_ARROW_UDF_ENABLED =
    buildConf("spark.gluten.sql.columnar.arrowUdf")
      .internal()
      .doc("Enable or disable columnar arrow udf.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_COALESCE_ENABLED =
    buildConf("spark.gluten.sql.columnar.coalesce")
      .internal()
      .doc("Enable or disable columnar coalesce.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_SHUFFLE_ENABLED =
    buildConf("spark.gluten.sql.columnar.shuffle")
      .internal()
      .doc("Enable or disable columnar shuffle.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_SHUFFLE_SORT_PARTITIONS_THRESHOLD =
    buildConf("spark.gluten.sql.columnar.shuffle.sort.partitions.threshold")
      .internal()
      .doc("The threshold to determine whether to use sort-based columnar shuffle. Sort-based " +
        "shuffle will be used if the number of partitions is greater than this threshold.")
      .intConf
      .createWithDefault(100000)

  val COLUMNAR_SHUFFLE_SORT_COLUMNS_THRESHOLD =
    buildConf("spark.gluten.sql.columnar.shuffle.sort.columns.threshold")
      .internal()
      .doc("The threshold to determine whether to use sort-based columnar shuffle. Sort-based " +
        "shuffle will be used if the number of columns is greater than this threshold.")
      .intConf
      .createWithDefault(100000)

  val COLUMNAR_PREFER_ENABLED =
    buildConf("spark.gluten.sql.columnar.preferColumnar")
      .internal()
      .doc("Prefer to use columnar operators if set to true.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_ONE_ROW_RELATION_ENABLED =
    buildConf("spark.gluten.sql.columnar.oneRowRelation")
      .internal()
      .doc("Enable or disable columnar `OneRowRelation`.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_TABLE_CACHE_ENABLED =
    buildConf("spark.gluten.sql.columnar.tableCache")
      .internal()
      .doc("Enable or disable columnar table cache.")
      .booleanConf
      .createWithDefault(false)

  val COLUMNAR_PHYSICAL_JOIN_OPTIMIZATION_THROTTLE =
    buildConf("spark.gluten.sql.columnar.physicalJoinOptimizationLevel")
      .internal()
      .doc("Fallback to row operators if there are several continuous joins.")
      .intConf
      .createWithDefault(12)

  val COLUMNAR_PHYSICAL_JOIN_OPTIMIZATION_ENABLED =
    buildConf("spark.gluten.sql.columnar.physicalJoinOptimizeEnable")
      .internal()
      .doc("Enable or disable columnar physicalJoinOptimize.")
      .booleanConf
      .createWithDefault(false)

  val COLUMNAR_LOGICAL_JOIN_OPTIMIZATION_THROTTLE =
    buildConf("spark.gluten.sql.columnar.logicalJoinOptimizationLevel")
      .internal()
      .doc("Fallback to row operators if there are several continuous joins.")
      .intConf
      .createWithDefault(12)

  val COLUMNAR_SCAN_ONLY_ENABLED =
    buildConf("spark.gluten.sql.columnar.scanOnly")
      .internal()
      .doc("When enabled, only scan and the filter after scan will be offloaded to native.")
      .booleanConf
      .createWithDefault(false)

  val COLUMNAR_TEMP_DIR =
    buildConf("spark.gluten.sql.columnar.tmp_dir")
      .internal()
      .doc("A folder to store the codegen files.")
      .stringConf
      .createOptional

  val COLUMNAR_BROADCAST_CACHE_TIMEOUT =
    buildConf("spark.sql.columnar.sort.broadcast.cache.timeout")
      .internal()
      .doc("Deprecated")
      .intConf
      .createWithDefault(-1)

  val COLUMNAR_SHUFFLE_REALLOC_THRESHOLD =
    buildConf("spark.gluten.sql.columnar.shuffle.realloc.threshold")
      .internal()
      .doubleConf
      .checkValue(v => v >= 0 && v <= 1, "Buffer reallocation threshold must between [0, 1]")
      .createWithDefault(0.25)

  val COLUMNAR_SHUFFLE_CODEC =
    buildConf("spark.gluten.sql.columnar.shuffle.codec")
      .internal()
      .doc(
        "By default, the supported codecs are lz4 and zstd. " +
          "When spark.gluten.sql.columnar.shuffle.codecBackend=qat," +
          "the supported codecs are gzip and zstd. " +
          "When spark.gluten.sql.columnar.shuffle.codecBackend=iaa," +
          "the supported codec is gzip.")
      .stringConf
      .transform(_.toLowerCase(Locale.ROOT))
      .createOptional

  val COLUMNAR_SHUFFLE_CODEC_BACKEND =
    buildConf(GlutenConfig.GLUTEN_SHUFFLE_CODEC_BACKEND)
      .internal()
      .stringConf
      .transform(_.toLowerCase(Locale.ROOT))
      .createOptional

  val COLUMNAR_SHUFFLE_COMPRESSION_MODE =
    buildConf("spark.gluten.sql.columnar.shuffle.compressionMode")
      .internal()
      .doc("buffer means compress each buffer to pre allocated big buffer," +
        "rowvector means to copy the buffers to a big buffer, and then compress the buffer")
      .stringConf
      .checkValues(Set("buffer", "rowvector"))
      .createWithDefault("buffer")

  val COLUMNAR_SHUFFLE_COMPRESSION_THRESHOLD =
    buildConf("spark.gluten.sql.columnar.shuffle.compression.threshold")
      .internal()
      .doc("If number of rows in a batch falls below this threshold," +
        " will copy all buffers into one buffer to compress.")
      .intConf
      .createWithDefault(100)

  val SHUFFLE_WRITER_MERGE_THRESHOLD =
    buildConf(GLUTEN_SHUFFLE_WRITER_MERGE_THRESHOLD)
      .internal()
      .doubleConf
      .checkValue(v => v >= 0 && v <= 1, "Shuffle writer merge threshold must between [0, 1]")
      .createWithDefault(0.25)

  val COLUMNAR_MAX_BATCH_SIZE =
    buildConf(GLUTEN_MAX_BATCH_SIZE_KEY)
      .internal()
      .intConf
      .checkValue(_ > 0, s"$GLUTEN_MAX_BATCH_SIZE_KEY must be positive.")
      .createWithDefault(4096)

  val GLUTEN_COLUMNAR_TO_ROW_MEM_THRESHOLD =
    buildConf("spark.gluten.sql.columnarToRowMemoryThreshold")
      .internal()
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("64MB")

  // if not set, use COLUMNAR_MAX_BATCH_SIZE instead
  val SHUFFLE_WRITER_BUFFER_SIZE =
    buildConf(GLUTEN_SHUFFLE_WRITER_BUFFER_SIZE)
      .internal()
      .intConf
      .checkValue(_ > 0, s"$GLUTEN_SHUFFLE_WRITER_BUFFER_SIZE must be positive.")
      .createOptional

  val COLUMNAR_LIMIT_ENABLED =
    buildConf("spark.gluten.sql.columnar.limit")
      .internal()
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_GENERATE_ENABLED =
    buildConf("spark.gluten.sql.columnar.generate")
      .internal()
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_TAKE_ORDERED_AND_PROJECT_ENABLED =
    buildConf("spark.gluten.sql.columnar.takeOrderedAndProject")
      .internal()
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_NATIVE_BLOOMFILTER_ENABLED =
    buildConf("spark.gluten.sql.native.bloomFilter")
      .internal()
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_NATIVE_HYPERLOGLOG_AGGREGATE_ENABLED =
    buildConf("spark.gluten.sql.native.hyperLogLog.Aggregate")
      .internal()
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_PARQUET_WRITE_BLOCK_SIZE =
    buildConf("spark.gluten.sql.columnar.parquet.write.blockSize")
      .internal()
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("128MB")

  val COLUMNAR_PARQUET_WRITE_BLOCK_ROWS =
    buildConf("spark.gluten.sql.native.parquet.write.blockRows")
      .internal()
      .longConf
      .createWithDefault(100 * 1000 * 1000)

  val COLUMNAR_QUERY_FALLBACK_THRESHOLD =
    buildConf("spark.gluten.sql.columnar.query.fallback.threshold")
      .internal()
      .doc("The threshold for whether query will fall back " +
        "by counting the number of ColumnarToRow & vanilla leaf node.")
      .intConf
      .createWithDefault(-1)

  val COLUMNAR_WHOLESTAGE_FALLBACK_THRESHOLD =
    buildConf("spark.gluten.sql.columnar.wholeStage.fallback.threshold")
      .internal()
      .doc("The threshold for whether whole stage will fall back in AQE supported case " +
        "by counting the number of ColumnarToRow & vanilla leaf node.")
      .intConf
      .createWithDefault(-1)

  val COLUMNAR_FALLBACK_IGNORE_ROW_TO_COLUMNAR =
    buildConf("spark.gluten.sql.columnar.fallback.ignoreRowToColumnar")
      .internal()
      .doc(
        "When true, the fallback policy ignores the RowToColumnar when counting fallback number.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_FALLBACK_EXPRESSIONS_THRESHOLD =
    buildConf("spark.gluten.sql.columnar.fallback.expressions.threshold")
      .internal()
      .doc("Fall back filter/project if number of nested expressions reaches this threshold," +
        " considering Spark codegen can bring better performance for such case.")
      .intConf
      .createWithDefault(50)

  val COLUMNAR_FALLBACK_PREFER_COLUMNAR =
    buildConf("spark.gluten.sql.columnar.fallback.preferColumnar")
      .internal()
      .doc(
        "When true, the fallback policy prefers to use Gluten plan rather than vanilla " +
          "Spark plan if the both of them contains ColumnarToRow and the vanilla Spark plan " +
          "ColumnarToRow number is not smaller than Gluten plan.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_NUMA_BINDING_ENABLED =
    buildConf("spark.gluten.sql.columnar.numaBinding")
      .internal()
      .booleanConf
      .createWithDefault(false)

  val COLUMNAR_NUMA_BINDING_CORE_RANGE =
    buildConf("spark.gluten.sql.columnar.coreRange")
      .internal()
      .stringConf
      .createOptional

  val NUM_TASK_SLOTS_PER_EXECUTOR =
    buildConf(GlutenConfig.GLUTEN_NUM_TASK_SLOTS_PER_EXECUTOR_KEY)
      .internal()
      .doc(
        "Must provide default value since non-execution operations " +
          "(e.g. org.apache.spark.sql.Dataset#summary) doesn't propagate configurations using " +
          "org.apache.spark.sql.execution.SQLExecution#withSQLConfPropagated")
      .intConf
      .createWithDefaultString("-1")

  val COLUMNAR_OVERHEAD_SIZE_IN_BYTES =
    buildConf(GlutenConfig.GLUTEN_OVERHEAD_SIZE_IN_BYTES_KEY)
      .internal()
      .doc(
        "Must provide default value since non-execution operations " +
          "(e.g. org.apache.spark.sql.Dataset#summary) doesn't propagate configurations using " +
          "org.apache.spark.sql.execution.SQLExecution#withSQLConfPropagated")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("0")

  val COLUMNAR_OFFHEAP_SIZE_IN_BYTES =
    buildConf(GlutenConfig.GLUTEN_OFFHEAP_SIZE_IN_BYTES_KEY)
      .internal()
      .doc(
        "Must provide default value since non-execution operations " +
          "(e.g. org.apache.spark.sql.Dataset#summary) doesn't propagate configurations using " +
          "org.apache.spark.sql.execution.SQLExecution#withSQLConfPropagated")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("0")

  val COLUMNAR_TASK_OFFHEAP_SIZE_IN_BYTES =
    buildConf(GlutenConfig.GLUTEN_TASK_OFFHEAP_SIZE_IN_BYTES_KEY)
      .internal()
      .doc(
        "Must provide default value since non-execution operations " +
          "(e.g. org.apache.spark.sql.Dataset#summary) doesn't propagate configurations using " +
          "org.apache.spark.sql.execution.SQLExecution#withSQLConfPropagated")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("0")

  val COLUMNAR_CONSERVATIVE_TASK_OFFHEAP_SIZE_IN_BYTES =
    buildConf(GlutenConfig.GLUTEN_CONSERVATIVE_TASK_OFFHEAP_SIZE_IN_BYTES_KEY)
      .internal()
      .doc(
        "Must provide default value since non-execution operations " +
          "(e.g. org.apache.spark.sql.Dataset#summary) doesn't propagate configurations using " +
          "org.apache.spark.sql.execution.SQLExecution#withSQLConfPropagated")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("0")

  val COLUMNAR_MEMORY_ISOLATION =
    buildConf("spark.gluten.memory.isolation")
      .internal()
      .doc("Enable isolated memory mode. If true, Gluten controls the maximum off-heap memory " +
        "can be used by each task to X, X = executor memory / max task slots. It's recommended " +
        "to set true if Gluten serves concurrent queries within a single session, since not all " +
        "memory Gluten allocated is guaranteed to be spillable. In the case, the feature should " +
        "be enabled to avoid OOM.")
      .booleanConf
      .createWithDefault(false)

  val COLUMNAR_MEMORY_BACKTRACE_ALLOCATION =
    buildConf("spark.gluten.memory.backtrace.allocation")
      .internal()
      .doc("Print backtrace information for large memory allocations. This helps debugging when " +
        "Spark OOM happens due to large acquire requests.")
      .booleanConf
      .createWithDefault(false)

  val COLUMNAR_MEMORY_OVER_ACQUIRED_RATIO =
    buildConf("spark.gluten.memory.overAcquiredMemoryRatio")
      .internal()
      .doc("If larger than 0, Velox backend will try over-acquire this ratio of the total " +
        "allocated memory as backup to avoid OOM.")
      .doubleConf
      .checkValue(d => d >= 0.0d, "Over-acquired ratio should be larger than or equals 0")
      .createWithDefault(0.3d)

  val COLUMNAR_MEMORY_RESERVATION_BLOCK_SIZE =
    buildConf("spark.gluten.memory.reservationBlockSize")
      .internal()
      .doc("Block size of native reservation listener reserve memory from Spark.")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("8MB")

  // Options used by RAS.
  val RAS_ENABLED =
    buildConf("spark.gluten.ras.enabled")
      .doc(
        "Experimental: Enables RAS (relational algebra selector) during physical " +
          "planning to generate more efficient query plan. Note, this feature is still in " +
          "development and may not bring performance profits.")
      .booleanConf
      .createWithDefault(false)

  val RAS_COST_MODEL =
    buildConf("spark.gluten.ras.costModel")
      .doc(
        "Experimental: The class name of user-defined cost model that will be used by RAS. If " +
          "not specified, a legacy built-in cost model that exhaustively offloads computations " +
          "will be used.")
      .stringConf
      .createWithDefaultString("legacy")

  // velox caching options.
  val COLUMNAR_VELOX_CACHE_ENABLED =
    buildStaticConf("spark.gluten.sql.columnar.backend.velox.cacheEnabled")
      .internal()
      .doc("Enable Velox cache, default off")
      .booleanConf
      .createWithDefault(false)

  val COLUMNAR_VELOX_MEM_CACHE_SIZE =
    buildStaticConf("spark.gluten.sql.columnar.backend.velox.memCacheSize")
      .internal()
      .doc("The memory cache size")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("1GB")

  val COLUMNAR_VELOX_MEM_INIT_CAPACITY =
    buildConf("spark.gluten.sql.columnar.backend.velox.memInitCapacity")
      .internal()
      .doc("The initial memory capacity to reserve for a newly created Velox query memory pool.")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("8MB")

  val COLUMNAR_VELOX_SSD_CACHE_PATH =
    buildStaticConf("spark.gluten.sql.columnar.backend.velox.ssdCachePath")
      .internal()
      .doc("The folder to store the cache files, better on SSD")
      .stringConf
      .createWithDefault("/tmp")

  val COLUMNAR_VELOX_SSD_CACHE_SIZE =
    buildStaticConf("spark.gluten.sql.columnar.backend.velox.ssdCacheSize")
      .internal()
      .doc("The SSD cache size, will do memory caching only if this value = 0")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("1GB")

  val COLUMNAR_VELOX_SSD_CACHE_SHARDS =
    buildStaticConf("spark.gluten.sql.columnar.backend.velox.ssdCacheShards")
      .internal()
      .doc("The cache shards")
      .intConf
      .createWithDefault(1)

  val COLUMNAR_VELOX_SSD_CACHE_IO_THREADS =
    buildStaticConf("spark.gluten.sql.columnar.backend.velox.ssdCacheIOThreads")
      .internal()
      .doc("The IO threads for cache promoting")
      .intConf
      .createWithDefault(1)

  val COLUMNAR_VELOX_SSD_ODIRECT_ENABLED =
    buildStaticConf("spark.gluten.sql.columnar.backend.velox.ssdODirect")
      .internal()
      .doc("The O_DIRECT flag for cache writing")
      .booleanConf
      .createWithDefault(false)

  val COLUMNAR_VELOX_CONNECTOR_IO_THREADS =
    buildStaticConf("spark.gluten.sql.columnar.backend.velox.IOThreads")
      .internal()
      .doc("The Size of the IO thread pool in the Connector. This thread pool is used for split" +
        " preloading and DirectBufferedInput.")
      .intConf
      .createOptional

  val COLUMNAR_VELOX_ASYNC_TIMEOUT =
    buildStaticConf("spark.gluten.sql.columnar.backend.velox.asyncTimeoutOnTaskStopping")
      .internal()
      .doc(
        "Timeout for asynchronous execution when task is being stopped in Velox backend. " +
          "It's recommended to set to a number larger than network connection timeout that the " +
          "possible aysnc tasks are relying on.")
      .timeConf(TimeUnit.MILLISECONDS)
      .createWithDefault(30000)

  val COLUMNAR_VELOX_SPLIT_PRELOAD_PER_DRIVER =
    buildConf("spark.gluten.sql.columnar.backend.velox.SplitPreloadPerDriver")
      .internal()
      .doc("The split preload per task")
      .intConf
      .createWithDefault(2)

  val COLUMNAR_VELOX_GLOG_VERBOSE_LEVEL =
    buildConf("spark.gluten.sql.columnar.backend.velox.glogVerboseLevel")
      .internal()
      .doc("Set glog verbose level in Velox backend, same as FLAGS_v.")
      .intConf
      .createWithDefault(0)

  val COLUMNAR_VELOX_GLOG_SEVERITY_LEVEL =
    buildConf("spark.gluten.sql.columnar.backend.velox.glogSeverityLevel")
      .internal()
      .doc("Set glog severity level in Velox backend, same as FLAGS_minloglevel.")
      .intConf
      .createWithDefault(1)

  val COLUMNAR_VELOX_SPILL_STRATEGY =
    buildConf("spark.gluten.sql.columnar.backend.velox.spillStrategy")
      .internal()
      .doc("none: Disable spill on Velox backend; " +
        "auto: Let Spark memory manager manage Velox's spilling")
      .stringConf
      .transform(_.toLowerCase(Locale.ROOT))
      .checkValues(Set("none", "auto"))
      .createWithDefault("auto")

  val COLUMNAR_VELOX_MAX_SPILL_LEVEL =
    buildConf("spark.gluten.sql.columnar.backend.velox.maxSpillLevel")
      .internal()
      .doc("The max allowed spilling level with zero being the initial spilling level")
      .intConf
      .createWithDefault(4)

  val COLUMNAR_VELOX_MAX_SPILL_FILE_SIZE =
    buildConf("spark.gluten.sql.columnar.backend.velox.maxSpillFileSize")
      .internal()
      .doc("The maximum size of a single spill file created")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("1GB")

  val COLUMNAR_VELOX_SPILL_FILE_SYSTEM =
    buildConf("spark.gluten.sql.columnar.backend.velox.spillFileSystem")
      .internal()
      .doc(
        "The filesystem used to store spill data. local: The local file system. " +
          "heap-over-local: Write file to JVM heap if having extra heap space. " +
          "Otherwise write to local file system.")
      .stringConf
      .checkValues(Set("local", "heap-over-local"))
      .createWithDefaultString("local")

  val COLUMNAR_VELOX_MAX_SPILL_RUN_ROWS =
    buildConf("spark.gluten.sql.columnar.backend.velox.maxSpillRunRows")
      .internal()
      .doc("The maximum row size of a single spill run")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("12M")

  val COLUMNAR_VELOX_MAX_SPILL_BYTES =
    buildConf("spark.gluten.sql.columnar.backend.velox.maxSpillBytes")
      .internal()
      .doc("The maximum file size of a query")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("100G")

  val COLUMNAR_VELOX_MAX_SPILL_WRITE_BUFFER_SIZE =
    buildConf("spark.gluten.sql.columnar.backend.velox.spillWriteBufferSize")
      .internal()
      .doc("The maximum write buffer size")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("4M")

  val MAX_PARTITION_PER_WRITERS_SESSION =
    buildConf("spark.gluten.sql.columnar.backend.velox.maxPartitionsPerWritersSession")
      .internal()
      .doc("Maximum number of partitions per a single table writer instance.")
      .intConf
      .checkValue(_ > 0, "must be a positive number")
      .createWithDefault(10000)

  val COLUMNAR_VELOX_RESIZE_BATCHES_SHUFFLE_INPUT =
    buildConf("spark.gluten.sql.columnar.backend.velox.resizeBatches.shuffleInput")
      .internal()
      .doc(s"If true, combine small columnar batches together before sending to shuffle. " +
        s"The default minimum output batch size is equal to 0.8 * $GLUTEN_MAX_BATCH_SIZE_KEY")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_VELOX_RESIZE_BATCHES_SHUFFLE_INPUT_MIN_SIZE =
    buildConf("spark.gluten.sql.columnar.backend.velox.resizeBatches.shuffleInput.minSize")
      .internal()
      .doc(
        s"The minimum batch size for shuffle. If size of an input batch is " +
          s"smaller than the value, it will be combined with other " +
          s"batches before sending to shuffle. Only functions when " +
          s"${COLUMNAR_VELOX_RESIZE_BATCHES_SHUFFLE_INPUT.key} is set to true. " +
          s"Default value: 0.25 * <max batch size>")
      .intConf
      .createOptional

  val COLUMNAR_CH_SHUFFLE_SPILL_THRESHOLD =
    buildConf("spark.gluten.sql.columnar.backend.ch.spillThreshold")
      .internal()
      .doc("Shuffle spill threshold on ch backend")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("0MB")

  val COLUMNAR_CH_MAX_SORT_BUFFER_SIZE =
    buildConf("spark.gluten.sql.columnar.backend.ch.maxSortBufferSize")
      .internal()
      .doc("The maximum size of sort shuffle buffer in CH backend.")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("0")

  val COLUMNAR_CH_FORCE_MEMORY_SORT_SHUFFLE =
    buildConf("spark.gluten.sql.columnar.backend.ch.forceMemorySortShuffle")
      .internal()
      .doc("Whether to force to use memory sort shuffle in CH backend. ")
      .booleanConf
      .createWithDefault(false)

  val TRANSFORM_PLAN_LOG_LEVEL =
    buildConf("spark.gluten.sql.transform.logLevel")
      .internal()
      .stringConf
      .transform(_.toUpperCase(Locale.ROOT))
      .checkValue(
        logLevel => Set("TRACE", "DEBUG", "INFO", "WARN", "ERROR").contains(logLevel),
        "Valid values are 'trace', 'debug', 'info', 'warn' and 'error'.")
      .createWithDefault("DEBUG")

  val SUBSTRAIT_PLAN_LOG_LEVEL =
    buildConf("spark.gluten.sql.substrait.plan.logLevel")
      .internal()
      .stringConf
      .transform(_.toUpperCase(Locale.ROOT))
      .checkValue(
        logLevel => Set("TRACE", "DEBUG", "INFO", "WARN", "ERROR").contains(logLevel),
        "Valid values are 'trace', 'debug', 'info', 'warn' and 'error'.")
      .createWithDefault("DEBUG")

  val VALIDATION_LOG_LEVEL =
    buildConf("spark.gluten.sql.validation.logLevel")
      .internal()
      .stringConf
      .transform(_.toUpperCase(Locale.ROOT))
      .checkValue(
        logLevel => Set("TRACE", "DEBUG", "INFO", "WARN", "ERROR").contains(logLevel),
        "Valid values are 'trace', 'debug', 'info', 'warn' and 'error'.")
      .createWithDefault("WARN")

  val VALIDATION_PRINT_FAILURE_STACK_ =
    buildConf("spark.gluten.sql.validation.printStackOnFailure")
      .internal()
      .booleanConf
      .createWithDefault(false)

  val SOFT_AFFINITY_LOG_LEVEL =
    buildConf("spark.gluten.soft-affinity.logLevel")
      .internal()
      .stringConf
      .transform(_.toUpperCase(Locale.ROOT))
      .checkValue(
        logLevel => Set("TRACE", "DEBUG", "INFO", "WARN", "ERROR").contains(logLevel),
        "Valid values are 'trace', 'debug', 'info', 'warn' and 'error'.")
      .createWithDefault("DEBUG")

  val DEBUG_ENABLED =
    buildConf(GLUTEN_DEBUG_MODE)
      .internal()
      .booleanConf
      .createWithDefault(false)

  val DEBUG_KEEP_JNI_WORKSPACE =
    buildStaticConf(GLUTEN_DEBUG_KEEP_JNI_WORKSPACE)
      .internal()
      .booleanConf
      .createWithDefault(false)

  val DEBUG_KEEP_JNI_WORKSPACE_DIR =
    buildStaticConf(GLUTEN_DEBUG_KEEP_JNI_WORKSPACE_DIR)
      .internal()
      .stringConf
      .createWithDefault("/tmp")

  val BENCHMARK_TASK_STAGEID =
    buildConf("spark.gluten.sql.benchmark_task.stageId")
      .internal()
      .intConf
      .createWithDefault(-1)

  val BENCHMARK_TASK_PARTITIONID =
    buildConf("spark.gluten.sql.benchmark_task.partitionId")
      .internal()
      .intConf
      .createWithDefault(-1)

  val BENCHMARK_TASK_TASK_ID =
    buildConf("spark.gluten.sql.benchmark_task.taskId")
      .internal()
      .longConf
      .createWithDefault(-1L)

  val NATIVE_WRITER_ENABLED =
    buildConf("spark.gluten.sql.native.writer.enabled")
      .internal()
      .doc("This is config to specify whether to enable the native columnar parquet/orc writer")
      .booleanConf
      .createOptional

  val NATIVE_HIVEFILEFORMAT_WRITER_ENABLED =
    buildConf("spark.gluten.sql.native.hive.writer.enabled")
      .internal()
      .doc(
        "This is config to specify whether to enable the native columnar writer for " +
          "HiveFileFormat. Currently only supports HiveFileFormat with Parquet as the output " +
          "file type.")
      .booleanConf
      .createWithDefault(true)

  val NATIVE_ARROW_READER_ENABLED =
    buildConf("spark.gluten.sql.native.arrow.reader.enabled")
      .internal()
      .doc("This is config to specify whether to enable the native columnar csv reader")
      .booleanConf
      .createWithDefault(false)

  val NATIVE_WRITE_FILES_COLUMN_METADATA_EXCLUSION_LIST =
    buildConf("spark.gluten.sql.native.writeColumnMetadataExclusionList")
      .doc(
        "Native write files does not support column metadata. Metadata in list would be " +
          "removed to support native write files. Multiple values separated by commas.")
      .stringConf
      .createWithDefault("comment")

  val REMOVE_NATIVE_WRITE_FILES_SORT_AND_PROJECT =
    buildConf("spark.gluten.sql.removeNativeWriteFilesSortAndProject")
      .internal()
      .doc("When true, Gluten will remove the vanilla Spark V1Writes added sort and project " +
        "for velox backend.")
      .booleanConf
      .createWithDefault(true)

  val UT_STATISTIC =
    buildConf("spark.gluten.sql.ut.statistic")
      .internal()
      .booleanConf
      .createWithDefault(false)

  // FIXME: This only works with CH backend.
  val EXTENDED_COLUMNAR_TRANSFORM_RULES =
    buildConf("spark.gluten.sql.columnar.extended.columnar.transform.rules")
      .withAlternative("spark.gluten.sql.columnar.extended.columnar.pre.rules")
      .doc("A comma-separated list of classes for the extended columnar transform rules.")
      .stringConf
      .createWithDefaultString("")

  // FIXME: This only works with CH backend.
  val EXTENDED_COLUMNAR_POST_RULES =
    buildConf("spark.gluten.sql.columnar.extended.columnar.post.rules")
      .doc("A comma-separated list of classes for the extended columnar post rules.")
      .stringConf
      .createWithDefaultString("")

  // FIXME: This only works with CH backend.
  val EXTENDED_EXPRESSION_TRAN_CONF =
    buildConf(GLUTEN_EXTENDED_EXPRESSION_TRAN_CONF)
      .doc("A class for the extended expressions transformer.")
      .stringConf
      .createWithDefaultString("")

  val EXPRESSION_BLACK_LIST =
    buildConf("spark.gluten.expression.blacklist")
      .doc("A black list of expression to skip transform, multiple values separated by commas.")
      .stringConf
      .createOptional

  val FALLBACK_REGEXP_EXPRESSIONS =
    buildConf("spark.gluten.sql.fallbackRegexpExpressions")
      .doc(
        "If true, fall back all regexp expressions. There are a few incompatible cases" +
          " between RE2 (used by native engine) and java.util.regex (used by Spark). User should" +
          " enable this property if their incompatibility is intolerable.")
      .booleanConf
      .createWithDefault(false)

  val FALLBACK_REPORTER_ENABLED =
    buildConf("spark.gluten.sql.columnar.fallbackReporter")
      .doc("When true, enable fallback reporter rule to print fallback reason")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_VELOX_ENABLE_USER_EXCEPTION_STACKTRACE =
    buildConf("spark.gluten.sql.columnar.backend.velox.enableUserExceptionStacktrace")
      .internal()
      .doc("Enable the stacktrace for user type of VeloxException")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_VELOX_SHOW_TASK_METRICS_WHEN_FINISHED =
    buildConf("spark.gluten.sql.columnar.backend.velox.showTaskMetricsWhenFinished")
      .internal()
      .doc("Show velox full task metrics when finished.")
      .booleanConf
      .createWithDefault(false)

  val COLUMNAR_VELOX_MEMORY_USE_HUGE_PAGES =
    buildConf("spark.gluten.sql.columnar.backend.velox.memoryUseHugePages")
      .internal()
      .doc("Use explicit huge pages for Velox memory allocation.")
      .booleanConf
      .createWithDefault(false)

  val COLUMNAR_VELOX_ENABLE_SYSTEM_EXCEPTION_STACKTRACE =
    buildConf("spark.gluten.sql.columnar.backend.velox.enableSystemExceptionStacktrace")
      .internal()
      .doc("Enable the stacktrace for system type of VeloxException")
      .booleanConf
      .createWithDefault(true)

  val TEXT_INPUT_ROW_MAX_BLOCK_SIZE =
    buildConf("spark.gluten.sql.text.input.max.block.size")
      .internal()
      .doc("the max block size for text input rows")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("8KB");

  val TEXT_INPUT_EMPTY_AS_DEFAULT =
    buildConf("spark.gluten.sql.text.input.empty.as.default")
      .internal()
      .doc("treat empty fields in CSV input as default values.")
      .booleanConf
      .createWithDefault(false);

  val ENABLE_PARQUET_ROW_GROUP_MAX_MIN_INDEX =
    buildConf("spark.gluten.sql.parquet.maxmin.index")
      .internal()
      .doc("Enable row group max min index for parquet file scan")
      .booleanConf
      .createWithDefault(false)

  val VELOX_FLUSHABLE_PARTIAL_AGGREGATION_ENABLED =
    buildConf("spark.gluten.sql.columnar.backend.velox.flushablePartialAggregation")
      .internal()
      .doc(
        "Enable flushable aggregation. If true, Gluten will try converting regular aggregation " +
          "into Velox's flushable aggregation when applicable. A flushable aggregation could " +
          "emit intermediate result at anytime when memory is full / data reduction ratio is low."
      )
      .booleanConf
      .createWithDefault(true)

  val MAX_PARTIAL_AGGREGATION_MEMORY_RATIO =
    buildConf("spark.gluten.sql.columnar.backend.velox.maxPartialAggregationMemoryRatio")
      .internal()
      .doc(
        "Set the max memory of partial aggregation as "
          + "maxPartialAggregationMemoryRatio of offheap size. Note: this option only works when " +
          "flushable partial aggregation is enabled. Ignored when " +
          "spark.gluten.sql.columnar.backend.velox.flushablePartialAggregation=false."
      )
      .doubleConf
      .createWithDefault(0.1)

  val MAX_EXTENDED_PARTIAL_AGGREGATION_MEMORY_RATIO =
    buildConf("spark.gluten.sql.columnar.backend.velox.maxExtendedPartialAggregationMemoryRatio")
      .internal()
      .doc(
        "Set the max extended memory of partial aggregation as "
          + "maxExtendedPartialAggregationMemoryRatio of offheap size. Note: this option only " +
          "works when flushable partial aggregation is enabled. Ignored when " +
          "spark.gluten.sql.columnar.backend.velox.flushablePartialAggregation=false."
      )
      .doubleConf
      .createWithDefault(0.15)

  val ABANDON_PARTIAL_AGGREGATION_MIN_PCT =
    buildConf("spark.gluten.sql.columnar.backend.velox.abandonPartialAggregationMinPct")
      .internal()
      .doc(
        "If partial aggregation input rows number greater than this value, "
          + " partial aggregation may be early abandoned. Note: this option only works when " +
          "flushable partial aggregation is enabled. Ignored when " +
          "spark.gluten.sql.columnar.backend.velox.flushablePartialAggregation=false.")
      .intConf
      .createWithDefault(90)

  val ABANDON_PARTIAL_AGGREGATION_MIN_ROWS =
    buildConf("spark.gluten.sql.columnar.backend.velox.abandonPartialAggregationMinRows")
      .internal()
      .doc(
        "If partial aggregation aggregationPct greater than this value, "
          + "partial aggregation may be early abandoned. Note: this option only works when " +
          "flushable partial aggregation is enabled. Ignored when " +
          "spark.gluten.sql.columnar.backend.velox.flushablePartialAggregation=false.")
      .intConf
      .createWithDefault(100000)

  val ENABLE_REWRITE_DATE_TIMESTAMP_COMPARISON =
    buildConf("spark.gluten.sql.rewrite.dateTimestampComparison")
      .internal()
      .doc("Rewrite the comparision between date and timestamp to timestamp comparison."
        + "For example `from_unixtime(ts) > date` will be rewritten to `ts > to_unixtime(date)`")
      .booleanConf
      .createWithDefault(true)

  val ENABLE_CH_REWRITE_DATE_CONVERSION =
    buildConf("spark.gluten.sql.columnar.backend.ch.rewrite.dateConversion")
      .internal()
      .doc(
        "Rewrite the conversion between date and string."
          + "For example `to_date(from_unixtime(unix_timestamp(stringType, 'yyyyMMdd')))`"
          + " will be rewritten to `to_date(stringType)`")
      .booleanConf
      .createWithDefault(true)

  val ENABLE_COLUMNAR_PROJECT_COLLAPSE =
    buildConf("spark.gluten.sql.columnar.project.collapse")
      .internal()
      .doc("Combines two columnar project operators into one and perform alias substitution")
      .booleanConf
      .createWithDefault(true)

  val ENABLE_COMMON_SUBEXPRESSION_ELIMINATE =
    buildConf("spark.gluten.sql.commonSubexpressionEliminate")
      .internal()
      .doc(
        "Eliminate common subexpressions in logical plan to avoid multiple evaluation of the same"
          + "expression, may improve performance")
      .booleanConf
      .createWithDefault(true)

  val ENABLE_COUNT_DISTINCT_WITHOUT_EXPAND =
    buildConf("spark.gluten.sql.countDistinctWithoutExpand")
      .internal()
      .doc(
        "Convert Count Distinct to a UDAF called count_distinct to " +
          "prevent SparkPlanner converting it to Expand+Count. WARNING: " +
          "When enabled, count distinct queries will fail to fallback!!!")
      .booleanConf
      .createWithDefault(false)

  val COLUMNAR_VELOX_BLOOM_FILTER_EXPECTED_NUM_ITEMS =
    buildConf("spark.gluten.sql.columnar.backend.velox.bloomFilter.expectedNumItems")
      .internal()
      .doc("The default number of expected items for the velox bloomfilter: " +
        "'spark.bloom_filter.expected_num_items'")
      .longConf
      .createWithDefault(1000000L)

  val COLUMNAR_VELOX_BLOOM_FILTER_NUM_BITS =
    buildConf("spark.gluten.sql.columnar.backend.velox.bloomFilter.numBits")
      .internal()
      .doc("The default number of bits to use for the velox bloom filter: " +
        "'spark.bloom_filter.num_bits'")
      .longConf
      .createWithDefault(8388608L)

  val COLUMNAR_VELOX_BLOOM_FILTER_MAX_NUM_BITS =
    buildConf("spark.gluten.sql.columnar.backend.velox.bloomFilter.maxNumBits")
      .internal()
      .doc("The max number of bits to use for the velox bloom filter: " +
        "'spark.bloom_filter.max_num_bits'")
      .longConf
      .createWithDefault(4194304L)

  val COLUMNAR_VELOX_FILE_HANDLE_CACHE_ENABLED =
    buildStaticConf("spark.gluten.sql.columnar.backend.velox.fileHandleCacheEnabled")
      .internal()
      .doc("Disables caching if false. File handle cache should be disabled " +
        "if files are mutable, i.e. file content may change while file path stays the same.")
      .booleanConf
      .createWithDefault(false)

  val CARTESIAN_PRODUCT_TRANSFORMER_ENABLED =
    buildConf("spark.gluten.sql.cartesianProductTransformerEnabled")
      .internal()
      .doc("Config to enable CartesianProductExecTransformer.")
      .booleanConf
      .createWithDefault(true)

  val BROADCAST_NESTED_LOOP_JOIN_TRANSFORMER_ENABLED =
    buildConf("spark.gluten.sql.broadcastNestedLoopJoinTransformerEnabled")
      .internal()
      .doc("Config to enable BroadcastNestedLoopJoinExecTransformer.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_SAMPLE_ENABLED =
    buildConf("spark.gluten.sql.columnarSampleEnabled")
      .internal()
      .doc("Disable or enable columnar sample.")
      .booleanConf
      .createWithDefault(false)

  val CACHE_WHOLE_STAGE_TRANSFORMER_CONTEXT =
    buildConf("spark.gluten.sql.cacheWholeStageTransformerContext")
      .internal()
      .doc("When true, `WholeStageTransformer` will cache the `WholeStageTransformerContext` " +
        "when executing. It is used to get substrait plan node and native plan string.")
      .booleanConf
      .createWithDefault(false)

  val INJECT_NATIVE_PLAN_STRING_TO_EXPLAIN =
    buildConf("spark.gluten.sql.injectNativePlanStringToExplain")
      .internal()
      .doc("When true, Gluten will inject native plan tree to explain string inside " +
        "`WholeStageTransformerContext`.")
      .booleanConf
      .createWithDefault(false)

  val DIRECTORY_SIZE_GUESS =
    buildStaticConf("spark.gluten.sql.columnar.backend.velox.directorySizeGuess")
      .internal()
      .doc("Set the directory size guess for velox file scan")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("32KB")

  val FILE_PRELOAD_THRESHOLD =
    buildStaticConf("spark.gluten.sql.columnar.backend.velox.filePreloadThreshold")
      .internal()
      .doc("Set the file preload threshold for velox file scan")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("1MB")

  val PREFETCH_ROW_GROUPS =
    buildStaticConf("spark.gluten.sql.columnar.backend.velox.prefetchRowGroups")
      .internal()
      .doc("Set the prefetch row groups for velox file scan")
      .intConf
      .createWithDefault(1)

  val LOAD_QUANTUM =
    buildStaticConf("spark.gluten.sql.columnar.backend.velox.loadQuantum")
      .internal()
      .doc("Set the load quantum for velox file scan")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("256MB")

  val MAX_COALESCED_DISTANCE_BYTES =
    buildStaticConf("spark.gluten.sql.columnar.backend.velox.maxCoalescedDistanceBytes")
      .internal()
      .doc(" Set the max coalesced distance bytes for velox file scan")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("1MB")

  val MAX_COALESCED_BYTES =
    buildStaticConf("spark.gluten.sql.columnar.backend.velox.maxCoalescedBytes")
      .internal()
      .doc("Set the max coalesced bytes for velox file scan")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("64MB")

  val CACHE_PREFETCH_MINPCT =
    buildStaticConf("spark.gluten.sql.columnar.backend.velox.cachePrefetchMinPct")
      .internal()
      .doc("Set prefetch cache min pct for velox file scan")
      .intConf
      .createWithDefault(0)

  val AWS_SDK_LOG_LEVEL =
    buildConf("spark.gluten.velox.awsSdkLogLevel")
      .internal()
      .doc("Log granularity of AWS C++ SDK in velox.")
      .stringConf
      .createWithDefault("FATAL")

  val AWS_S3_RETRY_MODE =
    buildConf("spark.gluten.velox.fs.s3a.retry.mode")
      .internal()
      .doc("Retry mode for AWS s3 connection error: legacy, standard and adaptive.")
      .stringConf
      .createWithDefault("legacy")

  val AWS_S3_CONNECT_TIMEOUT =
    buildConf("spark.gluten.velox.fs.s3a.connect.timeout")
      .internal()
      .doc("Timeout for AWS s3 connection.")
      .stringConf
      .createWithDefault("200s")

  val VELOX_ORC_SCAN_ENABLED =
    buildStaticConf("spark.gluten.sql.columnar.backend.velox.orc.scan.enabled")
      .internal()
      .doc("Enable velox orc scan. If disabled, vanilla spark orc scan will be used.")
      .booleanConf
      .createWithDefault(true)

  val VELOX_FORCE_COMPLEX_TYPE_SCAN_FALLBACK =
    buildConf("spark.gluten.sql.complexType.scan.fallback.enabled")
      .internal()
      .doc("Force fallback for complex type scan, including struct, map, array.")
      .booleanConf
      .createWithDefault(true)

  val VELOX_FORCE_ORC_CHAR_TYPE_SCAN_FALLBACK =
    buildConf("spark.gluten.sql.orc.charType.scan.fallback.enabled")
      .internal()
      .doc("Force fallback for orc char type scan.")
      .booleanConf
      .createWithDefault(true)

  val VELOX_FORCE_PARQUET_TIMESTAMP_TYPE_SCAN_FALLBACK =
    buildConf("spark.gluten.sql.parquet.timestampType.scan.fallback.enabled")
      .internal()
      .doc("Force fallback for parquet timestamp type scan.")
      .booleanConf
      .createWithDefault(false)

  val VELOX_SCAN_FILE_SCHEME_VALIDATION_ENABLED =
    buildConf("spark.gluten.sql.scan.fileSchemeValidation.enabled")
      .internal()
      .doc(
        "When true, enable file path scheme validation for scan. Validation will fail if" +
          " file scheme is not supported by registered file systems, which will cause scan " +
          " operator fall back.")
      .booleanConf
      .createWithDefault(true)

  val COLUMNAR_NATIVE_CAST_AGGREGATE_ENABLED =
    buildConf("spark.gluten.sql.columnar.cast.avg")
      .internal()
      .booleanConf
      .createWithDefault(true)

  val COST_EVALUATOR_ENABLED =
    buildConf(GlutenConfig.GLUTEN_COST_EVALUATOR_ENABLED)
      .internal()
      .doc(
        "If true and gluten enabled, use " +
          "org.apache.spark.sql.execution.adaptive.GlutenCostEvaluator as custom cost " +
          "evaluator class, else follow the configuration " +
          "spark.sql.adaptive.customCostEvaluatorClass.")
      .booleanConf
      .createWithDefault(true)

  val DYNAMIC_OFFHEAP_SIZING_ENABLED =
    buildConf(GlutenConfig.GLUTEN_DYNAMIC_OFFHEAP_SIZING_ENABLED)
      .internal()
      .doc(
        "Experimental: When set to true, the offheap config (spark.memory.offHeap.size) will " +
          "be ignored and instead we will consider onheap and offheap memory in combination, " +
          "both counting towards the executor memory config (spark.executor.memory). We will " +
          "make use of JVM APIs to determine how much onheap memory is use, alongside tracking " +
          "offheap allocations made by Gluten. We will then proceed to enforcing a total memory " +
          "quota, calculated by the sum of what memory is committed and in use in the Java " +
          "heap. Since the calculation of the total quota happens as offheap allocation happens " +
          "and not as JVM heap memory is allocated, it is possible that we can oversubscribe " +
          "memory. Additionally, note that this change is experimental and may have performance " +
          "implications.")
      .booleanConf
      .createWithDefault(false)

  val DYNAMIC_OFFHEAP_SIZING_MEMORY_FRACTION =
    buildConf(GlutenConfig.GLUTEN_DYNAMIC_OFFHEAP_SIZING_MEMORY_FRACTION)
      .internal()
      .doc(
        "Experimental: Determines the memory fraction used to determine the total " +
          "memory available for offheap and onheap allocations when the dynamic offheap " +
          "sizing feature is enabled. The default is set to match spark.executor.memoryFraction.")
      .doubleConf
      .checkValue(v => v >= 0 && v <= 1, "offheap sizing memory fraction must between [0, 1]")
      .createWithDefault(0.6)

  val CELEBORN_FALLBACK_ENABLED =
    buildStaticConf("spark.gluten.sql.columnar.shuffle.celeborn.fallback.enabled")
      .internal()
      .doc("If enabled, fall back to ColumnarShuffleManager when celeborn service is unavailable." +
        "Otherwise, throw an exception.")
      .booleanConf
      .createWithDefault(true)
}
