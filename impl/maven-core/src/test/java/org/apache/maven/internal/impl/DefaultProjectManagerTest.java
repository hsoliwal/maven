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
package org.apache.maven.internal.impl;

// Modified 2026-10-21 by Hitesh Soliwal and Contributors to the Synexia Project:
// Expose session-owned active reactor queries through the existing ProjectManager API.

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

import org.apache.maven.api.Language;
import org.apache.maven.api.Packaging;
import org.apache.maven.api.ProducedArtifact;
import org.apache.maven.api.Project;
import org.apache.maven.api.ProjectScope;
import org.apache.maven.api.SourceRoot;
import org.apache.maven.api.model.Source;
import org.apache.maven.api.services.ArtifactManager;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.graph.DefaultGraphBuilder;
import org.apache.maven.impl.DefaultModelVersionParser;
import org.apache.maven.impl.DefaultSourceRoot;
import org.apache.maven.impl.DefaultVersionParser;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.util.version.GenericVersionScheme;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class DefaultProjectManagerTest {

    private DefaultProjectManager projectManager;

    private Project project;

    private ProducedArtifact artifact;

    private Path artifactPath;

    @Test
    void addGeneratedSourceRoots() {
        InternalMavenSession session = Mockito.mock(InternalMavenSession.class);
        MavenProject mavenProject = new MavenProject();
        mavenProject.setFile(
                Path.of("target", "project", "pom.xml").toAbsolutePath().toFile());
        Project project = new DefaultProject(session, mavenProject);
        DefaultProjectManager projectManager = new DefaultProjectManager(session, Mockito.mock(ArtifactManager.class));

        projectManager.addSourceRoot(
                project, ProjectScope.MAIN, Language.JAVA_FAMILY, Path.of("target/generated-sources/main"), true);
        projectManager.addSourceRoot(
                project, ProjectScope.TEST, Language.JAVA_FAMILY, Path.of("target/generated-test-sources/test"), true);
        projectManager.addSourceRoot(project, ProjectScope.MAIN, Language.JAVA_FAMILY, Path.of("src/main/java"));
        projectManager.addSourceRoot(
                project, ProjectScope.MAIN, Language.JAVA_FAMILY, Path.of("target/generated-sources/main"), false);

        List<SourceRoot> roots = projectManager.getSourceRoots(project).stream().toList();
        assertEquals(3, roots.size());
        assertTrue(roots.get(0).generated());
        assertTrue(roots.get(1).generated());
        assertFalse(roots.get(2).generated());
    }

    @Test
    void attachArtifact() {
        InternalMavenSession session = Mockito.mock(InternalMavenSession.class);
        ArtifactManager artifactManager = Mockito.mock(ArtifactManager.class);
        MavenProject mavenProject = new MavenProject();
        project = new DefaultProject(session, mavenProject);
        artifact = Mockito.mock(ProducedArtifact.class);
        artifactPath = Paths.get("");
        DefaultVersionParser versionParser =
                new DefaultVersionParser(new DefaultModelVersionParser(new GenericVersionScheme()));
        projectManager = new DefaultProjectManager(session, artifactManager);

        mavenProject.setGroupId("myGroup");
        mavenProject.setArtifactId("myArtifact");
        mavenProject.setVersion("1.0-SNAPSHOT");
        when(artifact.getGroupId()).thenReturn("myGroup");
        when(artifact.getArtifactId()).thenReturn("myArtifact");
        when(artifact.getBaseVersion()).thenReturn(versionParser.parseVersion("1.0-SNAPSHOT"));
        projectManager.attachArtifact(project, artifact, artifactPath);

        // Verify that an exception is thrown when the artifactId differs
        when(artifact.getArtifactId()).thenReturn("anotherArtifact");
        assertExceptionMessageContains("myGroup:myArtifact:1.0-SNAPSHOT", "myGroup:anotherArtifact:1.0-SNAPSHOT");

        // Add a Java module. It should relax the restriction on artifactId.
        projectManager.addSourceRoot(
                project,
                new DefaultSourceRoot(
                        ProjectScope.MAIN,
                        Language.JAVA_FAMILY,
                        "org.foo.bar",
                        null,
                        Path.of("myProject"),
                        null,
                        null,
                        false,
                        null,
                        true));

        // Verify that we get the same exception when the artifactId does not match the module name
        assertExceptionMessageContains("", "anotherArtifact");

        // Verify that no exception is thrown when the artifactId is the module name
        when(artifact.getArtifactId()).thenReturn("org.foo.bar");
        projectManager.attachArtifact(project, artifact, artifactPath);

        // Verify that an exception is thrown when the groupId differs
        when(artifact.getGroupId()).thenReturn("anotherGroup");
        assertExceptionMessageContains("myGroup:myArtifact:1.0-SNAPSHOT", "anotherGroup:org.foo.bar:1.0-SNAPSHOT");
    }

    @Test
    void ordinaryJarPackagingProducesMainArtifact() {
        InternalMavenSession session = Mockito.mock(InternalMavenSession.class);
        Packaging packaging = Mockito.mock(Packaging.class);
        when(packaging.producesMainArtifact()).thenReturn(true);
        when(session.requirePackaging("jar")).thenReturn(packaging);

        ProducedArtifact pomProduced = Mockito.mock(ProducedArtifact.class);
        ProducedArtifact jarProduced = Mockito.mock(ProducedArtifact.class);
        when(session.getArtifact(
                        Mockito.eq(ProducedArtifact.class),
                        Mockito.argThat(a -> a != null && "pom".equals(a.getExtension()))))
                .thenReturn(pomProduced);
        when(session.getArtifact(
                        Mockito.eq(ProducedArtifact.class),
                        Mockito.argThat(a -> a != null && "jar".equals(a.getExtension()))))
                .thenReturn(jarProduced);

        MavenProject mavenProject = new MavenProject();
        mavenProject.setGroupId("org.foo");
        mavenProject.setArtifactId("bar");
        mavenProject.setVersion("1.0.0");
        mavenProject.setPackaging("jar");
        mavenProject.setFile(
                Path.of("target", "project", "pom.xml").toAbsolutePath().toFile());
        mavenProject.setArtifact(new org.apache.maven.artifact.DefaultArtifact(
                "org.foo", "bar", "1.0.0", "compile", "jar", "", new TestArtifactHandler("jar")));

        Project project = new DefaultProject(session, mavenProject);
        assertTrue(project.packaging().producesMainArtifact());
        assertTrue(project.producesMainArtifact());
        assertSame(project.packaging(), project.packaging());

        List<ProducedArtifact> artifacts = project.getArtifacts();
        assertEquals(2, artifacts.size());
        assertTrue(artifacts.contains(pomProduced));
        assertTrue(artifacts.contains(jarProduced));
        assertTrue(project.mainArtifact().isPresent());

        DefaultProjectManager projectManager = new DefaultProjectManager(session, Mockito.mock(ArtifactManager.class));
        Collection<ProducedArtifact> all = projectManager.getAllArtifacts(project);
        assertEquals(2, all.size());
    }

    @Test
    void pomPackagingDoesNotProduceMainArtifact() {
        InternalMavenSession session = Mockito.mock(InternalMavenSession.class);
        Packaging packaging = Mockito.mock(Packaging.class);
        when(packaging.producesMainArtifact()).thenReturn(false);
        when(session.requirePackaging("pom")).thenReturn(packaging);

        ProducedArtifact pomProduced = Mockito.mock(ProducedArtifact.class);
        when(session.getArtifact(Mockito.eq(ProducedArtifact.class), Mockito.any()))
                .thenReturn(pomProduced);

        MavenProject mavenProject = new MavenProject();
        mavenProject.setGroupId("org.foo");
        mavenProject.setArtifactId("bar-parent");
        mavenProject.setVersion("1.0.0");
        mavenProject.setPackaging("pom");
        mavenProject.setFile(
                Path.of("target", "project", "pom.xml").toAbsolutePath().toFile());
        mavenProject.setArtifact(new org.apache.maven.artifact.DefaultArtifact(
                "org.foo", "bar-parent", "1.0.0", "compile", "pom", "", new TestArtifactHandler("pom")));

        Project project = new DefaultProject(session, mavenProject);
        assertFalse(project.packaging().producesMainArtifact());
        assertFalse(project.producesMainArtifact());
        assertSame(project.packaging(), project.packaging());

        List<ProducedArtifact> artifacts = project.getArtifacts();
        assertEquals(1, artifacts.size());
        assertTrue(artifacts.contains(pomProduced));
        assertFalse(project.mainArtifact().isPresent());
    }

    @Test
    void bomPackagingDoesNotProduceMainArtifact() {
        InternalMavenSession session = Mockito.mock(InternalMavenSession.class);
        Packaging packaging = Mockito.mock(Packaging.class);
        when(packaging.producesMainArtifact()).thenReturn(false);
        when(session.requirePackaging("bom")).thenReturn(packaging);

        ProducedArtifact pomProduced = Mockito.mock(ProducedArtifact.class);
        when(session.getArtifact(Mockito.eq(ProducedArtifact.class), Mockito.any()))
                .thenReturn(pomProduced);

        MavenProject mavenProject = new MavenProject();
        mavenProject.setGroupId("org.foo");
        mavenProject.setArtifactId("bar-bom");
        mavenProject.setVersion("1.0.0");
        mavenProject.setPackaging("bom");
        mavenProject.setFile(
                Path.of("target", "project", "pom.xml").toAbsolutePath().toFile());
        mavenProject.setArtifact(new org.apache.maven.artifact.DefaultArtifact(
                "org.foo", "bar-bom", "1.0.0", "compile", "pom", "", new TestArtifactHandler("pom")));

        Project project = new DefaultProject(session, mavenProject);
        assertFalse(project.packaging().producesMainArtifact());
        assertFalse(project.producesMainArtifact());
        assertSame(project.packaging(), project.packaging());

        List<ProducedArtifact> artifacts = project.getArtifacts();
        assertEquals(1, artifacts.size());
        assertTrue(artifacts.contains(pomProduced));
        assertFalse(project.mainArtifact().isPresent());
    }

    @Test
    void staticModuleSourceHierarchyDoesNotProduceMainArtifact() {
        InternalMavenSession session = Mockito.mock(InternalMavenSession.class);
        Packaging packaging = Mockito.mock(Packaging.class);
        when(packaging.producesMainArtifact()).thenReturn(true);
        when(session.requirePackaging("jar")).thenReturn(packaging);

        ProducedArtifact pomProduced = Mockito.mock(ProducedArtifact.class);
        ProducedArtifact jarProduced = Mockito.mock(ProducedArtifact.class);

        when(session.getArtifact(
                        Mockito.eq(ProducedArtifact.class),
                        Mockito.argThat(a -> a != null && "pom".equals(a.getExtension()))))
                .thenReturn(pomProduced);
        when(session.getArtifact(
                        Mockito.eq(ProducedArtifact.class),
                        Mockito.argThat(a ->
                                a != null && "jar".equals(a.getExtension()) && "myArtifact".equals(a.getArtifactId()))))
                .thenReturn(jarProduced);

        MavenProject mavenProject = new MavenProject();
        mavenProject.setGroupId("myGroup");
        mavenProject.setArtifactId("myArtifact");
        mavenProject.setVersion("1.0-SNAPSHOT");
        mavenProject.setPackaging("jar");
        mavenProject.setFile(
                Path.of("target", "project", "pom.xml").toAbsolutePath().toFile());
        mavenProject.setArtifact(new org.apache.maven.artifact.DefaultArtifact(
                "myGroup", "myArtifact", "1.0-SNAPSHOT", "compile", "jar", "", new TestArtifactHandler("jar")));

        Source source = Source.newBuilder().module("org.foo.bar").build();
        org.apache.maven.api.model.Build modelBuild = org.apache.maven.api.model.Build.newBuilder()
                .sources(List.of(source))
                .build();
        mavenProject.setBuild(new org.apache.maven.model.Build(modelBuild));

        Project project = new DefaultProject(session, mavenProject);

        assertTrue(project.packaging().producesMainArtifact());
        assertFalse(project.producesMainArtifact());
        assertSame(project.packaging(), project.packaging());

        List<ProducedArtifact> artifacts = project.getArtifacts();
        assertEquals(1, artifacts.size());
        assertTrue(artifacts.contains(pomProduced));
        assertFalse(artifacts.contains(jarProduced));
        assertFalse(project.mainArtifact().isPresent());

        DefaultProjectManager projectManager = new DefaultProjectManager(session, Mockito.mock(ArtifactManager.class));
        Collection<ProducedArtifact> all = projectManager.getAllArtifacts(project);
        assertEquals(1, all.size());
        assertTrue(all.contains(pomProduced));
        assertFalse(all.contains(jarProduced));
    }

    @Test
    void moduleSourceHierarchyDoesNotProduceMainArtifactAndAttachesModuleJars() {
        InternalMavenSession session = Mockito.mock(InternalMavenSession.class);
        Packaging packaging = Mockito.mock(Packaging.class);
        when(packaging.producesMainArtifact()).thenReturn(true);
        when(session.requirePackaging("jar")).thenReturn(packaging);

        DefaultVersionParser versionParser =
                new DefaultVersionParser(new DefaultModelVersionParser(new GenericVersionScheme()));
        when(session.parseVersion("1.0-SNAPSHOT")).thenReturn(versionParser.parseVersion("1.0-SNAPSHOT"));

        ProducedArtifact pomProduced = Mockito.mock(ProducedArtifact.class);
        ProducedArtifact jarProduced = Mockito.mock(ProducedArtifact.class);
        ProducedArtifact moduleJarProduced = Mockito.mock(ProducedArtifact.class);

        when(session.getArtifact(
                        Mockito.eq(ProducedArtifact.class),
                        Mockito.argThat(a -> a != null && "pom".equals(a.getExtension()))))
                .thenReturn(pomProduced);
        when(session.getArtifact(
                        Mockito.eq(ProducedArtifact.class),
                        Mockito.argThat(a ->
                                a != null && "jar".equals(a.getExtension()) && "myArtifact".equals(a.getArtifactId()))))
                .thenReturn(jarProduced);
        when(session.getArtifact(
                        Mockito.eq(ProducedArtifact.class),
                        Mockito.argThat(a -> a != null && "org.foo.bar".equals(a.getArtifactId()))))
                .thenReturn(moduleJarProduced);

        MavenProject mavenProject = new MavenProject();
        mavenProject.setGroupId("myGroup");
        mavenProject.setArtifactId("myArtifact");
        mavenProject.setVersion("1.0-SNAPSHOT");
        mavenProject.setPackaging("jar");
        mavenProject.setFile(
                Path.of("target", "project", "pom.xml").toAbsolutePath().toFile());
        org.apache.maven.artifact.Artifact legacyArtifact = new org.apache.maven.artifact.DefaultArtifact(
                "myGroup", "myArtifact", "1.0-SNAPSHOT", "compile", "jar", "", new TestArtifactHandler("jar"));
        mavenProject.setArtifact(legacyArtifact);

        Project project = new DefaultProject(session, mavenProject);
        ArtifactManager artifactManager = Mockito.mock(ArtifactManager.class);
        DefaultProjectManager projectManager = new DefaultProjectManager(session, artifactManager);

        // Before adding module source root: standard JAR produces main artifact
        assertTrue(project.packaging().producesMainArtifact());
        assertTrue(project.producesMainArtifact());
        assertSame(project.packaging(), project.packaging());
        assertEquals(2, project.getArtifacts().size());

        // Add module source root
        projectManager.addSourceRoot(
                project,
                new DefaultSourceRoot(
                        ProjectScope.MAIN,
                        Language.JAVA_FAMILY,
                        "org.foo.bar",
                        null,
                        Path.of("myProject"),
                        null,
                        null,
                        false,
                        null,
                        true));

        // After adding module source hierarchy: project does not produce main artifact, but packaging descriptor still
        // does
        assertTrue(project.packaging().producesMainArtifact());
        assertFalse(project.producesMainArtifact());
        assertSame(project.packaging(), project.packaging());

        // project.getArtifacts() contains ONLY POM artifact (directory is NOT exposed as main artifact)
        List<ProducedArtifact> artifacts = project.getArtifacts();
        assertEquals(1, artifacts.size());
        assertTrue(artifacts.contains(pomProduced));
        assertFalse(artifacts.contains(jarProduced));
        assertFalse(project.mainArtifact().isPresent());

        // Legacy MavenProject.getArtifact() remains intact for backward compatibility
        assertEquals(legacyArtifact, mavenProject.getArtifact());

        // Attach module JAR
        ProducedArtifact attachedModuleJar = Mockito.mock(ProducedArtifact.class);
        when(attachedModuleJar.getGroupId()).thenReturn("myGroup");
        when(attachedModuleJar.getArtifactId()).thenReturn("org.foo.bar");
        when(attachedModuleJar.getBaseVersion()).thenReturn(versionParser.parseVersion("1.0-SNAPSHOT"));
        when(attachedModuleJar.getExtension()).thenReturn("jar");
        when(attachedModuleJar.getClassifier()).thenReturn("");
        when(session.toArtifact(attachedModuleJar))
                .thenReturn(new org.eclipse.aether.artifact.DefaultArtifact(
                        "myGroup", "org.foo.bar", "", "jar", "1.0-SNAPSHOT"));
        projectManager.attachArtifact(project, attachedModuleJar, Path.of("target/org.foo.bar-1.0-SNAPSHOT.jar"));

        // All artifacts includes POM and attached module JAR, without the directory main artifact
        Collection<ProducedArtifact> allArtifacts = projectManager.getAllArtifacts(project);
        assertEquals(2, allArtifacts.size());
        assertTrue(allArtifacts.contains(pomProduced));
        assertTrue(allArtifacts.contains(moduleJarProduced));
        assertFalse(allArtifacts.contains(jarProduced));
    }

    @Test
    void reactorQueriesPreserveDirectionTransitivityBuildOrderAndImmutability() {
        MavenProject base = reactorProject("base");
        MavenProject right = reactorProject("right", base);
        MavenProject left = reactorProject("left", base);
        MavenProject leaf = reactorProject("leaf", left, right);
        MavenProject unrelated = reactorProject("unrelated");
        List<MavenProject> all = List.of(base, right, left, leaf, unrelated);
        ReactorFixture fixture = reactor(all, all);
        Project seed = fixture.session().getProject(base);
        Project end = fixture.session().getProject(leaf);
        var graph = fixture.maven().getProjectDependencyGraph();
        List<Project> direct = fixture.manager().getDownstreamProjects(seed, false);
        List<Project> transitive = fixture.manager().getDownstreamProjects(seed, true);
        assertEquals(List.of("right", "left"), reactorIds(direct));
        assertEquals(List.of("right", "left", "leaf"), reactorIds(transitive));
        assertEquals(List.of("right", "left"), reactorIds(fixture.manager().getUpstreamProjects(end, false)));
        assertEquals(
                List.of("base", "right", "left"), reactorIds(fixture.manager().getUpstreamProjects(end, true)));
        assertTrue(fixture.manager().getUpstreamProjects(seed, true).isEmpty());
        assertTrue(fixture.manager().getDownstreamProjects(end, true).isEmpty());
        assertSame(fixture.session().getProject(right), direct.get(0));
        assertThrows(UnsupportedOperationException.class, () -> direct.add(seed));
        assertThrows(UnsupportedOperationException.class, transitive::clear);
        assertSame(graph, fixture.maven().getProjectDependencyGraph());
        assertEquals(all, fixture.maven().getProjects());
        assertEquals(all, fixture.maven().getAllProjects());
    }

    @Test
    void reactorQueriesRetainFilteredDirectRelationshipsAndRefuseInactiveSeeds() {
        MavenProject base = reactorProject("base");
        MavenProject excluded = reactorProject("excluded", base);
        MavenProject leaf = reactorProject("leaf", excluded);
        ReactorFixture fixture = reactor(List.of(base, excluded, leaf), List.of(base, leaf));
        Project seed = fixture.session().getProject(base);
        Project end = fixture.session().getProject(leaf);
        for (boolean transitive : List.of(false, true)) {
            assertEquals(List.of("leaf"), reactorIds(fixture.manager().getDownstreamProjects(seed, transitive)));
            assertEquals(List.of("base"), reactorIds(fixture.manager().getUpstreamProjects(end, transitive)));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.manager().getDownstreamProjects(fixture.session().getProject(excluded), true));
        assertEquals(List.of(base, leaf), fixture.maven().getProjects());
        assertEquals(List.of(base, excluded, leaf), fixture.maven().getAllProjects());
    }

    @Test
    void reactorQueriesRetainOriginalPluginOrderingEdges() {
        MavenProject tool = reactorProject("reactor-tool");
        MavenProject consumer = reactorProject("consumer");
        org.apache.maven.model.Plugin plugin = new org.apache.maven.model.Plugin();
        plugin.setGroupId(tool.getGroupId());
        plugin.setArtifactId(tool.getArtifactId());
        plugin.setVersion(tool.getVersion());
        org.apache.maven.model.Build build = new org.apache.maven.model.Build();
        build.addPlugin(plugin);
        consumer.setBuild(build);
        ReactorFixture fixture = reactor(List.of(consumer, tool), List.of(consumer, tool));
        assertEquals(
                List.of("consumer"),
                reactorIds(fixture.manager()
                        .getDownstreamProjects(fixture.session().getProject(tool), false)));
        assertEquals(
                List.of("reactor-tool"),
                reactorIds(
                        fixture.manager().getUpstreamProjects(fixture.session().getProject(consumer), false)));
    }

    @Test
    void reactorQueriesRejectForeignSameCoordinatesAndTemporaryProjectClones() {
        MavenProject base = reactorProject("base");
        ReactorFixture fixture = reactor(List.of(base), List.of(base));
        MavenProject foreign = reactorProject("base");
        ReactorFixture other = reactor(List.of(foreign), List.of(foreign));
        Project otherGeneration = other.session().getProject(foreign);
        assertThrows(
                IllegalArgumentException.class, () -> fixture.manager().getUpstreamProjects(otherGeneration, true));
        Project clone = new DefaultProject(fixture.session(), foreign);
        assertThrows(IllegalArgumentException.class, () -> fixture.manager().getDownstreamProjects(clone, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.manager().getDownstreamProjects(Mockito.mock(Project.class), true));
        assertThrows(NullPointerException.class, () -> fixture.manager().getUpstreamProjects(null, true));
    }

    @Test
    void reactorQueriesAcceptFacadesOfTheSameNativeSessionAndObserveCurrentGraph() {
        MavenProject base = reactorProject("base");
        MavenProject first = reactorProject("first", base);
        ReactorFixture fixture = reactor(List.of(base, first), List.of(base, first));
        DefaultSession derived = reactorApiSession(fixture.maven());
        Project seed = derived.getProject(base);
        List<Project> old = fixture.manager().getDownstreamProjects(seed, true);
        assertEquals(List.of("first"), reactorIds(old));
        MavenProject replacement = reactorProject("replacement", base);
        fixture.maven().setAllProjects(List.of(base, replacement));
        fixture.maven().setProjects(List.of(base, replacement));
        installReactorGraph(fixture.maven());
        assertEquals(List.of("replacement"), reactorIds(fixture.manager().getDownstreamProjects(seed, true)));
        assertEquals(List.of("first"), reactorIds(old));
        assertThrows(IllegalArgumentException.class, () -> fixture.manager().getUpstreamProjects(old.get(0), true));
    }

    @Test
    void reactorQueriesDistinguishMissingGraphFromAnEmptyClosure() {
        MavenProject base = reactorProject("base");
        ReactorFixture fixture = reactor(List.of(base), List.of(base));
        Project seed = fixture.session().getProject(base);
        assertTrue(fixture.manager().getDownstreamProjects(seed, true).isEmpty());
        fixture.maven().setProjectDependencyGraph(null);
        assertThrows(IllegalStateException.class, () -> fixture.manager().getDownstreamProjects(seed, true));
        assertThrows(IllegalStateException.class, () -> fixture.manager().getUpstreamProjects(seed, false));
    }

    @Test
    void alternateProjectManagerDefaultsRefuseUnsupportedReactorQueries() {
        ProjectManager alternate = Mockito.mock(ProjectManager.class, Mockito.CALLS_REAL_METHODS);
        Project seed = Mockito.mock(Project.class);
        assertThrows(UnsupportedOperationException.class, () -> alternate.getUpstreamProjects(seed, true));
        assertThrows(UnsupportedOperationException.class, () -> alternate.getDownstreamProjects(seed, false));
    }

    private static List<String> reactorIds(List<Project> projects) {
        return projects.stream().map(Project::artifactId).toList();
    }

    private static MavenProject reactorProject(String name, MavenProject... dependencies) {
        MavenProject project = new MavenProject();
        project.setGroupId("org.apache.maven.reactor.test");
        project.setArtifactId(name);
        project.setVersion("1");
        project.setFile(Path.of("target", "reactor-query", name, "pom.xml")
                .toAbsolutePath()
                .toFile());
        List<org.apache.maven.model.Dependency> edges = new java.util.ArrayList<>();
        for (MavenProject dependency : dependencies) {
            org.apache.maven.model.Dependency edge = new org.apache.maven.model.Dependency();
            edge.setGroupId(dependency.getGroupId());
            edge.setArtifactId(dependency.getArtifactId());
            edge.setVersion(dependency.getVersion());
            edges.add(edge);
        }
        project.setDependencies(edges);
        return project;
    }

    private static ReactorFixture reactor(List<MavenProject> all, List<MavenProject> selected) {
        MavenSession maven = new MavenSession(
                null, new DefaultRepositorySystemSession(h -> false), new DefaultMavenExecutionRequest(), null);
        maven.setAllProjects(all);
        maven.setProjects(selected);
        installReactorGraph(maven);
        DefaultSession api = reactorApiSession(maven);
        return new ReactorFixture(maven, api, new DefaultProjectManager(api, Mockito.mock(ArtifactManager.class)));
    }

    private static void installReactorGraph(MavenSession maven) {
        // The original builder constructs the actual filtered graph. No graph is mocked.
        var result = new DefaultGraphBuilder().build(maven);
        assertFalse(result.hasErrors());
        maven.setProjectDependencyGraph(result.get());
    }

    private static DefaultSession reactorApiSession(MavenSession maven) {
        Packaging packaging = Mockito.mock(Packaging.class);
        return new DefaultSession(maven, Mockito.mock(RepositorySystem.class), List.of(), null, null, null) {
            @Override
            public Packaging requirePackaging(String id) {
                return packaging;
            }
        };
    }

    private record ReactorFixture(MavenSession maven, DefaultSession session, DefaultProjectManager manager) {}

    /**
     * Verifies that {@code projectManager.attachArtifact(…)} throws an exception,
     * and that the exception message contains the expected and actual <abbr>GAV</abbr>.
     *
     * @param expectedGAV the expected <abbr>GAV</abbr> that the exception message should contain
     * @param actualGAV the actual <abbr>GAV</abbr> that the exception message should contain
     */
    private void assertExceptionMessageContains(String expectedGAV, String actualGAV) {
        String cause = assertThrows(
                        IllegalArgumentException.class,
                        () -> projectManager.attachArtifact(project, artifact, artifactPath))
                .getMessage();
        Supplier<String> message = () ->
                String.format("The exception message does not contain the expected GAV. Message was:%n%s%n", cause);

        assertTrue(cause.contains(expectedGAV), message);
        assertTrue(cause.contains(actualGAV), message);
    }
}
