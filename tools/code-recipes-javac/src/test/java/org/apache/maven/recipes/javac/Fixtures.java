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

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sources the tests compile. Cases marked "rewrite-java-tck" are copied from OpenRewrite's parser test kit
 * (github.com/hsoliwal/rewrite at 1e6bdc4025ac5d2bcf6d3337736f0cce5e958004, rewrite-java-tck/src/main/java/
 * org/openrewrite/java/tree, Apache-2.0); the class or method named in the origin is the test they come from. Where a
 * case was edited (renamed class, kept escapes), the comment in the fixture says so.
 */
final class Fixtures {
    private static final String OWN = "own";
    private static final String TCK = "rewrite-java-tck 1e6bdc4: ";

    static final String PLAIN = """
            package demo;

            import java.util.ArrayList;
            import java.util.List;

            /**
             * A plain class: fields, a constructor, methods and comments.
             */
            public class Plain {
                // a line comment
                private final List<String> names = new ArrayList<>();
                private int count; /* trailing block comment */

                public Plain(int count) {
                    this.count = count;
                }

                /** Adds a name and returns the new size. */
                public int add(String name) {
                    names.add(name.trim());
                    count += 1;
                    return names.size();
                }

                static int twice(int x) {
                    return Math.multiplyExact(x, 2);
                }
            }
            """;

    private Fixtures() {}

    /** Fixtures that compile; every file must convert with print equality. */
    static List<Fixture> compiling() {
        return List.of(
                Fixture.of("plain", OWN, "demo/Plain.java", PLAIN),
                Fixture.of("generics-lambdas", OWN, "demo/Generics.java", GENERICS),
                Fixture.of(
                        "java21",
                        OWN + "; " + TCK + "SwitchPatternMatchingTest (shouldParseJava21PatternSwitch,"
                                + " shouldSupportParsingNullSwitch)",
                        "demo/Shapes.java",
                        JAVA21),
                Fixture.of(
                        "tck-record-patterns",
                        TCK + "RecordPatternMatchingTest.shouldParseJava21NestedPatternMatchForRecords",
                        "Test.java",
                        RECORD_PATTERNS),
                Fixture.of("annotations-javadoc", OWN, "demo/Annotated.java", ANNOTATED),
                Fixture.of("two-top-level", OWN, "demo/TwoTypes.java", TWO_TYPES),
                Fixture.of(
                        "package-info",
                        OWN,
                        "p/package-info.java",
                        "/**\n * Package documentation.\n */\n@Deprecated\npackage p;\n",
                        "p/A.java",
                        "package p;\n\npublic class A {}\n",
                        "q/package-info.java",
                        "/** A doc comment only; javac still gives the package an env. */\npackage q;\n",
                        "q/B.java",
                        "package q;\n\npublic class B extends p.A {}\n",
                        "q/Empty.java",
                        "package q;\n// no type at all: javac sends no ANALYZE event for this file\n"),
                crlf(),
                Fixture.of(
                        "unicode-escapes", TCK + "LiteralTest (escapes kept as written)", "demo/Escapes.java", ESCAPES),
                Fixture.of("non-ascii", OWN, "demo/Unicode.java", NON_ASCII),
                tckLiteralsAndComments(),
                Fixture.of("string-folding", OWN, "demo/Folding.java", FOLDING));
    }

    /** A module: module-info.java is compiled but has no OpenRewrite tree. */
    static Fixture module() {
        return Fixture.of(
                "module-info",
                OWN,
                "module-info.java",
                "/** A module. */\nmodule demo.mod {\n    requires java.logging;\n    exports p;\n}\n",
                "p/Log.java",
                LOGGING);
    }

    /** A file in a generated-sources root and one outside it. */
    static Fixture generated() {
        return Fixture.of(
                "generated",
                OWN,
                "demo/User.java",
                "package demo;\n\nclass User {\n    Gen gen = new Gen();\n}\n",
                "target/generated-sources/annotations/demo/Gen.java",
                "package demo;\n\nclass Gen {}\n");
    }

    /** A superclass in a later file that javac desugars early when another file needs it (byfile policy). */
    static Fixture crossFileSuperclass() {
        return Fixture.of(
                "cross-file-superclass",
                OWN,
                "demo/A.java",
                "package demo;\n\nclass A extends B1 {\n    int a() {\n        return b() + 1;\n    }\n}\n",
                "demo/B.java",
                "package demo;\n\nclass B1 {\n    Runnable r = () -> {};\n\n    int b() {\n        return 1;\n"
                        + "    }\n}\n\nclass B2 extends B1 {}\n");
    }

