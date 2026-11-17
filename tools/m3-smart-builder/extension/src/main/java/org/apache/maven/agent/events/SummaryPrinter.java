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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The short text an agent reads first, on standard output and in {@code summary.txt}: one header line with the
 * outcome and counts, per failure its errors, failed tests and reproduce command, skipped projects, the warnings of a
 * green build, and where the files are. Every line starts with {@value #PREFIX} so it can be found in any output;
 * the whole stays within {@code maven.agent.summaryBytes}.
 */
final class SummaryPrinter {
    static final String PREFIX = "[agent] ";
    private static final Pattern LINE_BREAK = Pattern.compile("\r\n|[\r\n  ]");

    /** Counts of the header line. */
    record Header(String outcome, long durationMs, int projects, int success, int failure, int skipped, int errors,
            int warnings, int info, int testsRun, int testsFailed, int testErrors, int testsSkipped) {
        String text() {
            return String.format(Locale.ROOT, "BUILD %s in %.1f s: projects %d success, %d failure, %d skipped of %d;"
                    + " diagnostics %d error(s), %d warning(s), %d info; tests %d run, %d failed, %d errors, %d skipped",
                    outcome.toUpperCase(Locale.ROOT), durationMs / 1000.0, success, failure, skipped, projects, errors,
                    warnings, info, testsRun, testsFailed, testErrors, testsSkipped);
        }
    }

    private SummaryPrinter() {}

    /** The summary lines, unbounded. */
    static List<String> lines(Header header, List<FailureDigest.Failure> failures, List<String> skipped,
            List<Diagnostic> warnings, List<String> files, int printLimit) {
        List<String> lines = new ArrayList<>(List.of(header.text()));
        failures.forEach(failure -> failure(lines, failure));
        skipped.forEach(id -> lines.add("SKIPPED " + id));
        if (failures.isEmpty()) {
            warnings.stream().limit(printLimit).forEach(d -> lines.add(diagnostic(d)));
            if (warnings.size() > printLimit) {
                lines.add("... " + (warnings.size() - printLimit) + " more warning(s) in the events file");
            }
        }
        lines.add("files: " + String.join(" ", files));
        return lines;
    }

    private static void failure(List<String> lines, FailureDigest.Failure f) {
        lines.add("FAILED " + f.path() + " (" + f.project() + ") " + f.kind() + (f.mojo().isEmpty() ? "" : " " + f.mojo())
                + ": " + f.message());
        f.diagnostics().forEach(item -> lines.add("  " + diagnostic(item.diagnostic())));
        f.tests().forEach(test -> lines.add("  TEST " + test.name() + " " + test.test().outcome() + ": "
                + test.test().exception() + (test.message().isEmpty() ? "" : ": " + test.message())
                + (test.file().isEmpty() ? "" : " at " + test.file() + ":" + test.line())));
        lines.add("  reproduce: " + f.reproduce());
    }

    /**
     * {@code text} on one line: CR LF, CR, LF, U+2028 and U+2029 become the two characters {@code \n}, a tab a space,
     * and every other control character {@code \}{@code uXXXX}, so no message can start a line of its own.
     */
    static String oneLine(String text) {
        String joined = LINE_BREAK.matcher(text).replaceAll("\\\\n");
        StringBuilder out = new StringBuilder(joined.length());
        joined.chars().forEach(c -> {
            if (c == '\t') {
                out.append(' ');
            } else if (Character.isISOControl(c)) {
                out.append(String.format(Locale.ROOT, "\\u%04x", c));
            } else {
                out.append((char) c);
            }
        });
        return out.toString();
    }

    private static String diagnostic(Diagnostic d) {
        return d.severity() + " " + d.location() + (d.rule().isEmpty() ? "" : " [" + d.rule() + "]") + " " + d.message();
    }

    /**
     * The lines with {@link #PREFIX}, joined, cut at a line boundary to {@code maxBytes} (UTF-8) with a last line
     * naming {@code more} for the rest.
     */
    static String bounded(List<String> lines, int maxBytes, String more) {
        StringBuilder text = new StringBuilder();
        int[] used = {0};
        String tail = PREFIX + "... more in " + more + "\n";
        int budget = maxBytes - tail.getBytes(StandardCharsets.UTF_8).length;
        boolean cut = lines.stream().map(line -> PREFIX + oneLine(line) + "\n").anyMatch(line -> {
            int size = line.getBytes(StandardCharsets.UTF_8).length;
            if (used[0] + size > budget) {
                return true;
            }
            used[0] += size;
            text.append(line);
            return false;
        });
        return cut ? text.append(tail).toString() : text.toString();
    }
}
