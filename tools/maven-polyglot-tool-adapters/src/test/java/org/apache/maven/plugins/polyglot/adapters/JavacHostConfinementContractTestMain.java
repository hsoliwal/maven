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
import io.synexia.maven.polyglot.TaskManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What the static-analyzer task may write: never sources (Error Prone patching is refused at plan time and by the
 * host), javac output folders only once each and only in declared output space, and one classifier of javac
 * arguments shared by the provider and the host, so a value such as {@code -cp -d} cannot hide or fake a {@code -d}.
 * An annotation processor's ERROR without an element is a finding, not a failed invocation. JDK only.
 */
public final class JavacHostConfinementContractTestMain {
    private static final List<String> BASE = List.of("out/j.jsonl", "out/j.sarif", "parse=second");
    private static int passed;

    private JavacHostConfinementContractTestMain() { }

    public static void main(String[] ignored) throws Exception {
        Path ws = Files.createTempDirectory("javac-host-confinement-").toRealPath();
        try {
            errorPronePatchingIsRefusedAtPlan(ws);
            patchingPluginNeverRewritesSources(ws);
            everyOutputFolderOccurrenceIsChecked(ws);
            oneClassifierForProviderAndHost(ws);
            hiddenDashDIsNoOutputFolder(ws);
            processorErrorWithoutElementIsAFinding(ws);
            System.out.println("PASS: " + passed + " javac host confinement contract cases");
        } finally {
            delete(ws);
        }
    }

    private static void errorPronePatchingIsRefusedAtPlan(Path ws) {
        List<String> classes = List.of("out/classes");
        refuses(javac(ws, List.of("-Xplugin:ErrorProne -XepPatchChecks:MissingOverride -XepPatchLocation:IN_PLACE",
                "-d", "out/classes", "src/A.java"), classes), "Error Prone patching in place");
        refuses(javac(ws, List.of("-Xplugin:ErrorProne -XepPatchLocation:out/ep -XepPatchChecks:MissingOverride",
                "-d", "out/classes", "src/A.java"), List.of("out/classes", "out/ep")), "a patch folder");
        refuses(javac(ws, List.of("-Xplugin:ErrorProne\t-XepPatchChecks:MissingOverride", "-d", "out/classes",
                "src/A.java"), classes), "a patch option after a tab");
        refuses(javac(ws, List.of("-XepPatchLocation:IN_PLACE", "-d", "out/classes", "src/A.java"), classes),
                "a patch option as its own argument");
        require(!refused(javac(ws, List.of("-Xplugin:ErrorProne -XepDisableWarningsInGeneratedCode", "-d",
                "out/classes", "src/A.java"), classes)), "other Error Prone options stay allowed");
        passed++;
    }

    /** A javac plug-in named ErrorProne that rewrites every parsed source when asked to patch in place. */
    private static void patchingPluginNeverRewritesSources(Path ws) throws Exception {
        Path pp = patchingPlugin(ws.resolve("patching"));
        Path source = write(ws.resolve("patching/src/A.java"), "class A { }\n");
        Host host = host(ws.resolve("patching"), "-processorpath", pp.toString(),
                "-Xplugin:ErrorProne -XepPatchChecks:MissingOverride -XepPatchLocation:IN_PLACE", "-d",
                ws.resolve("patching/classes").toString(), source.toString());
        require(host.exit == 2 && Files.readString(source).equals("class A { }\n")
                && host.sarif.contains("\"executionSuccessful\":false"), "the host refuses patching options: " + host);
        passed++;
    }

    private static void everyOutputFolderOccurrenceIsChecked(Path ws) throws Exception {
        List<String> outputs = List.of("out/classes", "out/h", "out/gen");
        refuses(javac(ws, List.of("-d", "out/classes", "-h", "out/h", "-h", "src", "src/A.java"), outputs),
                "a second -h into the sources (javac uses the last)");
        refuses(javac(ws, List.of("-d", "out/classes", "-s", "out/gen", "-s", "src", "src/A.java"), outputs),
                "a second -s into the sources");
        refuses(javac(ws, List.of("-d", "out/classes", "-h", "out/h", "-h", "out/h", "src/A.java"), outputs),
                "a repeated -h even into the same folder");
        Path source = write(ws.resolve("dup/src/demo/N.java"), "package demo;\npublic class N { native void n(); }\n");
        Host host = host(ws.resolve("dup"), "-d", ws.resolve("dup/out/c").toString(), "-h",
                ws.resolve("dup/out/h").toString(), "-h", ws.resolve("dup/src").toString(), source.toString());
        require(host.exit == 2 && !Files.exists(ws.resolve("dup/src/demo_N.h")),
                "the host refuses a repeated -h and writes no header into the sources: " + host);
        passed++;
    }

    private static void oneClassifierForProviderAndHost(Path ws) throws Exception {
        JavacArguments split = JavacArguments.parse(List.of("-cp", "-d", "out/x.java", "src/A.java"));
        require(split.values("-d").isEmpty() && split.sources().equals(List.of("out/x.java", "src/A.java"))
                && split.options().equals(List.of("-cp", "-d")), "-d as the value of -cp is no -d: " + split);
        JavacArguments forms = JavacArguments.parse(List.of("--release=17", "-Xplugin:A b", "-Akey", "-d", "x.java",
                "-processorpath", "p.java", "B.java"));
        require(forms.values("-d").equals(List.of("x.java")) && forms.sources().equals(List.of("B.java")),
                "adjacent and separate values as javac reads them: " + forms);
        passed++;
    }

