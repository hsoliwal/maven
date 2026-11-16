// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.ast;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Objects;

/**
 * Setup-inclusive, source-bound performance *qualification*, not a pass/fail
 * JMH replacement. Compares the actual public Facts.first() against the
 * original array scan over the same immutable source-projection sidecars.
 */
public final class FactsFirstPerformanceProof {
    private static final int SAMPLES = 5;
    private static final int ITERATIONS = 20_000;
    private static volatile long blackhole;

    private FactsFirstPerformanceProof() { }

    /**
     * Capture existing private primitive columns ONCE. No reflective access
     * happens in the measured baseline loop. These are the exact fields that
     * the pre-optimization implementation used to scan.
     */
    private record OriginalScan(int[] begins, int[] counts, int[] nameIds,
                                int[] valueIds, String[] names, String[] values) {
        static OriginalScan of(SyntaxIndex.Facts facts) throws ReflectiveOperationException {
            return new OriginalScan((int[]) field(facts, "begins"),
                    (int[]) field(facts, "counts"),
                    (int[]) field(facts, "nameIds"),
                    (int[]) field(facts, "valueIds"),
                    (String[]) field(facts, "names"),
                    (String[]) field(facts, "values"));
        }

        String first(int node, String name) {
            Objects.requireNonNull(name, "name");
            int from = begins[Objects.checkIndex(node, begins.length)];
            for (int i = 0; i < counts[node]; i++) {
                int row = from + i;
                if (names[nameIds[row]].equals(name)) return values[valueIds[row]];
            }
            return null;
        }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static SyntaxTree singleNodeTree() {
        var builder = SyntaxTree.builder("m3.facts.first.perf/1", new byte[] {'x'});
        builder.open("file", "", 0, SyntaxTree.NAMED);
        builder.close(1);
        return builder.build();
    }

    private static SyntaxIndex.Facts facts(int rows, String[] properties) {
        SyntaxTree tree = singleNodeTree();
        SyntaxIndex.FactBuilder builder = SyntaxIndex.facts(tree);
        for (int i = 0; i < rows; i++) {
            builder.add(0, "bench", properties[i % properties.length], "v" + i);
        }
        SyntaxIndex.Facts result = builder.freeze();
        result.requireSource(tree);
        if (result.size() != rows || result.count(0) != rows) {
            throw new AssertionError("wrong current-owner semantic facts image");
        }
        return result;
    }

    private static String[] sequentialNames(int length) {
        String[] names = new String[length];
        for (int i = 0; i < names.length; i++) names[i] = "key" + i;
        return names;
    }

    /** "Aa" and "BB" are distinct equal-hash strings, recursively. */
    private static String[] collidingNames(int levels) {
        String[] names = {""};
        for (int i = 0; i < levels; i++) {
            String[] next = new String[names.length * 2];
            for (int j = 0; j < names.length; j++) {
                next[j * 2] = names[j] + "Aa";
                next[j * 2 + 1] = names[j] + "BB";
            }
            names = next;
        }
        for (String name : names) {
            if (name.hashCode() != names[0].hashCode()) {
                throw new AssertionError("invalid hash collision fixture");
            }
        }
        return names;
    }

    private static boolean indexed(SyntaxIndex.Facts facts) throws ReflectiveOperationException {
        return field(facts, "firstLookup") != null;
    }

    private static boolean refused(SyntaxIndex.Facts facts) throws ReflectiveOperationException {
        return (boolean) field(facts, "firstIndexRefused");
    }

    private static int tableBytes(SyntaxIndex.Facts facts) throws ReflectiveOperationException {
        Object cache = field(facts, "firstLookup");
        if (cache == null) return 0;
        return ((int[]) field(cache, "slots")).length * Integer.BYTES;
    }

    private static long run(SyntaxIndex.Facts facts, OriginalScan baseline,
                            String[] requests, boolean original) {
        long sum = 0;
        long start = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            String key = requests[i % requests.length];
            String value = original ? baseline.first(0, key) : facts.first(0, key);
            sum += value == null ? 0 : value.hashCode();
        }
        long elapsed = System.nanoTime() - start;
        blackhole = sum;
        return elapsed;
    }

    private static long median(long[] samples) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static void compare(SyntaxIndex.Facts facts, OriginalScan baseline,
                                String[] requests) {
        for (String request : requests) {
            String a = baseline.first(0, request);
            String b = facts.first(0, request);
            if (!Objects.equals(a, b)) {
                throw new AssertionError("current-owner lookup mismatch: " + request);
            }
        }
    }

