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

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * What failed and how to rerun it, as {@code failure.json} and {@code failure.md} rendered from the same data:
 * failures in reactor order with their error diagnostics (each with its source lines), failed tests and a reproduce
 * command; skipped projects; advice, never trusted. No durations, timestamps or absolute paths, so a rerun of the
 * same failure writes the same bytes.
 */
final class FailureDigest {
    /** A diagnostic with the source lines around it. */
    record Item(Diagnostic diagnostic, Optional<SourceWindow.Window> window) {
        JsonText.Obj json() {
            Diagnostic d = diagnostic;
            return new JsonText.Obj().put("file", d.file()).put("line", d.line() > 0 ? d.line() : null)
                    .put("column", d.column() > 0 ? d.column() : null).put("severity", d.severity())
                    .put("rule", JsonText.orNull(d.rule())).put("message", d.message())
                    .put("context", window.map(SourceWindow.Window::json).orElse(null));
        }
    }

    /** One failed project. */
    record Failure(int index, String project, String path, String kind, String mojo, String message,
            String helpReference, List<Item> diagnostics, List<TestResult> tests, String reproduce) {
        JsonText.Obj json() {
            return new JsonText.Obj().put("project", JsonText.orNull(project)).put("path", path).put("kind", kind)
                    .put("mojo", JsonText.orNull(mojo)).put("message", message)
                    .put("helpReference", JsonText.orNull(helpReference))
                    .put("diagnostics", diagnostics.stream().map(Item::json).collect(Collectors.toList()))
                    .put("tests", tests.stream().map(TestResult::digest).collect(Collectors.toList()))
                    .put("reproduce", reproduce);
        }
    }

    /** Advice of the build hook: status not-requested, none, available, failed or skipped; never trusted. */
    record Advice(String status, String source, String text) {
        static Advice of(String status) {
            return new Advice(status, "", "");
        }

        JsonText.Obj json() {
            return new JsonText.Obj().put("status", status).put("source", JsonText.orNull(source))
                    .put("trusted", false).put("text", JsonText.orNull(text));
        }
    }

    /** Diagnostics and tests per failure ({@code printed}) and what was left out. */
    record Limits(int printed, int omittedDiagnostics, int omittedTests) {
        JsonText.Obj json() {
            return new JsonText.Obj().put("printed", printed).put("omittedDiagnostics", omittedDiagnostics)
                    .put("omittedTests", omittedTests);
        }
    }

    private final String outcome;
    private final List<String> goals;
    private final List<Failure> failures;
    private final List<String> skipped;
    private final Advice advice;
    private final Limits limits;

    FailureDigest(String outcome, List<String> goals, List<Failure> failures, List<String> skipped, Advice advice,
            Limits limits) {
        this.outcome = outcome;
        this.goals = List.copyOf(goals);
        this.failures = List.copyOf(failures);
        this.skipped = List.copyOf(skipped);
        this.advice = advice;
        this.limits = limits;
    }

    List<Failure> failures() {
        return failures;
    }

    /** {@code failure.json}. */
    String json() {
        return new JsonText.Obj().put("v", 1).put("outcome", outcome).put("goals", goals)
                .put("failures", failures.stream().map(Failure::json).collect(Collectors.toList()))
                .put("skippedProjects", skipped).put("advice", advice.json()).put("limits", limits.json()).text()
                + "\n";
    }

    /** {@code failure.md}. */
    String markdown() {
        StringBuilder md = new StringBuilder("# Build failure\n\n");
        md.append("Outcome: ").append(outcome).append(". Goals: ").append(String.join(" ", goals)).append(".\n");
        failures.forEach(failure -> failure(md, failure));
        if (!skipped.isEmpty()) {
            md.append("\n## Skipped projects\n\n");
            skipped.forEach(id -> md.append("- ").append(id).append('\n'));
        }
        md.append("\n## Advice\n\nStatus: ").append(advice.status()).append(" (never trusted).\n");
        if (!advice.text().isEmpty()) {
            md.append("\n").append(advice.text().strip()).append('\n');
        }
        md.append("\n## Limits\n\nUp to ").append(limits.printed()).append(" diagnostics and tests per failure; ")
                .append(limits.omittedDiagnostics()).append(" diagnostics and ").append(limits.omittedTests())
                .append(" tests left out.\n");
        return md.toString();
    }

    private static void failure(StringBuilder md, Failure f) {
        md.append("\n## ").append(f.path()).append(" (").append(f.project()).append("): ").append(f.kind()).append("\n\n");
        md.append(f.mojo().isEmpty() ? "The project" : "`" + f.mojo() + "`").append(" failed: ").append(f.message())
                .append('\n');
        if (!f.helpReference().isEmpty()) {
            md.append("\nHelp: ").append(f.helpReference()).append('\n');
        }
        if (!f.diagnostics().isEmpty()) {
            md.append("\n### Diagnostics\n");
            f.diagnostics().forEach(item -> item(md, item));
        }
        if (!f.tests().isEmpty()) {
            md.append("\n### Failed tests\n");
            f.tests().forEach(test -> test(md, test));
        }
        md.append("\n### Reproduce\n\n```\n").append(f.reproduce()).append("\n```\n");
    }

    private static void item(StringBuilder md, Item item) {
        Diagnostic d = item.diagnostic();
        md.append("\n- `").append(d.location()).append("` ").append(d.severity())
                .append(d.rule().isEmpty() ? "" : " [" + d.rule() + "]").append(": ").append(d.message()).append('\n');
        item.window().ifPresent(window -> {
            md.append("\n  ```\n");
            int[] number = {window.startLine()};
            window.lines().forEach(line -> md.append(String.format("  %5d%s %s", number[0],
                    number[0]++ == window.markLine() ? ">" : "|", line)).append('\n'));
            md.append("  ```\n");
        });
    }

    private static void test(StringBuilder md, TestResult test) {
        md.append("\n- `").append(test.name()).append("` ").append(test.test().outcome()).append(": ")
                .append(test.test().exception()).append(test.message().isEmpty() ? "" : ": " + test.message())
                .append("\n\n  ```\n");
        test.stack().forEach(line -> md.append("  ").append(line).append('\n'));
        md.append("  ```\n");
    }
}
