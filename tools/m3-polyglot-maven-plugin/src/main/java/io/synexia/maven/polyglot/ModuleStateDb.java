/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.ServiceConfigurationError;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * One persistent, module-local, content-verified build-state database.
 *
 * <p>"file" publishes a single atomic, sealed image; "m3db" publishes that same
 * image as one native MIndexDB artifact using its public artifact API. The latter
 * is an optional runtime integration, not a Maven-bootstrap dependency.
 *
 * <p>File stat reuse is permitted only with a stable file key and a POSIX change
 * timestamp (ctime). On other file systems, the contents are SHA-256 checked
 * every time. Size and mtime alone are never sufficient cache evidence.
 */
final class ModuleStateDb implements AutoCloseable {
    static final String FILE = ".m3/module-state-v1.bin";
    static final String NATIVE_DIRECTORY = ".m3/maven-build-db";
    private static final int MAGIC = 0x4d334253; // M3BS
    private static final int VERSION = 1;
    private static final int MAX_IMAGE = 32 * 1024 * 1024;
    private static final int MAX_ENTRIES = 250_000;
    // JGit-style racy timestamp defense: recently modified files are always rehashed.
    private static final long RACY_MILLIS = 3000L;
    private static final String ARTIFACT = "maven-module-build-state-v1";
    private static final String KIND = "maven-module-state";

