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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class M3BuildSuggestionsTest {
    private static final String HELP = "http://cwiki.apache.org/confluence/display/MAVEN/";

    @TempDir
    Path temporary;

    @Test
    void disabledAndOptionllmOnlyDoNotReadSnapshotConfigOrProvider() {
        for (boolean llmOnly : List.of(false, true)) {
            Properties user = properties(false, llmOnly);
            AtomicInteger snapshots = new AtomicInteger();
            List<String> lines = new ArrayList<>();
            M3BuildSuggestions.afterBuild(
                    user,
                    new GuardedProperties(Set.of("m3.smart.suggest")),
                    () -> {
                        snapshots.incrementAndGet();
                        return failed();
                    },
                    lines::add,
                    (u, s, p) -> {
                        throw new AssertionError("disabled provider");
                    });
            assertEquals(0, snapshots.get());
            assertTrue(lines.isEmpty());
        }
    }

    @Test
    void userOptOutWinsAndSystemOptInRemainsAvailable() {
        Properties system = properties(true, false);
        Properties user = properties(false, true);
        assertFalse(M3BuildSuggestions.enabled(user, system));
        assertTrue(M3BuildSuggestions.enabled(new Properties(), system));
        assertFalse(M3BuildSuggestions.enabled(null, null));
    }

    @Test
    void completedSuccessAndMissingDiagnosticsNeverReadNativeConfiguration() {
        for (M3BuildSuggestions.BuildSnapshot snapshot : List.of(
                new M3BuildSuggestions.BuildSnapshot(false, 3, 0, Set.of(), List.of()),
                new M3BuildSuggestions.BuildSnapshot(true, 3, 0, Set.of(), List.of()))) {
            Properties user = new GuardedProperties(Set.of("m3.smart.suggest"));
            user.setProperty("m3.smart.suggest", "true");
            List<String> lines = new ArrayList<>();
            M3BuildSuggestions.afterBuild(user, null, () -> snapshot, lines::add, (u, s, p) -> {
                throw new AssertionError("no-diagnostic provider");
            });
            assertTrue(lines.stream().anyMatch(line -> line.contains("No completed build diagnostics")));
        }
    }

    @Test
    void deterministicPassUsesExistingHelpCategoriesWithoutNativeWork() {
        List<String> lines = new ArrayList<>();
        M3BuildSuggestions.BuildSnapshot snapshot = new M3BuildSuggestions.BuildSnapshot(
                true,
                4,
                2,
                Set.of(HELP + "LifecyclePhaseNotFoundException", HELP + "DependencyResolutionException"),
                List.of("Unknown lifecycle phase."));
        M3BuildSuggestions.afterBuild(properties(true, false), null, () -> snapshot, lines::add, (u, s, p) -> {
            throw new AssertionError("deterministic provider");
        });
        assertTrue(lines.stream().anyMatch(line -> line.contains("requested lifecycle phase")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("dependency coordinates")));
        assertFalse(lines.stream().anyMatch(line -> line.contains("UNTRUSTED LLM")));
    }

    @Test
    void plainHelpKeysFromCurrentMavenKeepTheirCategories() {
        List<String> lines = new ArrayList<>();
        M3BuildSuggestions.BuildSnapshot snapshot = new M3BuildSuggestions.BuildSnapshot(
                true,
                1,
                3,
                Set.of("LifecyclePhaseNotFoundException", "UnknownHostException", "UnsupportedClassVersionError"),
                List.of("Completed Maven diagnostics."));
        M3BuildSuggestions.afterBuild(properties(true, false), null, () -> snapshot, lines::add, (u, s, p) -> {
            throw new AssertionError("deterministic provider");
        });
        assertTrue(lines.stream().anyMatch(line -> line.contains("requested lifecycle phase")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("dependency coordinates")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("selected Java runtime")));
    }

    @Test
    void unrelatedHelpUrlsDoNotImpersonateMavenCategories() {
        List<String> lines = new ArrayList<>();
        M3BuildSuggestions.BuildSnapshot snapshot = new M3BuildSuggestions.BuildSnapshot(
                true,
                1,
                1,
                Set.of("https://example.invalid/LifecyclePhaseNotFoundException"),
                List.of("Third-party diagnostic."));
        M3BuildSuggestions.afterBuild(properties(true, false), null, () -> snapshot, lines::add, (u, s, p) -> {
            throw new AssertionError("deterministic provider");
        });
        assertTrue(lines.stream().anyMatch(line -> line.contains("Review Maven's reported failure")));
        assertFalse(lines.stream().anyMatch(line -> line.contains("requested lifecycle phase")));
    }

    @Test
    void usefulNativePromptContainsBoundedActualDiagnosticsAndNoConfiguration() {
        Properties user = properties(true, true);
        user.setProperty("m3.smart.modelPath", "/private/CONFIGURATION_SHOULD_NOT_BE_PROMPTED.gguf");
        AtomicReference<String> seen = new AtomicReference<>();
        List<String> lines = new ArrayList<>();
        M3BuildSuggestions.afterBuild(user, null, M3BuildSuggestionsTest::failed, lines::add, (u, s, prompt) -> {
            assertTrue(seen.compareAndSet(null, prompt));
            return "Check the missing symbol.\nReview the 🛠 test.";
        });
        assertNotNull(seen.get());
        assertTrue(seen.get().contains("cannot find symbol: ExampleType"));
        assertTrue(seen.get().contains("UNTRUSTED_MAVEN_DIAGNOSTICS_BEGIN"));
        assertTrue(seen.get().contains("category=PLUGIN"));
        assertFalse(seen.get().contains("CONFIGURATION_SHOULD_NOT_BE_PROMPTED"));
        assertEquals(
                2,
                lines.stream()
                        .filter(line -> line.startsWith("[M3][UNTRUSTED LLM]"))
                        .count());
    }

    @Test
    void diagnosticCaptureIsByteBoundedAndDoesNotSplitSupplementaryCharacters() {
        List<String> diagnostics = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            M3BuildSuggestions.capture(diagnostics, "🛠".repeat(2000) + "NOT_REACHED");
        }
        assertEquals(16, diagnostics.size());
        for (String diagnostic : diagnostics) {
            assertTrue(diagnostic.getBytes(StandardCharsets.UTF_8).length <= 1024);
            assertTrue(diagnostic.endsWith("[truncated]"));
            assertFalse(diagnostic.contains("NOT_REACHED"));
            assertFalse(diagnostic.contains("\ufffd"));
        }
        AtomicReference<String> prompt = new AtomicReference<>();
        M3BuildSuggestions.afterBuild(
                properties(true, true),
                null,
                () -> new M3BuildSuggestions.BuildSnapshot(true, 1, 20, Set.of(), diagnostics),
                line -> {},
                (u, s, text) -> {
                    prompt.set(text);
                    return "Review diagnostic.";
                });
        assertTrue(prompt.get().getBytes(StandardCharsets.UTF_8).length < 20_000);
    }

    @Test
    void diagnosticCaptureSanitizesInvalidUtf16AndTerminalControls() {
        List<String> diagnostics = new ArrayList<>();
        M3BuildSuggestions.capture(diagnostics, "A\ud800B\udc00C\u009bD\u001bE\0F");
        assertEquals(List.of("A\ufffdB\ufffdC D E F"), diagnostics);
        M3BuildSuggestions.capture(null, "unused");
    }

    @Test
    void unknownReferencesCannotInjectPromptInstructions() {
        AtomicReference<String> seen = new AtomicReference<>();
        M3BuildSuggestions.afterBuild(
                properties(true, true),
                null,
                () -> new M3BuildSuggestions.BuildSnapshot(
                        true, 1, 1, Set.of("https://attacker.invalid/REFERENCE_INJECTION"), List.of()),
                line -> {},
                (u, s, prompt) -> {
                    seen.set(prompt);
                    return "Review Maven's failure.";
                });
        assertTrue(seen.get().contains("category=BUILD"));
        assertFalse(seen.get().contains("REFERENCE_INJECTION"));
    }

    @Test
    void providerFailuresDoNotEscapeOrDiscloseMessages() {
        for (Throwable failure : List.of(
                new IOException("PRIVATE_PATH"),
                new IllegalStateException("PRIVATE_PATH"),
                new LinkageError("PRIVATE_PATH"))) {
            List<String> lines = new ArrayList<>();
            assertDoesNotThrow(() -> M3BuildSuggestions.afterBuild(
                    properties(true, true), null, M3BuildSuggestionsTest::failed, lines::add, (u, s, p) -> {
                        if (failure instanceof Exception exception) {
                            throw exception;
                        }
                        throw (LinkageError) failure;
                    }));
            assertTrue(lines.stream().anyMatch(line -> line.contains("result is unchanged")));
            assertFalse(lines.stream().anyMatch(line -> line.contains("PRIVATE_PATH")));
        }
    }

    @Test
    void throwingLoggersCannotEscapeIncludingFallbackAndInterruptedPaths() {
        assertDoesNotThrow(() -> M3BuildSuggestions.afterBuild(
                properties(true, true),
                null,
                M3BuildSuggestionsTest::failed,
                line -> {
                    throw new IllegalStateException("broken sink");
                },
                (u, s, p) -> {
                    throw new AssertionError("logging failed before provider");
                }));
        assertDoesNotThrow(() -> M3BuildSuggestions.afterBuild(
                properties(true, true),
                null,
                () -> {
                    throw new IllegalStateException("broken snapshot");
                },
                line -> {
                    throw new IllegalStateException("broken sink");
                },
                (u, s, p) -> {
                    throw new AssertionError("snapshot failed before provider");
                }));
        Thread.currentThread().interrupt();
        try {
            assertDoesNotThrow(() -> M3BuildSuggestions.afterBuild(
                    properties(true, true),
                    null,
                    () -> {
                        throw new AssertionError("interrupted snapshot");
                    },
                    line -> {
                        throw new IllegalStateException("broken sink");
                    },
                    (u, s, p) -> {
                        throw new AssertionError("interrupted provider");
                    }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void providerInterruptionIsPreserved() {
        try {
            M3BuildSuggestions.afterBuild(
                    properties(true, true), null, M3BuildSuggestionsTest::failed, line -> {}, (u, s, p) -> {
                        throw new InterruptedException("canceled");
                    });
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void invalidNativeTextIsNotRendered() {
        for (String response : List.of(
                "",
                " \n",
                "x\0y",
                "x\ud800y",
                "x\udc00y",
                "x\u009by",
                "x\u001by",
                "x".repeat(32_769),
                "🛠".repeat(8193))) {
            List<String> lines = new ArrayList<>();
            M3BuildSuggestions.afterBuild(
                    properties(true, true), null, M3BuildSuggestionsTest::failed, lines::add, (u, s, p) -> response);
            assertTrue(lines.stream().anyMatch(line -> line.contains("unavailable")));
            assertFalse(lines.stream().anyMatch(line -> line.startsWith("[M3][UNTRUSTED LLM]")));
        }
    }

    @Test
    void realChildPipesAcceptExactUtf8AndTheByteBoundary() throws Exception {
        assertEquals("actual input", M3BuildSuggestions.runProcess(command("echo"), "actual input", 5));
        assertEquals("Review the 🛠 diagnostic.", M3BuildSuggestions.runProcess(command("unicode"), "input", 5));
        assertEquals(
                32_768,
                M3BuildSuggestions.runProcess(command("exact"), "input", 5).length());
    }

    @Test
    void realChildMalformedBlankControlAndNonzeroOutputsAreRefused() {
        for (String mode : List.of("malformed", "nul", "c1", "blank", "nonzero")) {
            Exception error =
                    assertThrows(Exception.class, () -> M3BuildSuggestions.runProcess(command(mode), "input", 5));
            assertFalse(error.toString().contains("PRIVATE_PROVIDER_STDERR"));
            assertFalse(error.toString().contains("UNACCEPTED_PROVIDER_STDOUT"));
        }
    }

    @Test
    void stdoutOverflowIsObservedEvenWhenStdinWriteIsBlocked() {
        assertTimeoutPreemptively(
                Duration.ofSeconds(6),
                () -> assertThrows(
                        IOException.class,
                        () -> M3BuildSuggestions.runProcess(command("overflow-before-input"), "x".repeat(65_536), 5)));
    }

    @Test
    void stalledStdinDeadlineRetiresTheActualChild() throws Exception {
        Path pids = temporary.resolve("stalled.pid");
        assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> assertThrows(
                        IOException.class,
                        () -> M3BuildSuggestions.runProcess(command("stall", pids.toString()), "x".repeat(65_536), 1)));
        assertDead(pids);
    }

    @Test
    void deadlineRetiresDescendantHoldingInheritedPipes() throws Exception {
        Path pids = temporary.resolve("descendants.pid");
        assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> assertThrows(
                        IOException.class,
                        () -> M3BuildSuggestions.runProcess(
                                command("descendant", pids.toString()), "x".repeat(65_536), 1)));
        assertEquals(2, Files.readAllLines(pids).size());
        assertDead(pids);
    }

    @Test
    void callerCancellationIsResponsiveAndPreservesInterrupt() throws Exception {
        Path pids = temporary.resolve("canceled.pid");
        AtomicBoolean preserved = new AtomicBoolean();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                M3BuildSuggestions.afterBuild(
                        properties(true, true),
                        null,
                        M3BuildSuggestionsTest::failed,
                        line -> {},
                        (u, s, p) -> M3BuildSuggestions.runProcess(
                                command("descendant", pids.toString()), "x".repeat(65_536), 120));
                preserved.set(Thread.currentThread().isInterrupted());
            } catch (Throwable failure) {
                unexpected.set(failure);
            }
        });
        caller.start();
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(pids) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(Files.exists(pids), "scripted child started");
            caller.interrupt();
            caller.join(5000);
            assertFalse(caller.isAlive(), "advisory cancellation returned");
            assertEquals(null, unexpected.get());
            assertTrue(preserved.get());
            assertDead(pids);
        } finally {
            caller.interrupt();
            caller.join(5000);
            retireRecorded(pids);
        }
    }

    @Test
    void malformedOrOversizedInputRefusesBeforeProcessStartup() {
        Path pids = temporary.resolve("must-not-start.pid");
        for (String input : List.of("x\ud800", "x\udc00", "x\0y", "x".repeat(65_537), "🛠".repeat(16_385))) {
            assertThrows(
                    IOException.class,
                    () -> M3BuildSuggestions.runProcess(command("stall", pids.toString()), input, 1));
            assertFalse(Files.exists(pids));
        }
    }

    @Test
    void preexistingCancellationRefusesBeforeChildStartup() {
        Path pids = temporary.resolve("interrupted.pid");
        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    InterruptedException.class,
                    () -> M3BuildSuggestions.runProcess(command("stall", pids.toString()), "input", 1));
            assertFalse(Files.exists(pids));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static Properties properties(boolean suggest, boolean llm) {
        Properties properties = new Properties();
        properties.setProperty("m3.smart.suggest", Boolean.toString(suggest));
        properties.setProperty("optionllm", Boolean.toString(llm));
        return properties;
    }

    private static M3BuildSuggestions.BuildSnapshot failed() {
        return new M3BuildSuggestions.BuildSnapshot(
                true,
                2,
                1,
                Set.of(HELP + "MojoFailureException"),
                List.of("Compilation failed: cannot find symbol: ExampleType"));
    }

    private static List<String> command(String... args) {
        Path java = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        // JUnit Console can load tests outside java.class.path; use the actual fixture location.
        String fixturePath;
        try {
            fixturePath = Path.of(M3BuildSuggestionsProcessFixture.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toString();
        } catch (Exception invalid) {
            throw new IllegalStateException(invalid);
        }
        ArrayList<String> command = new ArrayList<>(
                List.of(java.toString(), "-cp", fixturePath, M3BuildSuggestionsProcessFixture.class.getName()));
        command.addAll(List.of(args));
        return command;
    }

    private static void assertDead(Path file) throws Exception {
        assertTrue(Files.exists(file), "fixture PID receipt exists");
        try {
            for (String line : Files.readAllLines(file)) {
                long pid = Long.parseLong(line);
                assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "retired PID " + pid);
            }
        } finally {
            retireRecorded(file);
        }
    }

    private static void retireRecorded(Path file) throws Exception {
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file)) {
                ProcessHandle.of(Long.parseLong(line)).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    private static final class GuardedProperties extends Properties {
        private static final long serialVersionUID = 1L;
        private final java.util.HashSet<String> allowed;

        private GuardedProperties(Set<String> allowed) {
            this.allowed = new java.util.HashSet<>(allowed);
        }

        @Override
        public String getProperty(String key) {
            if (!allowed.contains(key)) {
                fail("unexpected configuration read: " + key);
            }
            return super.getProperty(key);
        }
    }
}
