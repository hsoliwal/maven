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
package org.apache.maven.plugins.projectdb;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Module directories of a project outside Maven (CLI, MCP): the root and every directory below it that holds a
 * a Maven or Gradle build descriptor. Prune build output and VCS folders before walking their entries;
 * inside Maven, the reactor still gives the authoritative modules.
 */
final class Modules {
    private static final int DEPTH = 6;
    private static final Set<String> SKIP = Set.of("target", "build", ".gradle", ".git", ".mvn",
            "node_modules", "src", ".m3");

    private Modules() {}

    static List<Path> discover(Path root) throws IOException {
        Path project = root.toAbsolutePath().normalize();
        List<Path> modules = new ArrayList<>();
        Files.walkFileTree(project, Set.of(), DEPTH, visitor(project, modules));
        return modules.stream().distinct().sorted().toList();
    }

    /** Reject output and vendor subtrees before opening their descendants. */
    private static SimpleFileVisitor<Path> visitor(Path project, List<Path> modules) {
        return new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return !directory.equals(project) && SKIP.contains(directory.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile() && descriptor(file)) modules.add(file.getParent());
                return FileVisitResult.CONTINUE;
            }
        };
    }

    /** Native Gradle and Maven module boundaries, without treating output folders as modules. */
    private static boolean descriptor(Path path) {
        if (path.getFileName() == null) return false;
        return switch (path.getFileName().toString()) {
            case "pom.xml", "build.gradle", "build.gradle.kts",
                    "settings.gradle", "settings.gradle.kts" -> true;
            default -> false;
        };
    }

}
