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
import static org.apache.maven.plugins.clustering.InlinePluginTest.location;
import static org.apache.maven.plugins.clustering.InlinePluginTest.sources;
import static org.apache.maven.plugins.clustering.InlineRegressionTest.write;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The third review's findings on the javac plugin, each failing before its fix: every class of the plugin missing or
 * compiled for a newer Java in turn (javac green, at most one warning, the tables of the compilation or none, never an
 * earlier or a mixed set); a table that cannot be replaced (no mixed set, the merging case marked incomplete); a
 * collision warned once, not on every compilation; every source of a root deleted and pruned (no rows left). Run from
 * {@link InlinePluginTest}.
 */
final class InlineSafetyTest {
    private static final String SUM = "    int sum(int[] values) {\n        int total = 0;\n"
            + "        for (int v : values) {\n            total += v;\n        }\n        return total;\n    }\n";
    private static final String PACKAGE_DIR = "org/apache/maven/plugins/clustering/";
    private static final String SERVICE = "META-INF/services/com.sun.source.util.Plugin";
    /** Class file major version minus this is the Java feature release. */
    private static final int MAJOR_JAVA_OFFSET = 44;
    private static final Logger PLUGIN_LOGS = Logger.getLogger("org.apache.maven.plugins.clustering");

    private InlineSafetyTest() { }

    static void run(Path dir) throws Exception {
        variants(dir.resolve("variants"));
        unreplaceable(dir.resolve("unreplaceable"));
        collisionWarnings(dir.resolve("collisions"));
        allDeleted(dir.resolve("all-deleted"));
    }

    /**
     * Each class of the plugin but {@code InlineClustering} (which the core extension checks before it wires the
     * plugin) dropped, and compiled for a Java newer than this one, in turn, plus the whole plugin as a control: javac
     * runs in a new virtual machine that loads the plugin from each jar only ({@link InlineVariantDriver}), over an
     * output holding the tables of an earlier compilation.
     */
    private static void variants(Path dir) throws Exception {
        Path src = dir.resolve("src");
        Path earlier = dir.resolve("earlier");
        compile(fixture(src, "first"), dir.resolve("c1"), "out=" + earlier.toUri() + " roots=" + src.toUri() + " full=true");
        Path reference = dir.resolve("work/reference"); // the depth of each variant's output: same relative sources
        List<Path> files = fixture(src, "second");
        check("variants: reference compile green", compile(files, dir.resolve("c2"), "out=" + reference.toUri()
                + " roots=" + src.toUri() + " full=true"));
        List<Path> jars = jars(dir.resolve("jars"));
        List<String> lines = driver(dir, jars, files, src, earlier, reference);
        lines.stream().filter(l -> !fine(l)).forEach(l -> System.err.println("  " + l));
        check("variants: " + jars.size() + " jars, javac green, at most one warning, tables new or absent",
                lines.size() == jars.size() && lines.stream().allMatch(InlineSafetyTest::fine));
        check("variants: the whole plugin writes the new tables", lines.contains("VARIANT whole.jar javac=green warnings=0 tables=new"));
    }

    /** Sources with a type, methods, a record and a native method; {@code version} changes their atoms. */
    private static List<Path> fixture(Path src, String version) throws IOException {
        write(src.resolve("p/A.java"), "package p;\n\nclass A {\n" + SUM.replace("sum", version) + "    record R(int x) { }\n}\n");
        write(src.resolve("p/N.java"), "package p;\n\nclass N {\n    native int " + version + "(String s, int[] v);\n}\n");
        write(src.resolve("q/B.java"), "package q;\n\nclass B {\n" + SUM + "}\n");
        return sources(src);
    }

    private static boolean fine(String line) {
        return line.contains(" javac=green ") && (line.contains(" warnings=0 ") || line.contains(" warnings=1 "))
                && (line.endsWith(" tables=new") || line.endsWith(" tables=absent"));
    }

