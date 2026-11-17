// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.module.ModuleReader;
import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-module syntax memoization inside the installed canonical MIndexDB provider.
 * The provider owns the native artifact format and artifact replacement.
 * Maven owns only this bounded, checksummed application record and its identity.
 * No database engine, compiler tree, attributed fact or successful-build head is copied here.
 */
final class ModuleFingerprintDatabase {
    private static final int MAGIC = 0x4d334d46;
    private static final int VERSION = 1;
    private static final int MAX_FILES = 2000;
    private static final int MAX_ROWS = 4096;
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_STRING_BYTES = 8192;
    private static final String KIND = "maven-syntax-fingerprints";
    private static final ReentrantLock[] WRITERS = writerStripes();

    private final String mode;
    private final Path base;
    private final Path source;
    private final Path database;
    private final String entry;
    private final String context;
    private final Rows staged = new Rows();
    private NativeAccess provider;
    private Rows previous = new Rows();
    private byte[] expected;
    private String status;
    private String reason = "";
    private int hits;
    private int misses;

    private ModuleFingerprintDatabase(Path base, Path source, String mode, String context) {
        this.mode = mode;
        this.base = base;
        this.source = source;
        this.database = base.resolve(".m3/maven/mindexdb");
        this.entry = KIND + "-v1:" + Hashes.sha256(source.toUri().toString().getBytes(StandardCharsets.UTF_8));
        this.context = context;
        this.status = mode.equals("off") ? "off" : "unavailable";
    }

    static ModuleFingerprintDatabase open(Path base, Path source, String mode) throws IOException {
        return open(base, source, mode, null);
    }

    /** The explicit identity seam lets contract tests vary producer generations without a fake database. */
    static ModuleFingerprintDatabase open(Path base, Path source, String mode, String producerIdentity)
            throws IOException {
        if (!Set.of("auto", "off", "required").contains(mode))
            throw new IOException("module database mode must be auto, off or required");
        Path canonicalBase = base.toRealPath();
        Path canonicalSource = source.toRealPath();
        if (!canonicalSource.startsWith(canonicalBase) || canonicalSource.equals(canonicalBase))
            throw new IOException("module database requires a module-owned source root");
        NativeAccess nativeAccess = null;
        if (!mode.equals("off")) {
            try { nativeAccess = NativeAccess.discover(); }
            catch (IOException unavailable) {
                if (mode.equals("required") || Thread.currentThread().isInterrupted()) throw unavailable;
            }
        }
        String identity = "";
        if (nativeAccess != null) {
            try {
                identity = producerIdentity == null ? RuntimeIdentity.value() : producerIdentity;
            } catch (IOException failure) {
                if (mode.equals("required") || Thread.currentThread().isInterrupted()) throw failure;
                nativeAccess = null;
            }
        }
        String context = Hashes.sha256(("maven.module.fingerprints.v1\0" + canonicalBase.toUri()
                + '\0' + canonicalSource.toUri() + '\0' + identity).getBytes(StandardCharsets.UTF_8));
        ModuleFingerprintDatabase cache = new ModuleFingerprintDatabase(canonicalBase, canonicalSource, mode, context);
        if (mode.equals("off")) return cache;
        if (nativeAccess == null) {
            cache.reason = "provider-or-identity-unavailable";
            if (mode.equals("required"))
                throw new IOException("module database requires the installed com.synexia.mindex.db.MIndexDB provider");
            return cache;
        }
        cache.provider = nativeAccess;
        cache.status = "ready";
        try {
            cache.checkPaths(false);
            if (Files.exists(cache.database, LinkOption.NOFOLLOW_LINKS)) {
                cache.readPrevious();
            }
        } catch (IOException failure) {
            cache.bypass("read-refused", failure);
        }
        return cache;
    }

    Optional<List<String>> find(SourceInventory.FileFact file) {
        int index = Arrays.binarySearch(previous.paths, 0, previous.files, file.path());
        if (provider != null && index >= 0 && previous.hashes[index].equals(file.sha256())
                && previous.sizes[index] == file.bytes()) {
            hits++;
            return Optional.of(List.copyOf(Arrays.asList(previous.rows)
                    .subList(previous.offsets[index], previous.offsets[index + 1])));
        }
        misses++;
        return Optional.empty();
    }

