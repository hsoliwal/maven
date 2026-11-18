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

import java.util.Map;

/** The source texts of {@link IsolationFixtures}, from the round-2 verifiers' reproducers. */
final class IsolationSources {
    /** A library with {@code @ann.Ann(ann.E.X)} on {@code lib.Base.m()}; the {@code ann} classes are removed. */
    static final Map<String, String> LIBRARY = Map.of(
            "ann/Ann.java",
            "package ann;\nimport java.lang.annotation.*;\n"
                    + "@Retention(RetentionPolicy.CLASS) public @interface Ann { E value(); }\n",
            "ann/E.java",
            "package ann;\npublic enum E { X }\n",
            "lib/Base.java",
            "package lib;\npublic class Base { @ann.Ann(ann.E.X) public void m() {} }\n",
            "lib/Lib.java",
            "package lib;\npublic class Lib extends Base {}\n");

    static final String A = "package a;\npublic class A { lib.Lib field; }\n";

    static final String B = "package b;\npublic class B { int f(lib.Lib l) { l.m(); return l.hashCode(); } }\n";

    static final String MISSING =
            "package j;\n\n/** A stale link: {@link NoSuchType} and {@link NoSuchType#member()}. */\n"
                    + "public class Missing {\n    /** Stale parameter type: {@link #run(NoSuchParam)}. */\n"
                    + "    public void run() {}\n}\n";

    static final String OUTER =
            "package j;\n\n/** The pattern of maven-api-core Lifecycle.Link. */\npublic interface Outer {\n"
                    + "    /** Points {@link Kind#BEFORE} or {@link Kind#AFTER}. */\n    interface Link {\n"
                    + "        enum Kind { BEFORE, AFTER }\n        Kind kind();\n    }\n}\n";

    static final String DEPR = "package j;\n\n"
            + "/** Mentions {@link SecurityManager} and {@link java.util.Observable} in Javadoc only. */\n"
            + "public class Depr {\n    /** @see java.util.Observable#notifyObservers() */\n    public void m() {}\n}\n";

    static final String PKG =
            "package j;\n\n/** A package link: {@link java.util.concurrent}. */\npublic class Pkg {}\n";

    static final String FINE = "package j;\n\n"
            + "/** Valid links: {@link String}, {@link java.util.List#size()}, {@link #go(int)}. */\n"
            + "public class Fine {\n    /** @see Outer.Link.Kind */\n    void go(int x) {}\n}\n";

    static final String ONLY = "package other;\npublic class Only {}\n";

    static final String IMPL =
            "package j;\n/** Implementation notes live in {@link other.Only}. */\npublic class Impl {}\n";

    static final String SHAPES = "package r;\nimport java.lang.annotation.*;\npublic class Shapes {\n"
            + "  @Target(ElementType.FIELD) @Retention(RetentionPolicy.RUNTIME) @interface OnField {}\n"
            + "  @Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) @interface OnAccessor {}\n"
            + "  @Target(ElementType.RECORD_COMPONENT) @Retention(RetentionPolicy.RUNTIME) @interface OnComponent {}\n"
            + "  @Repeatable(Tags.class) @Retention(RetentionPolicy.RUNTIME) @interface Tag { String value(); }\n"
            + "  @Retention(RetentionPolicy.RUNTIME) @interface Tags { Tag[] value(); }\n"
            + "  public record Circle(@OnComponent @OnField double r) {}\n"
            + "  public record Square(@Tag(\"a\") @Tag(\"b\") @OnAccessor double side) {}\n}\n";

    static final String FLOW_A = "package p;\npublic class A {\n  int f(boolean b) { if (b) return 1; }\n}\n";

    static final String FLOW_B = "package p;\npublic class B {\n  void g() { undefined(); }\n}\n";

    static final String GOOD_A = "package p;\npublic class A { int f() { return 1; } }\n";

    static final String BAD_Z = "package p;\npublic class Z { void g() { undefined(); } }\n";

    static final String WARN_A = "package p;\npublic class A {\n  public A() { init(); }\n  public void init() {}\n"
            + "  int f(int x) { switch (x) { case 1: x++; case 2: return x; default: return 0; } }\n}\n";

    static final String WARN_B = "package p;\npublic class B {\n  String g(String s) { return (String) s; }\n}\n";

    static final String GEN = "package wp;\npublic @interface Gen {}\n";

    static final String USE = "package u;\n@wp.Gen\npublic class Use { String id() { return UseGen.id(); } }\n";

    static final String GEN_PROC = """
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

    /**
     * Javadoc references to classes javac completes (the design probe's {@code Refs.java} without the references into
     * classes javac never completes): types, a method with a primitive array parameter, a member type and its field.
     */
    static final String DOC_REFERENCES = """
            package j;
            import java.util.List;
            /**
             * Uses {@link List}, {@link String#valueOf(int)}, {@link #take(List, int[])}, {@link Inner} and
             * {@link Refs.Inner#x}.
             */
            public class Refs {
              /** See {@link Refs#take(List, int[])} and {@link #take(List, int[])}. */
              public int take(List<String> l, int[] a) { return l.size() + a.length + String.valueOf(1).length(); }
              static class Inner { int x; }
            }
            """;

    private IsolationSources() {}
}
