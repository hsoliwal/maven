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
package org.apache.maven.plugin.internal;

// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.apache.maven.api.SessionData;
import org.apache.maven.api.services.M3PluginEvidenceService;
import org.apache.maven.api.services.M3PluginEvidenceService.Receipt;
import org.apache.maven.api.services.ResourceObservation;
import org.apache.maven.api.services.ResourceObservation.Area;
import org.apache.maven.api.services.ResourceObservation.FileState;
import org.apache.maven.api.services.ResourceObservation.Kind;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.MojoExecutionEvent;
import org.apache.maven.internal.impl.InternalMavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Listener unit tests with actual filesystem observations. The public-getter Mojo
 * below is a test double, not a Resources implementation or a claim of plugin parity.
 * Original Resources goals are qualified separately in a source-built Maven runtime.
 */
class M3ResourcesExecutionListenerTest {
    @TempDir
    Path directory;

    private final M3ResourcesExecutionListener listener = new M3ResourcesExecutionListener();

    @Test
    void disabledByDefaultDoesNotInspectTheConfiguredMojoOrWriteEvidence() throws Exception {
        Fixture fixture = fixture("disabled", "resources", new SessionFixture(false));
        listener.beforeMojoExecution(fixture.event());
        listener.afterMojoExecutionSuccess(fixture.event());

        assertEquals(0, fixture.mojo.getterCalls.get());
        assertTrue(fixture.session.receipts.isEmpty());
        assertFalse(Files.exists(fixture.evidenceDirectory()));
    }

    @Test
    void userFalseOverridesProjectAndSystemOptIn() throws Exception {
        Fixture fixture = fixture("precedence", "resources", new SessionFixture(false));
        fixture.session.user.setProperty("m3.resources.evidence", "false");
        fixture.session.system.setProperty("m3.resources.evidence", "true");
        fixture.project.getProperties().setProperty("m3.resources.evidence", "true");

        listener.beforeMojoExecution(fixture.event());
        listener.afterMojoExecutionSuccess(fixture.event());

        assertEquals(0, fixture.mojo.getterCalls.get());
        assertTrue(fixture.session.receipts.isEmpty());
    }

    @Test
    void unrelatedPluginAndUnrecognizedResourcesGoalAreIgnored() throws Exception {
        Fixture fixture = fixture("unrelated", "resources", new SessionFixture(true));
        MojoExecutionEvent unrelated = fixture.event(execution("maven-compiler-plugin", "compile", "compile"));
        listener.beforeMojoExecution(unrelated);
        listener.afterMojoExecutionSuccess(unrelated);
        MojoExecutionEvent help = fixture.event(execution("maven-resources-plugin", "help", "help"));
        listener.beforeMojoExecution(help);
        listener.afterMojoExecutionSuccess(help);

        assertEquals(0, fixture.mojo.getterCalls.get());
        assertTrue(fixture.session.receipts.isEmpty());
    }

