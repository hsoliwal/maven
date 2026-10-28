/*
 * SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.apache.maven;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Nested
    @EnabledIfSystemProperty(named = "m3.watch.contract", matches = "true")
    @Timeout(15)
    class WithInstalledSdk {
        @BeforeEach
        void requiresTheActualNamedModule() {
            assertTrue(
                    ModuleLayer.boot().findModule("com.m3.file").isPresent(),
                    "Explicit integration requires --add-modules com.m3.file; an absent SDK is not a pass");
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
        Module module = ModuleLayer.boot().findModule("com.m3.file").orElseThrow();
        Class<?> ledger = Class.forName(module, "com.m3.file.M3FileDeltaLedger");
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
