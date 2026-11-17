/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.clustering.clang;

/**
 * A location from clang's JSON dump, reduced to where the text is: the expansion location when the dump gives a
 * spelling and an expansion location, else the location itself.
 *
 * @param file   the file name as clang printed it (the dump leaves it out when it repeats; the reader fills it in)
 * @param offset the byte offset in that file
 * @param tokLen the length in bytes of the token at the location
 * @param macro  true when the location came from a macro expansion; the offset is then the expansion's start
 */
record AstLoc(String file, int offset, int tokLen, boolean macro) {
    /** The byte offset just after the token. */
    int end() {
        return offset + tokLen;
    }
}