    private static void hiddenDashDIsNoOutputFolder(Path ws) throws Exception {
        refuses(javac(ws, List.of("-cp", "-d", "out/x.java", "src/A.java"), List.of("out/x.java")),
                "a -d that is the class path's value");
        write(ws.resolve("split/out/x.java"), "class X { }\n");
        Path source = write(ws.resolve("split/src/A.java"), "class A { }\n");
        Host host = host(ws.resolve("split"), "-cp", "-d", ws.resolve("split/out/x.java").toString(), source.toString());
        require(!Files.exists(ws.resolve("split/src/A.class")) && !Files.exists(ws.resolve("split/out/X.class")),
                "without a real -d the host compiles into a temporary folder: " + host);
        passed++;
    }

    private static void processorErrorWithoutElementIsAFinding(Path ws) throws Exception {
        Path pp = processor(ws.resolve("proc"));
        Path source = write(ws.resolve("proc/src/A.java"), "class A { }\n");
        Host host = host(ws.resolve("proc"), "-processorpath", pp.toString(), "-processor", "proc.NoElement", "-d",
                ws.resolve("proc/classes").toString(), source.toString());
        require(host.exit == 1 && host.sarif.contains("\"executionSuccessful\":true")
                && host.sarif.contains("\"ruleId\":\"compiler.err.proc.messager\"")
                && host.sarif.contains("module-info.java missing"), "a processor ERROR is an analysis result: " + host);
        passed++;
    }

    private static Path patchingPlugin(Path dir) throws IOException {
        Path source = write(dir.resolve("pp-src/fake/FakeErrorProne.java"), "package fake;\n"
                + "import com.sun.source.util.*;\nimport java.nio.file.*;\n"
                + "public class FakeErrorProne implements Plugin {\n    public String getName() { return \"ErrorProne\"; }\n"
                + "    public void init(JavacTask task, String... args) {\n"
                + "        if (!String.join(\" \", args).contains(\"-XepPatchLocation:IN_PLACE\")) return;\n"
                + "        task.addTaskListener(new TaskListener() { public void finished(TaskEvent e) {\n"
                + "            if (e.getKind() != TaskEvent.Kind.PARSE) return;\n"
                + "            try { Files.writeString(Path.of(e.getSourceFile().toUri()), \"class A { } // patched\\n\"); }\n"
                + "            catch (java.io.IOException x) { throw new RuntimeException(x); } } });\n    }\n}\n");
        return compiled(dir.resolve("pp"), source, "com.sun.source.util.Plugin", "fake.FakeErrorProne");
    }

    private static Path processor(Path dir) throws IOException {
        Path source = write(dir.resolve("pp-src/proc/NoElement.java"), "package proc;\n"
                + "import java.util.Set;\nimport javax.annotation.processing.*;\nimport javax.lang.model.SourceVersion;\n"
                + "import javax.lang.model.element.TypeElement;\nimport javax.tools.Diagnostic;\n"
                + "@SupportedAnnotationTypes(\"*\")\npublic class NoElement extends AbstractProcessor {\n"
                + "    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }\n"
                + "    public boolean process(Set<? extends TypeElement> a, RoundEnvironment round) {\n"
                + "        if (round.processingOver()) processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,"
                + " \"module-info.java missing\");\n        return false;\n    }\n}\n");
        return compiled(dir.resolve("pp"), source, "javax.annotation.processing.Processor", "proc.NoElement");
    }

    private static Path compiled(Path out, Path source, String service, String type) throws IOException {
        require(javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", out.toString(),
                source.toString()) == 0, "test plug-in compiles: " + source);
        write(out.resolve("META-INF/services/" + service), type + "\n");
        return out;
    }

    private static Attempt javac(Path ws, List<String> javac, List<String> extraOutputs) {
        List<String> args = new ArrayList<>(BASE);
        args.addAll(javac);
        List<String> outputs = new ArrayList<>(List.of("out/j.jsonl", "out/j.sarif"));
        outputs.addAll(extraOutputs);
        TaskManifest.Task task = new TaskManifest.Task("t", "@m3:javac-plugins:static-analyzer:java", ".", args,
                List.of(), List.of("src"), outputs);
        return () -> M3ToolProviders.plan(task, ws, false);
    }

    private record Host(int exit, String sarif) { }

    private static Host host(Path dir, String... javac) throws Exception {
        List<String> args = new ArrayList<>(List.of(dir.toString(), dir.resolve("out/d.jsonl").toString(),
                dir.resolve("out/d.sarif").toString(), "--"));
        args.addAll(List.of(javac));
        int exit = JavacPluginHost.run(args);
        Path sarif = dir.resolve("out/d.sarif");
        return new Host(exit, Files.exists(sarif) ? Files.readString(sarif) : "");
    }

    @FunctionalInterface private interface Attempt { M3ToolProviders.Planned plan() throws Exception; }

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
