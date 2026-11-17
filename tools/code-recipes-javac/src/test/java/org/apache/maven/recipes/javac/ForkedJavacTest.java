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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * The command-line javac of the running JDK, as a forked maven-compiler-plugin runs it: there is no
 * {@code DiagnosticListener} and no {@code JavacTask} API, so end positions and comments come only from javac's
 * reaction to the plugin's task listener. With {@code -processorpath} and {@code -Xplugin:MavenRecipes} it must exit
 * the same, print the same and write the same class files as without, and write the census the in-process run writes.
 */
class ForkedJavacTest {
    private static final long TIMEOUT_SECONDS = 120;

    record Forked(int exit, String output, Map<String, String> classes, String census) {}

    @TestFactory
    Stream<DynamicTest> forkedJavacWithThePluginMatchesForkedJavacWithoutAndTheInProcessCensus() {
        return JavacOutputParityTest.allFixtures()
                .map(fixture -> dynamicTest(fixture.name(), () -> assertForked(fixture)));
    }

    static void assertForked(Fixture fixture) throws IOException, InterruptedException {
        Path dir = Javac.WORK.resolve("forked").resolve(fixture.name()).toAbsolutePath();
        Path src = fixture.write(dir);
        Forked plain = fork(fixture, dir, src, false, Javac.PluginArgs.DEFAULT);
        Forked with = fork(fixture, dir, src, true, Javac.PluginArgs.DEFAULT);
        assertEquals(plain.exit(), with.exit());
        assertEquals(plain.output(), with.output());
        assertEquals(plain.classes(), with.classes());
        Javac.Compilation inProcess = Javac.compile("forked", fixture, Javac.Mode.DIRECT, CompilerTreeLstTest.BYFILE);
        assertEquals(LstCensus.tsv(inProcess.collector().orElseThrow().census()), with.census());
    }

    /** Forks javac on the fixture, with the plugin and the given options or without it. */
    static Forked fork(Fixture fixture, Path dir, Path src, boolean plugin, Javac.PluginArgs pluginArgs)
            throws IOException, InterruptedException {
        String label = plugin ? "plugin" : "plain";
        Path classes = dir.resolve("forked-classes-" + label);
        Javac.emptyDirectory(classes);
        Path census = dir.resolve("forked-census.tsv");
        if (Files.isRegularFile(census)) {
            Files.delete(census);
        }
        List<String> command = command(fixture, dir, classes);
        if (plugin) {
            command.addAll(List.of("-processorpath", Javac.pluginPath()));
            command.add("-Xplugin:" + MavenRecipesPlugin.NAME + " " + pluginArgs.of(census, src));
        }
        fixture.files().keySet().forEach(path -> command.add(src.resolve(path).toString()));
        return run(command, dir, classes, census);
    }

    /** javac with the jdk.compiler exports, the output directory, the policy and the fixture's options. */
    private static List<String> command(Fixture fixture, Path dir, Path classes) throws IOException {
        Files.createDirectories(dir.resolve("empty"));
        List<String> command = new ArrayList<>(List.of(javac().toString()));
        Arrays.stream(System.getProperty("recipes.javacExports", "").trim().split("\\s+"))
                .filter(flag -> !flag.isEmpty())
                .forEach(flag -> command.add("-J" + flag));
        command.addAll(List.of(
                "-d", classes.toString(), "-classpath", dir.resolve("empty").toString()));
        command.add(CompilerTreeLstTest.BYFILE);
        command.addAll(fixture.options());
        return command;
    }

    private static Forked run(List<String> command, Path dir, Path classes, Path census)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        byte[] output = process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS), "javac timed out");
        String censusText = Files.isRegularFile(census) ? Files.readString(census, StandardCharsets.UTF_8) : "";
        return new Forked(
                process.exitValue(), new String(output, StandardCharsets.UTF_8), Javac.hashes(classes), censusText);
    }

    private static Path javac() {
        boolean windows =
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "javac.exe" : "javac");
    }
}
