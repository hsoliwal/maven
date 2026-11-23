/*
 * SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.apache.maven;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.model.Build;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Request/delegation contracts and opt-in integration with the real SDK watcher.
 * Callback fixtures do not qualify compiler Mojo execution or class-output parity.
 */
@SuppressWarnings("deprecation")
class M3ProjectWatchTest {
    @TempDir
    Path root;

    @Test
    void userOptOutOverridesSystemOptIn() {
        var request = new DefaultMavenExecutionRequest();
        assertFalse(M3ProjectWatch.enabled(request));
        request.getSystemProperties().setProperty("m3.watch", "TRUE");
        assertTrue(M3ProjectWatch.enabled(request));
        request.getUserProperties().setProperty("m3.watch", "false");
        assertFalse(M3ProjectWatch.enabled(request));
    }

    @Test
    void invalidPolicyRefusesBeforeDelegationOrSdkLoading() throws IOException {
        for (var invalid : Map.of(
                        "m3.watch.mode",
                        "invalid",
                        "m3.watch.fullPercent",
                        "NaN",
                        "m3.watch.fullFileCount",
                        "-2",
                        "m3.watch.timestampChanges",
                        "yes",
                        "m3.watch.maxBuilds",
                        "-1")
                .entrySet()) {
            var request = request(1);
            request.getUserProperties().setProperty(invalid.getKey(), invalid.getValue());
            var calls = new AtomicInteger();
            var result = execute(request, next -> {
                calls.incrementAndGet();
                return successful("target");
            });
            assertEquals(0, calls.get());
            assertTrue(result.hasExceptions());
            assertTrue(result.getExceptions().get(0) instanceof IllegalArgumentException);
        }
        var request = request(1);
        request.setGoals(List.of());
        var result = execute(request, next -> {
            throw new AssertionError("no goals must refuse");
        });
        assertTrue(result.getExceptions().toString().contains("explicit goals"));
    }

    @Test
    void invalidBatchingPolicyRefusesBeforeDelegationOrSdkLoading() throws IOException {
        for (var properties : List.of(
                Map.of("m3.watch.quietMillis", "-1"),
                Map.of("m3.watch.quietMillis", "60001"),
                Map.of("m3.watch.quietMillis", "invalid"),
                Map.of("m3.watch.maxBatchMillis", "-1"),
                Map.of("m3.watch.maxBatchMillis", "60001"),
                Map.of("m3.watch.maxBatchMillis", "invalid"),
                Map.of("m3.watch.quietMillis", "2", "m3.watch.maxBatchMillis", "1"),
                Map.of("m3.watch.quietMillis", "1", "m3.watch.maxBatchMillis", "0"))) {
            var original = request(1);
            properties.forEach(original.getUserProperties()::setProperty);
            var result = execute(original, next -> {
                throw new AssertionError("invalid batching policy must refuse before delegation");
            });
            assertTrue(result.hasExceptions(), properties.toString());
            assertTrue(result.getExceptions().get(0) instanceof IllegalArgumentException);
        }
    }

    @Test
    void defaultBatchingIsImmediateAndMaximumIsOnlyActiveWithQuietTime() throws Exception {
        Class<?> type = Class.forName("org.apache.maven.M3ProjectWatch$Options");
        var from = type.getDeclaredMethod("from", MavenExecutionRequest.class);
        var quiet = type.getDeclaredMethod("quietMillis");
        var maximum = type.getDeclaredMethod("maxBatchMillis");
        from.setAccessible(true);
        quiet.setAccessible(true);
        maximum.setAccessible(true);
        var original = request(1);
        Object options = from.invoke(null, original);
        assertEquals(0, quiet.invoke(options));
        assertEquals(1000, maximum.invoke(options));
        original.getUserProperties().setProperty("m3.watch.maxBatchMillis", "0");
        options = from.invoke(null, original);
        assertEquals(0, quiet.invoke(options));
        assertEquals(0, maximum.invoke(options));
    }

