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

import static org.apache.maven.plugins.polyglot.adapters.ToolAdaptersRealRunTestMain.NOT_RUN;
import static org.apache.maven.plugins.polyglot.adapters.ToolAdaptersRealRunTestMain.PASSED;
import static org.apache.maven.plugins.polyglot.adapters.ToolAdaptersRealRunTestMain.RESULT;
import static org.apache.maven.plugins.polyglot.adapters.ToolAdaptersRealRunTestMain.fails;
import static org.apache.maven.plugins.polyglot.adapters.ToolAdaptersRealRunTestMain.fresh;
import static org.apache.maven.plugins.polyglot.adapters.ToolAdaptersRealRunTestMain.manifest;
import static org.apache.maven.plugins.polyglot.adapters.ToolAdaptersRealRunTestMain.process;
import static org.apache.maven.plugins.polyglot.adapters.ToolAdaptersRealRunTestMain.property;
import static org.apache.maven.plugins.polyglot.adapters.ToolAdaptersRealRunTestMain.require;
import static org.apache.maven.plugins.polyglot.adapters.ToolAdaptersRealRunTestMain.run;
import static org.apache.maven.plugins.polyglot.adapters.ToolAdaptersRealRunTestMain.show;
import static org.apache.maven.plugins.polyglot.adapters.ToolAdaptersRealRunTestMain.task;
import static org.apache.maven.plugins.polyglot.adapters.ToolAdaptersRealRunTestMain.write;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The analyzer and linter real runs of {@link ToolAdaptersRealRunTestMain}: javac lint and Error Prone through the
 * javac plug-in host, clangd through the LSP collector, and every SARIF report read back by
 * m3-analyzer-report-adapter's NormalizeCli. Results go to the same PASSED and NOT_RUN lists.
 */
final class ToolAdaptersRealRunAnalyzers {
    private ToolAdaptersRealRunAnalyzers() { }

    static void javacLint() throws Exception {
        Path dir = fresh("analyzer-javac-lint");
        write(dir.resolve("src/demo/Raw.java"), "package demo;\nimport java.util.*;\npublic class Raw {\n"
                + "    public List names() { List list = new ArrayList(); list.add(\"x\"); return list; }\n}\n");
        Files.createDirectories(dir.resolve("out/classes"));
        Path manifest = manifest(dir, task("lint", "@m3:javac-plugins:static-analyzer:java", List.of("out/javac.jsonl",
                "out/javac.sarif", "parse=second", "-Xlint:all", "-d", "out/classes", "src/demo/Raw.java"), List.of("src"),
                List.of("out/javac.jsonl", "out/javac.sarif", "out/classes/demo/Raw.class")));
        run(dir, manifest, "lint");
        require(show(dir.resolve("out/javac.jsonl")).contains("\"rule\":\"rawtypes\""), "build-events lint key");
        require(show(dir.resolve("out/javac.sarif")).contains("\"ruleId\":\"compiler.warn.raw.class.use\""), "lint finding");
        roundTrip(dir, "out/javac.sarif");
        PASSED.add("STATIC_ANALYZER javac lint (parse=second opt-in; classes + SARIF): " + dir.resolve("out/javac.sarif"));
    }

    static void errorProne() throws Exception {
        Optional<String> path = property("m3.errorprone.path");
        if (path.isEmpty()) {
            NOT_RUN.add("STATIC_ANALYZER Error Prone (set -Dm3.errorprone.path)");
            return;
        }
        Path dir = errorProneProject();
        Path manifest = manifest(dir, ep("ep", path.get(), "src/demo/Ep.java", "out/ep-classes/demo/Ep.class"),
                ep("dead", path.get(), "bad/demo/Dead.java", "out/dead-classes/demo/Dead.class"));
        if (Runtime.version().feature() < 21) {
            errorProneRefused(dir, manifest);
            return;
        }
        errorProneRuns(dir, manifest);
    }

    /** Ep.java has a MissingOverride (WARNING); Dead.java a DeadException (ERROR). */
    static Path errorProneProject() throws IOException {
        Path dir = fresh("analyzer-errorprone");
        write(dir.resolve("src/demo/Ep.java"), "package demo;\npublic class Ep {\n"
                + "    public String toString() { return \"ep\"; }\n}\n");
        write(dir.resolve("bad/demo/Dead.java"), "package demo;\npublic class Dead {\n"
                + "    void check(int x) { if (x < 0) { new IllegalStateException(\"negative\"); } }\n}\n");
        List.of("out/ep-classes", "out/dead-classes").forEach(d -> dir.resolve(d).toFile().mkdirs());
        return dir;
    }

    static void errorProneRuns(Path dir, Path manifest) throws Exception {
        run(dir, manifest, "ep");
        require(show(dir.resolve("out/ep.sarif")).contains("\"ruleId\":\"MissingOverride\""), "Error Prone warning");
        show(dir.resolve("out/ep.jsonl"));
        roundTrip(dir, "out/ep.sarif");
        String failure = fails(() -> run(dir, manifest, "dead"));
        require(failure.contains("exit 1") && show(dir.resolve("out/dead.sarif")).contains("\"ruleId\":\"DeadException\""),
                "an ERROR finding fails the compile and the task, reports kept: " + failure);
        PASSED.add("STATIC_ANALYZER Error Prone -Xplugin in the host compile (MissingOverride; DeadException fails): "
                + dir.resolve("out/ep.sarif"));
    }