    @Test
    void successfulCallbacksPublishMeasuredBeforeAndAfterWithExplicitLimitations() throws Exception {
        Fixture fixture = fixture("measured", "resources", new SessionFixture(true));
        Path source = Files.writeString(fixture.input.resolve("message.txt"), "original bytes");
        Path target = fixture.output.resolve("message.txt");
        Path unrelatedClass = Files.writeString(fixture.output.resolve("Unrelated.class"), "other producer");

        listener.beforeMojoExecution(fixture.event());
        Files.copy(source, target);
        listener.afterMojoExecutionSuccess(fixture.event());

        Receipt receipt = onlyReceipt(fixture);
        ResourceObservation observation = read(receipt);
        assertEquals("PARTIAL", observation.completeness());
        assertEquals(ResourceObservation.SCHEMA, receipt.schema());
        assertEquals(M3PluginEvidenceService.RESOURCES, receipt.capabilities());
        assertEquals(
                "org.apache.maven.plugins:maven-resources-plugin",
                receipt.identity().pluginGa());
        assertEquals("3.3.1", receipt.identity().version());
        assertEquals("resources", receipt.identity().goal());
        assertEquals(receipt.identity().contextSha256(), observation.configurationSha256());
        assertEquals(sha(receipt.file()), receipt.sha256());
        assertEquals(
                sha(source), state(observation.before(), Area.INPUT, source).sha256());
        assertEquals(
                sha(target), state(observation.after(), Area.OUTPUT, target).sha256());
        assertEquals(
                sha(unrelatedClass),
                state(observation.after(), Area.OUTPUT, unrelatedClass).sha256());
        assertTrue(observation
                .limitations()
                .containsAll(List.of(
                        "SOURCE_SELECTION_UNPROVEN",
                        "INPUT_CLOSURE_UNPROVEN",
                        "OUTPUT_OWNERSHIP_UNPROVEN",
                        "FILESYSTEM_NOT_TRANSACTIONAL")));
        assertEquals("other producer", Files.readString(unrelatedClass));
    }

    @Test
    void skipAndTestSkipAmbiguityRemainVisibleWithoutInventingExecution() throws Exception {
        Fixture skipped = fixture("skipped", "resources", new SessionFixture(true));
        skipped.mojo.skip = true;
        listener.beforeMojoExecution(skipped.event());
        listener.afterMojoExecutionSuccess(skipped.event());
        assertTrue(read(onlyReceipt(skipped)).skipRequested());

        Fixture test = fixture("test-skip", "testResources", new SessionFixture(true));
        test.session.user.setProperty("maven.test.skip", "true");
        listener.beforeMojoExecution(test.event());
        listener.afterMojoExecutionSuccess(test.event());
        ResourceObservation observation = read(onlyReceipt(test));
        assertFalse(observation.skipRequested(), "the public inherited getter is the only observed skip value");
        assertTrue(observation.limitations().contains("TEST_SKIP_UNOBSERVED"));
        assertEquals("PARTIAL", observation.completeness());
    }

    @Test
    void failureClearsPendingObservationAndCannotLaterBecomeASuccessReceipt() throws Exception {
        Fixture fixture = fixture("failure", "resources", new SessionFixture(true));
        listener.beforeMojoExecution(fixture.event());
        Files.writeString(fixture.output.resolve("partial.txt"), "partial plugin output");
        MojoExecutionEvent failure = new MojoExecutionEvent(
                fixture.session.legacy,
                fixture.project,
                fixture.execution,
                fixture.mojo,
                new IOException("original goal failed"));

        assertDoesNotThrow(() -> listener.afterExecutionFailure(failure));
        listener.afterMojoExecutionSuccess(fixture.event());
        assertTrue(fixture.session.receipts.isEmpty());
        assertFalse(Files.exists(fixture.evidenceDirectory()));
        assertEquals("partial plugin output", Files.readString(fixture.output.resolve("partial.txt")));

        listener.beforeMojoExecution(fixture.event());
        listener.afterMojoExecutionSuccess(fixture.event());
        assertEquals(1, fixture.session.receipts.size(), "a later repaired execution may publish its own observation");
    }

    @Test
    void checkedGoalFailuresClearPendingThroughTheActualLifecycleEventPath() throws Exception {
        List<Exception> failures = List.of(
                new MojoExecutionException("checked execution failure"),
                new MojoFailureException("checked goal failure"));
        for (int index = 0; index < failures.size(); index++) {
            Fixture fixture = fixture("checked-failure-" + index, "resources", new SessionFixture(true));
            listener.beforeMojoExecution(fixture.event());
            ExecutionEvent failure = mock(ExecutionEvent.class);
            when(failure.getType()).thenReturn(ExecutionEvent.Type.MojoFailed);
            when(failure.getSession()).thenReturn(fixture.session.legacy);
            when(failure.getProject()).thenReturn(fixture.project);
            when(failure.getMojoExecution()).thenReturn(fixture.execution);
            when(failure.getException()).thenReturn(failures.get(index));

            listener.onEvent(failure);
            listener.afterMojoExecutionSuccess(fixture.event());
            assertTrue(fixture.session.receipts.isEmpty());

            listener.beforeMojoExecution(fixture.event());
            listener.afterMojoExecutionSuccess(fixture.event());
            assertEquals(1, fixture.session.receipts.size(), "the same execution identity can be retried after repair");
        }
    }