    @Test
    void continuousChangesResetQuietTimeWithoutMovingMaximumEvenAcrossNanoTimeWrap() throws Exception {
        Class<?> type = Class.forName("org.apache.maven.M3ProjectWatch$BatchWindow");
        var constructor = type.getDeclaredConstructor(long.class, int.class, int.class);
        var remaining = type.getDeclaredMethod("remaining", long.class);
        var changed = type.getDeclaredMethod("changed", long.class);
        constructor.setAccessible(true);
        remaining.setAccessible(true);
        changed.setAccessible(true);
        long millis = Duration.ofMillis(1).toNanos();
        for (long start : new long[] {0, Long.MAX_VALUE - 50 * millis}) {
            Object window = constructor.newInstance(start, 100, 250);
            assertEquals(100 * millis, remaining.invoke(window, start));
            changed.invoke(window, start + 90 * millis);
            assertEquals(100 * millis, remaining.invoke(window, start + 90 * millis));
            changed.invoke(window, start + 180 * millis);
            assertEquals(70 * millis, remaining.invoke(window, start + 180 * millis));
            changed.invoke(window, start + 240 * millis);
            assertEquals(10 * millis, remaining.invoke(window, start + 240 * millis));
            assertEquals(0L, remaining.invoke(window, start + 250 * millis));
            assertEquals(-millis, remaining.invoke(window, start + 251 * millis));
            window = constructor.newInstance(start, 100, 100);
            changed.invoke(window, start + 90 * millis);
            assertEquals(10 * millis, remaining.invoke(window, start + 90 * millis));
            assertEquals(0L, remaining.invoke(window, start + 100 * millis));
            window = constructor.newInstance(start, 100, 250);
            assertEquals(0L, remaining.invoke(window, start + 100 * millis));
        }
    }

    @Test
    void unknownModulesCannotImpersonateTheM3FileOwner() {
        assertThrows(IllegalStateException.class,
                () -> M3ProjectWatch.sdkPackage(M3ProjectWatchTest.class.getModule()));
    }

    @Test
    void moduleDbIsAlwaysExcludedEvenWithCustomIgnoredNames() throws Exception {
        var original = request(1);
        original.getUserProperties().setProperty("m3.watch.ignoredDirectoryNames", "");
        Class<?> type = Class.forName("org.apache.maven.M3ProjectWatch$Options");
        var from = type.getDeclaredMethod("from", MavenExecutionRequest.class);
        var excludes = type.getDeclaredMethod("excludes", Path.class);
        from.setAccessible(true);
        excludes.setAccessible(true);
        Object options = from.invoke(null, original);
        assertTrue((Boolean) excludes.invoke(options, root.resolve(".m3/module.m3db")));
        assertTrue((Boolean) excludes.invoke(options, root.resolve("child/.m3/module.m3db")));
        assertTrue((Boolean) excludes.invoke(options, root.resolve(".m3-watch/state.bin")));
        assertFalse((Boolean) excludes.invoke(options, root.resolve("src/Watched.java")));
    }

    @Test
    void boundedWatchCannotHideAnUncommittedSuccessfulGeneration() throws Exception {
        assertThrows(IOException.class, () ->
                M3ProjectWatch.requireCheckpointOnBoundedExit(1, 1, false, false));
        M3ProjectWatch.requireCheckpointOnBoundedExit(1, 1, false, true);
        M3ProjectWatch.requireCheckpointOnBoundedExit(1, 1, true, false);
        M3ProjectWatch.requireCheckpointOnBoundedExit(1, 2, false, false);
        M3ProjectWatch.requireCheckpointOnBoundedExit(1, 0, false, false);
    }

    @Nested
    @EnabledIfSystemProperty(named = "m3.watch.contract", matches = "true")
    @Timeout(15)
    class WithInstalledSdk {
        @BeforeEach
        void requiresTheActualNamedModule() {
            Module module = M3ProjectWatch.sdkModule(ModuleLayer.boot());
            assertNotNull(Class.forName(module, M3ProjectWatch.sdkPackage(module) + ".M3FileWatcher"),
                    "Explicit integration requires the current or legacy M3File owner");
        }

        @Test
        void aTrailingOrRepeatedCleanGoalIsNormalizedBeforeTheLifecycle() throws Exception {
            var original = request(1);
            original.setGoals(List.of("verify", "clean", "test", "clean"));
            var observed = new ArrayList<List<String>>();
            var result = execute(original, next -> {
                observed.add(List.copyOf(next.getGoals()));
                return successful("target");
            });
            assertFalse(result.hasExceptions(), result.getExceptions().toString());
            assertEquals(List.of(List.of("clean", "verify", "test")), observed);
            assertEquals(List.of("verify", "clean", "test", "clean"), original.getGoals());
        }

