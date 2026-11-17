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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Turns the results of {@code make check} under Automake's parallel test harness into test cases: one
 * {@code :test-result:} line of each {@code <test>.trs} file per case (several for TAP tests), with the test's
 * {@code .log} as output. PASS and XFAIL pass; FAIL and XPASS fail; ERROR is an error; SKIP is skipped.
 */
public final class AutomakeTrsReport {
    private static final int MAX_LOG = 64 * 1024;

    private AutomakeTrsReport() {}

    /** All .trs files under {@code buildDirectory}, sorted by path. */
    public static TestSuiteReport read(Path buildDirectory, String suite) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(buildDirectory)) {
            files = walk.filter(p -> p.getFileName().toString().endsWith(".trs")).sorted().toList();
        }
        List<TestSuiteReport.Case> cases = files.stream().flatMap(f -> cases(buildDirectory, f, suite).stream())
                .toList();
        return new TestSuiteReport(suite, cases);
    }

    /** The test status of an Automake result word. */
    public static TestSuiteReport.Status status(String word) {
        return switch (word) {
            case "PASS", "XFAIL" -> TestSuiteReport.Status.PASSED;
            case "SKIP" -> TestSuiteReport.Status.SKIPPED;
            case "ERROR" -> TestSuiteReport.Status.ERRORED;
            default -> TestSuiteReport.Status.FAILED;
        };
    }

    private static List<TestSuiteReport.Case> cases(Path root, Path trs, String suite) {
        String name = WindowsPaths.relative(root, trs).replaceAll("\\.trs$", "");
        List<String> results = lines(trs).stream().filter(l -> l.startsWith(":test-result:"))
                .map(l -> l.substring(":test-result:".length()).strip()).toList();
        String output = log(trs);
        return IntStream.range(0, results.size()).mapToObj(i -> testCase(suite, results.size() > 1
                ? name + "#" + (i + 1) : name, results.get(i), output)).toList();
    }

    private static TestSuiteReport.Case testCase(String suite, String name, String result, String output) {
        String word = result.split("\\s+", 2)[0];
        TestSuiteReport.Status status = status(word);
        String message = status == TestSuiteReport.Status.PASSED ? "" : result;
        return new TestSuiteReport.Case(suite, name + ("XFAIL".equals(word) ? " (expected failure)" : ""), 0,
                status, message, output);
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return List.of(":test-result: ERROR cannot read " + file.getFileName());
        }
    }

    private static String log(Path trs) {
        Path log = trs.resolveSibling(trs.getFileName().toString().replaceAll("\\.trs$", ".log"));
        try {
            byte[] bytes = Files.isRegularFile(log) ? Files.readAllBytes(log) : new byte[0];
            int from = Math.max(0, bytes.length - MAX_LOG);
            return new String(bytes, from, bytes.length - from, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return "";
        }
    }
}
