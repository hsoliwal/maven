// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and contributors
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.ast;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Multi-origin source correspondences for generated, macro-expanded, embedded,
 * or preprocessed syntax. This is an optional, projection-bound *sidecar* over
 * the original SyntaxTree's source bytes, not another parser or source store.
 *
 * <p>One generated range can correspond to multiple original ranges, possibly
 * from different source files. Only a unique, explicitly EXACT, equal-byte-
 * length mapping may translate an individual byte point. Non-affine or
 * overlapping mappings remain discoverable but are never guessed into a point.
 * Source registration is an indexed, insertion-stable builder operation; a
 * large number of distinct provenance files does not require a full scan per
 * origin. The immutable sidecar retains the original flat integer columns.
 */
public final class SyntaxOrigins {
    public enum Relation { EXACT_BYTES, DERIVED, MACRO_EXPANSION, EMBEDDED_LANGUAGE }

    public record Origin(String sourceId, String sha256, int startByte, int endByte,
                         Relation relation) { }
    private final String projectionSha256;
    private final int generatedByteLength;
    private final String[] sourceIds, sha256s;
    private final int[] sourceIndex, from, to, originalStart, originalEnd, maxEndThrough;
    private final byte[] relation;
    private static final Relation[] RELATIONS = Relation.values();

    // Builder-only directory entry. Finalized origins retain primitive columns
    // and two parallel identity-string arrays, never per-origin objects.
    private record SourceIdentity(int index, String sha256) { }

    private SyntaxOrigins(Builder b) {
        projectionSha256 = b.tree.projectionSha256();
        generatedByteLength = b.tree.sourceLength();
        sourceIds = b.sourceIds.keySet().toArray(String[]::new);
        sha256s = b.sourceIds.values().stream().map(SourceIdentity::sha256)
                .toArray(String[]::new);
        sourceIndex = Arrays.copyOf(b.sourceIndex, b.size);
        from = Arrays.copyOf(b.from, b.size);
        to = Arrays.copyOf(b.to, b.size);
        originalStart = Arrays.copyOf(b.originalStart, b.size);
        originalEnd = Arrays.copyOf(b.originalEnd, b.size);
        relation = Arrays.copyOf(b.relation, b.size);
        maxEndThrough = new int[b.size];
        int max = 0;
        for (int i = 0; i < b.size; i++) {
            max = Math.max(max, to[i]);
            maxEndThrough[i] = max;
        }
    }
    public static Builder builder(SyntaxTree tree) { return new Builder(tree); }
    public int count() { return from.length; }
    public String projectionSha256() { return projectionSha256; }
    public void requireProjection(SyntaxTree tree) {
        if (!projectionSha256.equals(Objects.requireNonNull(tree, "tree").projectionSha256()))
            throw new IllegalStateException("source origin metadata belongs to another syntax generation");
    }
    public int generatedStart(int index) { return from[Objects.checkIndex(index, count())]; }
    public int generatedEnd(int index) { return to[Objects.checkIndex(index, count())]; }
    public Origin origin(int index) {
        int at = Objects.checkIndex(index, count());
        int source = sourceIndex[at];
        return new Origin(sourceIds[source], sha256s[source], originalStart[at],
                originalEnd[at], RELATIONS[Byte.toUnsignedInt(relation[at])]);
    }

    /**
     * Package-local primitive access for the canonical binary snapshot writer.
     * No public origin object, alternate collection owner or mutable column is
     * exposed. These source IDs are in their original first-registration order,
     * and every source ID belongs to at least one admitted origin row.
     */
    int sourceIdentityCount() { return sourceIds.length; }
    String sourceIdentityAt(int index) { return sourceIds[Objects.checkIndex(index, sourceIds.length)]; }
    String sourceHashAt(int index) { return sha256s[Objects.checkIndex(index, sha256s.length)]; }
    int sourceIndexAt(int row) { return sourceIndex[Objects.checkIndex(row, count())]; }
    int originalStartAt(int row) { return originalStart[Objects.checkIndex(row, count())]; }
    int originalEndAt(int row) { return originalEnd[Objects.checkIndex(row, count())]; }
    int relationCodeAt(int row) { return Byte.toUnsignedInt(relation[Objects.checkIndex(row, count())]); }

