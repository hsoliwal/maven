// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and contributors
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.ast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/** Original sorted-facts oracle vs stable in-place prefix-cursor scatter. */
//FLLDCIM: RELOCATE - m3ast-facts-stable-scatter-20261030: crate proof relocated into the module test style; path, URL and digests in m3/ast/recipe/syntax-index-facts-20261106/PROVENANCE.tsv (hsoliwal/com.synexia ca1aafc4d94f, donor blob b2af5af5dcd9); recipe m3/ast/recipe/syntax-index-facts-20261106/apply.py v1
public final class SyntaxIndexStableScatterContract {
    private record Fact(int node, int key, int value, int insertion) { }
    private record Sidecar(int[] nodes, int[] names, int[] values, int[] begins, int[] counts) {
        Sidecar {
            nodes = nodes.clone(); names = names.clone(); values = values.clone();
            begins = begins.clone(); counts = counts.clone();
        }
        int count(int node) { return counts[Objects.checkIndex(node, begins.length)]; }
        int row(int node, int ordinal) {
            Objects.checkIndex(ordinal, count(node));
            return begins[node] + ordinal;
        }
        int key(int node, int ordinal) { return names[row(node, ordinal)]; }
        int value(int node, int ordinal) { return values[row(node, ordinal)]; }
    }
    private SyntaxIndexStableScatterContract() { }

    private static Sidecar previous(List<Fact> input, int nodesCount) {
        List<Fact> facts = new ArrayList<>(input);
        facts.sort(Comparator.comparingInt(Fact::node).thenComparingInt(Fact::insertion));
        int[] nodes = new int[facts.size()], names = new int[facts.size()];
        int[] valuesAt = new int[facts.size()];
        int[] begins = new int[nodesCount], counts = new int[nodesCount];
        for (int i = 0; i < facts.size(); i++) {
            Fact fact = facts.get(i);
            nodes[i] = fact.node; names[i] = fact.key; valuesAt[i] = fact.value;
            counts[fact.node]++;
        }
        int next = 0;
        for (int i = 0; i < begins.length; i++) {
            begins[i] = next;
            next = Math.addExact(next, counts[i]);
        }
        return new Sidecar(nodes, names, valuesAt, begins, counts);
    }

    private static Sidecar candidate(List<Fact> facts, int nodesCount) {
        int[] nodes = new int[facts.size()], names = new int[facts.size()];
        int[] valuesAt = new int[facts.size()];
        int[] begins = new int[nodesCount], counts = new int[nodesCount];
        for (Fact fact : facts) counts[fact.node]++;
        int next = 0;
        for (int i = 0; i < begins.length; i++) {
            begins[i] = next;
            next = Math.addExact(next, counts[i]);
        }
        // Reuse begins[] as a transient, per-node destination cursor.
        // Serial append order is Fact.insertion order, so the scatter is stable.
        for (Fact fact : facts) {
            int at = begins[fact.node]++;
            nodes[at] = fact.node;
            names[at] = fact.key;
            valuesAt[at] = fact.value;
        }
        for (int i = 0; i < begins.length; i++) begins[i] -= counts[i];
        return new Sidecar(nodes, names, valuesAt, begins, counts);
    }

    private static long compare(List<Fact> input, int nodeCount, long[] cases) {
        Sidecar old = previous(input, nodeCount), now = candidate(input, nodeCount);
        if (!Arrays.equals(old.nodes, now.nodes) || !Arrays.equals(old.names, now.names)
                || !Arrays.equals(old.values, now.values) || !Arrays.equals(old.begins, now.begins)
                || !Arrays.equals(old.counts, now.counts)) {
            throw new AssertionError("snapshot columns differ; nodes=" + nodeCount
                    + " facts=" + input.size());
        }
        long assertions = 5;
        for (int n = 0; n < nodeCount; n++) {
            int count = old.count(n);
            if (count != now.count(n)) throw new AssertionError("count " + n);
            assertions++;
            for (int i = 0; i < count; i++) {
                if (old.key(n, i) != now.key(n, i) || old.value(n, i) != now.value(n, i)) {
                    throw new AssertionError("stable per-node iteration " + n + " ordinal=" + i);
                }
                assertions += 2;
            }
            int node = n;
            if (failure(() -> old.key(node, count)) != IndexOutOfBoundsException.class
                    || failure(() -> now.key(node, count)) != IndexOutOfBoundsException.class) {
                throw new AssertionError("ordinal refusal " + n);
            }
            assertions += 2;
        }
        if (nodeCount > 0) {
            if (failure(() -> now.count(-1)) != IndexOutOfBoundsException.class
                    || failure(() -> now.count(nodeCount)) != IndexOutOfBoundsException.class) {
                throw new AssertionError("node refusal");
            }
            assertions += 2;
        }
        cases[0]++;
        return assertions;
    }
    private static Class<? extends Throwable> failure(Runnable call) {
        try { call.run(); return null; }
        catch (RuntimeException ex) { return ex.getClass(); }
    }

    public static void main(String[] args) {
        Random random = new Random(0x4d33415354465343L);
        long[] cases = {0};
        long assertions = 0;
        assertions += compare(List.of(), 0, cases);
        assertions += compare(List.of(), 32, cases);
        assertions += compare(List.of(new Fact(0, 9, 99, 0), new Fact(0, 9, 100, 1),
                new Fact(1, 5, 7, 2), new Fact(0, 9, 101, 3)), 5, cases);
        for (int t = 0; t < 20_000; t++) {
            int nodes = random.nextInt(257);
            int rows = nodes == 0 ? 0 : random.nextInt(600);
            ArrayList<Fact> facts = new ArrayList<>(rows);
            for (int j = 0; j < rows; j++) {
                int node = t % 5 == 0 ? 0 : t % 5 == 1 ? nodes - 1 : random.nextInt(nodes);
                facts.add(new Fact(node, random.nextInt(19), random.nextInt(37), j));
            }
            assertions += compare(facts, nodes, cases);
        }
        int nodes = 4096, rows = 300_000;
        ArrayList<Fact> large = new ArrayList<>(rows);
        for (int j = 0; j < rows; j++) {
            large.add(new Fact(j % 2 == 0 ? j % nodes : random.nextInt(nodes),
                    random.nextInt(99), random.nextInt(99), j));
        }
        assertions += compare(large, nodes, cases);
        System.out.println("PASS AST_FACT_STABLE_SCATTER maps=" + cases[0]
                + " fact_rows_large=" + rows + " assertions=" + assertions);
    }
}
