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
 * <p>The javac arguments are read once, as the host reads them ({@link JavacArguments}: javac's own option table, so
 * {@code -cp -d x.java} holds no {@code -d}). They need exactly one {@code -d} and at most one {@code -s} and one
 * {@code -h} (javac uses the last of each); every one of those folders must lie inside the workspace in declared
 * output space (a declared output, or a folder that holds or is held by one) and never in a declared input, so no
 * class or header lands beside the sources. javac {@code @argfiles} are refused because they would hide those
 * options. The task only reads sources ({@code READS_SOURCES}): Error Prone's patching options
 * ({@code -XepPatchChecks}, {@code -XepPatchLocation}, in place or into a folder) are refused wherever they appear,
 * also inside the {@code -Xplugin:ErrorProne ...} argument; a fix is applied through the code-patch patcher, with
 * approval. javac plug-ins and annotation processors on the processor path run as trusted code in the host JVM.
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
        checkOutputs(task, workspace, JavacArguments.parse(javac));
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

    /**
     * No @argfiles, no patching option, exactly one -d, at most one -s and one -h, and every one of those folders
     * inside the workspace in declared output space ({@link ToolArgs#outputFolder}), read as the host reads them.
     */
    private static void checkOutputs(TaskManifest.Task task, Path workspace, JavacArguments javac) throws IOException {
        Optional<String> refusal = javac.refusal();
        if (refusal.isPresent()) {
            throw new IOException(refusal.get() + ": " + USAGE);
        }
        if (javac.values("-d").size() != 1) {
            throw new IOException("exactly one -d <class folder in the declared outputs> is required: " + USAGE);
        }
        Optional<String> misplaced = JavacArguments.OUTPUT_FLAGS.stream().flatMap(flag -> javac.values(flag).stream())
                .map(folder -> ToolArgs.outputRefusal(workspace, task, folder)).flatMap(Optional::stream).findFirst();
        if (misplaced.isPresent()) {
            throw new IOException("javac " + misplaced.get());
        }
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
