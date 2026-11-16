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
package org.apache.maven.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.maven.api.services.M3ExternalBuildService;
import org.apache.maven.api.services.M3ExternalBuildWire;
import org.apache.maven.api.services.M3ExternalBuildService.Change;
import org.apache.maven.api.services.M3ExternalBuildService.ChangeKind;
import org.apache.maven.api.services.M3ExternalBuildService.Disposition;
import org.apache.maven.api.services.M3ExternalBuildService.Generation;
import org.apache.maven.api.services.M3ExternalBuildService.Reason;
import org.apache.maven.di.Injector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Original Maven service DI plus predictable external delta classifications. */
final class DefaultM3ExternalBuildServiceTest {
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private final M3ExternalBuildService service = new DefaultM3ExternalBuildService();

    @Test
    void nativeMavenInjectorFindsOneService() {
        Injector injector = Injector.create();
        try {
            injector.bindImplicit(DefaultM3ExternalBuildService.class);
            M3ExternalBuildService instance = injector.getInstance(M3ExternalBuildService.class);
            assertInstanceOf(DefaultM3ExternalBuildService.class, instance);
            assertSame(instance, injector.getInstance(M3ExternalBuildService.class));
        } finally {
            injector.dispose();
        }
    }

    @Test
    void jdtAndOtherHostDeltaRemainsAdvisoryAndOrdered() {
        List<Change> input = new ArrayList<>(List.of(
                new Change("src/B.java", ChangeKind.MODIFIED, null),
                new Change("src/A.java", ChangeKind.ADDED, A)));
        var result = service.assess(generation("eclipse.jdt", 7, A, true),
                generation("eclipse.jdt", 8, A, true), input);
        input.clear();
        assertEquals(Disposition.HOST_DELTA_AVAILABLE, result.disposition());
        assertEquals(List.of(Reason.HOST_DELTA_READY), result.reasons());
        assertEquals(2, result.size());
        assertEquals("src/A.java", result.entry(0).relativePath());
        assertEquals("src/B.java", result.entry(1).relativePath());
        assertFalse(result.authorizesMavenGoalSkipping());
        assertThrows(IndexOutOfBoundsException.class, () -> result.entry(2));
        assertThrows(UnsupportedOperationException.class, () -> result.reasons().clear());
        assertEquals(2, result.size());
    }

    @Test
    void firstBuildIncompleteAndGappedGenerationsRequireMavenReconciliation() {
        Generation current = generation("eclipse.jdt", 10, A, true);
        var first = service.assess(null, current, List.of());
        assertEquals(Disposition.MAVEN_RECONCILIATION, first.disposition());
        assertTrue(first.reasons().contains(Reason.INITIAL_BASELINE_MISSING));
        var missing = service.assess(generation("eclipse.jdt", 9, A, true),
                generation("eclipse.jdt", 10, A, false), List.of());
        assertTrue(missing.reasons().contains(Reason.INCOMPLETE_DELTA));
        var gap = service.assess(generation("eclipse.jdt", 3, A, true), current, List.of());
        assertTrue(gap.reasons().contains(Reason.GENERATION_GAP));
        var rollover = service.assess(generation("eclipse.jdt", Long.MAX_VALUE, A, true),
                generation("eclipse.jdt", 1, A, true), List.of());
        assertTrue(rollover.reasons().contains(Reason.GENERATION_GAP));
    }

    @Test
    void providerChangesModelOrToolchainInvalidateDeltaAdvisory() {
        var previous = generation("eclipse.jdt", 1, A, true);
        var different = generation("visualstudio.msbuild", 2, A, true);
        assertTrue(service.assess(previous, different, List.of()).reasons().contains(Reason.FOREIGN_BASELINE));
        var changed = generation("eclipse.jdt", 2, B, true);
        assertTrue(service.assess(previous, changed, List.of()).reasons()
                .contains(Reason.MODEL_OR_TOOLCHAIN_CHANGED));
    }

    @Test
    void deletedSourcesAndBuildConfigChangesNeverClaimIncrementalMavenReuse() {
        var previous = generation("visualstudio.msbuild", 2, A, true);
        var current = generation("visualstudio.msbuild", 3, A, true);
        for (String filename : List.of("pom.xml", "module-a/pom.xml", ".mvn/maven.config", ".classpath",
                "module-info.java", "settings.gradle.kts", "project.vcxproj", "CMakeLists.txt",
                "project/pyproject.toml", "src/build/Generated.java")) {
            var assessment = service.assess(previous, current,
                    List.of(new Change(filename, ChangeKind.MODIFIED, null)));
            assertEquals(Disposition.MAVEN_RECONCILIATION, assessment.disposition(), filename);
            assertFalse(assessment.authorizesMavenGoalSkipping());
        }
        var deleted = service.assess(previous, current,
                List.of(new Change("src/Old.java", ChangeKind.DELETED, null)));
        assertTrue(deleted.reasons().contains(Reason.INPUT_REMOVED));
        assertEquals(Disposition.MAVEN_RECONCILIATION, deleted.disposition());
    }