        @Test
        void theInitialForcedBuildDoesNotWaitForTheConfiguredBatchWindow() throws Exception {
            var original = request(1);
            original.getUserProperties().setProperty("m3.watch.quietMillis", "60000");
            original.getUserProperties().setProperty("m3.watch.maxBatchMillis", "60000");
            var calls = new AtomicInteger();
            var result = execute(original, next -> {
                calls.incrementAndGet();
                return successful("target");
            });
            assertFalse(result.hasExceptions(), result.getExceptions().toString());
            assertEquals(1, calls.get());
            assertTrue(Files.isRegularFile(image()));
        }

        @Test
        void changesDuringOneBuildProduceOneLaterExecutionOfTheLatestGeneration() throws Exception {
            var original = request(2);
            original.getUserProperties().setProperty("m3.watch.quietMillis", "20");
            original.getUserProperties().setProperty("m3.watch.maxBatchMillis", "100");
            Path source = root.resolve("src/Watched.java");
            var calls = new AtomicInteger();
            var active = new AtomicInteger();
            var observed = new ArrayList<String>();
            var result = execute(original, next -> {
                assertEquals(1, active.incrementAndGet(), "watched lifecycle executions must remain serial");
                try {
                    assertEquals(List.of("clean", "verify"), next.getGoals());
                    observed.add(new String(bytes(source), java.nio.charset.StandardCharsets.UTF_8));
                    if (calls.incrementAndGet() == 1) {
                        write(source, "class Watched { int value = 2; }\n");
                        write(source, "class Watched { int value = 3; }\n");
                    }
                    return successful("target");
                } finally {
                    active.decrementAndGet();
                }
            });
            assertFalse(result.hasExceptions(), result.getExceptions().toString());
            assertEquals(2, calls.get());
            assertEquals(0, active.get());
            assertEquals("class Watched { int value = 3; }\n", observed.get(1));
            assertArrayEquals(
                    MessageDigest.getInstance("SHA-256").digest(bytes(source)),
                    persistedDigest(Path.of("src/Watched.java")));
        }

        @Test
        void batchingKeepsTheLatestOriginalSdkSnapshotAndRestartsOnlyItsQuietPeriod() throws Exception {
            var original = request(1);
            original.getUserProperties().setProperty("m3.watch.quietMillis", "100");
            original.getUserProperties().setProperty("m3.watch.maxBatchMillis", "250");
            Object startup = snapshot(null);
            write(root.resolve("src/Watched.java"), "class Watched { int value = 2; }\n");
            Object first = snapshot(startup);
            write(root.resolve("src/Watched.java"), "class Watched { int value = 3; }\n");
            Object second = snapshot(startup);
            write(root.resolve("src/Watched.java"), "class Watched { int value = 4; }\n");
            Object latest = snapshot(startup);
            var watcher = new ScriptedWatcher(new Offer(50, second), new Offer(80, latest), new Offer(100, null));
            assertSame(latest, batch(original, first, startup, watcher));
            assertEquals(
                    List.of(Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofMillis(100)), watcher.waits);
            assertEquals(3, watcher.next);
        }

        @Test
        void emptyPollsDoNotResetTheQuietPeriodAndDefaultPolicyDoesNotPoll() throws Exception {
            var original = request(1);
            Object first = snapshot(null);
            var immediate = new ScriptedWatcher();
            assertSame(first, batch(original, first, first, immediate));
            assertEquals(0, immediate.next);
            original.getUserProperties().setProperty("m3.watch.quietMillis", "100");
            original.getUserProperties().setProperty("m3.watch.maxBatchMillis", "250");
            var watcher = new ScriptedWatcher(new Offer(40, null), new Offer(60, null));
            assertSame(first, batch(original, first, first, watcher));
            assertEquals(List.of(Duration.ofMillis(100), Duration.ofMillis(60)), watcher.waits);
        }

