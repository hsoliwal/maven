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

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Counts and prints contract checks; one PASS or FAIL line each. */
final class Checks {
    private final PrintStream out;
    private final Path scratch;
    private int passed;
    private int failed;

    Checks(PrintStream out, Path scratch) {
        this.out = out;
        this.scratch = scratch;
    }

    void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        out.println((ok ? "PASS " : "FAIL ") + name);
    }

    void equal(String name, Object expected, Object actual) {
        boolean ok = Objects.equals(expected, actual);
        check(ok ? name : name + "\n     expected: " + expected + "\n     actual:   " + actual, ok);
    }

    /** Runs {@code body} and checks that it throws {@code type}. */
    void fails(String name, Class<? extends Throwable> type, ThrowingRunnable body) {
        try {
            body.run();
            check(name + " (no exception)", false);
        } catch (Throwable thrown) {
            check(name + (type.isInstance(thrown) ? "" : " (got " + thrown + ")"), type.isInstance(thrown));
        }
    }

    /** A fresh directory below the scratch root holding {@code files} (path to content). */
    Path dir(String name, Map<String, String> files) throws IOException {
        Path dir = scratch.resolve(name);
        Files.createDirectories(dir);
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path path = dir.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue(), StandardCharsets.UTF_8);
        }
        return dir;
    }

    int passed() {
        return passed;
    }

    int failed() {
        return failed;
    }

    /** A check body that may throw. */
    interface ThrowingRunnable {
        void run() throws Exception;
    }
}
