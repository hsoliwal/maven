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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Shared module-local build-state facade for ordinary Maven and polyglot tasks.
 * All backends have one lifetime lease, bounded sealed images, durable
 * pre-execution revocation and generation-safe switching. The default embedded
 * backend executes the original M3IndexDB implementation without an SDK jar.
 *
 * <p>The retained package-private task helpers preserve the earlier ctime and
 * file-key optimization. They are filesystem-metadata hints, not a complete
 * input proof; the production task/lifecycle admission paths hash content and
 * pass independently verified input/output digests to this facade.
 */
public final class ModuleStateDb implements AutoCloseable {
    public static final String FILE = M3DbModuleStore.FILE_BACKEND;
    public static final String EMBEDDED_FILE = M3DbModuleStore.FILE;
    public static final String NATIVE_DIRECTORY = M3DbModuleStore.NATIVE_DIRECTORY;
    public static final String SQLITE_FILE = M3ModuleJdbcStorage.SQLITE_FILE;
    public static final String H2_FILE = M3ModuleJdbcStorage.H2_FILE;
    public static final String LOCK = M3DbModuleStore.LOCK;
    public static final String MARKER = M3DbModuleStore.MARKER;
    private static final int MAX_ENTRIES = 250_000;
    private static final int MAX_FINGERPRINT_BYTES = 32 * 1024 * 1024 - 128 * 1024;
    private static final int FILE_VERSION = 1;

    private record Attributes(long size, long mtime, long ctime, String fileKey) {}

    private final Path root;
    private final M3DbModuleStore store;
    private String[] fileNames = new String[16];
    private String[] fileKeys = new String[16];
    private String[] fileDigests = new String[16];
    private long[] fileSizes = new long[16];
    private long[] fileMtimes = new long[16];
    private long[] fileCtimes = new long[16];
    private int fileCount;
    private int digestedFiles;

    private ModuleStateDb(Path root, M3DbModuleStore store) {
        this.root = root;
        this.store = store;
    }

    public static ModuleStateDb open(Path root, String backend) throws IOException {
        return open(root, backend, true, ignored -> {});
    }

    public static ModuleStateDb open(Path root, String backend, Consumer<String> log) throws IOException {
        return open(root, backend, true, log);
    }

    public static ModuleStateDb open(Path root, boolean create) throws IOException {
        return open(root, "embedded", create, ignored -> {});
    }

