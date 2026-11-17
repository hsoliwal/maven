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

import io.synexia.maven.polyglot.M3AstStatus;
import io.synexia.maven.polyglot.M3Report;
import io.synexia.maven.polyglot.M3ReportFormat;
import io.synexia.maven.polyglot.M3ToolCapability;
import io.synexia.maven.polyglot.M3ToolEffect;
import io.synexia.maven.polyglot.M3ToolProviders;
import io.synexia.maven.polyglot.TaskManifest;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Argv and run-contract suite for every adapter, through the engine's public planning entry point and the
 * ServiceLoader registry (no external tool is started), plus the report writer and one in-process javac plug-in host
 * compilation (JDK only).
 */
public final class ToolAdaptersContractTestMain {
    private static final String ID = "1ffd54051ffd54051ffd54051ffd54051ffd54051ffd54051ffd54051ffd5405";
    private static int passed;

    private ToolAdaptersContractTestMain() { }

    public static void main(String[] ignored) throws Exception {
        Path ws = Files.createTempDirectory("m3-tool-adapters-").toRealPath();
        try {
            cases(ws);
            System.out.println("PASS: " + passed + " M3 tool adapter contract cases");
        } finally {
            delete(ws);
        }
    }

    private static void cases(Path ws) throws Exception {
        inventory();
        gitTrees(ws);
        gitRepo(ws);
        clustering(ws);
        clusteringRefusals(ws);
        codePatch(ws);
        codePatchRefusals(ws);
        codePatchApprovals(ws);
        javacPlugins(ws);
        lspLinter(ws);
    }

    private static void inventory() throws Exception {
        Map<String, List<String>> roles = M3ToolProviders.available().stream()
                .collect(Collectors.toMap(M3ToolProviders.Descriptor::id, M3ToolProviders.Descriptor::capabilities));
        require(roles.keySet().containsAll(Set.of("clustering", "code-patch", "git", "javac-plugins", "jdk", "lsp")),
                "all adapters and the built-in JDK provider are discovered: " + roles);
        require(roles.get("git").equals(List.of("differ")) && roles.get("code-patch").equals(List.of("patcher"))
                && roles.get("javac-plugins").equals(List.of("static-analyzer")) && roles.get("lsp").equals(List.of("linter"))
                && roles.get("clustering").containsAll(List.of("atomizer", "differ")), "capability slugs: " + roles);
        passed++;
    }

    private static void gitTrees(Path ws) throws Exception {
        var trees = plan(task("@m3:git:differ:text", List.of("trees", "git", "before", "after", "out/f.patch"),
                List.of("before", "after"), List.of("out/f.patch")), ws, false);
        require(trees.argv().get(3).equals(GitTextDifferProvider.MAIN) && trees.argv().subList(4, trees.argv().size())
                .equals(List.of("trees", "git", "before", "after", ws.resolve("out/f.patch").toString())),
                "trees argv: " + trees.argv());
        require(trees.successExitCodes().equals(Set.of(0)) && trees.effect() == M3ToolEffect.READS_SOURCES
                && trees.reports().equals(List.of(new M3Report("out/f.patch", M3ReportFormat.UNIFIED_DIFF))), "trees contract");
        refuses(() -> plan(task("@m3:git:differ:text", List.of("trees", "git", "before", "after", "out/f.patch"),
                List.of("before", "after"), List.of("out/other.patch")), ws, false), "undeclared report");
        passed++;
    }

    private static void gitRepo(Path ws) throws Exception {
        var repo = plan(task("@m3:git:differ:text", List.of("repo", "git", "work", "HEAD", "out/r.patch", "src"),
                List.of("work"), List.of("out/r.patch")), ws, false);
        require(repo.argv().subList(4, repo.argv().size()).equals(List.of("repo", "git", ws.resolve("work").toString(),
                "HEAD", ws.resolve("out/r.patch").toString(), "src")) && repo.successExitCodes().equals(Set.of(0)),
                "repo argv: " + repo.argv());
        refuses(() -> plan(task("@m3:git:differ:text", List.of("repo", "git", "work", "--output=x", "out/r.patch"),
                List.of("work"), List.of("out/r.patch")), ws, false), "revision read as an option");
        passed++;
    }

