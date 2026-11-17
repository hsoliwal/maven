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
package org.apache.maven.recipes.javac;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.apache.maven.recipes.javac.IsolationSources.A;
import static org.apache.maven.recipes.javac.IsolationSources.B;

/**
 * Reproducers of the round-2 verifiers of L01: compiles that the plugin changed because the conversion ran javac's
 * lazy machinery (library completion, Javadoc attribution), wrote into javac's trees, needed a compile policy, kept
 * every tree, or was wired so that classpath processors were lost. The sources are in {@link IsolationSources}.
 */
final class IsolationFixtures {
    static final String ORIGIN = "round-2 verifiers of L01";
    private static final Path SIDE = Javac.WORK.resolve("isolation-side").toAbsolutePath();

    private IsolationFixtures() {}

    /**
     * A library whose class {@code lib.Base} carries {@code @ann.Ann(ann.E.X)} on a method, with the {@code ann}
     * classes missing, as in the adversarial verifier's {@code completion2}: javac never reads {@code Base} when the
     * sources only declare a {@code lib.Lib} field, so the conversion must not either.
     */
    static synchronized Path library() {
        Path classes = SIDE.resolve("library/classes");
        if (!Files.isDirectory(classes.resolve("lib"))) {
            compile(classes, IsolationSources.LIBRARY);
            delete(classes.resolve("ann/Ann.class"));
            delete(classes.resolve("ann/E.class"));
        }
        return classes;
    }

    /** {@code A} only declares a library field; with {@code B}, javac itself reads {@code Base} (two warnings). */
    static Fixture completion(boolean withB) {
        String[] files = withB ? new String[] {"a/A.java", A, "b/B.java", B} : new String[] {"a/A.java", A};
        return Fixture.of(withB ? "completion-ab" : "completion-a", ORIGIN, files)
                .withOptions("-classpath", library().toString());
    }

    /** The equivalence verifier's {@code corpus/javadoc}: stale, nested-member, parameter and deprecated links. */
    static Fixture javadoc() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        Map.of(
                        "j/Missing.java", IsolationSources.MISSING,
                        "j/Outer.java", IsolationSources.OUTER,
                        "j/Depr.java", IsolationSources.DEPR,
                        "j/Pkg.java", IsolationSources.PKG,
                        "j/Fine.java", IsolationSources.FINE)
                .entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(file -> files.put(file.getKey(), file.getValue().getBytes(StandardCharsets.UTF_8)));
        return Fixture.of("javadoc", ORIGIN, files);
    }

    /** A link to a class that only {@code -sourcepath} holds: javac never parses it unless the conversion asks. */
    static Fixture javadocSourcepath() {
        Path sourcepath = SIDE.resolve("sourcepath");
        write(sourcepath.resolve("other/Only.java"), IsolationSources.ONLY);
        return Fixture.of("javadoc-sourcepath", ORIGIN, "j/Impl.java", IsolationSources.IMPL)
                .withOptions("-sourcepath", sourcepath.toString());
    }

    /** The equivalence verifier's {@code RecordAnnoProbe} source: mixed-target and repeatable component annotations. */
    static Fixture records() {
        return Fixture.of("records", ORIGIN, "r/Shapes.java", IsolationSources.SHAPES);
    }

    /**
     * The adversarial verifier's policy fixtures, under javac's default policy: a flow error before a type error, a
     * good class before a failing one, and lint warnings whose order depends on the policy.
     */
    static List<Fixture> policy() {
        Fixture flowAttr = Fixture.of(
                "policy-flow-attr", ORIGIN, "p/A.java", IsolationSources.FLOW_A, "p/B.java", IsolationSources.FLOW_B);
        Fixture classfile = Fixture.of(
                "policy-classfile", ORIGIN, "p/A.java", IsolationSources.GOOD_A, "p/Z.java", IsolationSources.BAD_Z);
        Fixture warnOrder = Fixture.of(
                        "policy-warn-order",
                        ORIGIN,
                        "p/A.java",
                        IsolationSources.WARN_A,
                        "p/B.java",
                        IsolationSources.WARN_B)
                .withOptions("-Xlint:all");
        return List.of(flowAttr, classfile, warnOrder);
    }

    /** The adversarial verifier's {@code many}: generated classes of 60 methods each. */
    static Fixture many(int files) {
        Map<String, byte[]> sources = new LinkedHashMap<>();
        IntStream.range(0, files)
                .forEach(i -> sources.put(
                        String.format("p/C%04d.java", i), manyClass(i).getBytes(StandardCharsets.UTF_8)));
        return Fixture.of("many-" + files, ORIGIN, sources);
    }

    private static String manyClass(int i) {
        StringBuilder body = new StringBuilder();
        IntStream.range(0, 60)
                .forEach(j -> body.append(String.format(
                        "  public int m%d(int a, String s) { java.util.List<String> l = new java.util.ArrayList<>(); "
                                + "l.add(s + a); if (a > %d) { return l.size() + Integer.parseInt(\"%d\"); } "
                                + "return s.length() * %d; }%n",
                        j, j, j, j)));
        return String.format("package p;%npublic class C%04d {%n%s}%n", i, body);
    }

    /**
     * A processor found on the classpath (no {@code -processorpath}), as Lombok as a provided dependency or Maven's
     * DiIndexProcessor are found: it generates a source and a resource that the compile needs.
     */
    static synchronized Path processor() {
        Path classes = SIDE.resolve("processor/classes");
        if (!Files.isRegularFile(classes.resolve("wp/GenProc.class"))) {
            compile(classes, Map.of("wp/Gen.java", IsolationSources.GEN, "wp/GenProc.java", IsolationSources.GEN_PROC));
            write(classes.resolve("META-INF/services/javax.annotation.processing.Processor"), "wp.GenProc\n");
        }
        return classes;
    }

    /** A source that only compiles when the classpath processor runs. */
    static Fixture processorUse(String name, String... options) {
        List<String> all = new ArrayList<>(List.of("-classpath", processor().toString()));
        all.addAll(List.of(options));
        return Fixture.of(name, ORIGIN, "u/Use.java", IsolationSources.USE).withOptions(all.toArray(String[]::new));
    }

    /** Compiles helper sources (no plugin, no processors) for Java 8, so every javac the tests fork can read them. */
    private static void compile(Path classes, Map<String, String> sources) {
        Path src = classes.resolveSibling("src");
        sources.forEach((path, text) -> write(src.resolve(path), text));
        List<String> args = new ArrayList<>(List.of("--release", "8", "-proc:none", "-d", classes.toString()));
        sources.keySet().forEach(path -> args.add(src.resolve(path).toString()));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        if (javac.run(null, null, err, args.toArray(String[]::new)) != 0) {
            throw new IllegalStateException("helper compile failed: " + err.toString(StandardCharsets.UTF_8));
        }
    }

    static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
