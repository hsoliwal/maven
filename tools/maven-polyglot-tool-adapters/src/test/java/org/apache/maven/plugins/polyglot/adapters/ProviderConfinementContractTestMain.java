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

import io.synexia.maven.polyglot.M3Report;
import io.synexia.maven.polyglot.M3ReportFormat;
import io.synexia.maven.polyglot.M3ToolProviders;
import io.synexia.maven.polyglot.TaskManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Plan-time confinement through the real provider discovery: a command interpreter is never a launcher (cmd.exe
 * re-reads every argument, PowerShell and the POSIX shells take code), command() and reports() of a provider read
 * one parse of the arguments and every tool output folder stays inside the workspace in declared output space, the
 * first public {@code command} API refuses a source-writing task as {@code plan} does, and the git differ's
 * repository is a declared input. Planning only: no tool runs.
 */
public final class ProviderConfinementContractTestMain {
    private static int passed;
    private static Path workspace;

    private ProviderConfinementContractTestMain() { }

    public static void main(String[] ignored) throws Exception {
        Path ws = Files.createTempDirectory("provider-confinement-").toRealPath();
        Path outside = Files.createTempDirectory("provider-outside-").toRealPath();
        workspace = ws;
        try {
            cases(ws, outside);
            System.out.println("PASS: " + passed + " provider confinement contract cases");
        } finally {
            Files.delete(outside);
            Files.delete(ws);
        }
    }

    private static void cases(Path ws, Path outside) throws Exception {
        interpretersAreNeverLaunchers(ws);
        codePatchReadsOneParse(ws, outside);
        codePatchFoldersStayApart(outside);
        clusteringReadsOneParse(ws, outside);
        firstCommandApiIsGated(ws);
        repositoryIsADeclaredInput(ws, outside);
    }

    private static void interpretersAreNeverLaunchers(Path ws) {
        List<String> interpreters = List.of("cmd.exe", "C:\\Windows\\System32\\CMD.EXE", "cmd", "cmd.exe. ", "C:cmd.exe",
                "C:/Windows/System32/cmd.exe::$DATA", "powershell.exe", "C:/Windows/System32/WindowsPowerShell/v1.0/"
                + "powershell.exe", "pwsh", "pwsh.exe", "wscript.exe", "cscript", "mshta.exe",
                "C:\\Windows\\System32\\bash.exe", "/bin/sh", "/usr/bin/bash", "wsl.exe");
        List<String> accepted = interpreters.stream().filter(server -> !refused(lint(ws, server, "/c"))).toList();
        require(accepted.isEmpty(), "a command interpreter must never be a language server: " + accepted);
        require(refused(() -> plan(git("C:/Windows/System32/cmd.exe"))) && refused(() -> plan(git("pwsh"))),
                "nor the differ's git");
        require(!refused(lint(ws, "C:/Program Files/nodejs/node.exe", "server.js"))
                && !refused(lint(ws, "/opt/llvm/bin/clangd", "--log=error")), "ordinary executables stay allowed");
        passed++;
    }

    private static void codePatchReadsOneParse(Path ws, Path outside) throws Exception {
        refuses(() -> plan(patch(List.of("verify", "failOn=CHANGED", "mvn", "0.1.0-SNAPSHOT", "base",
                outside.toString(), "fix.patch"), List.of("base/PLAN.tsv"))), "an option first shifting out-dir away");
        M3ToolProviders.Planned first = plan(patch(List.of("verify", "failOn=CHANGED", "mvn", "0.1.0-SNAPSHOT", ".",
                "out/patch", "out/fix.patch"), List.of("out/patch/PLAN.tsv")));
        require(first.argv().contains("-Dpatch.outputDirectory=" + ws.resolve("out/patch"))
                && first.reports().equals(List.of(new M3Report("out/patch/PLAN.tsv", M3ReportFormat.PLAN_TSV))),
                "command and report read the same out-dir: " + first);
        passed++;
    }

    private static void codePatchFoldersStayApart(Path outside) {
        refuses(() -> plan(patch(List.of("plan", "mvn", "0.1.0-SNAPSHOT", "work", "work", "out/fix.patch"),
                List.of("work/PLAN.tsv"))), "an out-dir equal to the base-dir");
        refuses(() -> plan(patch(List.of("plan", "mvn", "0.1.0-SNAPSHOT", "work/a", "work", "out/fix.patch"),
                List.of("work/PLAN.tsv"))), "a base-dir inside the out-dir");
        refuses(() -> plan(patch(List.of("plan", "mvn", "0.1.0-SNAPSHOT", outside.toString(), "out/patch",
                "out/fix.patch"), List.of("out/patch/PLAN.tsv"))), "a base-dir outside the workspace");
        passed++;
    }

