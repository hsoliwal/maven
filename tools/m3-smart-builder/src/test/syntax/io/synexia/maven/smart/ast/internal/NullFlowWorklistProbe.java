// Copyright 2026 Hitesh Soliwal and contributors
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.ast.internal;

import java.util.Arrays;
import java.util.SplittableRandom;

/** Independent scalar CFG fixed-point oracle, runnable without Maven/JUnit. */
//FLLDCIM: RELOCATE - copy of M3JDK m3/ast/src/test/java/jdk/internal/m3/ast/NullFlowWorklistProbe.java at b7539452eb6c
public final class NullFlowWorklistProbe {
    private static long assertions;

    private NullFlowWorklistProbe() { }

    public static void main(String[] args) {
        System.out.println("PASS NULL_FLOW_WORKLIST scalar_contract_assertions=" + prove());
    }

    public static long prove() {
        assertions = 0;
        deterministicCases();
        SplittableRandom random = new SplittableRandom(0x574F_524B_4C49_5354L);
        int[] nodeSizes = {1, 2, 3, 7, 12, 32};
        int[] variableSizes = {0, 1, 2, 7, 31, 32, 33, 65};
        for (int nodes : nodeSizes) {
            for (int variables : variableSizes) {
                for (int trial = 0; trial < 110; trial++) {
                    int edges = random.nextInt(nodes * 3 + 1);
                    int[] source = new int[edges];
                    int[] target = new int[edges];
                    int[] slot = new int[edges];
                    byte[] operation = new byte[edges];
                    for (int e = 0; e < edges; e++) {
                        source[e] = random.nextInt(nodes);
                        target[e] = random.nextInt(nodes);
                        operation[e] = (byte) (variables == 0 ? 0 : random.nextInt(6));
                        slot[e] = operation[e] == 0 ? -1 : random.nextInt(variables);
                    }

                    int start = random.nextInt(nodes);
                    NullFlowFacts entry = new NullFlowFacts(variables);
                    int[] originalEntry = new int[variables];
                    for (int i = 0; i < variables; i++) {
                        originalEntry[i] = random.nextInt(3);
                        entry.set(i, originalEntry[i]);
                    }
                    boolean startReachable = random.nextInt(17) != 0;
                    if (!startReachable) entry.makeUnreachable();
                    NullFlowFacts retainedEntry = entry.copy();

                    // Construction must retain private edge copies, not caller arrays.
                    int[] frozenSource = source.clone();
                    int[] frozenTarget = target.clone();
                    int[] frozenSlot = slot.clone();
                    byte[] frozenOperation = operation.clone();
                    NullFlowWorklist worklist = new NullFlowWorklist(
                            nodes, variables, source, target, slot, operation);
                    Arrays.fill(source, -7);
                    Arrays.fill(target, -7);
                    Arrays.fill(slot, -7);
                    Arrays.fill(operation, (byte) -7);

                    ScalarResult expected = scalar(nodes, variables, frozenSource,
                            frozenTarget, frozenSlot, frozenOperation, start,
                            startReachable, originalEntry);
                    NullFlowFacts[] actual = worklist.solve(start, entry);
                    verify(actual, expected, variables);
                    equal(retainedEntry, entry);
                    equal(nodes, worklist.nodeCount());
                    equal(edges, worklist.edgeCount());
                    verify(worklist.solve(start, entry), expected, variables);

                    if (trial % 7 == 0) {
                        shuffle(random, frozenSource, frozenTarget, frozenSlot, frozenOperation);
                        NullFlowFacts[] shuffled = new NullFlowWorklist(nodes, variables,
                                frozenSource, frozenTarget, frozenSlot, frozenOperation)
                                .solve(start, entry);
                        verify(shuffled, expected, variables);
                    }
                }
            }
        }
        invalidCases();
        return assertions;
    }

    private static void deterministicCases() {
        NullFlowWorklist diamond = new NullFlowWorklist(4, 1,
                new int[] {0, 0, 1, 2}, new int[] {1, 2, 3, 3},
                new int[] {0, 0, -1, -1},
                new byte[] {1, 2, 0, 0});
        NullFlowFacts[] joined = diamond.solve(0, new NullFlowFacts(1));
        equal(NullFlowFacts.NULL, joined[1].state(0));
        equal(NullFlowFacts.NONNULL, joined[2].state(0));
        equal(NullFlowFacts.UNKNOWN, joined[3].state(0));

        NullFlowWorklist blocked = new NullFlowWorklist(3, 1,
                new int[] {0, 1, 1}, new int[] {1, 1, 2},
                new int[] {0, -1, 0}, new byte[] {1, 0, 5});
        NullFlowFacts[] frames = blocked.solve(0, new NullFlowFacts(1));
        equal(true, frames[1].reachable());
        equal(false, frames[2].reachable());

        NullFlowWorklist zero = new NullFlowWorklist(2, 0,
                new int[] {0, 1}, new int[] {1, 0},
                new int[] {-1, -1}, new byte[] {0, 0});
        frames = zero.solve(0, new NullFlowFacts(0));
        equal(true, frames[0].reachable());
        equal(true, frames[1].reachable());
        equal(0, frames[1].snapshotWords().length);
    }

