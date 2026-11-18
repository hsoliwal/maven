/*
 * SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Conservative mark-and-sweep compaction of the known M3IndexDB blob/ref directory layout.
 *
 * <p>Called only while the owning module's cross-process lease is held. Every ref
 * and blob entry is inventoried and validated before any deletion. An unfamiliar
 * layout is left entirely alone (e.g. another M3JDK21 or donor runtime engine).
 * Only blobs unreachable from <em>all</em> references are eligible for deletion.
 * The M3IndexDB storage format itself remains owned by its original engine.
 */
final class M3DirectoryVacuum {
    private static final String REF_MAGIC = "M3INDEXDB-REF-1";
    private static final int MAX_ENTRIES = 16_384;
    private static final long MAX_BLOB_BYTES = 32L * 1024 * 1024;
    private static final long MAX_REF_BYTES = 4_096;
    private static final String REF_PATTERN = "[0-9a-f]{64}\\.ref";
    private static final String BLOB_PATTERN = "[0-9a-f]{64}\\.blob";

    record Report(boolean recognized, int removedBlobs, long reclaimedBytes) {
        Report {
            if (removedBlobs < 0 || reclaimedBytes < 0) throw new IllegalArgumentException("negative reclamation");
        }
    }

    private M3DirectoryVacuum() {}

    static Report compact(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return new Report(false, 0, 0);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("M3DB native storage root is not an ordinary directory");
        }
        Path refs = root.resolve("refs");
        Path blobs = root.resolve("blobs");
        if (!Files.isDirectory(refs, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(blobs, LinkOption.NOFOLLOW_LINKS)) {
            return new Report(false, 0, 0);
        }
        // Unknown top-level files may belong to a newer runtime implementation.
        for (Path entry : entries(root)) {
            if (!entry.equals(refs) && !entry.equals(blobs)) return new Report(false, 0, 0);
        }
        List<Path> refFiles = entries(refs);
        List<Path> blobFiles = entries(blobs);
        Set<String> live = new HashSet<>();
        for (Path ref : refFiles) {
            if (!ordinary(ref)) throw new IOException("non-ordinary M3IndexDB reference");
            String name = ref.getFileName().toString();
            if (!name.matches(REF_PATTERN)) return new Report(false, 0, 0);
            if (Files.size(ref) > MAX_REF_BYTES) throw new IOException("M3IndexDB reference exceeds bound");
            List<String> lines = Files.readAllLines(ref, StandardCharsets.UTF_8);
            if (lines.size() != 6 || !REF_MAGIC.equals(lines.get(0))) {
                // Never guess about a later M3IndexDB reference format.
                return new Report(false, 0, 0);
            }
            String artifactName = lines.get(1);
            if (artifactName.isBlank() || !name.equals(sha256(artifactName.getBytes(StandardCharsets.UTF_8)) + ".ref")) {
                throw new IOException("M3IndexDB reference identity mismatch");
            }
            try {
                if (Integer.parseInt(lines.get(3)) <= 0) throw new NumberFormatException("version");
            } catch (NumberFormatException error) {
                throw new IOException("M3IndexDB reference version malformed", error);
            }
            String blobId = lines.get(4);
            if (!blobId.matches("[0-9a-f]{64}")) throw new IOException("M3IndexDB reference digest malformed");
            long length;
            try { length = Long.parseLong(lines.get(5)); }
            catch (NumberFormatException error) { throw new IOException("M3IndexDB blob length malformed", error); }
            if (length < 0 || length > MAX_BLOB_BYTES) throw new IOException("M3IndexDB referenced blob oversized");
            Path target = blobs.resolve(blobId + ".blob");
            if (!ordinary(target) || Files.size(target) != length) {
                throw new IOException("M3IndexDB live blob missing or length mismatched");
            }
            live.add(blobId);
        }
        // Validate the entire deletion set before deleting even the first orphan.
        for (Path blob : blobFiles) {
            if (!ordinary(blob)) throw new IOException("non-ordinary M3IndexDB blob");
            if (!blob.getFileName().toString().matches(BLOB_PATTERN)) return new Report(false, 0, 0);
            if (Files.size(blob) > MAX_BLOB_BYTES) throw new IOException("M3IndexDB blob exceeds bound");
        }
        List<Path> orphaned = new ArrayList<>();
        for (Path blob : blobFiles) {
            if (!live.contains(blob.getFileName().toString().substring(0, 64))) orphaned.add(blob);
        }
        if (orphaned.isEmpty()) return new Report(true, 0, 0);
        // If any ref is already corrupt, leave all files for recovery/forensics.
        // This full SHA pass happens only when actual garbage needs deletion.
        for (String id : live) {
            if (!id.equals(sha256(blobs.resolve(id + ".blob")))) {
                throw new IOException("M3IndexDB referenced blob digest mismatch");
            }
        }
        long reclaimed = 0;
        for (Path blob : orphaned) {
            long size = Files.size(blob);
            Files.delete(blob);
            reclaimed += size;
        }
        return new Report(true, orphaned.size(), reclaimed);
    }

    private static boolean ordinary(Path file) {
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
    }

    private static List<Path> entries(Path directory) throws IOException {
        List<Path> items = new ArrayList<>();
        try (Stream<Path> listing = Files.list(directory)) {
            var it = listing.iterator();
            while (it.hasNext()) {
                if (items.size() >= MAX_ENTRIES) throw new IOException("M3IndexDB maintenance entry budget exceeded");
                items.add(it.next());
            }
        }
        items.sort(Path::compareTo);
        return items;
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            MessageDigest digest = sha256();
            byte[] buffer = new byte[65_536];
            for (int count; (count = stream.read(buffer)) != -1;) {
                if (count == 0) throw new IOException("stalled M3IndexDB blob reader");
                digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
}
