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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The CMake releases that provisioning may unpack: {@code com.googlecode.cmake-maven-project:cmake-binaries} jars,
 * whose executables become the build tools, so a jar is unpacked only when its SHA-256 equals a pinned one. The pins
 * below are the Maven Central jars of the default version (each streamed from Central and hashed; its SHA-1 matched
 * Central's published .sha1). Another version or classifier needs {@code native.cmake.sha256}. The unpacked folder's
 * marker holds the hash and is checked on every use.
 */
public final class CMakeBinaries {
    /** The provisioned version without a configured one. */
    public static final String DEFAULT_VERSION = "3.31.5-b1";
    /** The unpacked folder's marker. */
    public static final String MARKER = ".unpacked";

    private static final Set<String> EXECUTABLES = Set.of("cmake", "cmake.exe", "ctest", "ctest.exe");

    private static final Map<String, String> PINNED = Map.of(
            "3.31.5-b1:windows-x86_64", "b392cf39462e5b208e8ef3644074db424ee214021e7fe8a83e1d87c6f444fe1f",
            "3.31.5-b1:windows-arm_64", "0046d38eed90e8678b425b35e52270b00b395a156ed05d6fdc285d96e1c91063",
            "3.31.5-b1:linux-x86_64", "76d6c8184cf4d2ad221d6e255d74cf50b3264d566b8662172e5c029d26781b61",
            "3.31.5-b1:linux-arm_64", "e95c11f76b9982ca0c6f34e3827dbe79c8e91657d00f8e301dbdf6b47a4b841a",
            "3.31.5-b1:mac-universal", "ce18444a75955a93a311005148742fd7552347bff138d884d2ee0b4b29a362b5");

    private CMakeBinaries() {}

    /** The expected SHA-256: the configured one, else the pin for this version and classifier. */
    public static Optional<String> expected(String version, String classifier, String configured) {
        String value = configured == null ? "" : configured.strip().toLowerCase(Locale.ROOT);
        return value.isEmpty() ? Optional.ofNullable(PINNED.get(version + ":" + classifier)) : Optional.of(value);
    }

    /** Fails unless {@code archive} has the expected SHA-256. */
    public static void verify(Path archive, String coordinates, Optional<String> expected) throws IOException {
        String wanted = expected.orElseThrow(() -> noPin(coordinates));
        String actual = NativeDependencies.sha256(archive);
        if (!actual.equals(wanted)) {
            throw mismatch(coordinates, archive, actual, wanted);
        }
    }

    /**
     * Copies {@code archive} to {@code copy} while hashing the bytes copied and keeps the copy only when that hash is
     * the expected one. Provisioning unpacks the copy, so the archive in the local repository cannot change between
     * the check and the unpacking.
     */
    public static Path copyVerified(Path archive, Path copy, String coordinates, Optional<String> expected)
            throws IOException {
        String wanted = expected.orElseThrow(() -> noPin(coordinates));
        Files.createDirectories(copy.toAbsolutePath().getParent());
        MessageDigest digest = sha256();
        try (InputStream in = new DigestInputStream(Files.newInputStream(archive), digest)) {
            Files.copy(in, copy, StandardCopyOption.REPLACE_EXISTING);
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equals(wanted)) {
            Files.deleteIfExists(copy);
            throw mismatch(coordinates, archive, actual, wanted);
        }
        return copy;
    }

    /**
     * True when {@code folder} was unpacked from an archive with hash {@code sha256} and its executables (bin/cmake,
     * bin/ctest, with or without .exe) still have the hashes the marker recorded.
     */
    public static boolean unpacked(Path folder, String sha256) throws IOException {
        Path marker = folder.resolve(MARKER);
        if (!Files.isRegularFile(marker)) {
            return false;
        }
        List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
        Map<String, String> recorded = lines.stream().filter(l -> l.startsWith("file\t")).map(l -> l.split("\t"))
                .filter(f -> f.length == 3).collect(Collectors.toMap(f -> f[1], f -> f[2], (a, b) -> b, TreeMap::new));
        return lines.contains("sha256=" + sha256) && !recorded.isEmpty() && recorded.equals(executables(folder));
    }

    /** Records the archive hash and the hashes of the executables after unpacking. */
    public static void mark(Path folder, String coordinates, String sha256) throws IOException {
        String files = executables(folder).entrySet().stream().map(e -> "file\t" + e.getKey() + "\t" + e.getValue()
                + "\n").collect(Collectors.joining());
        Files.writeString(folder.resolve(MARKER), coordinates + "\nsha256=" + sha256 + "\n" + files,
                StandardCharsets.UTF_8);
    }

    /** bin/cmake and bin/ctest (with or without .exe) below {@code folder}, relative path to SHA-256. */
    private static Map<String, String> executables(Path folder) throws IOException {
        try (Stream<Path> walk = Files.walk(folder, 6)) {
            return walk.filter(p -> EXECUTABLES.contains(p.getFileName().toString()) && p.getParent() != null
                    && "bin".equals(String.valueOf(p.getParent().getFileName())) && Files.isRegularFile(p))
                    .collect(Collectors.toMap(p -> WindowsPaths.relative(folder, p), CMakeBinaries::hash, (a, b) -> b,
                            TreeMap::new));
        }
    }

    private static String hash(Path file) {
        try {
            return NativeDependencies.sha256(file);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private static IOException noPin(String coordinates) {
        return new IOException("no pinned SHA-256 for " + coordinates + "; set native.cmake.sha256 to the archive's "
                + "SHA-256 to provision it");
    }

    private static IOException mismatch(String coordinates, Path archive, String actual, String expected) {
        return new IOException("refused " + coordinates + " at " + archive + ": SHA-256 " + actual
                + " does not match the expected " + expected);
    }
}
