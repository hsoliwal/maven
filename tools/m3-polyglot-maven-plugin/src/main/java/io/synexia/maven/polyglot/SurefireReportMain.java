/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Entry point of {@link SurefireReportProvider} and {@link CargoTestProvider}: {@code <format> <argument>...}, paths
 * relative to the current folder.
 * <ul>
 *   <li>{@code trx <results> <reports> [<tool> <argument>...]}: with a tool, old {@code *.trx} files directly in the
 *       results folder are removed and the tool runs (looked up as the task runner looks up bare tool names); the TRX
 *       files are then converted whatever its exit status, one report per test class. Prints one
 *       {@code M3-SUREFIRE} summary line.</li>
 *   <li>{@code libtest <reports> <tool> <argument>...}: {@link CargoTestReport} runs the tool, echoes its output and
 *       writes one report per test binary.</li>
 * </ul>
 * The process exits with the tool's status (0 without a tool). Every report goes through {@link SurefireXmlWriter}.
 */
public final class SurefireReportMain {
    /** The TRX format. */
    static final String TRX = "trx";
    /** The libtest output format of cargo test. */
    static final String LIBTEST = "libtest";

    private SurefireReportMain() { }

    public static void main(String[] args) throws IOException, InterruptedException {
        System.exit(run(List.of(args), Path.of("").toAbsolutePath()));
    }

    /** Runs the format's tool and writes its reports; returns the tool's exit status (0 without a tool). */
    static int run(List<String> args, Path directory) throws IOException, InterruptedException {
        if (args.size() >= 3 && LIBTEST.equals(args.get(0))) {
            return CargoTestReport.run(directory.resolve(args.get(1)).normalize(), args.subList(2, args.size()),
                    System.out);
        }
        if (args.size() < 3 || !TRX.equals(args.get(0))) {
            throw new IOException("usage: trx <results> <reports> [<tool> <argument>...] | libtest <reports> <tool>"
                    + " <argument>...");
        }
        return trx(args.subList(1, args.size()), directory);
    }

    private static int trx(List<String> args, Path directory) throws IOException, InterruptedException {
        Path results = directory.resolve(SurefireReportProvider.relative(args.get(0))).normalize();
        Path reports = directory.resolve(SurefireReportProvider.relative(args.get(1))).normalize();
        int exit = 0;
        if (args.size() > 2) {
            for (Path old : trx(results)) Files.delete(old);
            exit = execute(args.subList(2, args.size()), directory);
        }
        System.out.println(convert(results, reports));
        return exit;
    }

    /** Converts every TRX file of {@code results}, one report per test class; returns the summary line. */
    static String convert(Path results, Path reports) throws IOException {
        List<Path> files = trx(results);
        List<TestSuiteReport.Case> cases = new ArrayList<>();
        for (Path file : files) cases.addAll(TrxReport.read(file));
        List<TestSuiteReport> suites = suites(cases);
        for (TestSuiteReport suite : suites) SurefireXmlWriter.write(suite, reports);
        TestSuiteReport all = new TestSuiteReport("all", cases);
        return "M3-SUREFIRE trx=" + files.size() + " tests=" + cases.size()
                + " failures=" + all.count(TestSuiteReport.Status.FAILED)
                + " errors=" + all.count(TestSuiteReport.Status.ERRORED)
                + " skipped=" + all.count(TestSuiteReport.Status.SKIPPED) + " reports=" + suites.size();
    }

    /** One suite per class name, in name order, each with its cases in the given order. */
    static List<TestSuiteReport> suites(List<TestSuiteReport.Case> cases) {
        Map<String, List<TestSuiteReport.Case>> byClass = new TreeMap<>();
        for (TestSuiteReport.Case c : cases) byClass.computeIfAbsent(c.className(), name -> new ArrayList<>()).add(c);
        return byClass.entrySet().stream().map(e -> new TestSuiteReport(e.getKey(), e.getValue())).toList();
    }

    /** The TRX file itself, or the {@code *.trx} files directly in the folder, in name order; none when absent. */
    static List<Path> trx(Path results) throws IOException {
        if (Files.isRegularFile(results, LinkOption.NOFOLLOW_LINKS)) return List.of(results);
        if (!Files.isDirectory(results, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (Stream<Path> list = Files.list(results)) {
            return list.filter(path -> path.getFileName().toString().endsWith(".trx"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
        }
    }

    private static int execute(List<String> command, Path directory) throws IOException, InterruptedException {
        List<String> argv = new ArrayList<>(command);
        argv.set(0, TaskRunner.resolveTool(command.get(0), directory));
        Process process = new ProcessBuilder(argv).directory(directory.toFile()).inheritIO().start();
        return process.waitFor();
    }
}
