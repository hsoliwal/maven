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
import org.apache.maven.api.annotations.Nonnull;

/**
 * Read-only dependency selection and conflict evidence over a result already
 * produced by Maven Resolver. No second resolver, network access, filesystem
 * inspection, policy verdict, or plugin goal execution is performed.
 *
 * <p>Omissions and managed-version explanations require a verbose resolution
 * graph. An empty omission list without verbose metadata is not proof of
 * convergence. A completed resolution report is not dependency-usage proof.
 */
@Experimental
public interface DependencyInsightsService extends Service {

    /** Maven conflict identity, excluding the version of the artifact.
 *
 * @param groupId artifact group identifier
 * @param artifactId artifact identifier
 * @param extension artifact extension
 * @param classifier artifact classifier, possibly empty
 */
    record Coordinate(String groupId, String artifactId, String extension, String classifier) {
        public Coordinate {
            Objects.requireNonNull(groupId, "groupId");
            Objects.requireNonNull(artifactId, "artifactId");
            Objects.requireNonNull(extension, "extension");
            Objects.requireNonNull(classifier, "classifier");
        }
    }

    /** Artifact selected by the original flattening/resolution process.
 *
 * @param coordinate artifact identity without version
 * @param version selected version
 * @param snapshot whether the selected version is a snapshot
 */
    record Selected(Coordinate coordinate, String version, boolean snapshot) {
        public Selected {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(version, "version");
        }
    }

    /**
     * Verbose graph evidence that a node was omitted in favor of another node.
     * {@code duplicate} only denotes identical coordinates and versions; other
     * cases are resolver selections, not automatically policy failures.
 *
 * @param omitted artifact omitted by the original resolver
 * @param omittedVersion version of the omitted artifact
 * @param winner artifact selected instead
 * @param selectedVersion version of the selected winner
 * @param duplicate whether coordinates and versions are identical
 */
    record Omission(
            Coordinate omitted,
            String omittedVersion,
            Coordinate winner,
            String selectedVersion,
            boolean duplicate) {
        public Omission {
            Objects.requireNonNull(omitted, "omitted");
            Objects.requireNonNull(omittedVersion, "omittedVersion");
            Objects.requireNonNull(winner, "winner");
            Objects.requireNonNull(selectedVersion, "selectedVersion");
        }
    }

    /** Original and effective versions when Maven reports version management.
 *
 * @param coordinate resolved artifact identity
 * @param declaredVersion originally declared version
 * @param selectedVersion effective selected version
 */
    record ManagedVersion(Coordinate coordinate, String declaredVersion, String selectedVersion) {
        public ManagedVersion {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(declaredVersion, "declaredVersion");
            Objects.requireNonNull(selectedVersion, "selectedVersion");
        }
    }

    /**
     * Immutable diagnostics. {@code complete} only means a RESOLVE request had
     * a root and no reported resolver errors; it neither authenticates artifact
     * bytes nor asserts conflict-freedom. The verbose flag controls whether
     * omitted/managed explanations can be considered available.
 *
 * @param selected original selected artifacts
 * @param omissions omitted artifact explanations
 * @param managedVersions managed version explanations
 * @param graphNodes number of graph nodes observed
 * @param resolutionErrors number of resolver errors reported
 * @param verbose whether verbose graph evidence was available
 * @param complete whether a root was present and no resolver error was reported
 */
    record Report(
            List<Selected> selected,
            List<Omission> omissions,
            List<ManagedVersion> managedVersions,
            int graphNodes,
            int resolutionErrors,
            boolean verbose,
            boolean complete) {
        public Report {
            selected = List.copyOf(Objects.requireNonNull(selected, "selected"));
            omissions = List.copyOf(Objects.requireNonNull(omissions, "omissions"));
            managedVersions = List.copyOf(Objects.requireNonNull(managedVersions, "managedVersions"));
            if (graphNodes < 0 || resolutionErrors < 0 || (complete && resolutionErrors != 0)) {
                throw new IllegalArgumentException("invalid dependency insight counts");
            }
        }
    }

    /**
     * Observes the existing resolver result, including its verbose graph if
     * present. Refuses unbounded graphs rather than returning partial findings
     * that consumers might misinterpret as a clean dependency tree.
     *
     * @throws IllegalArgumentException if the graph exceeds the bounded node
     *         or edge budget
     */
    @Nonnull
    Report inspect(@Nonnull DependencyResolverResult result);
}
