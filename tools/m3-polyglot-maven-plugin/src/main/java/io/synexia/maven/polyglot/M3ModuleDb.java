/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One crash-safe, versioned M3DB receipt per Maven module, shared by its polyglot tasks.
 * The original build tool remains the producer; this records successful work only.
 * A database entry is not a license to skip arbitrary Maven plugins or M3 providers.
 */
final class M3ModuleDb {
    static final String DIRECTORY = ".m3";
    static final String FILENAME = "module.m3db";
    private static final byte[] MAGIC = "M3-MODULE-DB-V1\n".getBytes(StandardCharsets.US_ASCII);
    private static final int HASH_BYTES = 32;
    private static final int MAX_TASKS = 256;
    private static final int MAX_BYTES = 1_048_576;
    private static final short COMPLETE = 1;

    private record Entry(byte[] input, byte[] output) {}

    private M3ModuleDb() {}

    static Path file(Path root) {
        return root.resolve(DIRECTORY).resolve(FILENAME);
    }

    /** Hashes real input bytes, the complete task declaration and its declared output names. */
    static String fingerprint(TaskManifest.Task task, Path root) throws IOException {
        MessageDigest hash = sha256();
        frame(hash, "m3.module.inputs.v1");
        frame(hash, task.name());
        frame(hash, task.tool());
        frame(hash, task.workingDir());
        fields(hash, "arg", task.arguments());
        fields(hash, "dep", task.prerequisites());
        fields(hash, "output", task.outputs());
        paths(hash, root, "input", task.inputs());
        return HexFormat.of().formatHex(hash.digest());
    }

    /** A hit also requires the actual output tree to equal the successful producer's output tree. */
    static boolean upToDate(TaskManifest.Task task, Path root, String fingerprint) throws IOException {
        Entry saved = read(root).get(task.name());
        if (saved == null || !MessageDigest.isEqual(saved.input(), HexFormat.of().parseHex(fingerprint))) {
            return false;
        }
        return MessageDigest.isEqual(saved.output(), outputs(task, root));
    }

