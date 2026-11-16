// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and contributors
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.ast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Independent linear-scan oracle for a compact, source-ordered AST kind posting index. */
//FLLDCIM: RELOCATE - m3ast-kind-postings-20261030: crate proof relocated into the module test style; path, URL and digests in m3/ast/recipe/syntax-index-facts-20261106/PROVENANCE.tsv (hsoliwal/com.synexia ca1aafc4d94f, donor blob 3913bda237b3); recipe m3/ast/recipe/syntax-index-facts-20261106/apply.py v1
public final class SyntaxIndexKindPostingsContract {
    private static final class Indexed {
        private final String[] kinds;
        private volatile KindPostings postings;
        private Indexed(String[] kinds) { this.kinds = kinds.clone(); }
        int[] nodesOfKind(String kind) {
            Objects.requireNonNull(kind, "grammarKind");
            KindPostings local = postings;
            if (local == null) {
                synchronized (this) {
                    local = postings;
                    if (local == null) {
                        local = KindPostings.build(kinds);
                        postings = local;
                    }
                }
            }
            return local.nodesOfKind(kind);
        }
    }

    /** Compact, immutable CSR postings with a sorted dictionary; no per-node objects. */
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
        static KindPostings build(String[] input) {
            Map<String, Integer> names = new LinkedHashMap<>();
            for (String kind : input) {
                if (!names.containsKey(kind)) names.put(kind, names.size());
            }
            String[] keys = names.keySet().toArray(String[]::new);
            Arrays.sort(keys);
            for (int i = 0; i < keys.length; i++) names.put(keys[i], i);
            int[] counts = new int[keys.length];
            for (String kind : input) counts[names.get(kind)]++;
            int[] begins = new int[keys.length];
            int next = 0;
            for (int i = 0; i < begins.length; i++) {
                begins[i] = next;
                next = Math.addExact(next, counts[i]);
            }
            int[] cursor = begins.clone();
            int[] nodes = new int[input.length];
            for (int node = 0; node < input.length; node++) {
                nodes[cursor[names.get(input[node])]++] = node;
            }
            return new KindPostings(keys, begins, counts, nodes);
        }
        int[] nodesOfKind(String kind) {
            int id = Arrays.binarySearch(keys, kind);
            if (id < 0) return new int[0];
            return Arrays.copyOfRange(nodes, begins[id], begins[id] + counts[id]);
        }
        int uniqueKindCount() { return keys.length; }
    }

    private static int[] linear(String[] kinds, String query) {
        Objects.requireNonNull(query, "grammarKind");
        int[] result = new int[Math.min(16, kinds.length)];
        int count = 0;
        for (int i = 0; i < kinds.length; i++) {
            if (query.equals(kinds[i])) {
                if (count == result.length) result = Arrays.copyOf(result, count + (count >>> 1) + 1);
                result[count++] = i;
            }
        }
        return Arrays.copyOf(result, count);
    }

    private static long check(Indexed index, String query, int repeats) {
        int[] expected = linear(index.kinds, query);
        long checks = 0L;
        for (int j = 0; j < repeats; j++) {
            int[] actual = index.nodesOfKind(query);
            if (!Arrays.equals(expected, actual)) {
                throw new AssertionError("kind=" + query + " expected=" + Arrays.toString(expected)
                        + " actual=" + Arrays.toString(actual));
            }
            for (int i = 1; i < actual.length; i++) {
                if (actual[i - 1] >= actual[i]) throw new AssertionError("not source-ordered");
            }
            if (actual.length > 0) {
                actual[0] = -9; // returned array must not corrupt the cached index
                if (!Arrays.equals(expected, index.nodesOfKind(query))) {
                    throw new AssertionError("mutable cached results leaked");
                }
                checks++;
            }
            checks += expected.length + 1L;
        }
        return checks;
    }

    private static void requireNullRefusal(Indexed idx) {
        Class<? extends Throwable> expected, actual;
        try { linear(idx.kinds, null); throw new AssertionError("reference accepted null"); }
        catch (NullPointerException x) { expected = x.getClass(); }
        try { idx.nodesOfKind(null); throw new AssertionError("candidate accepted null"); }
        catch (NullPointerException x) { actual = x.getClass(); }
        if (expected != actual) throw new AssertionError("null behavior differs");
    }

    private static void concurrent(Indexed idx, String[] queries) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            final int threadId = t;
            threads[t] = Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 400; i++) {
                        String query = queries[(threadId * 97 + i) % queries.length];
                        int[] old = linear(idx.kinds, query);
                        int[] now = idx.nodesOfKind(query);
                        if (!Arrays.equals(old, now)) throw new AssertionError("concurrent parity");
                        if (now.length != 0) now[0] = -77;
                    }
                } catch (Throwable failure) { error.compareAndSet(null, failure); }
            });
        }
        start.countDown();
        for (Thread thread : threads) thread.join();
        if (error.get() != null) throw new AssertionError("concurrent index failure", error.get());
    }

    public static void main(String[] args) throws Exception {
        Random random = new Random(0x4d334b494e44584cL);
        String[] colliding = {"Aa", "BB", "AaAa", "BBBB", "AaBB", "BBAa"};
        long checks = 0;
        int maps = 0;
        Indexed zero = new Indexed(new String[0]);
        checks += check(zero, "missing", 3);
        requireNullRefusal(zero);
        maps++;
        for (int t = 0; t < 9_000; t++) {
            int length = random.nextInt(300);
            int domain = random.nextInt(50) + 1;
            String[] kinds = new String[length];
            ArrayList<String> queries = new ArrayList<>();
            for (int k = 0; k < domain; k++) {
                queries.add(k < colliding.length ? colliding[k] : "kind_" + k);
            }
            queries.add("never_seen_" + t);
            for (int i = 0; i < length; i++) kinds[i] = queries.get(random.nextInt(domain));
            Indexed idx = new Indexed(kinds);
            requireNullRefusal(idx);
            for (String query : queries) checks += check(idx, query, 3);
            if (idx.postings.uniqueKindCount() > kinds.length) {
                throw new AssertionError("too many keys");
            }
            maps++;
        }
        String[] kinds = new String[40_000];
        for (int i = 0; i < kinds.length; i++) {
            kinds[i] = i % 9 == 0 ? colliding[i % colliding.length] : "kind_" + (i % 97);
        }
        Indexed large = new Indexed(kinds);
        for (int i = 0; i < 15_000; i++) {
            checks += check(large, i % 4 == 0 ? "never_seen" : "kind_" + (i % 97), 2);
        }
        maps++;
        String[] domains = new String[colliding.length + 100];
        System.arraycopy(colliding, 0, domains, 0, colliding.length);
        for (int i = colliding.length; i < domains.length; i++) domains[i] = "kind_" + (i - colliding.length);
        concurrent(new Indexed(kinds), domains);
        System.out.println("PASS AST_KIND_POSTINGS maps=" + maps + " assertions=" + checks
                + " concurrency_threads=8 source_nodes_large=" + kinds.length);
    }
}