    private static void invalidCases() {
        int[] source = {0};
        int[] target = {1};
        int[] slot = {-1};
        byte[] action = {0};
        fails(IllegalArgumentException.class,
                () -> new NullFlowWorklist(0, 1, source, target, slot, action));
        fails(IllegalArgumentException.class,
                () -> new NullFlowWorklist(2, -1, source, target, slot, action));
        fails(IllegalArgumentException.class,
                () -> new NullFlowWorklist(2, 1, source, new int[0], slot, action));
        fails(NullPointerException.class,
                () -> new NullFlowWorklist(2, 1, null, target, slot, action));
        fails(IndexOutOfBoundsException.class,
                () -> new NullFlowWorklist(1, 1, source, target, slot, action));
        fails(IllegalArgumentException.class,
                () -> new NullFlowWorklist(2, 1, source, target, new int[] {0}, action));
        fails(IndexOutOfBoundsException.class,
                () -> new NullFlowWorklist(2, 1, source, target, new int[] {1}, new byte[] {2}));
        fails(IllegalArgumentException.class,
                () -> new NullFlowWorklist(2, 1, source, target, slot, new byte[] {9}));

        NullFlowWorklist graph = new NullFlowWorklist(2, 1, source, target, slot, action);
        fails(IndexOutOfBoundsException.class, () -> graph.solve(-1, new NullFlowFacts(1)));
        fails(IndexOutOfBoundsException.class, () -> graph.solve(2, new NullFlowFacts(1)));
        fails(NullPointerException.class, () -> graph.solve(0, null));
        fails(IllegalArgumentException.class, () -> graph.solve(0, new NullFlowFacts(2)));

        NullFlowFacts acc = new NullFlowFacts(1);
        NullFlowFacts incoming = new NullFlowFacts(1);
        incoming.set(0, NullFlowFacts.NONNULL);
        equal(false, acc.mergeFrom(incoming));
        equal(NullFlowFacts.UNKNOWN, acc.state(0));
        equal(false, acc.mergeFrom(incoming));
        equal(false, acc.mergeFrom(acc));
        acc.set(0, NullFlowFacts.NULL);
        equal(true, acc.mergeFrom(incoming));
        equal(NullFlowFacts.UNKNOWN, acc.state(0));
        acc.makeUnreachable();
        equal(true, acc.mergeFrom(incoming));
        equal(incoming, acc);
        equal(false, acc.mergeFrom(acc));
        fails(IllegalArgumentException.class, () -> acc.mergeFrom(new NullFlowFacts(2)));
        fails(NullPointerException.class, () -> acc.mergeFrom(null));
    }

    /** Deliberately object/array-oriented and structurally unlike the packed solver. */
    private static ScalarResult scalar(int nodes, int variables, int[] from, int[] to,
            int[] slot, byte[] action, int start, boolean startsReachable, int[] initial) {
        boolean[] live = new boolean[nodes];
        int[][] state = new int[nodes][variables];
        if (startsReachable) {
            live[start] = true;
            state[start] = initial.clone();
        }
        long maximumRounds = 2L * nodes * (variables + 1L) + 2;
        boolean stable = false;
        for (long round = 0; round < maximumRounds; round++) {
            boolean changed = false;
            for (int e = 0; e < from.length; e++) {
                if (!live[from[e]]) continue;
                int[] out = state[from[e]].clone();
                int v = slot[e];
                boolean legal = true;
                switch (action[e]) {
                    case 0 -> { }
                    case 1 -> out[v] = 1;
                    case 2 -> out[v] = 2;
                    case 3 -> out[v] = 0;
                    case 4 -> {
                        legal = out[v] != 2;
                        if (legal) out[v] = 1;
                    }
                    case 5 -> {
                        legal = out[v] != 1;
                        if (legal) out[v] = 2;
                    }
                    default -> throw new AssertionError("invalid test fixture");
                }
                if (!legal) continue;
                int dest = to[e];
                if (!live[dest]) {
                    live[dest] = true;
                    state[dest] = out;
                    changed = true;
                } else {
                    for (int i = 0; i < variables; i++) {
                        int merged = state[dest][i] == out[i] ? out[i] : 0;
                        if (merged != state[dest][i]) {
                            state[dest][i] = merged;
                            changed = true;
                        }
                    }
                }
            }
            if (!changed) {
                stable = true;
                break;
            }
        }
        if (!stable) throw new AssertionError("scalar oracle did not converge");
        return new ScalarResult(live, state);
    }

    private record ScalarResult(boolean[] live, int[][] state) { }

    private static void verify(NullFlowFacts[] actual, ScalarResult scalar, int variables) {
        equal(scalar.live().length, actual.length);
        for (int node = 0; node < actual.length; node++) {
            equal(scalar.live()[node], actual[node].reachable());
            equal(variables, actual[node].variableCount());
            if (scalar.live()[node]) {
                for (int v = 0; v < variables; v++) {
                    equal(scalar.state()[node][v], actual[node].state(v));
                }
            }
        }
    }

    private static void shuffle(SplittableRandom random, int[] from, int[] to,
            int[] slot, byte[] action) {
        for (int i = from.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            swap(from, i, j);
            swap(to, i, j);
            swap(slot, i, j);
            byte a = action[i];
            action[i] = action[j];
            action[j] = a;
        }
    }

    private static void swap(int[] array, int i, int j) {
        int v = array[i];
        array[i] = array[j];
        array[j] = v;
    }

    private static void equal(Object expected, Object actual) {
        assertions++;
        if (!expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }

    private static void fails(Class<? extends Throwable> expected, Runnable action) {
        assertions++;
        try {
            action.run();
        } catch (Throwable caught) {
            if (expected.isInstance(caught)) return;
            throw new AssertionError("wrong refusal", caught);
        }
        throw new AssertionError("expected " + expected.getSimpleName());
    }
}