    private static void clustering(Path ws) throws Exception {
        var atoms = plan(task("@m3:clustering:atomizer:java", List.of("cc.jar", "out/atoms", "src"),
                List.of("src"), List.of("out/atoms/ATOM_HASHES.tsv")), ws, false);
        require(atoms.argv().subList(1, atoms.argv().size()).equals(List.of("-cp", ws.resolve("cc.jar").toString(),
                ClusteringProvider.MAIN, "cluster", ws.resolve("out/atoms").toString(), ws.resolve("src").toString())),
                "atomizer argv: " + atoms.argv());
        var compare = plan(task("@m3:clustering:differ:c", List.of("cc.jar", "base.tsv", "out/cmp", "src",
                "failOn=CHANGED,ADDED"), List.of("src", "base.tsv"), List.of("out/cmp/COMPARE.tsv")), ws, false);
        require(compare.argv().get(1).equals("-Dclustering.failOn=CHANGED,ADDED") && compare.argv().contains("compare")
                && compare.reports().get(0).format() == M3ReportFormat.COMPARE_TSV, "differ argv: " + compare.argv());
        passed++;
    }

    private static void clusteringRefusals(Path ws) throws Exception {
        refuses(() -> plan(task("@m3:clustering:differ:c", List.of("cc.jar", "base.tsv", "out/cmp", "src", "failOn=changed"),
                List.of("src", "base.tsv"), List.of("out/cmp/COMPARE.tsv")), ws, false), "lowercase failOn state");
        refuses(() -> plan(task("@m3:clustering:atomizer:java", List.of("cc.jar", "src/out", "src"),
                List.of("src/A.java"), List.of("src/out/ATOM_HASHES.tsv")), ws, false), "out folder inside a root");
        var provider = new ClusteringProvider();
        require(provider.astStatus(M3ToolCapability.DIFFER, "java") == M3AstStatus.COMPILER_FRONT_END
                && provider.astStatus(M3ToolCapability.DIFFER, "c") == M3AstStatus.SEPARATE_PARSE, "one-AST status");
        passed++;
    }

    private static void codePatch(Path ws) throws Exception {
        var planned = plan(patchTask(List.of("plan", "mvn", "0.1.0-SNAPSHOT", ".", "out/patch", "out/fix.patch"),
                List.of("out/patch/PLAN.tsv")), ws, false);
        require(planned.argv().equals(List.of("mvn", "--batch-mode", "--no-transfer-progress", "--offline",
                CodePatchProvider.PLUGIN + "0.1.0-SNAPSHOT:plan", "-Dpatch.baseDirectory=" + ws,
                "-Dpatch.outputDirectory=" + ws.resolve("out/patch"), "-Dpatch.patches=" + ws.resolve("out/fix.patch")))
                && planned.effect() == M3ToolEffect.READS_SOURCES, "plan argv: " + planned.argv());
        var verify = plan(patchTask(List.of("verify", "mvn", "0.1.0-SNAPSHOT", ".", "out/patch", "out/fix.patch",
                "failOn=CHANGED"), List.of("out/patch/PLAN.tsv")), ws, false);
        require(verify.argv().contains("-Dpatch.failOn=CHANGED") && verify.reports().size() == 1
                && verify.reports().get(0).format() == M3ReportFormat.PLAN_TSV, "verify contract: " + verify.reports());
        passed++;
    }

