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

import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Operating system and processor in the os-maven-plugin vocabulary (windows, linux, osx; x86_64, aarch_64, ...), the
 * names native classifiers on Maven Central already use: {@code os-arch} is os-maven-plugin's
 * {@code os.detected.classifier}. On Windows the classifier also names the C runtime family, {@code -msvc} or
 * {@code -mingw}, because their libraries and import libraries do not mix; it is read from the configured build (see
 * {@link RuntimeDetector}), with {@link #runtime} only as the guess before configure.
 *
 * @param os normalized operating system name
 * @param arch normalized processor name
 */
public record Platform(String os, String arch) {
    /** Runtime suffix for libraries built by MSVC, clang-cl or the Visual Studio generator. */
    public static final String MSVC = "msvc";
    /** Runtime suffix for libraries built by MinGW gcc or clang. */
    public static final String MINGW = "mingw";

    /** The platform of this JVM. */
    public static Platform current() {
        return of(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /** Normalizes raw {@code os.name} and {@code os.arch} values. */
    public static Platform of(String osName, String osArch) {
        return new Platform(normalizeOs(osName), normalizeArch(osArch));
    }

    /** True on Windows. */
    public boolean windows() {
        return "windows".equals(os);
    }

    /** {@code os-arch}, plus {@code -runtime} on Windows when a runtime is given. */
    public String classifier(String runtime) {
        String base = os + "-" + arch;
        return windows() && runtime != null && !runtime.isBlank() ? base + "-" + runtime : base;
    }

    /**
     * The Windows runtime family a build produces: MSVC for the Visual Studio generator, cl and clang-cl; MinGW for
     * other configured compilers; otherwise MSVC when Visual Studio is installed (CMake picks it by default).
     * Empty elsewhere.
     */
    public String runtime(String generator, String compiler, boolean visualStudioInstalled) {
        if (!windows()) {
            return "";
        }
        String cc = WindowsPaths.stem(compiler == null ? "" : compiler).toLowerCase(Locale.ROOT);
        if ((generator != null && generator.startsWith("Visual Studio")) || "cl".equals(cc) || "clang-cl".equals(cc)) {
            return MSVC;
        }
        return cc.isEmpty() && visualStudioInstalled ? MSVC : MINGW;
    }

    /**
     * The build directory name: {@code os-arch} (or a configured classifier), then the CMake generator and the
     * configured compiler when given, so switching either never reuses a build tree configured for the other.
     */
    public String buildKey(String classifier, String generator, String compiler) {
        String base = classifier == null || classifier.isBlank() ? os + "-" + arch : classifier;
        return Stream.of(base, slug(generator), slug(WindowsPaths.stem(compiler == null ? "" : compiler)))
                .filter(s -> !s.isEmpty()).collect(Collectors.joining("-"));
    }

    private static String slug(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    static String normalizeOs(String raw) {
        String value = clean(raw);
        if (value.startsWith("windows")) {
            return "windows";
        }
        if (value.startsWith("mac") || value.startsWith("osx") || value.startsWith("darwin")) {
            return "osx";
        }
        if (value.startsWith("sunos") || value.startsWith("solaris")) {
            return "sunos";
        }
        return value.isEmpty() ? "unknown" : value.replaceAll("[^a-z0-9]", "");
    }

    static String normalizeArch(String raw) {
        String value = clean(raw);
        return switch (value) {
            case "x86_64", "amd64", "x64", "x8664", "em64t" -> "x86_64";
            case "x86", "i386", "i486", "i586", "i686", "x8632", "ia32" -> "x86_32";
            case "aarch64", "arm64" -> "aarch_64";
            case "arm", "arm32", "armv7", "armv7l", "armhf" -> "arm_32";
            case "ppc64le" -> "ppcle_64";
            case "ppc64" -> "ppc_64";
            case "s390x" -> "s390_64";
            case "riscv64" -> "riscv64";
            case "loongarch64" -> "loongarch_64";
            default -> value.isEmpty() ? "unknown" : value.replaceAll("[^a-z0-9_]", "");
        };
    }

    private static String clean(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
