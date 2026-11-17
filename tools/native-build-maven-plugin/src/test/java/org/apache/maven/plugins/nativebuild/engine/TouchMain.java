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
package org.apache.maven.plugins.nativebuild.engine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A stand-in native tool started by the incremental contract: appends one line to a counter file and writes the
 * output file; {@code fail} as first argument exits 3, {@code none} writes no output.
 */
public final class TouchMain {
    private TouchMain() {}

    public static void main(String[] args) throws Exception {
        if ("fail".equals(args[0])) {
            System.exit(3);
        }
        Path counter = Path.of("runs.txt");
        Files.writeString(counter, (Files.exists(counter) ? Files.readString(counter) : "") + "run\n",
                StandardCharsets.UTF_8);
        if (!"none".equals(args[0])) {
            Path output = Path.of(args[0]);
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output, "built\n", StandardCharsets.UTF_8);
        }
    }
}
