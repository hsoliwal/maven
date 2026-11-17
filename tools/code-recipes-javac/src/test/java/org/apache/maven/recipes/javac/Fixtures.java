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
import java.util.stream.Stream;

/**
 * The sources the tests compile. Cases marked "rewrite-java-tck" are copied from OpenRewrite's parser test kit
 * (github.com/openrewrite/rewrite, rewrite-java-tck/src/main/java/org/openrewrite/java/tree, Apache-2.0): at tag
 * v8.89.0, except the two CommentTest cases of {@link #rewrite8890CommentDefect()}, which upstream added with its fix
 * (commit 3c87c3ea6e, openrewrite/rewrite#8564). The class or method named in the origin is the test they come from.
 * Where a case was edited (renamed class, kept escapes), the origin says so.
 */
final class Fixtures {
    static final String OWN = "own";
    static final String TCK = "rewrite-java-tck v8.89.0: ";
    private static final String TCK_8564 = "rewrite-java-tck 3c87c3ea6e (openrewrite/rewrite#8564): ";
    private static final String JAVA21_ORIGIN =
            "SwitchPatternMatchingTest (shouldParseJava21PatternSwitch, shouldSupportParsingNullSwitch)";
    private static final String RECORD_ORIGIN =
            "RecordPatternMatchingTest.shouldParseJava21NestedPatternMatchForRecords";
    private static final String ESCAPES_ORIGIN = "LiteralTest (escapes kept as written)";

    private Fixtures() {}

    /** Fixtures that compile; every file must convert with print equality. */
    static List<Fixture> compiling() {
        return Stream.of(language(), text()).flatMap(List::stream).toList();
    }

    private static List<Fixture> language() {
        return List.of(
                Fixture.of("plain", OWN, "demo/Plain.java", FixtureSources.PLAIN),
                Fixture.of("generics-lambdas", OWN, "demo/Generics.java", FixtureSources.GENERICS),
                Fixture.of("java21", OWN + "; " + TCK + JAVA21_ORIGIN, "demo/Shapes.java", FixtureSources.JAVA21),
                Fixture.of("tck-record-patterns", TCK + RECORD_ORIGIN, "Test.java", FixtureSources.RECORD_PATTERNS),
                Fixture.of("annotations-javadoc", OWN, "demo/Annotated.java", FixtureSources.ANNOTATED),
                Fixture.of("two-top-level", OWN, "demo/TwoTypes.java", FixtureSources.TWO_TYPES),
                packageInfo());
    }

    private static List<Fixture> text() {
        return List.of(
                crlf(),
                Fixture.of("unicode-escapes", TCK + ESCAPES_ORIGIN, "demo/Escapes.java", FixtureSources.ESCAPES),
                Fixture.of("non-ascii", OWN, "demo/Unicode.java", FixtureSources.NON_ASCII),
                tckLiteralsAndComments(),
                FoldingFixtures.folding(),
                FoldingFixtures.chains(),
                FoldingFixtures.concat(FoldingFixtures.BOUNDED_LENGTH));
    }

    private static Fixture packageInfo() {
        return Fixture.of(
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
                "package q;\n// no type at all: javac sends no ANALYZE event for this file\n");
    }

    /** A module: module-info.java is compiled but has no OpenRewrite tree. */
    static Fixture module() {
        return Fixture.of(
                "module-info",
                OWN,
                "module-info.java",
                "/** A module. */\nmodule demo.mod {\n    requires java.logging;\n    exports p;\n}\n",
                "p/Log.java",
                FixtureSources.LOGGING);
    }

    /** A file under the build directory ({@code <root>/target} by default) and one outside it. */
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
        return List.of(
                utf8Bom(),
                Fixture.of("type-error", OWN, "demo/Broken.java", BROKEN),
                Fixture.of("werror-warning", OWN, "demo/Raw.java", RAW).withOptions("-Xlint:all", "-Werror"));
    }

    private static final String BROKEN = "package demo;\n\nclass Broken {\n    String s = 1;\n}\n";
    private static final String RAW =
            "package demo;\n\nimport java.util.*;\n\nclass Raw {\n    List names = new ArrayList();\n}\n";

    /** A UTF-8 byte order mark, which javac 21 rejects. */
    private static Fixture utf8Bom() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] text = "package demo;\n\nclass Bom {}\n".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[bom.length + text.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(text, 0, withBom, bom.length, text.length);
        return Fixture.of("utf8-bom", OWN, Map.of("demo/Bom.java", withBom));
    }

    private static Fixture crlf() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("demo/Plain.java", FixtureSources.PLAIN.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
        return Fixture.of("crlf", OWN, files);
    }

    /**
     * Two CommentTest cases that rewrite 8.89.0 itself cannot print back: a comment between an annotation and the
     * modifiers drops a modifier from the tree (fixed upstream after 8.89.0 by openrewrite/rewrite#8564, commit
     * 3c87c3ea6e, which added these cases). The classic parser returns a ParseError for them; the bridge must refuse
     * them as print mismatches and hand no tree to recipes.
     */
    static Fixture rewrite8890CommentDefect() {
        return Fixture.of(
                "tck-comment-modifiers",
                TCK_8564 + "CommentTest (slashImmediatelyAfterMultilineOpenerBeforeModifier,"
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
                TCK_LITERALS_AND_COMMENTS);
    }

    /** Path and text pairs of the TextBlockTest, BinaryTest and CommentTest cases. */
    private static final String[] TCK_LITERALS_AND_COMMENTS = {
        "TextBlock.java",
        FixtureSources.TEXT_BLOCK,
        "Arithmetic.java",
        "class Arithmetic {\n    void test() {\n    int n = 0 + 1;\n    }\n}\n",
        "FoldableStrings.java",
        "class FoldableStrings {\n    void test() {\n        String n = \"a\" + \"b\";\n    }\n}\n",
        "EndOfLineBreaks.java",
        FixtureSources.END_OF_LINE_BREAKS,
        "BackToBack.java",
        "class BackToBack {\n    /*\n        Comment 1\n    *//*\n        Comment 2\n    */\n}\n",
        "NestedInsideSingleLine.java",
        "class NestedInsideSingleLine {// /*\n}\n",
        "CommentAsLastLine.java",
        "class CommentAsLastLine {\n}\n//\n",
        "hello/CommentWithUrl.java",
        "package hello;\npublic class CommentWithUrl {\n  public static void test() {\n"
                + "    /*addItem(\"Site A\", \"https://hello.com/A\");*/\n  }\n}\n"
    };
}
