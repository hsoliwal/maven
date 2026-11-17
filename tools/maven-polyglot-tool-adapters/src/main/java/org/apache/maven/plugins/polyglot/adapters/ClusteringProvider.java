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
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code @m3:clustering:atomizer:<language>} and {@code @m3:clustering:differ:<language>}: the code clustering
 * plugin's command line ({@code CodeClustering cluster} and {@code compare}) in its own JVM.
 * <ul>
 *   <li>atomizer: {@code <clustering.jar> <out-dir> <source-root>...}; report {@code <out-dir>/ATOM_HASHES.tsv}.</li>
 *   <li>differ (structural): {@code <clustering.jar> <baseline ATOM_HASHES.tsv> <out-dir> <source-root>...
 *   [failOn=STATE,...]}; report {@code <out-dir>/COMPARE.tsv} with the states UNCHANGED, REFORMATTED, RENAMED,
 *   LITERALS_CHANGED, CHANGED, ADDED, REMOVED. Exit 3 (a state named in failOn was found) fails the task.</li>
 * </ul>
 * The tool scans every file its front ends accept under the roots; the language field declares the one-AST status:
 * Java goes through the compiler's parser in a separate call, the other languages through the lexical front end.
 * The arguments are read once ({@link #positional}) for command() and reports(); the atomizer takes no option, and
 * the out-dir lies inside the workspace in declared output space ({@link ToolArgs#outputFolder}), outside the roots.
 */
public final class ClusteringProvider implements M3ToolProvider {
    static final String MAIN = "org.apache.maven.plugins.clustering.CodeClustering";
    static final String USAGE = "atomizer: <clustering.jar> <out-dir> <source-root>..."
            + " | differ: <clustering.jar> <baseline.tsv> <out-dir> <source-root>... [failOn=STATE,...]";
    private static final Set<String> LANGUAGES = Set.of("java", "c", "cpp", "objc", "csharp", "javascript",
            "typescript", "go", "rust", "kotlin", "scala", "swift", "php", "dart", "groovy", "python");
    private static final Pattern STATES = Pattern.compile("[A-Z_]+(,[A-Z_]+)*");
    private static final List<String> OPTIONS = List.of("failOn");
    private static final System.Logger LOG = System.getLogger(ClusteringProvider.class.getName());

    @Override public String id() {
        return "clustering";
    }

    @Override public Set<M3ToolCapability> capabilities() {
        return ToolArgs.kinds(() -> Set.of(M3ToolCapability.ATOMIZER, M3ToolCapability.DIFFER));
    }

    @Override public Set<String> languages() {
        return LANGUAGES;
    }

    @Override public List<String> command(M3ToolCapability capability, String language, TaskManifest.Task task,
            Path workspace) throws IOException {
        List<String> argv = capability == M3ToolCapability.ATOMIZER ? cluster(task, workspace) : compare(task, workspace);
        LOG.log(System.Logger.Level.DEBUG, "planned clustering {0}: {1}", capability.slug(), argv);
        return ToolArgs.launch(argv);
    }

    private static List<String> cluster(TaskManifest.Task task, Path workspace) throws IOException {
        List<String> args = positional(ToolArgs.count(task, 3, 1024, USAGE));
        if (args.size() != task.arguments().size()) {
            throw new IOException("the atomizer takes no options: " + USAGE);
        }
        List<String> roots = absolute(args.subList(2, args.size()), task, workspace);
        String out = ToolArgs.outputFolder(workspace, task, args.get(1));
        ToolArgs.outside(out, roots);
        List<String> argv = new ArrayList<>(List.of(ToolArgs.java(), "-cp",
                ToolArgs.absolute(workspace, task, args.get(0)), MAIN, "cluster", out));
        argv.addAll(roots);
        return argv;
    }

    private static List<String> compare(TaskManifest.Task task, Path workspace) throws IOException {
        Optional<String> failOn = ToolArgs.option(ToolArgs.count(task, 4, 1024, USAGE), "failOn");
        List<String> args = positional(task.arguments());
        if (args.size() < 4 || failOn.filter(s -> !STATES.matcher(s).matches()).isPresent()) {
            throw new IOException("usage: " + USAGE);
        }
        List<String> roots = absolute(args.subList(3, args.size()), task, workspace);
        String out = ToolArgs.outputFolder(workspace, task, args.get(2));
        ToolArgs.outside(out, roots);
        return Stream.of(List.of(ToolArgs.java()), failOn.map(states -> List.of("-Dclustering.failOn=" + states))
                .orElse(List.of()), List.of("-cp", ToolArgs.absolute(workspace, task, args.get(0)), MAIN, "compare",
                ToolArgs.absolute(workspace, task, args.get(1)), out), roots).flatMap(List::stream).toList();
    }

    /** The one reading of the arguments: everything but the {@code failOn=} option. */
    private static List<String> positional(List<String> arguments) {
        return ToolArgs.positional(arguments, OPTIONS);
    }

    private static List<String> absolute(List<String> paths, TaskManifest.Task task, Path workspace) {
        return paths.stream().map(p -> ToolArgs.absolute(workspace, task, p)).toList();
    }

    @Override public List<M3Report> reports(M3ToolCapability capability, String language, TaskManifest.Task task) {
        List<String> args = positional(task.arguments());
        return capability == M3ToolCapability.ATOMIZER
                ? List.of(new M3Report(ToolArgs.declared(task, args.get(1) + "/ATOM_HASHES.tsv"),
                        M3ReportFormat.ATOM_HASHES_TSV))
                : List.of(new M3Report(ToolArgs.declared(task, args.get(2) + "/COMPARE.tsv"),
                        M3ReportFormat.COMPARE_TSV));
    }

    @Override public M3AstStatus astStatus(M3ToolCapability capability, String language) {
        return "java".equals(language) ? M3AstStatus.COMPILER_FRONT_END : M3AstStatus.SEPARATE_PARSE;
    }
}
