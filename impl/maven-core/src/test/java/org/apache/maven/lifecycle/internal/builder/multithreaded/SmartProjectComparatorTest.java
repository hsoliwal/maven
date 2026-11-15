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
package org.apache.maven.lifecycle.internal.builder.multithreaded;

// Modified 2026-11-15 by Hitesh Soliwal and Contributors to the Synexia Project:
// Qualify stack-safe lazy critical-path traversal and preserve native reactor contracts.

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.lifecycle.internal.stub.ProjectDependencyGraphStub;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for SmartProjectComparator to verify critical path scheduling logic.
 */
class SmartProjectComparatorTest {

    private SmartProjectComparator comparator;
    private ProjectDependencyGraph dependencyGraph;

    @BeforeEach
    void setUp() {
        dependencyGraph = new ProjectDependencyGraphStub();
        comparator = new SmartProjectComparator(dependencyGraph);
    }

    @Test
    void testProjectWeightCalculation() {
        // Test that projects with longer downstream chains get higher weights
        // Graph: A -> B,C; B -> X,Y; C -> X,Z
        MavenProject projectA = ProjectDependencyGraphStub.A;
        MavenProject projectB = ProjectDependencyGraphStub.B;
        MavenProject projectC = ProjectDependencyGraphStub.C;
        MavenProject projectX = ProjectDependencyGraphStub.X;

        long weightA = comparator.getProjectWeight(projectA);
        long weightB = comparator.getProjectWeight(projectB);
        long weightC = comparator.getProjectWeight(projectC);
        long weightX = comparator.getProjectWeight(projectX);

        // Project A should have the highest weight as it's at the root
        assertTrue(weightA > weightB, "Project A should have weight > Project B");
        assertTrue(weightA > weightC, "Project A should have weight > Project C");
        assertTrue(weightB > weightX, "Project B should have weight > Project X");
        assertTrue(weightC > weightX, "Project C should have weight > Project X");
    }

    @Test
    void testComparatorOrdering() {
        List<MavenProject> projects = Arrays.asList(
                ProjectDependencyGraphStub.X,
                ProjectDependencyGraphStub.C,
                ProjectDependencyGraphStub.A,
                ProjectDependencyGraphStub.B);

        // Sort using the comparator
        projects.sort(comparator.getComparator());

        // Project A should come first (highest weight)
        assertEquals(
                ProjectDependencyGraphStub.A,
                projects.get(0),
                "Project A should be first (highest critical path weight)");

        // B and C should come before X (they have higher weights)
        assertTrue(
                projects.indexOf(ProjectDependencyGraphStub.B) < projects.indexOf(ProjectDependencyGraphStub.X),
                "Project B should come before X");
        assertTrue(
                projects.indexOf(ProjectDependencyGraphStub.C) < projects.indexOf(ProjectDependencyGraphStub.X),
                "Project C should come before X");
    }

    @Test
    void testWeightConsistency() {
        // Test that weights are consistent across multiple calls
        MavenProject project = ProjectDependencyGraphStub.A;

        long weight1 = comparator.getProjectWeight(project);
        long weight2 = comparator.getProjectWeight(project);

        assertEquals(weight1, weight2, "Project weight should be consistent");
    }

    @Test
    void testDependencyChainLength() {
        // Test that projects with longer dependency chains get higher weights
        // In the stub: A -> B,C; B -> X,Y; C -> X,Z
        long weightA = comparator.getProjectWeight(ProjectDependencyGraphStub.A);
        long weightB = comparator.getProjectWeight(ProjectDependencyGraphStub.B);
        long weightC = comparator.getProjectWeight(ProjectDependencyGraphStub.C);
        long weightX = comparator.getProjectWeight(ProjectDependencyGraphStub.X);
        long weightY = comparator.getProjectWeight(ProjectDependencyGraphStub.Y);
        long weightZ = comparator.getProjectWeight(ProjectDependencyGraphStub.Z);

        // Verify the actual chain length calculation
        // Leaf nodes (no downstream dependencies)
        assertEquals(1L, weightX, "Project X should have weight 1 (1 + 0)");
        assertEquals(1L, weightY, "Project Y should have weight 1 (1 + 0)");
        assertEquals(1L, weightZ, "Project Z should have weight 1 (1 + 0)");

        // Middle nodes
        assertEquals(2L, weightB, "Project B should have weight 2 (1 + max(X=1, Y=1))");
        assertEquals(2L, weightC, "Project C should have weight 2 (1 + max(X=1, Z=1))");

        // Root node
        assertEquals(3L, weightA, "Project A should have weight 3 (1 + max(B=2, C=2))");
    }