    /** The jars: the whole plugin, then each class dropped and each class made newer. */
    private static List<Path> jars(Path dir) throws IOException {
        Path classes = Path.of(location(InlineClustering.class));
        List<String> entries;
        try (Stream<Path> walk = Files.walk(classes.resolve(PACKAGE_DIR))) {
            entries = walk.filter(p -> p.toString().endsWith(".class")).map(p -> classes.relativize(p).toString()
                    .replace('\\', '/')).sorted().collect(Collectors.toList());
        }
        List<Path> jars = new ArrayList<>(List.of(jar(dir.resolve("whole.jar"), classes, entries, "", false)));
        entries.stream().filter(e -> !e.equals(PACKAGE_DIR + "InlineClustering.class")).forEach(e -> {
            String name = e.substring(PACKAGE_DIR.length()).replace(".class", "").replace('$', '_');
            jars.add(jar(dir.resolve("drop-" + name + ".jar"), classes, entries, e, false));
            jars.add(jar(dir.resolve("newer-" + name + ".jar"), classes, entries, e, true));
        });
        return jars;
    }

    /** A plugin jar of the class entries and the registration; {@code changed} left out, or made newer. */
    private static Path jar(Path jar, Path classes, List<String> entries, String changed, boolean newer) {
        try {
            Files.createDirectories(jar.getParent());
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
                entry(out, SERVICE, (InlineClustering.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
                for (String e : entries) {
                    if (!e.equals(changed) || newer) entry(out, e, bytes(classes.resolve(e), e.equals(changed)));
                }
            }
            return jar;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The class file; {@code newer}: its major version one above what this Java runs. */
    private static byte[] bytes(Path file, boolean newer) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int major = Runtime.version().feature() + MAJOR_JAVA_OFFSET + 1;
        if (newer) {
            bytes[6] = (byte) (major >> 8);
            bytes[7] = (byte) major;
        }
        return bytes;
    }

    private static void entry(OutputStream out, String name, byte[] bytes) throws IOException {
        ((JarOutputStream) out).putNextEntry(new JarEntry(name));
        out.write(bytes);
        ((JarOutputStream) out).closeEntry();
    }

    /** Runs {@link InlineVariantDriver} in a new virtual machine with the test classes only; its output lines. */
    private static List<String> driver(Path dir, List<Path> jars, List<Path> files, Path src, Path earlier,
            Path reference) throws Exception {
        Path jarList = list(dir.resolve("jars.txt"), jars);
        Path sourceList = list(dir.resolve("sources.txt"), files);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process driver = new ProcessBuilder(java, "-cp", location(InlineVariantDriver.class),
                InlineVariantDriver.class.getName(), jarList.toString(), sourceList.toString(), src.toString(),
                earlier.toString(), reference.toString(), dir.resolve("work").toString()).redirectErrorStream(true).start();
        String log = new String(driver.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        System.err.println("  driver exit " + driver.waitFor());
        log.lines().filter(l -> !l.startsWith("VARIANT ")).limit(20).forEach(l -> System.err.println("  driver: " + l));
        return log.lines().filter(l -> l.startsWith("VARIANT ")).collect(Collectors.toList());
    }

    private static Path list(Path file, List<Path> paths) throws IOException {
        Files.write(file, paths.stream().map(Path::toString).collect(Collectors.toList()), StandardCharsets.UTF_8);
        return file;
    }

    /**
     * A table that cannot be replaced (a directory of its name holding a file): the compilation stays green, the set
     * is removed rather than mixed, no write directory is left, and a merging compilation marks the directory
     * incomplete until a full one replaces the set.
     */
    private static void unreplaceable(Path dir) throws Exception {
        Path src = dir.resolve("src");
        Path out = dir.resolve("out");
        String args = "out=" + out.toUri() + " roots=" + src.toUri();
        compile(fixture(src, "first"), dir.resolve("c"), args + " full=true");
        Files.delete(out.resolve("FILE_CLUSTERS.tsv"));
        write(out.resolve("FILE_CLUSTERS.tsv/held"), "a file\n");
        check("unreplaceable: compile green", compile(fixture(src, "second"), dir.resolve("c"), args));
        check("unreplaceable: no table left, none mixed", tables(out) == 0);
        check("unreplaceable: no write directory left", leftovers(out) == 0);
        check("unreplaceable: merging compilation marked incomplete", Files.isRegularFile(out.resolve("INCOMPLETE.txt")));
        replaced(dir, src, out, args);
    }

    /** With the obstacle gone, a full compilation writes the whole set and removes the mark. */
    private static void replaced(Path dir, Path src, Path out, String args) throws IOException {
        if (Files.exists(out.resolve("FILE_CLUSTERS.tsv"))) delete(out.resolve("FILE_CLUSTERS.tsv"));
        check("unreplaceable: full compile green", compile(sources(src), dir.resolve("c"), args + " full=true"));
        check("unreplaceable: full compile writes the set, mark removed", tables(out) == InlineVariantDriver.TABLES.size()
                && !Files.exists(out.resolve("INCOMPLETE.txt")));
    }

    /**
     * Default-package {@code Util.java} in two roots: two warnings on the first compilation, none when it is compiled
     * again or in part, one when a third root brings a third {@code Util.java}.
     */
    private static void collisionWarnings(Path dir) throws Exception {
        Path r1 = dir.resolve("r1/Util.java");
        Path r2 = dir.resolve("r2/Util.java");
        Path r3 = dir.resolve("r3/Util.java");
        write(r1, "class UtilA {\n" + SUM + "}\n");
        write(r2, "class UtilB {\n" + SUM.replace("total", "acc") + "}\n");
        write(r3, "class UtilC {\n" + SUM.replace("total", "all") + "}\n");
        String args = "out=" + dir.resolve("out").toUri() + Stream.of(r1, r2, r3).map(r -> " roots=" + r.getParent().toUri())
                .collect(Collectors.joining());
        List<Long> warned = List.of(warnings(List.of(r1, r2), dir, args + " full=true"),
                warnings(List.of(r1, r2), dir, args + " full=true"), warnings(List.of(r1), dir, args),
                warnings(List.of(r1, r2, r3), dir, args + " full=true"));
        check("collisions: warned when they start or change only " + warned, warned.equals(List.of(2L, 0L, 0L, 1L)));
    }

    /** The collision warnings of one compilation, counted on the plugin's java.util.logging loggers. */
    private static long warnings(List<Path> files, Path dir, String args) throws IOException {
        Collisions handler = new Collisions();
        PLUGIN_LOGS.addHandler(handler);
        try {
            compile(files, dir.resolve("c"), args);
        } finally {
            PLUGIN_LOGS.removeHandler(handler);
        }
        return handler.count.get();
    }

    /** Counts the collision warnings logged while it is attached. */
    private static final class Collisions extends Handler {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public void publish(LogRecord r) {
            if (r.getMessage() != null && r.getMessage().contains("shares the table path")) count.incrementAndGet();
        }

        @Override
        public void flush() { }

        @Override
        public void close() { }
    }

    /** Every source of the root deleted after a full compilation: prune leaves the tables without rows. */
    private static void allDeleted(Path dir) throws Exception {
        Path src = dir.resolve("src");
        Path out = dir.resolve("out");
        String args = "out=" + out.toUri() + " roots=" + src.toUri() + " full=true";
        compile(fixture(src, "first"), dir.resolve("c"), args);
        for (Path f : sources(src)) Files.delete(f);
        Object line = InlineClustering.class.getMethod("prune", String[].class).invoke(null, (Object) args.split(" "));
        System.err.println("  prune: " + line);
        check("all deleted: no source and no atom rows left", rows(out.resolve("ATOM_SOURCES.tsv")) == 0
                && rows(out.resolve("ATOM_HASHES.tsv")) == 0);
    }

    private static long rows(Path table) throws IOException {
        return Files.isRegularFile(table) ? Files.readAllLines(table).size() - 1 : -1;
    }

    private static long tables(Path out) {
        return InlineVariantDriver.TABLES.stream().filter(t -> Files.isRegularFile(out.resolve(t))).count();
    }

    private static long leftovers(Path out) throws IOException {
        try (Stream<Path> listed = Files.list(out)) {
            return listed.filter(p -> p.getFileName().toString().startsWith(".tables-")).count();
        }
    }

    private static void delete(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.delete(p);
        }
    }
}
