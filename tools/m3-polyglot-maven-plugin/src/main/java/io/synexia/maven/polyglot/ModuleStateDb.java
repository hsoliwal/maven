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
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
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
final class ModuleStateDb {
    static final String FILE = ".m3/module-state-v1.bin";
    static final String NATIVE_DIRECTORY = ".m3/maven-build-db";
    private static final int MAGIC = 0x4d334253; // M3BS
    private static final int VERSION = 1;
    private static final int MAX_IMAGE = 32 * 1024 * 1024;
    private static final int MAX_ENTRIES = 250_000;
    private static final String ARTIFACT = "maven-module-build-state-v1";
    private static final String KIND = "maven-module-state";

    private record FileState(long size, long mtime, long ctime, String fileKey, String sha256) {}
    private record TaskState(String inputStamp, String outputStamp) {}
    private record Attributes(long size, long mtime, long ctime, String fileKey) {
        boolean reusable(FileState prior) {
            return prior != null && ctime >= 0 && !fileKey.isEmpty()
                    && size == prior.size && mtime == prior.mtime
                    && ctime == prior.ctime && fileKey.equals(prior.fileKey);
        }
    }

    private interface Storage {
        byte[] read() throws IOException;
        void write(byte[] bytes) throws IOException;
    }

    private final Path root;
    private final Storage storage;
    private final Map<String, FileState> files = new TreeMap<>();
    private final Map<String, TaskState> tasks = new TreeMap<>();
    private int digestedFiles;

    private ModuleStateDb(Path root, Storage storage) {
        this.root = root;
        this.storage = storage;
    }

    static ModuleStateDb open(Path workspace, String backend) throws IOException {
        Path root = workspace.toAbsolutePath().normalize();
        Path stateDirectory = root.resolve(".m3");
        if (Files.isSymbolicLink(stateDirectory) || Files.isSymbolicLink(root.resolve(NATIVE_DIRECTORY))) {
            throw new IOException("module state directory must not be a symlink");
        }
        // Create the stable module-state parent before hashing an input directory
        // that includes the project root, avoiding a spurious second build.
        Files.createDirectories(stateDirectory);
        Storage storage = switch (Objects.requireNonNull(backend, "backend")) {
            case "file" -> new FileStorage(root.resolve(FILE));
            case "m3db" -> new NativeStorage(root.resolve(NATIVE_DIRECTORY));
            case "sqlite", "h2" -> {
                M3ModuleJdbcStorage jdbc = new M3ModuleJdbcStorage(root, backend);
                yield new Storage() {
                    @Override public byte[] read() throws IOException { return jdbc.read(); }
                    @Override public void write(byte[] bytes) throws IOException { jdbc.write(bytes); }
                };
            }
            default -> externalStorage(root, backend);
        };
        ModuleStateDb database = new ModuleStateDb(root, storage);
        try {
            byte[] image = storage.read();
            if (image != null) database.decode(image);
        } catch (IOException | IllegalArgumentException corrupt) {
            // Untrusted/malformed cache state is a cache miss, never build authority.
            database.files.clear();
            database.tasks.clear();
        }
        return database;
    }

    int digestedFiles() {
        return digestedFiles;
    }

    /**
     * Revoke a task and all transitive consumers before starting any changed tool.
     * Never permit a failed build to resurrect an earlier successful generation.
     */
    void invalidateTransitive(TaskManifest plan, String target) throws IOException {
        java.util.Set<String> invalid = new java.util.HashSet<>();
        invalid.add(target);
        boolean changed;
        do {
            changed = false;
            for (TaskManifest.Task task : plan.orderAll()) {
                if (!invalid.contains(task.name())
                        && task.prerequisites().stream().anyMatch(invalid::contains)) {
                    changed |= invalid.add(task.name());
                }
            }
        } while (changed);
        boolean removed = false;
        for (String name : invalid) removed |= tasks.remove(name) != null;
        if (removed) storage.write(encode());
    }

    /** External providers are explicit, trusted plugin dependencies, never silent fallbacks. */
    private static Storage externalStorage(Path root, String backend) throws IOException {
        if (!backend.matches("ext-[a-z0-9][a-z0-9-]{0,47}")) {
            throw new IllegalArgumentException("unknown module DB backend: " + backend);
        }
        M3ModuleDbBackendProvider selected = null;
        try {
            for (M3ModuleDbBackendProvider provider : ServiceLoader.load(M3ModuleDbBackendProvider.class)) {
                String id = provider.id();
                if (id == null || !id.matches("ext-[a-z0-9][a-z0-9-]{0,47}")) {
                    throw new IOException("invalid M3 portable DB provider identity");
                }
                if (backend.equals(id)) {
                    if (selected != null) throw new IOException("duplicate M3 portable DB provider: " + id);
                    selected = provider;
                }
            }
        } catch (ServiceConfigurationError malformed) {
            throw new IOException("M3 portable DB provider registration failed", malformed);
        }
        if (selected == null) throw new IOException("requested M3 portable DB provider is absent: " + backend);
        M3ModuleDbBackendProvider provider = selected;
        return new Storage() {
            @Override public byte[] read() throws IOException { return provider.read(root); }
            @Override public void write(byte[] image) throws IOException { provider.write(root, image); }
        };
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
        TaskState prior = tasks.get(task.name());
        return prior != null && !prior.outputStamp.isEmpty() && prior.inputStamp.equals(current)
                && prior.outputStamp.equals(outputStamp(task));
    }

    void record(TaskManifest.Task task, String current) throws IOException {
        String outputs = outputStamp(task);
        if (outputs.isEmpty()) throw new IOException("cannot record missing module outputs");
        tasks.put(task.name(), new TaskState(current, outputs));
        try {
            storage.write(encode());
        } catch (IOException failure) {
            tasks.remove(task.name());
            throw failure;
        }
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
        String name = relative.toString().replace('\\', '/');
        // A declared .m3 input cannot recursively fingerprint the cache's own
        // lock, database, SQLite journal or H2 lock/trace/temporary sidecars.
        if (relative.startsWith(".m3") && (path.equals(root.resolve(FILE))
                || path.startsWith(root.resolve(NATIVE_DIRECTORY))
                || name.startsWith(".m3/module-state."))) return;
        if (Files.isSymbolicLink(path)) throw new IOException("symlink in module tree: " + path);
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

    private void decode(byte[] image) throws IOException {
        if (image.length < 48 || image.length > MAX_IMAGE) return;
        int end = image.length - 32;
        byte[] actual = sha256().digest(Arrays.copyOf(image, end));
        if (!MessageDigest.isEqual(actual, Arrays.copyOfRange(image, end, image.length))) return;
        Map<String, FileState> readFiles = new TreeMap<>();
        Map<String, TaskState> readTasks = new TreeMap<>();
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(image, 0, end))) {
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
        // The FileLock is held for the try block only; javac -Xlint:try reports it as unused.
        @Override @SuppressWarnings("try") public void write(byte[] bytes) throws IOException {
            Path parent = file.getParent();
            if (Files.isSymbolicLink(parent)) throw new IOException("module cache directory is a symlink");
            Files.createDirectories(parent);
            Path lockPath = parent.resolve("module-state.lock");
            if (Files.isSymbolicLink(lockPath)) throw new IOException("module cache lock is a symlink");
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                Path tmp = Files.createTempFile(parent, "module-state-", ".tmp");
                try {
                    Files.write(tmp, bytes);
                    try {
                        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException unsupported) {
                        throw new IOException("module state needs atomic file replacement", unsupported);
                    }
                } finally {
                    Files.deleteIfExists(tmp);
                }
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