    @Test
    void testSameWeightOrdering() {
        // Test that projects with the same weight are ordered by project ID
        // Projects B and C both have weight 2, so they should be ordered by project ID
        List<MavenProject> projects = Arrays.asList(
                ProjectDependencyGraphStub.C, // weight=2, ID contains "C"
                ProjectDependencyGraphStub.B // weight=2, ID contains "B"
                );

        projects.sort(comparator.getComparator());

        // Both have same weight (2), so ordering should be by project ID
        // Project B should come before C alphabetically by project ID
        assertEquals(
                ProjectDependencyGraphStub.B,
                projects.get(0),
                "Project B should come before C when they have the same weight (ordered by project ID)");
        assertEquals(
                ProjectDependencyGraphStub.C,
                projects.get(1),
                "Project C should come after B when they have the same weight (ordered by project ID)");

        // Verify they actually have the same weight
        long weightB = comparator.getProjectWeight(ProjectDependencyGraphStub.B);
        long weightC = comparator.getProjectWeight(ProjectDependencyGraphStub.C);
        assertEquals(weightB, weightC, "Projects B and C should have the same weight");
    }

    @Test
    void testConcurrentWeightCalculation() throws Exception {
        int numThreads = 10;
        int numIterations = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < numThreads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int j = 0; j < numIterations; j++) {
                        List<MavenProject> projects = Arrays.asList(
                                ProjectDependencyGraphStub.A,
                                ProjectDependencyGraphStub.B,
                                ProjectDependencyGraphStub.C,
                                ProjectDependencyGraphStub.X,
                                ProjectDependencyGraphStub.Y,
                                ProjectDependencyGraphStub.Z);
                        projects.sort(comparator.getComparator());
                        for (MavenProject project : projects) {
                            assertTrue(comparator.getProjectWeight(project) > 0L);
                        }
                        assertEquals(3L, comparator.getProjectWeight(ProjectDependencyGraphStub.A));
                        assertEquals(2L, comparator.getProjectWeight(ProjectDependencyGraphStub.B));
                        assertEquals(2L, comparator.getProjectWeight(ProjectDependencyGraphStub.C));
                        assertEquals(1L, comparator.getProjectWeight(ProjectDependencyGraphStub.X));
                        assertEquals(1L, comparator.getProjectWeight(ProjectDependencyGraphStub.Y));
                        assertEquals(1L, comparator.getProjectWeight(ProjectDependencyGraphStub.Z));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                // Future.get propagates task Exceptions and Errors, including assertion failures.
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    @Test
    void testDeepDependencyChain() {
        int projectCount = 20_000;
        int[][] downstream = new int[projectCount][];
        for (int i = 0; i < projectCount; i++) {
            downstream[i] = i + 1 < projectCount ? new int[] {i + 1} : new int[0];
        }
        TestDependencyGraph graph = new TestDependencyGraph(downstream);
        SmartProjectComparator deepComparator = new SmartProjectComparator(graph);

        assertEquals((long) projectCount, deepComparator.getProjectWeight(graph.projects.get(0)));
        assertEquals(1L, deepComparator.getProjectWeight(graph.projects.get(projectCount - 1)));
        assertEquals(projectCount, graph.downstreamReads.get());
    }

    @Test
    void testDiamondAndGeneratedDagsMatchBottomUpOracle() {
        assertWeightsMatchBottomUpOracle(new int[][] {{1, 2}, {3, 4}, {3}, {5}, {5, 6}, {7}, {7}, {}});
        Random random = new Random(0x4D4156454EL);
        for (int trial = 0; trial < 12; trial++) {
            int projectCount = 80;
            int[][] downstream = new int[projectCount][];
            for (int project = 0; project < projectCount; project++) {
                List<Integer> children = new ArrayList<>();
                for (int child = project + 1; child < projectCount; child++) {
                    if (random.nextInt(5) == 0) {
                        children.add(child);
                    }
                }
                downstream[project] =
                        children.stream().mapToInt(Integer::intValue).toArray();
            }
            assertWeightsMatchBottomUpOracle(downstream);
        }
    }

    @Test
    void testWarmWeightCacheDoesNotReadDependencyGraphAgain() {
        TestDependencyGraph graph = new TestDependencyGraph(new int[][] {{1, 2}, {3}, {3}, {}});
        SmartProjectComparator warmComparator = new SmartProjectComparator(graph);
        assertEquals(3L, warmComparator.getProjectWeight(graph.projects.get(0)));
        assertEquals(4, graph.downstreamReads.get());

        List<MavenProject> projects = new ArrayList<>(graph.projects);
        Collections.reverse(projects);
        projects.sort(warmComparator.getComparator());
        assertEquals(graph.projects, projects);
        assertEquals(2L, warmComparator.getProjectWeight(graph.projects.get(1)));
        assertEquals(2L, warmComparator.getProjectWeight(graph.projects.get(2)));
        assertEquals(1L, warmComparator.getProjectWeight(graph.projects.get(3)));
        assertEquals(4, graph.downstreamReads.get(), "warm weights must not reread the graph");
    }

    @Test
    void testCyclicDependencyGraphsAreRejected() {
        for (int[][] downstream : new int[][][] {{{0}}, {{1}, {2}, {0}}}) {
            TestDependencyGraph graph = new TestDependencyGraph(downstream);
            SmartProjectComparator cyclicComparator = new SmartProjectComparator(graph);
            IllegalStateException failure = assertTimeoutPreemptively(
                    Duration.ofSeconds(5),
                    () -> assertThrows(
                            IllegalStateException.class,
                            () -> cyclicComparator.getProjectWeight(graph.projects.get(0))));
            assertTrue(failure.getMessage().contains("Cycle detected in reactor dependency graph"));
            assertEquals(downstream.length, graph.downstreamReads.get());
        }
    }

    private static void assertWeightsMatchBottomUpOracle(int[][] downstream) {
        // Fixtures number each prerequisite before its dependents, so this oracle can
        // calculate weights directly in reverse order without recursive traversal or caches.
        long[] expected = new long[downstream.length];
        for (int project = downstream.length - 1; project >= 0; project--) {
            long max = 0L;
            for (int child : downstream[project]) {
                assertTrue(child > project);
                max = Math.max(max, expected[child]);
            }
            expected[project] = 1L + max;
        }

        TestDependencyGraph graph = new TestDependencyGraph(downstream);
        SmartProjectComparator actual = new SmartProjectComparator(graph);
        List<Integer> order = new ArrayList<>();
        for (int project = 0; project < downstream.length; project++) {
            order.add(project);
        }
        Collections.shuffle(order, new Random(0x4D33444147L));
        for (int project : order) {
            assertEquals(expected[project], actual.getProjectWeight(graph.projects.get(project)));
        }
        assertEquals(downstream.length, graph.downstreamReads.get());
    }

    private static final class TestDependencyGraph implements ProjectDependencyGraph {
        private final List<MavenProject> projects;
        private final Map<MavenProject, List<MavenProject>> downstream = new HashMap<>();
        private final AtomicInteger downstreamReads = new AtomicInteger();

        private TestDependencyGraph(int[][] edges) {
            List<MavenProject> nodes = new ArrayList<>();
            for (int i = 0; i < edges.length; i++) {
                MavenProject project = new MavenProject();
                project.setGroupId("org.apache.maven.comparator.test");
                project.setArtifactId("node-" + i);
                project.setVersion("1");
                nodes.add(project);
            }
            projects = List.copyOf(nodes);
            for (int i = 0; i < edges.length; i++) {
                List<MavenProject> children = new ArrayList<>();
                for (int child : edges[i]) {
                    children.add(projects.get(child));
                }
                downstream.put(projects.get(i), List.copyOf(children));
            }
        }

        @Override
        public List<MavenProject> getAllProjects() {
            return projects;
        }

        @Override
        public List<MavenProject> getSortedProjects() {
            return projects;
        }

        @Override
        public List<MavenProject> getDownstreamProjects(MavenProject project, boolean transitive) {
            if (transitive) {
                throw new UnsupportedOperationException("comparator fixture exposes immediate downstream edges only");
            }
            downstreamReads.incrementAndGet();
            return downstream.getOrDefault(project, List.of());
        }

        @Override
        public List<MavenProject> getUpstreamProjects(MavenProject project, boolean transitive) {
            throw new UnsupportedOperationException("comparator fixture does not expose upstream queries");
        }
    }
}