        @Test
        void continuousOffersCannotExtendMaximumAndTheOfferReturnedAfterItsDeadlineIsRetained() throws Exception {
            var original = request(1);
            original.getUserProperties().setProperty("m3.watch.quietMillis", "100");
            original.getUserProperties().setProperty("m3.watch.maxBatchMillis", "250");
            Object startup = snapshot(null);
            write(root.resolve("src/Watched.java"), "class Watched { int value = 2; }\n");
            Object first = snapshot(startup);
            write(root.resolve("src/Watched.java"), "class Watched { int value = 3; }\n");
            Object second = snapshot(startup);
            write(root.resolve("src/Watched.java"), "class Watched { int value = 4; }\n");
            Object third = snapshot(startup);
            write(root.resolve("src/Watched.java"), "class Watched { int value = 5; }\n");
            Object latest = snapshot(startup);
            for (long start :
                    new long[] {0, Long.MAX_VALUE - Duration.ofMillis(50).toNanos()}) {
                var watcher = new ScriptedWatcher(new Offer(90, second), new Offer(90, third), new Offer(100, latest));
                watcher.now = start;
                assertSame(latest, batch(original, first, startup, watcher));
                assertEquals(
                        List.of(Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofMillis(70)), watcher.waits);
                assertEquals(3, watcher.next);
            }
        }

        @Test
        void anObservedStartupChangeRefusesBeforeAQueuedRestoredGenerationCanHideIt() throws Exception {
            var original = request(1);
            original.getUserProperties().setProperty("m3.watch.timestampChanges", "false");
            original.getUserProperties().setProperty("m3.watch.quietMillis", "100");
            original.getUserProperties().setProperty("m3.watch.maxBatchMillis", "250");
            Path startupFile = root.resolve(".mvn/maven.config");
            write(startupFile, "-Dfixture=first\n");
            Object startup = snapshot(null);
            write(root.resolve("src/Watched.java"), "class Watched { int value = 2; }\n");
            Object first = snapshot(startup);
            write(startupFile, "-Dfixture=other\n");
            Object changed = snapshot(startup);
            write(startupFile, "-Dfixture=first\n");
            Object restored = snapshot(startup);
            var watcher = new ScriptedWatcher(new Offer(50, changed), new Offer(50, restored));
            IOException failure = assertThrows(IOException.class, () -> batch(original, first, startup, watcher));
            assertTrue(failure.getMessage().contains("restart Maven"));
            assertEquals(1, watcher.next, "an observed startup change must refuse before reading the restored offer");
            assertFalse(Files.exists(image()));
        }

        @Test
        void aSuccessfulLookingInterruptedCallbackCannotPublishTheNewBaseline() throws Exception {
            var original = request(1);
            assertFalse(execute(original, next -> successful("target")).hasExceptions());
            byte[] previous = Files.readAllBytes(image());
            write(root.resolve("src/Watched.java"), "class Watched { int value = 4; }\n");
            MavenExecutionResult result;
            boolean interrupted;
            try {
                result = execute(original, next -> {
                    Thread.currentThread().interrupt();
                    return successful("target");
                });
                interrupted = Thread.currentThread().isInterrupted();
            } finally {
                Thread.interrupted(); // Do not leak the deliberate callback cancellation into other tests.
            }
            assertTrue(interrupted);
            assertTrue(result.hasExceptions());
            assertTrue(result.getExceptions().get(0) instanceof InterruptedException);
            assertArrayEquals(previous, Files.readAllBytes(image()));
        }

        @Test
        void boundedWatcherRejectsSuccessForInputsChangedDuringItsOnlyBuild() throws Exception {
            var original = request(1);
            Path watched = root.resolve("src/Watched.java");
            var result = execute(original, next -> {
                write(watched, "class Watched { int value = 9; }\\n");
                return successful("target");
            });
            assertTrue(result.hasExceptions(), "uncommitted bounded generation must fail");
            assertTrue(result.getExceptions().toString().contains("not committed"));
            assertFalse(Files.exists(image()), "no successful checkpoint may be persisted");
        }