    private static void codePatchRefusals(Path ws) throws Exception {
        refuses(() -> plan(patchTask(List.of("plan", "mvn", "LATEST", ".", "out/patch", "out/fix.patch"),
                List.of("out/patch/PLAN.tsv")), ws, false), "unpinned plugin version");
        refuses(() -> plan(patchTask(List.of("plan", "C:/maven/bin/mvn.cmd", "0.1.0-SNAPSHOT", ".", "out/patch",
                "out/a&b.patch"), List.of("out/patch/PLAN.tsv")), ws, false), "cmd.exe metacharacter through mvn.cmd");
        require(plan(patchTask(List.of("plan", "/opt/maven/bin/mvn", "0.1.0-SNAPSHOT", ".", "out/patch", "out/a&b.patch"),
                List.of("out/patch/PLAN.tsv")), ws, false).argv().get(0).equals("/opt/maven/bin/mvn"),
                "a metacharacter is plain data for a launcher no shell interprets");
        refuses(() -> plan(patchTask(List.of("plan", "mvn", "0.1.0-SNAPSHOT", ".", "out/patch", "out/a,b.patch"),
                List.of("out/patch/PLAN.tsv")), ws, false), "comma in a patch path");
        passed++;
    }

    private static void codePatchApprovals(Path ws) throws Exception {
        TaskManifest.Task apply = patchTask(List.of("apply", "mvn", "0.1.0-SNAPSHOT", ".", "out/patch", "out/fix.patch",
                "approvedPlan=" + ID), List.of("out/patch/receipts/" + ID + "/RECEIPT.tsv"));
        refuses(() -> plan(apply, ws, false), "source write without approval");
        var approved = plan(apply, ws, true);
        require(approved.effect() == M3ToolEffect.WRITES_SOURCES && approved.argv().contains("-Dpatch.approvedPlan=" + ID)
                && approved.reports().get(0).format() == M3ReportFormat.RECEIPT_TSV, "approved apply: " + approved.argv());
        refuses(() -> plan(patchTask(List.of("apply", "mvn", "0.1.0-SNAPSHOT", ".", "out/patch", "out/fix.patch"),
                List.of("out/patch/PLAN.tsv")), ws, true), "apply without a reviewed plan id");
        codePatchRollback(ws);
    }

    private static void codePatchRollback(Path ws) throws Exception {
        var rollback = plan(patchTask(List.of("rollback", "mvn", "0.1.0-SNAPSHOT", ".", "out/patch", ID),
                List.of("out/patch/receipts/" + ID + "/RECEIPT.tsv")), ws, true);
        require(rollback.argv().contains("-Dpatch.receipt=" + ID) && rollback.effect() == M3ToolEffect.WRITES_SOURCES,
                "rollback argv: " + rollback.argv());
        refuses(() -> plan(patchTask(List.of("rollback", "mvn", "0.1.0-SNAPSHOT", ".", "out/patch", "../x"),
                List.of("out/patch/PLAN.tsv")), ws, true), "receipt id that is not a plan id");
        passed++;
    }

    private static void javacPlugins(Path ws) throws Exception {
        var host = plan(task("@m3:javac-plugins:static-analyzer:java", List.of("out/j.jsonl", "out/j.sarif", "parse=second",
                "pp=ep/a.jar", "pp=ep/b.jar", "-Xplugin:ErrorProne", "-d", "out/classes", "src/A.java"), List.of("src"),
                List.of("out/j.jsonl", "out/j.sarif", "out/classes")), ws, false);
        List<String> argv = host.argv();
        int split = argv.indexOf("--");
        require(argv.containsAll(JavacPluginHostProvider.JVM_ACCESS) && argv.get(split - 4).equals(JavacPluginHostProvider.HOST)
                && argv.subList(split - 3, split).equals(List.of(ws.toString(), ws.resolve("out/j.jsonl").toString(),
                ws.resolve("out/j.sarif").toString())), "host argv: " + argv);
        require(argv.subList(split + 1, split + 3).equals(List.of("-processorpath",
                ws.resolve("ep/a.jar") + File.pathSeparator + ws.resolve("ep/b.jar")))
                && argv.containsAll(JavacPluginHostProvider.ERROR_PRONE_JAVAC) && argv.get(argv.size() - 1).equals("src/A.java"),
                "processor path and Error Prone options: " + argv);
        javacLintOnly(ws);
    }

