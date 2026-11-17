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
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Oracle 2, maven-compiler-plugin's compile: plain javac with the same options. With the plugin (initialised
 * directly, or loaded through {@code -processorpath} and {@code -Xplugin:MavenRecipes}) javac must return the same
 * result, report the same diagnostics and write byte-identical class files, also for sources javac rejects and for
 * {@code -Werror}. The census of the two plugin modes must be the same text.
 */
class JavacOutputParityTest {
    @TestFactory
    Stream<DynamicTest> thePluginChangesNoResultDiagnosticOrClassFile() {
        return allFixtures()
                .flatMap(fixture -> Stream.of(Javac.Mode.DIRECT, Javac.Mode.XPLUGIN)
                        .map(mode -> dynamicTest(fixture.name() + " " + mode, () -> assertUnchanged(fixture, mode))));
    }

    @TestFactory
    Stream<DynamicTest> bothPluginModesWriteTheSameCensus() {
        return allFixtures().map(fixture -> dynamicTest(fixture.name(), () -> assertSameCensus(fixture)));
    }

    @TestFactory
    Stream<DynamicTest> aFailedCompileKeepsNoTree() {
        return Fixtures.failing().stream().map(fixture -> dynamicTest(fixture.name(), () -> assertNoTree(fixture)));
    }

    static Stream<Fixture> allFixtures() {
        return Stream.of(
                        Fixtures.compiling(),
                        List.of(
                                Fixtures.module(),
                                Fixtures.generated(),
                                Fixtures.crossFileSuperclass(),
                                Fixtures.rewrite8890CommentDefect()),
                        Fixtures.failing())
                .flatMap(List::stream);
    }

    private static void assertUnchanged(Fixture fixture, Javac.Mode mode) throws IOException {
        Javac.Compilation plain = Javac.compile("javac-parity", fixture, Javac.Mode.NONE, CompilerTreeLstTest.BYFILE);
        Javac.Compilation with = Javac.compile("javac-parity", fixture, mode, CompilerTreeLstTest.BYFILE);
        assertEquals(plain.success(), with.success());
        assertEquals(plain.diagnostics(), with.diagnostics());
        assertEquals(plain.classes(), with.classes());
        boolean failing = Fixtures.failing().stream().anyMatch(f -> f.name().equals(fixture.name()));
        assertEquals(!failing, plain.success(), () -> String.join("\n", plain.diagnostics()));
    }

    private static void assertSameCensus(Fixture fixture) throws IOException {
        Javac.Compilation direct = Javac.compile("census", fixture, Javac.Mode.DIRECT, CompilerTreeLstTest.BYFILE);
        Javac.Compilation xplugin = Javac.compile("census", fixture, Javac.Mode.XPLUGIN, CompilerTreeLstTest.BYFILE);
        assertEquals(LstCensus.tsv(direct.collector().orElseThrow().census()), xplugin.census());
        assertTrue(xplugin.census().startsWith(LstCensus.HEADER + "\n"));
    }

    private static void assertNoTree(Fixture fixture) throws IOException {
        Javac.Compilation compilation =
                Javac.compile("failing", fixture, Javac.Mode.DIRECT, CompilerTreeLstTest.BYFILE);
        assertFalse(compilation.success());
        List<FileLst> files = compilation.collector().orElseThrow().files();
        assertEquals(fixture.files().size(), files.size());
        files.forEach(file ->
                assertEquals(LstStatus.COMPILE_ERRORS.label(), file.row().status()));
        files.forEach(file -> assertTrue(file.lst().isEmpty()));
    }
}
