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
package org.apache.maven.api.services;

import java.util.List;
import java.util.Objects;

import org.apache.maven.api.Service;
import org.apache.maven.api.annotations.Experimental;

/**
 * Read-only indexed explanations of an original Maven Resolver dependency graph.
 *
 * <p>The service never resolves dependencies, selects versions or invents
 * conflict decisions. It records only metadata reported by the original
 * {@link DependencyResolverResult}, in DFS encounter order. A parent index
 * references an earlier entry, making the snapshot compact and paths cheap
 * to recover on demand instead of copying one ancestor list per node.
 */
@Experimental
public interface DependencyInsightService extends Service {
    /** Cap memory and traversal work when inspecting an external dependency graph. */
    record Request(DependencyResolverResult result, int maxNodes) {
        public Request {
            result = Objects.requireNonNull(result, "result");
            if (maxNodes < 1 || maxNodes > 1_000_000) {
                throw new IllegalArgumentException("maxNodes must be in [1, 1000000]");
            }
        }
    }

    /**
     * @param parentIndex index of the immediate ancestor, or -1 for root
     * @param coordinates exact original artifact key, or "&lt;root&gt;" for an unlabeled root
     * @param omitted true only if the original node reported a conflict/duplicate winner
     * @param winningCoordinates artifact key of the reported winner, if available
     * @param premanagedVersion originally declared version, if reported by Maven Resolver
     * @param premanagedScope originally declared scope before management, if reported
     * @param originalScope scope before conflict resolution, if reported
     */
    record Entry(
            int parentIndex,
            String coordinates,
            boolean omitted,
            String winningCoordinates,
            String premanagedVersion,
            String premanagedScope,
            String originalScope) {}

    /**
     * Immutable flat snapshot. No Maven sessions, resolver nodes, or mutable
     * graph references are retained after {@link #inspect(Request)} returns.
     */
    interface Snapshot {
        /** Nodes actually captured, bounded by Request.maxNodes(). */
        int size();

        /** True if a root existed and no errors, cycles or truncation occurred. */
        boolean complete();

        /** The original resolver request explicitly enabled verbose metadata. */
        boolean verboseRequested();

        /** Number of back-edges omitted rather than following a cyclic source graph. */
        int cyclesSkipped();

        /** Individual entry, materialized only on request. */
        Entry entry(int index);

        /** An immutable root-to-entry coordinate path. */
        List<String> path(int index);
    }

    /** Snapshot only what the original resolver result actually contains. */
    Snapshot inspect(Request request);
}
