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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The single module-state publication owner. Every backend is one implementation of
 * {@link ModuleStateBackend}: the embedded (legacy) and file images and the m3db directory store
 * through {@link IndexStore} (M3IndexDB); optional JDBC and explicit SPI backends are external
 * engines. All share this owner's lease, receipt schema, backend generation and
 * incomplete-session recovery protocol.
 *
 * <p>A forced dirty marker precedes execution; only a healthy close marks the
 * session clean. An interrupted session or backend switch starts a new
 * generation, making old successes unusable. State images contain only bounded,
 * caller-validated digests, never serialized executable objects.
 */
final class M3DbModuleStore implements AutoCloseable {
    static final String FILE = ".m3/module.m3db";
    static final String FILE_BACKEND = ".m3/module-state-v1.bin";
    static final String NATIVE_DIRECTORY = ".m3/maven-build-db";
    static final String LOCK = M3ModuleDbRunLock.FILE;
    static final String MARKER = ".m3/module.m3db.state";
    private static final String MARKER_MAGIC = "M3-MODULE-STATE-2";
    static final int VERSION = 2;
    private static final int MAGIC = 0x4d334d44; // M3MD
    private static final int MAX_TASKS = 256;
    static final int MAX_PAYLOAD = 32 * 1024 * 1024;
    static final int MAX_ARCHIVE = MAX_PAYLOAD + 128 * 1024;
    static final int MAX_REF = 4096;

    static class BackendUnavailableException extends IOException {
        private static final long serialVersionUID = 1L;
        BackendUnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    /** One verified task receipt: task name, input generation and output tree digests. */
    record Receipt(String task, String input, String output) {}

    /** The receipts of a module's active backend; {@code backend} is empty when no clean session exists. */
    record Snapshot(String backend, List<Receipt> receipts) {}

    private final Path root;
    private final StateFiles files;
    private final Path markerPath;
    private final M3ModuleDbRunLock lease;
    private final boolean recordSuccess;
    private final Consumer<String> log;
    private final String backend;
    private final ModuleStateBackend storage;
    private final String[] names = new String[MAX_TASKS];
    private final String[] inputs = new String[MAX_TASKS];
    private final String[] outputs = new String[MAX_TASKS];
    private String generation;
    private byte[] auxiliary = new byte[0];
    private int size;
    private boolean hasState;
    private boolean needsReset;
    private boolean healthy = true;
    private boolean closed;

    private M3DbModuleStore(Path root, String backend, M3ModuleDbRunLock lease,
            boolean recordSuccess, Consumer<String> log) throws IOException {
        this(root, backend, lease, recordSuccess, log, true);
    }

    private M3DbModuleStore(Path root, String backend, M3ModuleDbRunLock lease,
            boolean recordSuccess, Consumer<String> log, boolean initializeStorage) throws IOException {
        this.root = root;
        this.files = new StateFiles(root, LOCK, MARKER);
        this.markerPath = root.resolve(MARKER);
        this.lease = lease;
        this.recordSuccess = recordSuccess;
        this.log = log;
        this.backend = backend;
        this.storage = initializeStorage ? ModuleStateBackend.of(backend, root, files) : null;
    }

    static M3DbModuleStore open(Path root, boolean create, Consumer<String> log) throws IOException {
        return open(root, "embedded", create, log);
    }

