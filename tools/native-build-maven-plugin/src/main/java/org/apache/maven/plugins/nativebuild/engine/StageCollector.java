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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Sorts an install stage into static libraries (.a, .lib), shared libraries (.so*, .dylib, .dll and their .dll.a or
 * .lib import libraries), executables (.exe, files in bin, ELF and Mach-O files) and headers (the include tree), so
 * they can be attached together or as separate archives.
 */
public final class StageCollector {
    private static final Pattern SHARED = Pattern.compile("(?i).*\\.(so(\\.\\d+)*|dylib|dll|dll\\.a)$");
    private static final Pattern HEADER = Pattern.compile("(?i).*\\.(h|hh|hpp|hxx|h\\+\\+|inl|ipp|tcc|modulemap)$");

    /**
     * The classified files, as paths relative to {@code root} with forward slashes, each list sorted.
     *
     * @param root the stage root (prefix stripped)
     * @param staticLibraries static libraries
     * @param sharedLibraries shared and import libraries
     * @param executables programs
     * @param headers the include tree
     * @param other everything else (pkg-config and CMake package files, documentation)
     */
    public record Staged(Path root, List<String> staticLibraries, List<String> sharedLibraries,
            List<String> executables, List<String> headers, List<String> other) {
        /** Every file, sorted. */
        public List<String> all() {
            return Stream.of(staticLibraries, sharedLibraries, executables, headers, other).flatMap(List::stream)
                    .sorted().toList();
        }
    }

    private StageCollector() {}

    /** The stage root: {@code stage/<prefix>} when an install wrote under DESTDIR, else the stage itself. */
    public static Path root(Path stage, String prefix) {
        String relative = prefix.replace('\\', '/').replaceFirst("^[A-Za-z]:", "").replaceAll("^/+", "");
        Path prefixed = relative.isEmpty() ? stage : stage.resolve(relative);
        return Files.isDirectory(prefixed) ? prefixed : stage;
    }

    /** Classifies every regular file under {@code root}; an absent root holds nothing. */
    public static Staged classify(Path root) throws IOException {
        List<String> files = files(root);
        Set<String> dllStems = files.stream().filter(f -> f.toLowerCase(Locale.ROOT).endsWith(".dll"))
                .map(f -> WindowsPaths.name(f).replaceAll("(?i)\\.dll$", "").toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        List<List<String>> groups = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>());
        for (String file : files) {
            groups.get(kind(root, file, dllStems)).add(file);
        }
        return new Staged(root, groups.get(0), groups.get(1), groups.get(2), groups.get(3), groups.get(4));
    }

    /**
     * Copies declared outputs into the stage for build systems without an install step: libraries to lib, headers to
     * include, anything else to bin. Returns the staged paths, relative to the stage.
     */
    public static List<String> copyOutputs(Path basedir, List<String> outputs, Path stage) throws IOException {
        List<String> staged = new ArrayList<>();
        for (String output : outputs) {
            Path source = basedir.resolve(output).normalize();
            if (Files.isRegularFile(source)) {
                String name = source.getFileName().toString();
                String folder = HEADER.matcher(name).matches() ? "include" : isLibrary(name) ? "lib" : "bin";
                Path target = stage.resolve(folder).resolve(name);
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                staged.add(folder + "/" + name);
            }
        }
        return staged;
    }

    private static int kind(Path root, String file, Set<String> dllStems) {
        String name = WindowsPaths.name(file).toLowerCase(Locale.ROOT);
        if (file.startsWith("include/") || HEADER.matcher(name).matches()) {
            return 3;
        }
        if (SHARED.matcher(name).matches() || (name.endsWith(".lib") && dllStems.contains(name.replace(".lib", "")))) {
            return 1;
        }
        if (name.endsWith(".a") || name.endsWith(".lib")) {
            return 0;
        }
        return name.endsWith(".exe") || file.startsWith("bin/") || binary(root.resolve(file)) ? 2 : 4;
    }

    private static boolean isLibrary(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return SHARED.matcher(lower).matches() || lower.endsWith(".a") || lower.endsWith(".lib");
    }

    private static boolean binary(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] magic = in.readNBytes(4);
            int word = magic.length == 4 ? ((magic[0] & 0xff) << 24) | ((magic[1] & 0xff) << 16)
                    | ((magic[2] & 0xff) << 8) | (magic[3] & 0xff) : 0;
            return word == 0x7f454c46 || word == 0xfeedface || word == 0xfeedfacf || word == 0xcefaedfe
                    || word == 0xcffaedfe;
        } catch (IOException unreadable) {
            return false;
        }
    }

    private static List<String> files(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).map(p -> WindowsPaths.relative(root, p)).sorted().toList();
        }
    }
}
