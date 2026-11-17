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

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * The javac plugin against the standalone scan: javac with {@code -Xplugin:CodeClustering} on fixtures writes tables
 * byte-identical to {@link CodeClustering#cluster} of the same root (classes without constructors and a record, whose
 * trees javac changes after PARSE, discriminate a collection that runs too late); the native methods with binary
 * names and descriptors resolved by attribution; partial compilations that replace, keep and drop rows; arguments
 * with a space; a compilation that stays green when the tables cannot be written or the arguments are bad; and the
 * plugin's own sources. Run from {@link CodeClusteringTest}.
 */
final class InlinePluginTest {
    private static final String[] TABLES = {"ATOM_HASHES.tsv", "CLUSTERS.tsv", "PLANE_SUMMARY.tsv", "FILE_PAIRS.tsv",
        "FILE_CLUSTERS.tsv"};
    private static final String SUM = "    int sum(int[] values) {\n        int total = 0;\n"
            + "        for (int v : values) {\n            total += v;\n        }\n        return total;\n    }\n";
    private static final String A = "package p;\n\nclass A {\n" + SUM
            + "    int add(int[] xs) {\n        int acc = 0;\n        for (int x : xs) {\n            acc += x;\n"
            + "        }\n        return acc;\n    }\n"
            + "    int start(int[] values) {\n        int total = 1;\n        for (int v : values) {\n"
            + "            total += v;\n        }\n        return total;\n    }\n"
            + "    record Point(int x, int y) { }\n    enum Mode { ON, OFF }\n}\n";
    private static final String B = "package q;\n\npublic class B {\n" + SUM + "}\n";
    private static final String NATIVES = String.join("\n", "package n;", "", "import java.util.List;", "import q.B;",
            "", "public class Natives {", "    static native int add(int a, int b);",
            "    native void put(String key, byte[] value);", "    native void put(String key, long[][] value);",
            "    native <T extends Number> T first(List<T> list, T fallback);",
            "    static class Inner {", "        native void run(B b, Inner self, String... rest);", "    }",
            "    Runnable task = new Runnable() {", "        public native void run();", "    };", "}", "");
    private static int failures;

    private InlinePluginTest() { }

    static int failures() {
        return failures;
    }

    static void run(Path dir) throws Exception {
        Path src = dir.resolve("src");
        write(src.resolve("p/A.java"), A);
        write(src.resolve("q/B.java"), B.replace("\n", "\r\n"));
        write(src.resolve("n/Natives.java"), NATIVES);
        Path classes = dir.resolve("classes");
        Path out = dir.resolve("inline");
        check("full compile green", compile(sources(src), classes, "out=" + out));
        check("full compile: tables identical to the standalone scan", sameAsScan(dir, src, out, "cli"));
        natives(out);
        incremental(dir, src, classes, out);
        robustness(dir, src);
        ownSources(dir);
    }

    /** JNI.tsv: binary names (nested, anonymous), erased descriptors, varargs, overloads; symbols are atom symbols. */
    private static void natives(Path out) throws IOException {
        List<String> rows = Files.readAllLines(out.resolve("JNI.tsv"));
        check("JNI header", rows.get(0).equals("path\tsymbol\tclass_name\tmethod\targuments"));
        check("JNI static int", rows.contains("n/Natives.java\tNatives.add(int,int)\tn/Natives\tadd\tII"));
        check("JNI overloads", rows.contains("n/Natives.java\tNatives.put(String,byte[])\tn/Natives\tput\tLjava/lang/String;[B")
                && rows.contains("n/Natives.java\tNatives.put(String,long[][])\tn/Natives\tput\tLjava/lang/String;[[J"));
        check("JNI type variable erased to its bound", rows.contains(
                "n/Natives.java\tNatives.first(List<T>,T)\tn/Natives\tfirst\tLjava/util/List;Ljava/lang/Number;"));
        check("JNI nested class, imported type, varargs", rows.contains(
                "n/Natives.java\tNatives.Inner.run(B,Inner,String[])\tn/Natives$Inner\trun\tLq/B;Ln/Natives$Inner;[Ljava/lang/String;"));
        check("JNI anonymous class", rows.contains("n/Natives.java\tNatives..run()\tn/Natives$1\trun\t"));
        check("JNI six rows", rows.size() == 7);
        List<String> symbols = PlaneTables.readAtoms(out.resolve("ATOM_HASHES.tsv")).stream()
                .map(a -> a.path() + "\t" + a.symbol()).collect(Collectors.toList());
        check("JNI symbols are atom symbols", rows.subList(1, rows.size()).stream()
                .allMatch(r -> symbols.contains(r.substring(0, r.indexOf('\t', r.indexOf('\t') + 1)))));
    }

