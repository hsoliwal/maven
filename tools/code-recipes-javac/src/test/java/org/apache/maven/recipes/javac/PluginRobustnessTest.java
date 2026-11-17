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
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * The census and the options are side reports: a problem with either never changes javac's result, diagnostics or
 * class files and never throws into javac. It prints one line on javac's output and skips the census. Paths with
 * spaces, which javac splits into several plugin arguments, are read back whole.
 */
class PluginRobustnessTest {
    private static final String NOTICE = "[" + MavenRecipesPlugin.NAME + "]";
    private static final String ROW = "p/A.java\t1\ttrue\t1\t0\tok\tANALYZE";

    @Test
    void pathsWithSpacesAreReadWholeInEveryForm() throws IOException {
        Map<String, Javac.PluginArgs> forms = Map.of(
                "plain", Javac.PluginArgs.DEFAULT,
                "quoted", (census, src) -> "census=\"" + census + "\" root='" + src + "'",
                "uri", (census, src) -> "census=" + census.toUri() + " root=" + src.toUri());
        for (Map.Entry<String, Javac.PluginArgs> form : forms.entrySet()) {
            Javac.Compilation with =
                    compile(EdgeFixtures.spaced().named("dir with spaces " + form.getKey()), form.getValue());
            assertTrue(with.census().contains("\n" + ROW + "\n"), form.getKey() + "\n" + with.census() + with.output());
            assertEquals("", with.output(), form.getKey());
        }
    }

    @TestFactory
    Stream<DynamicTest> aBadOptionOrCensusPathIsANoticeAndTheCompileIsUntouched() {
        Map<String, Javac.PluginArgs> cases = Map.of(
                "census-is-a-directory", (census, src) -> "census=" + directory(census) + " root=" + src,
                "census-empty-value", (census, src) -> "census= root=" + src,
                "unknown-option", (census, src) -> "cencus=" + census + " root=" + src,
                "census-in-a-file", (census, src) -> "census=" + file(census).resolve("x.tsv") + " root=" + src);
        return cases.entrySet().stream()
                .map(c -> dynamicTest(c.getKey(), () -> assertNotice(c.getKey(), c.getValue())));
    }

    @Test
    void forkedJavacReadsSpacedPathsAndKeepsItsResultOnACensusProblem() throws IOException, InterruptedException {
        ForkedJavacTest.assertForked(EdgeFixtures.spaced());
        Fixture fixture = EdgeFixtures.spaced().named("forked-census-is-a-directory");
        Path dir = Javac.WORK.resolve("forked").resolve(fixture.name()).toAbsolutePath();
        Path src = fixture.write(dir);
        ForkedJavacTest.Forked plain = ForkedJavacTest.fork(fixture, dir, src, false, Javac.PluginArgs.DEFAULT);
        ForkedJavacTest.Forked with = ForkedJavacTest.fork(
                fixture, dir, src, true, (census, root) -> "census=" + directory(census) + " root=" + root);
        assertEquals(plain.exit(), with.exit(), with.output());
        assertEquals(plain.classes(), with.classes());
        assertTrue(with.output().startsWith(NOTICE), with.output());
    }

    @Test
    void aRunOfSpacesThatJavacSplitIsANoticeAndNoDirectoryIsCreated() throws IOException {
        Fixture fixture = EdgeFixtures.spaced().named("two-spaces");
        Path dir = Javac.WORK.resolve("robust").resolve(fixture.name()).toAbsolutePath();
        Path census = dir.resolve("dir with  two spaces").resolve("LST_CENSUS.tsv");
        Path collapsed = dir.resolve("dir with two spaces");
        absent(census.getParent());
        absent(collapsed);
        Javac.Compilation with = compile(fixture, (ignored, src) -> "census=" + census + " root=" + src);
        assertFalse(Files.exists(collapsed), collapsed::toString);
        assertTrue(with.output().startsWith(NOTICE), with.output());
        assertEquals(1, with.output().lines().count(), with.output());
        Javac.Compilation uri = compile(fixture, (ignored, src) -> "census=" + census.toUri() + " root=" + src.toUri());
        assertEquals("", uri.output());
        assertTrue(Files.isRegularFile(census), census::toString);
    }

    private static void absent(Path dir) throws IOException {
        Javac.emptyDirectory(dir);
        Files.delete(dir);
    }

    @Test
    void aFailureDetailIsOneLineOfAtMost300Characters() {
        FileState state = new FileState("p/A.java");
        state.failed("x\n".repeat(20_000));
        String status = state.row().status();
        assertTrue(status.length() <= "failed: ".length() + 300, () -> Integer.toString(status.length()));
        assertFalse(status.contains("\n"));
    }

    private static void assertNotice(String name, Javac.PluginArgs args) throws IOException {
        Fixture fixture = EdgeFixtures.spaced().named(name);
        for (Javac.Mode mode : new Javac.Mode[] {Javac.Mode.DIRECT, Javac.Mode.XPLUGIN}) {
            Javac.Compilation plain = Javac.compile("robust", fixture, Javac.Mode.NONE);
            Javac.Compilation with = Javac.compile("robust", fixture, mode, args);
            assertEquals(plain.success(), with.success(), with.output());
            assertEquals(plain.diagnostics(), with.diagnostics());
            assertEquals(plain.classes(), with.classes());
            assertTrue(with.output().startsWith(NOTICE), mode + ": " + with.output());
            assertEquals(1, with.output().lines().count(), with.output());
        }
    }

    private static Javac.Compilation compile(Fixture fixture, Javac.PluginArgs args) throws IOException {
        Javac.Compilation plain = Javac.compile("robust", fixture, Javac.Mode.NONE);
        Javac.Compilation with = Javac.compile("robust", fixture, Javac.Mode.XPLUGIN, args);
        assertEquals(plain.success(), with.success(), with.output());
        assertEquals(plain.classes(), with.classes());
        return with;
    }

    /** A directory where the census file should be. */
    private static Path directory(Path census) {
        try {
            return Files.createDirectories(census.resolveSibling(census.getFileName() + ".d"));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** A regular file where the census directory should be. */
    private static Path file(Path census) {
        try {
            Path file = census.resolveSibling(census.getFileName() + ".f");
            return Files.isRegularFile(file) ? file : Files.writeString(file, "not a directory");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
