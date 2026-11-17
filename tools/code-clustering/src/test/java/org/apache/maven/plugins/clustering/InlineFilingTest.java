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
import static org.apache.maven.plugins.clustering.InlineRegressionTest.write;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Where the javac plugin files atoms, the second review's findings, each failing before its fix: colliding files
 * filed the same way whatever the build history, under a root index rather than a machine path; source files recorded
 * relative to the tables, so a moved workspace keeps its rows; several roots in the scan's order; package directories
 * named like build output kept by the scan (which still skips a project's output); and {@link InlineClustering#prune}
 * dropping a deleted file's rows without a compilation. Run from {@link InlinePluginTest}.
 */
final class InlineFilingTest {
    private static final String SUM = "    int sum(int[] values) {\n        int total = 0;\n"
            + "        for (int v : values) {\n            total += v;\n        }\n        return total;\n    }\n";
    private static final List<String> OUTPUT_NAMES = List.of("build", "target", "out", "dist", "vendor", "venv",
            "node_modules");

    private InlineFilingTest() { }

    static void run(Path dir) throws Exception {
        collisionHistory(dir.resolve("history"));
        movedWorkspace(dir.resolve("moved"));
        rootOrder(dir.resolve("order"));
        outputNamedPackages(dir.resolve("packages"));
        projectOutputSkipped(dir.resolve("project"));
        prune(dir.resolve("prune"));
    }

    /**
     * Default-package {@code Util.java} in two roots, compiled full, r1 alone, r2 alone, full again: the same table
     * paths every time, {@code @0/Util.java} and {@code @1/Util.java}, and sources recorded without a machine path.
     */
    private static void collisionHistory(Path dir) throws Exception {
        Path r1 = dir.resolve("r1/Util.java");
        Path r2 = dir.resolve("r2/Util.java");
        write(r1, "class UtilA {\n" + SUM + "}\n");
        write(r2, "class UtilB {\n" + SUM.replace("total", "acc") + "}\n");
        Path out = dir.resolve("out");
        String args = "out=" + out.toUri() + " roots=" + r1.getParent().toUri() + " roots=" + r2.getParent().toUri();
        List<List<String>> seen = Stream.of(List.of(r1, r2), List.of(r1), List.of(r2), List.of(r1, r2))
                .map(files -> compiledPaths(files, dir.resolve("c"), out, args + (files.size() > 1 ? " full=true" : "")))
                .collect(Collectors.toList());
        check("collisions: same paths after full, partial, partial, full " + seen, seen.stream().distinct().count() == 1);
        check("collisions: filed by root index", seen.get(0).equals(List.of("@0/Util.java", "@1/Util.java")));
        check("collisions: four atoms kept", atoms(out).size() == 4);
        check("sources relative to the tables", sourceColumn(out).stream().noneMatch(s -> Path.of(s).isAbsolute()));
    }

    /** A workspace moved after a full compile: a partial compile of one file keeps the other file's rows. */
    private static void movedWorkspace(Path dir) throws Exception {
        Path before = dir.resolve("before");
        write(before.resolve("src/x/X.java"), "package x;\n\nclass X {\n" + SUM + "}\n");
        write(before.resolve("src/x/Y.java"), "package x;\n\nclass Y {\n" + SUM + "}\n");
        String args = "out=" + before.resolve("out").toUri() + " roots=" + before.resolve("src").toUri();
        compile(sources(before.resolve("src")), before.resolve("c"), args + " full=true");
        Path after = dir.resolve("after");
        Files.move(before, after);
        String moved = "out=" + after.resolve("out").toUri() + " roots=" + after.resolve("src").toUri();
        check("moved workspace: partial compile green", compile(List.of(after.resolve("src/x/X.java")), after.resolve("c"), moved));
        check("moved workspace: kept rows survive", sameAsScan(dir, after.resolve("src"), after.resolve("out"), "moved-cli"));
    }

    /** Two roots whose files interleave in path order: the tables equal the scan of both roots. */
    private static void rootOrder(Path dir) throws Exception {
        Path r1 = dir.resolve("r1");
        Path r2 = dir.resolve("r2");
        write(r1.resolve("z/Z.java"), "package z;\n\nclass Z {\n" + SUM + "}\n");
        write(r2.resolve("a/A.java"), "package a;\n\nclass A {\n" + SUM.replace("total", "acc") + "}\n");
        Path out = dir.resolve("out");
        List<Path> files = List.of(r1.resolve("z/Z.java"), r2.resolve("a/A.java"));
        check("two roots: compile green", compile(files, dir.resolve("c"),
                "out=" + out.toUri() + " roots=" + r1.toUri() + " roots=" + r2.toUri() + " full=true"));
        check("two roots: identical to the scan of both", sameAsScan(dir, List.of(r1, r2), out, "order-cli"));
    }

    /** Packages named build, target, out, dist, vendor, venv, node_modules inside a source root: the scan keeps them. */
    private static void outputNamedPackages(Path dir) throws Exception {
        Path src = dir.resolve("src");
        OUTPUT_NAMES.forEach(n -> write(src.resolve("p/" + n + "/X.java"), "package p." + n + ";\n\nclass X {\n" + SUM + "}\n"));
        write(src.resolve("pom.xml"), "<project/>\n");
        Path out = dir.resolve("out");
        check("output-named packages: compile green", compile(sources(src), dir.resolve("c"),
                "out=" + out.toUri() + " roots=" + src.toUri() + " full=true"));
        check("output-named packages: identical to the scan", sameAsScan(dir, src, out, "packages-cli")
                && paths(out).size() == OUTPUT_NAMES.size());
    }

    /** A project's own output and dependency directories are still skipped by the scan. */
    private static void projectOutputSkipped(Path dir) throws IOException {
        List.of("pom.xml", "web/package.json", "tools/env/venv/pyvenv.cfg", "lib/build.gradle").forEach(f -> write(dir.resolve(f), "\n"));
        List.of("target/gen/G.java", "web/node_modules/m/index.js", "tools/env/venv/lib/v.py", "lib/build/B.java",
                ".git/H.java", "src/main/java/p/build/Kept.java", "web/app.js", "lib/src/out/Kept.java")
                .forEach(f -> write(dir.resolve(f), "class K {\n" + SUM + "}\n"));
        List<String> found = AtomPlanes.sourceFiles(dir, AtomFrontEnd.all()).stream()
                .map(p -> dir.relativize(p).toString().replace('\\', '/')).collect(Collectors.toList());
        System.err.println("  project files: " + found);
        check("project output skipped, packages and sources kept",
                found.equals(List.of("lib/src/out/Kept.java", "src/main/java/p/build/Kept.java", "web/app.js")));
    }

    /** A deleted file of a partial build, pruned without a compilation: the tables equal the scan. */
    private static void prune(Path dir) throws Exception {
        Path src = dir.resolve("src");
        write(src.resolve("x/X.java"), "package x;\n\nclass X {\n" + SUM + "}\n");
        write(src.resolve("x/Y.java"), "package x;\n\nclass Y {\n" + SUM + "}\n");
        Path out = dir.resolve("out");
        String args = "out=" + out.toUri() + " roots=" + src.toUri();
        compile(sources(src), dir.resolve("c"), args);
        Files.delete(src.resolve("x/Y.java"));
        check("prune: deleted file's rows dropped", prune(args) && sameAsScan(dir, src, out, "prune-cli"));
        Files.writeString(out.resolve("PLANE_SUMMARY.tsv"), "marker\n", StandardCharsets.UTF_8);
        check("prune: nothing deleted, tables untouched", prune(args)
                && Files.readString(out.resolve("PLANE_SUMMARY.tsv")).equals("marker\n"));
    }

    /** Calls {@code InlineClustering.prune} as the core extension does, reflectively; false when it is missing. */
    private static boolean prune(String args) throws Exception {
        try {
            Object line = InlineClustering.class.getMethod("prune", String[].class).invoke(null, (Object) args.split(" "));
            System.err.println("  prune: " + line);
            return true;
        } catch (NoSuchMethodException | InvocationTargetException e) {
            System.err.println("  prune: " + e);
            return false;
        }
    }

    private static List<String> compiledPaths(List<Path> files, Path classes, Path out, String args) {
        try {
            compile(files, classes, args);
            return paths(out);
        } catch (IOException e) {
            return List.of(e.toString());
        }
    }

    private static List<String> paths(Path out) throws IOException {
        return atoms(out).stream().map(AtomPlanes.Atom::path).distinct().sorted().collect(Collectors.toList());
    }

    private static List<AtomPlanes.Atom> atoms(Path out) throws IOException {
        Path table = out.resolve("ATOM_HASHES.tsv");
        return Files.isRegularFile(table) ? PlaneTables.readAtoms(table) : List.of();
    }

    private static List<String> sourceColumn(Path out) throws IOException {
        return Files.readAllLines(out.resolve("ATOM_SOURCES.tsv")).stream().skip(1).map(r -> r.split("\t")[1])
                .collect(Collectors.toList());
    }
}