    /**
     * A changed file compiled alone replaces its rows and keeps the others; a deleted file's rows go; a native file
     * compiled alone replaces its JNI rows. After each step the tables equal a fresh scan of the whole root.
     */
    private static void incremental(Path dir, Path src, Path classes, Path out) throws Exception {
        write(src.resolve("p/A.java"), A.replace("int start(", "int begin("));
        check("partial compile green", compile(List.of(src.resolve("p/A.java")), classes, "out=" + out));
        check("partial compile: changed file replaced, others kept", sameAsScan(dir, src, out, "cli-changed"));
        Files.delete(src.resolve("q/B.java"));
        Files.writeString(src.resolve("n/Natives.java"), NATIVES.replace("import q.B;", "")
                .replace("        native void run(B b, Inner self, String... rest);\n", ""), StandardCharsets.UTF_8);
        check("partial compile after delete green", compile(List.of(src.resolve("n/Natives.java")), classes, "out=" + out));
        check("deleted file's rows dropped", sameAsScan(dir, src, out, "cli-deleted"));
        List<String> rows = Files.readAllLines(out.resolve("JNI.tsv"));
        check("JNI rows of the recompiled file replaced", rows.size() == 6
                && rows.stream().noneMatch(r -> r.contains("Inner.run")));
        check("ATOM_SOURCES lists the two remaining files", Files.readAllLines(out.resolve("ATOM_SOURCES.tsv")).size() == 3);
    }

    /** A space in out=, an output that is a file, bad and missing arguments: the compilation stays green. */
    private static void robustness(Path dir, Path src) throws Exception {
        Path spaced = dir.resolve("with space/out dir");
        check("space in out=", compile(sources(src), dir.resolve("c1"), "out=" + spaced + " radius=3")
                && Files.isRegularFile(spaced.resolve("ATOM_HASHES.tsv")));
        Path blocked = dir.resolve("blocked");
        Files.writeString(blocked, "not a directory", StandardCharsets.UTF_8);
        check("unwritable output: compile green", compile(sources(src), dir.resolve("c2"), "out=" + blocked));
        check("bad number: compile green", compile(sources(src), dir.resolve("c3"), "out=" + dir.resolve("x") + " minTokens=many"));
        check("bad number: no tables", !Files.exists(dir.resolve("x")));
        check("no out=: compile green", compile(sources(src), dir.resolve("c4"), "radius=2"));
    }

    /** The plugin's own main sources, compiled with the plugin, against the standalone scan of src/main/java. */
    private static void ownSources(Path dir) throws Exception {
        Path main = Path.of("src/main/java");
        if (!Files.isDirectory(main)) {
            System.err.println("SKIP own sources: no src/main/java in " + Path.of("").toAbsolutePath());
            return;
        }
        String classpath = Stream.of(org.apache.maven.plugin.AbstractMojo.class,
                org.apache.maven.plugins.annotations.Mojo.class).map(InlinePluginTest::location).collect(Collectors.joining(File.pathSeparator));
        Path out = dir.resolve("own");
        check("own sources compile green", compile(sources(main), dir.resolve("own-classes"), "out=" + out, classpath));
        check("own sources: tables identical to the standalone scan", sameAsScan(dir, main, out, "own-cli"));
    }

    private static boolean sameAsScan(Path dir, Path src, Path inline, String name) throws IOException {
        Path cli = dir.resolve(name);
        String line = CodeClustering.cluster(cli, List.of(src), 12, 3, 0.5);
        boolean same = Stream.of(TABLES).allMatch(t -> mismatch(inline.resolve(t), cli.resolve(t)) == -1L);
        System.err.println("  " + name + ": " + line);
        return same;
    }

    private static long mismatch(Path a, Path b) {
        try {
            return Files.mismatch(a, b);
        } catch (IOException e) {
            return 0;
        }
    }

    static boolean compile(List<Path> files, Path classes, String pluginArgs) throws IOException {
        return compile(files, classes, pluginArgs, "");
    }

    static boolean compile(List<Path> files, Path classes, String pluginArgs, String classpath) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        Files.createDirectories(classes);
        List<String> options = new ArrayList<>(List.of("-encoding", "UTF-8", "-d", classes.toString(), "-processorpath",
                processorPath(classes.getParent()), "-cp", classes + File.pathSeparator + classpath));
        options.add("-Xplugin:" + InlineClustering.NAME + " " + pluginArgs);
        StringWriter log = new StringWriter();
        try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            boolean ok = javac.getTask(log, fm, null, options, null, fm.getJavaFileObjectsFromPaths(files)).call();
            if (!ok) System.err.println(log);
            return ok;
        }
    }

    /**
     * The plugin's classes plus its service registration, as the jar has them: the module keeps the registration
     * out of target/classes ({@code src/main/javac-plugin}, see the POM).
     */
    static String processorPath(Path dir) throws IOException {
        String service = "META-INF/services/com.sun.source.util.Plugin";
        Path shipped = Path.of("src/main/javac-plugin").resolve(service);
        Path registration = dir.resolve("plugin-registration").resolve(service);
        Files.createDirectories(registration.getParent());
        Files.writeString(registration, Files.isRegularFile(shipped) ? Files.readString(shipped)
                : InlineClustering.class.getName() + "\n", StandardCharsets.UTF_8);
        return dir.resolve("plugin-registration") + File.pathSeparator + location(InlineClustering.class);
    }

    static String location(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<Path> sources(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.err.println((ok ? "PASS " : "FAIL ") + "inline " + name);
    }
}
