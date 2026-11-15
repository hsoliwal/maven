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
// Exercise immutable and shared dependency-view ownership through the original helper.

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.lifecycle.internal.ProjectBuildList;
import org.apache.maven.lifecycle.internal.stub.ProjectDependencyGraphStub;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class ConcurrencyDependencyGraphTest {

    @Test
    void testGraph() throws Exception {

        ProjectBuildList projectBuildList =
                ProjectDependencyGraphStub.getProjectBuildList(ProjectDependencyGraphStub.getMavenSession());

        ProjectDependencyGraph projectDependencyGraph = new ProjectDependencyGraphStub();

        ConcurrencyDependencyGraph graph = new ConcurrencyDependencyGraph(projectBuildList, projectDependencyGraph);

        // start
        assertEquals(0, graph.getFinishedProjects().size());
        assertEquals(6, graph.getNumberOfBuilds());

        List<MavenProject> rootSchedulableBuilds = graph.getRootSchedulableBuilds();
        // only Project.A has no dependencies
        assertEquals(1, rootSchedulableBuilds.size());
        assertEquals(
                ProjectDependencyGraphStub.A, rootSchedulableBuilds.iterator().next());
        // double check A deps
        List<MavenProject> dependenciesA = graph.getDependencies(ProjectDependencyGraphStub.A);
        assertEquals(0, dependenciesA.size());

        assertEquals(6, graph.getUnfinishedProjects().size());

        List<MavenProject> schedulableNewProcesses = graph.markAsFinished(ProjectDependencyGraphStub.A);
        // expect Project B, C
        assertEquals(2, schedulableNewProcesses.size());
        assertEquals(1, graph.getFinishedProjects().size());

        graph.markAsFinished(ProjectDependencyGraphStub.A);
        // still only  A
        assertEquals(1, graph.getFinishedProjects().size());

        Set<MavenProject> unfinishedProjects = graph.getUnfinishedProjects();
        assertEquals(5, unfinishedProjects.size());

        graph.markAsFinished(schedulableNewProcesses.get(0));
        assertEquals(2, graph.getFinishedProjects().size());
        assertEquals(4, graph.getUnfinishedProjects().size());

        List<MavenProject> dependenciesC = graph.getDependencies(ProjectDependencyGraphStub.C);
        // C depends only on A
        assertEquals(1, dependenciesC.size());

        List<MavenProject> dependenciesX = graph.getDependencies(ProjectDependencyGraphStub.X);
        // X depends only on B and C
        assertEquals(2, dependenciesX.size());

        List<MavenProject> activeDependenciesC = graph.getActiveDependencies(ProjectDependencyGraphStub.C);
        // A already finished
        assertEquals(0, activeDependenciesC.size());

        List<MavenProject> activeDependenciesX = graph.getActiveDependencies(ProjectDependencyGraphStub.X);
        // waiting for C
        assertEquals(1, activeDependenciesX.size());
    }

    @Test
    void testActiveDependenciesAcceptImmutableViews() {
        List<MavenProject> dependencies = List.of(ProjectDependencyGraphStub.B, ProjectDependencyGraphStub.C);
        ConcurrencyDependencyGraph graph = graphWithDependencies(dependencies);
        graph.getFinishedProjects().add(ProjectDependencyGraphStub.B);

        List<MavenProject> active = graph.getActiveDependencies(ProjectDependencyGraphStub.X);
        assertEquals(List.of(ProjectDependencyGraphStub.C), active);
        assertEquals(List.of(ProjectDependencyGraphStub.B, ProjectDependencyGraphStub.C), dependencies);
        active.clear();
        assertEquals(List.of(ProjectDependencyGraphStub.C), graph.getActiveDependencies(ProjectDependencyGraphStub.X));
    }

    @Test
    void testActiveDependenciesPreserveSharedSingleton() {
        List<MavenProject> dependencies = new ArrayList<>(List.of(ProjectDependencyGraphStub.A));
        ConcurrencyDependencyGraph graph = graphWithDependencies(dependencies);
        graph.getFinishedProjects().add(ProjectDependencyGraphStub.A);

        assertEquals(List.of(), graph.getActiveDependencies(ProjectDependencyGraphStub.C));
        assertEquals(List.of(ProjectDependencyGraphStub.A), dependencies);
        assertEquals(List.of(ProjectDependencyGraphStub.A), graph.getDependencies(ProjectDependencyGraphStub.C));
    }

    @Test
    void testActiveDependenciesAreOrderedIndependentSnapshots() {
        List<MavenProject> dependencies = new ArrayList<>(
                List.of(ProjectDependencyGraphStub.C, ProjectDependencyGraphStub.B, ProjectDependencyGraphStub.A));
        ConcurrencyDependencyGraph graph = graphWithDependencies(dependencies);
        graph.getFinishedProjects().add(ProjectDependencyGraphStub.B);

        List<MavenProject> first = graph.getActiveDependencies(ProjectDependencyGraphStub.X);
        List<MavenProject> second = graph.getActiveDependencies(ProjectDependencyGraphStub.X);
        assertEquals(List.of(ProjectDependencyGraphStub.C, ProjectDependencyGraphStub.A), first);
        assertEquals(first, second);
        assertNotSame(first, second);
        first.clear();
        assertEquals(List.of(ProjectDependencyGraphStub.C, ProjectDependencyGraphStub.A), second);
        assertEquals(
                List.of(ProjectDependencyGraphStub.C, ProjectDependencyGraphStub.B, ProjectDependencyGraphStub.A),
                dependencies);
    }

    @Test
    void testEmptyActiveDependenciesRemainCallerOwned() {
        ConcurrencyDependencyGraph graph = graphWithDependencies(List.of());
        List<MavenProject> active = graph.getActiveDependencies(ProjectDependencyGraphStub.A);
        active.add(ProjectDependencyGraphStub.B);
        assertEquals(List.of(), graph.getActiveDependencies(ProjectDependencyGraphStub.A));
    }

    private static ConcurrencyDependencyGraph graphWithDependencies(List<MavenProject> dependencies) {
        ProjectDependencyGraph source = new ProjectDependencyGraphStub() {
            @Override
            public List<MavenProject> getUpstreamProjects(MavenProject project, boolean transitive) {
                return dependencies;
            }
        };
        return new ConcurrencyDependencyGraph(new ProjectBuildList(List.of()), source);
    }
}