    void stage(SourceInventory.FileFact file, List<String> rows) throws IOException {
        if (provider != null) {
            try { staged.add(file, rows); }
            catch (IOException invalid) { bypass("entry-refused", invalid); }
        }
    }

    /** Called only after source/evidence revalidation and successful final output admission. */
    void commit() throws IOException {
        if (provider == null) return;
        checkInterrupted();
        byte[] encoded;
        try { encoded = encode(staged, context); }
        catch (IOException invalid) { bypass("entry-refused", invalid); return; }
        // A fully warm generation never rewrites its native artifact.
        if (Arrays.equals(encoded, expected)) {
            status = "unchanged";
            return;
        }
        // On POSIX, closing a competing channel can release another channel's process-wide lock.
        // Avoid creating competing channels for this cache writer inside the plugin realm.
        ReentrantLock local = WRITERS[(database.hashCode() & Integer.MAX_VALUE) % WRITERS.length];
        if (!local.tryLock()) {
            bypass("writer-busy", new IOException("module database writer is busy in this JVM"));
            return;
        }
        try {
            checkInterrupted();
            checkPaths(true);
            Path lock = database.resolveSibling("mindexdb.lock");
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    FileLock held = tryLock(channel)) {
                if (!held.isValid()) throw new IOException("module database writer lock was lost");
                checkPaths(false);
                try (NativeHandle fs = provider.open(database)) {
                    byte[] current = fs.read(entry);
                    if (Arrays.equals(current, encoded)) {
                        status = "unchanged";
                        return;
                    }
                    if (!Arrays.equals(current, expected)) {
                        status = "superseded";
                        reason = "concurrent-update";
                        return;
                    }
                    fs.replace(entry, encoded);
                    status = "stored";
                }
            }
        } catch (OverlappingFileLockException busy) {
            bypass("writer-busy", new IOException("module database writer is busy", busy));
        } catch (IOException failure) {
            bypass("write-refused", failure);
        } finally {
            local.unlock();
        }
    }

    String json() {
        return "{\"mode\":" + TinyJson.quote(mode) + ",\"status\":" + TinyJson.quote(status)
                + ",\"reason\":" + TinyJson.quote(reason) + ",\"hits\":" + hits
                + ",\"misses\":" + misses + ",\"filesComputed\":" + misses
                + ",\"scope\":\"syntax-fingerprints\",\"provider\":\"MIndexDB\",\"path\":\".m3/maven/mindexdb\"}";
    }

    /** Cooperating readers and writers share the lock even on filesystems without atomic moves. */
    private void readPrevious() throws IOException {
        ReentrantLock local = WRITERS[(database.hashCode() & Integer.MAX_VALUE) % WRITERS.length];
        if (!local.tryLock()) throw new IOException("module database is busy in this JVM");
        try {
            Path lock = database.resolveSibling("mindexdb.lock");
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    FileLock held = tryLock(channel)) {
                if (!held.isValid()) throw new IOException("module database reader lock was lost");
                checkPaths(false);
                try (NativeHandle fs = provider.open(database)) {
                    expected = fs.read(entry);
                    if (expected != null) previous = decode(expected, context);
                }
            }
        } catch (OverlappingFileLockException busy) {
            throw new IOException("module database is busy", busy);
        } finally {
            local.unlock();
        }
    }

    private void bypass(String why, IOException failure) throws IOException {
        if (mode.equals("required") || Thread.currentThread().isInterrupted()) throw failure;
        status = "bypassed";
        reason = why;
        provider = null;
        previous = new Rows();
    }

    private void checkPaths(boolean create) throws IOException {
        Path directory = database;
        if (directory.startsWith(source) || source.startsWith(directory))
            throw new IOException("module database overlaps selected sources");
        Path current = base;
        for (Path segment : base.relativize(directory)) {
            current = current.resolve(segment);
            if (create && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                try { Files.createDirectory(current); }
                catch (FileAlreadyExistsException raced) { /* Validate the winning path below. */ }
            }
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("module database parent is a link or non-directory");
        }
        Path lock = database.resolveSibling("mindexdb.lock");
        if (Files.exists(lock, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("module database lock is a link or non-file");
        if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            int count = 0;
            try (var files = Files.newDirectoryStream(directory)) {
                for (Path file : files) {
                    if (++count > MAX_FILES || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                            || Files.size(file) > MAX_BYTES + 8192L)
                        throw new IOException("module database artifact is linked, oversized or outside its budget");
                }
            } catch (DirectoryIteratorException failed) {
                throw failed.getCause();
            }
        }
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        FileLock lock = channel.tryLock();
        if (lock == null) throw new IOException("module database writer is busy");
        return lock;
    }

    private static ReentrantLock[] writerStripes() {
        ReentrantLock[] locks = new ReentrantLock[64];
        Arrays.setAll(locks, unused -> new ReentrantLock());
        return locks;
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("module database interrupted");
    }

    /** Parallel parent-owned lanes; no retained map entry or compiler tree per source/atom. */
    private static final class Rows {
        final String[] paths = new String[MAX_FILES];
        final String[] hashes = new String[MAX_FILES];
        final int[] sizes = new int[MAX_FILES];
        final int[] offsets = new int[MAX_FILES + 1];
        final String[] rows = new String[MAX_ROWS];
        int files;
        int atoms;

        void add(SourceInventory.FileFact file, List<String> values) throws IOException {
            if (files == MAX_FILES || values.isEmpty() || values.size() > MAX_ROWS - atoms
                    || file.bytes() < 0 || file.bytes() > 1_048_576
                    || !file.sha256().matches("[0-9a-f]{64}")
                    || (files > 0 && paths[files - 1].compareTo(file.path()) >= 0))
                throw new IOException("module fingerprint cache geometry or ordering");
            String prior = "";
            for (String row : values) {
                if (row.length() > MAX_STRING_BYTES || row.indexOf('\n') >= 0 || row.indexOf('\r') >= 0
                        || row.indexOf('\0') >= 0 || row.compareTo(prior) <= 0)
                    throw new IOException("module fingerprint cache row ordering or encoding");
                String[] fields = row.split("\t", -1);
                if (fields.length != 9 || !fields[0].equals(file.path()) || !fields[1].equals(file.sha256())
                        || !fields[2].equals(MachineAtomInventory.atomId(atoms - offsets[files]))
                        || !fields[3].matches("[0-9a-f]{64}") || !fields[4].matches("[0-9a-f]{64}")
                        || !fields[5].matches("[0-9a-f]{16}") || !fields[6].matches("[0-9a-f]{64}")
                        || !fields[7].matches("00[0-9a-f]{2}") || !fields[8].matches("00[01][0-9a-f]"))
                    throw new IOException("module fingerprint cache source binding or schema");
                rows[atoms++] = row;
                prior = row;
            }
            paths[files] = file.path();
            hashes[files] = file.sha256();
            sizes[files] = file.bytes();
            offsets[++files] = atoms;
        }
    }

    private static byte[] encode(Rows rows, String context) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            string(output, context);
            output.writeInt(rows.files);
            for (int i = 0; i < rows.files; i++) {
                string(output, rows.paths[i]);
                string(output, rows.hashes[i]);
                output.writeInt(rows.sizes[i]);
                output.writeInt(rows.offsets[i + 1] - rows.offsets[i]);
                for (int j = rows.offsets[i]; j < rows.offsets[i + 1]; j++) string(output, rows.rows[j]);
            }
        }
        byte[] payload = bytes.toByteArray();
        if (payload.length > MAX_BYTES - 32) throw new IOException("module fingerprint cache byte budget");
        bytes.write(Hashes.digest().digest(payload));
        return bytes.toByteArray();
    }

    private static Rows decode(byte[] encoded, String context) throws IOException {
        if (encoded.length < 44 || encoded.length > MAX_BYTES)
            throw new IOException("module fingerprint cache byte budget");
        int length = encoded.length - 32;
        MessageDigest digest = Hashes.digest();
        digest.update(encoded, 0, length);
        if (!MessageDigest.isEqual(digest.digest(), Arrays.copyOfRange(encoded, length, encoded.length)))
            throw new IOException("module fingerprint cache checksum mismatch");
        Rows rows = new Rows();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded, 0, length))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION)
                throw new IOException("module fingerprint cache schema mismatch");
            String recordedContext = string(input);
            int files = input.readInt();
            if (files < 0 || files > MAX_FILES) throw new IOException("module fingerprint cache file budget");
            for (int i = 0; i < files; i++) {
                String path = string(input);
                String hash = string(input);
                int size = input.readInt();
                int count = input.readInt();
                if (count <= 0 || count > MAX_ROWS - rows.atoms)
                    throw new IOException("module fingerprint cache atom budget");
                String[] values = new String[count];
                for (int j = 0; j < count; j++) values[j] = string(input);
                rows.add(new SourceInventory.FileFact(path, hash, size), List.of(values));
            }
            if (input.read() != -1) throw new IOException("module fingerprint cache trailing bytes");
            return context.equals(recordedContext) ? rows : new Rows();
        }
    }

    private static void string(DataOutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) throw new IOException("module fingerprint cache string budget");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String string(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES || length > input.available())
            throw new IOException("module fingerprint cache string budget");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(input.readNBytes(length)))
                .toString();
    }

    /** Resolve the installed provider from the plugin realm; never download or copy an engine. */
    private static final class NativeAccess {
        private final Class<?> type;

        private NativeAccess(Class<?> type) { this.type = type; }

        static NativeAccess discover() throws IOException {
            checkInterrupted();
            try {
                Class<?> type = Class.forName("com.synexia.mindex.db.MIndexDB", false,
                        ModuleFingerprintDatabase.class.getClassLoader());
                type.getMethod("open", Path.class);
                type.getMethod("nativeStorage");
                type.getMethod("findArtifact", CharSequence.class);
                type.getMethod("putArtifact", CharSequence.class, CharSequence.class, int.class, byte[].class);
                type.getMethod("close");
                return new NativeAccess(type);
            } catch (ClassNotFoundException absent) {
                return null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError incompatible) {
                throw new IOException("MIndexDB native artifact API is unavailable", incompatible);
            }
        }

        NativeHandle open(Path database) throws IOException {
            Object opened = call(null, "open", new Class<?>[] {Path.class}, database);
            NativeHandle handle = new NativeHandle(this, opened);
            try {
                if (!Boolean.TRUE.equals(call(opened, "nativeStorage", new Class<?>[0])))
                    throw new IOException("MIndexDB did not open its native artifact engine");
                return handle;
            } catch (IOException | RuntimeException | Error failure) {
                try { handle.close(); }
                catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        Object call(Object target, String method, Class<?>[] types, Object... args) throws IOException {
            checkInterrupted();
            return cleanupCall(target, method, types, args);
        }

        /** Provider cleanup must run with the caller's interrupt flag still set. */
        Object cleanupCall(Object target, String method, Class<?>[] types, Object... args) throws IOException {
            try {
                return type.getMethod(method, types).invoke(target, args);
            } catch (InvocationTargetException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof IOException io) throw io;
                if (cause instanceof LinkageError incompatible)
                    throw new IOException("MIndexDB runtime unavailable: " + method, incompatible);
                if (cause instanceof Error error) throw error;
                throw new IOException("MIndexDB operation failed: " + method, cause);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError incompatible) {
                throw new IOException("MIndexDB API unavailable: " + method, incompatible);
            }
        }
    }

    private static final class NativeHandle implements AutoCloseable {
        private final NativeAccess api;
        private final Object database;

        NativeHandle(NativeAccess api, Object database) {
            this.api = api;
            this.database = Objects.requireNonNull(database);
        }

        byte[] read(String name) throws IOException {
            Object result = api.call(database, "findArtifact", new Class<?>[] {CharSequence.class}, name);
            if (!(result instanceof Optional<?> found)) throw new IOException("MIndexDB artifact result type");
            if (found.isEmpty()) return null;
            Object artifact = found.orElseThrow();
            try {
                Class<?> type = artifact.getClass();
                if (!name.equals(type.getMethod("name").invoke(artifact))
                        || !KIND.equals(type.getMethod("kind").invoke(artifact))
                        || !Integer.valueOf(VERSION).equals(type.getMethod("formatVersion").invoke(artifact)))
                    throw new IOException("module fingerprint artifact metadata mismatch");
                long size = (Long) type.getMethod("payloadLength").invoke(artifact);
                if (size < 0 || size > MAX_BYTES) throw new IOException("module fingerprint artifact byte budget");
                Object value = type.getMethod("payload").invoke(artifact);
                if (!(value instanceof byte[] bytes) || bytes.length != size)
                    throw new IOException("module fingerprint artifact payload mismatch");
                return bytes;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError incompatible) {
                throw new IOException("MIndexDB artifact API unavailable", incompatible);
            }
        }

        void replace(String name, byte[] encoded) throws IOException {
            // Cancellation is checked before entering the provider. Once entered, replacement and
            // temporary-file cleanup belong to MIndexDB; this adapter does not promise rollback.
            api.call(database, "putArtifact", new Class<?>[] {
                    CharSequence.class, CharSequence.class, int.class, byte[].class}, name, KIND, VERSION, encoded);
        }

        @Override public void close() throws IOException {
            api.cleanupCall(database, "close", new Class<?>[0]);
        }
    }

    /** Exact producer bytecode and original compiler resources, hashed once per loaded plugin/JVM. */
    private static final class RuntimeIdentity {
        private static String identity;

        static synchronized String value() throws IOException {
            if (identity != null) return identity;
            MessageDigest digest = Hashes.digest();
            feed(digest, "UTF-8|-proc:none|-source=21|" + Runtime.version() + "|"
                    + System.getProperty("java.vendor") + "|" + System.getProperty("java.vm.version"));
            try {
                var location = MachineFingerprintMain.class.getProtectionDomain().getCodeSource();
                if (location == null) throw new IOException("producer code source unavailable");
                Path code = Path.of(location.getLocation().toURI());
                if (Files.isRegularFile(code, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.size(code) > 64L * 1024 * 1024) throw new IOException("producer artifact byte budget");
                    feed(digest, Hashes.sha256(code));
                } else {
                    Path classes = code.resolve("io/synexia/maven/smart");
                    try (var walk = Files.walk(classes)) {
                        List<Path> files = walk.filter(p -> p.toString().endsWith(".class")).limit(4097).sorted().toList();
                        if (files.isEmpty() || files.size() > 4096) throw new IOException("producer class census");
                        for (Path file : files) {
                            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                                throw new IOException("producer class is not a regular file");
                            feed(digest, classes.relativize(file).toString().replace('\\', '/'));
                            feed(digest, Hashes.sha256(file));
                        }
                    }
                }
            } catch (URISyntaxException | RuntimeException failure) {
                throw new IOException("producer identity unavailable", failure);
            }
            var compiler = ModuleLayer.boot().configuration().findModule("jdk.compiler")
                    .orElseThrow(() -> new IOException("compiler module unavailable"));
            try (ModuleReader reader = compiler.reference().open(); var resources = reader.list()) {
                List<String> names = resources.limit(16_385).sorted().toList();
                if (names.size() > 16_384) throw new IOException("compiler resource census budget");
                byte[] buffer = new byte[65_536];
                long total = 0;
                for (String name : names) {
                    Optional<InputStream> found = reader.open(name);
                    if (found.isEmpty()) continue;
                    feed(digest, name);
                    MessageDigest resource = Hashes.digest();
                    try (InputStream stream = found.orElseThrow()) {
                        for (int n; (n = stream.read(buffer)) != -1; ) {
                            checkInterrupted();
                            if (n == 0 || (total += n) > 128L * 1024 * 1024)
                                throw new IOException("compiler resource byte budget");
                            resource.update(buffer, 0, n);
                        }
                    }
                    digest.update(resource.digest());
                }
            }
            identity = java.util.HexFormat.of().formatHex(digest.digest());
            return identity;
        }

        private static void feed(MessageDigest digest, String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
            digest.update(bytes);
        }
    }
}
