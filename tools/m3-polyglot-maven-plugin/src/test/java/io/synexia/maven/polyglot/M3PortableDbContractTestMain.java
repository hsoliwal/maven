/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Offline JDK-only portability contract; JDBC runtime drivers require separate qualification. */
public final class M3PortableDbContractTestMain {
    private static int passed;
    private static final String INPUT = "a".repeat(64);
    private static final String OUTPUT = "b".repeat(64);

    private M3PortableDbContractTestMain() {}

    public static void main(String[] ignored) throws Exception {
        Path root = Files.createTempDirectory("m3-portable-db-");
        try {
            propertySerialization(root.resolve("properties"));
            lostPropertyOwnership(root.resolve("lost-properties"));
            Path first = Files.createDirectories(root.resolve("a"));
            Path second = Files.createDirectories(root.resolve("b"));
            try (M3ModuleDbRunLock lock = M3ModuleDbRunLock.acquire(first)) {
                check(lock != null, "module lock acquired");
                check(Files.isRegularFile(first.resolve(M3ModuleDbRunLock.FILE)), "module lock is local");
                expectFailure(() -> M3ModuleDbRunLock.acquire(first), "same process lock rejects overlap");
                try (M3ModuleDbRunLock independent = M3ModuleDbRunLock.acquire(second)) {
                    check(independent != null, "independent lock acquired");
                    check(Files.isRegularFile(second.resolve(M3ModuleDbRunLock.FILE)), "independent module lock works");
                }
            }
            try (M3ModuleDbRunLock released = M3ModuleDbRunLock.acquire(first)) {
                check(released != null, "module reacquired lock");
                check(true, "closed lock can be reacquired");
            }
            Path outside = Files.createDirectories(root.resolve("outside"));
            Path linked = root.resolve("linked");
            try {
                Files.createSymbolicLink(linked, outside);
                expectFailure(() -> M3ModuleDbRunLock.acquire(linked), "linked module root must fail");
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
                // Symlink setup is optional on Windows.
            }
            Path module = Files.createDirectories(root.resolve("symlink-state"));
            Path symlinkState = module.resolve(".m3");
            try {
                Files.createSymbolicLink(symlinkState, outside);
                expectFailure(() -> M3ModuleDbRunLock.acquire(module), "linked cache state must fail");
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
                // Symlink setup is optional on Windows.
            }
            check(!Files.exists(root.resolve("elsewhere"), LinkOption.NOFOLLOW_LINKS), "no out-of-scope writes");
            rejectsUnapprovedJdbcBackends(first);
            oneLeaseOwner(root.resolve("one-owner"));
            externalProviders(root.resolve("providers"));
            stagedTransactions(root.resolve("batch"));
            batchedRunner(root.resolve("runner-batch"));
            System.out.println("PASS: " + passed + " M3 portable module DB lease and SPI checks");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void propertySerialization(Path root) throws Exception {
        Files.createDirectories(root);
        Properties properties = System.getProperties();
        // Properties.store itself initializes the JDK's user.timezone property.
        // Capture the baseline after exercising that ordinary pre-lease call.
        serializesProperties(properties, "before lease");
        Properties before = new Properties();
        before.putAll(properties);
        try (ModuleStateDb state = ModuleStateDb.open(root, "embedded")) {
            state.record("task-properties", INPUT, OUTPUT);
            Set<String> added = leaseProperties(properties);
            added.removeAll(leaseProperties(before));
            check(added.size() == 1, "one string marker identifies the canonical module lease");
            String key = added.iterator().next();
            String token = properties.getProperty(key);
            check(token != null && !token.isEmpty(), "lease ownership token is a standard string property");
            serializesProperties(properties, "during lease");
            expectFailure(() -> ModuleStateDb.open(root, "file"), "duplicate owner remains refused with string markers");
            check(token.equals(properties.getProperty(key)), "duplicate refusal does not remove the owner's token");
            Path independent = Files.createDirectories(root.resolve("independent"));
            try (ModuleStateDb other = ModuleStateDb.open(independent, "file")) {
                check(other.lookup("task-properties") == null, "independent module remains concurrent");
                check(leaseProperties(properties).size() == leaseProperties(before).size() + 2,
                        "independent roots have separate string markers");
                serializesProperties(properties, "during two leases");
            }
            check(token.equals(properties.getProperty(key)), "closing another root retains this lease marker");
        }
        check(properties.equals(before), "closed lease leaves the original system properties unchanged");
        serializesProperties(properties, "after lease");
        expectFailure(() -> ModuleStateDb.open(root, "ext-unavailable-properties-probe"),
                "backend failure after acquiring lease remains explicit");
        check(properties.equals(before), "failed open removes only its temporary lease marker");
    }

    private static void lostPropertyOwnership(Path root) throws Exception {
        Files.createDirectories(root);
        Properties properties = System.getProperties();
        Set<String> before = leaseProperties(properties);
        try (ModuleStateDb state = ModuleStateDb.open(root, "embedded")) {
            state.record("task-properties", INPUT, OUTPUT);
            Set<String> added = leaseProperties(properties);
            added.removeAll(before);
            String key = added.iterator().next();
            properties.setProperty(key, "replacement-owner");
            try {
                expectStateFailure(() -> state.lookup("task-properties"), "lost token refuses cached lookup");
                expectStateFailure(() -> state.record("task-other", INPUT, OUTPUT), "lost token refuses publication");
                expectFailure(state::close, "lost token cannot mark a session clean");
                check("replacement-owner".equals(properties.getProperty(key)), "close cannot remove another ownership token");
                check(Files.readAllLines(root.resolve(ModuleStateDb.MARKER)).get(3).equals("dirty"),
                        "lost coordination leaves durable state dirty");
            } finally { properties.remove(key, "replacement-owner"); }
        }
        try (ModuleStateDb state = ModuleStateDb.open(root, "embedded")) {
            check(state.lookup("task-properties") == null, "lost ownership cannot revive the abandoned image");
            state.record("task-properties", INPUT, OUTPUT);
            Set<String> added = leaseProperties(properties);
            added.removeAll(before);
            String key = added.iterator().next();
            Properties replacement = new Properties();
            replacement.putAll(properties);
            try {
                System.setProperties(replacement);
                expectStateFailure(() -> state.lookup("task-properties"), "replaced system properties refuse reuse");
                expectStateFailure(() -> state.record("task-other", INPUT, OUTPUT), "replaced system properties refuse publication");
                expectFailure(state::close, "replaced system properties cannot mark a session clean");
                check(!properties.containsKey(key), "close cleans the captured property table");
                check(replacement.containsKey(key), "close does not mutate a replacement property table");
            } finally { System.setProperties(properties); }
        }
        try (ModuleStateDb state = ModuleStateDb.open(root, "embedded")) {
            check(state.lookup("task-properties") == null, "replaced property table cannot bless earlier work");
        }
        check(leaseProperties(properties).equals(before), "ownership failure paths leave no temporary marker");
    }

    private static Set<String> leaseProperties(Properties properties) {
        Set<String> result = new HashSet<>();
        for (String name : properties.stringPropertyNames()) {
            if (ModuleStateDb.isJvmLeaseProperty(name)) result.add(name);
        }
        return result;
    }

    private static void serializesProperties(Properties properties, String phase) throws IOException {
        StringWriter text = new StringWriter();
        properties.store(text, phase);
        Properties loaded = new Properties();
        loaded.load(new StringReader(text.toString()));
        check(loaded.equals(properties), "Properties.store/load preserves every string entry " + phase);
        ByteArrayOutputStream xml = new ByteArrayOutputStream();
        properties.storeToXML(xml, phase);
        loaded.clear();
        loaded.loadFromXML(new ByteArrayInputStream(xml.toByteArray()));
        // XML 1.0 parsers normalize CR LF to LF, so a CR-bearing value (line.separator on Windows)
        // never round-trips through storeToXML, lease or not. Every name and every other value must.
        boolean xmlPreserved = loaded.stringPropertyNames().equals(properties.stringPropertyNames());
        for (String name : properties.stringPropertyNames()) {
            String value = properties.getProperty(name);
            xmlPreserved &= value.indexOf(0x0D) >= 0 || value.equals(loaded.getProperty(name));
        }
        check(xmlPreserved, "Properties.storeToXML/loadFromXML preserves every entry " + phase);
        StringWriter listed = new StringWriter();
        properties.list(new PrintWriter(listed));
        check(!listed.toString().isEmpty(), "Properties.list remains compatible " + phase);
    }

    private static void oneLeaseOwner(Path root) throws Exception {
        Files.createDirectories(root);
        check(ModuleStateDb.LOCK.equals(M3ModuleDbRunLock.FILE), "facade and retained run lock share one stable path");
        try (M3ModuleDbRunLock held = M3ModuleDbRunLock.acquire(root)) {
            check(held != null, "retained whole-run lease acquired");
            expectFailure(() -> ModuleStateDb.open(root, "embedded"), "standalone run lock excludes facade owners");
        }
        try (ModuleStateDb state = ModuleStateDb.open(root, "file")) {
            check(state.lookup("task-alpha") == null, "new facade acquires released common lease");
            expectFailure(() -> M3ModuleDbRunLock.acquire(root), "facade excludes standalone whole-run lock");
        }
        check(!Files.exists(root.resolve(".m3/module.m3db.lock")), "no second lock protocol is created");
    }

    /** Public end-to-end runner: two deterministic tasks, exactly one DB publication. */
    private static void batchedRunner(Path root) throws Exception {
        Files.createDirectories(root);
        Path module = Files.createDirectories(root.resolve("project"));
        Files.writeString(module.resolve("in.txt"), "input");
        Files.writeString(module.resolve("a.out"), "first");
        Files.writeString(module.resolve("b.out"), "second");
        Path executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java");
        String script = TaskManifest.MAGIC + "\n"
                + "task\ta\t" + executable + "\t.\n"
                + "arg\ta\t-version\n"
                + "input\ta\tin.txt\n"
                + "output\ta\ta.out\n"
                + "task\tb\t" + executable + "\t.\n"
                + "arg\tb\t-version\n"
                + "dep\tb\ta\n"
                + "input\tb\ta.out\n"
                + "output\tb\tb.out\n";
        TaskManifest plan = TaskManifest.load(Files.writeString(module.resolve("m3-build.tsv"), script));
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader realm = providerRealm(root.resolve("service"), FixtureProvider.class.getName())) {
            Thread.currentThread().setContextClassLoader(realm);
            FixtureProvider.committedWrites = 0;
            List<String> log = new java.util.ArrayList<>();
            var first = TaskRunner.run(plan, module, "b", false, java.time.Duration.ofSeconds(20),
                    false, true, "ext-fixture-v1", log::add);
            check(first.equals(List.of("a", "b")), "selected two-task graph executes in order");
            check(FixtureProvider.committedWrites == 1,
                    "two executed tools publish one committed module snapshot");
            log.clear();
            TaskRunner.run(plan, module, "b", false, java.time.Duration.ofSeconds(20),
                    false, true, "ext-fixture-v1", log::add);
            check(log.stream().anyMatch(v -> v.startsWith("UP-TO-DATE a"))
                            && log.stream().anyMatch(v -> v.startsWith("UP-TO-DATE b")),
                    "second runner invocation reuses both verified task receipts");
            check(FixtureProvider.committedWrites == 1,
                    "all-cache-hit module run performs no backend image rewrite");
        } finally {
            Thread.currentThread().setContextClassLoader(original);
            FixtureProvider.committedWrites = 0;
        }
    }

    /** Every selected build publishes one complete sealed image, never partial receipts. */
    private static void stagedTransactions(Path root) throws Exception {
        Files.createDirectories(root);
        Path module = Files.createDirectories(root.resolve("module"));
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader realm = providerRealm(root.resolve("service"), FixtureProvider.class.getName())) {
            Thread.currentThread().setContextClassLoader(realm);
            FixtureProvider.committedWrites = 0;
            try (ModuleStateDb state = ModuleStateDb.open(module, "ext-fixture-v1")) {
                state.stage("task-alpha", INPUT, OUTPUT);
                state.stage("task-beta", OUTPUT, INPUT);
                check(state.lookup("task-alpha") == null, "staged receipt is not yet visible");
                check(FixtureProvider.committedWrites == 0, "staging does not rewrite database");
                // Simulate an unsuccessful graph: close without commit.
            }
            check(FixtureProvider.committedWrites == 0, "failed graph did not publish staged results");
            try (ModuleStateDb state = ModuleStateDb.open(module, "ext-fixture-v1")) {
                check(state.lookup("task-alpha") == null && state.lookup("task-beta") == null,
                        "uncommitted receipts did not survive restart");
                state.stage("task-alpha", INPUT, OUTPUT);
                state.stage("task-beta", OUTPUT, INPUT);
                state.commitStaged();
                check(FixtureProvider.committedWrites == 1, "two staged results make one durable DB write");
                check(state.hit("task-alpha", INPUT, OUTPUT) && state.hit("task-beta", OUTPUT, INPUT),
                        "committed staged results are visible");
            }
            try (ModuleStateDb state = ModuleStateDb.open(module, "ext-fixture-v1")) {
                check(state.hit("task-alpha", INPUT, OUTPUT) && state.hit("task-beta", OUTPUT, INPUT),
                        "batched module success survives reopen");
                state.stage("task-alpha", OUTPUT, INPUT);
                state.invalidate("task-alpha");
                state.commitStaged();
                check(state.lookup("task-alpha") == null,
                        "pre-execution revocation cannot resurrect an earlier staged task");
                check(state.hit("task-beta", OUTPUT, INPUT), "unaffected task remains committed");
                state.stage("task-late", INPUT, OUTPUT);
                state.invalidateAll();
                state.commitStaged();
                check(state.lookup("task-late") == null && state.lookup("task-beta") == null,
                        "all-module revocation discards pending and persisted entries");
            }
            try (ModuleStateDb state = ModuleStateDb.open(module, "ext-fixture-v1")) {
                check(state.lookup("task-alpha") == null && state.lookup("task-beta") == null,
                        "revocation persists after restart");
            }
        } finally {
            Thread.currentThread().setContextClassLoader(original);
            FixtureProvider.committedWrites = 0;
        }
    }

