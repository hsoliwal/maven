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
package org.apache.maven.plugins.polyglot.adapters;

import io.synexia.maven.polyglot.M3ToolProviders;
import io.synexia.maven.polyglot.PolyglotCli;
import io.synexia.maven.polyglot.TaskManifest;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Real runs of every adapter through the portable engine ({@code PolyglotCli run}) on scratch projects under the
 * given root; outputs are kept there. Tools come from system properties; a case whose tool is missing prints
 * NOT RUN and the reason. Properties: {@code m3.git} (default git), {@code m3.clustering.jar}, {@code m3.mvn},
 * {@code m3.codepatch.version} (default 0.1.0-SNAPSHOT), {@code m3.errorprone.path}, {@code m3.clangd},
 * {@code m3.lsp4j.path}, {@code m3.analysis.path} (m3-analyzer-report-adapter classes + jackson-core: reads every
 * SARIF report back). Path lists use the platform separator.
 */
public final class ToolAdaptersRealRunTestMain {
    static final List<String> NOT_RUN = new ArrayList<>();
    static final List<String> PASSED = new ArrayList<>();
    static final Pattern RESULT = Pattern.compile("\"ruleId\":\"([^\"]+)\",\"ruleIndex\"");
    static Path base;

    private ToolAdaptersRealRunTestMain() { }

    public static void main(String[] args) throws Exception {
        base = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(base);
        textDiffer();
        structuralDiffer();
        differPatcherChain();
        ToolAdaptersRealRunAnalyzers.javacLint();
        ToolAdaptersRealRunAnalyzers.errorProne();
        ToolAdaptersRealRunAnalyzers.clangdLinter();
        PASSED.forEach(line -> System.out.println("PASS: " + line));
        NOT_RUN.forEach(line -> System.out.println("NOT RUN: " + line));
        System.out.println("REAL RUNS: " + PASSED.size() + " passed, " + NOT_RUN.size() + " not run, root " + base);
    }

    static void textDiffer() throws Exception {
        Path dir = fresh("differ-text");
        write(dir.resolve("before/f.txt"), "one\r\ntwo\r\n");
        write(dir.resolve("after/f.txt"), "one\r\nTWO\r\n");
        Files.createDirectories(dir.resolve("out"));
        Path manifest = manifest(dir, task("diff", "@m3:git:differ:text", List.of("trees", git(), "before", "after",
                "out/f.patch"), List.of("before", "after"), List.of("out/f.patch")));
        run(dir, manifest, "diff");
        String patch = show(dir.resolve("out/f.patch"));
        require(patch.contains("-two\r\n+TWO\r\n"), "CRLF kept byte for byte, git's exit 1 read as differences");
        misspelledTree(dir);
    }

    static void misspelledTree(Path dir) throws Exception {
        Path misspelled = manifest(dir, task("diff", "@m3:git:differ:text", List.of("trees", git(), "before", "aftr",
                "out/f.patch"), List.of("before", "aftr"), List.of("out/f.patch")));
        String failure = fails(() -> run(dir, misspelled, "diff"));
        require(failure.contains("missing declared input"), "a misspelled tree fails the task: " + failure);
        PASSED.add("DIFFER text (git diff --no-index; differences vs. errors; misspelled tree refused): "
                + dir.resolve("out/f.patch"));
    }

    static void structuralDiffer() throws Exception {
        Optional<String> jar = property("m3.clustering.jar");
        if (jar.isEmpty()) {
            NOT_RUN.add("DIFFER structural / ATOMIZER (set -Dm3.clustering.jar)");
            return;
        }
        Path dir = fresh("differ-structural");
        write(dir.resolve("src/demo/Calc.java"), calc("sum", "\n"));
        structuralRuns(dir, structuralManifest(dir, jar.get()));
    }

    static Path structuralManifest(Path dir, String jar) throws IOException {
        return manifest(dir, clustering("atoms", "atomizer", List.of(jar, "out/base", "src"),
                        List.of("src"), "out/base/ATOM_HASHES.tsv"),
                clustering("compare", "differ", List.of(jar, "out/base/ATOM_HASHES.tsv", "out/cmp", "src"),
                        List.of("src", "out/base/ATOM_HASHES.tsv"), "out/cmp/COMPARE.tsv"),
                clustering("gate", "differ", List.of(jar, "out/base/ATOM_HASHES.tsv", "out/gate", "src",
                        "failOn=LITERALS_CHANGED"), List.of("src", "out/base/ATOM_HASHES.tsv"), "out/gate/COMPARE.tsv"));
    }

