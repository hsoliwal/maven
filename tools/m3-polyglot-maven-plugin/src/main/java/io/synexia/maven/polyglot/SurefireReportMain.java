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
import java.util.stream.Stream;

/**
 * Entry point of {@link SurefireReportProvider}: {@code <results> <reports> [<tool> <argument>...]}, paths relative
 * to the current folder. With a tool, old {@code *.trx} files directly in the results folder are removed, the tool
 * runs (looked up as the task runner looks up bare tool names), and the conversion follows whatever its exit
 * status; the process then exits with that status. Prints one {@code M3-SUREFIRE} summary line.
 */
public final class SurefireReportMain {
    private SurefireReportMain() { }

    public static void main(String[] args) throws IOException, InterruptedException {
        System.exit(run(List.of(args), Path.of("").toAbsolutePath()));
    }

    /** Runs the optional tool, converts its TRX files; returns the tool's exit status (0 without a tool). */
    static int run(List<String> args, Path directory) throws IOException, InterruptedException {
        if (args.size() < 2) throw new IOException("usage: <results> <reports> [<tool> <argument>...]");
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

    /** Converts every TRX file of {@code results}; returns the summary line. */
    static String convert(Path results, Path reports) throws IOException {
        List<Path> files = trx(results);
        List<TrxReport.Case> cases = new ArrayList<>();
        for (Path file : files) cases.addAll(TrxReport.read(file));
        List<Path> written = files.isEmpty() ? List.of() : SurefireXml.write(cases, reports);
        return "M3-SUREFIRE trx=" + files.size() + " tests=" + cases.size()
                + " failures=" + count(cases, TrxReport.Status.FAILED)
                + " errors=" + count(cases, TrxReport.Status.ERRORED)
                + " skipped=" + count(cases, TrxReport.Status.SKIPPED) + " reports=" + written.size();
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

    private static long count(List<TrxReport.Case> cases, TrxReport.Status status) {
        return cases.stream().filter(c -> c.status() == status).count();
    }
}
