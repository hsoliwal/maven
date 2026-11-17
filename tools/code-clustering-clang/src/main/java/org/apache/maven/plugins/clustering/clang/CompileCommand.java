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

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * How one file is compiled: an entry of compile_commands.json, or the configured default flags.
 *
 * @param directory      the working directory of the compiler
 * @param file           the source file the entry compiles, absolute
 * @param arguments      the compiler and its arguments, as words
 * @param headerLanguage for a header that borrows another file's entry: the {@code -x} language to parse it as
 */
record CompileCommand(Path directory, Path file, List<String> arguments, Optional<String> headerLanguage) {
    CompileCommand {
        arguments = List.copyOf(arguments);
    }

    /** This entry, borrowed to parse a header. */
    CompileCommand forHeader(String language) {
        return new CompileCommand(directory, file, arguments, Optional.of(language));
    }
}
