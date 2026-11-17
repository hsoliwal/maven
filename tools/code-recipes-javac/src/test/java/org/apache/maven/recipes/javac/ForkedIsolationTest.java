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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Command-line javac on the round-2 verifiers' reproducers, under javac's default policy: exit code, the whole output
 * text (without the plugin's own notice lines, and with {@code -verbose} normalised) and every class and generated
 * file must be the same with the plugin as without it. This covers older and newer javac (the plugin must turn itself
 * off with one notice), a capped heap, and a processor found on the classpath.
 */
class ForkedIsolationTest {
    private static final String NOTICE = "[" + Javac.PLUGIN + "]";

    @TestFactory
    Stream<DynamicTest> forkedJavacIsUnchangedOnTheReproducers() {
        return IsolationParityTest.cases().stream()
                .filter(c -> !c.options().contains("-Xdoclint:all"))
                .map(c -> dynamicTest(c.name(), () -> assertSame(c.fixture(), c.options(), 0)));
    }

    @TestFactory
    Stream<DynamicTest> aClasspathProcessorStillRunsWithThePlugin() {
        return Stream.of(
                        List.<String>of(),
                        List.of("-proc:full"),
                        List.of("-verbose"),
                        List.of("-verbose", "-proc:full"))
                .map(options -> dynamicTest("implicit " + String.join(" ", options), () -> {
                    String name = "proc-forked" + String.join("", options).replaceAll("\\W", "-");
                    assertSame(IsolationFixtures.processorUse(name), options, 0);
                }));
    }

    @TestFactory
    Stream<DynamicTest> anotherJavacTurnsThePluginOffWithOneNotice() {
        return Stream.of("17", "25")
                .map(feature -> dynamicTest("JDK " + feature, () -> {
                    Path home = Path.of(System.getProperty("recipes.jdk" + feature, ""));
                    assumeTrue(
                            Files.isRegularFile(launcher(home)), "set -Drecipes.jdk" + feature + "=<JDK home> to run");
                    Fixture fixture = IsolationFixtures.completion(true).named("jdk" + feature);
                    assertSame(fixture, List.of("-Xlint:all"), 1, launcher(home));
                }));
    }

    @Test
    void aHeapThePluginCannotGuaranteeTurnsItOffWithOneNotice() throws IOException, InterruptedException {
        assertSame(IsolationFixtures.many(150), List.of("-J-Xmx64m"), 1);
    }

    @Test
    void aHeapAboveTheFloorGivesTheSameCompile() throws IOException, InterruptedException {
        assertSame(IsolationFixtures.many(150).named("many-150-320m"), List.of("-J-Xmx320m"), 0);
    }

    @Test
    void theCensusOfACompileThatWroteNoClassHasNoOkRow() throws IOException, InterruptedException {
        Fixture fixture = IsolationFixtures.completion(true).named("forked-should-stop-flow");
        ForkedJavacTest.Forked with = assertSame(fixture, List.of("-XDshould-stop.ifNoError=FLOW"), 0);
        assertTrue(with.classes().isEmpty(), with.classes()::toString);
        assertFalse(with.census().isEmpty(), with.output());
        with.census().lines().skip(1).forEach(row -> assertFalse(row.contains("\tok\t"), row));
    }

    private static ForkedJavacTest.Forked assertSame(Fixture fixture, List<String> options, int notices)
            throws IOException, InterruptedException {
        return assertSame(fixture, options, notices, ForkedJavacTest.javac());
    }

    /** Plain and plugin runs of the same compile; the plugin run prints exactly {@code notices} notice lines. */
    private static ForkedJavacTest.Forked assertSame(Fixture fixture, List<String> options, int notices, Path javac)
            throws IOException, InterruptedException {
        Path dir =
                Javac.WORK.resolve("forked-isolation").resolve(fixture.name()).toAbsolutePath();
        Path src = fixture.write(dir);
        ForkedJavacTest.Forked plain = ForkedJavacTest.fork(spec(fixture, dir, src, javac, options, false));
        ForkedJavacTest.Forked with = ForkedJavacTest.fork(spec(fixture, dir, src, javac, options, true));
        assertEquals(plain.exit(), with.exit(), with.output());
        assertEquals(notices, noticeLines(with.output()).size(), with.output());
        assertEquals(normalised(plain.output()), normalised(withoutNotices(with.output())));
        assertEquals(plain.classes(), with.classes());
        return with;
    }

    private static ForkedJavacTest.Spec spec(
            Fixture fixture, Path dir, Path src, Path javac, List<String> options, boolean plugin) {
        return new ForkedJavacTest.Spec(fixture, dir, src, javac, options, plugin, Javac.PluginArgs.DEFAULT);
    }

    private static List<String> noticeLines(String output) {
        return output.lines().filter(line -> line.startsWith(NOTICE)).toList();
    }

    private static String withoutNotices(String output) {
        return output.lines()
                .filter(line -> !line.startsWith(NOTICE))
                .map(line -> line + "\n")
                .collect(Collectors.joining());
    }

    private static String normalised(String output) {
        String text = output.lines().map(line -> line + "\n").collect(Collectors.joining());
        return IsolationParityTest.normalised(text.replaceAll("forked-classes-(plain|plugin)", "forked-classes-X"));
    }

    private static Path launcher(Path home) {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        return home.resolve("bin").resolve(windows ? "javac.exe" : "javac");
    }
}
