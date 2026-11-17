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
 *   <li>{@code plan|preview|verify <mvn> <version> <base-dir> <out-dir> <patch>... [failOn=S,...] [expectPlan=id]}:
 *   read sources, write {@code PLAN.tsv} and {@code PLAN_ID} (verify also {@code verify/<id>/COMPARE.tsv}).</li>
 *   <li>{@code apply ... <patch>... approvedPlan=<id>}: promotes the reviewed plan with backups and a receipt.</li>
 *   <li>{@code rollback <mvn> <version> <base-dir> <out-dir> <receipt-id>}: restores from the receipt.</li>
 * </ul>
 * apply and rollback declare {@link M3ToolEffect#WRITES_SOURCES}; the registry refuses them without separate approval.
 */
public final class CodePatchProvider implements M3ToolProvider {
    static final String PLUGIN = "org.apache.maven.plugins:maven-code-patch-plugin:";
    static final String USAGE = "plan|preview|verify|apply <mvn> <version> <base-dir> <out-dir> <patch>..."
            + " [failOn=S,...] [expectPlan=<id>] [approvedPlan=<id>] | rollback <mvn> <version> <base-dir> <out-dir> <id>";
    static final List<String> MAVEN_FLAGS = List.of("--batch-mode", "--no-transfer-progress", "--offline");
    private static final Set<String> MODES = Set.of("plan", "preview", "verify", "apply", "rollback");
    private static final List<String> OPTIONS = List.of("failOn", "expectPlan", "approvedPlan");
    private static final Pattern ID = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern STATES = Pattern.compile("[A-Z_]+(,[A-Z_]+)*");
    private static final System.Logger LOG = System.getLogger(CodePatchProvider.class.getName());

    @Override public String id() {
        return "code-patch";
    }

    @Override public Set<M3ToolCapability> capabilities() {
        return Set.of(M3ToolCapability.PATCHER);
    }

    @Override public Set<String> languages() {
        return Set.of("text");
    }

    @Override public List<String> command(M3ToolCapability capability, String language, TaskManifest.Task task,
            Path workspace) throws IOException {
        List<String> args = ToolArgs.positional(ToolArgs.count(task, 6, 1024, USAGE), OPTIONS);
        String mode = mode(task);
        if (args.size() < 6) {
            throw new IOException("usage: " + USAGE);
        }
        List<String> argv = new ArrayList<>(List.of(args.get(1)));
        argv.addAll(MAVEN_FLAGS);
        argv.add(PLUGIN + ToolArgs.version(args.get(2)) + ":" + mode);
        argv.add("-Dpatch.baseDirectory=" + ToolArgs.absolute(workspace, task, args.get(3)));
        argv.add("-Dpatch.outputDirectory=" + ToolArgs.absolute(workspace, task, args.get(4)));
        argv.addAll("rollback".equals(mode) ? rollback(args) : patches(args, task, workspace));
        argv.addAll(options(task.arguments(), mode));
        LOG.log(System.Logger.Level.DEBUG, "planned code-patch {0}: {1}", mode, argv);
        return ToolArgs.launch(argv);
    }

    private static String mode(TaskManifest.Task task) throws IOException {
        String mode = task.arguments().get(0);
        if (!MODES.contains(mode)) {
            throw new IOException("usage: " + USAGE);
        }
        return mode;
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
        Optional<String> expected = ToolArgs.option(args, "expectPlan");
        boolean misplaced = failOn.isPresent() && !"verify".equals(mode) || expected.isPresent() && !"verify".equals(mode)
                || approved.isPresent() != "apply".equals(mode);
        if (misplaced || failOn.filter(s -> !STATES.matcher(s).matches()).isPresent()
                || approved.or(() -> expected).filter(id -> !ID.matcher(id).matches()).isPresent()) {
            throw new IOException("invalid options for code-patch " + mode + " (apply needs approvedPlan=<id>): " + USAGE);
        }
        List<String> flags = new ArrayList<>();
        failOn.ifPresent(s -> flags.add("-Dpatch.failOn=" + s));
        approved.ifPresent(id -> flags.add("-Dpatch.approvedPlan=" + id));
        return flags;
    }

    @Override public M3ToolEffect effect(M3ToolCapability capability, String language, TaskManifest.Task task)
            throws IOException {
        String mode = mode(task);
        return "apply".equals(mode) || "rollback".equals(mode) ? M3ToolEffect.WRITES_SOURCES : M3ToolEffect.READS_SOURCES;
    }

    @Override public List<M3Report> reports(M3ToolCapability capability, String language, TaskManifest.Task task) {
        List<String> all = task.arguments();
        String out = all.get(4) + "/";
        String mode = all.get(0);
        if ("apply".equals(mode) || "rollback".equals(mode)) {
            String id = "apply".equals(mode) ? value(all, "approvedPlan") : all.get(5);
            return List.of(new M3Report(ToolArgs.declared(task, out + "receipts/" + id + "/RECEIPT.tsv"),
                    M3ReportFormat.RECEIPT_TSV));
        }
        List<M3Report> reports = new ArrayList<>(List.of(
                new M3Report(ToolArgs.declared(task, out + "PLAN.tsv"), M3ReportFormat.PLAN_TSV)));
        all.stream().filter(a -> a.startsWith("expectPlan=")).map(a -> a.substring("expectPlan=".length()))
                .forEach(id -> reports.add(new M3Report(ToolArgs.declared(task, out + "verify/" + id + "/COMPARE.tsv"),
                        M3ReportFormat.COMPARE_TSV)));
        return reports;
    }

    private static String value(List<String> args, String key) {
        return args.stream().filter(a -> a.startsWith(key + "=")).map(a -> a.substring(key.length() + 1))
                .findFirst().orElseThrow();
    }

    @Override public M3AstStatus astStatus(M3ToolCapability capability, String language) {
        return M3AstStatus.NO_AST;
    }
}
