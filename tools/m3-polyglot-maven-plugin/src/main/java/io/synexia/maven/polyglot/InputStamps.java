/*
 * SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Content identities for the original task runner. Each file is hashed separately;
 * length-framed paths, kinds, byte counts and digests prevent cross-file ambiguity.
 * No timestamp can authorize reuse. M3IndexDB owns persistence, not this scanner.
 */
final class InputStamps {
    private static final String DOMAIN = "m3.maven.module-task.v3";
    private static final int MAX_PATHS = 262_144;
    private static final long MAX_CONTENT_BYTES = 16L * 1024 * 1024 * 1024;
    private static final List<String> CONFIGURATION = List.of(
            "pom.xml", "m3-build.tsv", ".mvn/maven.config", ".mvn/jvm.config", ".mvn/extensions.xml", ".mvn/toolchains.xml");

    private InputStamps() {}

    /**
     * Immutable transcript of the exact bytes previously fed into the task
     * fingerprint for the loaded M3 engine implementation. This is deliberately
     * NOT a cached source-file digest: every task input and every output remains
     * content-hashed on every verification. One loaded plugin realm executes one
     * engine implementation for the lifetime of a module run.
     */
    static final class EngineTranscript {
        private final byte[] data;

        private EngineTranscript(byte[] data) { this.data = data.clone(); }

        void appendTo(MessageDigest fingerprint) { fingerprint.update(data); }

        int size() { return data.length; }
    }