        @Test
        void sameTimeMutationRemainsPendingAndEachDelegationGetsAFreshRequest() throws Exception {
            var original = request(2);
            Files.createDirectories(root.resolve(".mvn"));
            Files.writeString(root.resolve(".mvn/maven.config"), "-Dstartup=unchanged\n");
            original.getUserProperties().setProperty("fixture", "original");
            original.getUserProperties().setProperty("m3.watch.mode", "INCREMENTAL");
            original.getProjectActivation().activateOptionalProjectNonRecursive(":leaf");
            Path source = root.resolve("src/Watched.java");
            FileTime timestamp = Files.getLastModifiedTime(source);
            long size = Files.size(source);
            var seen = new ArrayList<MavenExecutionRequest>();
            var result = execute(original, request -> {
                assertEquals("original", request.getUserProperties().getProperty("fixture"));
                assertEquals(List.of("clean", "verify"), request.getGoals()); // Uncertified graph: explicit fallback.
                assertEquals(
                        original.getProjectActivation().getActivations(),
                        request.getProjectActivation().getActivations());
                seen.add(request);
                request.getUserProperties().setProperty("fixture", "execution-only");
                if (seen.size() == 1) {
                    try {
                        Files.writeString(source, "class Watched { int value = 2; }\n");
                        Files.setLastModifiedTime(source, timestamp);
                        assertEquals(size, Files.size(source));
                        assertEquals(timestamp, Files.getLastModifiedTime(source));
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                }
                return successful("target");
            });
            assertFalse(result.hasExceptions(), result.getExceptions().toString());
            assertEquals(2, seen.size());
            assertNotSame(original, seen.get(0));
            assertNotSame(seen.get(0), seen.get(1));
            assertNotSame(seen.get(0).getRepositoryCache(), seen.get(1).getRepositoryCache());
            assertNotSame(original.getRepositoryCache(), seen.get(0).getRepositoryCache());
            assertEquals("original", original.getUserProperties().getProperty("fixture"));
            assertEquals(List.of("verify"), original.getGoals());
            assertArrayEquals(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)),
                    persistedDigest(Path.of("src/Watched.java")));
        }

        @Test
        void failedDelegationDoesNotAcknowledgeBytesAndRestartRetriesThem() throws Exception {
            var original = request(1);
            assertFalse(execute(original, next -> successful("target")).hasExceptions());
            byte[] previous = Files.readAllBytes(image());
            Files.writeString(root.resolve("src/Watched.java"), "class Watched { int value = 3; }\n");
            var failure = execute(
                    original,
                    next -> successful("target")
                            .addException(new IllegalStateException("deliberate delegate failure")));
            assertTrue(failure.hasExceptions());
            assertArrayEquals(previous, Files.readAllBytes(image()));
            var calls = new AtomicInteger();
            var retried = execute(original, next -> {
                calls.incrementAndGet();
                assertArrayEquals(previous, bytes(image()));
                return successful("target");
            });
            assertFalse(retried.hasExceptions(), retried.getExceptions().toString());
            assertEquals(1, calls.get());
            assertArrayEquals(
                    MessageDigest.getInstance("SHA-256").digest(bytes(root.resolve("src/Watched.java"))),
                    persistedDigest(Path.of("src/Watched.java")));
        }

        @Test
        void startupConfigurationChangeRequiresRestartBeforeAnotherDelegation() throws Exception {
            var original = request(2);
            Files.createDirectories(root.resolve(".mvn"));
            Files.writeString(root.resolve(".mvn/maven.config"), "-Dfixture=first\n");
            var calls = new AtomicInteger();
            var result = execute(original, next -> {
                calls.incrementAndGet();
                write(root.resolve(".mvn/maven.config"), "-Dfixture=second\n");
                return successful("target");
            });
            assertEquals(1, calls.get());
            assertTrue(result.hasExceptions());
            assertTrue(result.getExceptions().toString().contains("restart Maven"));
            assertFalse(Files.exists(image()));
        }

        @Test
        void customOutputRequiresAnExplicitExclusion() throws Exception {
            var original = request(1);
            var refused = execute(original, next -> {
                write(root.resolve("custom/generated.txt"), "generated\n");
                return successful("custom");
            });
            assertTrue(refused.hasExceptions());
            assertTrue(refused.getExceptions().toString().contains("m3.watch.excludes"));
            assertFalse(Files.exists(image()));
            original.getUserProperties().setProperty("m3.watch.excludes", "custom");
            var admitted = execute(original, next -> successful("custom"));
            assertFalse(admitted.hasExceptions(), admitted.getExceptions().toString());
            assertTrue(Files.isRegularFile(image()));
            assertFalse(persistedPaths().stream().anyMatch(path -> path.startsWith("custom/")));
        }

