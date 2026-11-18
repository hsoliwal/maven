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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Module directories of a project outside Maven (CLI, MCP): the root and every directory below it that holds a
 * {@code pom.xml}, skipping build output and VCS folders. Inside Maven the reactor gives the modules instead.
 */
final class Modules {
    private static final int DEPTH = 6;
    private static final Set<String> SKIP = Set.of("target", "build", ".gradle", ".git", ".mvn",
            "node_modules", "src", ".m3");

    private Modules() {}

    static List<Path> discover(Path root) throws IOException {
        Path project = root.toAbsolutePath().normalize();
        try (Stream<Path> walk = Files.walk(project, DEPTH)) {
            return walk.filter(Modules::descriptor)
                    .map(Path::getParent)
                    .filter(module -> project.relativize(module).toString().isEmpty() || included(project, module))
                    .distinct().sorted().toList();
        }
    }

    /** Native Gradle and Maven module boundaries, without treating output folders as modules. */
    private static boolean descriptor(Path path) {
        if (path.getFileName() == null || !Files.isRegularFile(path)) return false;
        return switch (path.getFileName().toString()) {
            case "pom.xml", "build.gradle", "build.gradle.kts",
                    "settings.gradle", "settings.gradle.kts" -> true;
            default -> false;
        };
    }

    /** True when no directory between the root and the module is a skipped folder. */
    private static boolean included(Path root, Path module) {
        for (Path part : root.relativize(module)) {
            if (SKIP.contains(part.toString())) return false;
        }
        return true;
    }
}
