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

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Concatenations of string literals, which javac's parser folds into one literal when string folding is on (its
 * default). The bridge keeps javac's default and gives each folded chain back its {@code +} nodes.
 */
final class FoldingFixtures {
    /** Chain lengths of the stress fixtures: plain javac compiles them all; with folding off javac overflows. */
    static final List<Integer> CONCAT_LENGTHS = List.of(1500, 2000, 3000, 10000);

    /** A chain the bridge gives back in full: as long as it splits one folded literal into. */
    static final int BOUNDED_LENGTH = 128;

    private FoldingFixtures() {}

    static Fixture folding() {
        return Fixture.of("string-folding", Fixtures.OWN, "demo/Folding.java", FOLDING);
    }

    /** Chains with comments, escapes, unicode escapes, text blocks, numeric operands, parentheses and labels. */
    static Fixture chains() {
        return Fixture.of("string-folding-chains", Fixtures.OWN, "demo/Chains.java", CHAINS);
    }

    /** One constant that concatenates {@code n} literals, ten per line, as the review's stress cases. */
    static Fixture concat(int n) {
        String chain = IntStream.range(0, n)
                .mapToObj(i -> (i % 10 == 0 ? "\n        " : "") + "\"a\"")
                .collect(Collectors.joining(" + "));
        return Fixture.of(
                "concat-" + n,
                Fixtures.OWN,
                "cat/Cat.java",
                "package cat;\n\npublic class Cat {\n    static final String S =" + chain
                        + ";\n\n    String get() {\n        return S;\n    }\n}\n");
    }

    /** A folded chain with escaped surrogates, whose source text the parser visitor rewrites. */
    static Fixture surrogates() {
        return Fixture.of(
                "string-folding-surrogates",
                Fixtures.OWN,
                "demo/Surrogates.java",
                "package demo;\n\nclass Surrogates {\n    String s = \"\\ud83c\" + \"\\udf09\";\n}\n");
    }

    static List<Fixture> stress() {
        return CONCAT_LENGTHS.stream().map(FoldingFixtures::concat).toList();
    }

    static final String FOLDING = """
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

    static final String CHAINS = """
            package demo;

            import java.util.List;

            class Chains {
                static final String MESSAGE = "The packaging for this project did not assign "
                        + "a file to the build artifact";
                static final String COMMENTED = "a" /* before */ + /* after */ "b" // end of line
                        + "c";
                static final int N = 2;
                static final String NUMERIC = 1 + N + "x" + "y" + 'z';
                static final String ESCAPED = "tab\\t" + "quote\\"" + "back\\\\slash" + "\\101\\7\\s" + "\\u00e9" + "\\u0041";
                static final String BLOCK = \"""
                        first
                          second \\
                        joined
                        \""" + "tail" + \"""
                        x\""";

                @SuppressWarnings("un" + "checked")
                String label(String in) {
                    String s = "pre" + "fix" + in + "suf" + "fix";
                    s += "more" + "text";
                    List<String> list = List.of("one" + "two", ("three" + "four"));
                    switch (in) {
                        case "c" + "d":
                            return ("p" + "q") + s + list;
                        default:
                            return s + "x" + "y" + "z";
                    }
                }
            }
            """;
}