    private static void timedCase(String label, SyntaxIndex.Facts facts,
                                  OriginalScan baseline, String[] requests) {
        compare(facts, baseline, requests);
        for (int i = 0; i < 4000; i++) {
            String query = requests[i % requests.length];
            baseline.first(0, query);
            facts.first(0, query);
        }
        long[] original = new long[SAMPLES], candidate = new long[SAMPLES];
        for (int sample = 0; sample < SAMPLES; sample++) {
            // Alternate execution order to reduce deterministic warm-cache bias.
            if ((sample & 1) == 0) {
                original[sample] = run(facts, baseline, requests, true);
                candidate[sample] = run(facts, baseline, requests, false);
            } else {
                candidate[sample] = run(facts, baseline, requests, false);
                original[sample] = run(facts, baseline, requests, true);
            }
        }
        long oldNs = median(original) / ITERATIONS;
        long newNs = median(candidate) / ITERATIONS;
        double ratio = newNs == 0 ? Double.NaN : (double) oldNs / newNs;
        System.out.printf("BENCH\t%s\trows=%d\tqueries=%d\tbaseline_ns_per_query=%d"
                        + "\tindexed_ns_per_query=%d\tbaseline_over_indexed=%.2f%n",
                label, facts.size(), ITERATIONS, oldNs, newNs, ratio);
    }

    public static void main(String[] args) throws Exception {
        String[] properties = sequentialNames(1024);
        SyntaxIndex.Facts hot = facts(4096, properties);
        OriginalScan original = OriginalScan.of(hot);

        // All under this sample's initial 16 calls are cold; the 16th
        // qualifies for the optional index. Measure only that transition.
        for (int i = 0; i < 15; i++) {
            compare(hot, original, new String[] {"bench:key1"});
        }
        if (indexed(hot)) throw new AssertionError("cold table allocated early");
        long begin = System.nanoTime();
        compare(hot, original, new String[] {"bench:key1023"});
        long buildNs = System.nanoTime() - begin;
        if (!indexed(hot) || refused(hot)) {
            throw new AssertionError("ordinary repeated workload did not build index");
        }
        int bytes = tableBytes(hot);
        if (bytes <= 0 || bytes > 2 * 1024 * 1024) {
            throw new AssertionError("primitive table memory admission exceeded: " + bytes);
        }
        System.out.println("BUILD\trows=4096\tbuild_ns=" + buildNs + "\tslots_bytes=" + bytes);

        timedCase("early_match", hot, original, new String[] {"bench:key0"});
        timedCase("late_match", hot, original, new String[] {"bench:key1023"});
        timedCase("missing", hot, original, new String[] {"bench:missing"});
        timedCase("mixed", hot, original,
                new String[] {"bench:key0", "bench:key511", "bench:key1023", "bench:missing"});

        SyntaxIndex.Facts cold = facts(160, sequentialNames(16));
        OriginalScan coldScan = OriginalScan.of(cold);
        for (int i = 0; i < 200; i++) compare(cold, coldScan, new String[] {"bench:key5"});
        if (indexed(cold) || refused(cold)) {
            throw new AssertionError("small workload must retain only original scan");
        }

        String[] collisions = collidingNames(8);
        SyntaxIndex.Facts hostile = facts(4096, collisions);
        OriginalScan hostileScan = OriginalScan.of(hostile);
        for (int i = 0; i < 512; i++) {
            compare(hostile, hostileScan,
                    new String[] {"bench:" + collisions[i % collisions.length]});
        }
        if (indexed(hostile) || !refused(hostile)) {
            throw new AssertionError("hash-collision source failed to refuse optional index");
        }
        timedCase("collision_fallback", hostile, hostileScan,
                new String[] {"bench:" + collisions[0], "bench:" + collisions[127],
                        "bench:" + collisions[255], "bench:absent"});

        SyntaxIndex.Facts huge = facts((1 << 18) + 1, new String[] {"one"});
        OriginalScan hugeScan = OriginalScan.of(huge);
        for (int i = 0; i < 20; i++) compare(huge, hugeScan, new String[] {"bench:one"});
        if (indexed(huge) || refused(huge)) {
            throw new AssertionError("over-limit workload must bypass cache construction");
        }

        if (blackhole == Long.MIN_VALUE) throw new AssertionError("unreachable sink");
        System.out.println("PASS AST_FACTS_FIRST_PERFORMANCE_PROOF"
                + " memory_guard_bytes=" + bytes
                + " colliding_keys=" + collisions.length
                + " samples=" + SAMPLES
                + " query_iterations=" + ITERATIONS
                + " benchmark_timings_advisory=true");
    }
}
