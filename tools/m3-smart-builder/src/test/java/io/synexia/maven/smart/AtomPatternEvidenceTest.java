// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Receipt fixtures check structural admission; their digests do not claim executed semantic proofs. */
public final class AtomPatternEvidenceTest {
    private static int checks;
    private AtomPatternEvidenceTest() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--write-action-fixture")) {
            writeActionFixture(Path.of(args[1]), Path.of(args[2]));
            return;
        }
        if (args.length != 0) throw new IllegalArgumentException("unexpected fixture arguments");
        Fixture fixture = new Fixture(Files.createTempDirectory("atom-pattern-hooks-"));
        fixture.reset();
        require(fixture.hook(false, false, "pre-build") == 0, "legacy opt-in behavior");
        require("not-requested".equals(fixture.report("pre-build").get("atomPatternStatus")), "legacy evidence status");
        fixture.refused("manifest-required", false);
        fixture.reset();
        AtomPatternEvidence.Summary summary = AtomPatternEvidence.inspect(fixture.root, fixture.source, fixture.manifest);
        require(summary.javaFiles() == 2, "every scoped Java file included");
        require(summary.nonJavaFiles() == 1, "non-Java files counted outside the Java DAG");
        require(summary.manifestSha256().equals(hash(Files.readString(fixture.manifest))), "exact manifest identity");
        require(AtomPatternEvidence.inspect(fixture.root, Path.of("src"), Path.of("manifest.tsv")).equals(summary),
                "direct inspection resolves relative paths against the supplied project, not process cwd");
        require(fixture.hook(true, true, "pre-build") == 0, "valid pre-build checkpoint binding");
        require(fixture.hook(true, true, "post-build") == 0, "valid post-build checkpoint binding");
        Map<String, Object> post = fixture.report("post-build");
        require("failure".equals(post.get("buildOutcome")), "receipt cannot clear build failure");
        require("structurally-validated".equals(post.get("atomPatternStatus")), "structural status is explicit");
        require(Boolean.FALSE.equals(object(post.get("atomPatternFacts")).get("semanticProofAuthenticated")),
                "digest binding is not execution authentication");
        if (fixture.hasProvider()) {
            require(Files.exists(fixture.invoked), "accepted evidence reaches the actual synthetic subprocess");
            String request = Files.readString(fixture.request);
            require(request.contains("structurally-validated") && request.contains(summary.manifestSha256()),
                    "bounded prompt receives evidence status and identity");
            require(!request.contains("DO_NOT_DISCLOSE_SOURCE"), "source text excluded from prompt");
        }

        Files.writeString(fixture.source.resolve("A.java"), "class A { int stale; }\n");
        fixture.refused("stale-source", true);
        fixture.reset();
        Files.delete(fixture.checkpoint("src/A.java"));
        fixture.refused("unreadable-evidence", true);
        fixture.reset();
        Files.writeString(fixture.checkpoint("src/A.java"), "# changed\n", StandardOpenOption.APPEND);
        fixture.refused("checkpoint-digest", true);
        fixture.reset();

        List<String> manifest = Files.readAllLines(fixture.manifest);
        lines(fixture.manifest, List.of(manifest.get(0), manifest.get(1), manifest.get(2), manifest.get(2)));
        fixture.refused("source-order-or-duplicate", true);
        fixture.reset();
        manifest = Files.readAllLines(fixture.manifest);
        lines(fixture.manifest, manifest.subList(0, 3));
        fixture.refused("source-coverage", true);
        fixture.reset();
        manifest = Files.readAllLines(fixture.manifest);
        lines(fixture.manifest, List.of(manifest.get(0), manifest.get(1), manifest.get(3), manifest.get(2)));
        fixture.refused("source-order-or-duplicate", true);
        fixture.reset();
        Path unexpected = Files.writeString(fixture.source.resolve("Unexpected.java"), "class Unexpected {}\n");
        fixture.refused("source-coverage", true);
        Files.delete(unexpected);

        fixture.changeCheckpoint(text -> text.replaceFirst("FILE_ATOMIZATION", "FILE_PATTERNIZATION"));
        fixture.refused("checkpoint-stage-order", true);
        fixture.reset();
        fixture.changeCell(3, 3, hash("disconnected"));
        fixture.refused("checkpoint-chain", true);
        fixture.reset();
        fixture.changeCell(1, 4, hash("inventory changed source"));
        fixture.refused("checkpoint-inventory-mutated", true);
        fixture.reset();
        fixture.changeCell(6, 4, hash("replay changed source"));
        fixture.refused("checkpoint-not-fixed-point", true);
        fixture.reset();
        fixture.changeCell(2, 2, "src/Other.java");
        fixture.refused("checkpoint-source", true);
        fixture.reset();
        fixture.changeCell(2, 8, "");
        fixture.refused("digest-format", true);
        fixture.reset();
        fixture.changeCell(2, 5, "A".repeat(64));
        fixture.refused("digest-format", true);
        fixture.reset();
        fixture.changeCheckpoint(text -> text + text.lines().skip(1).findFirst().orElseThrow() + "\n");
        fixture.refused("checkpoint-schema-or-coverage", true);
        fixture.reset();

        manifest = Files.readAllLines(fixture.manifest);
        String[] second = manifest.get(3).split("\t", -1);
        second[3] = fixture.relative(fixture.checkpoint("src/A.java"));
        lines(fixture.manifest, List.of(manifest.get(0), manifest.get(1), manifest.get(2), String.join("\t", second)));
        fixture.refused("duplicate-checkpoint-path", true);
        fixture.reset();
        Files.writeString(fixture.manifest, Files.readString(fixture.manifest).replace("src/A.java", "../outside/A.java"));
        fixture.refused("noncanonical-path", true);
        fixture.reset();
        fixture.changeCheckpoint(text -> text.replace("\n", "\r\n"));
        fixture.refused("receipt-line-format", true);
        fixture.reset();
        Files.write(fixture.manifest, new byte[] {(byte) 0xc3, (byte) 0x28});
        fixture.refused("unreadable-evidence", true);
        fixture.reset();
        Files.writeString(fixture.checkpoint("src/A.java"), "x".repeat(64 * 1024 + 1));
        fixture.rewriteManifest();
        fixture.refused("receipt-size-limit", true);
        fixture.reset();
        Path oversized = fixture.source.resolve("Oversized.java");
        try (var output = Files.newByteChannel(oversized, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            output.position(16L * 1024 * 1024);
            output.write(ByteBuffer.wrap(new byte[] {1}));
        }
        fixture.refused("java-size-limit", true);
        Files.delete(oversized);

        Path link = fixture.source.resolve("Linked.java");
        Files.createSymbolicLink(link, fixture.root.resolve("pom.xml"));
        fixture.refused("source-link", true);
        Files.delete(link);
        Path external = Files.createTempDirectory("atom-pattern-outside-");
        link = fixture.source.resolve("escaping-directory");
        Files.createSymbolicLink(link, external);
        fixture.refused("source-link", true);
        Files.delete(link);
        Path checkpoint = fixture.checkpoint("src/A.java");
        Path copy = Files.writeString(external.resolve("checkpoint.tsv"), Files.readString(checkpoint));
        Files.delete(checkpoint);
        Files.createSymbolicLink(checkpoint, copy);
        fixture.refused("evidence-path-link", true);
        Files.delete(checkpoint);
        fixture.reset();

        // Supplying invalid optional evidence also refuses; only absence preserves the legacy contract.
        Files.writeString(fixture.source.resolve("A.java"), "class A { int stale; }\n");
        fixture.clearObservation();
        require(fixture.hook(false, true, "pre-build") == 2, "invalid supplied optional evidence cannot reach inference");
        require(!Files.exists(fixture.invoked), "optional evidence refusal precedes provider launch");
        fixture.reset();
        largeScope(fixture.root);
        System.out.println("ATOM PATTERN EVIDENCE TESTS PASS: " + checks
                + (fixture.hasProvider() ? " (synthetic provider observed)" : " (provider disabled)"));
    }

    static void writeActionFixture(Path project, Path sourceRoot) throws Exception {
        Path base = project.toRealPath();
        Path source = base.resolve(sourceRoot).toRealPath();
        if (!source.startsWith(base)) throw new IllegalArgumentException("fixture source must be local");
        Path directory = Files.createDirectory(base.resolve("synthetic-checkpoints"));
        StringBuilder manifest = new StringBuilder(AtomPatternEvidence.VERSION + "\n" + AtomPatternEvidence.HEADER + "\n");
        List<Path> files;
        try (var stream = Files.walk(source)) {
            files = stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java"))
                    .sorted().toList();
        }
        int index = 0;
        for (Path file : files) {
            String name = base.relativize(file).toString().replace('\\', '/');
            String digest = Hashes.sha256(Files.readAllBytes(file));
            String checkpoint = checkpoints(name, digest, digest);
            String receipt = "source-" + index++ + ".tsv";
            Files.writeString(directory.resolve(receipt), checkpoint);
            manifest.append(name).append('\t').append(digest).append('\t').append(digest)
                    .append("\tsynthetic-checkpoints/").append(receipt).append('\t').append(hash(checkpoint)).append('\n');
        }
        Files.writeString(base.resolve("synthetic-checkpoints.tsv"), manifest);
        System.out.println("SYNTHETIC ACTION CHECKPOINTS: " + index + " files; no semantic proof execution claimed");
    }

    private static void largeScope(Path root) throws Exception {
        Path scope = Files.createDirectories(root.resolve("large-source"));
        Path receipts = Files.createDirectories(root.resolve("large-receipts"));
        StringBuilder manifest = new StringBuilder(AtomPatternEvidence.VERSION + "\n" + AtomPatternEvidence.HEADER + "\n");
        for (int i = 0; i < 2001; i++) {
            String name = "F%04d.java".formatted(i);
            String path = "large-source/" + name;
            String source = "class F" + i + " {}\n";
            Files.writeString(scope.resolve(name), source);
            String checkpoint = checkpoints(path, hash(source), hash(source));
            String receipt = name + ".tsv";
            Files.writeString(receipts.resolve(receipt), checkpoint);
            manifest.append(path).append('\t').append(hash(source)).append('\t').append(hash(source))
                    .append("\tlarge-receipts/").append(receipt).append('\t').append(hash(checkpoint)).append('\n');
        }
        Path file = Files.writeString(root.resolve("large-manifest.tsv"), manifest);
        require(AtomPatternEvidence.inspect(root, scope, file).javaFiles() == 2001,
                "strict coverage is independent of the legacy 2000-file diagnostics bound");
    }

    private static String checkpoints(String source, String before, String after) {
        StringBuilder result = new StringBuilder(AtomPatternEvidence.CHECKPOINT_HEADER + "\n");
        String current = before;
        for (int round = 1; round <= 2; round++) {
            for (String phase : List.of("INVENTORY", "FILE_ATOMIZATION", "FILE_PATTERNIZATION", "FILE_DOCUMENTATION")) {
                String next = round == 1 && phase.equals("FILE_ATOMIZATION") ? after : current;
                result.append(round).append('\t').append(phase).append('\t').append(source).append('\t')
                        .append(current).append('\t').append(next);
                for (String proof : List.of("compiler", "contract", "behavior", "jit"))
                    result.append('\t').append(hash("SYNTHETIC-ATTESTATION:" + phase + ":" + proof));
                result.append('\n');
                current = next;
            }
        }
        return result.toString();
    }

    private static final class Fixture {
        private final Path root, source, manifest, invoked, request;
        private final TreeMap<String, String> sources = new TreeMap<>();
        private final List<String> arguments = new ArrayList<>();

        Fixture(Path root) throws Exception {
            this.root = root;
            source = Files.createDirectories(root.resolve("src"));
            manifest = root.resolve("manifest.tsv");
            Files.createDirectories(root.resolve("proofs"));
            Files.createDirectories(source.resolve("nested"));
            Files.writeString(source.resolve("README.txt"), "Outside the Java DAG lane.\n");
            Files.writeString(root.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion>"
                    + "<groupId>test</groupId><artifactId>evidence</artifactId><version>1</version></project>");
            Files.writeString(root.resolve("build.log"), "[ERROR] cannot find symbol\n");
            sources.put("src/A.java", "class A { private int value() { return 1; } } // DO_NOT_DISCLOSE_SOURCE\n");
            sources.put("src/nested/B.java", "class B {}\n");
            Path profile = root.resolve("mcp-evidence-observer.gguf");
            invoked = Path.of(profile + ".invoked");
            request = Path.of(profile + ".request");
            arguments.addAll(List.of("--project-dir", root.toString(), "--build-dir", ".reports", "--source-root", "src"));
            if (hasProvider()) {
                Path executable = Files.writeString(root.resolve("llama-fixture"), "synthetic executable\n");
                Files.writeString(profile, "fixture model\n");
                arguments.addAll(List.of("--backend", "cli", "--chrome-llm-jar", System.getProperty("m3.smart.fixtureJar"),
                        "--llama-bin", executable.toString(), "--model", profile.toString(), "--timeout-seconds", "7"));
            }
        }

        boolean hasProvider() { return System.getProperty("m3.smart.fixtureJar") != null; }
        String relative(Path path) { return root.relativize(path).toString().replace('\\', '/'); }
        Path checkpoint(String sourcePath) { return root.resolve("proofs/" + sourcePath.replace('/', '_') + ".tsv"); }

        void reset() throws Exception {
            for (var entry : sources.entrySet()) {
                Files.writeString(root.resolve(entry.getKey()), entry.getValue());
                Files.writeString(checkpoint(entry.getKey()), checkpoints(entry.getKey(), hash("original:" + entry.getKey()), hash(entry.getValue())));
            }
            rewriteManifest();
            clearObservation();
        }

        void rewriteManifest() throws Exception {
            StringBuilder text = new StringBuilder(AtomPatternEvidence.VERSION + "\n" + AtomPatternEvidence.HEADER + "\n");
            for (var entry : sources.entrySet()) text.append(entry.getKey()).append('\t')
                    .append(hash("original:" + entry.getKey())).append('\t').append(hash(entry.getValue())).append('\t')
                    .append(relative(checkpoint(entry.getKey()))).append('\t')
                    .append(hash(Files.readString(checkpoint(entry.getKey())))).append('\n');
            Files.writeString(manifest, text);
        }

        void changeCheckpoint(java.util.function.UnaryOperator<String> mutation) throws Exception {
            Path path = checkpoint("src/A.java");
            Files.writeString(path, mutation.apply(Files.readString(path)));
            rewriteManifest();
        }

        void changeCell(int row, int column, String value) throws Exception {
            changeCheckpoint(text -> {
                String[] lines = text.split("\n", -1);
                String[] cells = lines[row].split("\t", -1);
                cells[column] = value;
                lines[row] = String.join("\t", cells);
                return String.join("\n", lines);
            });
        }

        void clearObservation() throws Exception { Files.deleteIfExists(invoked); Files.deleteIfExists(request); }

        void refused(String reason, boolean includeManifest) throws Exception {
            clearObservation();
            require(hook(true, includeManifest, "post-build") == 2, "reject " + reason);
            Map<String, Object> report = report("post-build");
            require("blocked-evidence".equals(report.get("adviceStatus")), "blocked advice for " + reason);
            require(reason.equals(report.get("atomPatternError")), "specific refusal: expected " + reason + ", received " + report);
            require("failure".equals(report.get("buildOutcome")), "preserve failure for " + reason);
            require(!Files.exists(invoked), "reject " + reason + " before actual provider invocation");
        }

        int hook(boolean required, boolean includeManifest, String phase) throws Exception {
            List<String> args = new ArrayList<>(arguments);
            args.addAll(List.of("--phase", phase, "--require-atom-pattern", Boolean.toString(required)));
            if (includeManifest) args.addAll(List.of("--atom-pattern-manifest", "manifest.tsv"));
            if (phase.equals("post-build")) args.addAll(List.of("--build-outcome", "failure", "--log-file", "build.log"));
            try (PrintStream messages = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)) {
                return BuildHookMain.run(args.toArray(String[]::new), messages, messages);
            }
        }

        Map<String, Object> report(String phase) throws Exception {
            return object(TinyJson.parse(Files.readString(root.resolve(".reports/m3-smart/" + phase + ".json"))));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
    private static String hash(String value) { return Hashes.sha256(value.getBytes(StandardCharsets.UTF_8)); }
    /** Writes LF-terminated lines on every platform; the manifest contract refuses any other line ending. */
    private static void lines(Path file, List<String> rows) throws java.io.IOException {
        Files.writeString(file, String.join("\n", rows) + "\n", StandardCharsets.UTF_8);
    }
    private static void require(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
