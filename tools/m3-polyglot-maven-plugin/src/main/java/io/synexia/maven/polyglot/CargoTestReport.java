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
 * fail the build, after their reports are written. {@link CargoTestProvider} starts it in a separate JVM.
 */
public final class CargoTestReport {
    private CargoTestReport() {}

    /** Arguments: the reports folder, the cargo executable (a bare name is looked up on PATH), cargo's arguments. */
    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length < 2) {
            System.err.println("usage: CargoTestReport <reports-folder> <cargo> [argument...]");
            System.exit(2);
        }
        System.exit(run(Path.of(args[0]), List.of(args).subList(1, args.length), System.out));
    }

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
