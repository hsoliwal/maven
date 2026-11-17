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
cmake_minimum_required(VERSION 3.21)
project(fxnative C)
add_library(fxcore STATIC src/core.c)
target_include_directories(fxcore PUBLIC $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include> $<INSTALL_INTERFACE:include>)
target_compile_definitions(fxcore PRIVATE FX_BUILD=1)
add_library(fxshared SHARED src/core.c)
target_include_directories(fxshared PUBLIC $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include> $<INSTALL_INTERFACE:include>)
target_compile_definitions(fxshared PRIVATE FX_SHARED_BUILD=1)
add_executable(fxtool src/main.c)
target_link_libraries(fxtool fxcore)
enable_testing()
add_test(NAME adds COMMAND fxtool 2 3 5)
add_test(NAME rejects_wrong_sum COMMAND fxtool 2 2 5)
set_tests_properties(rejects_wrong_sum PROPERTIES WILL_FAIL TRUE)
install(TARGETS fxcore fxshared fxtool)
install(DIRECTORY include/ DESTINATION include)
