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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Fixtures from the review of L01: source encodings, paths with spaces, and rewrite 8.89.0 defects. */
final class EdgeFixtures {
    static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final String REVIEW = "review of L01";

    private EdgeFixtures() {}

    /**
     * Non-ASCII sources in a non-UTF-8 encoding, as maven-compiler-plugin compiles them in process: the charset is
     * given to the file manager and as {@code -encoding}.
     */
    static List<Fixture> encodings() {
        Fixture latin1 = encoded("latin1", "l/Lat.java", LATIN1, StandardCharsets.ISO_8859_1);
        Fixture cp1252 = encoded("cp1252", "w/Win.java", CP1252, WINDOWS_1252);
        return List.of(latin1, cp1252, withRelease(latin1), withRelease(cp1252));
    }

    /**
     * With {@code --release}, as maven-compiler-plugin passes it, javac replaces the file manager in its context by a
     * delegating one that hides the charset (DelegatingJavaFileManager.installReleaseFileManager, JDK 21).
     */
    private static Fixture withRelease(Fixture fixture) {
        List<String> options = new java.util.ArrayList<>(fixture.options());
        options.addAll(List.of("--release", "21"));
        return fixture.named(fixture.name() + "-release").withOptions(options.toArray(String[]::new));
    }

    /** The same sources compiled through the javax.tools API without a file manager charset: only -encoding. */
    static List<Fixture> encodingsWithoutManagerCharset() {
        return encodings().stream()
                .map(fixture -> fixture.named(fixture.name() + "-option-only").withManagerCharset(Optional.empty()))
                .toList();
    }

    private static Fixture encoded(String name, String path, String text, Charset charset) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(path, text.getBytes(charset));
        return Fixture.of(name, REVIEW, files)
                .withOptions("-encoding", charset.name())
                .withManagerCharset(Optional.of(charset));
    }

    /** A plain class compiled in a directory whose path has a space, so census and root paths have one too. */
    static Fixture spaced() {
        return Fixture.of("dir with spaces", REVIEW, "p/A.java", SIMPLE);
    }

    /** A project that lies under an ancestor directory named generated-sources, not under its build directory. */
    static Fixture generatedSourcesAncestor() {
        return Fixture.of("generated-sources", REVIEW, "p/A.java", SIMPLE);
    }

    /**
     * Valid sources rewrite 8.89.0 cannot turn into a tree that prints them back; the classic parser gives a
     * ParseError for each, and the bridge must refuse each without handing out a tree.
     */
    static List<Fixture> rewrite8890Defects() {
        return List.of(
                Fixture.of("defect-receiver-parameter", REVIEW, "r/Receiver.java", RECEIVER),
                Fixture.of("defect-multi-u-escape", REVIEW, "u/MultiU.java", MULTI_U),
                Fixture.of("defect-lone-cr", REVIEW, "c/LoneCr.java", LONE_CR),
                Fixture.of("defect-unicode-newline-in-comment", REVIEW, "u/Hidden.java", HIDDEN));
    }

    static final String SIMPLE = "package p;\n\npublic class A {\n    int x = \"a\".length();\n}\n";

    private static final String LATIN1 =
            "package l;\n\n// commentaire: \u00e9t\u00e9\npublic class Lat {\n    String s = \"Gr\u00f6\u00dfe \u00fcnd"
                    + " \u00e7\u00e0\";\n}\n";

    private static final String CP1252 = "package w;\n\n// \u201csmart\u201d \u2013 \u20ac\npublic class Win {\n"
            + "    String s = \"\u20ac \u201cq\u201d \u2014 \u2030\";\n}\n";

    private static final String RECEIVER = "package r;\n\nimport java.lang.annotation.ElementType;\n"
            + "import java.lang.annotation.Target;\n\npublic class Receiver<T> {\n"
            + "    @Target({ElementType.TYPE_USE, ElementType.PARAMETER})\n    @interface Ann {}\n\n"
            + "    void receiver(@Ann Receiver<T> this, @Ann final int p) {}\n}\n";

    private static final String MULTI_U =
            "package u;\n\npublic class MultiU {\n    String s = \"\\u0048\\u0069 \\uuuu0021\";\n}\n";

    private static final String LONE_CR =
            "package c;\rpublic class LoneCr {\r    int x = 1; // c\r" + "    int y() {\r        return x;\r    }\r}\r";

    private static final String HIDDEN = "package u;\n\npublic class Hidden {\n"
            + "    // the escape ends this comment \\u000a int hidden = 42;\n\n    int get() {\n"
            + "        return hidden;\n    }\n}\n";
}
