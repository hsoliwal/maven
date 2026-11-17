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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The shortest command that reruns one failure: the build's options ({@link ReproduceOptions}: {@code -f},
 * profiles, user properties), then the failed module with what it needs, up to the failed step, and for tests only
 * the failed ones; a goal-level failure reruns the build's goals. Spelling comes from {@link MavenCommands}; every
 * argument is one every shell keeps unchanged inside double quotes, so only the launcher differs per shell.
 */
final class ReproduceCommand {
    /** Above this many failed tests the selector names classes only. */
    static final int MAX_METHODS = 10;
    private static final Pattern SAFE_CLASS = Pattern.compile("[A-Za-z0-9_.*]++");
    private static final Pattern SAFE_METHOD = Pattern.compile("[A-Za-z0-9_]++");

    /** What the reproduce commands of one build share: launcher, options before {@code -pl}, the build's goals. */
    record Build(String launcher, List<String> options, List<String> goals) {}

    /**
     * One failure: its kind (compile, testCompile, test, integration-test, model, other), the module selector ({@code
     * -pl}; "" when no project failed), error diagnostics, failed tests, the failed mojo's phase ("" when no mojo
     * failed) and, for a goal run from the command line, its {@code g:a:v:goal} (else "").
     */
    record Failed(String kind, String module, List<Diagnostic> errors, List<SurefireReports.TestCase> tests,
            String phase, String goal) {}

    private ReproduceCommand() {}

    /** The command line that reruns {@code failed}. */
    static String of(Build build, Failed failed) {
        List<String> args = new ArrayList<>(build.options());
        args.addAll(arguments(build, failed));
        return MavenCommands.line(build.launcher(), args);
    }

    private static List<String> arguments(Build build, Failed f) {
        if (!f.goal().isEmpty()) {
            return MavenCommands.inModule(f.module().isEmpty() ? "." : f.module(), List.of(f.goal()));
        }
        if ("model".equals(f.kind())) {
            return List.of("validate");
        }
        return f.module().isEmpty() ? goals(build) : MavenCommands.inModule(f.module(), lifecycle(build, f));
    }

    private static List<String> lifecycle(Build build, Failed f) {
        String selector = selector(f.tests());
        switch (f.kind()) {
            case "compile":
            case "testCompile":
                boolean testSource = "testCompile".equals(f.kind())
                        || f.errors().stream().anyMatch(d -> d.file().contains("/src/test/"));
                return List.of(testSource ? "test-compile" : "compile");
            case "test":
                return selector.isEmpty() ? List.of("test") : MavenCommands.testArgs(selector);
            case "integration-test":
                return selector.isEmpty() ? List.of("verify") : MavenCommands.integrationTestArgs(selector);
            default:
                return f.phase().isEmpty() ? goals(build) : List.of(f.phase());
        }
    }

    /** The build's goals that every shell keeps unchanged; {@code verify} when none is left. */
    private static List<String> goals(Build build) {
        List<String> goals = build.goals().stream().filter(MavenCommands::quotable).collect(Collectors.toList());
        return goals.isEmpty() ? List.of("verify") : goals;
    }

    /** The {@code -pl} selector of a module: its path, or its id when a shell would change the path. */
    static String projectArg(String path, String id) {
        return MavenCommands.quotable(path) || id.isEmpty() ? path : id;
    }

    /**
     * {@code a.BTest#adds+subtracts,a.CTest#x}; classes only above {@link #MAX_METHODS} tests. A nested class's
     * {@code $} becomes {@code *}, which Surefire matches. A class with a method name outside {@code [A-Za-z0-9_]}
     * (a display name, a quote, a backtick) is selected whole; a class name outside {@code [A-Za-z0-9_.*]} is left
     * out, so no selector holds a character any shell changes inside double quotes.
     */
    static String selector(List<SurefireReports.TestCase> tests) {
        Map<String, Set<String>> byClass = new LinkedHashMap<>();
        tests.stream().filter(t -> SAFE_CLASS.matcher(className(t)).matches()).forEach(t -> byClass
                .computeIfAbsent(className(t), c -> new LinkedHashSet<>()).add(method(t.method())));
        boolean methods = tests.size() <= MAX_METHODS;
        return byClass.entrySet().stream().map(e -> methods && e.getValue().stream()
                .allMatch(m -> SAFE_METHOD.matcher(m).matches()) ? e.getKey() + "#" + String.join("+", e.getValue())
                : e.getKey()).collect(Collectors.joining(","));
    }

    private static String className(SurefireReports.TestCase test) {
        return test.className().replace('$', '*');
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
