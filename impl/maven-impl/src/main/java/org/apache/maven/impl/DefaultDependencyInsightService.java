/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.impl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.Node;
import org.apache.maven.api.di.Named;
import org.apache.maven.api.di.Singleton;
import org.apache.maven.api.services.DependencyInsightService;
import org.apache.maven.api.services.DependencyResolverRequest;
import org.apache.maven.api.services.DependencyResolverResult;

/**
 * Iterative, bounded Maven Resolver graph snapshot with parallel array storage.
 * The only temporary object graph is the active DFS stack and identity set,
 * both bounded by graph depth rather than retaining whole-node provenance.
 */
@Named
@Singleton
public final class DefaultDependencyInsightService implements DependencyInsightService {
    @Override
    public Snapshot inspect(Request request) {
        Objects.requireNonNull(request, "request");
        DependencyResolverResult source = request.result();
        DependencyResolverRequest original = source.getRequest();
        boolean verbose = original != null && original.getVerbose();
        Node root = source.getRoot();
        boolean errors = !source.getExceptions().isEmpty();
        if (root == null) {
            return new PackedSnapshot(new Storage(1), 0, false, verbose, 0);
        }
        Storage rows = new Storage(Math.min(16, request.maxNodes()));
        Deque<Frame> stack = new ArrayDeque<>();
        IdentityHashMap<Object, Boolean> active = new IdentityHashMap<>();
        stack.push(new Frame(root, -1));
        int cycles = 0;
        boolean truncated = false;
        while (!stack.isEmpty()) {
            Frame frame = stack.peek();
            if (frame.rowIndex < 0) {
                frame.rowIndex = rows.add(frame.parentIndex, frame.node, request.maxNodes());
                frame.children = Objects.requireNonNull(frame.node.children(), "node children");
                active.put(frame.identity, Boolean.TRUE);
            }
            if (frame.nextChild == frame.children.size()) {
                active.remove(frame.identity);
                stack.pop();
                continue;
            }
            Node child = Objects.requireNonNull(frame.children.get(frame.nextChild++), "child");
            if (active.containsKey(nodeIdentity(child))) {
                cycles++;
                continue;
            }
            if (rows.count == request.maxNodes()) {
                truncated = true;
                break;
            }
            stack.push(new Frame(child, frame.rowIndex));
        }
        // Do not retain the active DFS frames or original resolver nodes.
        return new PackedSnapshot(rows, rows.count, !errors && !truncated && cycles == 0, verbose, cycles);
    }

    /**
     * Maven's native Node facade can be reconstructed for each child access.
     * An active-path set keyed only by facade identity would miss cycles and
     * incorrectly report truncation. Keep the provider's original node as
     * the traversal identity; preserve the original identity for other Node
     * implementations without introducing a new resolver.
     */
    private static Object nodeIdentity(Node node) {
        return node instanceof AbstractNode nativeNode ? nativeNode.getDependencyNode() : node;
    }

    private static final class Frame {
        private final Node node;
        private final Object identity;
        private final int parentIndex;
        private List<Node> children;
        private int rowIndex = -1;
        private int nextChild;

        Frame(Node node, int parentIndex) {
            this.node = node;
            this.identity = nodeIdentity(node);
            this.parentIndex = parentIndex;
        }
    }

    /** Compact parallel lanes rather than retained per-dependency records. */
    private static final class Storage {
        private int count;
        private int[] parent;
        private String[] coordinates;
        private boolean[] omitted;
        private String[] winner;
        private String[] premanagedVersion;
        private String[] premanagedScope;
        private String[] originalScope;

        Storage(int capacity) {
            parent = new int[capacity];
            coordinates = new String[capacity];
            omitted = new boolean[capacity];
            winner = new String[capacity];
            premanagedVersion = new String[capacity];
            premanagedScope = new String[capacity];
            originalScope = new String[capacity];
        }

        int add(int parentIndex, Node node, int maxNodes) {
            if (count == parent.length) {
                int capacity = Math.min(maxNodes, Math.max(2, parent.length * 2));
                parent = Arrays.copyOf(parent, capacity);
                coordinates = Arrays.copyOf(coordinates, capacity);
                omitted = Arrays.copyOf(omitted, capacity);
                winner = Arrays.copyOf(winner, capacity);
                premanagedVersion = Arrays.copyOf(premanagedVersion, capacity);
                premanagedScope = Arrays.copyOf(premanagedScope, capacity);
                originalScope = Arrays.copyOf(originalScope, capacity);
            }
            int index = count++;
            parent[index] = parentIndex;
            coordinates[index] = key(node);
            Optional<Node> resolvedWinner = node.winner();
            omitted[index] = resolvedWinner.isPresent();
            winner[index] =
                    resolvedWinner.map(DefaultDependencyInsightService::key).orElse(null);
            premanagedVersion[index] = node.premanagedVersion().orElse(null);
            premanagedScope[index] = node.premanagedScope().orElse(null);
            originalScope[index] = node.originalScope().orElse(null);
            return index;
        }
    }

    private static String key(Node node) {
        Artifact artifact = node.artifact();
        if (artifact == null) {
            artifact = node.dependency();
        }
        return artifact == null ? "<root>" : artifact.key();
    }

    private static final class PackedSnapshot implements Snapshot {
        private final int count;
        private final boolean complete;
        private final boolean verbose;
        private final int cycles;
        private final int[] parent;
        private final String[] coordinates;
        private final boolean[] omitted;
        private final String[] winner;
        private final String[] premanagedVersion;
        private final String[] premanagedScope;
        private final String[] originalScope;

        PackedSnapshot(Storage store, int count, boolean complete, boolean verbose, int cycles) {
            this.count = count;
            this.complete = complete;
            this.verbose = verbose;
            this.cycles = cycles;
            parent = Arrays.copyOf(store.parent, count);
            coordinates = Arrays.copyOf(store.coordinates, count);
            omitted = Arrays.copyOf(store.omitted, count);
            winner = Arrays.copyOf(store.winner, count);
            premanagedVersion = Arrays.copyOf(store.premanagedVersion, count);
            premanagedScope = Arrays.copyOf(store.premanagedScope, count);
            originalScope = Arrays.copyOf(store.originalScope, count);
        }

        @Override
        public int size() {
            return count;
        }

        @Override
        public boolean complete() {
            return complete;
        }

        @Override
        public boolean verboseRequested() {
            return verbose;
        }

        @Override
        public int cyclesSkipped() {
            return cycles;
        }

        @Override
        public Entry entry(int index) {
            Objects.checkIndex(index, count);
            return new Entry(
                    parent[index],
                    coordinates[index],
                    omitted[index],
                    winner[index],
                    premanagedVersion[index],
                    premanagedScope[index],
                    originalScope[index]);
        }

        @Override
        public List<String> path(int index) {
            Objects.checkIndex(index, count);
            List<String> reversed = new ArrayList<>();
            for (int current = index; current >= 0; current = parent[current]) {
                reversed.add(coordinates[current]);
            }
            Collections.reverse(reversed);
            return List.copyOf(reversed);
        }
    }
}
