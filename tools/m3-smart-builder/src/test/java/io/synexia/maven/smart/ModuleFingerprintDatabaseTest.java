// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/** Real installed-provider integration; this suite deliberately refuses a mock or absent M3DB. */
public final class ModuleFingerprintDatabaseTest {
    private static int checks;
    private static final String CODE = "class Example { int f(int x) { return x + 1; } }\n";

    private ModuleFingerprintDatabaseTest() { }

    public static void main(String[] args) throws Exception {
        Class<?> provider = Class.forName("com.synexia.mindex.db.MIndexDB", true,
                ModuleFingerprintDatabaseTest.class.getClassLoader());
        require(AutoCloseable.class.isAssignableFrom(provider), "canonical MIndexDB provider is installed");
        coldWarmAndInvalidation();
        concurrentPublicationAndIdentity();
        interruptedPublicationAndClose();
        corruptAndUnsafeStorage();
        System.out.println("M3DB MODULE CACHE TESTS PASS: " + checks + " (canonical MIndexDB artifacts; Java/NIO)");
    }

    private static void coldWarmAndInvalidation() throws Exception {
        var fixture = MachineFingerprintTest.fixture(CODE, "Example.java", true);
        require(fixture.run(2, false, false, "required") == 0, "cold MIndexDB producer succeeds");
        count(fixture, 0, 2, "stored");
        Path database = database(fixture);
        byte[] magic = Arrays.copyOf(Files.readAllBytes(database), 8);
        require(new String(magic, StandardCharsets.US_ASCII).equals("MINDEXAR"),
                "the canonical MIndexDB artifact image is materialized");
        Object owner = providerOpen(databaseRoot(fixture));
        try {
            require(Boolean.TRUE.equals(providerCall(owner, "nativeStorage", new Class<?>[0])),
                    "original MIndexDB Java/NIO artifact engine owns persistence");
            Object artifact = artifact(owner, entry(fixture.source()));
            require("maven-syntax-fingerprints".equals(accessor(artifact, "kind"))
                    && Integer.valueOf(1).equals(accessor(artifact, "formatVersion")),
                    "the artifact carries its exact Maven kind and schema version");
        } finally { providerCall(owner, "close", new Class<?>[0]); }
        String first = fixture.tsv();
        String stored = Hashes.sha256(database);
        require(fixture.run(4, false, false, "required") == 0 && first.equals(fixture.tsv()),
                "warm parallel syntax output exactly equals original cold output");
        count(fixture, 2, 0, "unchanged");
        require(stored.equals(Hashes.sha256(database)), "fully warm run leaves database bytes unchanged");

        child(fixture, "required", true, 0);
        count(fixture, 2, 0, "unchanged");
        require(first.equals(fixture.tsv()), "a fresh JVM reopens and reuses both persisted files");
        child(fixture, "auto", false, 0);
        count(fixture, 0, 2, "unavailable");
        require(first.equals(fixture.tsv()), "stock JDK fallback preserves byte-identical results");
        child(fixture, "required", false, 2);

        Path changed = fixture.source().resolve("Example.java");
        FileTime time = Files.getLastModifiedTime(changed);
        long size = Files.size(changed);
        Files.writeString(changed, CODE.replace("+ 1", "+ 2"));
        Files.setLastModifiedTime(changed, time);
        require(Files.size(changed) == size && Files.getLastModifiedTime(changed).equals(time),
                "adversarial edit retains size and timestamp");
        refresh(fixture);
        require(fixture.run(3, false, false, "required") == 0 && !first.equals(fixture.tsv()),
                "content edit produces current fingerprints");
        count(fixture, 1, 1, "stored");
        String extra = Files.readString(fixture.source().resolve("Extra.java"));
        Files.delete(fixture.source().resolve("Extra.java"));
        refresh(fixture);
        require(fixture.run(2, false, false, "required") == 0 && !fixture.tsv().contains("Extra.java\t"),
                "removed sources disappear from both the current output and next cache generation");
        count(fixture, 1, 0, "stored");
        Files.writeString(fixture.source().resolve("Extra.java"), extra);
        refresh(fixture);
        require(fixture.run(2, false, false, "required") == 0, "re-added file is recomputed");
        count(fixture, 1, 1, "stored");

        String validCode = Files.readString(changed);
        String beforeFailure = Hashes.sha256(database);
        Files.writeString(changed, "class Example { int f( { }\n");
        refresh(fixture);
        require(fixture.run(2, false, false, "required") == 2, "syntax failure refuses the current generation");
        require(beforeFailure.equals(Hashes.sha256(database)), "failed syntax never commits staged rows");
        Files.writeString(changed, validCode);
        refresh(fixture);
        require(fixture.run(2, false, false, "required") == 0, "prior verified generation remains reusable");
        count(fixture, 2, 0, "unchanged");

        require(fixture.run(2, true, true, "required") == 0, "attribution and may-flow execute with cached syntax");
        count(fixture, 2, 0, "unchanged");
        require(Boolean.TRUE.equals(fixture.receipt().get("typeAttributionExecuted"))
                && Boolean.TRUE.equals(fixture.receipt().get("mayFlowAnalysisExecuted")),
                "syntax hits do not suppress dependency-sensitive passes");
        beforeFailure = Hashes.sha256(database);
        Files.writeString(fixture.source().resolve("Extra.java"), "class Extra { UnknownType value; }\n");
        refresh(fixture);
        require(fixture.run(2, true, false, "required") == 2, "unresolved module type still fails attribution");
        require(beforeFailure.equals(Hashes.sha256(database)), "failed attribution never publishes syntax candidates");
        Files.writeString(fixture.source().resolve("Extra.java"), extra);
        refresh(fixture);
        require(fixture.run(2, false, false, "required") == 0, "attribution failure does not discard last syntax image");
        count(fixture, 2, 0, "unchanged");

        beforeFailure = Hashes.sha256(database);
        try {
            Thread.currentThread().interrupt();
            require(fixture.run(1, false, false, "required") == 2 && Thread.currentThread().isInterrupted(),
                    "warm interrupted generation preserves interruption and refuses publication");
        } finally { Thread.interrupted(); }
        require(beforeFailure.equals(Hashes.sha256(database)), "interruption leaves the previous database intact");

        var other = MachineFingerprintTest.fixture(CODE, "Example.java", true);
        require(other.run(2, false, false, "required") == 0, "second reactor module gets its own database");
        count(other, 0, 2, "stored");
        require(!Files.isSameFile(database, database(other)), "equal source names in different modules remain isolated");
        Path oldOutput = fixture.base().resolve("target/m3-smart");
        try (var walk = Files.walk(fixture.base().resolve("target"))) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
        require(!Files.exists(oldOutput) && fixture.run(1, false, false, "required") == 0,
                "cleaned build output is regenerated from the module database outside target");
        count(fixture, 2, 0, "unchanged");
    }