    private static void clusteringReadsOneParse(Path ws, Path outside) throws Exception {
        refuses(() -> plan(clustering("atomizer", List.of("tools/clustering.jar", "failOn=/../../../escaped", "out",
                "src"), List.of("src"), "out/ATOM_HASHES.tsv")), "an option shifting the atomizer out-dir");
        refuses(() -> plan(clustering("atomizer", List.of("cc.jar", outside.toString(), "src"), List.of("src"),
                "out/ATOM_HASHES.tsv")), "an atomizer out-dir outside the workspace");
        M3ToolProviders.Planned compare = plan(clustering("differ", List.of("cc.jar", "base.tsv", "out/cmp", "src",
                "failOn=CHANGED"), List.of("src", "base.tsv"), "out/cmp/COMPARE.tsv"));
        require(compare.argv().contains(ws.resolve("out/cmp").toString()), "differ out-dir planned: " + compare);
        passed++;
    }

    /** code-patch apply writes sources: the first v1 entry point must refuse it like an unapproved plan. */
    private static void firstCommandApiIsGated(Path ws) throws Exception {
        String id = "1ffd5405".repeat(8);
        TaskManifest.Task apply = patch(List.of("apply", "mvn", "0.1.0-SNAPSHOT", ".", "out/apply", "out/fix.patch",
                "approvedPlan=" + id), List.of("out/apply/receipts/" + id + "/RECEIPT.tsv"));
        refuses(() -> {
            M3ToolProviders.command(apply, ws);
            return null;
        }, "a source-writing argv from the first public command API");
        require(M3ToolProviders.plan(apply, ws, true).argv().get(0).equals("mvn"), "approved plan still works");
        passed++;
    }

    private static void repositoryIsADeclaredInput(Path ws, Path outside) throws Exception {
        refuses(() -> plan(new TaskManifest.Task("t", "@m3:git:differ:text", ".", List.of("repo", "git", "work",
                "HEAD", "out/r.patch"), List.of(), List.of(), List.of("out/r.patch"))), "an undeclared repository");
        refuses(() -> plan(new TaskManifest.Task("t", "@m3:git:differ:text", ".", List.of("repo", "git",
                outside.toString(), "HEAD", "out/r.patch"), List.of(), List.of("work"), List.of("out/r.patch"))),
                "a repository outside the workspace");
        require(!refused(() -> plan(new TaskManifest.Task("t", "@m3:git:differ:text", ".", List.of("repo", "git",
                "work", "HEAD", "out/r.patch"), List.of(), List.of("work"), List.of("out/r.patch")))), "declared");
        passed++;
    }

    private static Attempt lint(Path ws, String server, String serverArg) {
        List<String> all = new ArrayList<>(List.of("out/l.jsonl", "out/l.sarif", "30000", "jar=lsp/a.jar",
                "server=" + server, "server-arg=" + serverArg, "src/a.c"));
        TaskManifest.Task task = new TaskManifest.Task("t", "@m3:lsp:linter:c", ".", all, List.of(), List.of("src"),
                List.of("out/l.jsonl", "out/l.sarif"));
        return () -> M3ToolProviders.plan(task, ws, false);
    }

    private static TaskManifest.Task git(String git) {
        return new TaskManifest.Task("t", "@m3:git:differ:text", ".", List.of("trees", git, "before", "after",
                "out/f.patch"), List.of(), List.of("before", "after"), List.of("out/f.patch"));
    }

    private static TaskManifest.Task patch(List<String> args, List<String> outputs) {
        return new TaskManifest.Task("t", "@m3:code-patch:patcher:text", ".", args, List.of(),
                List.of("out/fix.patch", "src"), outputs);
    }

    private static TaskManifest.Task clustering(String role, List<String> args, List<String> inputs, String output) {
        return new TaskManifest.Task("t", "@m3:clustering:" + role + ":java", ".", args, List.of(), inputs,
                List.of(output));
    }

    /** Plans in this run's temporary workspace, without approving source writes. */
    private static M3ToolProviders.Planned plan(TaskManifest.Task task) throws IOException {
        return M3ToolProviders.plan(task, workspace, false);
    }

    @FunctionalInterface private interface Attempt { Object plan() throws Exception; }

    private static void refuses(Attempt attempt, String label) {
        require(refused(attempt), "must refuse " + label);
    }

    private static boolean refused(Attempt attempt) {
        try {
            attempt.plan();
            return false;
        } catch (IOException expected) {
            return true;
        } catch (Exception unexpected) {
            throw new AssertionError("unexpected failure", unexpected);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
