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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.Node;
import org.apache.maven.api.di.Named;
import org.apache.maven.api.di.Singleton;
import org.apache.maven.api.services.DependencyInsightsService;
import org.apache.maven.api.services.DependencyResolverRequest;
import org.apache.maven.api.services.DependencyResolverResult;

/**
 * Native Maven 4 consumer of Resolver's selected nodes and optional verbose
 * conflict metadata. Traverses iteratively with an array-backed worklist.
 * Does not collect, download, alter or cache dependencies.
 */
@Named
@Singleton
public final class DefaultDependencyInsightsService implements DependencyInsightsService {
    private static final int MAX_REFERENCES = 262_144;

    private static final Comparator<Coordinate> COORDINATES = Comparator.comparing(Coordinate::groupId)
            .thenComparing(Coordinate::artifactId)
            .thenComparing(Coordinate::extension)
            .thenComparing(Coordinate::classifier);

    private static final Comparator<Selected> SELECTED = Comparator.comparing(Selected::coordinate, COORDINATES)
            .thenComparing(Selected::version)
            .thenComparing(Selected::snapshot);

    private static final Comparator<Omission> OMISSIONS = Comparator.comparing(Omission::omitted, COORDINATES)
            .thenComparing(Omission::omittedVersion)
            .thenComparing(Omission::winner, COORDINATES)
            .thenComparing(Omission::selectedVersion)
            .thenComparing(Omission::duplicate);

    private static final Comparator<ManagedVersion> MANAGED = Comparator.comparing(
                    ManagedVersion::coordinate, COORDINATES)
            .thenComparing(ManagedVersion::declaredVersion)
            .thenComparing(ManagedVersion::selectedVersion);

    @Override
    public Report inspect(DependencyResolverResult result) {
        Objects.requireNonNull(result, "result");
        List<Node> flat = Objects.requireNonNull(result.getNodes(), "result.nodes");
        if (flat.size() > MAX_REFERENCES) {
            throw new IllegalArgumentException("dependency insight selection budget exceeded");
        }
        List<Selected> selected = new ArrayList<>(flat.size());
        for (Node node : flat) {
            Artifact artifact = Objects.requireNonNull(node, "selected node").artifact();
            if (artifact != null) {
                selected.add(new Selected(coordinate(artifact), artifact.version().toString(), artifact.snapshot()));
            }
        }

        List<Omission> omissions = new ArrayList<>();
        List<ManagedVersion> managed = new ArrayList<>();
        Node root = result.getRoot();
        int visitedCount = root == null ? 0 : observeGraph(root, omissions, managed);
        DependencyResolverRequest request = result.getRequest();
        boolean verbose = request != null && request.getVerbose();
        int errors = Objects.requireNonNull(result.getExceptions(), "result.exceptions")
                .size();
        boolean complete = root != null
                && errors == 0
                && request != null
                && request.getRequestType() == DependencyResolverRequest.RequestType.RESOLVE;
        return new Report(
                unique(selected, SELECTED),
                unique(omissions, OMISSIONS),
                unique(managed, MANAGED),
                visitedCount,
                errors,
                verbose,
                complete);
    }

    private static int observeGraph(Node root, List<Omission> omissions, List<ManagedVersion> managed) {
        Node[] work = new Node[16];
        int size = 1;
        work[0] = root;
        int edges = 0;
        IdentityHashMap<Node, Boolean> visited = new IdentityHashMap<>();
        while (size != 0) {
            Node node = Objects.requireNonNull(work[--size], "graph node");
            work[size] = null;
            if (visited.put(node, Boolean.TRUE) != null) {
                continue;
            }
            if (visited.size() > MAX_REFERENCES) {
                throw new IllegalArgumentException("dependency insight node budget exceeded");
            }

            Artifact artifact = node.artifact();
            if (artifact != null) {
                Optional<Node> winner = node.winner();
                if (winner.isPresent()) {
                    Artifact chosen = winner.orElseThrow().artifact();
                    if (chosen != null) {
                        Coordinate omitted = coordinate(artifact);
                        Coordinate selected = coordinate(chosen);
                        String omittedVersion = artifact.version().toString();
                        String selectedVersion = chosen.version().toString();
                        omissions.add(new Omission(
                                omitted,
                                omittedVersion,
                                selected,
                                selectedVersion,
                                omitted.equals(selected) && omittedVersion.equals(selectedVersion)));
                    }
                }
                node.premanagedVersion()
                        .ifPresent(original -> managed.add(new ManagedVersion(
                                coordinate(artifact), original, artifact.version().toString())));
            }

            List<Node> children = Objects.requireNonNull(node.children(), "node.children");
            if (children.size() > MAX_REFERENCES - edges || children.size() > MAX_REFERENCES - size) {
                throw new IllegalArgumentException("dependency insight edge budget exceeded");
            }
            edges += children.size();
            int required = size + children.size();
            if (required > work.length) {
                work = Arrays.copyOf(work, Math.max(required, Math.min(MAX_REFERENCES, work.length * 2)));
            }
            for (int index = children.size() - 1; index >= 0; index--) {
                work[size++] = Objects.requireNonNull(children.get(index), "child node");
            }
        }
        return visited.size();
    }

    private static Coordinate coordinate(Artifact artifact) {
        return new Coordinate(
                artifact.groupId(), artifact.artifactId(), artifact.extension(), artifact.classifier());
    }

    private static <T> List<T> unique(List<T> entries, Comparator<T> comparator) {
        entries.sort(comparator);
        List<T> result = new ArrayList<>(entries.size());
        T previous = null;
        for (T entry : entries) {
            if (!entry.equals(previous)) {
                result.add(entry);
                previous = entry;
            }
        }
        return result;
    }
}
