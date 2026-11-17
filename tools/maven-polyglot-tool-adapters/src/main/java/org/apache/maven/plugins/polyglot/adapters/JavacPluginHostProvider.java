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
import io.synexia.maven.polyglot.M3ToolProvider;
import io.synexia.maven.polyglot.TaskManifest;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@code @m3:javac-plugins:static-analyzer:java}: one javac compilation in which javac plug-ins (Error Prone,
 * NullAway as an Error Prone plug-in, ...) and javac's own lint run on that compilation's AST. Arguments:
 * {@code <out diagnostics.jsonl> <out findings.sarif> parse=second [pp=<processor-path entry>]... <javac-arg>...}.
 * <p>One-AST rule: this compilation is a second javac parse next to the build's own compile (maven-compiler-plugin
 * or {@code @m3:jdk:compiler:java}), so its status is {@link M3AstStatus#COMPILER_FRONT_END} and it runs only on the
 * explicit opt-in {@code parse=second}. Analysis without a second parse puts {@code -Xplugin:...} and the processor
 * path on the build's own compile ({@code @m3:jdk:compiler:java} forwards them unchanged); this host gives way once
 * the diagnostics capture runs inside that compile.
 * <p>The javac arguments need exactly one {@code -d}; it and any {@code -s} or {@code -h} folder must lie in declared
 * output space (a declared output, or a folder that holds or is held by one) and never in a declared input, so no
 * class lands beside the sources. javac {@code @argfiles} are refused because they would hide those options.
 * {@code pp=} entries become one {@code -processorpath} in the host platform's syntax; with
 * {@code -Xplugin:ErrorProne} the options Error Prone requires of its compilation are added. The host exits 1 when
 * the compilation fails with an ERROR finding and 2 when the run is incomplete; both reports are written either way.
 */
public final class JavacPluginHostProvider implements M3ToolProvider {
    static final String HOST = "org.apache.maven.plugins.polyglot.adapters.JavacPluginHost";
    static final String SECOND_PARSE = "parse=second";
    static final String USAGE = "<out diagnostics.jsonl> <out findings.sarif> " + SECOND_PARSE
            + " [pp=<jar>]... <javac-arg>... (with -d <declared output folder>)";
    /** javac plug-ins reach compiler internals that JDK 16+ encapsulates. */
    static final List<String> JVM_ACCESS = List.of(
            "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
            "--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
            "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED");
    /** What Error Prone 2.5x requires of the compilation it runs in (it refuses to start without them on JDK 21). */
    static final List<String> ERROR_PRONE_JAVAC = List.of("-XDcompilePolicy=simple", "--should-stop=ifError=FLOW",
            "-XDaddTypeAnnotationsToSymbol=true");
    private static final List<String> OPTIONS = List.of("pp", "parse");
    private static final System.Logger LOG = System.getLogger(JavacPluginHostProvider.class.getName());

    @Override public String id() {
        return "javac-plugins";
    }

    @Override public Set<M3ToolCapability> capabilities() {
        return ToolArgs.kinds(() -> Set.of(M3ToolCapability.STATIC_ANALYZER));
    }

    @Override public Set<String> languages() {
        return Set.of("java");
    }

    @Override public List<String> command(M3ToolCapability capability, String language, TaskManifest.Task task,
            Path workspace) throws IOException {
        List<String> args = ToolArgs.count(task, 4, 1024, USAGE);
        secondParse(args);
        List<String> javac = javac(task);
        checkOutputs(task, javac);
        List<String> argv = new ArrayList<>(host(task, workspace));
        argv.addAll(processorPath(args, task, workspace));
        if (javac.stream().anyMatch(a -> a.startsWith("-Xplugin:ErrorProne"))) argv.addAll(ERROR_PRONE_JAVAC);
        argv.addAll(javac);
        LOG.log(System.Logger.Level.DEBUG, "planned javac plug-in host: {0}", argv);
        return ToolArgs.launch(argv);
    }

    /** The second javac parse is an explicit choice of the task, never a default. */
    private static void secondParse(List<String> args) throws IOException {
        if (!ToolArgs.option(args, "parse").filter("second"::equals).isPresent()) {
            throw new IOException("the javac plug-in host compiles the sources a second time next to the build's"
                    + " compile; put -Xplugin on the build's compile (@m3:jdk:compiler:java forwards it unchanged) or"
                    + " opt in with " + SECOND_PARSE + ": " + USAGE);
        }
    }

    /** java with the compiler access plug-ins need, the host class, root, the two report paths and "--". */
    private static List<String> host(TaskManifest.Task task, Path workspace) throws IOException {
        List<String> positional = ToolArgs.positional(task.arguments(), OPTIONS);
        return Stream.of(List.of(ToolArgs.java()), JVM_ACCESS, List.of("-cp", ToolArgs.home(JavacPluginHostProvider.class),
                HOST, workspace.resolve(task.workingDir()).toAbsolutePath().normalize().toString(),
                ToolArgs.absolute(workspace, task, positional.get(0)), ToolArgs.absolute(workspace, task, positional.get(1)),
                "--")).flatMap(List::stream).toList();
    }

    private static List<String> javac(TaskManifest.Task task) {
        List<String> positional = ToolArgs.positional(task.arguments(), OPTIONS);
        return positional.subList(Math.min(2, positional.size()), positional.size());
    }

    /** One -d; -d, -s and -h folders in declared output space and never in a source input; no @argfiles. */
    private static void checkOutputs(TaskManifest.Task task, List<String> javac) throws IOException {
        if (javac.stream().anyMatch(a -> a.startsWith("@"))) {
            throw new IOException("javac @argfiles are refused: they would hide -d, -s and -h: " + USAGE);
        }
        if (javac.stream().filter("-d"::equals).count() != 1) {
            throw new IOException("exactly one -d <class folder in the declared outputs> is required: " + USAGE);
        }
        Optional<String> misplaced = Stream.of("-d", "-s", "-h").map(flag -> folder(javac, flag))
                .flatMap(Optional::stream).map(f -> ToolArgs.declared(task, f)).filter(f -> !outputSpace(task, f))
                .findFirst();
        if (misplaced.isPresent()) {
            throw new IOException("a javac output folder must be declared output space, not a source: " + misplaced.get());
        }
    }

    private static Optional<String> folder(List<String> javac, String flag) {
        int at = javac.indexOf(flag);
        return at < 0 ? Optional.empty() : Optional.of(at + 1 < javac.size() ? javac.get(at + 1) : "");
    }

    /** Declared output space: related to a declared output, unrelated to every declared input, not the root. */
    static boolean outputSpace(TaskManifest.Task task, String folder) {
        boolean source = folder.isEmpty() || task.inputs().stream().anyMatch(input -> related(input, folder));
        return !source && task.outputs().stream().anyMatch(output -> related(output, folder));
    }

    private static boolean related(String a, String b) {
        return a.equals(b) || a.startsWith(b + "/") || b.startsWith(a + "/");
    }

    private static List<String> processorPath(List<String> args, TaskManifest.Task task, Path workspace) {
        List<String> entries = args.stream().filter(a -> a.startsWith("pp="))
                .map(a -> ToolArgs.absolute(workspace, task, a.substring(3))).toList();
        return entries.isEmpty() ? List.of() : List.of("-processorpath", String.join(File.pathSeparator, entries));
    }

    @Override public List<M3Report> reports(M3ToolCapability capability, String language, TaskManifest.Task task) {
        List<String> positional = ToolArgs.positional(task.arguments(), OPTIONS);
        return List.of(new M3Report(ToolArgs.declared(task, positional.get(0)), M3ReportFormat.BUILD_EVENTS_JSONL_V1),
                new M3Report(ToolArgs.declared(task, positional.get(1)), M3ReportFormat.SARIF_2_1_0));
    }

    /** A second javac parse with the real compiler until the capture runs inside the build's compile. */
    @Override public M3AstStatus astStatus(M3ToolCapability capability, String language) {
        return M3AstStatus.COMPILER_FRONT_END;
    }
}