    /** The lease covers reads, caller execution and publication, including disabled runs. */
    static M3DbModuleStore open(Path moduleRoot, String requestedBackend, boolean create,
            Consumer<String> log) throws IOException {
        Objects.requireNonNull(moduleRoot, "moduleRoot");
        Objects.requireNonNull(log, "log");
        String backend = backend(requestedBackend);
        M3ModuleDbRunLock lease = M3ModuleDbRunLock.acquire(moduleRoot);
        Path root = lease.root();
        try {
            M3DbModuleStore store = new M3DbModuleStore(root, backend, lease, create, log);
            store.selectGeneration();
            store.read();
            // Do not mark clean if an exception prevents returning the acquired store.
            store.writeMarker(false);
            return store;
        } catch (IOException | RuntimeException | Error failure) {
            try { lease.close(); } catch (IOException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    /** Selective revocation reads the active backend only while holding its common lease. */
    static void revokePrefix(Path moduleRoot, String prefix, Consumer<String> log) throws IOException {
        Objects.requireNonNull(log, "log");
        taskName(prefix);
        M3ModuleDbRunLock lease = M3ModuleDbRunLock.acquire(Objects.requireNonNull(moduleRoot, "moduleRoot"));
        Path root = lease.root();
        M3DbModuleStore store = null;
        Throwable failure = null;
        try {
            List<String> marker = readMarker(root.resolve(MARKER));
            if (marker.isEmpty()) return; // no valid generation can authorize a prior image
            String active = marker.get(1);
            try {
                store = new M3DbModuleStore(root, active, lease, false, log);
            } catch (BackendUnavailableException unavailable) {
                // No optional image is read or silently replaced. Rotate its evidence
                // generation so a later provider cannot revive success before this run.
                store = new M3DbModuleStore(root, active, lease, false, log, false);
                store.generation = newGeneration();
                log.accept("M3DB " + (active.equals("m3db") ? "native" : active)
                        + " provider unavailable during revocation; previous generation revoked");
                return;
            }
            store.selectGeneration();
            store.read();
            store.writeMarker(false);
            store.invalidatePrefix(prefix);
        } catch (IOException | RuntimeException | Error thrown) {
            failure = thrown;
            if (store != null) store.healthy = false;
            throw thrown;
        } finally {
            if (store != null) {
                try { store.close(); }
                catch (IOException | RuntimeException | Error close) {
                    if (failure != null) failure.addSuppressed(close);
                    else throw close;
                }
            } else {
                try { lease.close(); }
                catch (IOException | RuntimeException | Error closeFailure) {
                    if (failure != null) failure.addSuppressed(closeFailure);
                    else throw closeFailure;
                }
            }
        }
    }

    /**
     * Reads the receipts of the active backend of a clean session under the common lease, for a project
     * database to ingest. No state is published, revoked or rotated and no marker is written; the lease may
     * create its own lock and self-ignore files, as for any session.
     */
    static Snapshot snapshot(Path moduleRoot) throws IOException {
        Path root = Objects.requireNonNull(moduleRoot, "moduleRoot").toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve(MARKER), LinkOption.NOFOLLOW_LINKS)) return new Snapshot("", List.of());
        M3ModuleDbRunLock lease = M3ModuleDbRunLock.acquire(root);
        try {
            return snapshot(lease);
        } finally {
            lease.close();
        }
    }

    private static Snapshot snapshot(M3ModuleDbRunLock lease) throws IOException {
        List<String> marker = readMarker(lease.root().resolve(MARKER));
        if (marker.isEmpty() || !"clean".equals(marker.get(3))) return new Snapshot("", List.of());
        M3DbModuleStore store = new M3DbModuleStore(lease.root(), marker.get(1), lease, false, ignored -> {});
        store.generation = marker.get(2);
        store.read();
        return new Snapshot(marker.get(1), store.receipts());
    }

    private List<Receipt> receipts() {
        List<Receipt> receipts = new ArrayList<>(size);
        for (int i = 0; i < size; i++) receipts.add(new Receipt(names[i], inputs[i], outputs[i]));
        return List.copyOf(receipts);
    }

    static String backend(String value) {
        return switch (Objects.requireNonNull(value, "backend")) {
            case "embedded", "legacy" -> "embedded";
            case "file" -> "file";
            case "m3db", "native" -> "m3db";
            case "sqlite", "h2" -> value;
            default -> {
                if (!externalBackend(value)) {
                    throw new IllegalArgumentException("state backend must be embedded, legacy, file, m3db, sqlite, h2 or ext-<provider>");
                }
                yield value;
            }
        };
    }

    static boolean externalBackend(String value) {
        return value != null && value.matches("ext-[a-z0-9][a-z0-9-]{0,47}");
    }

    static boolean statePath(String relative) {
        String name = relative.replace('\\', '/');
        return name.equals(FILE) || name.equals(LOCK) || name.equals(MARKER) || name.equals(FILE_BACKEND)
                || name.equals(StateFiles.IGNORE)
                || name.equals(".m3/module-state.lock") || name.equals(".m3/module.m3db.lock")
                || M3ModuleJdbcStorage.statePath(name) || name.equals(NATIVE_DIRECTORY)
                || name.startsWith(NATIVE_DIRECTORY + "/") || name.startsWith(".m3/module-state-");
    }

    synchronized String[] lookup(String task) {
        ensureOpen();
        int at = Arrays.binarySearch(names, 0, size, taskName(task));
        return at < 0 ? null : new String[] {inputs[at], outputs[at]};
    }

    synchronized void invalidate(String task) throws IOException {
        invalidate(List.of(taskName(task)));
    }

    /** One atomic replacement revokes the entire affected dependency set. */
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

    synchronized void invalidateAll() throws IOException {
        ensureOpen();
        if (size == 0 && !needsReset) return;
        clear();
        publish();
    }

    /** Revoke one owner namespace without discarding independently validated tasks. */
    synchronized void invalidatePrefix(String prefix) throws IOException {
        ensureOpen();
        taskName(prefix);
        boolean changed = needsReset;
        for (int i = size - 1; i >= 0; i--) {
            if (names[i].startsWith(prefix)) {
                remove(i);
                changed = true;
            }
        }
        if (changed) publish();
    }

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

    synchronized byte[] auxiliary() {
        ensureOpen();
        return auxiliary.clone();
    }

    synchronized void auxiliary(byte[] value) throws IOException {
        ensureOpen();
        if (value.length > MAX_PAYLOAD - 128 * 1024) throw new IOException("M3DB fingerprint data exceeds limit");
        auxiliary = value.clone();
    }

    private void selectGeneration() throws IOException {
        files.checkPaths();
        List<String> lines = readMarker(markerPath);
        if (!lines.isEmpty() && backend.equals(lines.get(1)) && "clean".equals(lines.get(3))) {
            generation = lines.get(2);
            return;
        }
        generation = newGeneration();
    }

    private static String newGeneration() { return UUID.randomUUID().toString().replace("-", ""); }

    private static List<String> readMarker(Path path) throws IOException {
        StateFiles.checkFile(path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 256) return List.of();
        List<String> lines;
        try { lines = Files.readAllLines(path, StandardCharsets.US_ASCII); }
        catch (java.nio.charset.MalformedInputException corrupt) { return List.of(); }
        if (lines.size() != 4 || !MARKER_MAGIC.equals(lines.get(0))
                || (!Set.of("embedded", "file", "m3db", "sqlite", "h2").contains(lines.get(1))
                        && !externalBackend(lines.get(1)))
                || !lines.get(2).matches("[0-9a-f]{32}")
                || !Set.of("clean", "dirty").contains(lines.get(3))) return List.of();
        return lines;
    }

    private void writeMarker(boolean clean) throws IOException {
        byte[] bytes = (MARKER_MAGIC + "\n" + backend + "\n" + generation + "\n"
                + (clean ? "clean\n" : "dirty\n")).getBytes(StandardCharsets.US_ASCII);
        files.atomicFile(markerPath, bytes);
    }

    private void read() throws IOException {
        try {
            byte[] bytes = storage.read();
            hasState = bytes != null;
            if (hasState) decode(bytes);
        } catch (IOException | IllegalArgumentException | java.nio.file.ProviderNotFoundException invalid) {
            clear();
            hasState = true;
            needsReset = true;
            log.accept("M3DB cache miss: invalid, interrupted or unsupported module state ("
                    + invalid.getClass().getSimpleName() + ")");
        }
    }

    private byte[] encode() throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeUTF(root.toString());
            out.writeUTF(generation);
            out.writeInt(size);
            for (int i = 0; i < size; i++) {
                out.writeUTF(names[i]);
                out.write(HexFormat.of().parseHex(inputs[i]));
                out.write(HexFormat.of().parseHex(outputs[i]));
            }
            out.writeInt(auxiliary.length);
            out.write(auxiliary);
            out.flush();
            if (bytes.size() > MAX_PAYLOAD - 32) throw new IOException("M3DB module receipt exceeds limit");
            byte[] body = bytes.toByteArray();
            out.write(sha256().digest(body));
            out.flush();
            return bytes.toByteArray();
        }
    }

