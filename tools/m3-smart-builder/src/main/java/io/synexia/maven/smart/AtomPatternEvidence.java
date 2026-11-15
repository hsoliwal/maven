// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/** Structural consumer of canonical Java DAG receipts; does not execute or authenticate their proofs. */
final class AtomPatternEvidence {
    static final String VERSION = "# m3.atom-pattern.manifest.v1";
    static final String HEADER = "source_path\tinput_sha256\toutput_sha256\tcheckpoint_path\tcheckpoint_sha256";
    static final String CHECKPOINT_HEADER = "round\tphase\tsource_path\tbefore_sha256\tafter_sha256"
            + "\tcompiler_root\tcontract_root\tbehavior_root\tjit_root";
    private static final List<String> PHASES = List.of("INVENTORY", "FILE_ATOMIZATION",
            "FILE_PATTERNIZATION", "FILE_DOCUMENTATION");
    private static final int MAX_FILES = 262_144;
    private static final int MAX_DIRECTORIES = 262_144;
    private static final int MAX_MANIFEST_BYTES = 128 * 1024 * 1024;
    private static final int MAX_CHECKPOINT_BYTES = 64 * 1024;
    private static final long MAX_JAVA_BYTES = 16L * 1024 * 1024;
    private static final int MAX_PATH_CHARS = 4096;

    private AtomPatternEvidence() { }

    record Summary(String manifestSha256, String javaSourceSetSha256, String checkpointSetSha256,
                   int javaFiles, int nonJavaFiles, long javaBytes) {
        String json() {
            return "{\"schema\":\"m3.atom-pattern.manifest.v1\","
                    + "\"validation\":\"structural-source-and-checkpoint-binding\","
                    + "\"semanticProofAuthenticated\":false,"
                    + "\"scope\":\"explicit-java-source-root\","
                    + "\"manifestSha256\":" + TinyJson.quote(manifestSha256)
                    + ",\"javaSourceSetSha256\":" + TinyJson.quote(javaSourceSetSha256)
                    + ",\"checkpointSetSha256\":" + TinyJson.quote(checkpointSetSha256)
                    + ",\"javaFiles\":" + javaFiles + ",\"javaBytes\":" + javaBytes
                    + ",\"checkpointRows\":" + ((long) javaFiles * 8)
                    + ",\"roundsPerFile\":2,\"nonJavaFiles\":" + nonJavaFiles
                    + ",\"nonJavaDisposition\":\"outside-java-dag\"}";
        }
    }

    static final class Rejected extends IOException {
        private static final long serialVersionUID = 1L;
        Rejected(String code) { super(code); }
    }

    static Summary inspect(Path project, Path sourceRoot, Path manifest) throws IOException {
        if (sourceRoot == null) throw reject("source-root-required");
        if (manifest == null) throw reject("manifest-required");
        Path base = project.toRealPath();
        Path lexicalBase = project.toAbsolutePath().normalize();
        Path root = within(base, lexicalBase, sourceRoot);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw reject("source-root-not-directory");
        Path manifestFile = within(base, lexicalBase, manifest);
        byte[] manifestBytes = readOrdinary(manifestFile, MAX_MANIFEST_BYTES);
        String[] lines = lines(manifestBytes);
        if (lines.length < 3 || !VERSION.equals(lines[0]) || !HEADER.equals(lines[1]))
            throw reject("manifest-schema");
        if (lines.length - 2 > MAX_FILES) throw reject("manifest-file-limit");

        Inventory inventory = inventory(base, root);
        if (inventory.javaFiles.isEmpty()) throw reject("empty-java-scope");
        if (lines.length - 2 != inventory.javaFiles.size()) throw reject("source-coverage");
        Set<String> checkpointPaths = new HashSet<>();
        MessageDigest sourceDigest = Hashes.digest();
        MessageDigest checkpointDigest = Hashes.digest();
        String previous = "";
        long javaBytes = 0;
        for (int i = 2; i < lines.length; i++) {
            String[] cells = lines[i].split("\t", -1);
            if (cells.length != 5) throw reject("manifest-columns");
            String source = canonicalPath(cells[0]);
            if (source.compareTo(previous) <= 0) throw reject("source-order-or-duplicate");
            previous = source;
            SourceEntry sourceFile = inventory.javaFiles.get(source);
            if (sourceFile == null) throw reject("source-coverage");
            String input = digest(cells[1]);
            String output = digest(cells[2]);
            String checkpoint = canonicalPath(cells[3]);
            String checkpointHash = digest(cells[4]);
            if (!checkpointPaths.add(checkpoint)) throw reject("duplicate-checkpoint-path");
            byte[] receipt = readOrdinary(relative(base, checkpoint), MAX_CHECKPOINT_BYTES);
            if (!Hashes.sha256(receipt).equals(checkpointHash)) throw reject("checkpoint-digest");
            validateCheckpoints(lines(receipt), source, input, output);
            FileHash current = hashSource(sourceFile.path);
            if (!current.sha256.equals(output)) throw reject("stale-source");
            javaBytes = Math.addExact(javaBytes, current.bytes);
            update(sourceDigest, source, current.sha256);
            update(checkpointDigest, source, checkpointHash);
        }
        Inventory after = inventory(base, root);
        if (!inventory.javaFiles.equals(after.javaFiles) || inventory.nonJavaFiles != after.nonJavaFiles)
            throw reject("source-set-changed-during-check");
        return new Summary(Hashes.sha256(manifestBytes), hex(sourceDigest), hex(checkpointDigest),
                inventory.javaFiles.size(), inventory.nonJavaFiles, javaBytes);
    }

