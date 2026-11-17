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
package org.apache.maven.plugins.clustering.lsp;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Where the server profiles read their settings and look for programs: system properties, environment variables and
 * the {@code PATH}. {@link #system()} is the real one; tests pass their own maps.
 */
record Lookup(UnaryOperator<String> properties, UnaryOperator<String> environment) {
    static Lookup system() {
        return new Lookup(System::getProperty, System::getenv);
    }

    /** The system property, else the environment variable; blank values count as unset. */
    Optional<String> setting(String property, String variable) {
        return Stream.of(properties.apply(property), environment.apply(variable))
                .filter(v -> v != null && !v.isBlank()).findFirst().map(String::strip);
    }

    boolean windows() {
        return String.valueOf(properties.apply("os.name")).toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** A setting as a path, if it is a valid one. */
    Optional<Path> path(String property, String variable) {
        return setting(property, variable).flatMap(Lookup::toPath);
    }

    /** An environment variable alone (for example {@code JAVA_HOME}) as a path, if it is a valid one. */
    Optional<Path> variablePath(String variable) {
        return Optional.ofNullable(environment.apply(variable)).filter(v -> !v.isBlank()).map(String::strip)
                .flatMap(Lookup::toPath);
    }

    /** The first program of this name on the {@code PATH}: {@code name.exe} on Windows, an executable file elsewhere. */
    Optional<Path> onPath(String name) {
        return pathDirectories().stream().map(dir -> executableIn(dir, name)).flatMap(Optional::stream).findFirst();
    }

    /** The first {@code PATH} directory that holds a file of exactly this name, for example {@code jdtls.bat}. */
    Optional<Path> fileOnPath(String name) {
        return pathDirectories().stream().map(dir -> dir.resolve(name)).filter(Files::isRegularFile).findFirst();
    }

    /** {@code dir/name.exe} on Windows, {@code dir/name} elsewhere, when it is a runnable file. */
    Optional<Path> executableIn(Path dir, String name) {
        Path file = dir.resolve(windows() ? name + ".exe" : name);
        return Files.isRegularFile(file) && Files.isExecutable(file) ? Optional.of(file.toAbsolutePath()) : Optional.empty();
    }

    /** The java launcher of the JVM that runs this code. */
    Path runningJava() {
        return Path.of(properties.apply("java.home"), "bin", windows() ? "java.exe" : "java");
    }

    List<Path> pathDirectories() {
        String path = environment.apply("PATH");
        if (path == null) return List.of();
        return Arrays.stream(path.split(windows() ? ";" : ":")).map(s -> s.strip().replace("\"", ""))
                .filter(s -> !s.isEmpty()).map(Lookup::toPath).flatMap(Optional::stream).collect(Collectors.toList());
    }

    private static Optional<Path> toPath(String value) {
        try {
            return Optional.of(Path.of(value));
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }
}
