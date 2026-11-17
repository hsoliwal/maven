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

/**
 * {@code @m3:git:differ:text}: a unified diff written by the original git program, in two modes.
 * <ul>
 *   <li>{@code trees <git> <before> <after> <out.patch>}: {@code git diff --no-index}; exit 1 means "differences found"
 *   and is a declared success. Report grade: the paths keep both folder names, so code-patch does not apply it.</li>
 *   <li>{@code repo <git> <repository> <revision> <out.patch> [<pathspec>...]}: the work tree of a git repository
 *   against a revision; paths are repository-relative, so code-patch can plan, verify and apply the patch.</li>
 * </ul>
 * User and system configuration are pinned so the bytes do not depend on the machine: Git for Windows sets
 * {@code core.autocrlf=true} in its system configuration, which turns CRLF into LF in the patch.
 */
public final class GitTextDifferProvider implements M3ToolProvider {
    static final String USAGE = "trees <git> <before> <after> <out.patch>"
            + " | repo <git> <repository> <revision> <out.patch> [<pathspec>...]";
    static final List<String> PINNED_CONFIG = List.of("-c", "core.autocrlf=false", "-c", "core.safecrlf=false",
            "-c", "core.quotePath=true", "-c", "diff.noprefix=false", "-c", "diff.mnemonicPrefix=false",
            "-c", "diff.relative=false");
    static final List<String> DIFF_OPTIONS = List.of("--no-color", "--no-ext-diff", "--no-textconv", "--binary",
            "--no-renames", "--unified=3", "--inter-hunk-context=0", "--diff-algorithm=myers");
    private static final System.Logger LOG = System.getLogger(GitTextDifferProvider.class.getName());

    @Override public String id() {
        return "git";
    }

    @Override public Set<M3ToolCapability> capabilities() {
        return Set.of(M3ToolCapability.DIFFER);
    }

    @Override public Set<String> languages() {
        return Set.of("text");
    }

    @Override public List<String> command(M3ToolCapability capability, String language, TaskManifest.Task task,
            Path workspace) throws IOException {
        List<String> args = ToolArgs.count(task, 5, 1024, USAGE);
        List<String> argv = new ArrayList<>(List.of(args.get(1)));
        switch (args.get(0)) {
            case "trees" -> argv.addAll(trees(args, task, workspace));
            case "repo" -> argv.addAll(repo(args, task, workspace));
            default -> throw new IOException("usage: " + USAGE);
        }
        LOG.log(System.Logger.Level.DEBUG, "planned git differ: {0}", argv);
        return ToolArgs.launch(argv);
    }

    private static List<String> trees(List<String> args, TaskManifest.Task task, Path workspace) throws IOException {
        if (args.size() != 5) {
            throw new IOException("usage: " + USAGE);
        }
        List<String> argv = new ArrayList<>(PINNED_CONFIG);
        argv.addAll(List.of("diff", "--no-index"));
        argv.addAll(DIFF_OPTIONS);
        argv.add("--output=" + ToolArgs.absolute(workspace, task, args.get(4)));
        argv.addAll(List.of("--", ToolArgs.operand(args.get(2), "before"), ToolArgs.operand(args.get(3), "after")));
        return argv;
    }

    private static List<String> repo(List<String> args, TaskManifest.Task task, Path workspace) throws IOException {
        List<String> argv = new ArrayList<>(List.of("-C", ToolArgs.absolute(workspace, task, args.get(2))));
        argv.addAll(PINNED_CONFIG);
        argv.add("diff");
        argv.addAll(DIFF_OPTIONS);
        argv.add("--output=" + ToolArgs.absolute(workspace, task, args.get(4)));
        argv.add(ToolArgs.operand(args.get(3), "revision"));
        argv.add("--");
        argv.addAll(args.subList(5, args.size()));
        return argv;
    }

    /** {@code git diff --no-index} exits 1 when the trees differ; {@code git diff <revision>} exits 0 either way. */
    @Override public Set<Integer> successExitCodes(M3ToolCapability capability, String language,
            TaskManifest.Task task) {
        return "trees".equals(task.arguments().get(0)) ? Set.of(0, 1) : Set.of(0);
    }

    @Override public List<M3Report> reports(M3ToolCapability capability, String language, TaskManifest.Task task) {
        return List.of(new M3Report(ToolArgs.declared(task, task.arguments().get(4)), M3ReportFormat.UNIFIED_DIFF));
    }

    @Override public M3AstStatus astStatus(M3ToolCapability capability, String language) {
        return M3AstStatus.NO_AST;
    }
}
