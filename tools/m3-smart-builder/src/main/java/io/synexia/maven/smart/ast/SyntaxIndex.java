// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and contributors
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.ast;

import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Optional indexed queries, position-independent syntax-subtree fingerprints,
 * and source-sealed semantic sidecars for the existing SyntaxTree owner.
 * No new syntax parser, AST graph, per-node object, or rewrite authority.
 *
 * <p>The bottom-up fingerprint hashes the original source fragments not covered
 * by child nodes, followed by each child's position-independent fingerprint.
 * A byte participates in the digest of its deepest owning node only; every
 * parent hashes 32-byte child digests instead of repeatedly scanning source.
 * Fields, error/missing states and punctuation/trivia in gaps are preserved.
 * Fingerprints nominate reuse; they cannot prove semantic or ABI equivalence.
 */
public final class SyntaxIndex {
    private static final int HASH_BYTES = 32;
    private final SyntaxTree tree;
    private final byte[] digests;
    private volatile KindPostings kindPostings;

    public SyntaxIndex(SyntaxTree tree) {
        this.tree = Objects.requireNonNull(tree, "tree");
        int size = tree.size();
        this.digests = new byte[Math.multiplyExact(size, HASH_BYTES)];
        byte[] source = tree.sourceBytes(); // one transient source read; no retained duplicate
        MessageDigest hash = sha256(); // builder-confined cursor, one digest for every row
        for (int node = size - 1; node >= 0; node--) {
            hash.reset();
            text(hash, "m3.syntax.subtree/1");
            text(hash, tree.grammar());
            text(hash, tree.kind(node));
            text(hash, tree.field(node));
            integer(hash, tree.flags(node));
            int cursor = tree.startByte(node);
            int count = 0;
            for (int child = tree.firstChild(node); child >= 0; child = tree.nextSibling(child)) {
                int childStart = tree.startByte(child), childEnd = tree.endByte(child);
                if (childStart < cursor || childEnd < childStart || childEnd > tree.endByte(node)) {
                    throw new IllegalArgumentException("non-nested source spans at syntax node " + child);
                }
                chunk(hash, source, cursor, childStart);
                hash.update((byte) 0x7f); // explicit child boundary
                hash.update(digests, HASH_BYTES * child, HASH_BYTES);
                cursor = childEnd;
                count++;
            }
            chunk(hash, source, cursor, tree.endByte(node));
            integer(hash, count);
            // Write the exact V1 digest into its existing primitive sidecar; no per-node byte[32].
            try {
                hash.digest(digests, HASH_BYTES * node, HASH_BYTES);
            } catch (DigestException impossible) {
                throw new AssertionError(impossible);
            }
        }
    }

    public SyntaxTree tree() { return tree; }
    public String subtreeSha256(int node) {
        Objects.checkIndex(node, tree.size());
        return HexFormat.of().formatHex(digests, HASH_BYTES * node, HASH_BYTES * (node + 1));
    }
    public String rootSha256() { return subtreeSha256(0); }
    public boolean sameCandidateSubtree(int node, SyntaxIndex other, int otherNode) {
        Objects.requireNonNull(other, "other");
        Objects.checkIndex(node, tree.size());
        Objects.checkIndex(otherNode, other.tree.size());
        // Hash match is a non-authoritative reuse *candidate*, not semantic equivalence.
        return Arrays.equals(digests, node * HASH_BYTES, (node + 1) * HASH_BYTES,
                other.digests, otherNode * HASH_BYTES, (otherNode + 1) * HASH_BYTES);
    }
    public int firstChildWithField(int parent, String fieldName) {
        Objects.checkIndex(parent, tree.size());
        Objects.requireNonNull(fieldName, "fieldName");
        for (int child = tree.firstChild(parent); child >= 0; child = tree.nextSibling(child)) {
            if (tree.field(child).equals(fieldName)) return child;
        }
        return -1;
    }
    /** Source-order occurrence handles, not per-node heap instances. */
    //FLLDCIM: MRL - m3ast-kind-postings-20261030: lazy CSR kind postings answer every query after the first by binary search; same source-ordered result (hsoliwal/com.synexia ca1aafc4d94f, postimage blob 160807341996); recipe m3/ast/recipe/syntax-index-facts-20261106/apply.py v1
    public int[] nodesOfKind(String grammarKind) {
        Objects.requireNonNull(grammarKind, "grammarKind");
        KindPostings local = kindPostings;
        if (local == null) {
            synchronized (this) {
                local = kindPostings;
                if (local == null) {
                    local = KindPostings.build(tree);
                    kindPostings = local;
                }
            }
        }
        return local.nodesOfKind(grammarKind);
    }