    /** Fixtures javac rejects or fails with -Werror; the plugin must leave the result exactly as it was. */
    static List<Fixture> failing() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] text = "package demo;\n\nclass Bom {}\n".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[bom.length + text.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(text, 0, withBom, bom.length, text.length);
        return List.of(
                new Fixture("utf8-bom", OWN, Map.of("demo/Bom.java", withBom), List.of()),
                Fixture.of(
                        "type-error",
                        OWN,
                        "demo/Broken.java",
                        "package demo;\n\nclass Broken {\n    String s = 1;\n}\n"),
                Fixture.of(
                                "werror-warning",
                                OWN,
                                "demo/Raw.java",
                                "package demo;\n\nimport java.util.*;\n\nclass Raw {\n"
                                        + "    List names = new ArrayList();\n}\n")
                        .withOptions("-Xlint:all", "-Werror"));
    }

    private static Fixture crlf() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("demo/Plain.java", PLAIN.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
        return new Fixture("crlf", OWN, files, List.of());
    }

    /**
     * Two CommentTest cases that rewrite 8.89.0 itself cannot print back: a comment between an annotation and the
     * modifiers drops a modifier from the tree (fixed upstream after 8.89.0 by openrewrite/rewrite#8564, commit
     * 3c87c3ea6e in the fork's history). The classic parser returns a ParseError for them; the bridge must refuse
     * them as print mismatches and hand no tree to recipes.
     */
    static Fixture rewrite8890CommentDefect() {
        return Fixture.of(
                "tck-comment-modifiers",
                TCK + "CommentTest (slashImmediatelyAfterMultilineOpenerBeforeModifier,"
                        + " singleLineOpenerInsideMultilineCommentBeforeModifiers; class Test renamed per file)",
                "SlashAfterOpener.java",
                "class SlashAfterOpener {\n    @Deprecated /*/ not the end of the comment */ public String value() {\n"
                        + "        return null;\n    }\n}\n",
                "SingleLineOpener.java",
                "class SingleLineOpener {\n    @Deprecated /* // */ public static String value() {\n"
                        + "        return null;\n    }\n}\n");
    }

    private static Fixture tckLiteralsAndComments() {
        return Fixture.of(
                "tck-literals-comments",
                TCK + "TextBlockTest, BinaryTest, CommentTest (class Test renamed per file)",
                "TextBlock.java",
                TEXT_BLOCK,
                "Arithmetic.java",
                "class Arithmetic {\n    void test() {\n    int n = 0 + 1;\n    }\n}\n",
                "FoldableStrings.java",
                "class FoldableStrings {\n    void test() {\n        String n = \"a\" + \"b\";\n    }\n}\n",
                "EndOfLineBreaks.java",
                END_OF_LINE_BREAKS,
                "BackToBack.java",
                "class BackToBack {\n    /*\n        Comment 1\n    *//*\n        Comment 2\n    */\n}\n",
                "NestedInsideSingleLine.java",
                "class NestedInsideSingleLine {// /*\n}\n",
                "CommentAsLastLine.java",
                "class CommentAsLastLine {\n}\n//\n",
                "hello/CommentWithUrl.java",
                "package hello;\npublic class CommentWithUrl {\n  public static void test() {\n"
                        + "    /*addItem(\"Site A\", \"https://hello.com/A\");*/\n  }\n}\n");
    }

    private static final String GENERICS = """
            package demo;

            import java.util.Comparator;
            import java.util.List;
            import java.util.Map;
            import java.util.function.Function;
            import java.util.stream.Collectors;

            public class Generics<T extends Comparable<? super T>> {
                private final List<T> items;

                public Generics(List<T> items) {
                    this.items = List.copyOf(items);
                }

                public <K> Map<K, List<T>> group(Function<? super T, ? extends K> key) {
                    return items.stream().collect(Collectors.groupingBy(key));
                }

                public T max() {
                    return items.stream().max(Comparator.naturalOrder()).orElseThrow();
                }

                public List<String> render() {
                    var out = items.stream().map(String::valueOf).map(s -> s.toUpperCase()).toList();
                    Runnable r = new Runnable() {
                        @Override
                        public void run() {
                            System.out.println(out.size());
                        }
                    };
                    r.run();
                    return out;
                }
            }
            """;

    private static final String JAVA21 = """
            package demo;

            public class Shapes {
                sealed interface Shape permits Circle, Square, Rect {}

                record Circle(double r) implements Shape {}

                record Square(double side) implements Shape {}

                record Rect(double w, double h) implements Shape {
                    Rect {
                        if (w < 0 || h < 0) throw new IllegalArgumentException("negative");
                    }
                }

                static double area(Shape shape) {
                    return switch (shape) {
                        case Circle c -> Math.PI * c.r() * c.r();
                        case Square(double side) -> side * side;
                        case Rect(var w, var h) when w == h -> w * w;
                        case Rect r -> r.w() * r.h();
                    };
                }

                String formatterPatternSwitch(Object obj) {
                    return switch (obj) {
                        case Integer i -> String.format("int %d", i);
                        case Long l    -> String.format("long %d", l);
                        case Double d  -> String.format("double %f", d);
                        case String s  -> String.format("String %s", s);
                        default        -> obj.toString();
                    };
                }

                void fooBarWithNull(String s) {
                    switch (s) {
                        case null -> System.out.println("Oops");
                        case "Foo", "Bar" -> System.out.println("Great");
                        default -> System.out.println("Ok");
                    }
                }
            }
            """;

    private static final String RECORD_PATTERNS = """
            record Point(int x, int y) {}
            enum Color { RED, GREEN, BLUE }
            record ColoredPoint(Point p, Color c) {}
            record Rectangle(ColoredPoint upperLeft, ColoredPoint lowerRight) {}
            class Test {
                void printColorOfUpperLeftPoint(Rectangle r) {
                    if (r instanceof Rectangle(ColoredPoint(Point p, Color c),
                                               ColoredPoint lr)) {
                        System.out.println(c);
                    }
                }
            }
            """;

    private static final String ANNOTATED = """
            package demo;

            import java.lang.annotation.Documented;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            /**
             * Annotated elements with Javadoc.
             *
             * @see Annotated#value(int)
             */
            @Annotated.Marker(name = "type", tags = {"a", "b"})
            public class Annotated {
                /** A marker with two members. */
                @Documented
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
                public @interface Marker {
                    /** @return the name */
                    String name();

                    String[] tags() default {};
                }

                @Target(ElementType.TYPE_USE)
                @interface NonEmpty {}

                @Marker(name = "field")
                @Deprecated(since = "1", forRemoval = false)
                private @NonEmpty String label = "x";

                /**
                 * Doubles a value; see {@link Math#multiplyExact(int, int)}.
                 *
                 * @param x the value
                 * @return {@code 2 * x}
                 * @throws ArithmeticException on overflow
                 */
                @SuppressWarnings({"unused", "rawtypes"})
                public int value(int x) {
                    return Math.multiplyExact(x, 2) + label.length();
                }
            }
            """;

    private static final String TWO_TYPES = """
            package demo;

            class Base {
                int id() {
                    return 1;
                }
            }

            public class TwoTypes extends Base {
                @Override
                int id() {
                    return super.id() + 1;
                }
            }

            interface Third {
                default String name() {
                    return "third";
                }
            }
            """;

    private static final String ESCAPES = """
            package demo;

            class Escapes {
                String[] strings = new String[] { "\\\\u{U1}", "\\\\u1234", "\\\\u{00AUF}" };
                char c1 = '\\uD800';
                char c2 = '\\uDfFf';
                String s1 = "A\\ud83c\\udf09";
                String s3 = "C\\uDfFf D \\ud83c\\udf09\\ud83c\\udf09";
                String t = "\\t	\\n";
                char quote = '\\'';
            }
            """;

    private static final String NON_ASCII = """
            package demo;

            /** Prices in € and names like Zoë: 🌉. */
            class Unicode {
                String euro = "€ 5";
                String name = "Zoë";
                String bridge = "🌉";
                char e = 'é';
            }
            """;

    private static final String TEXT_BLOCK = """
            public class TextBlock {
                String s = \"""
                    Hello
                    World
                    \""";
            }
            """;

    private static final String END_OF_LINE_BREAKS = """
            import java.util.Objects;
            class EndOfLineBreaks {
                void test() {
                    boolean b = Objects.equals(1, 2) //
                                && Objects.equals(3, 4) //
                                && Objects.equals(4, 5);
                }
            }
            """;

    private static final String FOLDING = """
            package demo;

            class Folding {
                static final String KEY = "a" + "b";
                static final String MIXED = "x" + 1 + 'c' + 2L + true;

                @SuppressWarnings("un" + "checked")
                String label(String in, int n) {
                    String joined = "pre" + "fix" + in + "suf" + "fix" + n;
                    switch (in) {
                        case KEY:
                            return joined;
                        case "c" + "d":
                            return MIXED;
                        default:
                            return "x" + "y" + "z";
                    }
                }
            }
            """;

    private static final String LOGGING = """
            package p;

            import java.util.logging.Logger;

            public class Log {
                private static final Logger LOG = Logger.getLogger(Log.class.getName());

                public static void info(String message) {
                    LOG.info(message);
                }
            }
            """;
}
