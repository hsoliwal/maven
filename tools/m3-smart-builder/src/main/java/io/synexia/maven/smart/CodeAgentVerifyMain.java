// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/** Explicitly approved OpenRewrite replay in a staged copy, never in the caller's working sources. */
public final class CodeAgentVerifyMain {
    private static final String REWRITE_VERSION = "6.66.0";
    private static final String REWRITE_PLUGIN = "org.openrewrite.maven:rewrite-maven-plugin:" + REWRITE_VERSION + ":";
    private static final Set<String> OPTIONS = Set.of("--project-dir", "--build-dir", "--source-root",
            "--preparation-report", "--atom-pattern-manifest", "--recipe-catalog", "--machine-evidence",
            "--approved-recipe-id", "--approve-execution", "--maven-executable", "--maven-repository",
            "--recipe-coordinates", "--plugin-sha256", "--timeout-seconds");
    private static final long MAX_STAGE_BYTES = 268_435_456L;
    private static final int MAX_STAGE_FILES = 20_000;

    private CodeAgentVerifyMain() { }

    public static void main(String[] args) { System.exit(run(args, System.out, System.err)); }

    static int run(String[] args, PrintStream out, PrintStream error) {
        Path output = null;
        Path stage = null;
        boolean attempted = false;
        boolean verified = false;
        try {
            Map<String, String> argsMap = parse(args);
            Path base = Path.of(argsMap.getOrDefault("--project-dir", ".")).toAbsolutePath().normalize();
            Path source = resolve(base, required(argsMap, "--source-root"));
            Path build = resolve(base, argsMap.getOrDefault("--build-dir", "target"));
            if (build.resolve("m3-smart").startsWith(source)) throw new IOException("stage overlaps source root");
            output = SafeBuildOutput.directory(base, build);
            if (!"true".equals(required(argsMap, "--approve-execution")))
                throw new IOException("explicit operator approval required");

            Path preparationFile = resolve(base, required(argsMap, "--preparation-report"));
            Path atomFile = resolve(base, required(argsMap, "--atom-pattern-manifest"));
            Path catalogFile = resolve(base, required(argsMap, "--recipe-catalog"));
            Path machineFile = argsMap.containsKey("--machine-evidence")
                    ? resolve(base, argsMap.get("--machine-evidence")) : null;
            String preparation = CanonicalPreparationEvidence.inspect(base, source, preparationFile);
            AtomPatternEvidence.Summary atom = AtomPatternEvidence.inspect(base, source, atomFile);
            RecipeCatalog catalog = RecipeCatalog.load(base, catalogFile);
            MachineEvidence.Summary machine = machineFile == null ? null : MachineEvidence.inspect(base, source, machineFile);
            MachineRulebook.Policy rules = MachineRulebook.load();
            String binding = MachineRulebook.bind(preparation, atom, catalog, machine, rules);
            List<RecipeCatalog.Entry> applicable = catalog.applicable(object(object(TinyJson.parse(preparation))
                    .get("patternCounts")));
            String approvedId = required(argsMap, "--approved-recipe-id");
            RecipeCatalog.Entry entry = applicable.stream().filter(candidate -> candidate.id().equals(approvedId))
                    .findFirst().orElseThrow(() -> new IOException("recipe not in applicable catalogue"));
            if (!"FILE".equals(entry.scope()))
                throw new IOException("only FILE scope may run without higher-scope contracts");
            validateNomination(base, output.resolve("code-agent.json"), binding, preparation,
                    atom, machine, rules, catalog, applicable, entry);
            String pomBefore = Hashes.sha256(RecipeCatalog.readLocal(base, base.resolve("pom.xml"), 1_048_576));

            Path runner = Path.of(required(argsMap, "--maven-executable")).toAbsolutePath().normalize();
            if (Files.isSymbolicLink(runner) || !Files.isRegularFile(runner, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isExecutable(runner)) throw new IOException("approved Maven executable missing");
            Path repository = Path.of(required(argsMap, "--maven-repository")).toAbsolutePath().normalize();
            if (!Files.isDirectory(repository, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(repository))
                throw new IOException("offline Maven repository missing");
            String pluginHash = sha(required(argsMap, "--plugin-sha256"));
            String[] coords = required(argsMap, "--recipe-coordinates").split(":", -1);
            if (coords.length != 3 || !coords[0].matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)+")
                    || !coords[1].matches("[A-Za-z0-9_.-]+")
                    || !coords[2].matches("[0-9][A-Za-z0-9_.-]*") || coords[2].endsWith("-SNAPSHOT"))
                throw new IOException("immutable recipe Maven coordinates required");
            String coordinate = String.join(":", coords);
            Path recipeJar = repository.resolve(coords[0].replace('.', '/')).resolve(coords[1])
                    .resolve(coords[2]).resolve(coords[1] + "-" + coords[2] + ".jar");
            Path pluginJar = repository.resolve("org/openrewrite/maven/rewrite-maven-plugin")
                    .resolve(REWRITE_VERSION).resolve("rewrite-maven-plugin-" + REWRITE_VERSION + ".jar");
            if (!entry.implementationSha256().equals(Hashes.sha256(ordinaryJar(repository, recipeJar)))
                    || !pluginHash.equals(Hashes.sha256(ordinaryJar(repository, pluginJar))))
                throw new IOException("pinned Maven artifact digest mismatch");
            int timeout = Integer.parseInt(argsMap.getOrDefault("--timeout-seconds", "600"));
            if (timeout < 1 || timeout > 3600) throw new IOException("Maven timeout outside 1..3600");

            stage = Files.createTempDirectory(output, "code-agent-stage-");
            stageCopy(base, stage, build);
            Path stagedSource = stage.resolve(base.relativize(source));
            TreeMap<String, String> initial = snapshot(stage);
            var stageBefore = SourceInventory.inspectSelected(stage, stagedSource);
            runMaven(stage, runner, repository, List.of("-Drewrite.activeRecipes=" + entry.recipe(),
                    "-Drewrite.recipeArtifactCoordinates=" + coordinate,
                    "-Drewrite.failOnInvalidActiveRecipes=true", REWRITE_PLUGIN + "dryRun"),
                    "dry-run.log", timeout);
            if (!initial.equals(snapshot(stage))) throw new IOException("dry run changed staged project inputs");
            Path patch = stage.resolve("target/rewrite/rewrite.patch");
            String patchHash = null;
            long patchBytes = 0;
            if (Files.exists(patch, LinkOption.NOFOLLOW_LINKS)) {
                patchBytes = Files.size(patch);
                if (!Files.isRegularFile(patch, LinkOption.NOFOLLOW_LINKS) || patchBytes > 16_777_216L)
                    throw new IOException("invalid OpenRewrite preview patch");
                patchHash = Hashes.sha256(patch);
            }
            attempted = true;
            List<String> recipeArgs = List.of("-Drewrite.activeRecipes=" + entry.recipe(),
                    "-Drewrite.recipeArtifactCoordinates=" + coordinate,
                    "-Drewrite.failOnInvalidActiveRecipes=true", REWRITE_PLUGIN + "run");
            runMaven(stage, runner, repository, recipeArgs, "apply.log", timeout);
            TreeMap<String, String> rewritten = snapshot(stage);
            for (String path : initial.keySet())
                if (!initial.get(path).equals(rewritten.get(path))
                        && (!path.startsWith(base.relativize(source).toString().replace('\\', '/') + "/")
                        || !path.endsWith(".java"))) throw new IOException("recipe changed locked project input");
            if (!initial.keySet().equals(rewritten.keySet())) throw new IOException("recipe added or removed input files");
            var stageAfter = SourceInventory.inspectSelected(stage, stagedSource);
            Map<String, String> afterHashes = new TreeMap<>();
            for (SourceInventory.FileFact file : stageAfter.files()) afterHashes.put(file.path(), file.sha256());
            int changes = 0;
            for (SourceInventory.FileFact file : stageBefore.files())
                if (!file.sha256().equals(afterHashes.get(file.path()))) changes++;
            if (changes > 0 && (patchHash == null || patchBytes == 0))
                throw new IOException("OpenRewrite dry-run preview missing for applied Java changes");
            runMaven(stage, runner, repository, List.of("verify"), "verify.log", timeout);
            if (!rewritten.equals(snapshot(stage))) throw new IOException("verification modified staged inputs");
            runMaven(stage, runner, repository, recipeArgs, "fixed-point.log", timeout);
            if (!rewritten.equals(snapshot(stage))) throw new IOException("OpenRewrite replay not idempotent");
            if (!preparation.equals(CanonicalPreparationEvidence.inspect(base, source, preparationFile))
                    || !atom.equals(AtomPatternEvidence.inspect(base, source, atomFile))
                    || machine != null && !machine.equals(MachineEvidence.inspect(base, source, machineFile))
                    || !rules.sha256().equals(MachineRulebook.load().sha256())
                    || !pomBefore.equals(Hashes.sha256(RecipeCatalog.readLocal(base, base.resolve("pom.xml"), 1_048_576)))
                    || !catalog.digest().equals(RecipeCatalog.load(base, catalogFile).digest()))
                throw new IOException("source or approval evidence changed during replay");

            verified = true;
            String result = "{\"schema\":\"m3.smart.code-agent-verify.v1\",\"status\":"
                    + TinyJson.quote(changes == 0 ? "no-change" : "verified-for-review")
                    + ",\"mode\":\"isolated-execution\",\"approvedRecipeId\":" + TinyJson.quote(approvedId)
                    + ",\"recipeJarSha256\":" + TinyJson.quote(entry.implementationSha256())
                    + ",\"rewritePluginSha256\":" + TinyJson.quote(pluginHash)
                    + ",\"machineRulebookSha256\":" + TinyJson.quote(rules.sha256())
                    + ",\"evidenceBindingSha256\":" + TinyJson.quote(binding)
                    + ",\"stageRelativePath\":" + TinyJson.quote(base.relativize(stage).toString().replace('\\', '/'))
                    + ",\"beforeSourceDigest\":" + TinyJson.quote(stageBefore.summary().digest())
                    + ",\"afterSourceDigest\":" + TinyJson.quote(stageAfter.summary().digest())
                    + ",\"changedJavaFiles\":" + changes
                    + ",\"dryRunPatchSha256\":" + (patchHash == null ? "null" : TinyJson.quote(patchHash))
                    + ",\"recipeExecutionAttempted\":true,\"mavenVerifyPassed\":true,\"fixedPointPassed\":true,"
                    + "\"sourceMutationAuthority\":false,\"semanticProofAuthenticated\":false,"
                    + "\"verificationAuthenticated\":false,\"promoted\":false}\n";
            SafeBuildOutput.write(output, "code-agent-verify.json", result);
            out.println("M3 recipe staged and verified for review: " + output.resolve("code-agent-verify.json"));
            return 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return refused(output, stage, attempted, verified, "InterruptedException", error);
        } catch (Exception rejected) {
            return refused(output, stage, attempted, verified, rejected.getClass().getSimpleName(), error);
        }
    }

    private static void validateNomination(Path base, Path file, String binding, String preparation,
                                           AtomPatternEvidence.Summary atom, MachineEvidence.Summary machine,
                                           MachineRulebook.Policy rules,
                                           RecipeCatalog catalog, List<RecipeCatalog.Entry> applicable,
                                           RecipeCatalog.Entry entry) throws IOException {
        Map<?, ?> report = object(TinyJson.parse(new String(RecipeCatalog.readLocal(base, file, 1_048_576),
                StandardCharsets.UTF_8)));
        List<Object> expected = new ArrayList<>();
        for (RecipeCatalog.Entry available : applicable) expected.add(TinyJson.parse(available.json()));
        if (!report.keySet().equals(Set.of("schema", "mode", "status", "candidateOrigin",
                    "evidenceBindingSha256", "catalogSha256", "machineRulebookSha256", "atomPatternFacts", "machineEvidenceFacts",
                    "preparationFacts", "candidateSha256", "applicableRecipes", "selectedRecipe",
                    "modelConfidence", "modelOutputTrusted", "recipeApproved", "recipeExecuted",
                    "semanticProofAuthenticated", "verificationAuthenticated"))
                || !"m3.smart.code-agent.v1".equals(report.get("schema"))
                || !"review-only".equals(report.get("mode")) || !"proposed".equals(report.get("status"))
                || !Set.of("candidate-file", "chrome", "cli", "jni", "bridge")
                        .contains(report.get("candidateOrigin"))
                || !(report.get("candidateSha256") instanceof String hash && hash.matches("[0-9a-f]{64}"))
                || !Set.of("HIGH", "MEDIUM", "LOW").contains(report.get("modelConfidence"))
                || !binding.equals(report.get("evidenceBindingSha256"))
                || !catalog.digest().equals(report.get("catalogSha256"))
                || !rules.sha256().equals(report.get("machineRulebookSha256"))
                || !object(TinyJson.parse(preparation)).equals(report.get("preparationFacts"))
                || !object(TinyJson.parse(atom.json())).equals(report.get("atomPatternFacts"))
                || !(machine == null ? report.get("machineEvidenceFacts") == null
                        : object(TinyJson.parse(machine.json())).equals(report.get("machineEvidenceFacts")))
                || !expected.equals(report.get("applicableRecipes"))
                || !object(TinyJson.parse(entry.json())).equals(report.get("selectedRecipe"))
                || !Boolean.FALSE.equals(report.get("modelOutputTrusted"))
                || !Boolean.FALSE.equals(report.get("recipeApproved"))
                || !Boolean.FALSE.equals(report.get("recipeExecuted"))
                || !Boolean.FALSE.equals(report.get("semanticProofAuthenticated"))
                || !Boolean.FALSE.equals(report.get("verificationAuthenticated")))
            throw new IOException("stale or unapproved code-agent nomination");
    }

    private static Path ordinaryJar(Path root, Path file) throws IOException {
        Path candidate = root.toAbsolutePath().normalize();
        for (Path part : root.relativize(file)) {
            candidate = candidate.resolve(part);
            if (Files.isSymbolicLink(candidate)) throw new IOException("offline Maven artifact symlink");
        }
        if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) == 0 || Files.size(file) > 268_435_456L)
            throw new IOException("offline Maven artifact missing or too large");
        return file;
    }

    private static void stageCopy(Path base, Path stage, Path build) throws IOException {
        long[] total = { 0, 0 };
        Files.walkFileTree(base, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (directory.startsWith(build) || directory.getFileName().toString().equals(".git")
                        || !directory.equals(base) && directory.getFileName().toString().equals("target"))
                    return FileVisitResult.SKIP_SUBTREE;
                if (attributes.isSymbolicLink() || !attributes.isDirectory()) throw new IOException("project link in staging scope");
                Files.createDirectories(stage.resolve(base.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (file.startsWith(build) || file.getFileName().toString().equals(".git"))
                    return FileVisitResult.CONTINUE;
                if (attributes.isSymbolicLink() || !attributes.isRegularFile()) throw new IOException("project link in staging scope");
                if (++total[0] > MAX_STAGE_FILES || (total[1] += attributes.size()) > MAX_STAGE_BYTES)
                    throw new IOException("staging size exceeded");
                Files.copy(file, stage.resolve(base.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static TreeMap<String, String> snapshot(Path stage) throws IOException {
        TreeMap<String, String> files = new TreeMap<>();
        Files.walkFileTree(stage, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(stage) && dir.getFileName().toString().equals("target"))
                    return FileVisitResult.SKIP_SUBTREE;
                if (attrs.isSymbolicLink()) throw new IOException("stage directory symlink");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink() || !attrs.isRegularFile()) throw new IOException("stage output symlink");
                files.put(stage.relativize(file).toString().replace('\\', '/'), Hashes.sha256(file));
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private static void runMaven(Path stage, Path executable, Path repository, List<String> goals,
                                 String log, int timeout) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(executable.toString(), "-o", "-B", "-ntp",
                "-Dmaven.repo.local=" + repository));
        command.addAll(goals);
        Path logs = stage.resolve("target/m3-code-agent");
        Files.createDirectories(logs);
        ProcessBuilder builder = new ProcessBuilder(command).directory(stage.toFile())
                .redirectErrorStream(true).redirectOutput(logs.resolve(log).toFile());
        builder.environment().remove("MAVEN_ARGS");
        Process process = builder.start();
        try {
            if (!process.waitFor(Duration.ofSeconds(timeout).toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(child -> child.destroyForcibly());
                process.destroyForcibly();
                throw new IOException("offline Maven timed out");
            }
            if (process.exitValue() != 0) throw new IOException("offline Maven goal failed");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static int refused(Path output, Path stage, boolean attempted, boolean verified,
                               String name, PrintStream error) {
        if (output != null) try {
            SafeBuildOutput.write(output, "code-agent-verify.json", "{\"schema\":\"m3.smart.code-agent-verify.v1\","
                    + "\"status\":\"refused\",\"errorType\":" + TinyJson.quote(name)
                    + ",\"stagePresent\":" + (stage != null)
                    + ",\"recipeExecutionAttempted\":" + attempted + ",\"checksCompleted\":" + verified
                    + ",\"semanticProofAuthenticated\":false,\"verificationAuthenticated\":false,"
                    + "\"promoted\":false}\n");
        } catch (IOException ignored) { /* Refusal is still nonzero. */ }
        error.println("M3 code-agent verify refused: " + name);
        return 2;
    }

    private static Map<?, ?> object(Object raw) throws IOException {
        if (!(raw instanceof Map<?, ?> map)) throw new IOException("JSON object required");
        return map;
    }
    private static String sha(String s) throws IOException {
        if (!s.matches("[0-9a-f]{64}")) throw new IOException("SHA-256 required");
        return s;
    }
    private static Map<String, String> parse(String[] args) {
        if (args.length % 2 != 0) throw new IllegalArgumentException("option/value pairs required");
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2)
            if (!OPTIONS.contains(args[i]) || map.putIfAbsent(args[i], args[i + 1]) != null)
                throw new IllegalArgumentException("unknown or duplicate option");
        return map;
    }
    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("required option missing");
        return value;
    }
    private static Path resolve(Path base, String path) { return base.resolve(path).toAbsolutePath().normalize(); }
}
