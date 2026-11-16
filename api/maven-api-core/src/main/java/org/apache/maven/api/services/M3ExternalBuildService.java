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
 * Read-only, tool-neutral workspace change exchange for external build owners.
 *
 * <p>Eclipse JDT/m2e, Visual Studio/MSBuild/CMake, VS Code LSP clients, Gradle,
 * Ant, Make and other providers retain their own incremental state, compiler,
 * task execution, diagnostics, generated outputs and cancellation contracts.
 * Maven merely classifies caller-supplied deltas. This service never scans a
 * directory, starts a builder, runs a Mojo, skips a goal, authorizes build-cache
 * reuse or authenticates the provider's claim that its delta is complete.
 *
 * <p>The {@link Disposition#HOST_DELTA_AVAILABLE} classification says only that
 * a host may pass the supplied changes to its <em>own</em> incremental builder.
 * It is not evidence that Maven compilation, tests or publishing are reusable.
 */
@Experimental
public interface M3ExternalBuildService extends Service {
    int MAX_CHANGES = 65_536;

    /** Original host's change classification, not Maven's compiler decision. */
    enum ChangeKind {
        ADDED,
        MODIFIED,
        DELETED
    }

    /** Advisory next step: an external host delta or ordinary Maven reconciliation. */
    enum Disposition {
        HOST_DELTA_AVAILABLE,
        MAVEN_RECONCILIATION
    }

    /** Deterministic reasons without putting host-specific exceptions in the API. */
    enum Reason {
        INITIAL_BASELINE_MISSING,
        FOREIGN_BASELINE,
        INCOMPLETE_DELTA,
        GENERATION_GAP,
        MODEL_OR_TOOLCHAIN_CHANGED,
        BUILD_DESCRIPTOR_CHANGED,
        GENERATED_OUTPUT_CHANGED,
        INPUT_REMOVED,
        HOST_DELTA_READY,
        HOST_DELTA_EMPTY
    }

    /**
     * Original external owner generation. SHA-256 fields are provider claims;
     * they are not computed, authenticated or cached by this service.
     *
     * @param providerId unique host adapter name, e.g. eclipse.jdt or visualstudio.msbuild
     * @param projectId stable project identity scoped to that adapter
     * @param generation positive, monotonically advancing host generation
     * @param modelSha256 effective project/build-model digest reported by the owner
     * @param toolchainSha256 selected compiler/runtime identity digest
     * @param dependenciesSha256 effective dependency/classpath identity digest
     * @param sourceRootsSha256 source and generated-root configuration digest
     * @param deltaComplete whether the host claims complete change coverage
     */
    record Generation(
            String providerId,
            String projectId,
            long generation,
            String modelSha256,
            String toolchainSha256,
            String dependenciesSha256,
            String sourceRootsSha256,
            boolean deltaComplete) {
        public Generation {
            providerId = name(providerId, "providerId", 128);
            projectId = name(projectId, "projectId", 256);
            if (generation < 1) {
                throw new IllegalArgumentException("generation must be positive");
            }
            modelSha256 = sha256(modelSha256);
            toolchainSha256 = sha256(toolchainSha256);
            dependenciesSha256 = sha256(dependenciesSha256);
            sourceRootsSha256 = sha256(sourceRootsSha256);
        }
    }

    /**
     * One project-relative path as reported by the external source owner.
     *
     * @param relativePath canonical project-relative path, without traversal
     * @param kind original change kind
     * @param contentSha256 optional owner-supplied current digest; null is unknown
     */
    record Change(String relativePath, ChangeKind kind, String contentSha256) {
        public Change {
            relativePath = path(relativePath);
            kind = Objects.requireNonNull(kind, "kind");
            if (contentSha256 != null) {
                contentSha256 = sha256(contentSha256);
                if (kind == ChangeKind.DELETED) {
                    throw new IllegalArgumentException("deleted paths cannot claim current contents");
                }
            }
        }
    }

    /**
     * Immutable caller-supplied host generation and its bounded delta.
     *
     * @param previous known last successful generation or null for cold start
     * @param current host's newly reported generation
     * @param changes host-supplied changes; no file read occurs
     */
    record Request(Generation previous, Generation current, List<Change> changes) {
        public Request {
            current = Objects.requireNonNull(current, "current");
            Objects.requireNonNull(changes, "changes");
            if (changes.size() > MAX_CHANGES) {
                throw new IllegalArgumentException("workspace delta exceeds bounded row budget");
            }
            changes = List.copyOf(changes);
        }
    }

    /**
     * Immutable, array-backed interpretation of the host delta. Entries are
     * materialized on demand, not retained as one object graph per file.
     */
    interface Assessment {
        /** @return advisory host-delta or ordinary Maven reconciliation decision */
        Disposition disposition();

        /** @return deterministic reason codes describing this assessment */
        List<Reason> reasons();

        /** @return number of normalized file-change rows */
        int size();

        /** @return one canonical original change at index */
        Change entry(int index);

        /** @return false: a host hint can never skip any Maven Mojo */
        default boolean authorizesMavenGoalSkipping() {
            return false;
        }
    }

    /** Assess external change evidence without touching the host or Maven graph. */
    Assessment assess(Request request);

    /** Additive convenience overload for an explicitly supplied host generation. */
    default Assessment assess(Generation previous, Generation current, List<Change> changes) {
        return assess(new Request(previous, current, changes));
    }

    private static String name(String value, String field, int limit) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()
                || value.length() > limit
                || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        utf16(value);
        return value;
    }

    private static String sha256(String value) {
        Objects.requireNonNull(value, "sha256");
        if (value.length() != 64) {
            throw new IllegalArgumentException("SHA-256 must have 64 lowercase hex digits");
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!(ch >= '0' && ch <= '9') && !(ch >= 'a' && ch <= 'f')) {
                throw new IllegalArgumentException("noncanonical SHA-256");
            }
        }
        return value;
    }

    private static String path(String value) {
        Objects.requireNonNull(value, "relativePath");
        if (value.isEmpty()
                || value.length() > 2_048
                || value.charAt(0) == '/'
                || value.indexOf('\\') >= 0
                || value.indexOf(':') >= 0
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid host-relative path");
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("noncanonical host-relative path segment");
            }
        }
        utf16(value);
        return value;
    }

    private static void utf16(String text) {
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (Character.isHighSurrogate(ch)) {
                if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) {
                    throw new IllegalArgumentException("unpaired high surrogate");
                }
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("unpaired low surrogate");
            }
        }
    }
}
