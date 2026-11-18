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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Finds the build data a module already has. Nothing is generated here: a source exists only when its producer
 * (Surefire, the dependency plugin, the code-clustering plugin, the polyglot plugin, a build-events writer) wrote it.
 */
final class Sources {
    /**
     * The polyglot module-state marker (active backend and generation) and the state of every built-in backend;
     * lock files are coordination only. Their content hash decides whether the receipts are read again.
     */
    private static final List<String> POLYGLOT = List.of(".m3/module.m3db.state", ".m3/module.m3db",
            ".m3/module-state-v1.bin", ".m3/maven-build-db", ".m3/module-state.sqlite", ".m3/module-state.mv.db");
    private static final List<String> REPORTS = List.of("target/surefire-reports", "target/failsafe-reports");
    private static final List<String> POMS = List.of("target/effective-pom.xml", "target/project-db/effective-pom.xml");

    private Sources() {}

    /** Every source of one module, in a stable order. */
    static List<Source> discover(Path root, Path module) throws IOException {
        String name = moduleName(root, module);
        List<Source> sources = new ArrayList<>();
        polyglot(root, module, name, sources);
        for (String reports : REPORTS) {
            for (Path report : list(module.resolve(reports), "TEST-", ".xml")) {
                sources.add(Source.of(name, relative(root, report), "surefire", List.of(report)));
            }
        }
        firstExisting(root, module, name, POMS, "pom", sources);
        firstExisting(root, module, name, List.of("target/dependency-tree.txt"), "dependency-tree", sources);
        clustering(root, module, name, sources);
        events(root, module, name, sources);
        return sources;
    }

    /** The build log given to an ingest, as a root-level source. */
    static Source buildLog(Path root, Path log) throws IOException {
        Path file = log.toAbsolutePath().normalize();
        String path = file.startsWith(root) ? relative(root, file) : file.getFileName().toString();
        return Source.of(".", path, "build-log", List.of(file));
    }

    static String moduleName(Path root, Path module) {
        String name = relative(root, module);
        return name.isEmpty() ? "." : name;
    }

    static String relative(Path root, Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static void polyglot(Path root, Path module, String name, List<Source> sources) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String state : POLYGLOT) files.addAll(regularFiles(module.resolve(state)));
        if (!files.isEmpty()) sources.add(Source.of(name, relative(root, module.resolve(".m3")), "polyglot", files));
    }

    private static void clustering(Path root, Path module, String name, List<Source> sources) throws IOException {
        Path directory = module.resolve("target/code-clustering");
        Path atoms = directory.resolve("ATOM_HASHES.tsv");
        if (!Files.isRegularFile(atoms)) return;
        List<Path> files = new ArrayList<>(List.of(atoms));
        Path clusters = directory.resolve("CLUSTERS.tsv");
        if (Files.isRegularFile(clusters)) files.add(clusters);
        sources.add(Source.of(name, relative(root, directory), "clustering", files));
    }

    private static void events(Path root, Path module, String name, List<Source> sources) throws IOException {
        List<Path> files = new ArrayList<>(list(module.resolve("target/build-events"), "", ".jsonl"));
        files.addAll(list(module.resolve("target"), "build-events", ".jsonl"));
        for (Path file : files) sources.add(Source.of(name, relative(root, file), "events", List.of(file)));
    }

    private static void firstExisting(Path root, Path module, String name, List<String> candidates, String kind,
            List<Source> sources) throws IOException {
        for (String candidate : candidates) {
            Path file = module.resolve(candidate);
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                sources.add(Source.of(name, relative(root, file), kind, List.of(file)));
                return;
            }
        }
    }

    /** Regular files directly in a directory whose names have the prefix and suffix, sorted. */
    static List<Path> list(Path directory, String prefix, String suffix) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    .filter(file -> file.getFileName().toString().startsWith(prefix)
                            && file.getFileName().toString().endsWith(suffix))
                    .sorted().toList();
        }
    }

    /** A regular file, or every regular file under a directory, sorted. */
    private static List<Path> regularFiles(Path path) throws IOException {
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return List.of(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (Stream<Path> walk = Files.walk(path)) {
            return walk.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
        }
    }
}
