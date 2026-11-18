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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The check after a CMake install: {@code cmake --install} lists every file it wrote in
 * {@code <build>/install_manifest.txt} (or {@code install_manifest_<component>.txt}) as the path the install meant,
 * with DESTDIR taken off again (CMake 4.3: {@code /usr/local/lib/libfx.a}); the file itself lies at DESTDIR plus that
 * path without its drive letter. DESTDIR keeps absolute destinations inside the stage; a destination that climbs out
 * with {@code ..} is refused before the install from the generated scripts ({@link #planned}) and, should one escape
 * that reading, the install fails afterwards from the manifest ({@link #check}), naming the files.
 */
public final class InstallManifest {
    private static final Pattern DESTINATION = Pattern.compile("DESTINATION \"([^\"]*)\"");
    private static final Pattern VARIABLE = Pattern.compile("\\$(ENV)?\\{[^}]*\\}");

    private InstallManifest() {}

    /** The manifest entries, as written, that lie outside the stage. */
    public static List<String> outside(Layout layout, String buildDirectory) throws IOException {
        Path build = layout.path(buildDirectory);
        Path stage = layout.path(layout.stage());
        if (!Files.isDirectory(build)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(build)) {
            List<Path> manifests = list.filter(p -> p.getFileName().toString().matches("install_manifest.*[.]txt"))
                    .sorted().toList();
            return manifests.stream().flatMap(InstallManifest::entries).filter(e -> !inside(e, stage)).toList();
        }
    }

    /**
     * The install destinations, as the generated {@code cmake_install.cmake} scripts below the build tree spell them,
     * that would leave the stage under DESTDIR with {@code prefix}: checked before {@code cmake --install} runs, so a
     * literal {@code ..} destination is refused before anything is written. A destination built from other variables
     * is judged with each of them as one plain folder; {@link #check} afterwards remains the backstop.
     */
    public static List<String> planned(Layout layout, String buildDirectory, String prefix) throws IOException {
        Path build = layout.path(buildDirectory);
        Path stage = layout.path(layout.stage());
        if (!Files.isDirectory(build)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(build)) {
            List<Path> scripts = walk.filter(p -> p.getFileName().toString().equals("cmake_install.cmake")).sorted()
                    .toList();
            return scripts.stream().flatMap(InstallManifest::destinations).distinct()
                    .filter(d -> !inside(resolved(d, prefix) + "/file", stage)).toList();
        }
    }

    /** Fails before the install when a destination would leave the stage. */
    public static void refusePlanned(Layout layout, String buildDirectory, String prefix) throws IOException {
        List<String> escaping = planned(layout, buildDirectory, prefix);
        if (!escaping.isEmpty()) {
            throw new IOException("refused cmake --install: these install destinations leave the stage "
                    + layout.path(layout.stage()) + " even under DESTDIR: " + escaping + "; fix their '..' segments");
        }
    }

    private static Stream<String> destinations(Path script) {
        try {
            return DESTINATION.matcher(Files.readString(script, StandardCharsets.UTF_8)).results().map(m -> m.group(1));
        } catch (IOException unreadable) {
            return Stream.of(script + " (unreadable: " + unreadable.getMessage() + ")");
        }
    }

    private static String resolved(String destination, String prefix) {
        return VARIABLE.matcher(destination.replace("${CMAKE_INSTALL_PREFIX}", prefix)).replaceAll("x");
    }

    /** Fails when the install wrote outside the stage. */
    public static void check(Layout layout, String buildDirectory) throws IOException {
        List<String> escaped = outside(layout, buildDirectory);
        if (!escaped.isEmpty()) {
            throw new IOException("cmake --install wrote outside the stage " + layout.path(layout.stage()) + ": "
                    + escaped + "; fix the install(... DESTINATION) of these files (a relative destination with '..' "
                    + "leaves even DESTDIR)");
        }
    }

    private static Stream<String> entries(Path manifest) {
        try {
            return Files.readAllLines(manifest, StandardCharsets.UTF_8).stream().map(String::strip)
                    .filter(l -> !l.isEmpty());
        } catch (IOException unreadable) {
            return Stream.of(manifest + " (unreadable: " + unreadable.getMessage() + ")");
        }
    }

    /** True when DESTDIR (the stage) plus the entry, without its drive letter as CMake joins them, stays inside. */
    private static boolean inside(String entry, Path stage) {
        String relative = entry.replace('\\', '/').replaceFirst("^[A-Za-z]:", "").replaceAll("^/+", "");
        try {
            return !relative.isEmpty() && FileTrees.strictlyBelow(stage.resolve(relative), stage);
        } catch (InvalidPathException unusable) {
            return false;
        }
    }
}
