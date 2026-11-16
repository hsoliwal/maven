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
 * Versioned, deterministic fingerprinting of already produced Maven input
 * metadata. Maven, M3FS and specialized plugins remain owners of source
 * enumeration and content hashing; this service does not open a source file.
 *
 * <p>A fingerprint proves only that the supplied metadata encoded to the same
 * digest. It does not authenticate a producer, establish input completeness,
 * verify filesystem contents, admit build cache restoration, or allow a Mojo
 * to be skipped. Cache admission belongs to the original extension/plugin.
 */
@Experimental
public interface M3BuildFingerprintService extends Service {
    /** Maximum metadata entries in a single fingerprint operation. */
    int MAX_INPUTS = 262_144;

    /** Distinct input roles, encoded by name in the versioned fingerprint. */
    enum Kind {
        SOURCE,
        RESOURCE,
        CONFIGURATION,
        GENERATED,
        TOOL,
        DEPENDENCY
    }

    /**
     * A content digest supplied by an original input owner.
     *
     * @param relativePath canonical project-relative path using '/' separators
     * @param kind role of this input in the original plugin's declared inputs
     * @param sha256 producer-supplied lowercase SHA-256 of input bytes
     * @param bytes producer-supplied nonnegative input size
     */
    record Input(String relativePath, Kind kind, String sha256, long bytes) {
        public Input {
            relativePath = path(relativePath);
            kind = Objects.requireNonNull(kind, "kind");
            sha256 = sha(sha256);
            if (bytes < 0) {
                throw new IllegalArgumentException("negative input bytes");
            }
        }
    }

    /**
     * Versioned build-context metadata supplied by the invocation owner.
     *
     * <p>These digests must cover the effective goal configuration, exact
     * selected toolchain, selected dependency graph and relevant environment.
     * No untrusted source may assert that these fields are complete.
     *
     * @param project canonical project identity
     * @param pluginGa group and artifact identifier of the owning plugin
     * @param pluginVersion exact owning plugin version
     * @param goal original Mojo goal
     * @param execution original Mojo execution identifier
     * @param configurationSha256 effective configuration digest
     * @param toolchainSha256 selected toolchain digest
     * @param dependenciesSha256 selected dependency-graph digest
     * @param environmentSha256 relevant environment digest
     */
    record Context(
            String project,
            String pluginGa,
            String pluginVersion,
            String goal,
            String execution,
            String configurationSha256,
            String toolchainSha256,
            String dependenciesSha256,
            String environmentSha256) {
        public Context {
            project = name(project, "project");
            pluginGa = name(pluginGa, "pluginGa");
            pluginVersion = name(pluginVersion, "pluginVersion");
            goal = name(goal, "goal");
            execution = name(execution, "execution");
            configurationSha256 = sha(configurationSha256);
            toolchainSha256 = sha(toolchainSha256);
            dependenciesSha256 = sha(dependenciesSha256);
            environmentSha256 = sha(environmentSha256);
        }
    }

    /**
     * Immutable request for canonical metadata fingerprinting.
     *
     * @param context original owning plugin invocation context
     * @param inputs producer-supplied input facts, with no duplicate path/role pairs
     */
    record Request(Context context, List<Input> inputs) {
        public Request {
            context = Objects.requireNonNull(context, "context");
            Objects.requireNonNull(inputs, "inputs");
            if (inputs.size() > MAX_INPUTS) {
                throw new IllegalArgumentException("input fingerprint entry budget exceeded");
            }
            inputs = List.copyOf(inputs);
        }
    }

    /**
     * Immutable digest and non-authoritative size statistics.
     *
     * @param sha256 fingerprint of the canonical versioned metadata
     * @param inputCount number of distinct path/role metadata entries
     * @param inputBytes sum of declared byte sizes
     */
    record Fingerprint(String sha256, int inputCount, long inputBytes) {
        public Fingerprint {
            sha256 = sha(sha256);
            if (inputCount < 0 || inputBytes < 0) {
                throw new IllegalArgumentException("negative fingerprint statistics");
            }
        }
    }

    /**
     * Canonically fingerprint the supplied metadata, independent of list order.
     * A repeated path/role pair or overflowing byte sum is rejected.
     *
     * @param request metadata from the original build input owner
     * @return a deterministic fingerprint; not a cache admission decision
     */
    @Nonnull
    Fingerprint fingerprint(@Nonnull Request request);

    private static String name(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > 256 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field);
        }
        return value;
    }

    private static String path(String value) {
        Objects.requireNonNull(value, "relativePath");
        if (value.isBlank()
                || value.length() > 4096
                || value.startsWith("/")
                || value.contains("\\")
                || value.contains(":")
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("noncanonical input path");
        }
        for (String part : value.split("/", -1)) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("noncanonical input segment");
            }
        }
        return value;
    }

    private static String sha(String digest) {
        Objects.requireNonNull(digest, "sha256");
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid lowercase SHA-256");
        }
        return digest;
    }
}
