// Copyright 2026 Hitesh Soliwal and contributors
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.ast.internal;

import java.util.Arrays;
import java.util.Objects;

/**
 * Candidate-only method-local control-flow solver over attributed predecessor IDs.
 * A compiler owner must supply real edges, stable local-variable IDs, and correct
 * transfer semantics, including exceptional and volatile/field invalidation.
 * This is not a source parser or an opt-in javac diagnostic by itself.
 *
 * <p>The adjacency and work queue are primitive arrays; no node, edge, or
 * variable object is retained. Each node owns one {@link NullFlowFacts}
 * frame; states may only move from unreachable to a fact and then toward
 * UNKNOWN, so cyclic graphs reach a finite fixed point.
 */
//FLLDCIM: RELOCATE - copy of M3JDK m3/ast/src/main/java/jdk/internal/m3/ast/NullFlowWorklist.java at b7539452eb6c
final class NullFlowWorklist {
    static final byte IDENTITY = 0;
    static final byte SET_NULL = 1;
    static final byte SET_NONNULL = 2;
    static final byte FORGET = 3;
    static final byte REQUIRE_NULL = 4;
    static final byte REQUIRE_NONNULL = 5;

    private final int nodeCount;
    private final int variableCount;
    private final int[] firstEdge;
    private final int[] nextEdge;
    private final int[] target;
    private final int[] variable;
    private final byte[] transfer;

    /** Every input is validated and copied before a solver can run. */
    NullFlowWorklist(int nodeCount, int variableCount, int[] source,
            int[] target, int[] variable, byte[] transfer) {
        if (nodeCount < 1 || variableCount < 0) {
            throw new IllegalArgumentException("invalid method frame dimensions");
        }
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(variable, "variable");
        Objects.requireNonNull(transfer, "transfer");
        int edges = source.length;
        if (target.length != edges || variable.length != edges || transfer.length != edges) {
            throw new IllegalArgumentException("mismatched edge arrays");
        }
        for (int e = 0; e < edges; e++) {
            if (source[e] < 0 || source[e] >= nodeCount
                    || target[e] < 0 || target[e] >= nodeCount) {
                throw new IndexOutOfBoundsException("invalid control-flow edge: " + e);
            }
            if (transfer[e] < IDENTITY || transfer[e] > REQUIRE_NONNULL) {
                throw new IllegalArgumentException("unsupported transfer: " + e);
            }
            if (transfer[e] == IDENTITY) {
                if (variable[e] != -1) {
                    throw new IllegalArgumentException("identity edge must use variable -1");
                }
            } else if (variable[e] < 0 || variable[e] >= variableCount) {
                throw new IndexOutOfBoundsException("invalid transfer variable: " + e);
            }
        }
        this.nodeCount = nodeCount;
        this.variableCount = variableCount;
        this.target = target.clone();
        this.variable = variable.clone();
        this.transfer = transfer.clone();
        this.firstEdge = new int[nodeCount];
        this.nextEdge = new int[edges];
        Arrays.fill(firstEdge, -1);
        // Preserve the caller's original edge order when walking a node.
        for (int e = edges - 1; e >= 0; e--) {
            nextEdge[e] = firstEdge[source[e]];
            firstEdge[source[e]] = e;
        }
    }

    int nodeCount() {
        return nodeCount;
    }

    int edgeCount() {
        return target.length;
    }

    /**
     * Compute the least reachable fixed point under conservative nullness joins.
     * Returns independent, method-local frames; no shared queue or mutable graph
     * input leaks to the caller. The entry frame is never modified.
     */
    NullFlowFacts[] solve(int entryNode, NullFlowFacts entry) {
        if (entryNode < 0 || entryNode >= nodeCount) {
            throw new IndexOutOfBoundsException("invalid entry node");
        }
        Objects.requireNonNull(entry, "entry");
        if (entry.variableCount() != variableCount) {
            throw new IllegalArgumentException("entry variable-count mismatch");
        }
        NullFlowFacts[] in = new NullFlowFacts[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            in[i] = new NullFlowFacts(variableCount);
            in[i].makeUnreachable();
        }
        if (!entry.reachable()) return in;
        in[entryNode] = entry.copy();

        // A node is enqueued only once at a time: at most nodeCount slots.
        int[] queue = new int[nodeCount];
        boolean[] queued = new boolean[nodeCount];
        int head = 0;
        int tail = 1 % nodeCount;
        int size = 1;
        queue[0] = entryNode;
        queued[entryNode] = true;

        NullFlowFacts unreachable = new NullFlowFacts(variableCount);
        unreachable.makeUnreachable();
        NullFlowFacts candidate = new NullFlowFacts(variableCount);
        while (size != 0) {
            int from = queue[head];
            head = (head + 1) % nodeCount;
            size--;
            queued[from] = false;
            for (int edge = firstEdge[from]; edge != -1; edge = nextEdge[edge]) {
                // Reuse a single frame across all transitions, including loops.
                candidate.joinFrom(in[from], unreachable);
                apply(edge, candidate);
                if (!candidate.reachable()) continue;
                int to = target[edge];
                if (!in[to].mergeFrom(candidate) || queued[to]) continue;
                queue[tail] = to;
                tail = (tail + 1) % nodeCount;
                queued[to] = true;
                size++;
            }
        }
        return in;
    }

    private void apply(int edge, NullFlowFacts candidate) {
        int slot = variable[edge];
        switch (transfer[edge]) {
            case IDENTITY -> { }
            case SET_NULL -> candidate.set(slot, NullFlowFacts.NULL);
            case SET_NONNULL -> candidate.set(slot, NullFlowFacts.NONNULL);
            case FORGET -> candidate.set(slot, NullFlowFacts.UNKNOWN);
            case REQUIRE_NULL -> candidate.assume(slot, NullFlowFacts.NULL);
            case REQUIRE_NONNULL -> candidate.assume(slot, NullFlowFacts.NONNULL);
            default -> throw new AssertionError("validated transfer");
        }
    }
}