    /**
     * Lazy, immutable CSR occurrence lanes in parser source order. The transient
     * kind dictionary is discarded after construction; readers see only compact
     * primitive offsets and one string key per grammar kind, never node objects.
     */
    //FLLDCIM: MRL - m3ast-kind-postings-20261030: immutable kind lanes (sorted keys, begins, counts, source-ordered nodes) built once per index (hsoliwal/com.synexia ca1aafc4d94f, postimage blob 160807341996); recipe m3/ast/recipe/syntax-index-facts-20261106/apply.py v1
    private static final class KindPostings {
        private final String[] keys;
        private final int[] begins;
        private final int[] counts;
        private final int[] nodes;
        private KindPostings(String[] keys, int[] begins, int[] counts, int[] nodes) {
            this.keys = keys;
            this.begins = begins;
            this.counts = counts;
            this.nodes = nodes;
        }
        private static KindPostings build(SyntaxTree tree) {
            Map<String, Integer> names = new LinkedHashMap<>();
            for (int node = 0; node < tree.size(); node++) {
                String kind = tree.kind(node);
                if (!names.containsKey(kind)) names.put(kind, names.size());
            }
            String[] keys = names.keySet().toArray(String[]::new);
            Arrays.sort(keys);
            for (int i = 0; i < keys.length; i++) names.put(keys[i], i);
            int[] counts = new int[keys.length];
            for (int node = 0; node < tree.size(); node++) counts[names.get(tree.kind(node))]++;
            int[] begins = new int[keys.length];
            int next = 0;
            for (int i = 0; i < begins.length; i++) {
                begins[i] = next;
                next = Math.addExact(next, counts[i]);
            }
            int[] cursor = begins.clone();
            int[] nodes = new int[tree.size()];
            for (int node = 0; node < tree.size(); node++) {
                nodes[cursor[names.get(tree.kind(node))]++] = node;
            }
            return new KindPostings(keys, begins, counts, nodes);
        }
        private int[] nodesOfKind(String grammarKind) {
            int id = Arrays.binarySearch(keys, grammarKind);
            if (id < 0) return new int[0];
            return Arrays.copyOfRange(nodes, begins[id], begins[id] + counts[id]);
        }
    }

    /**
     * Metadata never alters syntax bytes or claims that different language
     * binding types have equivalent meanings. Keys are provider-namespaced.
     * The sidecar refuses a different syntax projection / source generation.
     */
    public static final class Facts {
        private final String projectionSha256;
        private final int[] nodes, nameIds, valueIds, begins, counts;
        private final String[] names, values;
        private volatile FirstLookup firstLookup;
        private volatile int firstRequests;
        private volatile boolean firstIndexRefused;
        private Facts(String seal, int[] nodes, int[] nameIds, int[] valueIds,
                      String[] names, String[] values, int[] begins, int[] counts) {
            this.projectionSha256 = seal;
            this.nodes = nodes; this.nameIds = nameIds; this.valueIds = valueIds;
            this.names = names; this.values = values; this.begins = begins; this.counts = counts;
        }
        public int size() { return nodes.length; }
        public String projectionSha256() { return projectionSha256; }
        public void requireSource(SyntaxTree expected) {
            if (!projectionSha256.equals(Objects.requireNonNull(expected, "expected").projectionSha256()))
                throw new IllegalStateException("stale semantic attribution or different parser projection");
        }
        public int count(int node) { return counts[Objects.checkIndex(node, begins.length)]; }
        public String key(int node, int ordinal) {
            int at = row(node, ordinal);
            return names[nameIds[at]];
        }
        public String value(int node, int ordinal) {
            int at = row(node, ordinal);
            return values[valueIds[at]];
        }
        private int row(int node, int ordinal) {
            Objects.checkIndex(ordinal, count(node));
            return begins[node] + ordinal;
        }
        /**
         * Small and infrequent queries retain the original scan; only repeated
         * large-node queries manufacture the bounded primitive first-row index.
         */
        public String first(int node, String name) {
            Objects.requireNonNull(name, "name");
            int from = begins[Objects.checkIndex(node, begins.length)];
            FirstLookup ready = firstLookup;
            if (ready != null) return ready.first(this, node, name);
            if (!firstIndexRefused && nodes.length >= 256
                    && nodes.length <= (1 << 18) && counts[node] >= 16) {
                if (firstRequests < 16) firstRequests++;
                if (firstRequests >= 16) {
                    synchronized (this) {
                        ready = firstLookup;
                        if (ready == null && !firstIndexRefused) {
                            ready = FirstLookup.tryBuild(this);
                            if (ready == null) firstIndexRefused = true;
                            else firstLookup = ready;
                        }
                    }
                    if (ready != null) return ready.first(this, node, name);
                }
            }
            return scanFirst(node, name, from);
        }

