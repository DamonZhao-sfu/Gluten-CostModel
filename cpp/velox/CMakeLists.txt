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

cmake_minimum_required(VERSION 3.16)

project(velox)

include(ExternalProject)
include(FindPkgConfig)
include(GNUInstallDirs)
include(CheckCXXCompilerFlag)
include(FindPackageHandleStandardArgs)

set(CMAKE_CXX_FLAGS
    "${CMAKE_CXX_FLAGS} -Wno-deprecated-declarations -Wno-attributes")
if(NOT ${CMAKE_SYSTEM_NAME} STREQUAL "Darwin")
  set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wno-class-memaccess")
endif()

set(SYSTEM_LIB_PATH
    "/usr/lib"
    CACHE PATH "System Lib dir")
set(SYSTEM_LIB64_PATH
    "/usr/lib64"
    CACHE PATH "System Lib64 dir")
set(SYSTEM_LOCAL_LIB_PATH
    "/usr/local/lib"
    CACHE PATH "System Local Lib dir")
set(SYSTEM_LOCAL_LIB64_PATH
    "/usr/local/lib64"
    CACHE PATH "System Local Lib64 dir")
if(CMAKE_SYSTEM_PROCESSOR MATCHES "(x86)|(X86)|(amd64)|(AMD64)")
  set(SYSTEM_LIB_MULTIARCH_PATH
      "/usr/lib/x86_64-linux-gnu"
      CACHE PATH "System Lib MultiArch dir")
elseif(CMAKE_SYSTEM_PROCESSOR STREQUAL aarch64)
  set(SYSTEM_LIB_MULTIARCH_PATH
      "/usr/lib/aarch64-linux-gnu"
      CACHE PATH "System Lib MultiArch dir")
elseif(CMAKE_SYSTEM_PROCESSOR STREQUAL arm64)
  set(SYSTEM_LIB_MULTIARCH_PATH
      "/usr/lib"
      CACHE PATH "System Lib MultiArch dir")
else()
  message(FATAL_ERROR "Unsupported processor type: ${CMAKE_SYSTEM_PROCESSOR}")
endif()

if(NOT DEFINED VELOX_HOME)
  set(VELOX_HOME ${GLUTEN_HOME}/ep/build-velox/build/velox_ep)
  message(STATUS "Set VELOX_HOME to ${VELOX_HOME}")
endif()

# Keep same compile option with Velox.
execute_process(
  COMMAND
    bash -c
    "( source ${VELOX_HOME}/scripts/setup-helper-functions.sh && echo -n $(get_cxx_flags $ENV{CPU_TARGET}))"
  OUTPUT_VARIABLE SCRIPT_CXX_FLAGS
  RESULT_VARIABLE COMMAND_STATUS)
if(COMMAND_STATUS EQUAL "1")
  message(FATAL_ERROR "Unable to determine compiler flags!")
endif()
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${SCRIPT_CXX_FLAGS}")

message("Velox module final CMAKE_CXX_FLAGS=${CMAKE_CXX_FLAGS}")

# User can specify VELOX_BUILD_PATH, if Velox are built elsewhere.
if(NOT DEFINED VELOX_BUILD_PATH)
  if(${CMAKE_BUILD_TYPE} STREQUAL "Debug")
    set(VELOX_BUILD_PATH
        "${VELOX_HOME}/_build/debug"
        CACHE PATH "Velox build directory.")
  else()
    set(VELOX_BUILD_PATH
        "${VELOX_HOME}/_build/release"
        CACHE PATH "Velox build directory.")
  endif()
endif()

set(VELOX_COMPONENTS_PATH "${VELOX_BUILD_PATH}/velox")

function(import_library TARGET_NAME LIB_PATH)
  if(NOT EXISTS ${LIB_PATH})
    message(FATAL_ERROR "Library does not exist: ${LIB_PATH}")
  endif()
  add_library(${TARGET_NAME} STATIC IMPORTED)
  set_target_properties(${TARGET_NAME} PROPERTIES IMPORTED_LOCATION ${LIB_PATH})
endfunction()

macro(add_duckdb)
  find_package(DuckDB)
  if(NOT DuckDB_FOUND)
    message(FATAL_ERROR "Cannot find DuckDB.")
  else()
    message(STATUS "Found DuckDB library from ${DuckDB_DIR}")
    target_link_libraries(facebook::velox INTERFACE duckdb_static)
  endif()
endmacro()

macro(find_libhdfs3)
  find_package(libhdfs3 CONFIG)
  if(libhdfs3_FOUND AND TARGET HDFS::hdfs3)
    set(LIBHDFS3_LIBRARY HDFS::hdfs3)
  else()
    find_path(libhdfs3_INCLUDE_DIR hdfs/hdfs.h)
    set(CMAKE_FIND_LIBRARY_SUFFIXES ".so")
    find_library(libhdfs3_LIBRARY NAMES hdfs3)
    find_package_handle_standard_args(libhdfs3 DEFAULT_MSG libhdfs3_INCLUDE_DIR
                                      libhdfs3_LIBRARY)
    add_library(HDFS::hdfs3 SHARED IMPORTED)
    set_target_properties(
      HDFS::hdfs3
      PROPERTIES INTERFACE_INCLUDE_DIRECTORIES "${libhdfs3_INCLUDE_DIR}"
                 IMPORTED_LOCATION "${libhdfs3_LIBRARY}")
  endif()

  if(NOT libhdfs3_FOUND)
    message(FATAL_ERROR "LIBHDFS3 Library Not Found")
  endif()
