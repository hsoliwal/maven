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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * In-process javac (javax.tools, with a DiagnosticListener) on the round-2 verifiers' reproducers, under javac's
 * default compile policy: with the plugin, the result, every diagnostic (text and order), the output text
 * ({@code -verbose} included, normalised for timings) and every class file and generated file must equal the compile
 * without it. These are the compiles that the conversion changed by completing library classes javac never read,
 * attributing Javadoc references with javac's own Attr, or losing a classpath processor.
 */
class IsolationParityTest {
    /** {@code -verbose} timings and the module-info loading lines, whose order javac itself does not keep. */
    private static final Pattern TIMING = Pattern.compile("\\d+ms]");

    private static final Pattern MODULE_INFO = Pattern.compile("(?m)^\\[loading /modules/[^/]*/module-info.class]\\R");
    private static final Pattern OUTPUT_DIR = Pattern.compile("classes-[a-z_]+-");

    @TestFactory
    Stream<DynamicTest> thePluginNeitherCompletesLibraryClassesNorAttributesJavadocInJavacsOwnState() {
        return cases().stream()
                .flatMap(c -> Stream.of(Javac.Mode.DIRECT, Javac.Mode.XPLUGIN)
                        .map(mode -> dynamicTest(c.name() + " " + mode, () -> assertSame(c, mode))));
    }

    @TestFactory
    Stream<DynamicTest> aClasspathProcessorStillRunsWithThePlugin() {
        return Stream.of(
                dynamicTest(
                        "explicit -proc:full, processor path",
                        () -> assertSame(
                                IsolationFixtures.processorUse("proc-full", "-proc:full"), Javac.Mode.XPLUGIN)),
                dynamicTest(
                        "implicit processing, classpath",
                        () -> assertSame(IsolationFixtures.processorUse("proc-implicit"), Javac.Mode.XPLUGIN_CP)));
    }

    @TestFactory
    Stream<DynamicTest> theConvertedFilesOfTheReproducersAreOk() {
        return Stream.of(
                        IsolationFixtures.completion(true),
                        IsolationFixtures.javadoc(),
                        IsolationFixtures.javadocSourcepath(),
                        IsolationFixtures.records())
                .map(fixture -> dynamicTest(fixture.name(), () -> assertAllOk(fixture)));
    }

    @Test
    void aFileWhoseClassesWereNeverGeneratedIsNotOk() throws IOException {
        Fixture fixture = IsolationFixtures.completion(true).named("should-stop-flow");
        Javac.Compilation plain = Javac.compile("census-honesty", fixture, Javac.Mode.NONE, SHOULD_STOP_FLOW);
        Javac.Compilation with = Javac.compile("census-honesty", fixture, Javac.Mode.DIRECT, SHOULD_STOP_FLOW);
        assertTrue(plain.success());
        assertTrue(plain.classes().isEmpty(), plain.classes()::toString);
        assertEquals(plain.classes(), with.classes());
        CompilerTreeLstTest.rows(with).values().forEach(row -> {
            assertNotEquals(LstStatus.OK.label(), row.status(), row::tsv);
            assertTrue(row.status().startsWith("skipped: compile did not finish (0/1"), row::tsv);
        });
    }

    @Test
    void aSkippedRowHasNoBuildEvent() throws IOException {
        Fixture fixture = IsolationFixtures.policy().get(1);
        Javac.Compilation with = Javac.compile("census-honesty", fixture, Javac.Mode.DIRECT);
        assertFalse(with.success());
        CompilerTreeLstTest.rows(with).values().forEach(row -> {
            assertEquals(LstStatus.COMPILE_ERRORS.label(), row.status(), row::tsv);
            assertEquals("-", row.builtAt(), row::tsv);
        });
    }

    private static final String SHOULD_STOP_FLOW = "-XDshould-stop.ifNoError=FLOW";

    /** One reproducer compile: a fixture and the javac options it is compiled with on both sides. */
    record Case(Fixture fixture, List<String> options) {
        String name() {
            return fixture.name() + " " + String.join(" ", options);
        }
    }

    static List<Case> cases() {
        List<Case> cases = new ArrayList<>();
        add(cases, IsolationFixtures.completion(false), "", "-Xlint:all -Werror", VERBOSE);
        add(cases, IsolationFixtures.completion(true), "", "-Xlint:all", VERBOSE);
        add(cases, IsolationFixtures.javadoc(), "", "-Xlint:all", "-Xlint:all -Werror", "-Xlint:none");
        add(cases, IsolationFixtures.javadoc(), "-Xdoclint:all", VERBOSE);
        add(cases, IsolationFixtures.javadocSourcepath(), "", "-Xdoclint:all", "-Xlint:all");
        add(cases, IsolationFixtures.records(), "", "-Xlint:all");
        IsolationFixtures.policy().forEach(fixture -> add(cases, fixture, ""));
        return cases;
    }

    private static final String VERBOSE = "-verbose";

    /** One case per option set; each set is the options separated by single spaces, or empty. */
    private static void add(List<Case> cases, Fixture fixture, String... optionSets) {
        Stream.of(optionSets)
                .map(set -> set.isEmpty() ? List.<String>of() : List.of(set.split(" ")))
                .forEach(options -> cases.add(new Case(fixture, options)));
    }

    private static void assertSame(Case c, Javac.Mode mode) throws IOException {
        String[] options = c.options().toArray(String[]::new);
        Javac.Compilation plain = Javac.compile("isolation", c.fixture(), Javac.Mode.NONE, options);
        Javac.Compilation with = Javac.compile("isolation", c.fixture(), mode, options);
        assertSame(plain, with);
    }

    private static void assertSame(Fixture fixture, Javac.Mode mode) throws IOException {
        assertSame(new Case(fixture, List.of()), mode);
    }

    static void assertSame(Javac.Compilation plain, Javac.Compilation with) {
        assertEquals(plain.success(), with.success(), with::output);
        assertEquals(plain.diagnostics(), with.diagnostics());
        assertEquals(normalised(plain.output()), normalised(with.output()));
        assertEquals(plain.classes(), with.classes());
    }

    /** The output text without timings, module-info loading lines and the per-mode output directory name. */
    static String normalised(String output) {
        String text = MODULE_INFO.matcher(output).replaceAll("");
        return OUTPUT_DIR.matcher(TIMING.matcher(text).replaceAll("Nms]")).replaceAll("classes-MODE-");
    }

    private static void assertAllOk(Fixture fixture) throws IOException {
        Javac.Compilation with = Javac.compile("isolation-census", fixture, Javac.Mode.DIRECT);
        assertTrue(with.success(), () -> String.join("\n", with.diagnostics()));
        Map<String, CensusRow> rows = CompilerTreeLstTest.rows(with);
        assertEquals(fixture.files().size(), rows.size());
        rows.values().forEach(row -> assertEquals(LstStatus.OK.label(), row.status(), row::tsv));
        assertEquals(
                "",
                with.output(),
                () -> rows.values().stream().map(CensusRow::tsv).collect(Collectors.joining("\n")));
    }
}