    private static void javacLintOnly(Path ws) throws Exception {
        var lint = plan(task("@m3:javac-plugins:static-analyzer:java", List.of("out/j.jsonl", "out/j.sarif", "parse=second",
                "-Xlint:all", "-d", "out/classes", "src/A.java"), List.of("src"), List.of("out/j.jsonl", "out/j.sarif",
                "out/classes/A.class")), ws, false);
        require(!lint.argv().contains("-processorpath") && Collections.disjoint(lint.argv(),
                JavacPluginHostProvider.ERROR_PRONE_JAVAC) && lint.reports().size() == 2, "javac lint only: " + lint.argv());
        require(new JavacPluginHostProvider().astStatus(M3ToolCapability.STATIC_ANALYZER, "java")
                == M3AstStatus.COMPILER_FRONT_END, "a second javac parse next to the build's compile");
        passed++;
    }

    private static void lspLinter(Path ws) throws Exception {
        List<String> args = new ArrayList<>(List.of("out/l.jsonl", "out/l.sarif", "30000", "jar=lsp/a.jar", "jar=lsp/b.jar",
                "server=/opt/llvm/bin/clangd", "server-arg=--log=error", "src/a.c"));
        var lint = plan(task("@m3:lsp:linter:c", args, List.of("src"), List.of("out/l.jsonl", "out/l.sarif")), ws, false);
        List<String> argv = lint.argv();
        require(argv.get(2).equals(ToolArgs.home(LspLinterProvider.class) + File.pathSeparator + ws.resolve("lsp/a.jar")
                + File.pathSeparator + ws.resolve("lsp/b.jar")) && argv.get(3).equals(LspLinterProvider.MAIN)
                && argv.subList(7, argv.size()).equals(List.of("30000", "2000", "c", "/opt/llvm/bin/clangd", "--log=error", "--",
                ws.resolve("src/a.c").toString())), "linter argv: " + argv);
        require(lint.reports().get(1).format() == M3ReportFormat.SARIF_2_1_0
                && new LspLinterProvider().astStatus(M3ToolCapability.LINTER, "c") == M3AstStatus.LANGUAGE_SERVER, "contract");
        lspRefusals(ws, args);
    }

    private static void lspRefusals(Path ws, List<String> args) throws Exception {
        lintRefused(ws, args, "30000", "0", "zero deadline");
        lintRefused(ws, args, "30000", "600001", "deadline over ten minutes");
        lintRefused(ws, args, "server=/opt/llvm/bin/clangd", "server-arg=--x", "no server");
        List<String> noJars = args.stream().filter(a -> !a.startsWith("jar=")).collect(Collectors.toList());
        noJars.add("src/b.c");
        lintRefused(ws, noJars, "src/b.c", "src/c.c", "no jars");
        passed++;
    }

    /** Plans with one argument replaced, which must be refused. */
    private static void lintRefused(Path ws, List<String> args, String from, String to, String label) throws Exception {
        List<String> broken = new ArrayList<>(args);
        broken.set(broken.indexOf(from), to);
        TaskManifest.Task task = task("@m3:lsp:linter:c", broken, List.of("src"), List.of("out/l.jsonl", "out/l.sarif"));
        refuses(() -> plan(task, ws, false), "linter with " + label);
    }

    private static M3ToolProviders.Planned plan(TaskManifest.Task task, Path ws, boolean approve) throws IOException {
        return M3ToolProviders.plan(task, ws, approve);
    }

    private static TaskManifest.Task task(String tool, List<String> args, List<String> inputs, List<String> outputs) {
        return new TaskManifest.Task("t", tool, ".", args, List.of(), inputs, outputs);
    }

    private static TaskManifest.Task patchTask(List<String> args, List<String> outputs) {
        return task("@m3:code-patch:patcher:text", args, List.of("out/fix.patch", "src"), outputs);
    }

    @FunctionalInterface private interface Attempt { void run() throws Exception; }

    private static void refuses(Attempt attempt, String label) throws Exception {
        try {
            attempt.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("must refuse " + label);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void delete(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }
}
