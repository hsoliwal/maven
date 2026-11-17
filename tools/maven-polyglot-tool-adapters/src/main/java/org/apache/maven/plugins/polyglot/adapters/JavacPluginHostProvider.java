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
import java.util.Set;

/**
 * {@code @m3:javac-plugins:static-analyzer:java}: one javac compilation in which javac plug-ins (Error Prone,
 * NullAway as an Error Prone plug-in, ...) and javac's own lint run on the compiler's AST; the compilation also
 * writes the classes, so the build keeps one Java parse. Arguments:
 * {@code <out diagnostics.jsonl> <out findings.sarif> [pp=<processor-path entry>]... <javac-arg>...}.
 * {@code pp=} entries become one {@code -processorpath} in the host platform's syntax. When a javac argument starts
 * with {@code -Xplugin:ErrorProne}, the options Error Prone requires of its compilation are added. The host process
 * exits 1 when the compilation fails (an ERROR finding), which fails the task after both reports are written.
 */
public final class JavacPluginHostProvider implements M3ToolProvider {
    static final String HOST = "org.apache.maven.plugins.polyglot.adapters.JavacPluginHost";
    static final String USAGE = "<out diagnostics.jsonl> <out findings.sarif> [pp=<jar>]... <javac-arg>...";
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
    private static final System.Logger LOG = System.getLogger(JavacPluginHostProvider.class.getName());

    @Override public String id() {
        return "javac-plugins";
    }

    @Override public Set<M3ToolCapability> capabilities() {
        return Set.of(M3ToolCapability.STATIC_ANALYZER);
    }

    @Override public Set<String> languages() {
        return Set.of("java");
    }

    @Override public List<String> command(M3ToolCapability capability, String language, TaskManifest.Task task,
            Path workspace) throws IOException {
        List<String> args = ToolArgs.count(task, 3, 1024, USAGE);
        List<String> argv = new ArrayList<>(List.of(ToolArgs.java()));
        argv.addAll(JVM_ACCESS);
        argv.addAll(List.of("-cp", ToolArgs.home(JavacPluginHostProvider.class), HOST,
                workspace.resolve(task.workingDir()).toAbsolutePath().normalize().toString(),
                ToolArgs.absolute(workspace, task, args.get(0)), ToolArgs.absolute(workspace, task, args.get(1)), "--"));
        argv.addAll(processorPath(args.subList(2, args.size()), task, workspace));
        List<String> javac = args.subList(2, args.size()).stream().filter(a -> !a.startsWith("pp=")).toList();
        if (javac.stream().anyMatch(a -> a.startsWith("-Xplugin:ErrorProne"))) argv.addAll(ERROR_PRONE_JAVAC);
        argv.addAll(javac);
        LOG.log(System.Logger.Level.DEBUG, "planned javac plug-in host: {0}", argv);
        return ToolArgs.launch(argv);
    }

    private static List<String> processorPath(List<String> args, TaskManifest.Task task, Path workspace) {
        List<String> entries = args.stream().filter(a -> a.startsWith("pp="))
                .map(a -> ToolArgs.absolute(workspace, task, a.substring(3))).toList();
        return entries.isEmpty() ? List.of() : List.of("-processorpath", String.join(File.pathSeparator, entries));
    }

    @Override public List<M3Report> reports(M3ToolCapability capability, String language, TaskManifest.Task task) {
        return List.of(new M3Report(ToolArgs.declared(task, task.arguments().get(0)), M3ReportFormat.BUILD_EVENTS_JSONL_V1),
                new M3Report(ToolArgs.declared(task, task.arguments().get(1)), M3ReportFormat.SARIF_2_1_0));
    }

    @Override public M3AstStatus astStatus(M3ToolCapability capability, String language) {
        return M3AstStatus.COMPILER_PLUGIN;
    }
}
