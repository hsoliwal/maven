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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.TreeVisitingPrinter;
import org.openrewrite.java.tree.J;
import org.openrewrite.tree.ParseError;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Oracle 1, rewrite-maven-plugin's parse path: {@code MavenMojoProjectParser} builds
 * {@code JavaParser.fromJavaVersion()} (on JDK 21 the classic {@code Java21Parser}, which runs a private javac) and
 * parses every source again. The bridge must give the same tree: the same printed text, the same node structure
 * ({@link TreeVisitingPrinter}) and the same types on every node ({@link TypeTrail}).
 */
class ClassicParserParityTest {
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    /** Object.toString identity hashes, as in {@code J$NewArray$Padding@784223e9}. */
    private static final Pattern IDENTITY_HASH = Pattern.compile("(?<=[\\w$])@[0-9a-f]{1,8}\\b");
    /**
     * A node TreeVisitingPrinter shows through its truncated toString; the cut point moves with the length of the ids
     * and hashes inside, so only the node line is compared (its text is compared by printAll).
     */
    private static final Pattern TRUNCATED_TO_STRING = Pattern.compile("\\| \"J\\.\\w+\\(.*\\.\\.\\.\"");

    @Test
    void forTheKnownRewrite8890CommentDefectTheClassicParserAlsoGivesNoTree() {
        Fixture fixture = Fixtures.rewrite8890CommentDefect();
        Path src = fixture.write(
                Javac.WORK.resolve("classic-defect").resolve(fixture.name()).toAbsolutePath());
        List<SourceFile> parsed = JavaParser.fromJavaVersion()
                .build()
                .parse(
                        fixture.files().keySet().stream().map(src::resolve).toList(),
                        src,
                        new InMemoryExecutionContext())
                .toList();
        assertEquals(fixture.files().size(), parsed.size());
        parsed.forEach(sourceFile -> assertInstanceOf(ParseError.class, sourceFile, sourceFile::printAll));
    }

    @TestFactory
    Stream<DynamicTest> theBridgeGivesTheClassicParsersTreeForEveryFile() {
        return Fixtures.compiling().stream().map(fixture -> dynamicTest(fixture.name(), () -> assertParity(fixture)));
    }

    private static void assertParity(Fixture fixture) throws IOException {
        Javac.Compilation compilation =
                Javac.compile("classic-parity", fixture, Javac.Mode.DIRECT, CompilerTreeLstTest.BYFILE);
        Path src = Javac.WORK
                .resolve("classic-parity")
                .resolve(fixture.name())
                .resolve("src")
                .toAbsolutePath();
        Map<String, J.CompilationUnit> classic = classic(src, fixture);
        List<FileLst> files = compilation.collector().orElseThrow().files();
        assertEquals(
                classic.keySet().stream().sorted().toList(),
                files.stream().map(f -> f.row().path()).toList());
        files.forEach(file -> assertSameTree(classic.get(file.row().path()), file));
    }

    private static void assertSameTree(J.CompilationUnit oracle, FileLst file) {
        J.CompilationUnit bridge =
                file.lst().orElseThrow(() -> new AssertionError(file.row().tsv()));
        assertEquals(oracle.printAll(), bridge.printAll(), file.row().path());
        assertEquals(structure(oracle), structure(bridge), file.row().path());
        assertEquals(TypeTrail.of(oracle), TypeTrail.of(bridge), file.row().path());
        assertEquals(oracle.getCharset(), bridge.getCharset(), file.row().path());
        assertEquals(
                oracle.isCharsetBomMarked(),
                bridge.isCharsetBomMarked(),
                file.row().path());
    }

    /** The node structure; TreeVisitingPrinter prints some nodes through toString, which holds random ids. */
    private static String structure(J.CompilationUnit lst) {
        String printed =
                TRUNCATED_TO_STRING.matcher(TreeVisitingPrinter.printTree(lst)).replaceAll("| <toString>");
        printed = UUID.matcher(printed).replaceAll("<id>");
        return IDENTITY_HASH.matcher(printed).replaceAll("@<hash>");
    }

    private static Map<String, J.CompilationUnit> classic(Path src, Fixture fixture) {
        JavaParser parser = JavaParser.fromJavaVersion().build();
        List<Path> paths = fixture.files().keySet().stream().map(src::resolve).toList();
        InMemoryExecutionContext ctx = new InMemoryExecutionContext(t -> fail("classic parser: " + t, t));
        return parser.parse(paths, src, ctx)
                .map(ClassicParserParityTest::compilationUnit)
                .collect(Collectors.toMap(cu -> cu.getSourcePath().toString().replace('\\', '/'), Function.identity()));
    }

    private static J.CompilationUnit compilationUnit(SourceFile sourceFile) {
        return assertInstanceOf(J.CompilationUnit.class, sourceFile, () -> sourceFile.printAll());
    }
}
