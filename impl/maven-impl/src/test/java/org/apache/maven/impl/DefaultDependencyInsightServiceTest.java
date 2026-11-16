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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.Node;
import org.apache.maven.api.services.DependencyInsightService;
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

final class DefaultDependencyInsightServiceTest {
    private final DefaultDependencyInsightService service = new DefaultDependencyInsightService();

    @Test
    void mavenInjectorResolvesNativeInsightService() {
        Injector injector = Injector.create();
        try {
            injector.bindImplicit(DefaultDependencyInsightService.class);
            var nativeService = injector.getInstance(DependencyInsightService.class);
            assertInstanceOf(DefaultDependencyInsightService.class, nativeService);
            assertSame(nativeService, injector.getInstance(DependencyInsightService.class));
        } finally {
            injector.dispose();
        }
    }

    @Test
    void nativeVerboseGraphYieldsFlatPathsConflictWinnerAndManagementEvidence() {
        Node first = node("group:first:jar:1");
        Node omitted = node("group:first:jar:2");
        Node root = node(null);
        when(root.children()).thenReturn(List.of(first, omitted));
        when(omitted.winner()).thenReturn(Optional.of(first));
        when(omitted.premanagedVersion()).thenReturn(Optional.of("2.1"));
        when(omitted.premanagedScope()).thenReturn(Optional.of("test"));
        when(omitted.originalScope()).thenReturn(Optional.of("runtime"));

        var snapshot = service.inspect(new DependencyInsightService.Request(result(root, true), 8));
        assertTrue(snapshot.complete());
        assertTrue(snapshot.verboseRequested());
        assertEquals(3, snapshot.size());
        assertEquals(-1, snapshot.entry(0).parentIndex());
        assertEquals(0, snapshot.entry(1).parentIndex());
        assertEquals(0, snapshot.entry(2).parentIndex());
        assertFalse(snapshot.entry(1).omitted());
        assertTrue(snapshot.entry(2).omitted());
        assertEquals("group:first:jar:1", snapshot.entry(2).winningCoordinates());
        assertEquals("2.1", snapshot.entry(2).premanagedVersion());
        assertEquals("test", snapshot.entry(2).premanagedScope());
        assertEquals("runtime", snapshot.entry(2).originalScope());
        assertEquals(List.of("<root>", "group:first:jar:2"), snapshot.path(2));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.path(2).clear());
    }

    @Test
    void truncationIsExplicitAndPreservesFirstEntries() {
        Node first = node("a");
        Node second = node("b");
        Node root = node(null);
        when(root.children()).thenReturn(List.of(first, second));
        var snapshot = service.inspect(new DependencyInsightService.Request(result(root, false), 2));
        assertEquals(2, snapshot.size());
        assertFalse(snapshot.complete());
        assertFalse(snapshot.verboseRequested());
        assertEquals("a", snapshot.entry(1).coordinates());
        assertThrows(IndexOutOfBoundsException.class, () -> snapshot.entry(2));
        assertThrows(IndexOutOfBoundsException.class, () -> snapshot.path(-1));
    }

    @Test
    void cyclicProviderGraphIsSkippedInsteadOfRecursing() {
        Node root = node(null);
        Node child = node("child");
        when(root.children()).thenReturn(List.of(child));
        when(child.children()).thenReturn(List.of(root));
        var snapshot = service.inspect(new DependencyInsightService.Request(result(root, true), 8));
        assertEquals(2, snapshot.size());
        assertEquals(1, snapshot.cyclesSkipped());
        assertFalse(snapshot.complete());
        assertEquals(List.of("<root>", "child"), snapshot.path(1));
    }

    @Test
    void recreatedNativeNodeFacadesStillDetectTheOriginalResolverCycle() {
        InternalSession owner = mock(InternalSession.class);
        DependencyNode resolverRoot = mock(DependencyNode.class);
        DependencyNode resolverChild = mock(DependencyNode.class);
        when(resolverRoot.getData()).thenReturn(Map.of());
        when(resolverChild.getData()).thenReturn(Map.of());
        when(resolverRoot.getChildren()).thenReturn(List.of(resolverChild));
        when(resolverChild.getChildren()).thenReturn(List.of(resolverRoot));
        when(owner.getNode(any(DependencyNode.class), eq(true)))
                .thenAnswer(invocation -> new DefaultNode(owner, invocation.getArgument(0), true));

        // Every child access constructs another Java facade for the same
        // underlying graph node. This cycle must not appear as truncation.
        Node root = new DefaultNode(owner, resolverRoot, true);
        var snapshot = service.inspect(new DependencyInsightService.Request(result(root, true), 8));
        assertEquals(2, snapshot.size());
        assertEquals(1, snapshot.cyclesSkipped());
        assertFalse(snapshot.complete());
        assertEquals(List.of("<root>", "<root>"), snapshot.path(1));
    }

    @Test
    void sharedNativeChildRetainsEachParentPathWithoutFalseCycle() {
        InternalSession owner = mock(InternalSession.class);
        DependencyNode rootNode = mock(DependencyNode.class);
        DependencyNode left = mock(DependencyNode.class);
        DependencyNode right = mock(DependencyNode.class);
        DependencyNode shared = mock(DependencyNode.class);
        for (DependencyNode node : List.of(rootNode, left, right, shared)) {
            when(node.getData()).thenReturn(Map.of());
        }
        when(rootNode.getChildren()).thenReturn(List.of(left, right));
        when(left.getChildren()).thenReturn(List.of(shared));
        when(right.getChildren()).thenReturn(List.of(shared));
        when(shared.getChildren()).thenReturn(List.of());
        when(owner.getNode(any(DependencyNode.class), eq(true)))
                .thenAnswer(invocation -> new DefaultNode(owner, invocation.getArgument(0), true));

        var snapshot = service.inspect(new DependencyInsightService.Request(
                result(new DefaultNode(owner, rootNode, true), true), 8));
        assertTrue(snapshot.complete());
        assertEquals(0, snapshot.cyclesSkipped());
        assertEquals(5, snapshot.size());
        assertEquals(1, snapshot.entry(2).parentIndex());
        assertEquals(3, snapshot.entry(4).parentIndex());
    }

    @Test
    void missingRootOrResolverExceptionsCannotClaimCompleteness() {
        var missing = service.inspect(new DependencyInsightService.Request(result(null, false), 8));
        assertEquals(0, missing.size());
        assertFalse(missing.complete());
        DependencyResolverResult failed = result(node("root"), true);
        when(failed.getExceptions()).thenReturn(List.of(new IllegalStateException("failure")));
        assertFalse(
                service.inspect(new DependencyInsightService.Request(failed, 8)).complete());
        assertThrows(IllegalArgumentException.class, () -> new DependencyInsightService.Request(failed, 0));
        assertThrows(IllegalArgumentException.class, () -> new DependencyInsightService.Request(failed, 1_000_001));
    }

    @Test
    void snapshotDoesNotRetainOriginalNodeState() {
        Node root = node("before");
        var snapshot = service.inspect(new DependencyInsightService.Request(result(root, false), 1));
        Artifact changed = artifact("after");
        when(root.artifact()).thenReturn(changed);
        assertEquals("before", snapshot.entry(0).coordinates());
        assertEquals(List.of("before"), snapshot.path(0));
    }

    private static Node node(String key) {
        Node node = mock(Node.class);
        when(node.children()).thenReturn(List.of());
        when(node.winner()).thenReturn(Optional.empty());
        when(node.premanagedVersion()).thenReturn(Optional.empty());
        when(node.premanagedScope()).thenReturn(Optional.empty());
        when(node.originalScope()).thenReturn(Optional.empty());
        if (key != null) {
            when(node.artifact()).thenReturn(artifact(key));
        }
        return node;
    }

    private static Artifact artifact(String key) {
        Artifact artifact = mock(Artifact.class);
        when(artifact.key()).thenReturn(key);
        return artifact;
    }

    private static DependencyResolverResult result(Node root, boolean verbose) {
        DependencyResolverResult result = mock(DependencyResolverResult.class);
        DependencyResolverRequest request = mock(DependencyResolverRequest.class);
        when(result.getRequest()).thenReturn(request);
        when(request.getVerbose()).thenReturn(verbose);
        when(result.getRoot()).thenReturn(root);
        when(result.getExceptions()).thenReturn(List.of());
        return result;
    }
}
