/*
 * SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jdk.m3.indexdb.M3IndexDB;
import jdk.m3.indexdb.M3IndexDbArtifact;

/**
 * One durable M3IndexDB image per Maven module. The original database owns its
 * content-addressed blobs and refs inside a ZIP filesystem; this adapter owns
 * the bounded Maven receipt schema and whole-file publication. A sibling lock
 * covers validation, execution and publication, including nonincremental runs.
 * Only successful, externally validated input/output digests belong here.
 */
final class M3DbModuleStore implements AutoCloseable {
    static final String FILE = ".m3/module.m3db";
    static final String LOCK = ".m3/module.m3db.lock";
    private static final String ARTIFACT = "successful-tasks";
    private static final String KIND = "maven.polyglot.module-state";
    private static final int VERSION = 1;
    private static final int MAGIC = 0x4d334d44; // M3MD
    private static final int MAX_TASKS = 256;
    private static final int MAX_PAYLOAD = 128 * 1024;
    private static final int MAX_ARCHIVE = 256 * 1024;
    private static final int MAX_REF = 4096;
    private static final int MAX_ROOT_CHARS = 8192;

    private final Path root;
    private final Path database;
    private final Path lockPath;
    private final FileChannel channel;
    private final FileLock lock;
    private final boolean recordSuccess;
    private final Consumer<String> log;
    private final String[] names = new String[MAX_TASKS];
    private final String[] inputs = new String[MAX_TASKS];
    private final String[] outputs = new String[MAX_TASKS];
    private int size;
    private boolean hasState;
    private boolean needsReset;
    private boolean closed;

    private M3DbModuleStore(Path root, FileChannel channel, FileLock lock,
            boolean recordSuccess, Consumer<String> log) {
        this.root = root;
        database = root.resolve(FILE);
        lockPath = root.resolve(LOCK);
        this.channel = channel;
        this.lock = lock;
        this.recordSuccess = recordSuccess;
        this.log = log;
    }

