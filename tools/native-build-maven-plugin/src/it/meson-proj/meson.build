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
project('fxnative', 'c', version : '1.0')
inc = include_directories('include')
core = static_library('fxcore', 'src/core.c', include_directories : inc, c_args : '-DFX_BUILD=1', install : true)
tool = executable('fxtool', 'src/main.c', include_directories : inc, link_with : core, install : true)
install_headers('include/fx.h')
test('adds', tool, args : ['2', '3', '5'])
test('rejects_wrong_sum', tool, args : ['2', '2', '5'], should_fail : true)
