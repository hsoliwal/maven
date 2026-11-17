/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs {@code cargo test} in the current folder, echoes its merged output, and writes one surefire report per test
 * binary ({@link CargoTestOutput}, {@link SurefireXmlWriter}). The exit status is cargo's, so failing tests still
 * fail the build, after their reports are written. It is the {@code libtest} format of {@link SurefireReportMain},
 * which {@link CargoTestProvider} and {@code @m3:surefire:interpreter:libtest} start in a separate JVM.
 */
public final class CargoTestReport {
    private CargoTestReport() {}

    /** Runs the command, writes the reports, returns the command's exit status. */
    static int run(Path reports, List<String> command, PrintStream echo) throws IOException, InterruptedException {
        List<String> argv = new ArrayList<>(command);
        argv.set(0, TaskRunner.resolveTool(argv.get(0), Path.of("").toAbsolutePath()));
        Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        process.getOutputStream().close();
        CargoTestOutput parser = new CargoTestOutput();
        try (BufferedReader lines = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            lines.lines().forEach(line -> {
                echo.println(line);
                parser.accept(line);
            });
        }
        int exit = process.waitFor();
        write(parser.finish(), reports, echo);
        return exit;
    }

    private static void write(List<TestSuiteReport> suites, Path reports, PrintStream echo) throws IOException {
        for (TestSuiteReport suite : suites) {
            Path file = SurefireXmlWriter.write(suite, reports);
            echo.println("[surefire] " + file.getFileName() + ": " + suite.summary());
        }
    }
}