    static void structuralRuns(Path dir, Path manifest) throws Exception {
        List.of("out/base", "out/cmp", "out/gate").forEach(d -> dir.resolve(d).toFile().mkdirs());
        run(dir, manifest, "atoms");
        show(dir.resolve("out/base/ATOM_HASHES.tsv"));
        write(dir.resolve("src/demo/Calc.java"), calc("total", "\n"));
        run(dir, manifest, "compare");
        require(show(dir.resolve("out/cmp/COMPARE.tsv")).contains("LITERALS_CHANGED"), "literal change found");
        String failure = fails(() -> run(dir, manifest, "gate"));
        require(failure.contains("exit 3"), "failOn=LITERALS_CHANGED fails the task with exit 3: " + failure);
        PASSED.add("ATOMIZER + DIFFER structural (clustering compare; gate exit 3 refused): " + dir.resolve("out/cmp/COMPARE.tsv"));
    }

    /** DIFFER (git, repository mode) -> PATCHER plan -> verify -> apply (approved) -> rollback, on CRLF sources. */
    static void differPatcherChain() throws Exception {
        Optional<String> mvn = property("m3.mvn");
        if (mvn.isEmpty()) {
            NOT_RUN.add("PATCHER chain (set -Dm3.mvn to the Maven launcher; code-patch plugin in the local repository)");
            return;
        }
        Path dir = fresh("patcher-chain");
        byte[] before = calc("sum", "\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] after = calc("total", "\r\n").getBytes(StandardCharsets.UTF_8);
        writeBytes(dir.resolve("src/demo/Calc.java"), before);
        repository(dir.resolve("work"), before, after);
        String id = planStage(dir, mvn.get());
        verifyStage(dir, mvn.get(), id, before);
        approvedStages(dir, mvn.get(), id, before, after);
    }

    static void repository(Path work, byte[] before, byte[] after) throws Exception {
        writeBytes(work.resolve("src/demo/Calc.java"), before);
        process(work, git(), "-c", "core.autocrlf=false", "init", "--quiet");
        process(work, git(), "-c", "core.autocrlf=false", "add", "src");
        process(work, git(), "-c", "core.autocrlf=false", "-c", "user.name=m3", "-c", "user.email=m3@example.invalid",
                "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "before");
        writeBytes(work.resolve("src/demo/Calc.java"), after);
    }

    static String planStage(Path dir, String mvn) throws Exception {
        List.of("out/plan").forEach(d -> dir.resolve(d).toFile().mkdirs());
        Path manifest = manifest(dir, task("diff", "@m3:git:differ:text", List.of("repo", git(), "work", "HEAD",
                        "out/fix.patch"), List.of("work"), List.of("out/fix.patch")),
                patch("plan", List.of("plan", mvn, codePatchVersion(), ".", "out/plan", "out/fix.patch"),
                        List.of("out/plan/PLAN.tsv", "out/plan/PLAN_ID")) + "dep\tplan\tdiff\n");
        run(dir, manifest, "plan");
        require(show(dir.resolve("out/fix.patch")).contains("diff --git a/src/demo/Calc.java b/src/demo/Calc.java"),
                "repository-relative paths");
        require(Files.readString(dir.resolve("out/fix.patch")).contains("\"total\";\r\n"), "CRLF kept in the patch");
        require(show(dir.resolve("out/plan/PLAN.tsv")).contains("MODIFY"), "plan row");
        return Files.readString(dir.resolve("out/plan/PLAN_ID")).strip();
    }

    static void verifyStage(Path dir, String mvn, String id, byte[] before) throws Exception {
        String compare = "out/verify/verify/" + id + "/COMPARE.tsv";
        Path manifest = manifest(dir, patch("verify", List.of("verify", mvn, codePatchVersion(), ".", "out/verify",
                "out/fix.patch", "failOn=CHANGED,ADDED,REMOVED"), List.of("out/verify/PLAN.tsv", compare)),
                patch("apply", List.of("apply", mvn, codePatchVersion(), ".", "out/apply", "out/fix.patch",
                        "approvedPlan=" + id), List.of("out/apply/receipts/" + id + "/RECEIPT.tsv")));
        run(dir, manifest, "verify");
        require(show(dir.resolve(compare)).contains("LITERALS_CHANGED"), "verify compares atoms");
        require(Arrays.equals(before, Files.readAllBytes(dir.resolve("src/demo/Calc.java"))), "verify writes no source");
        String refused = fails(() -> run(dir, manifest, "apply"));
        require(refused.contains("needs explicit approval") && Arrays.equals(before,
                Files.readAllBytes(dir.resolve("src/demo/Calc.java"))), "apply refused without approval: " + refused);
        PolyglotCli.main(new String[] {"plan", dir.toString(), manifest.toString(), "apply"});
    }

    /** The approval the engine does not have yet (design PR 4): plan with approval, run the argv as the runner would. */
    static void approvedStages(Path dir, String mvn, String id, byte[] before, byte[] after) throws Exception {
        String receipt = "out/apply/receipts/" + id + "/RECEIPT.tsv";
        approved(dir, new TaskManifest.Task("apply", "@m3:code-patch:patcher:text", ".", List.of("apply", mvn,
                codePatchVersion(), ".", "out/apply", "out/fix.patch", "approvedPlan=" + id), List.of(),
                List.of("out/fix.patch", "src"), List.of(receipt)));
        require(Arrays.equals(after, Files.readAllBytes(dir.resolve("src/demo/Calc.java"))), "apply wrote the after-image");
        show(dir.resolve(receipt));
        approved(dir, new TaskManifest.Task("rollback", "@m3:code-patch:patcher:text", ".", List.of("rollback", mvn,
                codePatchVersion(), ".", "out/apply", id), List.of(), List.of("src"), List.of(receipt)));
        require(Arrays.equals(before, Files.readAllBytes(dir.resolve("src/demo/Calc.java"))), "rollback restored bytes");
        PASSED.add("DIFFER text (git repo mode) -> PATCHER plan/verify/apply/rollback, CRLF byte-exact, plan " + id);
    }

    static void approved(Path dir, TaskManifest.Task task) throws Exception {
        M3ToolProviders.Planned planned = M3ToolProviders.plan(task, dir, true);
        int exit = new ProcessBuilder(planned.argv()).directory(dir.toFile()).inheritIO().start().waitFor();
        require(planned.successExitCodes().contains(exit), task.name() + " exit " + exit);
    }

    static String clustering(String name, String role, List<String> args, List<String> inputs, String output) {
        return task(name, "@m3:clustering:" + role + ":java", args, inputs, List.of(output));
    }

    static String patch(String name, List<String> args, List<String> outputs) {
        return task(name, "@m3:code-patch:patcher:text", args, List.of("out/fix.patch", "src"), outputs);
    }

    static String task(String name, String tool, List<String> args, List<String> inputs, List<String> outputs) {
        StringBuilder text = new StringBuilder("task\t" + name + "\t" + tool + "\t.\n");
        args.forEach(a -> text.append("arg\t").append(name).append('\t').append(a).append('\n'));
        inputs.forEach(i -> text.append("input\t").append(name).append('\t').append(i).append('\n'));
        outputs.forEach(o -> text.append("output\t").append(name).append('\t').append(o).append('\n'));
        return text.toString();
    }

    static Path manifest(Path dir, String... tasks) throws IOException {
        Path file = dir.resolve("m3-build.tsv");
        Files.writeString(file, TaskManifest.MAGIC + "\n" + String.join("", tasks), StandardCharsets.UTF_8);
        return file;
    }

    static void run(Path dir, Path manifest, String target) throws Exception {
        System.out.println("RUN " + target + " in " + dir);
        PolyglotCli.main(new String[] {"run", dir.toString(), manifest.toString(), target, "--timeout-seconds", "600"});
    }

    static void process(Path dir, String... command) throws Exception {
        int exit = new ProcessBuilder(command).directory(dir.toFile()).inheritIO().start().waitFor();
        require(exit == 0, "exit " + exit + ": " + String.join(" ", command));
    }

    @FunctionalInterface interface Attempt { void run() throws Exception; }

    static String fails(Attempt attempt) throws Exception {
        try {
            attempt.run();
        } catch (IOException expected) {
            System.out.println("EXPECTED FAILURE: " + expected.getMessage());
            return String.valueOf(expected.getMessage());
        }
        throw new AssertionError("the task must fail");
    }

    static String show(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        System.out.println("OUTPUT " + file + " (" + Files.size(file) + " bytes)\n"
                + text.lines().limit(12).map(l -> "  | " + l).reduce("", (a, b) -> a + b + "\n"));
        return text;
    }

    static String calc(String label, String eol) {
        return String.join(eol, "package demo;", "", "public class Calc {", "    public int add(int a, int b) {",
                "        return a + b;", "    }", "", "    public String label() {", "        return \"" + label + "\";",
                "    }", "}", "");
    }

    static Optional<String> property(String key) {
        return Optional.ofNullable(System.getProperty(key)).filter(v -> !v.isBlank());
    }

    static String git() {
        return System.getProperty("m3.git", "git");
    }

    static String codePatchVersion() {
        return System.getProperty("m3.codepatch.version", "0.1.0-SNAPSHOT");
    }

    static Path fresh(String name) throws IOException {
        Path dir = base.resolve(name);
        if (Files.exists(dir)) {
            try (var paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
        return Files.createDirectories(dir);
    }

    static void write(Path file, String text) throws IOException {
        writeBytes(file, text.getBytes(StandardCharsets.UTF_8));
    }

    static void writeBytes(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
