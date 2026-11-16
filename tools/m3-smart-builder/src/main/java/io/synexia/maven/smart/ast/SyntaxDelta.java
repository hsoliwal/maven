// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and contributors
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.ast;

import java.util.Arrays;
import java.util.Objects;

/**
 * Source-generation-bound, position-aware reuse CANDIDATES between two independently
 * projected syntax trees. This does not reparse, mutate a tree, retain per-node
 * objects, or transfer semantic bindings. Callers must still rerun a parser and
 * prove language/provider-specific semantics before promoting any reuse.
 *
 * <p>Aligned int lanes and a transient primitive hash table join old/new
 * occurrences by mapped half-open source spans, native grammar kind/field,
 * flags, source bytes and bottom-up syntax digest. Repeated indistinguishable
 * nodes at the same span are intentionally left unmatched rather than guessed.
 */
public final class SyntaxDelta {
    /**
     * Four-method boundary to the original source owner; deliberately independent
     * of a file image, JNI, or any particular parser package. All coordinates are bytes.
     */
    public interface SourceEditMap {
        String oldSha256();
        String newSha256();
        boolean touchesOldSpan(int startByte, int endByte);
        /** False means a right/start boundary; true means a left/end boundary. */
        int oldToNew(int byteOffset, boolean rightAffinity);
    }

    private final SyntaxIndex before, after;
    private final int[] oldToNew, newToOld;
    private final int candidates;

    private SyntaxDelta(SyntaxIndex before, SyntaxIndex after,
                          int[] oldToNew, int[] newToOld, int candidates) {
        this.before = before;
        this.after = after;
        this.oldToNew = oldToNew;
        this.newToOld = newToOld;
        this.candidates = candidates;
    }

    public static SyntaxDelta match(SyntaxIndex before, SyntaxIndex after,
                                      SourceEditMap edits) {
        Objects.requireNonNull(before, "previous syntax index");
        Objects.requireNonNull(after, "next syntax index");
        Objects.requireNonNull(edits, "source generation mapping");
        SyntaxTree prior = before.tree(), next = after.tree();
        if (!prior.grammar().equals(next.grammar()))
            throw new IllegalArgumentException("syntax grammar/provider changed");
        if (!prior.sourceSha256().equals(edits.oldSha256())
                || !next.sourceSha256().equals(edits.newSha256()))
            throw new IllegalStateException("source generation or parser projection is stale");
        int n = next.size();
        if (n > Integer.MAX_VALUE / 4)
            throw new IllegalArgumentException("syntax index exceeds integer table budget");
        int tableSize = 16;
        while (tableSize < n * 2) tableSize = Math.multiplyExact(tableSize, 2);
        int[] head = new int[tableSize];
        int[] chain = new int[n];
        Arrays.fill(head, -1);
        // Reverse input produces deterministic ascending ordinal candidates per bucket.
        for (int node = n - 1; node >= 0; node--) {
            int bucket = bucket(next.startByte(node), next.endByte(node), tableSize - 1);
            chain[node] = head[bucket];
            head[bucket] = node;
        }
        int[] oldMap = new int[prior.size()], newMap = new int[n];
        Arrays.fill(oldMap, -1);
        Arrays.fill(newMap, -1);
        //FLLDCIM: MRL - merge-loss leaf 71 (origin fb3a33474a27, retain 615c10fe7e9c): unique matches
        // A unique new occurrence can still have multiple indistinguishable
        // OLD occurrences (for example, duplicate zero-width recovery tokens).
        // Invalidate the first match when a second old occurrence is found.
        boolean[] ambiguousNew = new boolean[n];
        byte[] oldBytes = prior.sourceBytes(), newBytes = next.sourceBytes();
        int count = 0;
        for (int node = 0; node < oldMap.length; node++) {
            int from = prior.startByte(node), to = prior.endByte(node);
            if (edits.touchesOldSpan(from, to)) continue;
            int mappedStart = edits.oldToNew(from, true);
            int mappedEnd = edits.oldToNew(to, false);
            if (mappedStart < 0 || mappedEnd < mappedStart || mappedEnd > newBytes.length
                    || mappedEnd - mappedStart != to - from) continue;
            int found = -1;
            for (int candidate = head[bucket(mappedStart, mappedEnd, tableSize - 1)];
                    candidate >= 0; candidate = chain[candidate]) {
                if (next.startByte(candidate) != mappedStart || next.endByte(candidate) != mappedEnd
                        || prior.flags(node) != next.flags(candidate)
                        || !prior.kind(node).equals(next.kind(candidate))
                        || !prior.field(node).equals(next.field(candidate))
                        || !before.sameCandidateSubtree(node, after, candidate)
                        || !Arrays.equals(oldBytes, from, to, newBytes, mappedStart, mappedEnd)) continue;
                if (found >= 0) { found = -2; break; } // multiple equivalent matches: refuse
                found = candidate;
            }
            //FLLDCIM: MRL - merge-loss leaf 71 (origin fb3a33474a27, retain 615c10fe7e9c): unique matches
            if (found >= 0) {
                if (ambiguousNew[found]) continue;
                int previous = newMap[found];
                if (previous >= 0) {
                    // Do not guess which old occurrence survived a reparse.
                    // Both sides remain externally unmatched (-1).
                    oldMap[previous] = -1;
                    newMap[found] = -1;
                    ambiguousNew[found] = true;
                    count--;
                } else {
                    oldMap[node] = found;
                    newMap[found] = node;
                    count++;
                }
            }
        }
        return new SyntaxDelta(before, after, oldMap, newMap, count);
    }

    public int candidates() { return candidates; }
    public int oldNodes() { return oldToNew.length; }
    public int newNodes() { return newToOld.length; }
    public int newNodeForOld(int node) { return oldToNew[Objects.checkIndex(node, oldNodes())]; }
    public int oldNodeForNew(int node) { return newToOld[Objects.checkIndex(node, newNodes())]; }
    public boolean rootCandidate() { return newNodeForOld(0) == 0; }
    public int[] oldUnmatched() { return unmatched(oldToNew); }
    public int[] newUnmatched() { return unmatched(newToOld); }
    public String oldProjectionSha256() { return before.tree().projectionSha256(); }
    public String newProjectionSha256() { return after.tree().projectionSha256(); }
    private static int[] unmatched(int[] lane) {
        int count = 0;
        for (int node : lane) if (node < 0) count++;
        int[] result = new int[count];
        int at = 0;
        for (int i = 0; i < lane.length; i++) if (lane[i] < 0) result[at++] = i;
        return result;
    }
    private static int bucket(int start, int end, int mask) {
        long x = ((long) start << Integer.SIZE) | Integer.toUnsignedLong(end);
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= x >>> 33;
        return (int) x & mask;
    }
}
