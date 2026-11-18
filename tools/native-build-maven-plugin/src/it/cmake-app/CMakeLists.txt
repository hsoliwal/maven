# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
# Consumes cmake-lib's zip: the plugin unpacks it and passes it as CMAKE_PREFIX_PATH, so find_path and find_library
# see its include and lib folders without any path written here.
cmake_minimum_required(VERSION 3.21)
project(fxapp C)
find_path(FX_INCLUDE fx.h REQUIRED)
find_library(FX_CORE NAMES fxcore REQUIRED)
message(STATUS "fx.h from ${FX_INCLUDE}, fxcore from ${FX_CORE}")
add_executable(fxapp src/app.c)
target_include_directories(fxapp PRIVATE ${FX_INCLUDE})
target_link_libraries(fxapp ${FX_CORE})
enable_testing()
add_test(NAME uses_fxcore COMMAND fxapp)
install(TARGETS fxapp)
