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
package org.apache.maven.plugins.nativebuild.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Native dependencies: zip artifacts (as {@code attach} produces them) whose classifier starts with this platform's
 * {@code os-arch}, unpacked once per zip content into {@code
 * <buildDirectory>/deps/<groupId>/<artifactId>/<classifier>}. Each folder is an install prefix (include, lib,
 * lib/pkgconfig, lib/cmake) that the planners pass on: CMAKE_PREFIX_PATH for CMake, pkg_config_path and
 * cmake_prefix_path for Meson, CPPFLAGS, LDFLAGS and PKG_CONFIG_PATH for Autotools and Make. A marker with the zip's
 * SHA-256 is a configure input, so a rebuilt dependency re-runs configure.
 */
public final class NativeDependencies {
    /** The marker file in each unpacked folder. */
    public static final String MARKER = ".native-dependency.sha256";

    /**
     * One resolved dependency.
     *
     * @param groupId its group
     * @param artifactId its artifact
     * @param classifier its classifier
     * @param zip the resolved file
     */
    public record Dependency(String groupId, String artifactId, String classifier, Path zip) {}

    private NativeDependencies() {}

    /** True for a classifier built for this platform: {@code os-arch} itself or {@code os-arch-<runtime>...}. */
    public static boolean forPlatform(String classifier, Platform platform) {
        String base = platform.os() + "-" + platform.arch();
        return classifier != null && (classifier.equals(base) || classifier.startsWith(base + "-"));
    }

    /**
     * The project-relative folders of {@code dependencies} below the project-relative {@code root}:
     * {@code <groupId>/<artifactId>/<classifier>}, one per zip, so the {@code -lib} and {@code -headers} archives of a
     * split attach never unpack over each other (each folder is its own prefix).
     */
    public static List<String> folders(String root, List<Dependency> dependencies) {
        return dependencies.stream().map(d -> Layout.join(Layout.join(Layout.join(root, d.groupId()), d.artifactId()),
                NativeNames.classifier(d.classifier()))).toList();
    }

    /** Unpacks each zip whose marker does not hold its hash; returns the folders. */
    public static List<String> unpack(Layout layout, String root, List<Dependency> dependencies) throws IOException {
        List<String> folders = folders(root, dependencies);
        try {
            java.util.stream.IntStream.range(0, folders.size())
                    .forEach(i -> unpackOne(layout.path(folders.get(i)), dependencies.get(i).zip()));
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
        return folders;
    }

    private static void unpackOne(Path folder, Path zip) {
        try {
            String hash = sha256(zip);
            Path marker = folder.resolve(MARKER);
            if (Files.isRegularFile(marker) && hash.equals(Files.readString(marker, StandardCharsets.UTF_8).strip())) {
                return;
            }
            FileTrees.delete(folder);
            DeterministicZip.extract(zip, folder);
            Files.writeString(marker, hash + "\n", StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** The SHA-256 of a file, in hex. */
    public static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
