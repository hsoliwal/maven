/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** No-dependency CLI contract exercised by Maven verify and the offline checker. */
public final class PolyglotCliContractTestMain {
    private static int passed;

    private PolyglotCliContractTestMain() {}

    public static void main(String[] ignored) throws Exception {
        Path root = Files.createTempDirectory("m3-polyglot-cli-");
        try {
            Path input = root.resolve("source.txt");
            Path output = root.resolve("result.txt");
            Files.writeString(input, "first", StandardCharsets.UTF_8);
            Files.writeString(output, "existing", StandardCharsets.UTF_8);
            String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java").toString();
            Path manifest = root.resolve("m3-build.tsv");
            Files.writeString(manifest, TaskManifest.MAGIC + "\n"
                    + "task\tall\t-\t.\n"
                    + "dep\tall\tcompile\n"
                    + "task\tcompile\t" + javaExecutable + "\t.\n"
                    + "arg\tcompile\t-version\n"
                    + "input\tcompile\tsource.txt\n"
                    + "output\tcompile\tresult.txt\n", StandardCharsets.UTF_8);
            String[] base = {"plan", root.toString(), manifest.toString(), "all"};
            List<String> log = new ArrayList<>();
            check(PolyglotCli.execute(base, log::add).equals(List.of("compile", "all")),
                    "original four-argument plan");
            check(log.stream().anyMatch(line -> line.equals("PLANNED 2 targets")), "plan summary");
            check(PolyglotCli.execute(new String[] {
                    "plan", root.toString(), manifest.toString(), "all", "--timeout-seconds", "1"
            }, line -> {}).size() == 2, "bounded timeout is accepted on plan");
            passed++;

            log.clear();
            check(PolyglotCli.execute(new String[] {
                    "run", root.toString(), manifest.toString(), "all", "--incremental",
                    "--timeout-seconds", "30"
            }, log::add).size() == 2, "incremental first run");
            check(log.stream().noneMatch(line -> line.startsWith("UP-TO-DATE")), "first run executes");
            log.clear();
            PolyglotCli.execute(new String[] {
                    "run", root.toString(), manifest.toString(), "all", "--timeout-seconds", "30",
                    "--incremental"
            }, log::add);
            check(log.stream().anyMatch(line -> line.startsWith("UP-TO-DATE compile")),
                    "explicit unchanged-input shortcut");
            Files.writeString(input, "changed", StandardCharsets.UTF_8);
            log.clear();
            PolyglotCli.execute(new String[] {
                    "run", root.toString(), manifest.toString(), "all", "--incremental"
            }, log::add);
            check(log.stream().noneMatch(line -> line.startsWith("UP-TO-DATE")), "changed input executes");
            log.clear();
            PolyglotCli.execute(new String[] {
                    "run", root.toString(), manifest.toString(), "all", "--incremental",
                    "--state-backend", "file"
            }, log::add);
            check(Files.isRegularFile(root.resolve(ModuleStateDb.FILE)), "one module DB exists");
            check(log.stream().noneMatch(line -> line.startsWith("UP-TO-DATE")),
                    "first DB-backed run must execute");
            log.clear();
            PolyglotCli.execute(new String[] {
                    "run", root.toString(), manifest.toString(), "all", "--incremental",
                    "--state-backend", "file"
            }, log::add);
            check(log.stream().anyMatch(line -> line.startsWith("UP-TO-DATE compile")),
                    "second DB-backed run must reuse verified state");
            passed++;

            log.clear();
            PolyglotCli.execute(new String[] {
                    "run", root.toString(), manifest.toString(), "all", "--incremental", "--m3db"
            }, log::add);
            check(Files.isRegularFile(root.resolve(M3DbModuleStore.FILE)), "CLI creates one per-module M3DB database");
            log.clear();
            PolyglotCli.execute(new String[] {
                    "run", root.toString(), manifest.toString(), "all", "--m3db", "--incremental"
            }, log::add);
            check(log.stream().anyMatch(line -> line.startsWith("UP-TO-DATE compile")),
                    "CLI reads its completed module M3DB receipt");
            log.clear();
            PolyglotCli.execute(new String[] {
                    "run", root.toString(), manifest.toString(), "all", "--incremental", "--state-backend", "indexdb"
            }, log::add);
            check(log.stream().anyMatch(line -> line.startsWith("UP-TO-DATE compile")),
                    "--state-backend indexdb is the same module M3DB as --m3db");
            passed++;

            for (String[] rejected : List.of(
                    new String[] {"plan", root.toString(), manifest.toString(), "all", "--incremental"},
                    new String[] {"plan", root.toString(), manifest.toString(), "all", "--approve-rewrite-apply"},
                    new String[] {"plan", root.toString(), manifest.toString(), "all", "--m3db"},
                    new String[] {"run", root.toString(), manifest.toString(), "all", "--m3db"},
                    new String[] {"run", root.toString(), manifest.toString(), "all", "--incremental", "--m3db", "--m3db"},
                    new String[] {"run", root.toString(), manifest.toString(), "all", "--incremental", "--m3db",
                        "--state-backend", "file"},
                    new String[] {"run", root.toString(), manifest.toString(), "all", "--state-backend", "indexdb"},
                    new String[] {"run", root.toString(), manifest.toString(), "all", "--incremental", "--incremental"},
                    new String[] {"run", root.toString(), manifest.toString(), "all", "--timeout-seconds", "0"},
                    new String[] {"run", root.toString(), manifest.toString(), "all", "--timeout-seconds", "43201"},
                    new String[] {"run", root.toString(), manifest.toString(), "all", "--timeout-seconds", "oops"},
                    new String[] {"run", root.toString(), manifest.toString(), "all", "--timeout-seconds"},
                    new String[] {"run", root.toString(), manifest.toString(), "all", "--state-backend", "file"},
                    new String[] {"run", root.toString(), manifest.toString(), "all", "--incremental", "--state-backend", "invalid"},
                    new String[] {"run", root.toString(), manifest.toString(), "all", "--unknown"})) {
                boolean failed = false;
                try {
                    PolyglotCli.execute(rejected, line -> {});
                } catch (IllegalArgumentException expected) {
                    failed = true;
                }
                check(failed, "invalid invocation must be refused: " + List.of(rejected));
            }
            passed++;
            System.out.println("PASS: " + passed + " M3 polyglot CLI contract groups");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
