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
package org.apache.maven.plugins.projectdb;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** The one dispatch from a source kind to its reader. */
final class Parsers {
    private Parsers() {}

    static List<Chunk> parse(Path root, Source source) throws IOException {
        return switch (source.kind()) {
            case "surefire" -> SurefireParser.parse(root, source);
            case "pom" -> BuildDataParsers.pom(source);
            case "dependency-tree" -> BuildDataParsers.dependencyTree(source);
            case "build-log" -> BuildDataParsers.buildLog(root, source);
            case "events" -> BuildDataParsers.events(source);
            case "clustering" -> CodeStateParsers.clustering(root, source);
            case "polyglot" -> CodeStateParsers.polyglot(root, source);
            default -> throw new IOException("no reader for source kind " + source.kind());
        };
    }
}
