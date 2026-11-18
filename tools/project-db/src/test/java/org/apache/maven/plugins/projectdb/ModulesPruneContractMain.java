/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.maven.plugins.projectdb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Java 17 contract oracle: prune excluded directories without changing module discovery results. */
public final class ModulesPruneContractMain {
    private static final Set<String> SKIP = Set.of(
            "target", "build", ".gradle", ".git", ".mvn", "node_modules", "src", ".m3");
    private static final Set<String> DESCRIPTORS = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");
    private static int checks;

    private ModulesPruneContractMain() {}

    public static void main(String[] ignored) throws Exception {
        Path root = Files.createTempDirectory("m3-projectdb-prune-");
        try {
            fixture(root);
            List<Path> actual = Modules.discover(root);
            check(actual.equals(legacyOracle(root)), "exact legacy observable result: " + actual);
            check(actual.equals(List.of(root, root.resolve("a"), root.resolve("a/b"),
                    root.resolve("a/b/c/d/e"))), "deterministic project and module identity");
            check(actual.equals(Modules.discover(root)), "repeat discovery does not change order");
            System.out.println("PASS: " + checks + " bounded Gradle/Maven module-pruning oracle cases");
        } finally {
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }

    private static void fixture(Path root) throws IOException {
        write(root.resolve("settings.gradle.kts"));
        write(root.resolve("pom.xml"));
        write(root.resolve("build.gradle"));
        write(root.resolve("a/build.gradle.kts"));
        write(root.resolve("a/pom.xml"));
        write(root.resolve("a/b/settings.gradle"));
        write(root.resolve("a/b/c/d/e/pom.xml"));
        write(root.resolve("a/b/c/d/e/f/build.gradle"));
        for (String ignored : SKIP) {
            write(root.resolve(ignored + "/build.gradle"));
            write(root.resolve("a/" + ignored + "/pom.xml"));
            write(root.resolve("a/b/" + ignored + "/x/settings.gradle.kts"));
        }
    }

    private static List<Path> legacyOracle(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root, 6)) {
            return walk.filter(Files::isRegularFile).filter(ModulesPruneContractMain::descriptor)
                    .map(Path::getParent).filter(path -> included(root, path))
                    .distinct().sorted().toList();
        }
    }

    private static boolean descriptor(Path file) {
        return file.getFileName() != null && DESCRIPTORS.contains(file.getFileName().toString());
    }

    private static boolean included(Path root, Path module) {
        for (Path part : root.relativize(module)) if (SKIP.contains(part.toString())) return false;
        return true;
    }

    private static void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "descriptor\n");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
