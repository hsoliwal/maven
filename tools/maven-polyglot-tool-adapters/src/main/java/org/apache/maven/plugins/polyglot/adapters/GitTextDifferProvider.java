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
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@code @m3:git:differ:text}: a unified diff written by the original git program through {@link GitDiffMain}, which
 * pins every byte-changing setting ({@link PinnedGit}) and tells git's errors from differences. Two modes:
 * <ul>
 *   <li>{@code trees <git> <before> <after> <out.patch>}: {@code git diff --no-index}; before and after must be
 *   declared inputs (so the engine checks they exist). Report grade: the paths keep both folder names, so code-patch
 *   does not apply it.</li>
 *   <li>{@code repo <git> <repository> <revision> <out.patch> [<pathspec>...]}: the work tree of a git repository
 *   (a declared input) against a revision, untracked (not ignored) files included; paths are repository-relative, so
 *   code-patch can plan, verify and apply the patch. Commands the repository configures never run (hooks,
 *   fsmonitor, external diff, textconv), and a path with a {@code filter} attribute is refused, because a clean
 *   filter is a configured program.</li>
 * </ul>
 * Either mode exits 0 with the patch (empty when nothing differs) and 2 without one when git failed. System, global
 * and XDG git configuration is never read ({@link PinnedGit}). The repository's own {@code .gitattributes} and
 * configuration still apply (they are part of the repository): with {@code text}/{@code eol} conversion the patch is
 * in repository form. A git launcher is checked like every launcher ({@link CmdLauncher}).
 */
public final class GitTextDifferProvider implements M3ToolProvider {
    static final String MAIN = "org.apache.maven.plugins.polyglot.adapters.GitDiffMain";
    static final String USAGE = "trees <git> <before> <after> <out.patch>"
            + " | repo <git> <repository> <revision> <out.patch> [<pathspec>...]";
    private static final System.Logger LOG = System.getLogger(GitTextDifferProvider.class.getName());

    @Override public String id() {
        return "git";
    }

    @Override public Set<M3ToolCapability> capabilities() {
        return ToolArgs.kinds(() -> Set.of(M3ToolCapability.DIFFER));
    }

    @Override public Set<String> languages() {
        return Set.of("text");
    }

    @Override public List<String> command(M3ToolCapability capability, String language, TaskManifest.Task task,
            Path workspace) throws IOException {
        List<String> args = ToolArgs.count(task, 5, 1024, USAGE);
        List<String> operands = switch (args.get(0)) {
            case "trees" -> trees(args, task);
            case "repo" -> repo(args, task, workspace);
            default -> throw new IOException("usage: " + USAGE);
        };
        String out = ToolArgs.absolute(workspace, task, args.get(4));
        ToolArgs.nested(Stream.of(List.of(args.get(1)), operands, List.of(out)).flatMap(List::stream).toList());
        List<String> argv = argv(args, operands, out);
        LOG.log(System.Logger.Level.DEBUG, "planned git differ: {0}", argv);
        return ToolArgs.launch(argv);
    }

    /** {@code java -cp <adapters> GitDiffMain <mode> <git>}, the first two operands, the patch, the pathspecs. */
    private static List<String> argv(List<String> args, List<String> operands, String out) throws IOException {
        int split = Math.min(2, operands.size());
        return Stream.of(List.of(ToolArgs.java(), "-cp", ToolArgs.home(GitTextDifferProvider.class), MAIN, args.get(0),
                args.get(1)), operands.subList(0, split), List.of(out), operands.subList(split, operands.size()))
                .flatMap(List::stream).toList();
    }

    /** The two trees as given (patch headers keep them), each one a declared input. */
    private static List<String> trees(List<String> args, TaskManifest.Task task) throws IOException {
        if (args.size() != 5) {
            throw new IOException("usage: " + USAGE);
        }
        List<String> trees = List.of(ToolArgs.operand(args.get(2), "before"), ToolArgs.operand(args.get(3), "after"));
        if (!trees.stream().map(tree -> ToolArgs.declared(task, tree)).allMatch(task.inputs()::contains)) {
            throw new IOException("before and after must be declared inputs, so the engine checks they exist: " + trees);
        }
        return trees;
    }

    /** Repository (a declared input, so inside the workspace and checked by the engine), revision, the pathspecs. */
    private static List<String> repo(List<String> args, TaskManifest.Task task, Path workspace) throws IOException {
        if (!task.inputs().contains(ToolArgs.declared(task, args.get(2)))) {
            throw new IOException("the repository must be a declared input, so it lies in the workspace: " + args.get(2));
        }
        List<String> operands = new ArrayList<>(List.of(ToolArgs.absolute(workspace, task, args.get(2)),
                ToolArgs.operand(args.get(3), "revision")));
        operands.addAll(args.subList(5, args.size()));
        return operands;
    }

    @Override public List<M3Report> reports(M3ToolCapability capability, String language, TaskManifest.Task task) {
        return List.of(new M3Report(ToolArgs.declared(task, task.arguments().get(4)), M3ReportFormat.UNIFIED_DIFF));
    }

    @Override public M3AstStatus astStatus(M3ToolCapability capability, String language) {
        return M3AstStatus.NO_AST;
    }
}
