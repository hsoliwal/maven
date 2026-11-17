/*
 * SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

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
import java.util.stream.Stream;

/**
 * Content identities for the original task runner. Each file is hashed separately;
 * length-framed paths, kinds, byte counts and digests prevent cross-file ambiguity.
 * No timestamp can authorize reuse. Persistence belongs to the selected state backend:
 * legacy per-task stamp files here, or the module M3IndexDB image of {@link M3DbModuleStore}.
 */
final class InputStamps {
    private static final String DOMAIN = "m3.maven.module-task.v2";
    private static final int MAX_PATHS = 262_144;
    private static final long MAX_CONTENT_BYTES = 16L * 1024 * 1024 * 1024;
    private static final List<String> CONFIGURATION = List.of(
            "pom.xml", ".mvn/maven.config", ".mvn/jvm.config", ".mvn/extensions.xml", ".mvn/toolchains.xml");

    /** Per-task stamp files of the default "legacy" incremental state backend. */
    static final String DIRECTORY = ".m3/polyglot-stamps";

    private InputStamps() {}

    /**
     * Legacy per-task stamp: SHA-256 over the task definition and every declared input file;
     * directories recursively, sorted. Each file contributes its length-framed size and own
     * SHA-256, so bytes of one file can no longer impersonate the path and bytes of another.
     */
    static String stamp(TaskManifest.Task task, Path root) throws IOException {
        MessageDigest digest = sha256();
        update(digest, "task");
        update(digest, task.name());
        update(digest, task.tool());
        update(digest, task.workingDir());
        for (String argument : task.arguments()) {
            update(digest, "arg");
            update(digest, argument);
        }
        for (String output : task.outputs()) {
            update(digest, "output");
            update(digest, output);
        }
        for (String input : task.inputs()) {
            Path path = root.resolve(input).normalize();
            update(digest, "input");
            update(digest, input);
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                List<Path> files = new ArrayList<>();
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).sorted().forEach(files::add);
                }
                for (Path file : files) {
                    update(digest, relative(root, file));
                    file(digest, file, new Budget());
                }
            } else {
                file(digest, path, new Budget());
            }
        }
        return hex(digest);
    }

    /** Legacy: true when the recorded stamp equals the current one and every declared output exists. */
    static boolean upToDate(TaskManifest.Task task, Path root, String current) throws IOException {
        Path file = file(task, root);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
        if (!current.equals(Files.readString(file, StandardCharsets.UTF_8).strip())) return false;
        for (String output : task.outputs()) {
            if (!Files.exists(root.resolve(output).normalize(), LinkOption.NOFOLLOW_LINKS)) return false;
        }
        return true;
    }

    /** Legacy: records the stamp of a task that completed and produced its declared outputs. */
    static void record(TaskManifest.Task task, Path root, String current) throws IOException {
        Path file = file(task, root);
        Files.createDirectories(file.getParent());
        Files.writeString(file, current + "\n", StandardCharsets.UTF_8);
    }

    static Path file(TaskManifest.Task task, Path root) {
        return root.resolve(DIRECTORY).resolve(task.name() + ".sha256");
    }

    /** SHA-256 over length-framed values, framed like {@link #stamp(TaskManifest.Task, Path)}. */
    static String digest(String... values) {
        MessageDigest digest = sha256();
        for (String value : values) update(digest, value);
        return hex(digest);
    }

    /** SHA-256 of the bytes of one file, as hex. */
    static String fileSha256(Path file) throws IOException {
        try (InputStream bytes = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return content(bytes, new Budget());
        }
    }

    /**
     * M3DB ("m3db" state backend): bind the actual command, inherited process environment, local Maven configuration,
     * declared inputs and successful prerequisite generations to this module location.
     * Tool libraries, external parents/settings and other hidden inputs remain declarations
     * owned by the caller; this is not a claim of arbitrary process hermeticity.
     */
    static String stamp(TaskManifest.Task task, Path root, List<String> command,
            Map<String, String> environment, Map<String, String> prerequisites) throws IOException {
        MessageDigest digest = sha256();
        update(digest, DOMAIN);
        update(digest, root.toString());
        update(digest, task.name());
        update(digest, task.tool());
        update(digest, task.workingDir());
        strings(digest, "argv", command);
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
                "java.version", "java.vendor", "file.encoding", "user.language", "user.country")) {
            update(digest, property);
            update(digest, System.getProperty(property, ""));
        }
        engine(digest);
        Path tool = Path.of(command.get(0));
        if (!tool.isAbsolute()) tool = root.resolve(task.workingDir()).resolve(tool);
        // Installed tool launchers commonly use symlinks. Bind the actual target and bytes.
        tool = tool.toRealPath();
        update(digest, "executable");
        update(digest, tool.toString());
        file(digest, tool, new Budget());
        configuration(digest, root);
        update(digest, paths(task.inputs(), root));
        return hex(digest);
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
        return overlaps(path, ".m3/module.m3db") || overlaps(path, ".m3/module.m3db.lock");
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

    /** Hash the complete owning JARs or package trees, including anonymous/nested helpers. */
    private static void engine(MessageDigest digest) throws IOException {
        Map<String, Path> sources = new TreeMap<>();
        for (Class<?> owner : List.of(InputStamps.class, jdk.m3.indexdb.M3IndexDB.class)) {
            var source = owner.getProtectionDomain().getCodeSource();
            if (source == null) throw new IOException("missing task engine code source");
            final Path location;
            try {
                location = Path.of(source.getLocation().toURI()).toRealPath();
            } catch (URISyntaxException | IllegalArgumentException invalid) {
                throw new IOException("unsupported task engine code source", invalid);
            }
            if (Files.isRegularFile(location, LinkOption.NOFOLLOW_LINKS)) {
                sources.put(location.toString(), location);
            } else {
                Path packageRoot = location.resolve(owner.getPackageName().replace('.', '/'));
                sources.put(packageRoot.toString(), packageRoot);
            }
        }
        for (Map.Entry<String, Path> source : sources.entrySet()) {
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

    private static void configuration(MessageDigest digest, Path root) throws IOException {
        Budget budget = new Budget();
        Path directory = root;
        for (int depth = 0; directory != null; depth++, directory = directory.getParent()) {
            if (depth == 128) throw new IOException("Maven configuration ancestry budget exceeded");
            for (String name : CONFIGURATION) {
                Path configuration = checked(directory, name);
                update(digest, configuration.toString());
                if (Files.notExists(configuration, LinkOption.NOFOLLOW_LINKS)) {
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

    private static final class Budget { private int paths; private long bytes; }
}
