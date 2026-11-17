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
package org.apache.maven.agent.events;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The shortest command that reruns one failure: the failed module with what it needs, up to the failed step, and for
 * tests only the failed ones. Spelling comes from {@link MavenCommands}.
 */
final class ReproduceCommand {
    /** Above this many failed tests the selector names classes only. */
    static final int MAX_METHODS = 10;

    private ReproduceCommand() {}

    /**
     * The command for a failure of {@code kind} (compile, testCompile, test, integration-test, other) in the module at
     * {@code path}; {@code phase} is the failed mojo's phase, used for other failures.
     */
    static String of(String launcher, String kind, String path, List<Diagnostic> errors,
            List<SurefireReports.TestCase> tests, String phase) {
        switch (kind) {
            case "compile":
            case "testCompile":
                boolean testSource = "testCompile".equals(kind)
                        || errors.stream().anyMatch(d -> d.file().contains("/src/test/"));
                return MavenCommands.module(launcher, path, testSource ? "test-compile" : "compile");
            case "test":
                return tests.isEmpty() ? MavenCommands.module(launcher, path, "test")
                        : MavenCommands.test(launcher, path, selector(tests));
            case "integration-test":
                return tests.isEmpty() ? MavenCommands.module(launcher, path, "verify")
                        : MavenCommands.integrationTest(launcher, path, selector(tests));
            default:
                return MavenCommands.module(launcher, path, phase == null || phase.isEmpty() ? "verify" : phase);
        }
    }

    /** {@code a.BTest#adds+subtracts,a.CTest#x}; classes only above {@link #MAX_METHODS} tests. */
    static String selector(List<SurefireReports.TestCase> tests) {
        Map<String, Set<String>> byClass = new LinkedHashMap<>();
        tests.forEach(t -> byClass.computeIfAbsent(t.className(), c -> new LinkedHashSet<>()).add(method(t.method())));
        boolean methods = tests.size() <= MAX_METHODS;
        return byClass.entrySet().stream()
                .map(e -> methods ? e.getKey() + "#" + String.join("+", e.getValue()) : e.getKey())
                .collect(Collectors.joining(","));
    }

    /** A method name without a parameterized suffix ({@code adds[1]}, {@code adds(int)[2]}). */
    static String method(String name) {
        int cut = name.length();
        int bracket = name.indexOf('[');
        int paren = name.indexOf('(');
        cut = bracket >= 0 ? Math.min(cut, bracket) : cut;
        return name.substring(0, paren >= 0 ? Math.min(cut, paren) : cut);
    }
}