    /** Overlapping macro/template mappings yield every applicable row. */
    public int[] originRowsAt(int generatedByte) {
        Objects.checkIndex(generatedByte, generatedByteLength + 1);
        int right = upperBound(from, generatedByte);
        int[] result = new int[Math.min(4, right)];
        int size = 0;
        for (int i = right - 1; i >= 0 && maxEndThrough[i] > generatedByte; i--) {
            if (generatedByte < to[i]) {
                if (size == result.length) result = Arrays.copyOf(result, size + (size >>> 1) + 1);
                result[size++] = i;
            }
        }
        // Stable source addition order rather than reverse scan order.
        for (int i = 0, j = size - 1; i < j; i++, j--) {
            int swap = result[i]; result[i] = result[j]; result[j] = swap;
        }
        return Arrays.copyOf(result, size);
    }
    /** Unique metadata-only offset candidate; use verify() for byte-proven origins. */
    public int exactOriginalByteAt(int generatedByte) {
        int[] matches = originRowsAt(generatedByte);
        if (matches.length != 1) return -1;
        int row = matches[0];
        if (relation[row] != (byte) Relation.EXACT_BYTES.ordinal()
                || to[row] - from[row] != originalEnd[row] - originalStart[row]) return -1;
        return Math.addExact(originalStart[row], generatedByte - from[row]);
    }
    /**
     * Read a source image by its stable logical ID. The returned array is NOT
     * trusted: verification clones it, hashes it and compares exact byte spans.
     * A file source consumer can adapt this with id -> sourceFor(id).bytes() without
     * introducing a dependency from the AST module into the file provider.
     */
    @FunctionalInterface
    public interface SourceBytes {
        byte[] read(String sourceId);
    }

    /** An original physical byte position proven against immutable source bytes. */
    public record ExactPoint(String sourceId, String sha256, int byteOffset, byte value) { }

    /**
     * A fully verified, bounded snapshot of all source origins in this sidecar.
     * The independent source bytes are copied only once per distinct source ID,
     * never once per occurrence. Stored arrays are private and never exposed.
     */
    public static final class Verified {
        private final SyntaxOrigins origins;
        private final Map<String, byte[]> snapshots;
        private final long retainedSourceBytes;

        private Verified(SyntaxOrigins origins, Map<String, byte[]> snapshots,
                         long retainedSourceBytes) {
            this.origins = origins;
            this.snapshots = Map.copyOf(snapshots);
            this.retainedSourceBytes = retainedSourceBytes;
        }
        public int distinctSources() { return snapshots.size(); }
        public long retainedSourceBytes() { return retainedSourceBytes; }
        public String projectionSha256() { return origins.projectionSha256(); }
        public void requireProjection(SyntaxTree tree) { origins.requireProjection(tree); }

        /** Null for missing, overlapping, derived or otherwise ambiguous origins. */
        public ExactPoint exactAtByte(int generatedByte) {
            int[] matches = origins.originRowsAt(generatedByte);
            if (matches.length != 1) return null;
            int row = matches[0];
            if (origins.relation[row] != (byte) Relation.EXACT_BYTES.ordinal()) return null;
            int index = origins.sourceIndex[row];
            String id = origins.sourceIds[index];
            int originalByte = Math.addExact(origins.originalStart[row],
                    generatedByte - origins.from[row]);
            return new ExactPoint(id, origins.sha256s[index], originalByte,
                    snapshots.get(id)[originalByte]);
        }
    }