    private record SourceEntry(Path path, long bytes, FileTime modified, Object fileKey) { }
    private record Inventory(TreeMap<String, SourceEntry> javaFiles, int nonJavaFiles) { }

    private static Inventory inventory(Path base, Path root) throws IOException {
        TreeMap<String, SourceEntry> javaFiles = new TreeMap<>();
        int[] counts = new int[3]; // directories, ordinary files, non-Java files
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes)
                    throws IOException {
                if (Files.isSymbolicLink(dir) || !attributes.isDirectory()) throw reject("source-link");
                if (++counts[0] > MAX_DIRECTORIES) throw reject("source-directory-limit");
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(file)) throw reject("source-link");
                if (!attributes.isRegularFile()) throw reject("source-not-ordinary");
                if (++counts[1] > MAX_FILES) throw reject("source-file-limit");
                if (file.getFileName().toString().endsWith(".java")) {
                    if (attributes.size() > MAX_JAVA_BYTES) throw reject("java-size-limit");
                    String name = canonicalPath(base.relativize(file).toString().replace('\\', '/'));
                    if (javaFiles.putIfAbsent(name, new SourceEntry(file, attributes.size(),
                            attributes.lastModifiedTime(), attributes.fileKey())) != null) throw reject("duplicate-source");
                } else counts[2]++;
                return FileVisitResult.CONTINUE;
            }
        });
        return new Inventory(javaFiles, counts[2]);
    }

    private static void validateCheckpoints(String[] rows, String source, String input, String output)
            throws IOException {
        if (rows.length != 9 || !CHECKPOINT_HEADER.equals(rows[0])) throw reject("checkpoint-schema-or-coverage");
        String expected = input;
        for (int row = 1; row <= 8; row++) {
            String[] cells = rows[row].split("\t", -1);
            if (cells.length != 9) throw reject("checkpoint-columns");
            int round = (row - 1) / 4 + 1;
            if (!Integer.toString(round).equals(cells[0]) || !PHASES.get((row - 1) % 4).equals(cells[1]))
                throw reject("checkpoint-stage-order");
            if (!source.equals(cells[2])) throw reject("checkpoint-source");
            String before = digest(cells[3]);
            String after = digest(cells[4]);
            if (!expected.equals(before)) throw reject("checkpoint-chain");
            if (cells[1].equals("INVENTORY") && !before.equals(after)) throw reject("checkpoint-inventory-mutated");
            if (round == 2 && !before.equals(after)) throw reject("checkpoint-not-fixed-point");
            for (int field = 5; field < 9; field++) digest(cells[field]);
            expected = after;
        }
        if (!expected.equals(output)) throw reject("checkpoint-output");
    }

    private record FileHash(String sha256, long bytes) { }

    private static FileHash hashSource(Path path) throws IOException {
        BasicFileAttributes before = attributes(path);
        if (before.size() > MAX_JAVA_BYTES) throw reject("java-size-limit");
        MessageDigest result = Hashes.digest();
        long total = 0;
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_JAVA_BYTES) throw reject("java-size-limit");
                result.update(buffer, 0, read);
            }
        }
        BasicFileAttributes after = attributes(path);
        if (total != before.size() || total != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !java.util.Objects.equals(before.fileKey(), after.fileKey())) throw reject("source-changed-during-check");
        return new FileHash(hex(result), total);
    }

    private static byte[] readOrdinary(Path path, int limit) throws IOException {
        BasicFileAttributes before = attributes(path);
        if (before.size() == 0 || before.size() > limit) throw reject("receipt-size-limit");
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(limit + 1);
        }
        BasicFileAttributes after = attributes(path);
        if (bytes.length > limit) throw reject("receipt-size-limit");
        if (bytes.length != before.size() || bytes.length != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !java.util.Objects.equals(before.fileKey(), after.fileKey())) throw reject("receipt-changed-during-check");
        return bytes;
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        BasicFileAttributes result = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!result.isRegularFile() || result.isSymbolicLink()) throw reject("receipt-or-source-not-ordinary");
        return result;
    }

    private static String[] lines(byte[] bytes) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (text.indexOf('\r') >= 0 || text.indexOf('\0') >= 0) throw reject("receipt-line-format");
        if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
        return text.split("\n", -1);
    }

    private static Path within(Path realBase, Path lexicalBase, Path supplied) throws IOException {
        Path normalized = lexicalBase.resolve(supplied).toAbsolutePath().normalize();
        if (!normalized.startsWith(lexicalBase)) throw reject("path-outside-project");
        String relative = lexicalBase.relativize(normalized).toString().replace('\\', '/');
        return relative.isEmpty() ? realBase : relative(realBase, canonicalPath(relative));
    }

    private static Path relative(Path base, String relative) throws IOException {
        Path current = base;
        for (String segment : relative.split("/")) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) throw reject("evidence-path-link");
        }
        if (!current.normalize().startsWith(base)) throw reject("path-outside-project");
        return current;
    }

    private static String canonicalPath(String path) throws IOException {
        if (path.isEmpty() || path.length() > MAX_PATH_CHARS || path.startsWith("/") || path.endsWith("/")
                || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0 || path.chars().anyMatch(Character::isISOControl))
            throw reject("noncanonical-path");
        for (String part : path.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw reject("noncanonical-path");
        return path;
    }

    private static String digest(String value) throws IOException {
        if (value.length() != 64) throw reject("digest-format");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) throw reject("digest-format");
        }
        return value;
    }

    private static void update(MessageDigest digest, String path, String value) {
        digest.update(path.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(value.getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) '\n');
    }

    private static String hex(MessageDigest digest) { return HexFormat.of().formatHex(digest.digest()); }
    private static Rejected reject(String reason) { return new Rejected(reason); }
}
