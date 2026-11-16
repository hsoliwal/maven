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
package org.apache.maven.cling.invoker.mvn;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.api.cli.Invoker;
import org.apache.maven.api.cli.InvokerException;
import org.apache.maven.api.cli.Parser;
import org.apache.maven.api.cli.ParserRequest;
import org.apache.maven.cling.invoker.ProtoLookup;
import org.apache.maven.exception.ExceptionSummary;
import org.apache.maven.jline.JLineMessageBuilderFactory;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uses the original Maven parser/invoker and real result handling; no Maven API substitutes. */
class M3BuildSuggestionsInvokerTest extends MavenInvokerTestSupport {
    @TempDir
    Path temporary;

    private int sequence;
    private final List<String> summaryReferences = new ArrayList<>();

    @Override
    protected Invoker createInvoker(ClassWorld classWorld) {
        return new MavenInvoker(
                ProtoLookup.builder().addMapping(ClassWorld.class, classWorld).build(), null) {
            @Override
            protected void logSummary(MavenContext context, ExceptionSummary summary, String indent) {
                summaryReferences.add(summary.getReference() + " ("
                        + (summary.getException() == null
                                ? "no exception"
                                : summary.getException().getClass().getName())
                        + ")");
                super.logSummary(context, summary, indent);
            }
        };
    }

    @Override
    protected Parser createParser() {
        return new MavenParser();
    }

    @Test
    void ordinaryInvocationAndOptionllmAloneStayInert() throws Exception {
        Outcome normal = completed("validate");
        Outcome llmOnly = completed("-Doptionllm=true", "-Dm3.smart.adviserJar=/missing/artifact.jar", "validate");
        assertEquals(0, normal.exitCode());
        assertEquals(normal.exitCode(), llmOnly.exitCode());
        assertFalse(normal.output().contains("[M3] Build suggestions"));
        assertFalse(llmOnly.output().contains("[M3] Build suggestions"));
    }

    @Test
    void deterministicPassUsesTheCompletedFailureAndPreservesItsExitCode() throws Exception {
        Outcome baseline = completed("not-a-lifecycle-phase");
        Outcome suggestions = completed("-Dm3.smart.suggest=true", "not-a-lifecycle-phase");
        assertEquals(1, baseline.exitCode());
        assertEquals(baseline.exitCode(), suggestions.exitCode());
        assertTrue(suggestions.output().contains("requested lifecycle phase"));
        assertFalse(suggestions.output().contains("[UNTRUSTED LLM]"));
    }

    @Test
    void successfulBuildDoesNotReadInvalidModelConfiguration() throws Exception {
        Outcome result = completed(
                "-Dm3.smart.suggest=true",
                "-Doptionllm=true",
                "-Dm3.smart.adviserJar=/missing/artifact.jar",
                "-Dm3.smart.chromeLlmJarSha256=invalid",
                "validate");
        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("No completed build diagnostics require suggestions"));
        assertFalse(result.output().contains("suggestions unavailable"));
    }

    @Test
    void nativeConfigurationFailureDoesNotReplaceTheMavenFailure() throws Exception {
        Outcome result = completed(
                "-Dm3.smart.suggest=true",
                "-Doptionllm=true",
                "-Dm3.smart.adviserJar=/missing/artifact.jar",
                "not-a-lifecycle-phase");
        assertEquals(1, result.exitCode());
        assertTrue(result.output().contains("suggestions unavailable; Maven's result is unchanged"));
        assertFalse(result.output().contains("[UNTRUSTED LLM]"));
    }

    @Test
    void failNeverRetainsItsOriginalExitContract() throws Exception {
        Outcome baseline = completed("-fn", "not-a-lifecycle-phase");
        Outcome suggestions = completed("-fn", "-Dm3.smart.suggest=true", "-Doptionllm=true", "not-a-lifecycle-phase");
        assertEquals(0, baseline.exitCode());
        assertEquals(baseline.exitCode(), suggestions.exitCode());
        assertTrue(suggestions.output().contains("Build failures were ignored"));
        assertTrue(suggestions.output().contains("suggestions unavailable"));
    }

    @Test
    void nestedModelFailureKeepsModelAndRepositoryAdvice() throws Exception {
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.apache.maven.suggestions.fixture</groupId>
                    <artifactId>unavailable-parent</artifactId>
                    <version>0-never-published</version>
                    <relativePath/>
                  </parent>
                  <artifactId>completed-model-failure</artifactId>
                  <packaging>pom</packaging>
                </project>
                """;
        Outcome baseline = completedWithPom(pom, "validate");
        Outcome suggestions = completedWithPom(pom, "-Dm3.smart.suggest=true", "validate");
        assertEquals(1, baseline.exitCode());
        assertEquals(baseline.exitCode(), suggestions.exitCode());
        assertTrue(suggestions.output().contains("reported POM, parent and reactor"));
        assertTrue(
                suggestions.output().contains("dependency coordinates"),
                () -> suggestions.output() + "\nActual summary references: " + suggestions.references());
        assertFalse(suggestions.output().contains("[UNTRUSTED LLM]"));
    }

    private Outcome completed(String... arguments) throws Exception {
        return completedWithPom("""
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.apache.maven.suggestions.fixture</groupId>
                  <artifactId>completed-build</artifactId>
                  <version>1</version>
                  <packaging>pom</packaging>
                </project>
                """, arguments);
    }

    private Outcome completedWithPom(String pom, String... arguments) throws Exception {
        summaryReferences.clear();
        Path cwd = temporary.resolve("project-" + ++sequence);
        Path home = temporary.resolve("home-" + sequence);
        Files.createDirectories(cwd.resolve(".mvn"));
        Files.createDirectories(home);
        Files.writeString(cwd.resolve("pom.xml"), pom);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        List<String> args = new ArrayList<>(List.of("-B", "-ntp", "-o"));
        args.addAll(List.of(arguments));
        int exit;
        try (ClassWorld world = createClassWorld();
                Invoker invoker = createInvoker(world)) {
            try {
                exit = invoker.invoke(createParser()
                        .parseInvocation(ParserRequest.mvn(args, new JLineMessageBuilderFactory())
                                .cwd(cwd)
                                .userHome(home)
                                .stdOut(stdout)
                                .stdErr(stderr)
                                .embedded(true)
                                .build()));
            } catch (InvokerException.ExitException completed) {
                exit = completed.getExitCode();
            }
        }
        return new Outcome(
                exit,
                stdout.toString(StandardCharsets.UTF_8) + stderr.toString(StandardCharsets.UTF_8),
                List.copyOf(summaryReferences));
    }

    private record Outcome(int exitCode, String output, List<String> references) {}
}
