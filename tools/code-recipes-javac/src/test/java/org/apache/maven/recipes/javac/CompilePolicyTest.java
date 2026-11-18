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
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * javac's compile policy decides whether a file's trees are still undesugared when its last class is analyzed. The
 * default ({@code byTodo}) desugars each class before the next is attributed; {@code byfile} keeps a file together but
 * desugars a superclass early when a file processed before it needs it; {@code simple} analyzes every file before
 * desugaring any. The guard must turn each early desugar into a skipped row, never a wrong tree. The plugin sets no
 * policy: {@code simple} gives the same class files for compiles that pass, but other diagnostics and class files for
 * compiles that fail.
 */
class CompilePolicyTest {
    private static final String SIMPLE = "-XDcompilePolicy=simple";

    @Test
    void underTheDefaultPolicyAFileWithSeveralClassesIsSkippedAndASingleClassFileIsBuilt() throws IOException {
        Map<String, CensusRow> twoTypes = rows(named("two-top-level"));
        assertEquals(
                LstStatus.DESUGARED.label(), twoTypes.get("demo/TwoTypes.java").status());
        Map<String, CensusRow> plain = rows(named("plain"));
        assertEquals(LstStatus.OK.label(), plain.get("demo/Plain.java").status());
    }

    @Test
    void underByfileASuperclassFileDesugaredForAnEarlierFileIsSkipped() throws IOException {
        Map<String, CensusRow> rows = rows(Fixtures.crossFileSuperclass(), CompilerTreeLstTest.BYFILE);
        assertEquals(LstStatus.OK.label(), rows.get("demo/A.java").status());
        assertEquals(LstStatus.DESUGARED.label(), rows.get("demo/B.java").status());
    }

    @Test
    void underSimpleTheSameFilesAreBuilt() throws IOException {
        Map<String, CensusRow> rows = rows(Fixtures.crossFileSuperclass(), SIMPLE);
        assertEquals(LstStatus.OK.label(), rows.get("demo/A.java").status());
        assertEquals(LstStatus.OK.label(), rows.get("demo/B.java").status());
        assertEquals(
                LstStatus.OK.label(),
                rows(named("two-top-level"), SIMPLE).get("demo/TwoTypes.java").status());
    }

    @TestFactory
    Stream<DynamicTest> underSimpleEveryFixtureFileIsBuilt() {
        return Fixtures.compiling().stream()
                .map(fixture -> dynamicTest(
                        fixture.name(),
                        () -> rows(fixture, SIMPLE)
                                .values()
                                .forEach(row -> assertEquals(LstStatus.OK.label(), row.status(), row::tsv))));
    }

    @TestFactory
    Stream<DynamicTest> forPassingCompilesThePolicyOptionChangesNoClassFile() {
        return Fixtures.compiling().stream()
                .map(fixture -> dynamicTest(fixture.name(), () -> {
                    Javac.Compilation byTodo = Javac.compile("policy-classes", fixture, Javac.Mode.NONE);
                    Javac.Compilation byFile =
                            Javac.compile("policy-classes", fixture, Javac.Mode.NONE, CompilerTreeLstTest.BYFILE);
                    Javac.Compilation simple = Javac.compile("policy-classes", fixture, Javac.Mode.NONE, SIMPLE);
                    assertTrue(byTodo.success());
                    assertEquals(byTodo.classes(), byFile.classes());
                    assertEquals(byTodo.classes(), simple.classes());
                }));
    }

    @Test
    void forFailingCompilesThePolicyOptionChangesDiagnosticsSoThePluginSetsNone() throws IOException {
        Fixture flowAttr = IsolationFixtures.policy().get(0);
        assertEquals(2, compile(flowAttr).diagnostics().size());
        assertEquals(1, compile(flowAttr, SIMPLE).diagnostics().size());
    }

    @Test
    void forFailingCompilesThePolicyOptionChangesClassFilesSoThePluginSetsNone() throws IOException {
        Fixture classfile = IsolationFixtures.policy().get(1);
        assertEquals(java.util.Set.of("p/A.class"), compile(classfile).classes().keySet());
        assertEquals(Map.of(), compile(classfile, SIMPLE).classes());
    }

    private static Javac.Compilation compile(Fixture fixture, String... options) throws IOException {
        return Javac.compile("policy-failing", fixture, Javac.Mode.NONE, options);
    }

    private static Fixture named(String name) {
        return Fixtures.compiling().stream()
                .filter(f -> f.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, CensusRow> rows(Fixture fixture, String... options) throws IOException {
        Javac.Compilation compilation = Javac.compile("policy", fixture, Javac.Mode.DIRECT, options);
        assertTrue(compilation.success(), compilation.diagnostics()::toString);
        return CompilerTreeLstTest.rows(compilation);
    }
}