    @Test
    void duplicatePathsAndUnsafeInputsAreRejectedBeforeAnyUse() {
        var previous = generation("eclipse.jdt", 1, A, true);
        var current = generation("eclipse.jdt", 2, A, true);
        assertThrows(IllegalArgumentException.class, () -> service.assess(previous, current,
                List.of(new Change("src/A.java", ChangeKind.ADDED, A),
                        new Change("src/A.java", ChangeKind.MODIFIED, B))));
        for (String path : List.of("", "/etc/passwd", "C:/temp/file", "src/../outside", "src/./x",
                "src//x", "src/x\\y", "src/tab\tfile", "src/" + (char) 0xd800)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Change(path, ChangeKind.MODIFIED, null), path);
        }
        assertThrows(IllegalArgumentException.class, () -> new Change("a", ChangeKind.MODIFIED, "A".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new Change("a", ChangeKind.DELETED, A));
        assertThrows(IllegalArgumentException.class, () -> generation("eclipse.jdt", 0, A, true));
        assertThrows(IllegalArgumentException.class, () -> new M3ExternalBuildService.Request(
                previous, current, Collections.nCopies(M3ExternalBuildService.MAX_CHANGES + 1,
                        new Change("a", ChangeKind.MODIFIED, null))));
    }

    @Test
    void emptyCompleteHostDeltaIsNotAProvenMavenCacheHit() {
        var assessment = service.assess(generation("vscode.java", 4, A, true),
                generation("vscode.java", 5, A, true), List.of());
        assertEquals(Disposition.HOST_DELTA_AVAILABLE, assessment.disposition());
        assertEquals(List.of(Reason.HOST_DELTA_EMPTY), assessment.reasons());
        assertFalse(assessment.authorizesMavenGoalSkipping());
    }

    @Test
    void boundedLargeDeltasAreSortedWithoutPerEntryRetainedObjects() {
        var changes = new ArrayList<Change>();
        for (int i = 1_000; i >= 1; i--) {
            changes.add(new Change("src/C" + String.format("%04d", i) + ".java",
                    ChangeKind.MODIFIED, null));
        }
        var report = service.assess(generation("eclipse.jdt", 1, A, true),
                generation("eclipse.jdt", 2, A, true), changes);
        assertEquals(1_000, report.size());
        assertEquals("src/C0001.java", report.entry(0).relativePath());
        assertEquals("src/C1000.java", report.entry(999).relativePath());
        assertEquals(Disposition.HOST_DELTA_AVAILABLE, report.disposition());
    }

    @Test
    void decodedHostWirePreservesNativeAssessmentAndNeverGrantsReuse() throws Exception {
        Generation previous = new Generation("eclipse.m2e", "project", 4, A, A, A, A, true);
        Generation current = new Generation("eclipse.m2e", "project", 5, A, A, A, A, true);
        byte[] readyPacket = M3ExternalBuildWire.encode(new M3ExternalBuildWire.Envelope(
                "eclipse.m2e",
                "project",
                "INCREMENTAL",
                true,
                List.of(new Change("src/A.java", ChangeKind.MODIFIED, null))));
        var available = service.assess(readyPacket, previous, current);
        assertEquals(Disposition.HOST_DELTA_AVAILABLE, available.disposition());
        assertEquals("src/A.java", available.entry(0).relativePath());
        assertFalse(available.authorizesMavenGoalSkipping());

        var cold = service.assess(readyPacket, null, current);
        assertEquals(Disposition.MAVEN_RECONCILIATION, cold.disposition());
        assertTrue(cold.reasons().contains(Reason.INITIAL_BASELINE_MISSING));

        byte[] incompletePacket = M3ExternalBuildWire.encode(new M3ExternalBuildWire.Envelope(
                "eclipse.m2e",
                "project",
                "AUTO",
                false,
                List.of(new Change("src/A.java", ChangeKind.MODIFIED, null))));
        var incomplete = service.assess(incompletePacket, previous, current);
        assertEquals(Disposition.MAVEN_RECONCILIATION, incomplete.disposition());
        assertTrue(incomplete.reasons().contains(Reason.INCOMPLETE_DELTA));
        assertThrows(
                IOException.class,
                () -> service.assess("not an IDE delta".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        previous, current));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.assess(
                        readyPacket,
                        previous,
                        new Generation("vscode.java", "project", 5, A, A, A, A, true)));
    }

    private static Generation generation(String provider, long generation, String model, boolean complete) {
        return new Generation(provider, "project", generation, model, A, A, A, complete);
    }
}
