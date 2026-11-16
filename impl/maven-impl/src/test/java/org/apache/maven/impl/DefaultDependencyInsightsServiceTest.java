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

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.Node;
import org.apache.maven.api.Version;
import org.apache.maven.api.services.DependencyInsightsService;
import org.apache.maven.api.services.DependencyResolverRequest;
import org.apache.maven.api.services.DependencyResolverResult;
import org.apache.maven.di.Injector;
import org.eclipse.aether.graph.DependencyNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exercises native Resolver diagnostics without collecting or downloading artifacts. */
final class DefaultDependencyInsightsServiceTest {

    @Test
    void nativeInjectorExposesOneInsightsService() {
        Injector injector = Injector.create();
        try {
            injector.bindImplicit(DefaultDependencyInsightsService.class);
            DependencyInsightsService service = injector.getInstance(DependencyInsightsService.class);
            assertInstanceOf(DefaultDependencyInsightsService.class, service);
            assertSame(service, injector.getInstance(DependencyInsightsService.class));
        } finally {
            injector.dispose();
        }
    }

    @Test
    void reportsSelectedVersionsAndVerboseSelectionExplanationsInStableOrder() {
        Node original = node(artifact("example", "a", "1.0"));
        Node chosen = node(artifact("example", "a", "2.0"));
        Node z = node(artifact("example", "z", "3.0-SNAPSHOT"));
        Node root = node(null, original, z, chosen);
        when(original.winner()).thenReturn(Optional.of(chosen));
        when(chosen.premanagedVersion()).thenReturn(Optional.of("1.5"));

        DependencyInsightsService.Report report =
                new DefaultDependencyInsightsService().inspect(result(root, List.of(z, chosen), List.of(), true));
        assertTrue(report.complete());
        assertTrue(report.verbose());
        assertEquals(4, report.graphNodes());
        assertEquals(
                List.of("a", "z"),
                report.selected().stream().map(s -> s.coordinate().artifactId()).toList());
        assertEquals("2.0", report.selected().get(0).version());
        assertTrue(report.selected().get(1).snapshot());
        assertEquals(1, report.omissions().size());
        assertEquals("1.0", report.omissions().get(0).omittedVersion());
        assertEquals("2.0", report.omissions().get(0).selectedVersion());
        assertFalse(report.omissions().get(0).duplicate());
        assertEquals(1, report.managedVersions().size());
        assertEquals("1.5", report.managedVersions().get(0).declaredVersion());
        assertEquals("2.0", report.managedVersions().get(0).selectedVersion());
        assertThrows(
                UnsupportedOperationException.class, () -> report.selected().clear());
        assertThrows(
                UnsupportedOperationException.class, () -> report.omissions().clear());
    }

    @Test
    void incompleteResolutionDoesNotProduceAFalseCleanVerdict() {
        Node selected = node(artifact("example", "a", "1.0"));
        DependencyInsightsService.Report report = new DefaultDependencyInsightsService()
                .inspect(result(null, List.of(selected), List.of(new IllegalStateException("failed")), false));
        assertFalse(report.complete());
        assertFalse(report.verbose());
        assertEquals(1, report.resolutionErrors());
        assertEquals(0, report.graphNodes());
        assertEquals(1, report.selected().size(), "retained selected facts must be marked incomplete");
        assertTrue(report.omissions().isEmpty(), "missing verbose metadata is not a convergence proof");
    }

    @Test
    void cyclesSharedNodesAndDuplicateVersionsCannotMultiplyEvidence() {
        Node skipped = node(artifact("example", "a", "1.0"));
        Node chosen = node(artifact("example", "a", "1.0"));
        Node root = node(null, skipped, chosen, skipped);
        when(skipped.winner()).thenReturn(Optional.of(chosen));
        when(chosen.children()).thenReturn(List.of(root));
        DependencyInsightsService.Report report =
                new DefaultDependencyInsightsService().inspect(result(root, List.of(chosen, chosen), List.of(), true));
        assertEquals(3, report.graphNodes());
        assertEquals(1, report.selected().size());
        assertEquals(1, report.omissions().size());
        assertTrue(report.omissions().get(0).duplicate());
        assertTrue(report.complete());
    }

    @Test
    void nativeResolverWrappersShareOriginalNodeIdentity() {
        InternalSession owner = mock(InternalSession.class);
        DependencyNode rootGraph = mock(DependencyNode.class);
        DependencyNode childGraph = mock(DependencyNode.class);
        when(rootGraph.getChildren()).thenReturn(List.of(childGraph, childGraph));
        when(childGraph.getChildren()).thenReturn(List.of(rootGraph));
        when(owner.getNode(any(DependencyNode.class), eq(true)))
                .thenAnswer(call -> new DefaultNode(owner, call.getArgument(0), true));

        // DefaultNode.children() reconstructs facade objects on each access.
        // A facade-identity visited set would loop until its budget fails.
        Node root = new DefaultNode(owner, rootGraph, true);
        DependencyInsightsService.Report report =
                new DefaultDependencyInsightsService().inspect(result(root, List.of(), List.of(), true));
        assertEquals(2, report.graphNodes());
        assertTrue(report.complete());
    }

    @Test
    void boundedGraphRefusesExcessiveEdgesRatherThanReturningPartialEvidence() {
        Node root = node(null);
        when(root.children()).thenReturn(Collections.nCopies(262_145, root));
        DependencyResolverResult result = result(root, List.of(), List.of(), true);
        assertThrows(IllegalArgumentException.class, () -> new DefaultDependencyInsightsService().inspect(result));
    }

    private static DependencyResolverResult result(
            Node root, List<Node> selected, List<Exception> exceptions, boolean verbose) {
        DependencyResolverRequest request = mock(DependencyResolverRequest.class);
        when(request.getVerbose()).thenReturn(verbose);
        when(request.getRequestType()).thenReturn(DependencyResolverRequest.RequestType.RESOLVE);
        DependencyResolverResult result = mock(DependencyResolverResult.class);
        when(result.getRequest()).thenReturn(request);
        when(result.getRoot()).thenReturn(root);
        when(result.getNodes()).thenReturn(selected);
        when(result.getExceptions()).thenReturn(exceptions);
        return result;
    }

    private static Node node(Artifact artifact, Node... children) {
        Node node = mock(Node.class);
        when(node.artifact()).thenReturn(artifact);
        when(node.children()).thenReturn(List.of(children));
        when(node.winner()).thenReturn(Optional.empty());
        when(node.premanagedVersion()).thenReturn(Optional.empty());
        return node;
    }

    private static Artifact artifact(String group, String name, String versionText) {
        Artifact artifact = mock(Artifact.class);
        Version version = mock(Version.class);
        when(version.toString()).thenReturn(versionText);
        when(artifact.groupId()).thenReturn(group);
        when(artifact.artifactId()).thenReturn(name);
        when(artifact.extension()).thenReturn("jar");
        when(artifact.classifier()).thenReturn("");
        when(artifact.version()).thenReturn(version);
        when(artifact.snapshot()).thenReturn(versionText.endsWith("-SNAPSHOT"));
        return artifact;
    }
}
