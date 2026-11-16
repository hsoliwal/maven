// Copyright 2026 Hitesh Soliwal and contributors
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.ast.internal;

import java.util.Arrays;

/**
 * Candidate-only, method-local nullness facts for an existing AST/javac owner.
 * This class neither parses Java nor changes Java language or runtime semantics.
 * A future compiler owner must supply correctly scoped, attributed variable IDs.
 *
 * <p>Each slot has two bits: UNKNOWN=0, NULL=1, NONNULL=2. State 3 is invalid.
 * Reachability belongs to the entire control-flow frame, not an individual slot.
 * Joins retain a fact only when both reachable predecessors agree. This is a
 * conservative may-analysis: UNKNOWN never proves an expression is non-null.
 *
 * <p>One primitive word holds 32 facts. No object is retained per variable.
 * Frames are mutable, strictly method-confined, and not thread-safe.
 */
//FLLDCIM: RELOCATE - copy of M3JDK m3/ast/src/main/java/jdk/internal/m3/ast/NullFlowFacts.java at b7539452eb6c
final class NullFlowFacts {
    static final int UNKNOWN = 0;
    static final int NULL = 1;
    static final int NONNULL = 2;

    private static final long EVEN_BITS = 0x5555_5555_5555_5555L;
    private final int variableCount;
    private final long[] words;
    private boolean reachable = true;

    NullFlowFacts(int variableCount) {
        if (variableCount < 0) throw new IllegalArgumentException("negative variable count");
        this.variableCount = variableCount;
        this.words = new long[(int) (((long) variableCount + 31L) >>> 5)];
    }

    private NullFlowFacts(NullFlowFacts original) {
        this.variableCount = original.variableCount;
        this.words = original.words.clone();
        this.reachable = original.reachable;
    }

    int variableCount() {
        return variableCount;
    }

    boolean reachable() {
        return reachable;
    }

    int state(int variable) {
        checkVariable(variable);
        if (!reachable) throw new IllegalStateException("unreachable control-flow frame");
        int shift = (variable & 31) << 1;
        return (int) ((words[variable >>> 5] >>> shift) & 3L);
    }

    void set(int variable, int state) {
        checkVariable(variable);
        if (state < UNKNOWN || state > NONNULL) {
            throw new IllegalArgumentException("invalid nullness state");
        }
        if (!reachable) throw new IllegalStateException("unreachable control-flow frame");
        int word = variable >>> 5;
        int shift = (variable & 31) << 1;
        long mask = 3L << shift;
        words[word] = (words[word] & ~mask) | ((long) state << shift);
    }

    /** Refine an existing fact for a true branch of a null/non-null test. */
    boolean assume(int variable, int requiredState) {
        checkVariable(variable);
        if (requiredState != NULL && requiredState != NONNULL) {
            throw new IllegalArgumentException("assumption must be NULL or NONNULL");
        }
        if (!reachable) return false;
        int prior = state(variable);
        if (prior != UNKNOWN && prior != requiredState) {
            makeUnreachable();
            return false;
        }
        set(variable, requiredState);
        return true;
    }

    void makeUnreachable() {
        Arrays.fill(words, 0L);
        reachable = false;
    }

    NullFlowFacts copy() {
        return new NullFlowFacts(this);
    }

    /**
     * Meet facts at a control-flow join without creating per-variable records.
     * Destination may alias either or both source frames. Mismatched frame sizes
     * are rejected before any destination mutation.
     */
    void joinFrom(NullFlowFacts left, NullFlowFacts right) {
        if (left == null || right == null) throw new NullPointerException("null predecessor");
        if (variableCount != left.variableCount || variableCount != right.variableCount) {
            throw new IllegalArgumentException("variable-count mismatch");
        }
        if (!left.reachable && !right.reachable) {
            makeUnreachable();
            return;
        }
        if (!left.reachable) {
            copyFrom(right);
            return;
        }
        if (!right.reachable) {
            copyFrom(left);
            return;
        }
        for (int i = 0; i < words.length; i++) {
            long a = left.words[i];
            long b = right.words[i];
            long difference = a ^ b;
            // Identify differing two-bit lanes, then clear the entire lane.
            long differingLanes = (difference | (difference >>> 1)) & EVEN_BITS;
            words[i] = a & ~(differingLanes | (differingLanes << 1));
        }
        reachable = true;
    }

    /**
     * Monotone control-flow union with a predecessor, reporting a real state
     * change without copying an entire frame for comparison. This operation
     * is safe when {@code incoming == this} and never promotes UNKNOWN to a
     * definite non-null assertion.
     *
     * @return true if reachability or at least one nullness slot changed
     */
    boolean mergeFrom(NullFlowFacts incoming) {
        if (incoming == null) throw new NullPointerException("null predecessor");
        if (variableCount != incoming.variableCount) {
            throw new IllegalArgumentException("variable-count mismatch");
        }
        if (!incoming.reachable) return false;
        if (!reachable) {
            copyFrom(incoming);
            return true;
        }
        boolean changed = false;
        for (int i = 0; i < words.length; i++) {
            long a = words[i];
            long b = incoming.words[i];
            long difference = a ^ b;
            long differingLanes = (difference | (difference >>> 1)) & EVEN_BITS;
            long merged = a & ~(differingLanes | (differingLanes << 1));
            changed |= merged != a;
            words[i] = merged;
        }
        return changed;
    }

    /** Independent caller-owned snapshot; never exports the retained words. */
    long[] snapshotWords() {
        return words.clone();
    }

    private void copyFrom(NullFlowFacts source) {
        System.arraycopy(source.words, 0, words, 0, words.length);
        reachable = source.reachable;
    }

    private void checkVariable(int variable) {
        if (variable < 0 || variable >= variableCount) {
            throw new IndexOutOfBoundsException("invalid variable ID: " + variable);
        }
    }

    @Override public boolean equals(Object candidate) {
        if (this == candidate) return true;
        if (!(candidate instanceof NullFlowFacts other)) return false;
        return variableCount == other.variableCount && reachable == other.reachable
                && Arrays.equals(words, other.words);
    }

    @Override public int hashCode() {
        int hash = 31 * variableCount + Boolean.hashCode(reachable);
        return 31 * hash + Arrays.hashCode(words);
    }
}