    /**
     * Upgrade declared origin ranges into checked physical-byte correspondences.
     * This checks the COMPLETE SHA-256 for each external image and requires every
     * EXACT_BYTES range to match the generated image byte-for-byte. Derived and
     * macro correspondences are preserved but cannot become exact points.
     *
     * <p>Budgets bound unique source count and all transient/captured bytes,
     * including one transient copy of the generated AST source image. A
     * failed proof returns nothing and retains no partially accepted state.
     */
    public Verified verify(SyntaxTree tree, SourceBytes loader,
                           int maximumSources, long maximumSnapshotBytes) {
        requireProjection(tree);
        Objects.requireNonNull(loader, "source resolver");
        if (maximumSources < 0 || maximumSnapshotBytes < 0
                || tree.sourceLength() > maximumSnapshotBytes)
            throw new IllegalArgumentException("source-origin verification budget exceeded");
        byte[] generated = tree.sourceBytes();
        long total = generated.length;
        Map<String, byte[]> copies = new LinkedHashMap<>();
        for (int row = 0; row < count(); row++) {
            int index = sourceIndex[row];
            String id = sourceIds[index];
            byte[] original = copies.get(id);
            if (original == null) {
                if (copies.size() >= maximumSources)
                    throw new IllegalArgumentException("too many source origins");
                byte[] supplied = loader.read(id);
                if (supplied == null || supplied.length > maximumSnapshotBytes - total)
                    throw new IllegalArgumentException("source image missing or too large");
                original = supplied.clone();
                if (!sha256s[index].equals(hashBytes(original)))
                    throw new IllegalArgumentException("external source generation drift: " + id);
                copies.put(id, original);
                total += original.length;
            }
            if (originalEnd[row] > original.length)
                throw new IllegalArgumentException("source correspondence beyond physical image");
            if (relation[row] == (byte) Relation.EXACT_BYTES.ordinal()
                    && !Arrays.equals(generated, from[row], to[row],
                            original, originalStart[row], originalEnd[row]))
                throw new IllegalArgumentException("declared exact origin has different bytes");
        }
        return new Verified(this, copies, total - generated.length);
    }

    private static String hashBytes(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static int upperBound(int[] a, int x) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] <= x) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
    public static final class Builder {
        private final SyntaxTree tree;
        private final Map<String, SourceIdentity> sourceIds = new LinkedHashMap<>();
        private int[] sourceIndex = new int[16], from = new int[16], to = new int[16];
        private int[] originalStart = new int[16], originalEnd = new int[16];
        private byte[] relation = new byte[16];
        private int size;
        private boolean frozen;
        private Builder(SyntaxTree tree) { this.tree = Objects.requireNonNull(tree, "tree"); }
        public Builder add(int generatedStart, int generatedEnd, String sourceId,
                           String sourceSha256, int sourceStart, int sourceEnd,
                           Relation relation) {
            if (frozen) throw new IllegalStateException("source origin sidecar already built");
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(sourceSha256, "sourceSha256");
            Objects.requireNonNull(relation, "relation");
            if (sourceId.isBlank() || sourceId.length() > 2048 || sourceId.contains("\0")
                    || !sourceSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("invalid source identity");
            if (relation == Relation.EXACT_BYTES
                    && generatedEnd - generatedStart != sourceEnd - sourceStart)
                throw new IllegalArgumentException("exact source bytes require equal range widths");
            if (generatedStart < 0 || generatedEnd <= generatedStart
                    || generatedEnd > tree.sourceLength()
                    || sourceStart < 0 || sourceEnd < sourceStart
                    || (size > 0 && generatedStart < from[size - 1]))
                throw new IllegalArgumentException("invalid or unordered origin coordinates");
            SourceIdentity known = sourceIds.get(sourceId);
            if (known != null && !known.sha256().equals(sourceSha256))
                throw new IllegalArgumentException("conflicting content generation for source id");
            // Complete capacity/validation checks before admitting a source ID.
            if (size == from.length) grow();
            if (known == null) {
                known = new SourceIdentity(sourceIds.size(), sourceSha256);
                sourceIds.put(sourceId, known);
            }
            sourceIndex[size] = known.index();
            from[size] = generatedStart; to[size] = generatedEnd;
            originalStart[size] = sourceStart; originalEnd[size] = sourceEnd;
            this.relation[size] = (byte) relation.ordinal();
            size++;
            return this;
        }
        public SyntaxOrigins freeze() {
            if (frozen) throw new IllegalStateException("source origins already frozen");
            frozen = true;
            return new SyntaxOrigins(this);
        }
        private void grow() {
            int next = Math.addExact(size, (size >>> 1) + 1);
            sourceIndex = Arrays.copyOf(sourceIndex, next);
            from = Arrays.copyOf(from, next); to = Arrays.copyOf(to, next);
            originalStart = Arrays.copyOf(originalStart, next);
            originalEnd = Arrays.copyOf(originalEnd, next);
            relation = Arrays.copyOf(relation, next);
        }
    }
}
