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
import io.synexia.maven.polyglot.M3ToolCapability;
import io.synexia.maven.polyglot.M3ToolProviders;
import io.synexia.maven.polyglot.TaskManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Failure contracts of the adapters (no external tool except git and the running JDK): nested .cmd/.bat launchers
 * never receive cmd.exe metacharacters, javac diagnostics on jar entries and missing plug-ins, no class output next
 * to sources, UTF-16 columns, the build-events vocabulary, and the explicit second-parse opt-in. The git cases are in
 * {@link GitDifferContractTestMain}.
 */
public final class ToolAdaptersFailureContractTestMain {
    private static int passed;

    private ToolAdaptersFailureContractTestMain() { }

    public static void main(String[] ignored) throws Exception {
        Path ws = Files.createTempDirectory("tool-adapter-failures-").toRealPath();
        try {
            cases(ws);
            System.out.println("PASS: " + passed + " tool adapter failure contract cases");
        } finally {
            delete(ws);
        }
    }

    private static void cases(Path ws) throws Exception {
        nestedLaunchersRefuseMetacharacters(ws);
        jarEntryDiagnosticIsAFinding(ws);
        missingPluginIsAnInvocationFailure(ws);
        classesNeverLandNextToSources(ws);
        secondParseIsAnExplicitOptIn(ws);
        columnsCountUtf16Units(ws);
        eventsUseTheBuildEventsVocabulary(ws);
        codePatchHasNoExpectPlan(ws);
        olderEngineIsNamed();
    }

    /** An engine without the additive kinds: NoSuchFieldError becomes the refusal every registry already handles. */
    private static void olderEngineIsNamed() {
        try {
            ToolArgs.kinds(() -> {
                throw new NoSuchFieldError("M3ToolCapability does not have member field DIFFER");
            });
        } catch (IllegalStateException refused) {
            require(refused.getMessage().contains("additive tool kinds"), "names the engine it needs: " + refused);
            passed++;
            return;
        }
        throw new AssertionError("a LinkageError must become an IllegalStateException");
    }

    private static void nestedLaunchersRefuseMetacharacters(Path ws) throws Exception {
        List<String> args = List.of("out/l.jsonl", "out/l.sarif", "30000", "jar=lsp/a.jar");
        List<String> accepted = List.of("x&md,pwned", "a%PATHEXT%b", "q^w", "a\"b", "x|y", "a>b", "a!b").stream()
                .filter(unsafe -> !refused(lint(ws, args, "server=C:/node/pyright-langserver.cmd", "server-arg=" + unsafe)))
                .toList();
        require(accepted.isEmpty(), "a .cmd language server must never receive: " + accepted);
        otherLaunchers(ws, args);
        passed++;
    }

    private static void otherLaunchers(Path ws, List<String> args) throws Exception {
        refuses(lint(ws, args, "server=C:/x&y/server.bat", "server-arg=--stdio"), "metacharacter in a .bat server path");
        refuses(lint(ws, args, "server=C:/tools/SERVER.CMD. ", "server-arg=a&b"), "trailing dot and space hide .cmd");
        require(plan(lint(ws, args, "server=/opt/llvm/bin/clangd", "server-arg=a&b")).argv().contains("a&b"),
                "no shell reads the arguments of an ordinary executable");
        refuses(() -> plan(gitTask("C:/git/cmd/git.cmd", "aft&r")), "metacharacter for a .cmd git");
        refuses(() -> plan(gitTask("git", "aftr")), "an after tree that is not a declared input");
        require(refused(() -> {
            ToolArgs.nested(List.of("tool.cmd", "line\nbreak"));
            return null;
        }), "a line break ends a cmd.exe command");
    }

    private static Attempt lint(Path ws, List<String> args, String server, String serverArg) {
        List<String> all = new ArrayList<>(args);
        all.addAll(List.of(server, serverArg, "src/a.c"));
        TaskManifest.Task task = new TaskManifest.Task("t", "@m3:lsp:linter:c", ".", all, List.of(), List.of("src"),
                List.of("out/l.jsonl", "out/l.sarif"));
        return () -> M3ToolProviders.plan(task, ws, false);
    }

    /** The after tree is declared as "after"; the argument names {@code after}. */
    private static TaskManifest.Task gitTask(String git, String after) {
        return new TaskManifest.Task("t", "@m3:git:differ:text", ".", List.of("trees", git, "before", after,
                "out/f.patch"), List.of(), List.of("before", after.contains("&") ? after : "after"), List.of("out/f.patch"));
    }