    public static ModuleStateDb open(Path root, String backend, boolean create, Consumer<String> log)
            throws IOException {
        M3DbModuleStore store = M3DbModuleStore.open(root, backend, create, log);
        try {
            ModuleStateDb result = new ModuleStateDb(root.toRealPath(), store);
            try {
                result.decodeFiles(store.auxiliary());
            } catch (IOException | IllegalArgumentException corrupt) {
                store.invalidateAll();
                result.fileCount = 0;
                log.accept("M3DB cache miss: unsupported file fingerprint metadata");
            }
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            try { store.close(); } catch (IOException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    public static boolean statePath(String relative) {
        return M3DbModuleStore.statePath(Objects.requireNonNull(relative, "relative"));
    }

    /** Identify only temporary internal JVM lease markers, never ordinary user properties. */
    public static boolean isJvmLeaseProperty(String name) {
        return M3ModuleDbRunLock.isJvmLeaseProperty(name);
    }

    /** Revoke one namespace in the active backend without switching other owners. */
    public static void revokePrefix(Path root, String prefix, Consumer<String> log) throws IOException {
        M3DbModuleStore.revokePrefix(root, prefix, log);
    }

    public synchronized String[] lookup(String key) { return store.lookup(key); }

    public synchronized boolean hit(String key, String input, String output) {
        String[] value = lookup(key);
        return value != null && value[0].equals(input) && value[1].equals(output);
    }

    public synchronized String generation(String key) {
        String[] value = lookup(key);
        if (value == null) return null;
        MessageDigest digest = sha256();
        update(digest, "M3-MODULE-GENERATION-2");
        update(digest, key);
        update(digest, value[0]);
        update(digest, value[1]);
        return HexFormat.of().formatHex(digest.digest());
    }

    public synchronized void invalidate(String key) throws IOException { store.invalidate(key); }
    public synchronized void invalidate(Collection<String> keys) throws IOException { store.invalidate(keys); }
    public synchronized void invalidateAll() throws IOException { store.invalidateAll(); }
    public synchronized void invalidatePrefix(String prefix) throws IOException { store.invalidatePrefix(prefix); }

    /** Caller supplies content-validated evidence only after original execution succeeds. */
    public synchronized void record(String key, String input, String output) throws IOException {
        if (fileCount != 0) store.auxiliary(encodeFiles());
        store.record(key, input, output);
    }

    public synchronized void put(String key, String input, String output) throws IOException {
        record(key, input, output);
    }

    /** Stage one verified task success, without exposing it to a later process. */
    public synchronized void stage(String key, String input, String output) throws IOException {
        if (fileCount != 0) store.auxiliary(encodeFiles());
        store.stage(key, input, output);
    }

    /** Commit the successful module DAG once, or discard it by closing without commit. */
    public synchronized void commitStaged() throws IOException {
        store.commitStaged();
    }

    public synchronized int digestedFiles() { return digestedFiles; }

    @Override public synchronized void close() throws IOException { store.close(); }

    private static String taskKey(String name) {
        MessageDigest digest = sha256();
        update(digest, "M3-COMPATIBILITY-TASK-1");
        update(digest, name);
        return "compat-" + HexFormat.of().formatHex(digest.digest());
    }

    String stamp(TaskManifest.Task task) throws IOException {
        MessageDigest digest = sha256();
        update(digest, "M3-MODULE-BUILD-STATE-V1");
        update(digest, task.name());
        update(digest, task.tool());
        update(digest, task.workingDir());
        for (String value : task.arguments()) { update(digest, "arg"); update(digest, value); }
        for (String value : task.prerequisites()) { update(digest, "dep"); update(digest, value); }
        for (String value : task.outputs()) { update(digest, "output"); update(digest, value); }
        update(digest, System.getProperty("os.name", ""));
        update(digest, System.getProperty("os.arch", ""));
        update(digest, System.getProperty("java.runtime.version", ""));
        update(digest, System.getenv().getOrDefault("JAVA_HOME", ""));
        update(digest, System.getenv().getOrDefault("PATH", ""));

        // Include the executable's bytes, not merely its command-line spelling.
        String tool = TaskRunner.resolveTool(task.tool(), checked(task.workingDir()));
        Path executable = Path.of(tool);
        if (!executable.isAbsolute()) executable = checked(task.workingDir()).resolve(executable);
        executable = executable.toAbsolutePath().normalize();
        if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) {
            // Unresolvable executables must not be cached as if their toolchain were fixed.
            throw new IOException("cannot fingerprint incremental build tool: " + task.tool());
        }
        update(digest, "tool");
        update(digest, executable.toString());
        update(digest, shaFile(executable, "tool:" + executable));

        Path pom = root.resolve("pom.xml");
        if (Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS)) {
            update(digest, "pom.xml");
            update(digest, shaFile(pom, "pom.xml"));
        }
        Path manifest = root.resolve("m3-build.tsv");
        if (Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            update(digest, "m3-build.tsv");
            update(digest, shaFile(manifest, "m3-build.tsv"));
        }
        for (String input : task.inputs()) {
            update(digest, "input");
            update(digest, input);
            addPath(digest, checked(input), true);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    boolean upToDate(TaskManifest.Task task, String current) throws IOException {
        String[] prior = lookup(taskKey(task.name()));
        return prior != null && prior[0].equals(current)
                && prior[1].equals(outputStamp(task));
    }

    void record(TaskManifest.Task task, String current) throws IOException {
        String outputs = outputStamp(task);
        if (outputs.isEmpty()) throw new IOException("missing declared output from " + task.name());
        if (!current.equals(stamp(task))) throw new IOException("task inputs changed before publication");
        record(taskKey(task.name()), current, outputs);
    }

    /** Retained task-helper contract: revoke the producer and every transitive consumer atomically. */
    void invalidateTransitive(TaskManifest plan, String target) throws IOException {
        java.util.Set<String> invalid = new java.util.HashSet<>();
        invalid.add(target);
        boolean changed;
        do {
            changed = false;
            for (TaskManifest.Task task : plan.orderAll()) {
                if (!invalid.contains(task.name()) && task.prerequisites().stream().anyMatch(invalid::contains)) {
                    changed |= invalid.add(task.name());
                }
            }
        } while (changed);
        java.util.List<String> keys = new ArrayList<>();
        for (String name : invalid) keys.add(taskKey(name));
        invalidate(keys);
    }

    private String outputStamp(TaskManifest.Task task) throws IOException {
        MessageDigest digest = sha256();
        for (String output : task.outputs()) {
            update(digest, "output");
            update(digest, output);
            Path path = checked(output);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return "";
            addPath(digest, path, false);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private Path checked(String relative) throws IOException {
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root)) throw new IOException("module state path escapes project: " + relative);
        Path cursor = root;
        for (Path name : root.relativize(path)) {
            cursor = cursor.resolve(name);
            if (Files.isSymbolicLink(cursor)) throw new IOException("symlink in module input/output: " + relative);
        }
        return path;
    }

    private void addPath(MessageDigest digest, Path path, boolean required) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (required) throw new IOException("missing module input: " + path);
            update(digest, "missing");
            return;
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            addEntry(digest, path);
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            ArrayList<Path> entries = new ArrayList<>();
            var iterator = walk.iterator();
            while (iterator.hasNext()) {
                if (entries.size() >= MAX_ENTRIES) throw new IOException("too many module inputs/outputs");
                entries.add(iterator.next());
            }
            entries.sort(null);
            for (Path entry : entries) addEntry(digest, entry);
        }
    }

    private void addEntry(MessageDigest digest, Path path) throws IOException {
        Path relative = root.relativize(path);
        if (statePath(relative.toString().replace('\\', '/'))) return;
        if (Files.isSymbolicLink(path)) throw new IOException("symlink in module tree: " + path);
        String name = relative.toString().replace('\\', '/');
        update(digest, name);
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            update(digest, "directory");
        } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            update(digest, shaFile(path, name));
        } else {
            throw new IOException("non-ordinary module input/output: " + path);
        }
    }

    private String shaFile(Path path, String identity) throws IOException {
        Attributes before = attributes(path);
        int at = Arrays.binarySearch(fileNames, 0, fileCount, identity);
        if (at >= 0 && before.ctime >= 0 && !before.fileKey.isEmpty()
                && before.size == fileSizes[at] && before.mtime == fileMtimes[at]
                && before.ctime == fileCtimes[at] && before.fileKey.equals(fileKeys[at])) {
            return fileDigests[at];
        }
        MessageDigest digest = sha256();
        byte[] buffer = new byte[65536];
        try (InputStream stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            for (int n; (n = stream.read(buffer)) != -1; ) if (n > 0) digest.update(buffer, 0, n);
        }
        Attributes after = attributes(path);
        if (!before.equals(after)) throw new IOException("module file changed during hashing: " + path);
        String hash = HexFormat.of().formatHex(digest.digest());
        rememberFile(identity, after, hash);
        digestedFiles++;
        return hash;
    }

    private static Attributes attributes(Path path) throws IOException {
        BasicFileAttributes basic = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!basic.isRegularFile()) throw new IOException("not an ordinary module file: " + path);
        long ctime = -1;
        try {
            Object value = Files.getAttribute(path, "unix:ctime", LinkOption.NOFOLLOW_LINKS);
            if (value instanceof FileTime time) ctime = time.to(java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (UnsupportedOperationException ignored) {
            // Windows and other providers lacking ctime must rehash content.
        }
        return new Attributes(basic.size(), basic.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS),
                ctime, basic.fileKey() == null ? "" : basic.fileKey().toString());
    }

    private void rememberFile(String name, Attributes value, String digest) throws IOException {
        if (name.isEmpty() || name.length() > 8192) throw new IOException("module file identity exceeds limit");
        if (value.fileKey.length() > 4096) throw new IOException("module file key exceeds limit");
        int at = Arrays.binarySearch(fileNames, 0, fileCount, name);
        if (at < 0) {
            if (fileCount == MAX_ENTRIES) return; // optional metadata eviction is always a content rehash
            ensureCapacity(fileCount + 1);
            at = -at - 1;
            int remaining = fileCount - at;
            System.arraycopy(fileNames, at, fileNames, at + 1, remaining);
            System.arraycopy(fileKeys, at, fileKeys, at + 1, remaining);
            System.arraycopy(fileDigests, at, fileDigests, at + 1, remaining);
            System.arraycopy(fileSizes, at, fileSizes, at + 1, remaining);
            System.arraycopy(fileMtimes, at, fileMtimes, at + 1, remaining);
            System.arraycopy(fileCtimes, at, fileCtimes, at + 1, remaining);
            fileCount++;
        }
        fileNames[at] = name;
        fileKeys[at] = value.fileKey;
        fileDigests[at] = digest;
        fileSizes[at] = value.size;
        fileMtimes[at] = value.mtime;
        fileCtimes[at] = value.ctime;
    }

    private void ensureCapacity(int desired) {
        if (desired <= fileNames.length) return;
        int capacity = Math.min(MAX_ENTRIES, Math.max(desired, fileNames.length * 2));
        fileNames = Arrays.copyOf(fileNames, capacity);
        fileKeys = Arrays.copyOf(fileKeys, capacity);
        fileDigests = Arrays.copyOf(fileDigests, capacity);
        fileSizes = Arrays.copyOf(fileSizes, capacity);
        fileMtimes = Arrays.copyOf(fileMtimes, capacity);
        fileCtimes = Arrays.copyOf(fileCtimes, capacity);
    }

    private byte[] encodeFiles() throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(FILE_VERSION);
            out.writeInt(fileCount);
            for (int i = 0; i < fileCount; i++) {
                long maximumRow = 60L + 3L * (fileNames[i].length() + fileKeys[i].length());
                if (bytes.size() + maximumRow > MAX_FINGERPRINT_BYTES) {
                    throw new IOException("module fingerprint metadata exceeds limit");
                }
                out.writeUTF(fileNames[i]);
                out.writeUTF(fileKeys[i]);
                out.writeLong(fileSizes[i]);
                out.writeLong(fileMtimes[i]);
                out.writeLong(fileCtimes[i]);
                out.write(HexFormat.of().parseHex(fileDigests[i]));
            }
            out.flush();
            return bytes.toByteArray();
        }
    }

    private void decodeFiles(byte[] bytes) throws IOException {
        if (bytes.length == 0) return;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != FILE_VERSION) throw new IOException("unsupported fingerprint schema");
            int count = in.readInt();
            if (count < 0 || count > MAX_ENTRIES) throw new IOException("module fingerprint count exceeds limit");
            ensureCapacity(count);
            byte[] digest = new byte[32];
            for (int i = 0; i < count; i++) {
                String name = in.readUTF();
                if (name.isEmpty() || name.length() > 8192
                        || i > 0 && fileNames[i - 1].compareTo(name) >= 0) {
                    throw new IOException("noncanonical module file identity");
                }
                fileNames[i] = name;
                fileKeys[i] = in.readUTF();
                if (fileKeys[i].length() > 4096) throw new IOException("module file key exceeds limit");
                fileSizes[i] = in.readLong();
                fileMtimes[i] = in.readLong();
                fileCtimes[i] = in.readLong();
                if (fileSizes[i] < 0) throw new IOException("negative module file size");
                in.readFully(digest);
                fileDigests[i] = HexFormat.of().formatHex(digest);
            }
            if (in.read() != -1) throw new IOException("trailing file fingerprint data");
            fileCount = count;
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] text = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        digest.update((byte) (text.length >>> 24));
        digest.update((byte) (text.length >>> 16));
        digest.update((byte) (text.length >>> 8));
        digest.update((byte) text.length);
        digest.update(text);
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