    @Test
    void changedSourceAndUnchangedWrongOutputAreRecordedAsObserved() throws Exception {
        Fixture fixture = fixture("stale", "resources", new SessionFixture(true));
        Path source = Files.writeString(fixture.input.resolve("message.txt"), "before");
        Path target = Files.writeString(fixture.output.resolve("message.txt"), "stale!");
        Files.setLastModifiedTime(source, FileTime.fromMillis(1_600_000_000_000L));
        Files.setLastModifiedTime(target, FileTime.fromMillis(1_700_000_000_000L));
        FileTime sourceTime = Files.getLastModifiedTime(source);
        FileTime targetTime = Files.getLastModifiedTime(target);

        listener.beforeMojoExecution(fixture.event());
        Files.writeString(source, "after!");
        Files.setLastModifiedTime(source, sourceTime);
        listener.afterMojoExecutionSuccess(fixture.event());

        ResourceObservation observation = read(onlyReceipt(fixture));
        assertNotEquals(
                state(observation.before(), Area.INPUT, source).sha256(),
                state(observation.after(), Area.INPUT, source).sha256());
        assertEquals(state(observation.before(), Area.OUTPUT, target), state(observation.after(), Area.OUTPUT, target));
        assertNotEquals(
                state(observation.after(), Area.INPUT, source).sha256(),
                state(observation.after(), Area.OUTPUT, target).sha256());
        assertEquals("stale!", Files.readString(target));
        assertEquals(targetTime, Files.getLastModifiedTime(target));
    }

    @Test
    void deletedSourcesDoNotCauseObserverToDeleteStaleOutputs() throws Exception {
        Fixture fixture = fixture("deleted", "resources", new SessionFixture(true));
        Path source = Files.writeString(fixture.input.resolve("old.txt"), "retained output");
        Path target = Files.copy(source, fixture.output.resolve("old.txt"));
        listener.beforeMojoExecution(fixture.event());
        Files.delete(source);
        listener.afterMojoExecutionSuccess(fixture.event());

        ResourceObservation observation = read(onlyReceipt(fixture));
        assertEquals(Kind.FILE, state(observation.before(), Area.INPUT, source).kind());
        assertTrue(observation.after().stream().noneMatch(file -> matches(file, Area.INPUT, source)));
        assertEquals("retained output", Files.readString(target));
        assertEquals(
                sha(target), state(observation.after(), Area.OUTPUT, target).sha256());
    }

    @Test
    void missingResourceRootAndNewlyCreatedOutputAreRepresented() throws Exception {
        Fixture fixture = fixture("missing", "copy-resources", new SessionFixture(true));
        Path missing = fixture.input.resolveSibling("absent");
        fixture.mojo.resources.get(0).setDirectory(missing.toString());
        Files.delete(fixture.output);

        listener.beforeMojoExecution(fixture.event());
        Files.createDirectories(fixture.output);
        listener.afterMojoExecutionSuccess(fixture.event());

        ResourceObservation observation = read(onlyReceipt(fixture));
        assertEquals(
                Kind.MISSING, state(observation.before(), Area.INPUT, missing).kind());
        assertEquals(
                Kind.MISSING,
                state(observation.before(), Area.OUTPUT, fixture.output).kind());
        assertEquals(
                Kind.DIRECTORY,
                state(observation.after(), Area.OUTPUT, fixture.output).kind());
        assertEquals("copy-resources", onlyReceipt(fixture).identity().goal());
    }