    private static void concurrentPublicationAndIdentity() throws Exception {
        var fixture = MachineFingerprintTest.fixture(CODE, "Example.java", true);
        require(fixture.run(1, false, false, "off") == 0, "reference rows are produced by the unchanged compiler path");
        var facts = SourceInventory.inspectSelected(fixture.base(), fixture.source()).files();
        List<String> rows = fixture.tsv().lines().skip(1).toList();
        var first = ModuleFingerprintDatabase.open(fixture.base(), fixture.source(), "required", "producer-A");
        var stale = ModuleFingerprintDatabase.open(fixture.base(), fixture.source(), "required", "producer-B");
        stage(first, facts, rows, false);
        stage(stale, facts, rows, false);
        first.commit();
        String committed = Hashes.sha256(database(fixture));
        stale.commit();
        require("superseded".equals(object(stale.json()).get("status"))
                && committed.equals(Hashes.sha256(database(fixture))),
                "stale writer cannot replace a newer committed generation");
        var warm = ModuleFingerprintDatabase.open(fixture.base(), fixture.source(), "required", "producer-A");
        stage(warm, facts, rows, true);
        warm.commit();
        require("unchanged".equals(object(warm.json()).get("status")), "same producer generation is reused");
        var changed = ModuleFingerprintDatabase.open(fixture.base(), fixture.source(), "required", "producer-B");
        stage(changed, facts, rows, false);

        // MIndexDB artifacts are immutable payload snapshots, not MVCC database handles.
        Object snapshotOwner = providerOpen(databaseRoot(fixture));
        Object snapshot;
        try { snapshot = artifact(snapshotOwner, entry(fixture.source())); }
        finally { providerCall(snapshotOwner, "close", new Class<?>[0]); }
        byte[] old = payload(snapshot);
        byte[] callerCopy = payload(snapshot);
        callerCopy[0] ^= 1;
        require(Arrays.equals(old, payload(snapshot)), "MIndexDB artifact payload access returns a defensive copy");
        changed.commit();
        require("stored".equals(object(changed.json()).get("status")),
                "publication succeeds with an earlier caller-owned artifact snapshot retained");
        require(Arrays.equals(old, payload(snapshot)),
                "an immutable artifact snapshot retains its old bytes across publication");
        Object latest = providerOpen(databaseRoot(fixture));
        try {
            require(!Arrays.equals(old, payload(artifact(latest, entry(fixture.source())))),
                    "reopened MIndexDB observes the new committed context image");
        } finally { providerCall(latest, "close", new Class<?>[0]); }
        for (var fact : facts) require(warm.find(fact).isPresent(),
                "an already loaded adapter snapshot retains its accepted rows after another commit");
        var obsolete = ModuleFingerprintDatabase.open(fixture.base(), fixture.source(), "required", "producer-A");
        for (var fact : facts) require(obsolete.find(fact).isEmpty(), "old compiler/algorithm identity is invalidated");

        var busy = ModuleFingerprintDatabase.open(fixture.base(), fixture.source(), "auto", "producer-C");
        stage(busy, facts, rows, false);
        Path lockPath = databaseRoot(fixture).resolveSibling("mindexdb.lock");
        committed = Hashes.sha256(database(fixture));
        var stripesField = ModuleFingerprintDatabase.class.getDeclaredField("WRITERS");
        stripesField.setAccessible(true);
        ReentrantLock[] stripes = (ReentrantLock[]) stripesField.get(null);
        ReentrantLock stripe = stripes[(databaseRoot(fixture).hashCode() & Integer.MAX_VALUE) % stripes.length];
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> lockingFailure = new AtomicReference<>();
        Thread owner = new Thread(() -> {
            stripe.lock();
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE);
                    FileLock held = channel.lock()) {
                if (!held.isValid()) throw new IOException("test writer lock unavailable");
                locked.countDown();
                if (!release.await(30, TimeUnit.SECONDS)) throw new IOException("test writer release timed out");
            } catch (IOException | InterruptedException error) { lockingFailure.set(error); }
            finally { stripe.unlock(); }
        }, "m3db-lock-owner");
        owner.start();
        try {
            require(locked.await(5, TimeUnit.SECONDS), "cooperating writer holds JVM guard and actual OS file lock");
            busy.commit();
            require("bypassed".equals(object(busy.json()).get("status")), "automatic cache bypasses a busy writer");
            // A second channel close must not silently release the first writer's POSIX lock.
            child(fixture, "required", true, 2);
        } finally {
            release.countDown();
            owner.join(Duration.ofSeconds(5).toMillis());
        }
        require(!owner.isAlive() && lockingFailure.get() == null, "writer lock and JVM guard close cleanly");
        require(committed.equals(Hashes.sha256(database(fixture))), "busy same-JVM and cross-process writers preserve head");

        Path secondRoot = Files.createDirectory(fixture.base().resolve("src-second"));
        Files.writeString(secondRoot.resolve("Example.java"), CODE);
        var separate = ModuleFingerprintDatabase.open(fixture.base(), secondRoot, "required", "producer-B");
        require(separate.find(facts.getFirst()).isEmpty(), "a different source root has a distinct database entry");
    }

    private static void corruptAndUnsafeStorage() throws Exception {
        var fixture = MachineFingerprintTest.fixture(CODE, "Example.java", true);
        require(fixture.run(1, false, false, "required") == 0, "corruption case begins with a real published image");
        String expected = fixture.tsv();
        Object owner = providerOpen(databaseRoot(fixture));
        try {
            byte[] bytes = payload(artifact(owner, entry(fixture.source())));
            bytes[bytes.length - 1] ^= 1;
            put(owner, entry(fixture.source()), "maven-syntax-fingerprints", 1, bytes);
        } finally { providerCall(owner, "close", new Class<?>[0]); }
        String corrupt = Hashes.sha256(database(fixture));
        require(fixture.run(2, false, false, "auto") == 0 && expected.equals(fixture.tsv()),
                "checksum failure falls back to original syntax computation");
        count(fixture, 0, 2, "bypassed");
        require(corrupt.equals(Hashes.sha256(database(fixture))), "corrupt cache is preserved rather than silently repaired");
        require(fixture.run(1, false, false, "required") == 2, "required mode reports corruption as refusal");

        for (boolean wrongKind : List.of(true, false)) {
            var metadata = MachineFingerprintTest.fixture(CODE, "Example.java", true);
            require(metadata.run(1, false, false, "required") == 0, "metadata case has a published image");
            String valid = metadata.tsv();
            Object metadataOwner = providerOpen(databaseRoot(metadata));
            try {
                byte[] bytes = payload(artifact(metadataOwner, entry(metadata.source())));
                put(metadataOwner, entry(metadata.source()),
                        wrongKind ? "foreign-kind" : "maven-syntax-fingerprints", wrongKind ? 1 : 2, bytes);
            } finally { providerCall(metadataOwner, "close", new Class<?>[0]); }
            String foreign = Hashes.sha256(database(metadata));
            require(metadata.run(2, false, false, "auto") == 0 && valid.equals(metadata.tsv()),
                    "foreign artifact kind/version falls back to original syntax computation");
            count(metadata, 0, 2, "bypassed");
            require(foreign.equals(Hashes.sha256(database(metadata))), "foreign metadata is not overwritten");
            require(metadata.run(1, false, false, "required") == 2, "required mode rejects foreign kind/version");
        }

        var malformed = MachineFingerprintTest.fixture(CODE, "Example.java", false);
        Files.createDirectories(databaseRoot(malformed).getParent());
        Files.writeString(databaseRoot(malformed), "this is not a database directory");
        require(malformed.run(1, false, false, "auto") == 0, "foreign physical root cannot block automatic mode");
        count(malformed, 0, 1, "bypassed");
        require(Files.readString(databaseRoot(malformed)).equals("this is not a database directory"),
                "foreign root file is never overwritten");
        require(malformed.run(1, false, false, "required") == 2, "required mode rejects a foreign root file");

        var foreignArtifact = MachineFingerprintTest.fixture(CODE, "Example.java", false);
        Files.createDirectories(databaseRoot(foreignArtifact));
        Files.writeString(database(foreignArtifact), "this is not a MINDEXAR image");
        require(foreignArtifact.run(1, false, false, "auto") == 0, "foreign artifact cannot block automatic mode");
        count(foreignArtifact, 0, 1, "bypassed");
        require(Files.readString(database(foreignArtifact)).equals("this is not a MINDEXAR image"),
                "foreign artifact bytes are never overwritten");
        require(foreignArtifact.run(1, false, false, "required") == 2, "required mode rejects a foreign artifact");

        var oversized = MachineFingerprintTest.fixture(CODE, "Example.java", false);
        require(oversized.run(1, false, false, "required") == 0, "oversized case starts with a canonical image");
        try (FileChannel channel = FileChannel.open(database(oversized), StandardOpenOption.WRITE)) {
            channel.position(32L * 1024 * 1024);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }
        String largeHash = Hashes.sha256(database(oversized));
        require(oversized.run(1, false, false, "auto") == 0, "oversized physical image bypasses automatic cache");
        count(oversized, 0, 1, "bypassed");
        require(largeHash.equals(Hashes.sha256(database(oversized))), "oversized image is preserved");
        require(oversized.run(1, false, false, "required") == 2, "required mode rejects the physical byte budget");

        var linked = MachineFingerprintTest.fixture(CODE, "Example.java", false);
        Path outside = Files.createTempDirectory("mindexdb-outside-");
        Files.createSymbolicLink(linked.base().resolve(".m3"), outside);
        require(linked.run(1, false, false, "auto") == 0, "automatic mode bypasses a linked database parent");
        count(linked, 0, 1, "bypassed");
        require(!Files.exists(outside.resolve("maven")), "linked directory receives no cache writes");
        require(linked.run(1, false, false, "required") == 2, "required mode rejects a linked database parent");

        for (String location : List.of("root", "artifact", "lock")) {
            var symlink = MachineFingerprintTest.fixture(CODE, "Example.java", false);
            Path target;
            Path link;
            if (location.equals("root")) {
                Files.createDirectories(databaseRoot(symlink).getParent());
                target = Files.createTempDirectory("mindexdb-external-root-");
                Files.writeString(target.resolve("sentinel.txt"), "untouched");
                link = databaseRoot(symlink);
            } else {
                Files.createDirectories(databaseRoot(symlink));
                target = Files.createTempFile("mindexdb-external-", ".txt");
                Files.writeString(target, "untouched");
                link = location.equals("artifact") ? database(symlink)
                        : databaseRoot(symlink).resolveSibling("mindexdb.lock");
            }
            Files.createSymbolicLink(link, target);
            require(symlink.run(1, false, false, "auto") == 0, "linked cache owner is bypassed: " + location);
            count(symlink, 0, 1, "bypassed");
            Path sentinel = location.equals("root") ? target.resolve("sentinel.txt") : target;
            require(Files.readString(sentinel).equals("untouched"), "linked target remains untouched: " + location);
            require(symlink.run(1, false, false, "required") == 2,
                    "required mode refuses linked cache owner: " + location);
        }
    }

    /** Deterministic adapter cancellation boundary; no provider rollback or fsync guarantee is assumed. */
    private static void interruptedPublicationAndClose() throws Exception {
        var fixture = MachineFingerprintTest.fixture(CODE, "Example.java", true);
        require(fixture.run(1, false, false, "required") == 0, "interruption case has a committed image");
        String before = Hashes.sha256(database(fixture));
        var facts = SourceInventory.inspectSelected(fixture.base(), fixture.source()).files();
        for (boolean fullyWarm : List.of(true, false)) {
            var cache = fullyWarm ? ModuleFingerprintDatabase.open(fixture.base(), fixture.source(), "required")
                    : ModuleFingerprintDatabase.open(fixture.base(), fixture.source(), "required", "cancelled-producer");
            stage(cache, facts, fixture.tsv().lines().skip(1).toList(), fullyWarm);
            try {
                Thread.currentThread().interrupt();
                IOException failure = null;
                try { cache.commit(); }
                catch (IOException refused) { failure = refused; }
                require(failure instanceof java.io.InterruptedIOException && Thread.currentThread().isInterrupted(),
                        "pre-publication cancellation preserves interruption, including a fully warm commit: " + fullyWarm);
            } finally { Thread.interrupted(); }
            require(before.equals(Hashes.sha256(database(fixture))), "pre-publication interruption preserves the image");
        }

        Class<?> accessType = Class.forName(ModuleFingerprintDatabase.class.getName() + "$NativeAccess");
        var discover = accessType.getDeclaredMethod("discover");
        discover.setAccessible(true);
        Object access = discover.invoke(null);
        var open = accessType.getDeclaredMethod("open", Path.class);
        open.setAccessible(true);
        Object handle = open.invoke(access, databaseRoot(fixture));
        var ownerField = handle.getClass().getDeclaredField("database");
        ownerField.setAccessible(true);
        Object providerOwner = ownerField.get(handle);
        require(Boolean.TRUE.equals(providerCall(providerOwner, "isOpen", new Class<?>[0])),
                "adapter test handle owns an open canonical provider");
        var close = handle.getClass().getDeclaredMethod("close");
        close.setAccessible(true);
        try {
            Thread.currentThread().interrupt();
            close.invoke(handle);
            require(Thread.currentThread().isInterrupted()
                    && Boolean.FALSE.equals(providerCall(providerOwner, "isOpen", new Class<?>[0])),
                    "adapter closes the actual MIndexDB provider with the caller interrupt flag retained");
        } finally { Thread.interrupted(); }
        require(before.equals(Hashes.sha256(database(fixture))), "interrupted provider close leaves artifact bytes intact");
    }

    private static void stage(ModuleFingerprintDatabase cache, List<SourceInventory.FileFact> facts,
                              List<String> rows, boolean hit) throws Exception {
        for (var fact : facts) {
            var found = cache.find(fact);
            require(found.isPresent() == hit, "file lookup observes the exact producer/source generation");
            cache.stage(fact, rows.stream().filter(row -> row.startsWith(fact.path() + "\t")).toList());
        }
    }

    private static void refresh(MachineFingerprintTest.Fixture fixture) throws Exception {
        try (var old = Files.walk(fixture.base().resolve("synthetic-checkpoints"))) {
            for (Path path : old.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        AtomPatternEvidenceTest.writeActionFixture(fixture.base(), Path.of("src"));
        Files.writeString(fixture.base().resolve("receipts/preparation.json"),
                CanonicalPreparationEvidenceTestMain.report(SourceInventory.inspectSelected(fixture.base(), fixture.source())));
    }

    private static void count(MachineFingerprintTest.Fixture fixture, int hits, int computed, String status)
            throws Exception {
        Map<?, ?> data = (Map<?, ?>) fixture.receipt().get("moduleDatabase");
        require(data != null && Integer.parseInt(data.get("hits").toString()) == hits
                && Integer.parseInt(data.get("filesComputed").toString()) == computed
                && status.equals(data.get("status")), "cache counters/status: " + hits + "/" + computed + "/" + status);
    }

    private static Path databaseRoot(MachineFingerprintTest.Fixture fixture) {
        return fixture.base().resolve(".m3/maven/mindexdb");
    }

    private static Path database(MachineFingerprintTest.Fixture fixture) throws IOException {
        String file = Hashes.sha256(entry(fixture.source()).getBytes(StandardCharsets.UTF_8)) + ".miart";
        return databaseRoot(fixture).resolve(file);
    }

    private static String entry(Path source) throws IOException {
        return "maven-syntax-fingerprints-v1:"
                + Hashes.sha256(source.toRealPath().toUri().toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Map<?, ?> object(String json) throws IOException { return (Map<?, ?>) TinyJson.parse(json); }

    private static Object providerOpen(Path root) throws Exception {
        return providerCall(null, "open", new Class<?>[] {Path.class}, root);
    }

    private static Object artifact(Object owner, String name) throws Exception {
        return ((Optional<?>) providerCall(owner, "findArtifact", new Class<?>[] {CharSequence.class}, name))
                .orElseThrow();
    }

    private static Object accessor(Object artifact, String name) throws Exception {
        return artifact.getClass().getMethod(name).invoke(artifact);
    }

    private static byte[] payload(Object artifact) throws Exception { return (byte[]) accessor(artifact, "payload"); }

    private static void put(Object owner, String name, String kind, int version, byte[] value) throws Exception {
        providerCall(owner, "putArtifact", new Class<?>[] {
                CharSequence.class, CharSequence.class, int.class, byte[].class}, name, kind, version, value);
    }

    private static Object providerCall(Object target, String method, Class<?>[] types, Object... args) throws Exception {
        Class<?> type = Class.forName("com.synexia.mindex.db.MIndexDB", true,
                ModuleFingerprintDatabaseTest.class.getClassLoader());
        try { return type.getMethod(method, types).invoke(target, args); }
        catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof Exception exception) throw exception;
            throw failed;
        }
    }

    private static void child(MachineFingerprintTest.Fixture fixture, String mode, boolean provider, int expected)
            throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        String classpath;
        if (provider) {
            classpath = System.getProperty("java.class.path");
        } else {
            var roots = new LinkedHashSet<String>();
            for (Class<?> type : List.of(MachineFingerprintMain.class, ModuleFingerprintDatabaseTest.class)) {
                roots.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
            }
            classpath = String.join(File.pathSeparator, roots);
        }
        command.addAll(List.of("-cp", classpath, MachineFingerprintMain.class.getName(),
                "--project-dir", fixture.base().toString(), "--source-root", "src", "--build-dir", "target",
                "--preparation-report", "receipts/preparation.json", "--atom-pattern-manifest", "synthetic-checkpoints.tsv",
                "--workers", "4", "--module-db", mode));
        Path log = Files.createTempFile("m3db-child-", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            boolean finished = process.waitFor(Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS);
            require(finished && process.exitValue() == expected,
                    "fresh JVM status=" + expected + ": " + Files.readString(log));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
