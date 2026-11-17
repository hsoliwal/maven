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

/** The source texts of {@link Fixtures}; see there for where each case comes from. */
final class FixtureSources {
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

    static final String GENERICS = """
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

    static final String JAVA21 = """
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

    static final String RECORD_PATTERNS = """
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

    static final String ANNOTATED = """
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

    static final String TWO_TYPES = """
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

    static final String ESCAPES = """
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

    static final String NON_ASCII = """
            package demo;

            /** Prices in € and names like Zoë: 🌉. */
            class Unicode {
                String euro = "€ 5";
                String name = "Zoë";
                String bridge = "🌉";
                char e = 'é';
            }
            """;

    static final String TEXT_BLOCK = """
            public class TextBlock {
                String s = \"""
                    Hello
                    World
                    \""";
            }
            """;

    static final String END_OF_LINE_BREAKS = """
            import java.util.Objects;
            class EndOfLineBreaks {
                void test() {
                    boolean b = Objects.equals(1, 2) //
                                && Objects.equals(3, 4) //
                                && Objects.equals(4, 5);
                }
            }
            """;

    static final String LOGGING = """
            package p;

            import java.util.logging.Logger;

            public class Log {
                private static final Logger LOG = Logger.getLogger(Log.class.getName());

                public static void info(String message) {
                    LOG.info(message);
                }
            }
            """;

    private FixtureSources() {}
}
