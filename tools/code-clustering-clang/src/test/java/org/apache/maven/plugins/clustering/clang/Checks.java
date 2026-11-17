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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/** Pass/fail bookkeeping for the test mains; a failure makes the main exit with status 1. */
final class Checks {
    private int passed;
    private int failed;

    void check(String name, boolean ok) {
        if (ok) passed++;
        else failed++;
        System.err.println((ok ? "PASS " : "FAIL ") + name);
    }

    void end(String suite) {
        System.err.println(suite + ": " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static Path write(Path dir, String name, String text) throws IOException {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    static Atom find(List<Atom> atoms, String path, String symbol) {
        return atoms.stream().filter(a -> a.path().equals(path) && a.symbol().equals(symbol)).findFirst().orElse(null);
    }

    static long count(List<Atom> atoms, String symbol) {
        return atoms.stream().filter(a -> a.symbol().equals(symbol)).count();
    }
}
