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
import io.synexia.maven.polyglot.M3ToolEffect;
import io.synexia.maven.polyglot.M3ToolProvider;
import io.synexia.maven.polyglot.TaskManifest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code @m3:code-patch:patcher:text}: the code patch plugin's goals through Maven without a POM (the plugin has no
 * command-line main yet), offline and in batch mode. The only provider that writes sources.
 * <ul>
 *   <li>{@code plan|preview|verify <mvn> <version> <base-dir> <out-dir> <patch>... [failOn=S,...]}: read sources,
 *   write {@code PLAN.tsv} and {@code PLAN_ID} (verify also {@code verify/<id>/COMPARE.tsv}).</li>
 *   <li>{@code apply ... <patch>... approvedPlan=<id>}: promotes the reviewed plan with backups and a receipt.</li>
 *   <li>{@code rollback <mvn> <version> <base-dir> <out-dir> <receipt-id>}: restores from the receipt.</li>
 * </ul>
 * apply and rollback declare {@link M3ToolEffect#WRITES_SOURCES}; the registry refuses them without separate approval.
 * The arguments are read once ({@link #positional}): the {@code key=value} options may stand anywhere, and command(),
 * effect() and reports() see the same mode, base-dir and out-dir. The base-dir lies inside the workspace; the out-dir
 * lies inside it in declared output space ({@link ToolArgs#outputFolder}) and neither is nor holds the base-dir, so
 * the plan, stage, verify and receipt folders never land among the sources or outside the workspace.
 * There is no {@code expectPlan}: code-patch has no such check, so verify cannot be tied to a reviewed plan id, and a
 * declared {@code verify/<id>/COMPARE.tsv} could be satisfied by a stale file of an earlier run. The reviewed plan id
 * binds at apply ({@code approvedPlan}), which code-patch checks.
 */
public final class CodePatchProvider implements M3ToolProvider {
    static final String PLUGIN = "org.apache.maven.plugins:maven-code-patch-plugin:";
    static final String USAGE = "plan|preview|verify|apply <mvn> <version> <base-dir> <out-dir> <patch>..."
            + " [failOn=S,...] [approvedPlan=<id>] | rollback <mvn> <version> <base-dir> <out-dir> <id>";
    static final List<String> MAVEN_FLAGS = List.of("--batch-mode", "--no-transfer-progress", "--offline");
    private static final Set<String> MODES = Set.of("plan", "preview", "verify", "apply", "rollback");
    private static final List<String> OPTIONS = List.of("failOn", "approvedPlan");
    private static final Pattern ID = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern STATES = Pattern.compile("[A-Z_]+(,[A-Z_]+)*");
    private static final System.Logger LOG = System.getLogger(CodePatchProvider.class.getName());

    @Override public String id() {
        return "code-patch";
    }

    @Override public Set<M3ToolCapability> capabilities() {
        return ToolArgs.kinds(() -> Set.of(M3ToolCapability.PATCHER));
    }

    @Override public Set<String> languages() {
        return Set.of("text");
    }

    @Override public List<String> command(M3ToolCapability capability, String language, TaskManifest.Task task,
            Path workspace) throws IOException {
        List<String> args = checked(task);
        String mode = args.get(0);
        List<String> argv = new ArrayList<>(List.of(args.get(1)));
        argv.addAll(MAVEN_FLAGS);
        argv.add(PLUGIN + ToolArgs.version(args.get(2)) + ":" + mode);
        argv.addAll(directories(args, task, workspace));
        argv.addAll("rollback".equals(mode) ? rollback(args) : patches(args, task, workspace));
        argv.addAll(options(task.arguments(), mode));
        LOG.log(System.Logger.Level.DEBUG, "planned code-patch {0}: {1}", mode, argv);
        return ToolArgs.launch(argv);
    }

    /** The one reading of the arguments: everything but the options, mode first. */
    private static List<String> positional(TaskManifest.Task task) {
        return ToolArgs.positional(task.arguments(), OPTIONS);
    }

    /** {@link #positional}, checked: a mode, at least five operands, no expectPlan. */
    private static List<String> checked(TaskManifest.Task task) throws IOException {
        List<String> args = positional(task);
        if (args.size() < 6 || !MODES.contains(args.get(0)) || task.arguments().size() > 1024
                || task.arguments().stream().anyMatch(a -> a.startsWith("expectPlan="))) {
            throw new IOException("usage (code-patch has no expectPlan check; approvedPlan binds the plan at apply): "
                    + USAGE);
        }
        return args;
    }

    /** The base-dir inside the workspace; the out-dir in declared output space, neither the base-dir nor above it. */
    private static List<String> directories(List<String> args, TaskManifest.Task task, Path workspace)
            throws IOException {
        String base = ToolArgs.inside(workspace, task, args.get(3), "base-dir");
        String out = ToolArgs.outputFolder(workspace, task, args.get(4));
        if (Path.of(base).startsWith(Path.of(out))) {
            throw new IOException("the out-dir must neither be the base-dir nor hold it: " + args.get(4));
        }
        return List.of("-Dpatch.baseDirectory=" + base, "-Dpatch.outputDirectory=" + out);
    }

    private static List<String> rollback(List<String> args) throws IOException {
        if (args.size() != 6 || !ID.matcher(args.get(5)).matches()) {
            throw new IOException("rollback needs exactly one receipt id (64 lowercase hex): " + USAGE);
        }
        return List.of("-Dpatch.receipt=" + args.get(5));
    }

    /** Patch files as one comma-separated property, which is why a patch path may not hold a comma. */
    private static List<String> patches(List<String> args, TaskManifest.Task task, Path workspace) throws IOException {
        List<String> files = args.subList(5, args.size()).stream()
                .map(p -> ToolArgs.absolute(workspace, task, p)).toList();
        if (files.stream().anyMatch(f -> f.indexOf(',') >= 0)) {
            throw new IOException("a patch path holds a comma, which the patch.patches property cannot carry");
        }
        return List.of("-Dpatch.patches=" + String.join(",", files));
    }

    private static List<String> options(List<String> args, String mode) throws IOException {
        Optional<String> failOn = ToolArgs.option(args, "failOn");
        Optional<String> approved = ToolArgs.option(args, "approvedPlan");
        boolean misplaced = failOn.isPresent() && !"verify".equals(mode) || approved.isPresent() != "apply".equals(mode);
        if (misplaced || failOn.filter(s -> !STATES.matcher(s).matches()).isPresent()
                || approved.filter(id -> !ID.matcher(id).matches()).isPresent()) {
            throw new IOException("invalid options for code-patch " + mode + " (apply needs approvedPlan=<id>): " + USAGE);
        }
        List<String> flags = new ArrayList<>();
        failOn.ifPresent(s -> flags.add("-Dpatch.failOn=" + s));
        approved.ifPresent(id -> flags.add("-Dpatch.approvedPlan=" + id));
        return flags;
    }

    @Override public M3ToolEffect effect(M3ToolCapability capability, String language, TaskManifest.Task task)
            throws IOException {
        String mode = checked(task).get(0);
        return "apply".equals(mode) || "rollback".equals(mode) ? M3ToolEffect.WRITES_SOURCES : M3ToolEffect.READS_SOURCES;
    }

    @Override public List<M3Report> reports(M3ToolCapability capability, String language, TaskManifest.Task task) {
        List<String> args = positional(task);
        String out = args.get(4) + "/";
        String mode = args.get(0);
        if ("apply".equals(mode) || "rollback".equals(mode)) {
            String id = "apply".equals(mode) ? value(task.arguments(), "approvedPlan") : args.get(5);
            return List.of(new M3Report(ToolArgs.declared(task, out + "receipts/" + id + "/RECEIPT.tsv"),
                    M3ReportFormat.RECEIPT_TSV));
        }
        return List.of(new M3Report(ToolArgs.declared(task, out + "PLAN.tsv"), M3ReportFormat.PLAN_TSV));
    }

    private static String value(List<String> args, String key) {
        return args.stream().filter(a -> a.startsWith(key + "=")).map(a -> a.substring(key.length() + 1))
                .findFirst().orElseThrow();
    }

    @Override public M3AstStatus astStatus(M3ToolCapability capability, String language) {
        return M3AstStatus.NO_AST;
    }
}
