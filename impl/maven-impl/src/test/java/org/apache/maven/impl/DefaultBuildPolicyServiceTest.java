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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.maven.api.Dependency;
import org.apache.maven.api.Node;
import org.apache.maven.api.Session;
import org.apache.maven.api.Version;
import org.apache.maven.api.VersionRange;
import org.apache.maven.api.services.BuildPolicyService;
import org.apache.maven.api.services.DependencyResolverResult;
import org.apache.maven.api.services.VersionParser;
import org.apache.maven.di.Injector;
import org.junit.jupiter.api.Test;

import static org.apache.maven.api.services.BuildPolicyService.Severity.ERROR;
import static org.apache.maven.api.services.BuildPolicyService.Severity.WARN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class DefaultBuildPolicyServiceTest {
    private final Session session = mock(Session.class);
    private final VersionParser parser = mock(VersionParser.class);
    private final DefaultBuildPolicyService service = new DefaultBuildPolicyService(parser);

    @Test
    void mavenInjectorResolvesNativePolicyService() {
        Injector injector = Injector.create();
        try {
            injector.bindInstance(VersionParser.class, parser);
            injector.bindImplicit(DefaultBuildPolicyService.class);
            var nativeService = injector.getInstance(BuildPolicyService.class);
            assertInstanceOf(DefaultBuildPolicyService.class, nativeService);
            assertSame(nativeService, injector.getInstance(BuildPolicyService.class));
        } finally {
            injector.dispose();
        }
    }

    @Test
    void enforcerStyleBareMinimumUsesNativeMavenVersionComparison() {
        Version actual = mock(Version.class);
        Version floor = mock(Version.class);
        when(session.getMavenVersion()).thenReturn(actual);
        when(parser.parseVersion("4.2.0")).thenReturn(floor);
        when(actual.compareTo(floor)).thenReturn(-1);
        var report = evaluate(21, null, new BuildPolicyService.MavenVersion("4.2.0", ERROR));
        assertFalse(report.accepted());
        assertEquals("requireMavenVersion", report.findings().get(0).rule());
        when(actual.compareTo(floor)).thenReturn(0);
        assertTrue(evaluate(21, null, new BuildPolicyService.MavenVersion("4.2.0", ERROR)).accepted());
    }

    @Test
    void bracketedRequirementsUseMavenRangesAndWarnDoesNotFail() {
        Version actual = mock(Version.class);
        VersionRange range = mock(VersionRange.class);
        when(session.getMavenVersion()).thenReturn(actual);
        when(parser.parseVersionRange("[4.0,5.0)")).thenReturn(range);
        when(range.contains(actual)).thenReturn(false);
        var report = evaluate(21, null, new BuildPolicyService.MavenVersion("[4.0,5.0)", WARN));
        assertTrue(report.accepted());
        assertEquals(WARN, report.findings().get(0).severity());
        when(range.contains(actual)).thenReturn(true);
        assertTrue(evaluate(21, null, new BuildPolicyService.MavenVersion("[4.0,5.0)", ERROR))
                .findings().isEmpty());
    }

    @Test
    void javaFeatureIsTheExecutingJvmNotACompilerToolchain() {
        var report = evaluate(17, null, new BuildPolicyService.JavaFeature(21, ERROR));
        assertFalse(report.accepted());
        assertEquals("runtime-jvm", report.findings().get(0).subject());
        assertTrue(evaluate(21, null, new BuildPolicyService.JavaFeature(21, ERROR)).accepted());
    }

    @Test
    void missingOrFailedResolutionDoesNotSilentlyPassSnapshotPolicy() {
        var rule = new BuildPolicyService.NoSnapshotDependencies(ERROR);
        assertFalse(evaluate(21, null, rule).accepted());
        DependencyResolverResult failed = mock(DependencyResolverResult.class);
        when(failed.getExceptions()).thenReturn(List.of(new IllegalStateException("resolution failed")));
        assertFalse(evaluate(21, failed, rule).accepted());
        DependencyResolverResult missingRoot = mock(DependencyResolverResult.class);
        when(missingRoot.getExceptions()).thenReturn(List.of());
        assertFalse(evaluate(21, missingRoot, rule).accepted());
    }

    @Test
    void selectedSnapshotsAreOrderedAndWarningsRemainNonFatal() {
        Dependency alpha = dependency("a", false);
        Dependency zeta = dependency("z", true);
        Dependency beta = dependency("b", true);
        DependencyResolverResult resolved = mock(DependencyResolverResult.class);
        when(resolved.getExceptions()).thenReturn(List.of());
        when(resolved.getRoot()).thenReturn(mock(Node.class));
        when(resolved.getDependencies())
                .thenReturn(Map.of(zeta, Path.of("z.jar"), alpha, Path.of("a.jar"), beta, Path.of("b.jar")));
        var report = evaluate(21, resolved, new BuildPolicyService.NoSnapshotDependencies(WARN));
        assertTrue(report.accepted());
        assertEquals(List.of("b", "z"), report.findings().stream().map(BuildPolicyService.Finding::subject).toList());
        assertEquals(2, report.findings().size());
    }

    @Test
    void requestsAndReportsAreImmutableAndRejectInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> new BuildPolicyService.JavaFeature(0, ERROR));
        assertThrows(IllegalArgumentException.class, () -> new BuildPolicyService.MavenVersion(" ", ERROR));
        assertThrows(NullPointerException.class, () -> new BuildPolicyService.NoSnapshotDependencies(null));
        assertThrows(IllegalArgumentException.class, () -> new BuildPolicyService.Request(session, 0, null, List.of()));
        var request = new BuildPolicyService.Request(session, 21, null, List.of());
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.rules().add(new BuildPolicyService.JavaFeature(21, ERROR)));
        var report = evaluate(21, null, new BuildPolicyService.JavaFeature(22, ERROR));
        assertThrows(UnsupportedOperationException.class, () -> report.findings().clear());
    }

    private BuildPolicyService.Report evaluate(
            int feature, DependencyResolverResult dependencies, BuildPolicyService.Rule... rules) {
        return service.evaluate(new BuildPolicyService.Request(session, feature, dependencies, List.of(rules)));
    }

    private static Dependency dependency(String key, boolean snapshot) {
        Dependency dependency = mock(Dependency.class);
        when(dependency.key()).thenReturn(key);
        when(dependency.snapshot()).thenReturn(snapshot);
        return dependency;
    }
}