    /** Acquires a module lock. A disabled run never creates a database file. */
    static M3DbModuleStore open(Path moduleRoot, boolean create, Consumer<String> log) throws IOException {
        Objects.requireNonNull(moduleRoot, "moduleRoot");
        Objects.requireNonNull(log, "log");
        Path requested = moduleRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("M3DB module root must be a directory, not a symlink");
        }
        Path root = requested.toRealPath();
        if (root.toString().length() > MAX_ROOT_CHARS) throw new IOException("M3DB module identity exceeds limit");
        Path directory = root.resolve(".m3");
        checkDirectory(directory);
        Files.createDirectories(directory);
        checkDirectory(directory);
        Path lockPath = root.resolve(LOCK);
        checkFile(lockPath);
        FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException busy) {
                throw new IOException("M3DB module is already in use: " + root, busy);
            }
            if (lock == null) throw new IOException("M3DB module is already in use: " + root);
            M3DbModuleStore store = new M3DbModuleStore(root, channel, lock, create, log);
            try {
                store.read();
                return store;
            } catch (IOException | RuntimeException | Error failure) {
                try { store.close(); } catch (IOException close) { failure.addSuppressed(close); }
                throw failure;
            }
        } catch (IOException | RuntimeException | Error failure) {
            try { channel.close(); } catch (IOException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    /** Returns a defensive input/output digest pair, or null for a cache miss. */
    synchronized String[] lookup(String task) {
        ensureOpen();
        int at = Arrays.binarySearch(names, 0, size, taskName(task));
        return at < 0 ? null : new String[] {inputs[at], outputs[at]};
    }

    /** Durably revokes success before a task, or any prerequisite, mutates outputs. */
    synchronized void invalidate(String task) throws IOException {
        ensureOpen();
        int at = Arrays.binarySearch(names, 0, size, taskName(task));
        if (at >= 0) remove(at);
        if (at >= 0 || needsReset) publish();
    }

    /** Revokes a prerequisite's complete affected set in one atomic publication. */
    synchronized void invalidate(Collection<String> tasks) throws IOException {
        ensureOpen();
        Objects.requireNonNull(tasks, "tasks");
        for (String task : tasks) taskName(task);
        boolean changed = needsReset;
        for (String task : tasks) {
            int at = Arrays.binarySearch(names, 0, size, task);
            if (at >= 0) {
                remove(at);
                changed = true;
            }
        }
        if (changed) publish();
    }

    /** Revokes the module's prior successes before a nonincremental invocation. */
    synchronized void invalidateAll() throws IOException {
        ensureOpen();
        if (size == 0 && !needsReset) return;
        clear();
        publish();
    }

    /** Stores only caller-verified successful work; disabled runs retain no new success. */
    synchronized void record(String task, String input, String output) throws IOException {
        ensureOpen();
        taskName(task);
        digest(input);
        digest(output);
        if (!recordSuccess) return;
        int at = Arrays.binarySearch(names, 0, size, task);
        if (at < 0 && size == MAX_TASKS) {
            log.accept("M3DB receipt capacity reached; evicting " + names[0]);
            remove(0);
            at = Arrays.binarySearch(names, 0, size, task);
        }
        if (at < 0) {
            at = -at - 1;
            System.arraycopy(names, at, names, at + 1, size - at);
            System.arraycopy(inputs, at, inputs, at + 1, size - at);
            System.arraycopy(outputs, at, outputs, at + 1, size - at);
            size++;
        }
        names[at] = task;
        inputs[at] = input;
        outputs[at] = output;
        publish();
    }

    private void read() throws IOException {
        checkPaths();
        hasState = Files.exists(database, LinkOption.NOFOLLOW_LINKS);
        if (!hasState) return;
        try {
            validateArchive(database);
            try (FileSystem archive = FileSystems.newFileSystem(database, Map.of());
                    M3IndexDB db = M3IndexDB.open(archive.getPath("/"))) {
                M3IndexDbArtifact artifact = db.requireArtifact(ARTIFACT);
                if (!KIND.equals(artifact.kind()) || artifact.formatVersion() != VERSION) {
                    throw new IOException("unsupported module receipt schema");
                }
                decode(artifact.payload());
            }
        } catch (IOException | IllegalArgumentException | java.nio.file.ProviderNotFoundException invalid) {
            clear();
            needsReset = true;
            log.accept("M3DB cache miss: invalid or unsupported module state ("
                    + invalid.getClass().getSimpleName() + ")");
        }
    }

    private byte[] encode() throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeUTF(root.toString());
            out.writeInt(size);
            for (int i = 0; i < size; i++) {
                out.writeUTF(names[i]);
                out.write(HexFormat.of().parseHex(inputs[i]));
                out.write(HexFormat.of().parseHex(outputs[i]));
            }
            out.flush();
            if (bytes.size() > MAX_PAYLOAD) throw new IOException("M3DB module receipt exceeds limit");
            return bytes.toByteArray();
        }
    }

    private void decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_PAYLOAD) throw new IOException("M3DB module receipt exceeds limit");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION || !root.toString().equals(in.readUTF())) {
                throw new IOException("module receipt identity/version mismatch");
            }
            int count = in.readInt();
            if (count < 0 || count > MAX_TASKS) throw new IOException("M3DB task count exceeds limit");
            String[] nextNames = new String[count], nextInputs = new String[count], nextOutputs = new String[count];
            byte[] lane = new byte[32];
            for (int i = 0; i < count; i++) {
                String name = taskName(in.readUTF());
                if (i > 0 && nextNames[i - 1].compareTo(name) >= 0) {
                    throw new IOException("module task identities are not canonical");
                }
                nextNames[i] = name;
                in.readFully(lane);
                nextInputs[i] = HexFormat.of().formatHex(lane);
                in.readFully(lane);
                nextOutputs[i] = HexFormat.of().formatHex(lane);
            }
            if (in.read() != -1) throw new IOException("trailing module receipt bytes");
            System.arraycopy(nextNames, 0, names, 0, count);
            System.arraycopy(nextInputs, 0, inputs, 0, count);
            System.arraycopy(nextOutputs, 0, outputs, 0, count);
            size = count;
        }
    }

    /** Build a fresh original database image, close and force it, then publish atomically. */
    private void publish() throws IOException {
        if (!recordSuccess && !hasState) return;
        checkPaths();
        byte[] payload = encode();
        Path temporaryDirectory = Files.createTempDirectory(database.getParent(), "module-state-");
        Path temporary = temporaryDirectory.resolve("module.m3db");
        Throwable failure = null;
        try {
            try (FileSystem archive = FileSystems.newFileSystem(temporary, Map.of("create", "true"));
                    M3IndexDB db = M3IndexDB.open(archive.getPath("/"))) {
                db.putArtifact(ARTIFACT, KIND, VERSION, payload);
            }
            validateArchive(temporary);
            try (FileChannel file = FileChannel.open(temporary,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                file.force(true);
            }
            checkPaths();
            // No non-atomic fallback: a failed revocation must stop task execution.
            Files.move(temporary, database, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            hasState = true;
            needsReset = false;
        } catch (IOException | RuntimeException | Error thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            try {
                Files.deleteIfExists(temporary);
                Files.deleteIfExists(temporaryDirectory);
            } catch (IOException cleanup) {
                if (failure != null) failure.addSuppressed(cleanup);
                else throw cleanup;
            }
        }
    }

    /** Bound both compressed and expanded inputs before the original database reads them. */
    private static void validateArchive(Path file) throws IOException {
        if (Files.size(file) > MAX_ARCHIVE) throw new IOException("M3DB archive exceeds limit");
        Set<String> seen = new HashSet<>();
        int blobs = 0, refs = 0;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!seen.add(name) || seen.size() > 4) throw new IOException("M3DB archive entry count");
                int limit;
                if (name.equals("blobs/") || name.equals("refs/")) {
                    if (!entry.isDirectory()) throw new IOException("M3DB archive directory type");
                    limit = 0;
                } else if (name.matches("blobs/[0-9a-f]{64}\\.blob")) {
                    blobs++;
                    limit = MAX_PAYLOAD;
                } else if (name.matches("refs/[0-9a-f]{64}\\.ref")) {
                    refs++;
                    limit = MAX_REF;
                } else throw new IOException("unexpected M3DB archive entry");
                if (entry.getSize() < 0 || entry.getSize() > limit) throw new IOException("M3DB entry exceeds limit");
                try (InputStream in = zip.getInputStream(entry)) {
                    byte[] expanded = in.readNBytes(limit + 1);
                    if (expanded.length > limit || expanded.length != entry.getSize()) {
                        throw new IOException("M3DB expanded entry exceeds limit or disagrees with metadata");
                    }
                }
            }
        }
        if (blobs != 1 || refs != 1 || !seen.contains("blobs/") || !seen.contains("refs/")) {
            throw new IOException("M3DB module image must contain one artifact and both directories");
        }
    }

    private void checkPaths() throws IOException {
        checkDirectory(database.getParent());
        checkFile(database);
        checkFile(lockPath);
    }

    private static void checkDirectory(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("M3DB state directory is not a directory or is a symlink: " + path);
        }
    }

    private static void checkFile(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("M3DB state path is not a regular file or is a symlink: " + path);
        }
    }

    private static String taskName(String task) {
        if (task == null || !task.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}")) {
            throw new IllegalArgumentException("invalid module receipt task identity");
        }
        return task;
    }

    private static void digest(String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("module receipt digest must be lowercase SHA-256");
        }
    }

    private void remove(int at) {
        int remaining = size - at - 1;
        System.arraycopy(names, at + 1, names, at, remaining);
        System.arraycopy(inputs, at + 1, inputs, at, remaining);
        System.arraycopy(outputs, at + 1, outputs, at, remaining);
        size--;
        names[size] = null;
        inputs[size] = null;
        outputs[size] = null;
    }

    private void clear() {
        Arrays.fill(names, null);
        Arrays.fill(inputs, null);
        Arrays.fill(outputs, null);
        size = 0;
    }

    private void ensureOpen() {
        if (closed || !lock.isValid()) throw new IllegalStateException("M3DB module store is closed");
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        try { lock.release(); } catch (IOException release) { failure = release; }
        try { channel.close(); } catch (IOException close) {
            if (failure == null) failure = close;
            else failure.addSuppressed(close);
        }
        if (failure != null) throw failure;
    }
}