    /** Error Prone 2.52.0 class files are Java 21: on an older JDK the host must report, not crash silently. */
    static void errorProneRefused(Path dir, Path manifest) throws Exception {
        String failure = fails(() -> run(dir, manifest, "ep"));
        String sarif = show(dir.resolve("out/ep.sarif"));
        require(failure.contains("exit 2") && sarif.contains("\"executionSuccessful\":false"),
                "plug-in load failure reported as an unsuccessful invocation: " + failure);
        NOT_RUN.add("STATIC_ANALYZER Error Prone analysis on Java " + Runtime.version().feature()
                + " (its class files need Java 21); the refusal was reported in " + dir.resolve("out/ep.sarif"));
    }

    static void clangdLinter() throws Exception {
        Optional<String> clangd = property("m3.clangd");
        Optional<String> lsp4j = property("m3.lsp4j.path");
        if (clangd.isEmpty() || lsp4j.isEmpty()) {
            NOT_RUN.add("LINTER clangd over LSP (set -Dm3.clangd and -Dm3.lsp4j.path)");
            return;
        }
        Path dir = clangdProject();
        run(dir, manifest(dir, task("lint", "@m3:lsp:linter:c", clangdArgs(clangd.get(), lsp4j.get()), List.of("src"),
                List.of("out/clangd.jsonl", "out/clangd.sarif"))), "lint");
        clangdChecks(dir);
    }

    static Path clangdProject() throws IOException {
        Path dir = fresh("linter-clangd");
        write(dir.resolve("compile_flags.txt"), "-Wall\n-Wextra\n");
        // The unused local comes first: clang stops reporting unused locals once an error has occurred.
        write(dir.resolve("src/lint.c"), "int helper(int x) {\n    int unused = 1;\n    return x;\n}\n\n"
                + "int broken(int x) {\n    return x + undeclared_value;\n}\n");
        return dir;
    }

    static List<String> clangdArgs(String clangd, String lsp4j) {
        List<String> args = new ArrayList<>(List.of("out/clangd.jsonl", "out/clangd.sarif", "60000"));
        Arrays.stream(lsp4j.split(Pattern.quote(File.pathSeparator))).forEach(j -> args.add("jar=" + j));
        args.addAll(List.of("server=" + clangd, "server-arg=--log=error", "server-arg=--background-index=false",
                "src/lint.c"));
        return args;
    }

    static void clangdChecks(Path dir) throws Exception {
        String sarif = show(dir.resolve("out/clangd.sarif"));
        show(dir.resolve("out/clangd.jsonl"));
        require(sarif.contains("\"executionSuccessful\":true") && sarif.contains("unused-variable")
                && sarif.contains("\"level\":\"error\""), "clangd warning and error collected");
        roundTrip(dir, "out/clangd.sarif");
        PASSED.add("LINTER clangd diagnostics over LSP (COMPLETE): " + dir.resolve("out/clangd.sarif"));
    }

    /** The fork's own SARIF reader (m3-analyzer-report-adapter) must read the report and keep every result's rule. */
    static void roundTrip(Path dir, String sarif) throws Exception {
        Optional<String> analysis = property("m3.analysis.path");
        if (analysis.isEmpty()) {
            NOT_RUN.add("SARIF read-back of " + sarif + " (set -Dm3.analysis.path)");
            return;
        }
        String tsv = sarif.replace(".sarif", ".m3sa.tsv");
        process(dir, ToolArgs.java(), "-cp", analysis.get(), "io.synexia.maven.analysis.NormalizeCli", "SARIF",
                "COMPLETE", dir.toString(), sarif, tsv, sarif.replace(".sarif", ".m3sa.sha256"));
        String rows = show(dir.resolve(tsv));
        Matcher rules = RESULT.matcher(Files.readString(dir.resolve(sarif)));
        rules.results().map(rule -> rule.group(1))
                .forEach(rule -> require(rows.contains("." + rule + "\t"), "rule read back: " + rule));
        PASSED.add("SARIF read back by m3-analyzer-report-adapter NormalizeCli: " + dir.resolve(tsv));
    }

    static String ep(String name, String path, String source, String classFile) {
        List<String> args = new ArrayList<>(List.of("out/" + name + ".jsonl", "out/" + name + ".sarif", "parse=second"));
        Arrays.stream(path.split(Pattern.quote(File.pathSeparator))).forEach(j -> args.add("pp=" + j));
        args.addAll(List.of("-Xplugin:ErrorProne", "-Xlint:all", "-d", "out/" + name + "-classes", source));
        return task(name, "@m3:javac-plugins:static-analyzer:java", args, List.of(source.substring(0, source.indexOf('/'))),
                List.of("out/" + name + ".jsonl", "out/" + name + ".sarif", classFile));
    }
}
