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
package org.apache.maven.plugins.clustering;

import static org.apache.maven.plugins.clustering.InlinePluginTest.check;
import static org.apache.maven.plugins.clustering.InlinePluginTest.compile;
import static org.apache.maven.plugins.clustering.InlinePluginTest.sameAsScan;
import static org.apache.maven.plugins.clustering.InlinePluginTest.sources;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The review findings on the javac plugin, each reproduced before its fix: rows kept by source file (a package change
 * leaves one file's rows once), {@code full=true} replacing the tables (a file no longer compiled drops out), table
 * paths that collide (default-package files in different directories: kept apart, and identical to the scan with
 * {@code roots}), arguments given as file URIs (two consecutive spaces in a path), generated sources in their own
 * tables, and failure isolation for errors such as {@link StackOverflowError}. Run from {@link InlinePluginTest}.
 */
final class InlineRegressionTest {
    private static final String SUM = "    int sum(int[] values) {\n        int total = 0;\n"
            + "        for (int v : values) {\n            total += v;\n        }\n        return total;\n    }\n";

    private InlineRegressionTest() { }

    static void run(Path dir) throws Exception {
        packageChange(dir.resolve("package-change"));
        full(dir.resolve("full"));
        collisions(dir.resolve("collisions"));
        uris(dir.resolve("uris"));
        generated(dir.resolve("generated"));
        guarded();
    }

    /** The same file compiled under package p, then under package q: its rows are filed once, under the new path. */
    private static void packageChange(Path dir) throws Exception {
        Path file = dir.resolve("src/p/B.java");
        Path out = dir.resolve("out");
        write(file, "package p;\n\nclass B {\n" + SUM + "}\n");
        check("package change: first compile green", compile(List.of(file), dir.resolve("c"), "out=" + out));
        write(file, "package q;\n\nclass B {\n" + SUM + "}\n");
        check("package change: second compile green", compile(List.of(file), dir.resolve("c"), "out=" + out));
        check("package change: one source row", lines(out.resolve("ATOM_SOURCES.tsv")).size() == 2);
        check("package change: atoms of one path only", paths(out).equals(List.of("B.java")));
    }

    /** full=true: the tables hold this compilation only, so a file left out of it drops out; merging keeps it. */
    private static void full(Path dir) throws Exception {
        Path src = dir.resolve("src");
        write(src.resolve("x/X.java"), "package x;\n\nclass X {\n" + SUM + "}\n");
        write(src.resolve("x/Y.java"), "package x;\n\nclass Y {\n" + SUM + "}\n");
        Path merged = dir.resolve("merged");
        Path replaced = dir.resolve("replaced");
        compile(sources(src), dir.resolve("c1"), "out=" + merged);
        compile(sources(src), dir.resolve("c2"), "out=" + replaced + " full=true");
        List<Path> onlyX = List.of(src.resolve("x/X.java"));
        check("full: compile green", compile(onlyX, dir.resolve("c2"), "out=" + replaced + " full=true"));
        compile(onlyX, dir.resolve("c1"), "out=" + merged);
        check("full: file no longer compiled dropped", paths(replaced).equals(List.of("x/X.java")));
        check("full: merging keeps an existing file not compiled", paths(merged).equals(List.of("x/X.java", "x/Y.java")));
    }

    /**
     * Default-package files of the same name in two directories: with {@code roots} the tables equal the scan; without
     * them the paths collide, and both files keep their atoms under their absolute paths. Across compilations into one
     * output, a kept file whose path a new file takes keeps its atoms under its absolute path.
     */
    private static void collisions(Path dir) throws Exception {
        Path src = dir.resolve("src");
        write(src.resolve("a/Util.java"), "class UtilA {\n" + SUM + "}\n");
        write(src.resolve("b/Util.java"), "class UtilB {\n" + SUM.replace("total", "acc") + "}\n");
        Path rooted = dir.resolve("rooted");
        check("collisions: roots compile green", compile(sources(src), dir.resolve("c1"), "out=" + rooted + " roots=" + src));
        check("collisions: with roots identical to the scan", sameAsScan(dir, src, rooted, "rooted-cli"));
        Path bare = dir.resolve("bare");
        check("collisions: no roots compile green", compile(sources(src), dir.resolve("c2"), "out=" + bare));
        check("collisions: both files kept apart", symbols(bare).containsAll(List.of("UtilA", "UtilB"))
                && lines(bare.resolve("ATOM_SOURCES.tsv")).size() == 3);
        Path across = dir.resolve("across");
        compile(List.of(src.resolve("a/Util.java")), dir.resolve("c3"), "out=" + across);
        compile(List.of(src.resolve("b/Util.java")), dir.resolve("c3"), "out=" + across);
        check("collisions: across compilations both kept", symbols(across).containsAll(List.of("UtilA", "UtilB")));
    }

    /** out and roots as file URIs: a directory with two consecutive spaces (javac splits arguments at white space). */
    private static void uris(Path dir) throws Exception {
        Path src = dir.resolve("two  spaces/src");
        write(src.resolve("p/A.java"), "package p;\n\nclass A {\n" + SUM + "}\n");
        Path out = dir.resolve("two  spaces/out");
        String args = "out=" + out.toUri() + " roots=" + src.toUri();
        check("uris: compile green", compile(sources(src), dir.resolve("c"), args));
        check("uris: tables in the directory with two spaces", Files.isRegularFile(out.resolve("ATOM_HASHES.tsv")));
        check("uris: identical to the scan", Files.isDirectory(out) && sameAsScan(dir, src, out, "uris-cli"));
    }

    /**
     * Sources under {@code generated} go to {@code out/generated}, filed relative to that directory; the main tables
     * equal the scan of the hand-written root. A full compilation without generated sources removes those tables.
     */
    private static void generated(Path dir) throws Exception {
        Path src = dir.resolve("src");
        Path gen = dir.resolve("gen");
        write(src.resolve("p/A.java"), "package p;\n\nclass A {\n" + SUM + "}\n");
        write(gen.resolve("p/AGen.java"), "package p;\n\nclass AGen {\n" + SUM + "}\n");
        Path out = dir.resolve("out");
        String args = "out=" + out.toUri() + " roots=" + src.toUri() + " generated=" + gen.toUri() + " full=true";
        List<Path> both = List.of(src.resolve("p/A.java"), gen.resolve("p/AGen.java"));
        check("generated: compile green", compile(both, dir.resolve("c"), args));
        check("generated: main tables identical to the scan", sameAsScan(dir, src, out, "main-cli"));
        check("generated: own tables identical to the scan", Files.isDirectory(out.resolve("generated"))
                && sameAsScan(dir, gen, out.resolve("generated"), "gen-cli"));
        compile(List.of(src.resolve("p/A.java")), dir.resolve("c"), args);
        check("generated: full compile without them removes their tables",
                !Files.exists(out.resolve("generated/ATOM_HASHES.tsv")));
    }

    /** Failures, including errors such as a stack overflow, are logged; other virtual machine errors propagate. */
    private static void guarded() {
        check("guard: StackOverflowError logged", InlineClustering.guarded("test", () -> {
            throw new StackOverflowError("deep");
        }));
        check("guard: AssertionError logged", InlineClustering.guarded("test", () -> {
            throw new AssertionError("walker");
        }));
        check("guard: OutOfMemoryError propagates", propagates(() -> {
            throw new OutOfMemoryError("heap");
        }));
        check("guard: success", !InlineClustering.guarded("test", () -> { }));
    }

    private static boolean propagates(InlineClustering.Step step) {
        try {
            InlineClustering.guarded("test", step);
            return false;
        } catch (OutOfMemoryError expected) {
            return true;
        }
    }

    private static List<String> paths(Path out) throws IOException {
        return atoms(out).stream().map(AtomPlanes.Atom::path).distinct().collect(Collectors.toList());
    }

    private static List<String> symbols(Path out) throws IOException {
        return atoms(out).stream().map(AtomPlanes.Atom::symbol).collect(Collectors.toList());
    }

    private static List<AtomPlanes.Atom> atoms(Path out) throws IOException {
        Path table = out.resolve("ATOM_HASHES.tsv");
        return Files.isRegularFile(table) ? PlaneTables.readAtoms(table) : List.of();
    }

    private static List<String> lines(Path table) throws IOException {
        return Files.isRegularFile(table) ? Files.readAllLines(table, StandardCharsets.UTF_8) : List.of();
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}