endmacro()

macro(find_re2)
  find_package(re2 CONFIG)
  if(re2_FOUND AND TARGET re2::re2)
    set(RE2_LIBRARY re2::re2)
  else()
    find_library(
      RE2_LIBRARY
      NAMES re2
      PATHS ${SYSTEM_LIB_PATH} ${SYSTEM_LIB64_PATH} ${SYSTEM_LIB_MULTIARCH_PATH}
            ${SYSTEM_LOCAL_LIB_PATH} ${SYSTEM_LOCAL_LIB64_PATH}
      NO_DEFAULT_PATH)
  endif()

  if(NOT RE2_LIBRARY)
    message(FATAL_ERROR "RE2 Library Not Found")
  else()
    message(STATUS "RE2 Library Can Be Found in ${RE2_LIBRARY}")
  endif()
endmacro()

macro(find_awssdk)
  set(CMAKE_FIND_LIBRARY_SUFFIXES ".a")
  find_package(AWSSDK REQUIRED COMPONENTS s3;identity-management)
endmacro()

macro(find_gcssdk)
  set(CMAKE_FIND_LIBRARY_SUFFIXES ".so")
  find_package(google_cloud_cpp_storage REQUIRED)
endmacro()

macro(find_azure)
  find_package(CURL REQUIRED)
  find_package(LibXml2 REQUIRED)
  set(CMAKE_FIND_LIBRARY_SUFFIXES ".a")
  find_package(azure-storage-blobs-cpp CONFIG REQUIRED)
  find_package(azure-storage-files-datalake-cpp CONFIG REQUIRED)
endmacro()

# Build Velox backend.
set(VELOX_SRCS
    compute/VeloxBackend.cc
    compute/VeloxRuntime.cc
    compute/WholeStageResultIterator.cc
    compute/VeloxPlanConverter.cc
    compute/iceberg/IcebergPlanConverter.cc
    jni/VeloxJniWrapper.cc
    jni/JniFileSystem.cc
    jni/JniUdf.cc
    memory/BufferOutputStream.cc
    memory/VeloxColumnarBatch.cc
    memory/VeloxMemoryManager.cc
    operators/functions/RegistrationAllFunctions.cc
    operators/functions/RowConstructorWithNull.cc
    operators/functions/SparkTokenizer.cc
    operators/serializer/VeloxColumnarToRowConverter.cc
    operators/serializer/VeloxColumnarBatchSerializer.cc
    operators/serializer/VeloxRowToColumnarConverter.cc
    operators/writer/VeloxParquetDatasource.cc
    shuffle/VeloxShuffleReader.cc
    shuffle/VeloxShuffleWriter.cc
    shuffle/VeloxHashShuffleWriter.cc
    shuffle/VeloxSortShuffleWriter.cc
    shuffle/VeloxRssSortShuffleWriter.cc
    substrait/SubstraitParser.cc
    substrait/SubstraitToVeloxExpr.cc
    substrait/SubstraitToVeloxPlan.cc
    substrait/SubstraitToVeloxPlanValidator.cc
    substrait/VariantToVectorConverter.cc
    substrait/SubstraitExtensionCollector.cc
    substrait/VeloxSubstraitSignature.cc
    substrait/VeloxToSubstraitExpr.cc
    substrait/VeloxToSubstraitPlan.cc
    substrait/VeloxToSubstraitType.cc
    udf/UdfLoader.cc
    utils/VeloxArrowUtils.cc
    utils/ConfigExtractor.cc
    utils/Common.cc
    utils/VeloxBatchResizer.cc)

if(ENABLE_HDFS)
  list(APPEND VELOX_SRCS utils/HdfsUtils.cc)
endif()

if(ENABLE_S3)
  find_package(ZLIB)
endif()

if(BUILD_TESTS OR BUILD_BENCHMARKS)
  list(APPEND VELOX_SRCS utils/tests/MemoryPoolUtils.cc)
endif()

add_library(velox SHARED ${VELOX_SRCS})

if(ENABLE_GLUTEN_VCPKG)
  # Hide symbols of static dependencies
  target_link_options(
    velox PRIVATE -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/symbols.map)
endif()

target_include_directories(
  velox
  PUBLIC ${CMAKE_SYSTEM_INCLUDE_PATH}
         ${JNI_INCLUDE_DIRS}
         ${CMAKE_CURRENT_SOURCE_DIR}
         ${VELOX_HOME}/
         ${VELOX_BUILD_PATH}/
         ${VELOX_BUILD_PATH}/_deps/xsimd-src/include/
         ${VELOX_HOME}/third_party/xsimd/include/)

set_target_properties(velox PROPERTIES LIBRARY_OUTPUT_DIRECTORY
                                       ${root_directory}/releases)

