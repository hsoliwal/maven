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
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * In-process compiles of non-UTF-8 sources: maven-compiler-plugin gives the charset to the file manager, and a
 * javax.tools caller may give only {@code -encoding}. Either way the tree must carry the compile's charset and print
 * the file's exact bytes, as rewrite-maven-plugin's classic parser does with the same charset. The classic parser's
 * tree names the charset it guesses from the bytes (rewrite 8.89.0 {@code EncodingDetectingInputStream}: windows-1252
 * for both fixtures), so only the printed text and bytes are compared with it. The forked compile of the same
 * fixtures is ForkedJavacTest.
 */
class EncodingTest {
    @TestFactory
    Stream<DynamicTest> aNonUtf8SourceGetsATreeInTheCompilesCharset() {
        return Stream.of(EdgeFixtures.encodings(), EdgeFixtures.encodingsWithoutManagerCharset())
                .flatMap(List::stream)
                .map(fixture -> dynamicTest(fixture.name(), () -> assertEncoded(fixture)));
    }

    private static void assertEncoded(Fixture fixture) throws IOException {
        Charset charset = Charset.forName(fixture.options().get(1));
        Javac.Compilation compilation =
                Javac.compile("encoding", fixture, Javac.Mode.DIRECT, CompilerTreeLstTest.BYFILE);
        assertTrue(compilation.success(), compilation::output);
        FileLst file = compilation.collector().orElseThrow().files().get(0);
        assertEquals(LstStatus.OK.label(), file.row().status(), file.row()::tsv);
        J.CompilationUnit lst = file.lst().orElseThrow();
        assertEquals(charset, lst.getCharset());
        assertArrayEquals(fixture.files().get(file.row().path()), lst.printAllAsBytes());
        J.CompilationUnit classic = classic(fixture, file.row().path(), charset);
        assertEquals(classic.printAll(), lst.printAll());
        assertArrayEquals(classic.printAllAsBytes(), lst.printAllAsBytes());
    }

    private static J.CompilationUnit classic(Fixture fixture, String path, Charset charset) {
        Path src = Javac.WORK
                .resolve("encoding")
                .resolve(fixture.name())
                .resolve("src")
                .toAbsolutePath();
        SourceFile parsed = JavaParser.fromJavaVersion()
                .charset(charset)
                .build()
                .parse(List.of(src.resolve(path)), src, new InMemoryExecutionContext())
                .findFirst()
                .orElseThrow();
        return assertInstanceOf(J.CompilationUnit.class, parsed, parsed::printAll);
    }
}
