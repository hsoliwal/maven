/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the output of {@code cargo test} into one {@link TestSuiteReport} per test binary. It reads the format of
 * stable libtest ({@code test <name> ... ok|FAILED|ignored}, {@code ---- <name> stdout ----} failure sections,
 * {@code test result:}) and cargo's {@code Running} and {@code Doc-tests} lines, which name each binary; the JSON
 * and JUnit formats of libtest need nightly. Lines are fed in the order cargo's merged stdout and stderr give them;
 * nothing runs here. A binary that ends without a {@code test result:} line gets an error case when cargo names it
 * in {@code error: test failed, to rerun pass ...} or exits non-zero without naming any binary, so a crash is never
 * reported as passing tests only; otherwise (a {@code [[test]] harness = false} binary that passed) it gets one
 * passed case.
 */
public final class CargoTestOutput {
    private static final Pattern RUNNING = Pattern.compile("\\s+Running (?:unittests )?(.+?) \\((.+)\\)");
    private static final Pattern DOC_TESTS = Pattern.compile("\\s+Doc-tests (\\S+)");
    private static final Pattern CASE = Pattern.compile("test (.+?) \\.\\.\\. (ok|FAILED|ignored)(?:, (.*))?");
    private static final Pattern FAILURE = Pattern.compile("---- (.+) stdout ----");
    private static final Pattern RESULT = Pattern.compile("test result: (?:ok|FAILED)\\..*");
    private static final Pattern HASH = Pattern.compile("-[0-9a-f]{16}$");
    private static final String RERUN = "error: test failed, to rerun pass ";

    private final List<TestSuiteReport> done = new ArrayList<>();
    private final Set<String> incomplete = new HashSet<>();
    private final Set<String> rerun = new HashSet<>();
    private String last;
    private String suite;
    private Map<String, TestSuiteReport.Case> cases = new LinkedHashMap<>();
    private String failing;
    private StringBuilder output;

    /** Feeds one output line, without its line end. */
    public void accept(String line) {
        Matcher match;
        if (line.startsWith(RERUN)) {
            rerun.add(suite != null ? suite : last);
        } else if ((match = RUNNING.matcher(line)).matches()) {
            open(binary(match.group(2)) + " " + match.group(1).replace('\\', '/'));
        } else if ((match = DOC_TESTS.matcher(line)).matches()) {
            open(match.group(1) + " doc-tests");
        } else if (suite != null) {
            inSuite(line);
        }
    }

    /** The suites read so far; a suite still open is closed as incomplete. {@code exit} is cargo's exit status. */
    public List<TestSuiteReport> finish(int exit) {
        if (suite != null) close(false);
        return done.stream().map(s -> incomplete.contains(s.name()) ? ended(s, exit) : s).toList();
    }

    /** A suite without a result line: an error case when it failed, else one passed case for the whole binary. */
    private TestSuiteReport ended(TestSuiteReport s, int exit) {
        List<TestSuiteReport.Case> all = new ArrayList<>(s.cases());
        if (rerun.contains(s.name()) || (exit != 0 && rerun.isEmpty())) {
            all.add(new TestSuiteReport.Case(s.name(), s.name() + " (incomplete)", 0, TestSuiteReport.Status.ERRORED,
                    "the test binary ended without a test result line", ""));
        } else if (all.isEmpty()) {
            all.add(new TestSuiteReport.Case(s.name(), s.name(), 0, TestSuiteReport.Status.PASSED, "", ""));
        }
        return new TestSuiteReport(s.name(), all);
    }

    private void inSuite(String line) {
        Matcher match;
        if ((match = CASE.matcher(line)).matches()) {
            record(match.group(1), match.group(2), match.group(3));
        } else if ((match = FAILURE.matcher(line)).matches()) {
            capture(match.group(1));
        } else if (RESULT.matcher(line).matches()) {
            close(true);
        } else if ("failures:".equals(line)) {
            capture(null);
        } else if (output != null) {
            output.append(line).append('\n');
        }
    }

    private void record(String name, String outcome, String reason) {
        TestSuiteReport.Status status = switch (outcome) {
            case "ok" -> TestSuiteReport.Status.PASSED;
            case "ignored" -> TestSuiteReport.Status.SKIPPED;
            default -> TestSuiteReport.Status.FAILED;
        };
        String message = status == TestSuiteReport.Status.FAILED ? "test failed"
                : status == TestSuiteReport.Status.SKIPPED && reason != null ? reason : "";
        cases.put(name, new TestSuiteReport.Case(suite, name, 0, status, message, ""));
    }

    /** Ends the failure section being read (its text becomes that case's output) and starts the one of name. */
    private void capture(String name) {
        TestSuiteReport.Case owner = failing == null ? null : cases.get(failing);
        if (owner != null) {
            String text = output.toString().strip();
            cases.put(failing, new TestSuiteReport.Case(owner.className(), owner.name(), owner.seconds(),
                    owner.status(), panicMessage(text, owner.message()), text));
        }
        failing = name;
        output = name == null ? null : new StringBuilder();
    }

    private void open(String name) {
        if (suite != null) close(false);
        suite = name;
        cases = new LinkedHashMap<>();
    }

    private void close(boolean complete) {
        capture(null);
        if (!complete) incomplete.add(suite);
        done.add(new TestSuiteReport(suite, new ArrayList<>(cases.values())));
        last = suite;
        suite = null;
    }

    /**
     * The panic message in a failure section: the line after {@code panicked at <file>:<line>:<column>:} (Rust 1.73
     * and later), or the quoted text of {@code panicked at '<message>', <file>} (earlier); else {@code fallback}.
     */
    static String panicMessage(String text, String fallback) {
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            int at = lines[i].indexOf(" panicked at ");
            if (at < 0) continue;
            String rest = lines[i].substring(at + " panicked at ".length());
            if (rest.endsWith(":") && i + 1 < lines.length) return lines[i + 1].strip();
            if (rest.startsWith("'") && rest.lastIndexOf("', ") > 0) return rest.substring(1, rest.lastIndexOf("', "));
        }
        return fallback;
    }

    /** The test binary's name: the file name without .exe and without cargo's 16-digit hash. */
    static String binary(String path) {
        String file = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
        String stem = file.endsWith(".exe") ? file.substring(0, file.length() - 4) : file;
        return HASH.matcher(stem).replaceFirst("");
    }
}