# If folly is not installed in system lib paths, please add
# `-DCMAKE_PREFIX_PATH="${folly lib path}" to cmake arguments. It is also
# applicable to other dependencies.
find_package(Folly REQUIRED CONFIG)

target_include_directories(velox PUBLIC ${GTEST_INCLUDE_DIRS}
                                        ${PROTOBUF_INCLUDE})

if(BUILD_JEMALLOC)
  include(Findjemalloc_pic)
  find_jemalloc()
  if(JEMALLOC_NOT_FOUND)
    include(Buildjemalloc_pic)
    build_jemalloc()
  endif()
  add_definitions(-DENABLE_JEMALLOC)
  target_link_libraries(velox PUBLIC jemalloc::libjemalloc)
endif()

target_link_libraries(velox PUBLIC gluten)

# Requires VELOX_MONO_LIBRARY=ON when building Velox.
import_library(facebook::velox ${VELOX_BUILD_PATH}/lib/libvelox.a)

if(BUILD_TESTS)
  add_duckdb()

  import_library(facebook::velox::dbgen
                 ${VELOX_BUILD_PATH}/velox/tpch/gen/dbgen/libdbgen.a)
  target_link_libraries(facebook::velox INTERFACE facebook::velox::dbgen)

  import_library(
    facebook::velox::vector_test_lib
    ${VELOX_BUILD_PATH}/velox/vector/tests/utils/libvelox_vector_test_lib.a)
  import_library(
    facebook::velox::dwio_common_test
    ${VELOX_BUILD_PATH}/velox/dwio/common/tests/utils/libvelox_dwio_common_test_utils.a
  )
  import_library(
    facebook::velox::file_test_utils
    ${VELOX_BUILD_PATH}/velox/common/file/tests/libvelox_file_test_utils.a)
  import_library(
    facebook::velox::temp_path
    ${VELOX_BUILD_PATH}/velox/exec/tests/utils/libvelox_temp_path.a)
  import_library(
    facebook::velox::exec_test_lib
    ${VELOX_COMPONENTS_PATH}/exec/tests/utils/libvelox_exec_test_lib.a)
  target_link_libraries(
    facebook::velox::exec_test_lib
    INTERFACE facebook::velox::vector_test_lib
              facebook::velox::dwio_common_test
              facebook::velox::file_test_utils facebook::velox::temp_path)
  target_link_libraries(velox PUBLIC facebook::velox::exec_test_lib)
endif()

target_link_libraries(velox PUBLIC facebook::velox)

target_link_libraries(velox PUBLIC Folly::folly)

find_re2()
target_link_libraries(velox PUBLIC ${RE2_LIBRARY})

import_library(external::stemmer
               ${VELOX_BUILD_PATH}/_deps/libstemmer/src/libstemmer/libstemmer.a)
target_link_libraries(velox PUBLIC external::stemmer)

set(CMAKE_FIND_LIBRARY_SUFFIXES_BCK ${CMAKE_FIND_LIBRARY_SUFFIXES})
set(CMAKE_FIND_LIBRARY_SUFFIXES ".a")
find_package(simdjson CONFIG)
if(simdjson_FOUND AND TARGET simdjson::simdjson)
  target_link_libraries(velox PUBLIC simdjson::simdjson)
else()
  import_library(external::simdjson
                 ${VELOX_BUILD_PATH}/_deps/simdjson-build/libsimdjson.a)
  target_link_libraries(velox PUBLIC external::simdjson)
endif()
set(CMAKE_FIND_LIBRARY_SUFFIXES ${CMAKE_FIND_LIBRARY_SUFFIXES_BCK})

if(BUILD_TESTS)
  add_subdirectory(tests)
endif()

if(BUILD_BENCHMARKS)
  add_subdirectory(benchmarks)
endif()

if(ENABLE_HDFS)
  add_definitions(-DENABLE_HDFS)
  find_libhdfs3()
  target_link_libraries(velox PUBLIC HDFS::hdfs3)
endif()

if(ENABLE_S3)
  add_definitions(-DENABLE_S3)
  find_awssdk()
  target_link_libraries(velox PUBLIC ${AWSSDK_LIBRARIES})
endif()

if(ENABLE_GCS)
  add_definitions(-DENABLE_GCS)
  find_gcssdk()
  target_link_libraries(velox PUBLIC google-cloud-cpp::storage)
endif()

if(ENABLE_ABFS)
  add_definitions(-DENABLE_ABFS)
  find_azure()
  target_link_libraries(velox PUBLIC Azure::azure-storage-blobs)
  target_link_libraries(velox PUBLIC Azure::azure-storage-files-datalake)
endif()

if(BUILD_EXAMPLES)
  add_subdirectory(udf/examples)
endif()

add_custom_command(
  TARGET velox
  POST_BUILD
  COMMAND ld $<TARGET_FILE:velox> || true
  COMMENT "Checking ld result of libvelox.so")
add_custom_command(
  TARGET velox
  POST_BUILD
  COMMAND ldd $<TARGET_FILE:velox> || true
  COMMENT "Checking ldd result of libvelox.so")