        @Test
        void moduleDbWritesCannotScheduleAnotherWatchedLifecycle() throws Exception {
            var original = request(2);
            original.getUserProperties().setProperty("m3.watch.idleSeconds", "1");
            original.getUserProperties().setProperty("m3.watch.ignoredDirectoryNames", "");
            original.getUserProperties().setProperty("m3.watch.excludes", "target");
            var calls = new AtomicInteger();
            var result = execute(original, next -> {
                calls.incrementAndGet();
                write(root.resolve("child/.m3/module.m3db"), "published receipt\\n");
                write(root.resolve(".m3/module.m3db.state"), "clean\\n");
                return successful("target");
            });
            assertFalse(result.hasExceptions(), result.getExceptions().toString());
            assertEquals(1, calls.get(), "M3DB state is not a watched source generation");
            assertFalse(persistedPaths().stream().anyMatch(path ->
                    path.startsWith(".m3/") || path.contains("/.m3/")));
        }

        @Test
        void outputAndLocalRepositoryEventsDoNotScheduleExtraLifecycleCalls() throws Exception {
            var original = request(2);
            original.getUserProperties().setProperty("m3.watch.idleSeconds", "1");
            var calls = new AtomicInteger();
            var result = execute(original, next -> {
                calls.incrementAndGet();
                write(root.resolve("module/target/generated.txt"), "generated\n");
                write(root.resolve("repository/group/artifact.jar"), "repository output\n");
                return successful("target");
            });
            assertFalse(result.hasExceptions(), result.getExceptions().toString());
            assertEquals(1, calls.get());
            assertFalse(persistedPaths().stream()
                    .anyMatch(path -> path.contains("/target/")
                            || path.startsWith("repository/")
                            || path.startsWith(".m3-watch/")));
        }

        @Test
        void rejectsSourcesOutsideTheWatchedRoot() throws Exception {
            var original = request(1);
            var result = execute(original, next -> {
                var built = successful("target");
                built.getProject()
                        .addCompileSourceRoot(
                                root.getParent().resolve("outside").toString());
                return built;
            });
            assertTrue(result.hasExceptions());
            assertTrue(result.getExceptions().toString().contains("outside its root"));
            assertFalse(Files.exists(image()));
        }