    /** Publish only after the original process succeeded and all declared outputs were found. */
    static void record(TaskManifest.Task task, Path root, String fingerprint) throws IOException {
        byte[] output = outputs(task, root);
        Path directory = root.resolve(DIRECTORY);
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("M3DB directory cannot be a symlink");
        }
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("M3DB directory changed into a symlink");
        }
        Path lock = directory.resolve(FILENAME + ".lock");
        // File locks coordinate separate Maven processes; the monitor also serializes threads in this JVM.
        synchronized (M3ModuleDb.class) {
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    FileLock held = channel.lock()) {
                if (!held.isValid()) throw new IOException("M3DB lock not acquired");
                Map<String, Entry> rows = read(root);
                rows.put(task.name(), new Entry(HexFormat.of().parseHex(fingerprint), output));
                if (rows.size() > MAX_TASKS) {
                    throw new IOException("M3DB module task budget exceeded");
                }
                byte[] bytes = encode(rows);
                Path staged = Files.createTempFile(directory, "module-", ".tmp");
                try {
                    Files.write(staged, bytes, StandardOpenOption.TRUNCATE_EXISTING);
                    try (FileChannel flushed = FileChannel.open(staged, StandardOpenOption.WRITE)) {
                        flushed.force(true);
                    }
                    try {
                        Files.move(staged, file(root), StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException unavailable) {
                        throw new IOException("M3DB requires atomic publication", unavailable);
                    }
                } finally {
                    Files.deleteIfExists(staged);
                }
            }
        }
    }

    /** Missing or corrupt state is a cache miss; never a reason to skip original work. */
    private static Map<String, Entry> read(Path root) throws IOException {
        Map<String, Entry> rows = new TreeMap<>();
        Path directory = root.resolve(DIRECTORY);
        Path database = file(root);
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(database)) {
            throw new IOException("M3DB path cannot be a symlink");
        }
        if (!Files.exists(database, LinkOption.NOFOLLOW_LINKS)) return rows;
        if (!Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)
                || Files.size(database) > MAX_BYTES) return rows;
        byte[] bytes;
        try (InputStream input = Files.newInputStream(database, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES || bytes.length < MAGIC.length + Integer.BYTES + HASH_BYTES) return rows;
        int payloadSize = bytes.length - HASH_BYTES;
        MessageDigest verifier = sha256();
        verifier.update(bytes, 0, payloadSize);
        if (!MessageDigest.isEqual(verifier.digest(), Arrays.copyOfRange(bytes, payloadSize, bytes.length))) {
            return rows;
        }
        ByteBuffer data = ByteBuffer.wrap(bytes, 0, payloadSize);
        byte[] magic = new byte[MAGIC.length];
        data.get(magic);
        if (!Arrays.equals(magic, MAGIC)) return rows;
        int count = data.getInt();
        if (count < 0 || count > MAX_TASKS) return new TreeMap<>();
        try {
            String previous = "";
            for (int index = 0; index < count; index++) {
                int length = Short.toUnsignedInt(data.getShort());
                if (length == 0 || length > 80 || data.remaining() < length + HASH_BYTES * 2 + 2) {
                    return new TreeMap<>();
                }
                byte[] name = new byte[length];
                data.get(name);
                String target = new String(name, StandardCharsets.US_ASCII);
                if (!target.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}") || previous.compareTo(target) >= 0) {
                    return new TreeMap<>();
                }
                byte[] input = new byte[HASH_BYTES];
                byte[] output = new byte[HASH_BYTES];
                data.get(input);
                data.get(output);
                if (data.getShort() != COMPLETE) return new TreeMap<>();
                rows.put(target, new Entry(input, output));
                previous = target;
            }
            if (data.hasRemaining()) return new TreeMap<>();
            return rows;
        } catch (java.nio.BufferUnderflowException invalid) {
            return new TreeMap<>();
        }
    }

    private static byte[] encode(Map<String, Entry> rows) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(body)) {
            out.write(MAGIC);
            out.writeInt(rows.size());
            for (Map.Entry<String, Entry> row : rows.entrySet()) {
                byte[] name = row.getKey().getBytes(StandardCharsets.US_ASCII);
                out.writeShort(name.length);
                out.write(name);
                out.write(row.getValue().input());
                out.write(row.getValue().output());
                out.writeShort(COMPLETE);
            }
        }
        byte[] payload = body.toByteArray();
        MessageDigest hash = sha256();
        hash.update(payload);
        body.write(hash.digest());
        if (body.size() > MAX_BYTES) throw new IOException("M3DB byte budget exceeded");
        return body.toByteArray();
    }

    private static byte[] outputs(TaskManifest.Task task, Path root) throws IOException {
        MessageDigest digest = sha256();
        frame(digest, "m3.module.outputs.v1");
        paths(digest, root, "output", task.outputs());
        return digest.digest();
    }

    private static void fields(MessageDigest digest, String role, List<String> values) {
        frame(digest, role);
        number(digest, values.size());
        for (String value : values) frame(digest, value);
    }

    /** Identical content with forged timestamps remains identical; same-sized changed bytes never do. */
    private static void paths(MessageDigest digest, Path root, String role, List<String> declared) throws IOException {
        List<String> sorted = new ArrayList<>(declared);
        sorted.sort(String::compareTo);
        frame(digest, role);
        number(digest, sorted.size());
        for (String name : sorted) {
            Path path = checked(root, name);
            if (path.startsWith(root.resolve(DIRECTORY))) {
                throw new IOException("M3DB cannot fingerprint its own state directory");
            }
            frame(digest, name);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("missing M3DB declared " + role + ": " + name);
            }
            if (Files.isSymbolicLink(path)) throw new IOException("M3DB input/output symlink: " + name);
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                List<Path> members;
                try (var walk = Files.walk(path)) {
                    members = walk.limit(262_145).sorted().toList();
                }
                if (members.size() > 262_144) throw new IOException("M3DB input/output tree budget exceeded");
                number(digest, members.size());
                for (Path member : members) {
                    if (Files.isSymbolicLink(member)) throw new IOException("M3DB nested symlink: " + member);
                    digestPath(digest, root, member);
                }
            } else {
                number(digest, 1);
                digestPath(digest, root, path);
            }
        }
    }

    private static void digestPath(MessageDigest digest, Path root, Path member) throws IOException {
        frame(digest, root.relativize(member).toString().replace('\\', '/'));
        if (Files.isDirectory(member, LinkOption.NOFOLLOW_LINKS)) {
            frame(digest, "directory");
        } else if (Files.isRegularFile(member, LinkOption.NOFOLLOW_LINKS)) {
            frame(digest, "file");
            long length = Files.size(member);
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(length).array());
            long read = 0;
            try (InputStream stream = Files.newInputStream(member, LinkOption.NOFOLLOW_LINKS)) {
                byte[] chunk = new byte[65536];
                for (int size; (size = stream.read(chunk)) != -1;) {
                    digest.update(chunk, 0, size);
                    read += size;
                }
            }
            if (read != length || Files.size(member) != length) {
                throw new IOException("M3DB input/output changed while hashing: " + member);
            }
        } else {
            throw new IOException("unsupported M3DB input/output type: " + member);
        }
    }

    private static Path checked(Path root, String relative) throws IOException {
        Path value = root.resolve(relative).normalize();
        if (!value.startsWith(root)) throw new IOException("M3DB path outside module: " + relative);
        Path cursor = root;
        for (Path part : root.relativize(value)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("M3DB path traverses symlink: " + relative);
        }
        return value;
    }

    private static void frame(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        number(digest, bytes.length);
        digest.update(bytes);
    }

    private static void number(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