    private static void externalProviders(Path root) throws Exception {
        Files.createDirectories(root);
        Path module = Files.createDirectories(root.resolve("module"));
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader valid = providerRealm(root.resolve("valid-service"), FixtureProvider.class.getName())) {
            Thread.currentThread().setContextClassLoader(valid);
            try (ModuleStateDb state = ModuleStateDb.open(module, "ext-fixture-v1")) {
                check(state.lookup("task-alpha") == null, "explicit SPI provider starts cold");
                state.record("task-alpha", INPUT, OUTPUT);
                state.record("lifecycle-alpha", INPUT, OUTPUT);
                expectFailure(() -> M3ModuleDbRunLock.acquire(module), "SPI publication shares module run lease");
            }
            check(Files.isRegularFile(module.resolve(FixtureProvider.FILE)), "actual test provider committed sealed image");
            check(!Files.exists(module.resolve(ModuleStateDb.EMBEDDED_FILE)), "SPI never writes an embedded fallback");
            String marker = Files.readString(module.resolve(ModuleStateDb.MARKER));
            ModuleStateDb.revokePrefix(module, "lifecycle-", ignored -> {});
            check(marker.equals(Files.readString(module.resolve(ModuleStateDb.MARKER))), "SPI namespace revocation preserves active generation");
            try (ModuleStateDb state = ModuleStateDb.open(module, "ext-fixture-v1")) {
                check(state.hit("task-alpha", INPUT, OUTPUT), "SPI image survives close and reopen");
                check(state.lookup("lifecycle-alpha") == null, "SPI lifecycle namespace revoked independently");
            }
            byte[] untouched = Files.readAllBytes(module.resolve(FixtureProvider.FILE));
            Thread.currentThread().setContextClassLoader(original);
            ModuleStateDb.revokePrefix(module, "lifecycle-", ignored -> {});
            check(Arrays.equals(untouched, Files.readAllBytes(module.resolve(FixtureProvider.FILE))), "absent SPI revocation does not rewrite provider-owned image");
            check(Files.readAllLines(module.resolve(ModuleStateDb.MARKER)).get(1).equals("ext-fixture-v1"), "absent SPI revocation does not switch backend");
            Thread.currentThread().setContextClassLoader(valid);
            try (ModuleStateDb state = ModuleStateDb.open(module, "ext-fixture-v1")) {
                check(state.lookup("task-alpha") == null, "returning provider cannot revive revoked generation");
                state.record("task-alpha", INPUT, OUTPUT);
            }
            try (ModuleStateDb state = ModuleStateDb.open(module, "file")) {
                check(state.lookup("task-alpha") == null, "SPI-to-file selection starts cold");
                state.record("task-file", INPUT, OUTPUT);
            }
            try (ModuleStateDb state = ModuleStateDb.open(module, "ext-fixture-v1")) {
                check(state.lookup("task-alpha") == null, "file-to-SPI cannot revive previous image");
                state.record("task-alpha", INPUT, OUTPUT);
            }
            FixtureProvider.failWrites = true;
            try (ModuleStateDb state = ModuleStateDb.open(module, "ext-fixture-v1")) {
                expectFailure(() -> state.record("task-extra", INPUT, OUTPUT), "SPI commit failure remains explicit");
            } finally { FixtureProvider.failWrites = false; }
            try (ModuleStateDb state = ModuleStateDb.open(module, "ext-fixture-v1")) {
                check(state.lookup("task-alpha") == null, "SPI failure cannot resurrect old success");
                check(state.lookup("task-extra") == null, "SPI failure cannot authorize requested success");
            }
        } finally { Thread.currentThread().setContextClassLoader(original); }
        expectFailure(() -> ModuleStateDb.open(module, "ext-unavailable"), "absent explicit SPI provider is refused");
        try (URLClassLoader duplicates = providerRealm(root.resolve("duplicate-service"),
                FixtureProvider.class.getName(), DuplicateProvider.class.getName())) {
            Thread.currentThread().setContextClassLoader(duplicates);
            expectFailure(() -> ModuleStateDb.open(module, "ext-fixture-v1"), "duplicate explicit SPI identities are refused");
        } finally { Thread.currentThread().setContextClassLoader(original); }
        try (URLClassLoader invalid = providerRealm(root.resolve("invalid-service"), InvalidProvider.class.getName())) {
            Thread.currentThread().setContextClassLoader(invalid);
            expectFailure(() -> ModuleStateDb.open(module, "ext-fixture-v1"), "SPI cannot claim a reserved built-in identity");
        } finally { Thread.currentThread().setContextClassLoader(original); }
        for (String backend : List.of("ext-", "ext-UPPER", "ext-../escape", "ext-" + "x".repeat(49), "postgres")) {
            try {
                ModuleStateDb.open(module, backend);
                throw new AssertionError("unbounded or unknown backend accepted: " + backend);
            } catch (IllegalArgumentException expected) { check(true, "backend identity is bounded and explicit"); }
        }
    }

    private static URLClassLoader providerRealm(Path directory, String... implementations) throws IOException {
        Path services = directory.resolve("META-INF/services/" + M3ModuleDbBackendProvider.class.getName());
        Files.createDirectories(services.getParent());
        Files.writeString(services, String.join("\n", implementations) + "\n");
        return new URLClassLoader(new URL[] {directory.toUri().toURL()}, M3PortableDbContractTestMain.class.getClassLoader());
    }

    /** A real local test implementation of the public SPI; it impersonates no third-party engine. */
    public static class FixtureProvider implements M3ModuleDbBackendProvider {
        static final String FILE = ".m3/module-state-ext-fixture-v1.bin";
        static boolean failWrites;
        static int committedWrites;
        @Override public String id() { return "ext-fixture-v1"; }
        @Override public byte[] read(Path root) throws IOException {
            Path file = root.resolve(FILE);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("test provider state path");
            return Files.readAllBytes(file);
        }
        @Override public void write(Path root, byte[] image) throws IOException {
            if (failWrites) throw new IOException("explicit SPI publication-failure witness");
            Path file = root.resolve(FILE);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("test provider state path");
            }
            Path temporary = Files.createTempFile(file.getParent(), "module-state-ext-fixture-", ".tmp");
            try {
                try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    ByteBuffer buffer = ByteBuffer.wrap(image);
                    while (buffer.hasRemaining()) output.write(buffer);
                    output.force(true);
                }
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                committedWrites++;
                try (FileChannel directory = FileChannel.open(file.getParent(), StandardOpenOption.READ)) {
                    directory.force(true);
                } catch (java.nio.file.AccessDeniedException | UnsupportedOperationException unsupported) {
                    // Same provider-conditional directory sync limit as the file backend.
                }
            } finally { Files.deleteIfExists(temporary); }
        }
    }
    public static final class DuplicateProvider extends FixtureProvider {}
    public static final class InvalidProvider extends FixtureProvider {
        @Override public String id() { return "sqlite"; }
    }

    private static void rejectsUnapprovedJdbcBackends(Path module) throws Exception {
        boolean refused = false;
        try {
            new M3ModuleJdbcStorage(module, "remote-postgres");
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        check(refused, "network JDBC backend ids are not accepted");
        for (String[] option : new String[][] {
                {"sqlite", "org.sqlite.JDBC"}, {"h2", "org.h2.Driver"}}) {
            try {
                Class.forName(option[1]);
            } catch (ClassNotFoundException unavailable) {
                expectFailure(() -> new M3ModuleJdbcStorage(module, option[0]),
                        "missing explicit " + option[0] + " driver refuses selection");
            }
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        passed++;
    }

    private static void expectFailure(Checked callback, String what) throws Exception {
        try {
            callback.run();
        } catch (IOException expected) {
            check(true, what);
            return;
        }
        throw new AssertionError(what);
    }

    private static void expectStateFailure(Checked callback, String what) throws Exception {
        try {
            callback.run();
        } catch (IllegalStateException expected) {
            check(true, what);
            return;
        }
        throw new AssertionError(what);
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