    private record FileState(long size, long mtime, long ctime, String fileKey, String sha256) {}
    private record TaskState(String inputStamp, String outputStamp) {}
    private record Attributes(long size, long mtime, long ctime, String fileKey) {
        boolean reusable(FileState prior) {
            long safeBefore = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                    System.currentTimeMillis() - RACY_MILLIS);
            return prior != null && ctime >= 0 && ctime < safeBefore
                    && mtime < safeBefore && !fileKey.isEmpty()
                    && size == prior.size && mtime == prior.mtime
                    && ctime == prior.ctime && fileKey.equals(prior.fileKey);
        }
    }

    private interface Storage extends AutoCloseable {
        byte[] read() throws IOException;
        void write(byte[] bytes) throws IOException;
        @Override default void close() throws IOException { }
    }

    private final Path root;
    private final Storage storage;
    private final FileChannel leaseChannel;
    private final FileLock lease;
    private final Map<String, FileState> files = new TreeMap<>();
    private final Map<String, TaskState> tasks = new TreeMap<>();
    private int digestedFiles;
    private boolean dirty;

    private ModuleStateDb(Path root, Storage storage, FileChannel leaseChannel, FileLock lease) {
        this.root = root;
        this.storage = storage;
        this.leaseChannel = leaseChannel;
        this.lease = lease;
    }

    /**
     * Acquire a module-wide exclusive lease before reading a state snapshot.
     *
     * <p>This prevents two Maven processes from deciding to reuse or overwrite
     * the same module outputs concurrently, including when using native M3DB.
     */
    static ModuleStateDb open(Path workspace, String backend) throws IOException {
        Path root = workspace.toAbsolutePath().normalize();
        Path stateDirectory = root.resolve(".m3");
        if (Files.isSymbolicLink(stateDirectory) || Files.isSymbolicLink(root.resolve(NATIVE_DIRECTORY))) {
            throw new IOException("module state directory must not be a symlink");
        }
        Files.createDirectories(stateDirectory);
        Path lockPath = stateDirectory.resolve("module-state.lock");
        if (Files.isSymbolicLink(lockPath)) throw new IOException("module state lock must not be a symlink");
        FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException overlapping) {
            channel.close();
            throw new IOException("module state already owned by another Maven build: " + root, overlapping);
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
        if (lock == null) {
            channel.close();
            throw new IOException("module state already owned by another Maven build: " + root);
        }
        Storage storage = null;
        try {
            storage = switch (Objects.requireNonNull(backend, "backend")) {
                case "file" -> new FileStorage(root.resolve(FILE));
                case "m3db" -> new NativeStorage(root.resolve(NATIVE_DIRECTORY));
                case "sqlite", "h2", "derby" -> {
                    JdbcModuleStateStorage jdbc = new JdbcModuleStateStorage(root, backend);
                    yield new Storage() {
                        @Override public byte[] read() throws IOException { return jdbc.read(); }
                        @Override public void write(byte[] bytes) throws IOException { jdbc.write(bytes); }
                    };
                }
                default -> providerStore(root, backend);
            };
            ModuleStateDb database = new ModuleStateDb(root, storage, channel, lock);
            byte[] image = storage.read();
            if (image != null) {
                try {
                    database.decode(image);
                } catch (IOException | IllegalArgumentException corrupted) {
                    // An invalid sealed payload is never a cache hit. Storage failures
                    // are deliberately propagated instead of being hidden as misses.
                    database.files.clear();
                    database.tasks.clear();
                }
            }
            return database;
        } catch (IOException | RuntimeException | LinkageError failure) {
            if (storage != null) {
                try { storage.close(); }
                catch (IOException suppressed) { failure.addSuppressed(suppressed); }
            }
            try { lock.release(); }
            catch (IOException suppressed) { failure.addSuppressed(suppressed); }
            try { channel.close(); }
            catch (IOException suppressed) { failure.addSuppressed(suppressed); }
            throw failure;
        }
    }

    /** Discover an explicitly installed trusted backend for an alternate KV engine. */
    private static Storage providerStore(Path root, String backend) throws IOException {
        if (backend == null || !backend.matches("[a-z][a-z0-9-]{0,31}")) {
            throw new IllegalArgumentException("invalid module database backend id");
        }
        M3BuildStateStoreProvider chosen = null;
        java.util.Set<String> ids = new java.util.HashSet<>();
        try {
            for (M3BuildStateStoreProvider candidate : ServiceLoader.load(M3BuildStateStoreProvider.class)) {
                String name = candidate.id();
                if (name == null || !name.matches("[a-z][a-z0-9-]{0,31}")
                        || java.util.Set.of("legacy", "file", "m3db", "sqlite", "h2", "derby")
                                .contains(name) || !ids.add(name)) {
                    throw new IOException("invalid/duplicate custom module database backend id: " + name);
                }
                if (name.equals(backend)) chosen = candidate;
            }
        } catch (ServiceConfigurationError failure) {
            throw new IOException("cannot discover module database provider", failure);
        }
        if (chosen == null) {
            throw new IllegalArgumentException("unavailable module database backend: " + backend);
        }
        M3BuildStateStoreProvider.Store target = chosen.open(root);
        if (target == null) throw new IOException("module database provider returned null storage");
        return new Storage() {
            @Override public byte[] read() throws IOException { return target.read(); }
            @Override public void write(byte[] bytes) throws IOException { target.write(bytes); }
            @Override public void close() throws IOException { target.close(); }
        };
    }

    @Override
    public void close() throws IOException {
        Throwable failure = null;
        try { storage.close(); }
        catch (IOException | RuntimeException | Error error) { failure = error; }
        try { lease.release(); }
        catch (IOException | RuntimeException error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        try { leaseChannel.close(); }
        catch (IOException | RuntimeException error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        if (failure instanceof IOException error) throw error;
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }

    int digestedFiles() {
        return digestedFiles;
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
        update(digest, System.getProperty("file.encoding", ""));
        update(digest, System.getProperty("user.language", ""));
        update(digest, System.getProperty("user.timezone", ""));
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
        TaskState prior = tasks.get(task.name());
        if (prior == null || !prior.inputStamp.equals(current) || prior.outputStamp.isEmpty()) {
            return false;
        }
        String observedOutputs = outputStamp(task);
        return !observedOutputs.isEmpty() && prior.outputStamp.equals(observedOutputs);
    }

    /** Original immediate-publish contract (used by the single-task API and tests). */
    void record(TaskManifest.Task task, String current) throws IOException {
        recordPending(task, current);
        commit();
    }

    /** RocksDB WriteBatch-inspired staging: one module image publication per successful graph. */
    void recordPending(TaskManifest.Task task, String current) throws IOException {
        String outputs = outputStamp(task);
        if (outputs.isEmpty()) throw new IOException("cannot persist task without all declared outputs");
        tasks.put(task.name(), new TaskState(current, outputs));
        dirty = true;
    }

    void commit() throws IOException {
        if (!dirty) return;
        storage.write(encode());
        dirty = false;
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
            for (Path entry : walk.sorted().toList()) {
                if (entries.size() >= MAX_ENTRIES) throw new IOException("too many module inputs/outputs");
                entries.add(entry);
            }
            for (Path entry : entries) addEntry(digest, entry);
        }
    }

    private void addEntry(MessageDigest digest, Path path) throws IOException {
        Path relative = root.relativize(path);
        // The M3 module-state namespace is never its own build input.
        // External providers must keep their store under .m3/module-state-<id>.
        if (relative.getNameCount() >= 2 && ".m3".equals(relative.getName(0).toString())
                && relative.getName(1).toString().startsWith("module-state-")) return;
        if (relative.startsWith(".m3") && (path.equals(root.resolve(FILE))
                || path.equals(root.resolve(".m3/module-state.lock"))
                || path.equals(root.resolve(".m3/module-state.sqlite"))
                || path.equals(root.resolve(".m3/module-state.sqlite-journal"))
                || path.equals(root.resolve(".m3/module-state.sqlite-wal"))
                || path.equals(root.resolve(".m3/module-state.sqlite-shm"))
                || path.equals(root.resolve(".m3/module-state-h2.mv.db"))
                || path.equals(root.resolve(".m3/module-state-h2.lock.db"))
                || path.startsWith(root.resolve(".m3/module-state-derby"))
                || path.startsWith(root.resolve(NATIVE_DIRECTORY)))) return;
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
        FileState prior = files.get(identity);
        if (before.reusable(prior)) return prior.sha256;
        MessageDigest digest = sha256();
        byte[] buffer = new byte[65536];
        try (InputStream stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            for (int n; (n = stream.read(buffer)) != -1; ) if (n > 0) digest.update(buffer, 0, n);
        }
        Attributes after = attributes(path);
        if (!before.equals(after)) throw new IOException("module file changed during hashing: " + path);
        String hash = HexFormat.of().formatHex(digest.digest());
        files.put(identity, new FileState(after.size, after.mtime, after.ctime, after.fileKey, hash));
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

    private byte[] encode() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(buffer)) {
            data.writeInt(MAGIC);
            data.writeInt(VERSION);
            data.writeInt(files.size());
            for (var entry : files.entrySet()) {
                data.writeUTF(entry.getKey());
                FileState f = entry.getValue();
                data.writeLong(f.size); data.writeLong(f.mtime); data.writeLong(f.ctime);
                data.writeUTF(f.fileKey); data.writeUTF(f.sha256);
            }
            data.writeInt(tasks.size());
            for (var entry : tasks.entrySet()) {
                data.writeUTF(entry.getKey());
                data.writeUTF(entry.getValue().inputStamp);
                data.writeUTF(entry.getValue().outputStamp);
            }
        }
        byte[] body = buffer.toByteArray();
        if (body.length + 32 > MAX_IMAGE) throw new IOException("module state image exceeds 32 MiB");
        byte[] result = Arrays.copyOf(body, body.length + 32);
        byte[] seal = sha256().digest(body);
        System.arraycopy(seal, 0, result, body.length, seal.length);
        return result;
    }

    private void decode(byte[] sealed) throws IOException {
        if (sealed.length < 48 || sealed.length > MAX_IMAGE) return;
        int end = sealed.length - 32;
        byte[] actual = sha256().digest(Arrays.copyOf(sealed, end));
        if (!MessageDigest.isEqual(actual, Arrays.copyOfRange(sealed, end, sealed.length))) return;
        Map<String, FileState> readFiles = new TreeMap<>();
        Map<String, TaskState> readTasks = new TreeMap<>();
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(sealed, 0, end))) {
            if (data.readInt() != MAGIC || data.readInt() != VERSION) return;
            int count = data.readInt();
            if (count < 0 || count > MAX_ENTRIES) return;
            for (int index = 0; index < count; index++) {
                String path = data.readUTF();
                FileState state = new FileState(data.readLong(), data.readLong(), data.readLong(),
                        data.readUTF(), data.readUTF());
                if (!state.sha256.matches("[0-9a-f]{64}") || readFiles.putIfAbsent(path, state) != null) return;
            }
            count = data.readInt();
            if (count < 0 || count > MAX_ENTRIES) return;
            for (int index = 0; index < count; index++) {
                String name = data.readUTF();
                TaskState state = new TaskState(data.readUTF(), data.readUTF());
                if (!state.inputStamp.matches("[0-9a-f]{64}")
                        || (!state.outputStamp.isEmpty() && !state.outputStamp.matches("[0-9a-f]{64}"))
                        || readTasks.putIfAbsent(name, state) != null) return;
            }
            if (data.available() != 0) return;
        }
        files.putAll(readFiles);
        tasks.putAll(readTasks);
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

    private static final class FileStorage implements Storage {
        private final Path file;
        FileStorage(Path file) { this.file = file; }
        @Override public byte[] read() throws IOException {
            if (Files.isSymbolicLink(file)) throw new IOException("module cache is a symlink");
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
            if (Files.size(file) > MAX_IMAGE) return null;
            return Files.readAllBytes(file);
        }
        @Override public void write(byte[] bytes) throws IOException {
            Path parent = file.getParent();
            if (Files.isSymbolicLink(parent) || Files.isSymbolicLink(file)) {
                throw new IOException("module cache image or directory is a symlink");
            }
            Files.createDirectories(parent);
            // The DB's outer lease covers the whole build, including this write.
            // Force the image before atomic publication (SQLite/LMDB-inspired
            // durable commit fence; no WAL or invasive Maven core mutation).
            Path tmp = Files.createTempFile(parent, "module-state-", ".tmp");
            try {
                try (FileChannel image = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                    ByteBuffer data = ByteBuffer.wrap(bytes);
                    while (data.hasRemaining()) image.write(data);
                    image.force(true);
                }
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    throw new IOException("module state needs atomic file replacement", unsupported);
                }
                // Directory fsync is not provided everywhere (notably Windows).
                try (FileChannel directory = FileChannel.open(parent, StandardOpenOption.READ)) {
                    directory.force(true);
                } catch (IOException | UnsupportedOperationException unsupported) {
                    // Still atomic at the filesystem rename boundary. Power-loss
                    // durability on filesystems without directory sync is weaker.
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
    }

    /** Adapt the canonical MIndexDB artifact API without making Maven depend on the SDK at bootstrap. */
    private static final class NativeStorage implements Storage {
        private final Path directory;
        private final Method open;
        private final Method read;
        private final Method write;
        NativeStorage(Path directory) throws IOException {
            this.directory = directory;
            try {
                Class<?> type = Class.forName("com.synexia.mindex.db.MIndexDB");
                open = type.getMethod("open", Path.class);
                read = type.getMethod("findArtifact", CharSequence.class);
                write = type.getMethod("putArtifact", CharSequence.class, CharSequence.class, int.class, byte[].class);
            } catch (ReflectiveOperationException missing) {
                throw new IOException("MIndexDB must be installed on the polyglot plugin classpath", missing);
            }
        }
        @Override public byte[] read() throws IOException {
            try (AutoCloseable database = (AutoCloseable) open.invoke(null, directory)) {
                Object value = read.invoke(database, ARTIFACT);
                if (!(value instanceof Optional<?> result) || result.isEmpty()) return null;
                return (byte[]) result.get().getClass().getMethod("payload").invoke(result.get());
            } catch (ReflectiveOperationException | ExceptionInInitializerError | RuntimeException error) {
                throw new IOException("cannot read MIndexDB module artifact", error);
            } catch (Exception error) {
                throw new IOException("cannot close MIndexDB module artifact", error);
            }
        }
        @Override public void write(byte[] bytes) throws IOException {
            try (AutoCloseable database = (AutoCloseable) open.invoke(null, directory)) {
                write.invoke(database, ARTIFACT, KIND, VERSION, bytes);
            } catch (ReflectiveOperationException | RuntimeException error) {
                throw new IOException("cannot publish MIndexDB module artifact", error);
            } catch (Exception error) {
                throw new IOException("cannot close MIndexDB module artifact", error);
            }
        }
    }
}
