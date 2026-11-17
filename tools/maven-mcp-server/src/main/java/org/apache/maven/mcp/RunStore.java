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
package org.apache.maven.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Run directories outside the project's target directory, so {@code mvn clean} never deletes or trips over them:
 * {@code <runsDir>/<n>} for each build or test run (the newest {@code keep} are kept) and {@code <runsDir>/query} for
 * the single output of the latest effective_pom, dependency_tree or describe_goal call.
 */
final class RunStore {

    static final String RUN_JSON = "run.json";

    private final Path runsDir;
    private final int keep;

    RunStore(Path runsDir, int keep) {
        this.runsDir = runsDir;
        this.keep = keep;
    }

    Path runsDir() {
        return runsDir;
    }

    /** Creates the next numbered run directory and removes the oldest beyond the kept count. */
    synchronized Path allocate() throws IOException {
        Files.createDirectories(runsDir);
        int next = numbers().stream().max(Integer::compare).orElse(0) + 1;
        Path dir = Files.createDirectory(runsDir.resolve(Integer.toString(next)));
        numbers().stream()
                .sorted(Comparator.reverseOrder())
                .skip(keep)
                .forEach(old -> deleteTree(runsDir.resolve(Integer.toString(old))));
        return dir;
    }

    /** An emptied directory for one query's output. */
    synchronized Path query() throws IOException {
        Path dir = runsDir.resolve("query");
        deleteTree(dir);
        return Files.createDirectories(dir);
    }

    /** The run directory for "latest" or a run number, if it still exists. */
    Optional<Path> resolve(String run) {
        Optional<Integer> number = "latest".equals(run)
                ? numbers().stream().max(Integer::compare)
                : Optional.of(Integer.valueOf(run));
        return number.map(n -> runsDir.resolve(Integer.toString(n))).filter(Files::isDirectory);
    }

    static int number(Path runDir) {
        return Integer.parseInt(runDir.getFileName().toString());
    }

    static void record(Path runDir, ObjectNode summary) throws IOException {
        Files.writeString(runDir.resolve(RUN_JSON), CompactJson.text(summary), StandardCharsets.UTF_8);
    }

    static Optional<JsonNode> summary(Path runDir) throws IOException {
        Path file = runDir.resolve(RUN_JSON);
        return Files.isRegularFile(file)
                ? Optional.of(CompactJson.parse(Files.readString(file, StandardCharsets.UTF_8)))
                : Optional.empty();
    }

    private List<Integer> numbers() {
        if (!Files.isDirectory(runsDir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(runsDir)) {
            return entries.map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("[1-9][0-9]{0,8}"))
                    .map(Integer::valueOf)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(dir)) {
            tree.sorted(Comparator.reverseOrder()).forEach(RunStore::delete);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path path) {
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
