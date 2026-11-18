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
package org.apache.maven.plugins.nativebuild;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.maven.plugins.nativebuild.engine.CMakeBinaries;
import org.apache.maven.plugins.nativebuild.engine.DeterministicZip;
import org.apache.maven.plugins.nativebuild.engine.FileTrees;
import org.apache.maven.plugins.nativebuild.engine.NativeNames;
import org.apache.maven.plugins.nativebuild.engine.Platform;
import org.apache.maven.plugins.nativebuild.engine.WindowsPaths;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

/**
 * Optional CMake provisioning: resolves {@code <group>:cmake-binaries:<version>:<classifier>} (the CMake releases
 * cmake-maven-project publishes on Maven Central for windows-x86_64, windows-arm_64, linux-x86_64, linux-arm_64 and
 * mac-universal) through Maven Resolver with checksum policy {@code fail}, checks the archive's SHA-256 against the pin
 * ({@link CMakeBinaries}) or {@code native.cmake.sha256} while copying it into {@code target/native/tools}, unpacks
 * that verified copy there (the local repository file is read once), and records the archive hash and the hashes of
 * bin/cmake and bin/ctest in the folder's marker, which is checked on every use; the build directory and the
 * coordinates are checked before anything is written. Only configure and the goals after it provision; detect never
 * does.
 */
final class CMakeProvisioner {
    private static final String ARTIFACT = "cmake-binaries";

    private CMakeProvisioner() {}

    /** Paths of cmake and ctest from the provisioned, verified release. */
    static Map<String, String> provision(AbstractNativeMojo m, Platform platform) throws IOException {
        Artifact wanted = artifact(m, platform);
        Optional<String> expected = CMakeBinaries.expected(m.cmakeVersion, classifier(platform), m.cmakeSha256);
        Path tools = m.buildDirectory.toPath().resolve("tools");
        Path target = tools.resolve("cmake-" + m.cmakeVersion + "-" + classifier(platform));
        if (expected.isEmpty() || !CMakeBinaries.unpacked(target, expected.get())) {
            Path copy = CMakeBinaries.copyVerified(resolve(m, wanted), tools.resolve(target.getFileName() + ".jar"),
                    wanted.toString(), expected);
            FileTrees.delete(target);
            DeterministicZip.extract(copy, target);
            CMakeBinaries.mark(target, wanted.toString(), expected.orElseThrow());
            Files.delete(copy);
        }
        String cmake = executable(target, "cmake", platform);
        m.getLog().info("PROVISIONED " + wanted + " (sha256 " + expected.orElseThrow() + ") -> " + cmake);
        return Map.of("cmake", cmake, "ctest", executable(target, "ctest", platform));
    }

    /** The coordinates provisioning resolves. */
    static String coordinates(AbstractNativeMojo m, Platform platform) {
        return artifact(m, platform).toString();
    }

    /** The cmake-binaries classifier of a platform. */
    static String classifier(Platform platform) {
        String arch = "aarch_64".equals(platform.arch()) ? "arm_64" : platform.arch();
        return switch (platform.os()) {
            case "windows" -> "windows-" + arch;
            case "osx" -> "mac-universal";
            default -> platform.os() + "-" + arch;
        };
    }

    /** The artifact; its group and version name a folder too, so both are checked first. */
    private static Artifact artifact(AbstractNativeMojo m, Platform platform) {
        return new DefaultArtifact(NativeNames.coordinate(m.cmakeProvisionGroupId, "native.cmake.provisionGroupId"),
                ARTIFACT, classifier(platform), "jar", NativeNames.coordinate(m.cmakeVersion, "native.cmake.version"));
    }

    /** Resolves with checksum policy fail: a checksum mismatch is an error, never a warning. */
    private static Path resolve(AbstractNativeMojo m, Artifact wanted) throws IOException {
        DefaultRepositorySystemSession strict = new DefaultRepositorySystemSession(m.session.getRepositorySession());
        strict.setChecksumPolicy(RepositoryPolicy.CHECKSUM_POLICY_FAIL);
        ArtifactRequest request = new ArtifactRequest(wanted, m.project.getRemoteProjectRepositories(), null);
        try {
            return m.repositorySystem.resolveArtifact(strict, request).getArtifact().getFile().toPath();
        } catch (ArtifactResolutionException missing) {
            throw new IOException("cannot provision CMake " + wanted + ": " + missing.getMessage(), missing);
        }
    }

    private static String executable(Path root, String name, Platform platform) throws IOException {
        String file = platform.windows() ? name + ".exe" : name;
        Optional<Path> found;
        try (Stream<Path> walk = Files.walk(root, 6)) {
            found = walk.filter(p -> p.getFileName().toString().equals(file) && p.getParent() != null
                    && "bin".equals(String.valueOf(p.getParent().getFileName()))).findFirst();
        }
        return WindowsPaths.mixed(found.orElseThrow(() -> new IOException("no bin/" + file + " in " + root)));
    }
}
