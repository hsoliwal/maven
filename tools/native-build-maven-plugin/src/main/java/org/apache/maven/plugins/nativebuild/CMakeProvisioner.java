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
import org.apache.maven.plugins.nativebuild.engine.DeterministicZip;
import org.apache.maven.plugins.nativebuild.engine.Platform;
import org.apache.maven.plugins.nativebuild.engine.WindowsPaths;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

/**
 * Optional CMake provisioning: resolves {@code <group>:cmake-binaries:<version>:<classifier>} (the CMake releases
 * cmake-maven-project publishes on Maven Central for windows-x86_64, windows-arm_64, linux-x86_64, linux-arm_64 and
 * mac-universal) through Maven Resolver into the local repository, and unpacks it once under
 * {@code target/native/tools}.
 */
final class CMakeProvisioner {
    private static final String ARTIFACT = "cmake-binaries";

    private CMakeProvisioner() {}

    /** Paths of cmake and ctest from the provisioned release. */
    static Map<String, String> provision(AbstractNativeMojo m, Platform platform) throws IOException {
        Artifact wanted = new DefaultArtifact(m.cmakeProvisionGroupId, ARTIFACT, classifier(platform), "jar",
                m.cmakeVersion);
        Path archive = resolve(m, wanted);
        Path target = m.buildDirectory.toPath().resolve("tools").resolve("cmake-" + m.cmakeVersion + "-"
                + classifier(platform));
        Path marker = target.resolve(".unpacked");
        if (!Files.isRegularFile(marker)) {
            DeterministicZip.extract(archive, target);
            Files.writeString(marker, wanted.toString() + "\n");
        }
        String cmake = executable(target, "cmake", platform);
        m.getLog().info("PROVISIONED " + wanted + " -> " + cmake);
        return Map.of("cmake", cmake, "ctest", executable(target, "ctest", platform));
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

    private static Path resolve(AbstractNativeMojo m, Artifact wanted) throws IOException {
        ArtifactRequest request = new ArtifactRequest(wanted, m.project.getRemoteProjectRepositories(), null);
        try {
            return m.repositorySystem.resolveArtifact(m.session.getRepositorySession(), request).getArtifact()
                    .getFile().toPath();
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
