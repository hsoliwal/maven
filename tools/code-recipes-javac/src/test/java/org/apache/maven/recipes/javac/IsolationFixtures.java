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

/**
 * Reproducers of the round-2 verifiers of L01: compiles that the plugin changed because the conversion ran javac's
 * lazy machinery (library completion, Javadoc attribution), wrote into javac's trees, needed a compile policy, kept
 * every tree, or was wired so that classpath processors were lost.
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
        if (Files.isDirectory(classes.resolve("lib"))) {
            return classes;
        }
        compile(
                classes,
                Map.of(
                        "ann/Ann.java",
                                "package ann;\nimport java.lang.annotation.*;\n"
                                        + "@Retention(RetentionPolicy.CLASS) public @interface Ann { E value(); }\n",
                        "ann/E.java", "package ann;\npublic enum E { X }\n",
                        "lib/Base.java", "package lib;\npublic class Base { @ann.Ann(ann.E.X) public void m() {} }\n",
                        "lib/Lib.java", "package lib;\npublic class Lib extends Base {}\n"));
        delete(classes.resolve("ann/Ann.class"));
        delete(classes.resolve("ann/E.class"));
        return classes;
    }

    /** {@code A} only declares a library field; with {@code B}, javac itself reads {@code Base} (two warnings). */
    static Fixture completion(boolean withB) {
        List<String> files = new ArrayList<>(List.of("a/A.java", "package a;\npublic class A { lib.Lib field; }\n"));
        if (withB) {
            files.addAll(List.of(
                    "b/B.java", "package b;\npublic class B { int f(lib.Lib l) { l.m(); return l.hashCode(); } }\n"));
        }
        return Fixture.of(withB ? "completion-ab" : "completion-a", ORIGIN, files.toArray(String[]::new))
                .withOptions("-classpath", library().toString());
    }

    /** The equivalence verifier's {@code corpus/javadoc}: stale, nested-member, parameter and deprecated links. */
    static Fixture javadoc() {
        return Fixture.of(
                "javadoc",
                ORIGIN,
                "j/Missing.java",
                "package j;\n\n/** A stale link: {@link NoSuchType} and {@link NoSuchType#member()}. */\n"
                        + "public class Missing {\n    /** Stale parameter type: {@link #run(NoSuchParam)}. */\n"
                        + "    public void run() {}\n}\n",
                "j/Outer.java",
                "package j;\n\n/** The pattern of maven-api-core Lifecycle.Link. */\npublic interface Outer {\n"
                        + "    /** Points {@link Kind#BEFORE} or {@link Kind#AFTER}. */\n    interface Link {\n"
                        + "        enum Kind { BEFORE, AFTER }\n        Kind kind();\n    }\n}\n",
                "j/Depr.java",
                "package j;\n\n/** Mentions {@link SecurityManager} and {@link java.util.Observable} in Javadoc only. */\n"
                        + "public class Depr {\n    /** @see java.util.Observable#notifyObservers() */\n"
                        + "    public void m() {}\n}\n",
                "j/Pkg.java",
                "package j;\n\n/** A package link: {@link java.util.concurrent}. */\npublic class Pkg {}\n",
                "j/Fine.java",
                "package j;\n\n/** Valid links: {@link String}, {@link java.util.List#size()}, {@link #go(int)}. */\n"
                        + "public class Fine {\n    /** @see Outer.Link.Kind */\n    void go(int x) {}\n}\n");
    }

    /** A link to a class that only {@code -sourcepath} holds: javac never parses it unless the conversion asks. */
    static Fixture javadocSourcepath() {
        Path sourcepath = SIDE.resolve("sourcepath");
        write(sourcepath.resolve("other/Only.java"), "package other;\npublic class Only {}\n");
        return Fixture.of(
                        "javadoc-sourcepath",
                        ORIGIN,
                        "j/Impl.java",
                        "package j;\n/** Implementation notes live in {@link other.Only}. */\npublic class Impl {}\n")
                .withOptions("-sourcepath", sourcepath.toString());
    }

    /** The equivalence verifier's {@code RecordAnnoProbe} source: mixed-target and repeatable component annotations. */
    static Fixture records() {
        return Fixture.of(
                "records",
                ORIGIN,
                "r/Shapes.java",
                "package r;\nimport java.lang.annotation.*;\npublic class Shapes {\n"
                        + "  @Target(ElementType.FIELD) @Retention(RetentionPolicy.RUNTIME) @interface OnField {}\n"
                        + "  @Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) @interface OnAccessor {}\n"
                        + "  @Target(ElementType.RECORD_COMPONENT) @Retention(RetentionPolicy.RUNTIME)"
                        + " @interface OnComponent {}\n"
                        + "  @Repeatable(Tags.class) @Retention(RetentionPolicy.RUNTIME) @interface Tag { String value(); }\n"
                        + "  @Retention(RetentionPolicy.RUNTIME) @interface Tags { Tag[] value(); }\n"
                        + "  public record Circle(@OnComponent @OnField double r) {}\n"
                        + "  public record Square(@Tag(\"a\") @Tag(\"b\") @OnAccessor double side) {}\n}\n");
    }

    /**
     * The adversarial verifier's policy fixtures, under javac's default policy: a flow error before a type error, a
     * good class before a failing one, and lint warnings whose order depends on the policy.
     */
    static List<Fixture> policy() {
        Fixture flowAttr = Fixture.of(
                "policy-flow-attr",
                ORIGIN,
                "p/A.java",
                "package p;\npublic class A {\n  int f(boolean b) { if (b) return 1; }\n}\n",
                "p/B.java",
                "package p;\npublic class B {\n  void g() { undefined(); }\n}\n");
        Fixture classfile = Fixture.of(
                "policy-classfile",
                ORIGIN,
                "p/A.java",
                "package p;\npublic class A { int f() { return 1; } }\n",
                "p/Z.java",
                "package p;\npublic class Z { void g() { undefined(); } }\n");
        return List.of(flowAttr, classfile, warnOrder());
    }

    private static Fixture warnOrder() {
        return Fixture.of(
                        "policy-warn-order",
                        ORIGIN,
                        "p/A.java",
                        "package p;\npublic class A {\n  public A() { init(); }\n  public void init() {}\n"
                                + "  int f(int x) { switch (x) { case 1: x++; case 2: return x; default: return 0; } }\n"
                                + "}\n",
                        "p/B.java",
                        "package p;\npublic class B {\n  String g(String s) { return (String) s; }\n}\n")
                .withOptions("-Xlint:all");
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
        if (Files.isRegularFile(classes.resolve("wp/GenProc.class"))) {
            return classes;
        }
        compile(classes, Map.of("wp/Gen.java", "package wp;\npublic @interface Gen {}\n", "wp/GenProc.java", GEN_PROC));
        write(classes.resolve("META-INF/services/javax.annotation.processing.Processor"), "wp.GenProc\n");
        return classes;
    }

    /** A source that only compiles when the classpath processor runs. */
    static Fixture processorUse(String name, String... options) {
        List<String> all = new ArrayList<>(List.of("-classpath", processor().toString()));
        all.addAll(List.of(options));
        return Fixture.of(
                        name,
                        ORIGIN,
                        "u/Use.java",
                        "package u;\n@wp.Gen\npublic class Use { String id() { return UseGen.id(); } }\n")
                .withOptions(all.toArray(String[]::new));
    }

    private static final String GEN_PROC = """
            package wp;
            import java.io.*;
            import java.util.Set;
            import javax.annotation.processing.*;
            import javax.lang.model.SourceVersion;
            import javax.lang.model.element.*;
            import javax.tools.*;
            @SupportedAnnotationTypes("wp.Gen")
            public class GenProc extends AbstractProcessor {
              @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
              @Override public boolean process(Set<? extends TypeElement> annos, RoundEnvironment env) {
                for (Element e : env.getElementsAnnotatedWith(Gen.class)) {
                  String n = e.getSimpleName() + "Gen";
                  try (Writer w = processingEnv.getFiler().createSourceFile("u." + n, e).openWriter()) {
                    w.write("package u; public class " + n + " { public static String id() { return \\"" + n
                        + "\\"; } }\\n");
                    try (Writer r = processingEnv.getFiler()
                        .createResource(StandardLocation.CLASS_OUTPUT, "", "META-INF/" + n + ".idx").openWriter()) {
                      r.write(n + "\\n");
                    }
                  } catch (IOException x) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, x.toString());
                  }
                }
                return false;
              }
            }
            """;

    /** Compiles helper sources (no plugin, no processors) into a class directory. */
    private static void compile(Path classes, Map<String, String> sources) {
        Path src = classes.resolveSibling("src");
        sources.forEach((path, text) -> write(src.resolve(path), text));
        List<String> args = new ArrayList<>(List.of("-proc:none", "-d", classes.toString()));
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
