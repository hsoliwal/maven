/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Test cases of a native test runner written in the surefire report format: one {@code TEST-<class>.xml} per
 * test class, a {@code testsuite} with tests, failures, errors, skipped and time, and {@code testcase} elements
 * with failure, error or skipped children and system-out. The surefire report plugin, CI servers and Eclipse's
 * JUnit view read these files. Text that XML 1.0 cannot carry becomes {@code ?}.
 */
final class SurefireXml {
    private SurefireXml() { }

    /** The report file name of a suite; characters other than letters, digits, '.', '_', '$' and '-' become '_'. */
    static String fileName(String suite) {
        return "TEST-" + suite.replaceAll("[^A-Za-z0-9._$-]", "_") + ".xml";
    }

    /** Suites by class name, in name order, each with its cases in the given order. */
    static Map<String, List<TrxReport.Case>> suites(List<TrxReport.Case> cases) {
        Map<String, List<TrxReport.Case>> suites = new TreeMap<>();
        for (TrxReport.Case c : cases) suites.computeIfAbsent(c.className(), name -> new ArrayList<>()).add(c);
        return suites;
    }

    /** Writes one report per suite into {@code directory} (created when missing); returns the files. */
    static List<Path> write(List<TrxReport.Case> cases, Path directory) throws IOException {
        Files.createDirectories(directory);
        List<Path> files = new ArrayList<>();
        for (Map.Entry<String, List<TrxReport.Case>> suite : suites(cases).entrySet()) {
            Path file = directory.resolve(fileName(suite.getKey()));
            Path temp = Files.createTempFile(directory, ".surefire-", ".tmp");
            Files.writeString(temp, render(suite.getKey(), suite.getValue()), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            files.add(file);
        }
        return files;
    }

    /** The report XML of one suite. */
    static String render(String suite, List<TrxReport.Case> cases) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<testsuite xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
                .append(" xsi:noNamespaceSchemaLocation=\"https://maven.apache.org/surefire/maven-surefire-plugin/")
                .append("xsd/surefire-test-report.xsd\" name=\"").append(escape(suite))
                .append("\" time=\"").append(time(cases.stream().mapToDouble(TrxReport.Case::seconds).sum()))
                .append("\" tests=\"").append(cases.size())
                .append("\" errors=\"").append(count(cases, TrxReport.Status.ERRORED))
                .append("\" skipped=\"").append(count(cases, TrxReport.Status.SKIPPED))
                .append("\" failures=\"").append(count(cases, TrxReport.Status.FAILED)).append("\">\n");
        for (TrxReport.Case c : cases) xml.append(testCase(c));
        return xml.append("</testsuite>\n").toString();
    }

    private static String testCase(TrxReport.Case c) {
        String open = "  <testcase name=\"" + escape(c.name()) + "\" classname=\"" + escape(c.className())
                + "\" time=\"" + time(c.seconds()) + "\"";
        String body = detail(c) + (c.output().isEmpty() ? "" : "    <system-out>" + escape(c.output()) + "</system-out>\n");
        return body.isEmpty() ? open + "/>\n" : open + ">\n" + body + "  </testcase>\n";
    }

    private static String detail(TrxReport.Case c) {
        String message = escape(firstLine(c.message()));
        String trace = escape((c.message() + "\n" + c.detail()).strip());
        return switch (c.status()) {
            case FAILED -> "    <failure message=\"" + message + "\" type=\"dotnet\">" + trace + "</failure>\n";
            case ERRORED -> "    <error message=\"" + message + "\" type=\"dotnet\">" + trace + "</error>\n";
            case SKIPPED -> "    <skipped message=\"" + message + "\"/>\n";
            case PASSED -> "";
        };
    }

    private static long count(List<TrxReport.Case> cases, TrxReport.Status status) {
        return cases.stream().filter(c -> c.status() == status).count();
    }

    private static String firstLine(String text) {
        String stripped = text.strip();
        int end = stripped.indexOf('\n');
        return (end < 0 ? stripped : stripped.substring(0, end)).strip();
    }

    private static String time(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }

    /** XML text and attribute escaping; line ends are kept, other control characters become '?'. */
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.replace("\r\n", "\n").codePoints().forEach(c -> out.append(switch (c) {
            case '&' -> "&amp;";
            case '<' -> "&lt;";
            case '>' -> "&gt;";
            case '"' -> "&quot;";
            case '\n', '\t' -> Character.toString(c);
            default -> c < 0x20 || c == 0xFFFE || c == 0xFFFF ? "?" : Character.toString(c);
        }));
        return out.toString();
    }
}
