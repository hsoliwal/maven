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

import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

import org.apache.maven.api.di.Named;
import org.apache.maven.api.di.Singleton;
import org.apache.maven.api.services.M3ExternalBuildService;

/**
 * Native Maven 4 interpretation of original external build-owner deltas.
 * Pure and stateless: Maven retains no external workspace state or compiler cache.
 */
@Named
@Singleton
public final class DefaultM3ExternalBuildService implements M3ExternalBuildService {
    private static final Comparator<Change> BY_PATH = Comparator.comparing(Change::relativePath);
    private static final ChangeKind[] KINDS = ChangeKind.values();

    @Override
    public Assessment assess(Request request) {
        Objects.requireNonNull(request, "request");
        Change[] sorted = request.changes().toArray(Change[]::new);
        Arrays.sort(sorted, BY_PATH);
        for (int index = 1; index < sorted.length; index++) {
            if (sorted[index - 1].relativePath().equals(sorted[index].relativePath())) {
                throw new IllegalArgumentException("ambiguous duplicate host delta path");
            }
        }

        EnumSet<Reason> reasons = EnumSet.noneOf(Reason.class);
        Generation previous = request.previous();
        Generation current = request.current();
        if (previous == null) {
            reasons.add(Reason.INITIAL_BASELINE_MISSING);
        } else {
            if (!previous.providerId().equals(current.providerId())
                    || !previous.projectId().equals(current.projectId())) {
                reasons.add(Reason.FOREIGN_BASELINE);
            }
            if (previous.generation() == Long.MAX_VALUE
                    || current.generation() != previous.generation() + 1) {
                reasons.add(Reason.GENERATION_GAP);
            }
            if (!previous.modelSha256().equals(current.modelSha256())
                    || !previous.toolchainSha256().equals(current.toolchainSha256())
                    || !previous.dependenciesSha256().equals(current.dependenciesSha256())
                    || !previous.sourceRootsSha256().equals(current.sourceRootsSha256())) {
                reasons.add(Reason.MODEL_OR_TOOLCHAIN_CHANGED);
            }
        }
        if (!current.deltaComplete()) {
            reasons.add(Reason.INCOMPLETE_DELTA);
        }
        for (Change change : sorted) {
            if (change.kind() == ChangeKind.DELETED) {
                reasons.add(Reason.INPUT_REMOVED);
            }
            if (buildDescriptor(change.relativePath())) {
                reasons.add(Reason.BUILD_DESCRIPTOR_CHANGED);
            }
            if (generatedOutput(change.relativePath())) {
                reasons.add(Reason.GENERATED_OUTPUT_CHANGED);
            }
        }
        Disposition disposition;
        if (!reasons.isEmpty()) {
            disposition = Disposition.MAVEN_RECONCILIATION;
        } else {
            disposition = Disposition.HOST_DELTA_AVAILABLE;
            reasons.add(sorted.length == 0 ? Reason.HOST_DELTA_EMPTY : Reason.HOST_DELTA_READY);
        }
        return new PackedAssessment(disposition, List.copyOf(reasons), sorted);
    }

    private static boolean buildDescriptor(String path) {
        if (path.startsWith(".mvn/") || path.contains("/.mvn/")
                || path.startsWith(".settings/") || path.contains("/.settings/")
                || path.startsWith("gradle/wrapper/") || path.contains("/gradle/wrapper/")) {
            return true;
        }
        int slash = path.lastIndexOf('/');
        String name = path.substring(slash + 1);
        return switch (name) {
            case "pom.xml", ".project", ".classpath", "module-info.java", "build.gradle", "build.gradle.kts",
                    "settings.gradle", "settings.gradle.kts", "gradle.properties", "CMakeLists.txt",
                    "Makefile", "GNUmakefile", "build.xml", "package.json", "Cargo.toml", "Cargo.lock",
                    "go.mod", "go.sum", "pyproject.toml", "requirements.txt", "meson.build", "BUILD.bazel",
                    "WORKSPACE.bazel", "Package.swift", "CMakePresets.json", "vcpkg.json",
                    "Directory.Build.props", "Directory.Build.targets", ".factorypath", "libs.versions.toml",
                    "gradle.lockfile", "global.json", "NuGet.Config", "packages.lock.json",
                    "package-lock.json", "pnpm-lock.yaml", "yarn.lock", "bun.lock", "bun.lockb",
                    "Pipfile", "Pipfile.lock", "poetry.lock", "uv.lock", "pdm.lock", "setup.py", "setup.cfg",
                    "go.work", "go.work.sum", "rust-toolchain", "rust-toolchain.toml", "build.rs",
                    "Package.resolved", "meson.options", "CMakeUserPresets.json", "conanfile.py", "conanfile.txt",
                    "vcpkg-configuration.json", "MODULE.bazel", "MODULE.bazel.lock", ".bazelrc", ".bazelversion",
                    "BUILD", "WORKSPACE", "ivy.xml", "ivysettings.xml", "Makefile.am", "configure.ac" -> true;
            default -> name.endsWith(".csproj") || name.endsWith(".vcxproj")
                    || name.endsWith(".sln") || name.endsWith(".slnx")
                    || name.endsWith(".props") || name.endsWith(".targets")
                    || name.endsWith(".gradle") || name.endsWith(".gradle.kts");
        };
    }

    private static boolean generatedOutput(String path) {
        return path.startsWith("target/") || path.startsWith("build/")
                || path.contains("/target/") || path.contains("/build/")
                || path.startsWith(".gradle/");
    }

    /** Parallel arrays retain only normalized metadata, not host event instances. */
    private static final class PackedAssessment implements Assessment {
        private final Disposition disposition;
        private final List<Reason> reasons;
        private final String[] paths;
        private final byte[] kinds;
        private final String[] hashes;

        PackedAssessment(Disposition disposition, List<Reason> reasons, Change[] sorted) {
            this.disposition = disposition;
            this.reasons = reasons;
            paths = new String[sorted.length];
            kinds = new byte[sorted.length];
            hashes = new String[sorted.length];
            for (int index = 0; index < sorted.length; index++) {
                Change change = sorted[index];
                paths[index] = change.relativePath();
                kinds[index] = (byte) change.kind().ordinal();
                hashes[index] = change.contentSha256();
            }
        }

        @Override
        public Disposition disposition() {
            return disposition;
        }

        @Override
        public List<Reason> reasons() {
            return reasons;
        }

        @Override
        public int size() {
            return paths.length;
        }

        @Override
        public Change entry(int index) {
            Objects.checkIndex(index, paths.length);
            return new Change(paths[index], KINDS[Byte.toUnsignedInt(kinds[index])], hashes[index]);
        }
    }
}