    @Test
    void declaredSelectionAndTargetPathArePreservedWithoutClaimingOutputOwnership() throws Exception {
        Fixture fixture = fixture("declarations", "resources", new SessionFixture(true));
        Resource resource = fixture.mojo.resources.get(0);
        resource.setFiltering(true);
        resource.setTargetPath("../other-output");
        resource.setIncludes(List.of("**/*.txt", "settings.properties"));
        resource.setExcludes(List.of("private/**"));
        Path excluded = fixture.input.resolve("private/value.txt");
        Files.createDirectories(excluded.getParent());
        Files.writeString(excluded, "observed but not asserted selected");

        listener.beforeMojoExecution(fixture.event());
        listener.afterMojoExecutionSuccess(fixture.event());

        ResourceObservation observation = read(onlyReceipt(fixture));
        assertEquals("../other-output", observation.resources().get(0).targetPath());
        assertTrue(observation.resources().get(0).filtering());
        assertEquals(resource.getIncludes(), observation.resources().get(0).includes());
        assertEquals(resource.getExcludes(), observation.resources().get(0).excludes());
        assertEquals(
                sha(excluded), state(observation.before(), Area.INPUT, excluded).sha256());
        assertTrue(observation.limitations().contains("SOURCE_SELECTION_UNPROVEN"));
        assertTrue(observation.limitations().contains("OUTPUT_OWNERSHIP_UNPROVEN"));
    }