        private String scanFirst(int node, String name, int from) {
            for (int i = 0; i < counts[node]; i++) {
                int row = from + i;
                if (names[nameIds[row]].equals(name)) return values[valueIds[row]];
            }
            return null;
        }

        /**
         * Immutable primitive open-addressing index. Each occupied slot records
         * the first source-order row of one (node, exact name) pair. Hash
         * collisions are always resolved by exact String.equals.
         */
        private static final class FirstLookup {
            private static final int MAX_PROBES = 32;
            private final int[] slots;
            private FirstLookup(int[] slots) { this.slots = slots; }

            private static int mixed(int node, int stringHash) {
                int value = (node * 0x9E3779B9) ^ stringHash;
                value ^= value >>> 16;
                value *= 0x7feb352d;
                value ^= value >>> 15;
                return value;
            }

            private static FirstLookup tryBuild(Facts facts) {
                int length = 1;
                // Caller bounds the fact count to 2^18 before index manufacture.
                while (length < facts.nodes.length * 2) length <<= 1;
                int[] slots = new int[length];
                Arrays.fill(slots, -1);
                int mask = length - 1;
                for (int row = 0; row < facts.nodes.length; row++) {
                    int node = facts.nodes[row];
                    String name = facts.names[facts.nameIds[row]];
                    int slot = mixed(node, name.hashCode()) & mask;
                    int probes = 0;
                    while (slots[slot] >= 0) {
                        int previous = slots[slot];
                        if (facts.nodes[previous] == node
                                && facts.names[facts.nameIds[previous]].equals(name)) break;
                        // Do not spend quadratic time manufacturing an optional index.
                        if (++probes >= MAX_PROBES) return null;
                        slot = (slot + 1) & mask;
                    }
                    if (slots[slot] < 0) slots[slot] = row;
                }
                return new FirstLookup(slots);
            }