    /**
     * A packaged engine JAR is fixed for the lifetime of the executing class
     * realm. During a source checkout or IDE build, the engine can be loaded
     * from mutable target/classes directories: retain the original per-task
     * full content hashing in that case, including after execution.
     */
    static EngineTranscript captureStableEngineTranscript() throws IOException {
        for (Path source : engineSources().values()) {
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) return null;
        }
        return captureEngineTranscript();
    }

    /** Capture engine JAR/package identity once per selected task graph. */
    static EngineTranscript captureEngineTranscript() throws IOException {
        TranscriptDigest recorder = new TranscriptDigest();
        engine(recorder);
        byte[] data = recorder.digest();
        if (data.length > 1_048_576) {
            throw new IOException("M3 engine digest transcript exceeds 1 MiB");
        }
        return new EngineTranscript(data);
    }

    /**
     * Bind the actual command, inherited process environment, local Maven configuration,
     * declared inputs and successful prerequisite generations to this module location.
     * Tool libraries, external parents/settings and other hidden inputs remain declarations
     * owned by the caller; this is not a claim of arbitrary process hermeticity.
     */
    static String stamp(TaskManifest.Task task, Path root, List<String> command,
            Map<String, String> environment, Map<String, String> prerequisites) throws IOException {
        return stamp(task, root, command, environment, prerequisites, false, null);
    }

    static String stamp(TaskManifest.Task task, Path root, List<String> command,
            Map<String, String> environment, Map<String, String> prerequisites,
            EngineTranscript engine) throws IOException {
        return stamp(task, root, command, environment, prerequisites, false, engine);
    }

    /** Explicit output-cache opt-in additionally declares that outputs can be relocated. */
    static String portableStamp(TaskManifest.Task task, Path root, List<String> command,
            Map<String, String> environment, Map<String, String> prerequisites) throws IOException {
        return stamp(task, root, command, environment, prerequisites, true, null);
    }

    static String portableStamp(TaskManifest.Task task, Path root, List<String> command,
            Map<String, String> environment, Map<String, String> prerequisites,
            EngineTranscript engine) throws IOException {
        return stamp(task, root, command, environment, prerequisites, true, engine);
    }

    /** Standalone artifact-key helper; prerequisite work requires the verified-generation overload. */
    static String stamp(TaskManifest.Task task, Path root) throws IOException {
        if (!task.prerequisites().isEmpty()) throw new IOException("artifact key requires verified prerequisites");
        Path realRoot = root.toAbsolutePath().normalize().toRealPath();
        List<String> command = new ArrayList<>();
        command.add(TaskRunner.resolveTool(task.tool(), realRoot.resolve(task.workingDir())));
        command.addAll(task.arguments());
        return portableStamp(task, realRoot, command, System.getenv(), Map.of());
    }

    private static String stamp(TaskManifest.Task task, Path root, List<String> command,
            Map<String, String> environment, Map<String, String> prerequisites,
            boolean portable, EngineTranscript engineTranscript) throws IOException {
        MessageDigest digest = sha256();
        update(digest, DOMAIN);
        update(digest, portable ? "relocatable-output-cache-v1" : root.toString());
        update(digest, task.name());
        update(digest, task.tool());
        update(digest, task.workingDir());
        List<String> identityCommand = new ArrayList<>(command);
        if (portable) {
            Path executable = Path.of(command.get(0));
            if (executable.isAbsolute() && executable.normalize().startsWith(root)) {
                identityCommand.set(0, "module:" + relative(root, executable.normalize()));
            }
        }
        strings(digest, "argv", identityCommand);
        strings(digest, "outputs", task.outputs());
        strings(digest, "prerequisite-names", task.prerequisites().stream().sorted().toList());
        for (String dependency : task.prerequisites().stream().sorted().toList()) {
            update(digest, dependency);
            update(digest, Objects.requireNonNull(prerequisites.get(dependency), "unverified prerequisite"));
        }
        for (Map.Entry<String, String> variable : new TreeMap<>(environment).entrySet()) {
            update(digest, "environment");
            update(digest, variable.getKey());
            update(digest, variable.getValue());
        }
        for (String property : List.of("os.name", "os.version", "os.arch", "java.home",
                "java.version", "java.runtime.version", "java.vendor", "file.encoding", "user.language", "user.country")) {
            update(digest, property);
            update(digest, System.getProperty(property, ""));
        }
        if (engineTranscript == null) engine(digest);
        else engineTranscript.appendTo(digest);
        Path tool = Path.of(command.get(0));
        if (!tool.isAbsolute()) tool = root.resolve(task.workingDir()).resolve(tool);
        // Installed tool launchers commonly use symlinks. Bind the actual target and bytes.
        tool = launchTarget(tool).toRealPath();
        update(digest, "executable");
        update(digest, portable && tool.startsWith(root) ? "module:" + relative(root, tool) : tool.toString());
        file(digest, tool, new Budget());
        configuration(digest, root, portable);
        update(digest, paths(task.inputs(), root));
        return hex(digest);
    }

    /** Retains the package contract used by the upstream artifact store. */
    static String digest(String... values) {
        MessageDigest digest = sha256();
        for (String value : values) update(digest, value);
        return hex(digest);
    }

    /** Raw SHA-256 of file bytes for the artifact store's content-addressed blobs. */
    static String fileSha256(Path path) throws IOException {
        try (InputStream bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            return content(bytes, new Budget());
        }
    }

    /**
     * Windows starts {@code name.exe} for a tool path whose file name has no extension (for example
     * {@code .../bin/java}); fingerprint that file instead of failing on the extensionless spelling.
     */
    private static Path launchTarget(Path tool) {
        if (!System.getProperty("os.name", "").startsWith("Windows")
                || Files.exists(tool, LinkOption.NOFOLLOW_LINKS)) return tool;
        Path name = tool.getFileName();
        if (name == null || name.toString().indexOf('.') >= 0) return tool;
        return tool.resolveSibling(name + ".exe");
    }

    /** Exact declared output tree identity, including empty directories and added files. */
    static String outputs(TaskManifest.Task task, Path root) throws IOException {
        return paths(task.outputs(), root);
    }

    /** Stable generation used by a dependent task or an aggregation target. */
    static String combine(List<String> values) {
        MessageDigest digest = sha256();
        update(digest, DOMAIN + ".generation");
        strings(digest, "values", values);
        return hex(digest);
    }

    /** In-place tasks and references to the cache's own files cannot have reusable results. */
    static boolean separate(TaskManifest.Task task) {
        for (String input : task.inputs()) {
            if (statePath(input)) return false;
            for (String output : task.outputs()) if (overlaps(input, output)) return false;
        }
        return task.outputs().stream().noneMatch(InputStamps::statePath);
    }

    static boolean statePath(String path) {
        return ModuleStateDb.statePath(path) || overlaps(path, ".m3/polyglot-stamps");
    }

    private static boolean overlaps(String first, String second) {
        return first.equals(second) || first.startsWith(second + "/") || second.startsWith(first + "/");
    }

    private static String paths(List<String> declarations, Path root) throws IOException {
        MessageDigest digest = sha256();
        update(digest, DOMAIN + ".paths");
        Budget budget = new Budget();
        for (String declaration : declarations.stream().sorted().toList()) {
            update(digest, "declaration");
            update(digest, declaration);
            Path path = checked(root, declaration);
            List<Path> entries = new ArrayList<>();
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                private void admit(Path entry, BasicFileAttributes attributes) throws IOException {
                    if (++budget.paths > MAX_PATHS) throw new IOException("module file identity budget exceeded");
                    if (!attributes.isRegularFile() && !attributes.isDirectory()) {
                        throw new IOException("module input/output is a symlink or special file: " + entry);
                    }
                    entries.add(entry);
                }

                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs)
                        throws IOException {
                    admit(directory, attrs);
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFile(Path entry, BasicFileAttributes attrs) throws IOException {
                    admit(entry, attrs);
                    return FileVisitResult.CONTINUE;
                }
            });
            entries.sort(Comparator.comparing(entry -> relative(root, entry)));
            for (Path entry : entries) {
                String relative = relative(root, entry);
                checked(root, relative);
                update(digest, relative);
                BasicFileAttributes attributes = Files.readAttributes(
                        entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isDirectory()) {
                    update(digest, "directory");
                } else if (attributes.isRegularFile()) {
                    update(digest, "file");
                    file(digest, entry, budget);
                } else {
                    throw new IOException("module path changed type while hashing: " + relative);
                }
            }
        }
        return hex(digest);
    }

    /** Preserve the original runner's lexical and symlink boundary for all path components. */
    static Path checked(Path root, String relative) throws IOException {
        Path value = root.resolve(relative).normalize();
        if (!value.startsWith(root)) throw new IOException("resource is outside polyglot project root: " + relative);
        Path cursor = root;
        for (Path component : root.relativize(value)) {
            cursor = cursor.resolve(component);
            if (Files.isSymbolicLink(cursor)) throw new IOException("polyglot declared path traverses symlink: " + relative);
        }
        return value;
    }

    /**
     * Hash the complete owning JARs or package trees, including anonymous/nested helpers. A platform
     * M3IndexDB (M3jdk21) has no code source of its own; its module and runtime identity stand for it.
     */
    private static void engine(MessageDigest digest) throws IOException {
        update(digest, IndexStores.engineIdentity(ModuleLayer.boot()));
        for (Map.Entry<String, Path> source : engineSources().entrySet()) {
            update(digest, "engine");
            update(digest, source.getKey());
            Path path = source.getValue();
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                update(digest, paths(List.of(path.getFileName().toString()), path.getParent()));
            } else {
                file(digest, path, new Budget());
            }
        }
    }

    private static Map<String, Path> engineSources() throws IOException {
        Map<String, Path> sources = new TreeMap<>();
        for (Class<?> owner : IndexStores.engineClasses(InputStamps.class, ModuleLayer.boot())) {
            Path location = codeSource(owner);
            if (!Files.isRegularFile(location, LinkOption.NOFOLLOW_LINKS)) {
                location = location.resolve(owner.getPackageName().replace('.', '/'));
            }
            sources.put(location.toString(), location);
        }
        return sources;
    }

    private static Path codeSource(Class<?> owner) throws IOException {
        var source = owner.getProtectionDomain().getCodeSource();
        if (source == null) throw new IOException("missing task engine code source");
        try {
            return Path.of(source.getLocation().toURI()).toRealPath();
        } catch (URISyntaxException | IllegalArgumentException invalid) {
            throw new IOException("unsupported task engine code source", invalid);
        }
    }

    private static void configuration(MessageDigest digest, Path root, boolean portable) throws IOException {
        Budget budget = new Budget();
        Path directory = root;
        for (int depth = 0; directory != null; depth++, directory = directory.getParent()) {
            if (depth == 128) throw new IOException("Maven configuration ancestry budget exceeded");
            for (String name : CONFIGURATION) {
                Path configuration = checked(directory, name);
                boolean absent = Files.notExists(configuration, LinkOption.NOFOLLOW_LINKS);
                // Empty ancestor levels do not make an otherwise identical checkout nonrelocatable.
                if (portable && depth > 0 && absent) continue;
                update(digest, portable ? "configuration:" + depth + ":" + name : configuration.toString());
                if (absent) {
                    update(digest, "absent");
                } else {
                    update(digest, "present");
                    file(digest, configuration, budget);
                }
            }
        }
    }

    private static void file(MessageDigest digest, Path path, Budget budget) throws IOException {
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile()) throw new IOException("not an ordinary file: " + path);
        update(digest, Long.toString(before.size()));
        long previousBytes = budget.bytes;
        try (InputStream bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            update(digest, content(bytes, budget));
        }
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || before.size() != after.size()
                || budget.bytes - previousBytes != before.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) {
            throw new IOException("file changed while computing module identity: " + path);
        }
    }

    private static String content(InputStream input, Budget budget) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[65_536];
        for (int count; (count = input.read(buffer)) != -1;) {
            if (count == 0) throw new IOException("stalled module content reader");
            budget.bytes += count;
            if (budget.bytes > MAX_CONTENT_BYTES) throw new IOException("module content identity byte budget exceeded");
            digest.update(buffer, 0, count);
        }
        return hex(digest);
    }

    private static void strings(MessageDigest digest, String role, List<String> values) {
        update(digest, role);
        update(digest, Integer.toString(values.size()));
        for (String value : values) update(digest, value);
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static String relative(Path root, Path entry) {
        return root.relativize(entry).toString().replace('\\', '/');
    }

    private static String hex(MessageDigest digest) { return HexFormat.of().formatHex(digest.digest()); }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    /**
     * Replays exactly the update stream of engine(digest). Source bytes are
     * hashed inside file()/paths(); only small framed digest facts are retained.
     * Replaying this transcript yields byte-identical legacy input keys.
     */
    private static final class TranscriptDigest extends MessageDigest {
        private final ByteArrayOutputStream transcript = new ByteArrayOutputStream(1024);

        TranscriptDigest() { super("M3-ENGINE-TRANSCRIPT"); }

        @Override protected void engineUpdate(byte value) { transcript.write(value); }

        @Override protected void engineUpdate(byte[] values, int offset, int count) {
            transcript.write(values, offset, count);
        }

        @Override protected byte[] engineDigest() { return transcript.toByteArray(); }

        @Override protected void engineReset() { transcript.reset(); }
    }

    private static final class Budget { private int paths; private long bytes; }
}
