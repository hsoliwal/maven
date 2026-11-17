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
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Writes a {@link TestSuiteReport} in the surefire report format ({@code TEST-<suite>.xml}: testsuite with tests,
 * failures, errors, skipped and time; testcase with failure, error or skipped children and system-out), which the
 * surefire report plugin, CI servers and IDEs read. Ported from the native-build plugin's writer; it is the one writer
 * of this plugin (cargo test, TRX). A failure or error message with more than one line (a stack trace) is also the
 * element's text, and its first line the message attribute; a report replaces the old file in one move.
 */
public final class SurefireXmlWriter {
    private SurefireXmlWriter() {}

    /** The report file name for a suite: characters outside {@code [A-Za-z0-9._-]} become {@code _}. */
    public static String fileName(String suite) {
        return "TEST-" + suite.replaceAll("[^A-Za-z0-9._-]", "_") + ".xml";
    }

    /** Writes the report into {@code directory}, creating it, through a temporary file moved into place. */
    public static Path write(TestSuiteReport report, Path directory) throws IOException {
        Path file = directory.resolve(fileName(report.name()));
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, ".surefire-", ".tmp");
        try {
            Files.writeString(temp, render(report), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
        return file;
    }

    /** The report XML. */
    public static String render(TestSuiteReport r) {
        String head = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite xmlns:xsi=\"http://www.w3.org/2001/"
                + "XMLSchema-instance\" xsi:noNamespaceSchemaLocation=\"https://maven.apache.org/surefire/"
                + "maven-surefire-plugin/xsd/surefire-test-report.xsd\" name=\"" + escape(r.name())
                + "\" time=\"" + time(r.seconds()) + "\" tests=\"" + r.cases().size() + "\" errors=\""
                + r.count(TestSuiteReport.Status.ERRORED) + "\" skipped=\"" + r.count(TestSuiteReport.Status.SKIPPED)
                + "\" failures=\"" + r.count(TestSuiteReport.Status.FAILED) + "\">\n";
        return head + r.cases().stream().map(SurefireXmlWriter::testCase).collect(Collectors.joining())
                + "</testsuite>\n";
    }

    private static String testCase(TestSuiteReport.Case c) {
        String open = "  <testcase name=\"" + escape(c.name()) + "\" classname=\""
                + escape(c.className()) + "\" time=\"" + time(c.seconds()) + "\"";
        String detail = detail(c) + (c.output().isEmpty() ? ""
                : "    <system-out>" + escape(c.output()) + "</system-out>\n");
        return detail.isEmpty() ? open + "/>\n" : open + ">\n" + detail + "  </testcase>\n";
    }

    private static String detail(TestSuiteReport.Case c) {
        String message = escape(firstLine(c.message()));
        return switch (c.status()) {
            case FAILED -> problem("failure", message, c.message());
            case ERRORED -> problem("error", message, c.message());
            case SKIPPED -> "    <skipped message=\"" + message + "\"/>\n";
            case PASSED -> "";
        };
    }

    /** A failure or error element; a message of more than one line is its text too. */
    private static String problem(String element, String message, String text) {
        String open = "    <" + element + " message=\"" + message + "\" type=\"native\"";
        String trace = text.strip();
        return trace.indexOf('\n') < 0 ? open + "/>\n" : open + ">" + escape(trace) + "</" + element + ">\n";
    }

    private static String firstLine(String text) {
        String stripped = text.strip();
        int end = stripped.indexOf('\n');
        return (end < 0 ? stripped : stripped.substring(0, end)).strip();
    }

    private static String time(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }

    /** XML text: markup characters as entities; characters XML 1.0 forbids (such as ANSI escapes) dropped. */
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().filter(SurefireXmlWriter::allowed).forEach(c -> out.append(entity(c)));
        return out.toString();
    }

    private static boolean allowed(int c) {
        return c == 0x9 || c == 0xA || c == 0xD || (c >= 0x20 && c <= 0xD7FF) || (c >= 0xE000 && c <= 0xFFFD)
                || c >= 0x10000;
    }

    private static String entity(int c) {
        return switch (c) {
            case '&' -> "&amp;";
            case '<' -> "&lt;";
            case '>' -> "&gt;";
            case '"' -> "&quot;";
            case '\'' -> "&apos;";
            default -> new String(Character.toChars(c));
        };
    }
}