            private String first(Facts facts, int node, String name) {
                int mask = slots.length - 1;
                int slot = mixed(node, name.hashCode()) & mask;
                int probes = 0;
                while (slots[slot] >= 0 && probes++ < MAX_PROBES) {
                    int row = slots[slot];
                    if (facts.nodes[row] == node
                            && facts.names[facts.nameIds[row]].equals(name)) {
                        return facts.values[facts.valueIds[row]];
                    }
                    slot = (slot + 1) & mask;
                }
                // Even an absent key sharing a crowded hash bucket must remain exact.
                return probes > MAX_PROBES
                        ? facts.scanFirst(node, name, facts.begins[node]) : null;
            }
        }
    }
    public static FactBuilder facts(SyntaxTree tree) { return new FactBuilder(tree); }
    public static final class FactBuilder {
        private final SyntaxTree tree;
        private static final int[] EMPTY_FACTS = new int[0];
        private int[] factNodes = EMPTY_FACTS, factKeys = EMPTY_FACTS, factValues = EMPTY_FACTS;
        private int factSize;
        private final Map<String, Integer> keys = new LinkedHashMap<>();
        private final Map<String, Integer> values = new LinkedHashMap<>();
        private boolean frozen;
        private FactBuilder(SyntaxTree tree) { this.tree = Objects.requireNonNull(tree, "tree"); }
        public FactBuilder add(int node, String provider, String property, String value) {
            if (frozen) throw new IllegalStateException("metadata already frozen");
            Objects.checkIndex(node, tree.size());
            String qualified = component(provider) + ":" + component(property);
            String stored = Objects.requireNonNull(value, "value");
            int key = keys.computeIfAbsent(qualified, ignored -> keys.size());
            int val = values.computeIfAbsent(stored, ignored -> values.size());
            ensureFactCapacity();
            factNodes[factSize] = node;
            factKeys[factSize] = key;
            factValues[factSize] = val;
            factSize++;
            return this;
        }
        //FLLDCIM: MRL - m3ast-facts-stable-scatter-20261030: stable count-prefix scatter replaces the (node, insertion) comparator sort; same sidecar columns (hsoliwal/com.synexia ca1aafc4d94f, postimage blob a640939625b1); recipe m3/ast/recipe/syntax-index-facts-20261106/apply.py v1
        public Facts freeze() {
            if (frozen) throw new IllegalStateException("metadata already frozen");
            frozen = true;
            int[] nodes = new int[factSize], names = new int[factSize], valuesAt = new int[factSize];
            int[] begins = new int[tree.size()], counts = new int[tree.size()];
            for (int i = 0; i < factSize; i++) counts[factNodes[i]]++;
            int next = 0;
            for (int i = 0; i < begins.length; i++) {
                begins[i] = next;
                next = Math.addExact(next, counts[i]);
            }
            // Retain the original insertion order while scattering primitive
            // columns. Prefix starts are restored after their use as cursors.
            for (int i = 0; i < factSize; i++) {
                int node = factNodes[i];
                int at = begins[node]++;
                nodes[at] = node;
                names[at] = factKeys[i];
                valuesAt[at] = factValues[i];
            }
            for (int i = 0; i < begins.length; i++) begins[i] -= counts[i];
            return new Facts(tree.projectionSha256(), nodes, names, valuesAt,
                    keys.keySet().toArray(String[]::new), values.keySet().toArray(String[]::new),
                    begins, counts);
        }
        /**
         * Stage all three primitive column copies before publishing any lane.
         * A failing allocation leaves existing arrays and the logical size intact.
         */
        private void ensureFactCapacity() {
            if (factSize < factNodes.length) return;
            int oldCapacity = factNodes.length;
            int nextCapacity = oldCapacity == 0 ? 4
                    : Math.addExact(oldCapacity, Math.max(1, oldCapacity >>> 1));
            int[] nextNodes = Arrays.copyOf(factNodes, nextCapacity);
            int[] nextKeys = Arrays.copyOf(factKeys, nextCapacity);
            int[] nextValues = Arrays.copyOf(factValues, nextCapacity);
            factNodes = nextNodes;
            factKeys = nextKeys;
            factValues = nextValues;
        }
        private static String component(String text) {
            if (text == null || text.isBlank() || text.indexOf(':') >= 0)
                throw new IllegalArgumentException("invalid semantic namespace/key");
            return text;
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void chunk(MessageDigest hash, byte[] bytes, int start, int end) {
        integer(hash, end - start);
        hash.update(bytes, start, end - start);
    }
    private static void text(MessageDigest hash, String text) {
        integer(hash, text.length());
        // Non-lossy framing for admitted Java strings including unpaired surrogates.
        for (int i = 0; i < text.length(); i++) {
            int unit = text.charAt(i);
            hash.update((byte) (unit >>> 8)); hash.update((byte) unit);
        }
    }
    private static void integer(MessageDigest hash, int value) {
        hash.update((byte) (value >>> 24)); hash.update((byte) (value >>> 16));
        hash.update((byte) (value >>> 8)); hash.update((byte) value);
    }
}