    private void decode(byte[] bytes) throws IOException {
        if (bytes.length < 48 || bytes.length > MAX_PAYLOAD) throw new IOException("M3DB module receipt size");
        int end = bytes.length - 32;
        MessageDigest hash = sha256();
        hash.update(bytes, 0, end);
        if (!MessageDigest.isEqual(hash.digest(), Arrays.copyOfRange(bytes, end, bytes.length))) {
            throw new IOException("M3DB module receipt checksum");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, end))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION || !root.toString().equals(in.readUTF())
                    || !generation.equals(in.readUTF())) {
                throw new IOException("module receipt identity/version/generation mismatch");
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
            int length = in.readInt();
            if (length < 0 || length > MAX_PAYLOAD - 128 * 1024 || length > in.available()) {
                throw new IOException("M3DB fingerprint data size");
            }
            byte[] nextAuxiliary = new byte[length];
            in.readFully(nextAuxiliary);
            if (in.read() != -1) throw new IOException("trailing module receipt bytes");
            System.arraycopy(nextNames, 0, names, 0, count);
            System.arraycopy(nextInputs, 0, inputs, 0, count);
            System.arraycopy(nextOutputs, 0, outputs, 0, count);
            auxiliary = nextAuxiliary;
            size = count;
        }
    }

    private void publish() throws IOException {
        if (!recordSuccess && !hasState) return;
        try {
            files.checkPaths();
            storage.write(encode());
            hasState = true;
            needsReset = false;
        } catch (IOException | RuntimeException | Error failure) {
            healthy = false;
            throw failure;
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

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
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
        auxiliary = new byte[0];
    }

    private void ensureOpen() {
        if (closed || !lease.isValid()) throw new IllegalStateException("M3DB module store is closed");
        if (!healthy) throw new IllegalStateException("M3DB module publication failed; reopen required");
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        Throwable failure = null;
        if (healthy && !lease.isValid()) {
            failure = new IOException("M3DB module lease lost JVM coordination; state remains incomplete");
        } else if (healthy) {
            try { writeMarker(true); } catch (IOException | RuntimeException | Error mark) { failure = mark; }
        }
        try { lease.close(); } catch (IOException | RuntimeException | Error close) {
            if (failure == null) failure = close;
            else failure.addSuppressed(close);
        }
        if (failure instanceof IOException io) throw io;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }
}
