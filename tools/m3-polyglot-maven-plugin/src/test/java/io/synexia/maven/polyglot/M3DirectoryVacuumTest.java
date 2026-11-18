/*
 * SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Offline contract for M3IndexDB-style mark/sweep maintenance. */
public final class M3DirectoryVacuumTest {
    private M3DirectoryVacuumTest() {}

    public static void main(String[] ignored) throws Exception {
        Path temp = Files.createTempDirectory("m3-vacuum-test-");
        try {
            Path dir = Files.createDirectories(temp.resolve("db"));
            require(!M3DirectoryVacuum.compact(dir).recognized(), "unknown empty format remains untouched");
            Path blobs = Files.createDirectories(dir.resolve("blobs"));
            Path refs = Files.createDirectories(dir.resolve("refs"));
            String a = blob(blobs, "older");
            String b = blob(blobs, "newer");
            String c = blob(blobs, "other-live");
            reference(refs, "compile", a, "older".length());
            reference(refs, "test", c, "other-live".length());
            reference(refs, "compile", b, "newer".length());
            var compact = M3DirectoryVacuum.compact(dir);
            require(compact.recognized() && compact.removedBlobs() == 1 && compact.reclaimedBytes() == 5,
                    "old unreachable blob reclaimed exactly once");
            require(!Files.exists(blobs.resolve(a + ".blob")) && Files.exists(blobs.resolve(b + ".blob"))
                    && Files.exists(blobs.resolve(c + ".blob")), "all live refs remain readable");
            require(M3DirectoryVacuum.compact(dir).removedBlobs() == 0, "second compaction is idempotent");
            String orphan = blob(blobs, "orphan");
            Path good = refs.resolve(sha("test") + ".ref");
            String original = Files.readString(good);
            Files.writeString(good, original.replace("other-live".length() + "\n", "999\n"));
            refuse(() -> M3DirectoryVacuum.compact(dir));
            require(Files.exists(blobs.resolve(orphan + ".blob")), "invalid live ref blocks every deletion");
            Files.writeString(good, original);
            Files.writeString(good, original.replace("M3INDEXDB-REF-1", "FUTURE-REF-2"));
            require(!M3DirectoryVacuum.compact(dir).recognized()
                    && Files.exists(blobs.resolve(orphan + ".blob")), "future format is preserved");
            Files.writeString(good, original);
            Path unexpected = Files.writeString(blobs.resolve("unknown.data"), "keep");
            require(!M3DirectoryVacuum.compact(dir).recognized()
                    && Files.exists(blobs.resolve(orphan + ".blob")), "foreign blob files prevent reclaim");
            Files.delete(unexpected);
            Files.writeString(blobs.resolve(b + ".blob"), "NEwer", StandardCharsets.UTF_8);
            refuse(() -> M3DirectoryVacuum.compact(dir));
            require(Files.exists(blobs.resolve(orphan + ".blob")),
                    "damaged live blob prevents orphan deletion");
            Files.writeString(blobs.resolve(b + ".blob"), "newer", StandardCharsets.UTF_8);
            require(M3DirectoryVacuum.compact(dir).removedBlobs() == 1, "cleanup resumes after layout restored");
            Path alien = Files.writeString(dir.resolve("future.header"), "opaque");
            require(!M3DirectoryVacuum.compact(dir).recognized(), "unknown top-level format left untouched");
            Files.delete(alien);
            // A native provider can be interrupted after storing a blob but before
            // publishing its reference: the unreferenced blob is safe to reclaim.
            Path orphanOnly = Files.createDirectories(temp.resolve("interrupted"));
            Files.createDirectories(orphanOnly.resolve("refs"));
            Path onlyBlobs = Files.createDirectories(orphanOnly.resolve("blobs"));
            String interrupted = blob(onlyBlobs, "unpublished");
            require(M3DirectoryVacuum.compact(orphanOnly).removedBlobs() == 1
                    && !Files.exists(onlyBlobs.resolve(interrupted + ".blob")),
                    "interrupted pre-reference write is reclaimed under the module lease");
            originalBackendRetainsOnlyLiveContent(temp.resolve("backend"));
            System.out.println("PASS: M3IndexDB vacuum live refs, orphan reclamation, idempotence, corrupt/future safety");
        } finally {
            try (var walk = Files.walk(temp)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    /**
     * Exercise the real DirectoryStateBackend writer with the exact inlined
     * M3IndexDB engine (not a fake provider). The source-pinned engine emits
     * blob/ref pairs, and successive module commits must not retain orphans.
     */
    private static void originalBackendRetainsOnlyLiveContent(Path root) throws Exception {
        Path state = Files.createDirectories(root.resolve(".m3"));
        Path database = state.resolve("maven-build-db");
        var backend = new DirectoryStateBackend(database,
                new StateFiles(root, M3DbModuleStore.LOCK, M3DbModuleStore.MARKER),
                LibraryIndexStore.ENGINE);
        for (int i = 0; i < 12; i++) {
            byte[] image = ("successful module state " + i).getBytes(StandardCharsets.UTF_8);
            backend.write(image);
            require(java.util.Arrays.equals(image, backend.read()), "native store read-after-write round trip");
            try (var blobs = Files.list(database.resolve("blobs"))) {
                require(blobs.count() == 1, "native store must retain only the live content blob");
            }
            try (var refs = Files.list(database.resolve("refs"))) {
                require(refs.count() == 1, "native store retains canonical artifact reference");
            }
        }
    }

    private static String blob(Path directory, String value) throws Exception {
        String id = sha(value);
        Files.writeString(directory.resolve(id + ".blob"), value, StandardCharsets.UTF_8);
        return id;
    }

    private static void reference(Path directory, String name, String hash, long length) throws Exception {
        String data = String.join("\n", List.of("M3INDEXDB-REF-1", name,
                "maven-module-state", "2", hash, Long.toString(length))) + "\n";
        Files.writeString(directory.resolve(sha(name) + ".ref"), data, StandardCharsets.UTF_8);
    }

    private static String sha(String input) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    }

    private static void refuse(Checked action) throws Exception {
        boolean failed = false;
        try { action.run(); } catch (IOException expected) { failed = true; }
        require(failed, "corrupted native store must fail closed");
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void require(boolean value, String text) { if (!value) throw new AssertionError(text); }
}