    @Test
    void configurationDigestChangesWithoutPublishingPropertyValuesOrContents() throws Exception {
        Fixture fixture = fixture("private-context", "resources", new SessionFixture(true));
        String privateValue = "private-filter-value-not-to-publish";
        fixture.session.user.setProperty("filter.secret", privateValue);
        Files.writeString(fixture.input.resolve("message.txt"), privateValue);
        listener.beforeMojoExecution(fixture.event());
        listener.afterMojoExecutionSuccess(fixture.event());
        Receipt first = onlyReceipt(fixture);

        fixture.session.user.setProperty("filter.secret", privateValue + "-changed");
        listener.beforeMojoExecution(fixture.event());
        listener.afterMojoExecutionSuccess(fixture.event());
        Receipt second = fixture.session.receipts.get(1);

        assertNotEquals(first.identity().contextSha256(), second.identity().contextSha256());
        assertFalse(new String(Files.readAllBytes(first.file()), java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains(privateValue));
        assertFalse(new String(Files.readAllBytes(second.file()), java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains(privateValue));
    }

    @Test
    void observerPreservesActualPosixPermissions() throws Exception {
        Fixture fixture = fixture("permissions", "resources", new SessionFixture(true));
        Path file = Files.writeString(fixture.output.resolve("script.sh"), "#!/bin/sh\n");
        assumeTrue(Files.getFileStore(file).supportsFileAttributeView(PosixFileAttributeView.class));
        var mode = PosixFilePermissions.fromString("rwxr-x---");
        Files.setPosixFilePermissions(file, mode);
        FileTime time = Files.getLastModifiedTime(file);

        listener.beforeMojoExecution(fixture.event());
        listener.afterMojoExecutionSuccess(fixture.event());

        ResourceObservation observation = read(onlyReceipt(fixture));
        assertEquals("rwxr-x---", state(observation.after(), Area.OUTPUT, file).permissions());
        assertEquals(mode, Files.getPosixFilePermissions(file));
        assertEquals(time, Files.getLastModifiedTime(file));
    }

    @Test
    void symbolicSourceAndSymbolicAncestorRefusePublicationWithoutFollowingThem() throws Exception {
        Fixture fixture = fixture("symlink", "resources", new SessionFixture(true));
        Path outside = Files.writeString(directory.resolve("outside.txt"), "outside");
        createSymbolicLink(fixture.input.resolve("link.txt"), outside);

        assertDoesNotThrow(() -> listener.beforeMojoExecution(fixture.event()));
        assertDoesNotThrow(() -> listener.afterMojoExecutionSuccess(fixture.event()));
        assertTrue(fixture.session.receipts.isEmpty());
        assertEquals("outside", Files.readString(outside));

        Fixture ancestor = fixture("symlink-ancestor", "resources", new SessionFixture(true));
        Path link = ancestor.input.resolveSibling("linked-root");
        createSymbolicLink(link, ancestor.input);
        ancestor.mojo
                .resources
                .get(0)
                .setDirectory(link.resolve("missing-child").toString());
        listener.beforeMojoExecution(ancestor.event());
        listener.afterMojoExecutionSuccess(ancestor.event());
        assertTrue(ancestor.session.receipts.isEmpty());
    }

    @Test
    void normalizationCannotEraseASymbolicAncestor() throws Exception {
        Fixture fixture = fixture("symlink-parent", "resources", new SessionFixture(true));
        Path outside = Files.createDirectories(directory.resolve("outside-root/child"));
        Path outsideResource = Files.writeString(
                Files.createDirectories(outside.resolveSibling("resources")).resolve("private.txt"), "outside");
        Path link = fixture.input.resolveSibling("linked-root");
        createSymbolicLink(link, outside);
        fixture.mojo.resources.get(0).setDirectory(link.resolve("../resources").toString());

        listener.beforeMojoExecution(fixture.event());
        listener.afterMojoExecutionSuccess(fixture.event());

        assertTrue(fixture.session.receipts.isEmpty());
        assertFalse(Files.exists(fixture.evidenceDirectory()));
        assertEquals("outside", Files.readString(outsideResource));
    }

    @Test
    void normalizationCannotTurnAMissingAncestorIntoAnObservedRoot() throws Exception {
        Fixture fixture = fixture("missing-parent", "resources", new SessionFixture(true));
        fixture.mojo
                .resources
                .get(0)
                .setDirectory(fixture.input
                        .resolveSibling("missing")
                        .resolve("../resources")
                        .toString());

        listener.beforeMojoExecution(fixture.event());
        listener.afterMojoExecutionSuccess(fixture.event());

        assertTrue(fixture.session.receipts.isEmpty());
        assertFalse(Files.exists(fixture.evidenceDirectory()));
    }

    @Test
    void existingNonSymbolicParentReferencesKeepTheirActualMeaning() throws Exception {
        Fixture fixture = fixture("existing-parent", "resources", new SessionFixture(true));
        Path existing = Files.createDirectory(fixture.input.resolveSibling("existing"));
        fixture.mojo
                .resources
                .get(0)
                .setDirectory(existing.resolve("../resources").toString());

        listener.beforeMojoExecution(fixture.event());
        listener.afterMojoExecutionSuccess(fixture.event());

        ResourceObservation observation = read(onlyReceipt(fixture));
        assertEquals(
                fixture.input, Path.of(URI.create(observation.resources().get(0).directory())));
    }

    @Test
    void symbolicOutputAppearingAfterTheGoalPreventsPublication() throws Exception {
        Fixture fixture = fixture("symlink-after", "resources", new SessionFixture(true));
        Path outside = Files.writeString(directory.resolve("outside-after.txt"), "outside");
        Path supportProbe = fixture.input.resolve("probe");
        createSymbolicLink(supportProbe, outside);
        Files.delete(supportProbe);
        listener.beforeMojoExecution(fixture.event());
        Files.createSymbolicLink(fixture.output.resolve("linked.txt"), outside);
        listener.afterMojoExecutionSuccess(fixture.event());

        assertTrue(fixture.session.receipts.isEmpty());
        assertFalse(Files.exists(fixture.evidenceDirectory()));
        assertTrue(Files.isSymbolicLink(fixture.output.resolve("linked.txt")));
    }

    @Test
    void evidenceDirectoryCannotOverlapObservedOutput() throws Exception {
        Fixture fixture = fixture("overlap", "resources", new SessionFixture(true));
        fixture.mojo.outputDirectory = fixture.output.getParent().toFile();
        listener.beforeMojoExecution(fixture.event());
        listener.afterMojoExecutionSuccess(fixture.event());

        assertTrue(fixture.session.receipts.isEmpty());
        assertFalse(Files.exists(fixture.evidenceDirectory()));
    }

    @Test
    void fileSizeAndRootCountLimitsDoNotAffectOriginalGoalCallbacks() throws Exception {
        Fixture large = fixture("large", "resources", new SessionFixture(true));
        Path source = large.input.resolve("large.bin");
        try (RandomAccessFile file = new RandomAccessFile(source.toFile(), "rw")) {
            file.setLength(64L * 1024 * 1024 + 1);
        }
        assertDoesNotThrow(() -> listener.beforeMojoExecution(large.event()));
        assertDoesNotThrow(() -> listener.afterMojoExecutionSuccess(large.event()));
        assertTrue(large.session.receipts.isEmpty());
        assertEquals(64L * 1024 * 1024 + 1, Files.size(source));

        Fixture many = fixture("many-roots", "resources", new SessionFixture(true));
        Resource first = many.mojo.resources.get(0);
        for (int index = 0; index < ResourceObservation.MAX_ROOTS; index++) {
            many.mojo.resources.add(first);
        }
        listener.beforeMojoExecution(many.event());
        listener.afterMojoExecutionSuccess(many.event());
        assertTrue(many.session.receipts.isEmpty());
    }

    @Test
    void publicationFailureRemovesOnlyItsUnpublishedReceipt() throws Exception {
        Fixture fixture = fixture("sink-failure", "resources", new SessionFixture(true));
        Path output = Files.writeString(fixture.output.resolve("result.txt"), "original output");
        doThrow(new IOException("receipt service unavailable"))
                .when(fixture.session.service)
                .publish(any(), any());

        listener.beforeMojoExecution(fixture.event());
        assertDoesNotThrow(() -> listener.afterMojoExecutionSuccess(fixture.event()));
        assertEquals("original output", Files.readString(output));
        try (var files = Files.list(fixture.evidenceDirectory())) {
            assertEquals(0, files.count());
        }
        assertTrue(fixture.session.receipts.isEmpty());
    }

    @Test
    void repeatedExecutionIdsUseDistinctGenerationsAndConsumePendingStateOnce() throws Exception {
        Fixture fixture = fixture("repeat", "resources", new SessionFixture(true));
        listener.beforeMojoExecution(fixture.event());
        listener.afterMojoExecutionSuccess(fixture.event());
        listener.afterMojoExecutionSuccess(fixture.event());
        assertEquals(1, fixture.session.receipts.size());

        listener.beforeMojoExecution(fixture.event());
        listener.afterMojoExecutionSuccess(fixture.event());

        assertEquals(2, fixture.session.receipts.size());
        Receipt first = fixture.session.receipts.get(0);
        Receipt second = fixture.session.receipts.get(1);
        assertEquals(first.identity().execution(), second.identity().execution());
        assertTrue(second.identity().generation() > first.identity().generation());
        assertNotEquals(first.file(), second.file());
        assertTrue(Files.isRegularFile(first.file()));
        assertTrue(Files.isRegularFile(second.file()));
    }

    @Test
    void pendingInvocationsCannotCrossSessionBoundaries() throws Exception {
        Fixture fixture = fixture("sessions", "resources", new SessionFixture(true));
        SessionFixture other = new SessionFixture(true);
        listener.beforeMojoExecution(fixture.event());
        listener.afterMojoExecutionSuccess(
                new MojoExecutionEvent(other.legacy, fixture.project, fixture.execution, fixture.mojo));
        assertTrue(other.receipts.isEmpty());
        assertTrue(fixture.session.receipts.isEmpty());

        listener.afterMojoExecutionSuccess(fixture.event());
        assertEquals(1, fixture.session.receipts.size());
    }

    @Test
    void lifecycleFailureForAnotherProjectCannotDiscardPendingInvocation() throws Exception {
        Fixture fixture = fixture("failure-project", "resources", new SessionFixture(true));
        listener.beforeMojoExecution(fixture.event());
        ExecutionEvent unrelated = mock(ExecutionEvent.class);
        when(unrelated.getType()).thenReturn(ExecutionEvent.Type.MojoFailed);
        when(unrelated.getSession()).thenReturn(fixture.session.legacy);
        when(unrelated.getProject()).thenReturn(new MavenProject());
        when(unrelated.getMojoExecution()).thenReturn(fixture.execution);

        listener.onEvent(unrelated);
        listener.afterMojoExecutionSuccess(fixture.event());

        assertEquals(1, fixture.session.receipts.size());
    }

    @Test
    void parallelProjectsWithTheSameExecutionIdKeepSeparateEvidence() throws Exception {
        SessionFixture session = new SessionFixture(true);
        Fixture left = fixture("parallel-left", "resources", session);
        Fixture right = fixture("parallel-right", "resources", session);
        CyclicBarrier barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (Fixture fixture : List.of(left, right)) {
                futures.add(executor.submit(() -> {
                    listener.beforeMojoExecution(fixture.event());
                    barrier.await(10, TimeUnit.SECONDS);
                    Files.writeString(fixture.output.resolve("result.txt"), fixture.project.getArtifactId());
                    listener.afterMojoExecutionSuccess(fixture.event());
                    return null;
                }));
            }
            for (var future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(2, session.receipts.size());
        assertEquals(
                2,
                session.receipts.stream()
                        .map(receipt -> receipt.identity().project())
                        .distinct()
                        .count());
        assertEquals(2, session.receipts.stream().map(Receipt::file).distinct().count());
        for (Receipt receipt : session.receipts) {
            assertEquals("default-resources", receipt.identity().execution());
            assertEquals("PARTIAL", read(receipt).completeness());
        }
    }

    private Fixture fixture(String name, String goal, SessionFixture session) throws IOException {
        Path base = Files.createDirectories(directory.toRealPath().resolve(name));
        Path input = Files.createDirectories(base.resolve("src/resources"));
        Path output = Files.createDirectories(base.resolve("target/classes"));
        MavenProject project = new MavenProject();
        project.setGroupId("observation.tests");
        project.setArtifactId(name);
        project.setVersion("1");
        project.setFile(Files.writeString(base.resolve("pom.xml"), "<project/>").toFile());
        Build build = new Build();
        build.setDirectory(base.resolve("target").toString());
        build.setOutputDirectory(output.toString());
        project.setBuild(build);
        Resource resource = new Resource();
        resource.setDirectory(input.toString());
        TestResourcesMojo mojo = new TestResourcesMojo(new ArrayList<>(List.of(resource)), output.toFile());
        return new Fixture(
                session, project, execution("maven-resources-plugin", goal, "default-resources"), mojo, input, output);
    }

    private static MojoExecution execution(String artifact, String goal, String id) {
        PluginDescriptor plugin = new PluginDescriptor();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId(artifact);
        plugin.setVersion("3.3.1");
        MojoDescriptor descriptor = new MojoDescriptor(
                plugin,
                org.apache.maven.api.plugin.descriptor.MojoDescriptor.newBuilder()
                        .goal(goal)
                        .implementation(TestResourcesMojo.class.getName())
                        .build());
        return new MojoExecution(descriptor, id);
    }

    private static Receipt onlyReceipt(Fixture fixture) {
        assertEquals(1, fixture.session.receipts.size());
        return fixture.session.receipts.get(0);
    }

    private static ResourceObservation read(Receipt receipt) throws IOException {
        try (var input = Files.newInputStream(receipt.file())) {
            return ResourceObservation.readFrom(input);
        }
    }

    private static FileState state(List<FileState> states, Area area, Path path) {
        return states.stream()
                .filter(file -> matches(file, area, path))
                .findFirst()
                .orElseThrow();
    }

    private static boolean matches(FileState state, Area area, Path path) {
        return state.area() == area
                && Path.of(URI.create(state.uri())).equals(path.toAbsolutePath().normalize());
    }

    private static String sha(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static void createSymbolicLink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException unsupported) {
            assumeTrue(
                    false,
                    "symbolic links are unavailable: " + unsupported.getClass().getSimpleName());
        }
    }

    private record Fixture(
            SessionFixture session,
            MavenProject project,
            MojoExecution execution,
            TestResourcesMojo mojo,
            Path input,
            Path output) {
        MojoExecutionEvent event() {
            return event(execution);
        }

        MojoExecutionEvent event(MojoExecution value) {
            return new MojoExecutionEvent(session.legacy, project, value, mojo);
        }

        Path evidenceDirectory() {
            return Path.of(project.getBuild().getDirectory()).resolve("m3-resources-evidence");
        }
    }

    private static final class SessionFixture {
        final MavenSession legacy = mock(MavenSession.class);
        final InternalMavenSession api = mock(InternalMavenSession.class);
        final M3PluginEvidenceService service = mock(M3PluginEvidenceService.class);
        final Properties user = new Properties();
        final Properties system = new Properties();
        final List<Receipt> receipts = new CopyOnWriteArrayList<>();

        SessionFixture(boolean enabled) throws IOException {
            if (enabled) {
                user.setProperty("m3.resources.evidence", "true");
            }
            when(legacy.getSession()).thenReturn(api);
            when(legacy.getUserProperties()).thenReturn(user);
            when(legacy.getSystemProperties()).thenReturn(system);
            when(api.getData()).thenReturn(new Memory());
            when(api.getService(M3PluginEvidenceService.class)).thenReturn(service);
            doAnswer(invocation -> {
                        assertSame(api, invocation.getArgument(0));
                        receipts.add(invocation.getArgument(1));
                        return null;
                    })
                    .when(service)
                    .publish(any(), any());
        }
    }

    /** Public getters deliberately mirror Resources 3.3.1; execute does no work. */
    public static final class TestResourcesMojo extends AbstractMojo {
        private final AtomicInteger getterCalls = new AtomicInteger();
        private final List<Resource> resources;
        private File outputDirectory;
        private boolean skip;

        TestResourcesMojo(List<Resource> resources, File outputDirectory) {
            this.resources = resources;
            this.outputDirectory = outputDirectory;
        }

        @Override
        public void execute() {}

        public List<Resource> getResources() {
            getterCalls.incrementAndGet();
            return resources;
        }

        public File getOutputDirectory() {
            return outputDirectory;
        }

        public boolean isSkip() {
            return skip;
        }

        public List<String> getFilters() {
            return List.of();
        }

        public LinkedHashSet<String> getDelimiters() {
            return new LinkedHashSet<>(List.of("${*}", "@"));
        }

        public boolean isUseDefaultDelimiters() {
            return true;
        }

        public boolean isIncludeEmptyDirs() {
            return false;
        }

        public boolean isOverwrite() {
            return false;
        }
    }

    private static final class Memory implements SessionData {
        private final Map<Key<?>, Object> state = new ConcurrentHashMap<>();

        @Override
        public synchronized <T> void set(Key<T> key, T value) {
            if (value == null) {
                state.remove(key);
            } else {
                state.put(key, value);
            }
        }

        @Override
        public synchronized <T> boolean replace(Key<T> key, T previous, T next) {
            if (state.get(key) != previous) {
                return false;
            }
            set(key, next);
            return true;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Key<T> key) {
            return (T) state.get(key);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T computeIfAbsent(Key<T> key, Supplier<T> supply) {
            return (T) state.computeIfAbsent(key, ignored -> supply.get());
        }
    }
}
