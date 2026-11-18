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
import java.nio.file.StandardOpenOption;

/**
 * A stand-in native tool for the stamp contracts: {@code ModeMain <tag> <output> <stamp>} appends a line to
 * {@code runs-<tag>.txt}, records in {@code seen-<tag>.txt} whether {@code <stamp>} existed when it started, writes
 * {@code <output>}, and exits 3 when {@code mode.txt} holds {@code fail}.
 */
public final class ModeMain {
    private ModeMain() {}

    public static void main(String[] args) throws Exception {
        append(Path.of("runs-" + args[0] + ".txt"), "run");
        append(Path.of("seen-" + args[0] + ".txt"), Files.exists(Path.of(args[2])) ? "present" : "absent");
        Path output = Path.of(args[1]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, "built\n", StandardCharsets.UTF_8);
        Path mode = Path.of("mode.txt");
        if (Files.exists(mode) && Files.readString(mode, StandardCharsets.UTF_8).strip().equals("fail")) {
            System.exit(3);
        }
    }

    private static void append(Path file, String line) throws Exception {
        Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }
}
