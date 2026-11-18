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
package org.apache.maven.extensions.clustering;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The inline tables of an execution the spy leaves alone, whatever the reason (the off switch included): they would
 * stay as if this build had written them, and a later build that finds the classes up to date would not replace them.
 * So every file directly in the execution's table directory and in its {@code generated} directory goes, with the
 * directories an interrupted write left ({@value #WRITING}...); the {@code test} and other executions' directories
 * below it stay. An execution that compiles stale sources only merges its next compilation into the tables it finds,
 * and finds none now, so its directory is marked {@value #INCOMPLETE} (the javac plugin keeps the marker through such
 * merges and removes it when a full compilation replaces the set).
 */
final class StaleTables {
    /** The marker the javac plugin reads ({@code InlineClustering.INCOMPLETE}). */
    static final String INCOMPLETE = "INCOMPLETE.txt";
    /** The prefix of the directory the javac plugin writes a set into before it moves the tables into place. */
    static final String WRITING = ".tables-";
    private static final String GENERATED = "generated";
    private static final String MARK = "These tables hold only the sources compiled since the tables were removed"
            + " (a compilation that could not write them, or an execution the core extension left alone). A full"
            + " compilation (mvn clean compile) writes the whole set again.\n";

    private StaleTables() { }

    /** Removes the tables in {@code out} and {@code out/generated}; marks {@code out} when partial. Whether any went. */
    static boolean remove(Path out, boolean partial) throws IOException {
        boolean removed = removeFiles(out) | removeFiles(out.resolve(GENERATED));
        if (removed && partial) Files.writeString(out.resolve(INCOMPLETE), MARK, StandardCharsets.UTF_8);
        return removed;
    }

    /** Deletes the files directly in {@code dir} but the marker, and the write directories; whether any went. */
    private static boolean removeFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return false;
        List<Path> found;
        try (Stream<Path> listed = Files.list(dir)) {
            found = listed.filter(p -> Files.isRegularFile(p) && !p.getFileName().toString().equals(INCOMPLETE)
                    || p.getFileName().toString().startsWith(WRITING)).collect(Collectors.toList());
        }
        for (Path p : found) delete(p);
        return !found.isEmpty();
    }

    private static void delete(Path path) throws IOException {
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.deleteIfExists(p);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