        @Test
        void preservesModelPolicyButClearsThePreviousRepositorySession() throws Exception {
            var original = request(1);
            var configured = new DefaultProjectBuildingRequest();
            var previousSession = new DefaultRepositorySystemSession(handle -> false);
            configured.setRepositorySession(previousSession);
            configured.setValidationLevel(20);
            configured.setResolveDependencies(true);
            original.setProjectBuildingConfiguration(configured);
            var result = execute(original, next -> {
                assertEquals(20, next.getProjectBuildingRequest().getValidationLevel());
                assertTrue(next.getProjectBuildingRequest().isResolveDependencies());
                assertNull(next.getProjectBuildingRequest().getRepositorySession());
                return successful("target");
            });
            assertFalse(result.hasExceptions(), result.getExceptions().toString());
            assertSame(previousSession, configured.getRepositorySession());
            configured.setProject(new MavenProject());
            var refused = execute(original, next -> {
                throw new AssertionError("preset project must refuse");
            });
            assertTrue(refused.hasExceptions());
        }
    }

    private DefaultMavenExecutionRequest request(int maximum) throws IOException {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        Files.writeString(root.resolve("src/Watched.java"), "class Watched { int value = 1; }\n");
        var request = new DefaultMavenExecutionRequest();
        request.setRootDirectory(root);
        request.setTopDirectory(root);
        request.setBaseDirectory(root.toFile());
        request.setGoals(List.of("verify"));
        request.setLocalRepositoryPath(root.resolve("repository").toFile());
        request.getUserProperties().setProperty("m3.watch", "true");
        request.getUserProperties().setProperty("m3.watch.maxBuilds", Integer.toString(maximum));
        request.getUserProperties().setProperty("m3.watch.idleSeconds", "3");
        request.getUserProperties().setProperty("m3.watch.reconcileSeconds", "1");
        return request;
    }

    private MavenExecutionResult successful(String outputDirectory) {
        var project = new MavenProject();
        var build = new Build();
        Path output = root.resolve(outputDirectory);
        build.setDirectory(output.toString());
        build.setOutputDirectory(output.resolve("classes").toString());
        build.setTestOutputDirectory(output.resolve("test-classes").toString());
        project.setBuild(build);
        project.setFile(root.resolve("pom.xml").toFile());
        project.addCompileSourceRoot(root.resolve("src").toString());
        return new DefaultMavenExecutionResult().setProject(project).setTopologicallySortedProjects(List.of(project));
    }

    private static MavenExecutionResult execute(
            MavenExecutionRequest request,
            java.util.function.Function<MavenExecutionRequest, MavenExecutionResult> delegate) {
        return M3ProjectWatch.execute(request, delegate, LoggerFactory.getLogger(M3ProjectWatchTest.class));
    }

    private Path image() {
        return root.resolve(".m3-watch/state.bin");
    }

    private Object persistedSnapshot() throws Exception {
        Module module = M3ProjectWatch.sdkModule(ModuleLayer.boot());
        Class<?> ledger = Class.forName(module, M3ProjectWatch.sdkPackage(module) + ".M3FileDeltaLedger");
        assertNotNull(ledger);
        return ledger.getMethod("load", Path.class, Path.class).invoke(null, image(), root);
    }

    private byte[] persistedDigest(Path path) throws Exception {
        Object snapshot = persistedSnapshot();
        int slot =
                (int) snapshot.getClass().getMethod("physicalSlot", Path.class).invoke(snapshot, path);
        assertTrue(slot >= 0);
        return (byte[]) snapshot.getClass().getMethod("digestAt", int.class).invoke(snapshot, slot);
    }

    private List<String> persistedPaths() throws Exception {
        Object snapshot = persistedSnapshot();
        var paths = new ArrayList<String>();
        int slots = (int) snapshot.getClass().getMethod("slots").invoke(snapshot);
        for (int slot = 0; slot < slots; slot++) {
            paths.add(
                    (String) snapshot.getClass().getMethod("pathAt", int.class).invoke(snapshot, slot));
        }
        return paths;
    }

    private Object snapshot(Object previous) throws Exception {
        Module module = M3ProjectWatch.sdkModule(ModuleLayer.boot());
        Class<?> ledger = Class.forName(module, M3ProjectWatch.sdkPackage(module) + ".M3FileDeltaLedger");
        Class<?> snapshot = Class.forName(module, M3ProjectWatch.sdkPackage(module) + ".M3FileDeltaLedger$Snapshot");
        assertNotNull(ledger);
        assertNotNull(snapshot);
        return ledger.getMethod("scan", Path.class, snapshot, Collection.class, boolean.class)
                .invoke(
                        null,
                        root,
                        previous,
                        List.of(Path.of("pom.xml"), Path.of("src/Watched.java"), Path.of(".mvn/maven.config")),
                        false);
    }

    private static Object batch(MavenExecutionRequest request, Object first, Object startup, ScriptedWatcher watcher)
            throws Exception {
        Class<?> optionsType = Class.forName("org.apache.maven.M3ProjectWatch$Options");
        var from = optionsType.getDeclaredMethod("from", MavenExecutionRequest.class);
        from.setAccessible(true);
        Object options = from.invoke(null, request);
        Class<?> watchType = Class.forName("org.apache.maven.M3ProjectWatch$SharedWatch");
        var constructor = watchType.getDeclaredConstructor(Object.class, Class.class, Module.class);
        constructor.setAccessible(true);
        Object shared = constructor.newInstance(
                watcher,
                ScriptedWatcher.class,
                M3ProjectWatch.sdkModule(ModuleLayer.boot()));
        var method = M3ProjectWatch.class.getDeclaredMethod(
                "awaitBatch", watchType, Object.class, Object.class, optionsType, LongSupplier.class);
        method.setAccessible(true);
        try {
            return method.invoke(null, shared, first, startup, options, (LongSupplier) () -> watcher.now);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError(cause);
        }
    }

    private record Offer(long elapsedMillis, Object snapshot) {}

    /** Scripted timing only; every snapshot and startup comparison still uses the original SDK. */
    public static final class ScriptedWatcher {
        private final List<Offer> offers;
        private final List<Duration> waits = new ArrayList<>();
        private int next;
        private long now;

        private ScriptedWatcher(Offer... offers) {
            this.offers = List.of(offers);
        }

        public Optional<?> awaitChange(Duration wait) {
            assertTrue(next < offers.size(), "unexpected poll after the scripted batch deadline");
            waits.add(wait);
            Offer offer = offers.get(next++);
            now += Duration.ofMillis(offer.elapsedMillis).toNanos();
            return Optional.ofNullable(offer.snapshot);
        }
    }

    private static void write(Path path, String value) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, value);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static byte[] bytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