    /** A class file inside a jar whose annotation type is missing: javac warns with the jar entry as source. */
    private static void jarEntryDiagnosticIsAFinding(Path ws) throws Exception {
        Path jar = annotatedJar(ws.resolve("jarentry"));
        Path app = write(ws.resolve("jarentry/app/App.java"), "class App { void f() { lib.Use.m(); } }\n");
        Host host = host(ws.resolve("jarentry"), "-Xlint:all", "-cp", jar.toString(), "-d",
                ws.resolve("jarentry/classes").toString(), app.toString());
        require(host.exit == 0 && host.sarif.contains("\"executionSuccessful\":true")
                && host.sarif.contains("\"uri\":\"jar:") && host.sarif.contains("Cannot find annotation method")
                && Files.isRegularFile(ws.resolve("jarentry/classes/App.class")),
                "a warning located in a jar entry is a finding, the compile completes: " + host);
        passed++;
    }

    private static Path annotatedJar(Path dir) throws Exception {
        Path ann = write(dir.resolve("lib/lib/Ann.java"), "package lib;\nimport java.lang.annotation.*;\n"
                + "@Retention(RetentionPolicy.CLASS) public @interface Ann { String value(); }\n");
        Path use = write(dir.resolve("lib/lib/Use.java"), "package lib;\n@Ann(\"x\") public class Use {"
                + " public static void m() { } }\n");
        require(javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d",
                dir.resolve("libout").toString(), ann.toString(), use.toString()) == 0, "library compiles");
        Path jar = dir.resolve("lib.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("lib/Use.class"));
            out.write(Files.readAllBytes(dir.resolve("libout/lib/Use.class")));
            out.closeEntry();
        }
        return jar;
    }

    private static void missingPluginIsAnInvocationFailure(Path ws) throws Exception {
        Path source = write(ws.resolve("noplugin/src/A.java"), "class A { }\n");
        Host host = host(ws.resolve("noplugin"), "-Xplugin:NoSuchPlugin", "-d",
                ws.resolve("noplugin/classes").toString(), source.toString());
        require(host.exit == 2 && host.sarif.contains("\"executionSuccessful\":false")
                && host.sarif.contains("\"results\":[]") && host.sarif.contains("plugin.not.found"),
                "a plug-in that is not found is an unsuccessful invocation, not an ERROR finding: " + host);
        passed++;
    }

    private static void classesNeverLandNextToSources(Path ws) throws Exception {
        Path source = write(ws.resolve("nod/src/Tab.java"), "class Tab { }\n");
        Host host = host(ws.resolve("nod"), "-Xlint:none", source.toString());
        require(host.exit == 0 && !Files.exists(ws.resolve("nod/src/Tab.class")),
                "without -d the host compiles into a temporary folder, never beside the source: " + host);
        List<String> base = List.of("out/j.jsonl", "out/j.sarif", "parse=second", "-Xlint:all");
        refuses(javac(ws, base, List.of("src/A.java"), List.of()), "no -d");
        refuses(javac(ws, base, List.of("-d", "src", "src/A.java"), List.of()), "-d is a source folder");
        refuses(javac(ws, base, List.of("-d", "out/classes", "-s", "src/gen", "src/A.java"), List.of("out/classes")),
                "-s outside the declared outputs");
        refuses(javac(ws, base, List.of("-d", "out/classes", "@args.txt"), List.of("out/classes")), "an @argfile");
        require(plan(javac(ws, base, List.of("-d", "out/classes", "src/A.java"), List.of("out/classes"))).argv()
                .contains("-d"), "-d under a declared output is planned");
        passed++;
    }

    private static void secondParseIsAnExplicitOptIn(Path ws) throws Exception {
        List<String> noOptIn = List.of("out/j.jsonl", "out/j.sarif", "-Xlint:all");
        refuses(javac(ws, noOptIn, List.of("-d", "out/classes", "src/A.java"), List.of("out/classes")),
                "a second Java compile without parse=second");
        require(new JavacPluginHostProvider().astStatus(M3ToolCapability.STATIC_ANALYZER, "java")
                == M3AstStatus.COMPILER_FRONT_END, "the host's compile is a second javac parse until it is the build's");
        passed++;
    }

    private static Attempt javac(Path ws, List<String> base, List<String> javac, List<String> extraOutputs) {
        List<String> args = new ArrayList<>(base);
        args.addAll(javac);
        List<String> outputs = new ArrayList<>(List.of("out/j.jsonl", "out/j.sarif"));
        outputs.addAll(extraOutputs);
        TaskManifest.Task task = new TaskManifest.Task("t", "@m3:javac-plugins:static-analyzer:java", ".", args,
                List.of(), List.of("src"), outputs);
        return () -> M3ToolProviders.plan(task, ws, false);
    }

    /** javac expands a tab to the next multiple of 8; SARIF counts UTF-16 code units. */
    private static void columnsCountUtf16Units(Path ws) throws Exception {
        String line = "\tvoid f() { java.util.List l = null; }";
        Path source = write(ws.resolve("tab/src/Tab.java"), "class Tab {\n" + line + "\n}\n");
        Host host = host(ws.resolve("tab"), "-Xlint:rawtypes", "-d", ws.resolve("tab/classes").toString(),
                source.toString());
        // javac points at the '.' of java.util.List; with the tab expanded to 8 it would report column 29.
        int expected = line.indexOf(".List l") + 1;
        require(expected == 22 && host.sarif.contains("\"startLine\":2,\"startColumn\":" + expected + "}"),
                "column " + expected + " in UTF-16 units: " + host.sarif);
        require(JavacDiagnostics.utf16Column("ab\r\n\tcd", 5) == 2 && JavacDiagnostics.utf16Column("x", 0) == 1,
                "column after CRLF and a tab");
        passed++;
    }

    private static void eventsUseTheBuildEventsVocabulary(Path ws) throws Exception {
        Path root = ws.resolve("vocab");
        EventVocabulary.Entry raw = EventVocabulary.javac("found raw type: List\n  missing type arguments", root);
        EventVocabulary.Entry check = EventVocabulary.javac("[DeadException] Exception created but not thrown", root);
        EventVocabulary.Entry symbol = EventVocabulary.javac("cannot find symbol\n  symbol:   variable x\n"
                + "  location: class " + root.resolve("A") + " x", root);
        require(raw.equals(new EventVocabulary.Entry("rawtypes", "found raw type: List"))
                && check.equals(new EventVocabulary.Entry("", "[DeadException] Exception created but not thrown"))
                && EventVocabulary.javac("[rawtypes] found raw type: Map", root).rule().equals("rawtypes"),
                "javac lint key as rule, plug-in prefix kept in the message: " + raw + " " + check);
        require(symbol.message().equals("cannot find symbol; symbol: variable x; location: class A x"),
                "continuation lines joined, root made relative: " + symbol);
        eventsFromTheHost(ws, root);
    }

    private static void eventsFromTheHost(Path ws, Path root) throws Exception {
        require(EventVocabulary.clean("x".repeat(400), root).length() == EventVocabulary.MESSAGE_CHARS
                && EventVocabulary.clean("https://u:secret@h/p", root).equals("https://***@h/p"), "bounded, redacted");
        Path source = write(ws.resolve("vocab/src/Raw.java"), "class Raw { java.util.List l; }\n");
        Host host = host(root, "-Xlint:all", "-d", ws.resolve("vocab/classes").toString(), source.toString());
        require(host.jsonl.contains("\"source\":\"compiler\"") && host.jsonl.contains("\"rule\":\"rawtypes\"")
                && host.sarif.contains("\"ruleId\":\"compiler.warn.raw.class.use\""), "events and SARIF: " + host);
        passed++;
    }

    private static void codePatchHasNoExpectPlan(Path ws) throws Exception {
        String id = "1ffd5405".repeat(8);
        refuses(() -> plan(new TaskManifest.Task("t", "@m3:code-patch:patcher:text", ".", List.of("verify", "mvn",
                "0.1.0-SNAPSHOT", ".", "out/patch", "out/fix.patch", "expectPlan=" + id), List.of(),
                List.of("out/fix.patch", "src"), List.of("out/patch/PLAN.tsv"))), "expectPlan, which code-patch lacks");
        passed++;
    }

    private record Host(int exit, String sarif, String jsonl) { }

    private static Host host(Path dir, String... javac) throws Exception {
        List<String> args = new ArrayList<>(List.of(dir.toString(), dir.resolve("out/d.jsonl").toString(),
                dir.resolve("out/d.sarif").toString(), "--"));
        args.addAll(List.of(javac));
        int exit = JavacPluginHost.run(args);
        return new Host(exit, Files.readString(dir.resolve("out/d.sarif")), Files.readString(dir.resolve("out/d.jsonl")));
    }

    private static M3ToolProviders.Planned plan(Attempt attempt) throws Exception {
        return attempt.plan();
    }

    private static M3ToolProviders.Planned plan(TaskManifest.Task task) throws IOException {
        return M3ToolProviders.plan(task, Path.of(".").toAbsolutePath(), false);
    }

    @FunctionalInterface private interface Attempt { M3ToolProviders.Planned plan() throws Exception; }

    private static void refuses(Attempt attempt, String label) {
        require(refused(attempt), "must refuse " + label);
    }

    /** True when the attempt ends in an IOException; any other failure propagates as an error. */
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

    private static Path write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void delete(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
        }
    }
}
