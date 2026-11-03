/*
 * SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.apache.maven.execution;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.toolchain.model.ToolchainModel;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real Maven request contracts; no SDK module is needed for these copy checks. */
@SuppressWarnings("deprecation")
class M3ExecutionRequestCopyTest {
    @TempDir
    Path directory;

    @Test
    void preservesReactorPolicyAndExactActivationStrength() {
        var original = new DefaultMavenExecutionRequest();
        original.setGoals(List.of("verify", "site"));
        original.setBaseDirectory(directory.toFile());
        original.setRootDirectory(directory);
        original.setTopDirectory(directory.resolve("module"));
        original.setMultiModuleProjectDirectory(directory.toFile());
        original.setPom(directory.resolve("pom.xml").toFile());
        original.setRecursive(false);
        original.setResume(true);
        original.setResumeFrom(":resumed");
        original.setMakeBehavior(MavenExecutionRequest.REACTOR_MAKE_BOTH);
        original.setReactorFailureBehavior(MavenExecutionRequest.REACTOR_FAIL_AT_END);
        original.setBuilderId("multithreaded");
        original.setDegreeOfConcurrency(3);
        original.setArtifactsUpdatePolicy("always");
        original.setMetadataUpdatePolicy("never");
        original.setStartInstant(Instant.parse("2026-10-21T10:00:00.123456789Z"));
        var profiles = original.getProfileActivation();
        profiles.activateRequiredProfile("required");
        profiles.activateOptionalProfile("optional");
        profiles.deactivateRequiredProfile("excluded");
        profiles.deactivateOptionalProfile("optional-excluded");
        var projects = original.getProjectActivation();
        projects.activateRequiredProject(":required");
        projects.activateOptionalProject(":optional");
        projects.activateOptionalProjectNonRecursive(":leaf");
        projects.deactivateRequiredProject(":excluded");
        projects.deactivateOptionalProject(":optional-excluded");

        var copy = DefaultMavenExecutionRequest.copy(original);
        assertNotSame(original, copy);
        assertEquals(original.getGoals(), copy.getGoals());
        assertEquals(original.getBaseDirectory(), copy.getBaseDirectory());
        assertEquals(original.getRootDirectory(), copy.getRootDirectory());
        assertEquals(original.getTopDirectory(), copy.getTopDirectory());
        assertEquals(original.getMultiModuleProjectDirectory(), copy.getMultiModuleProjectDirectory());
        assertEquals(original.getPom(), copy.getPom());
        assertFalse(copy.isRecursive());
        assertTrue(copy.isResume());
        assertEquals(":resumed", copy.getResumeFrom());
        assertEquals(MavenExecutionRequest.REACTOR_MAKE_BOTH, copy.getMakeBehavior());
        assertEquals(MavenExecutionRequest.REACTOR_FAIL_AT_END, copy.getReactorFailureBehavior());
        assertEquals("multithreaded", copy.getBuilderId());
        assertEquals(3, copy.getDegreeOfConcurrency());
        assertEquals("always", copy.getArtifactsUpdatePolicy());
        assertEquals("never", copy.getMetadataUpdatePolicy());
        assertEquals(original.getStartInstant(), copy.getStartInstant());
        assertEquals(
                profiles.getRequiredActiveProfileIds(),
                copy.getProfileActivation().getRequiredActiveProfileIds());
        assertEquals(
                profiles.getOptionalActiveProfileIds(),
                copy.getProfileActivation().getOptionalActiveProfileIds());
        assertEquals(
                profiles.getRequiredInactiveProfileIds(),
                copy.getProfileActivation().getRequiredInactiveProfileIds());
        assertEquals(
                profiles.getOptionalInactiveProfileIds(),
                copy.getProfileActivation().getOptionalInactiveProfileIds());
        assertEquals(projects.getActivations(), copy.getProjectActivation().getActivations());
        assertNotSame(projects, copy.getProjectActivation());
        copy.getProjectActivation().activateRequiredProject(":copy-only");
        copy.getProfileActivation().activateRequiredProfile("copy-only");
        assertFalse(projects.getRequiredActiveProjectSelectors().contains(":copy-only"));
        assertFalse(profiles.getRequiredActiveProfileIds().contains("copy-only"));
    }

    @Test
    void ownsMutableRequestContainersWithoutInventingCopiesOfServiceHandles() {
        var original = new DefaultMavenExecutionRequest();
        original.setGoals(List.of("verify"));
        original.getUserProperties().setProperty("user", "old");
        original.getSystemProperties().setProperty("system", "old");
        original.getData().put("context", "old");
        var toolchain = new ToolchainModel();
        original.setToolchains(new HashMap<>(Map.of("jdk", new ArrayList<>(List.of(toolchain)))));
        var copy = DefaultMavenExecutionRequest.copy(original);
        assertSame(original.getRepositoryCache(), copy.getRepositoryCache());
        assertSame(toolchain, copy.getToolchains().get("jdk").get(0));
        assertNotSame(original.getToolchains(), copy.getToolchains());
        assertNotSame(original.getToolchains().get("jdk"), copy.getToolchains().get("jdk"));
        copy.getGoals().add("site");
        copy.getUserProperties().setProperty("user", "new");
        copy.getSystemProperties().setProperty("system", "new");
        copy.getData().put("context", "new");
        copy.getToolchains().get("jdk").clear();
        assertEquals(List.of("verify"), original.getGoals());
        assertEquals("old", original.getUserProperties().getProperty("user"));
        assertEquals("old", original.getSystemProperties().getProperty("system"));
        assertEquals("old", original.getData().get("context"));
        assertEquals(List.of(toolchain), original.getToolchains().get("jdk"));
    }

    @Test
    void preservesConfiguredProjectBuildingFlagsInANewContainer() {
        var original = new DefaultMavenExecutionRequest();
        var configured = new DefaultProjectBuildingRequest();
        var repositorySession = new DefaultRepositorySystemSession(handle -> false);
        configured.setRepositorySession(repositorySession);
        configured.setValidationLevel(20);
        configured.setBuildStartInstant(Instant.parse("2026-10-21T10:00:00.123456789Z"));
        configured.setProcessPlugins(false);
        configured.setResolveDependencies(true);
        configured.setActiveProfileIds(List.of("model-profile"));
        configured.getUserProperties().setProperty("model.only", "retained");
        original.setProjectBuildingConfiguration(configured);

        var copy = DefaultMavenExecutionRequest.copy(original).getProjectBuildingRequest();
        assertNotSame(configured, copy);
        assertEquals(20, copy.getValidationLevel());
        assertEquals(configured.getBuildStartInstant(), copy.getBuildStartInstant());
        assertFalse(copy.isProcessPlugins());
        assertTrue(copy.isResolveDependencies());
        assertEquals(List.of("model-profile"), copy.getActiveProfileIds());
        assertEquals("retained", copy.getUserProperties().getProperty("model.only"));
        assertSame(
                repositorySession, copy.getRepositorySession()); // The watch execution loop replaces this per session.
        copy.getActiveProfileIds().add("copy-only");
        copy.getUserProperties().setProperty("model.only", "changed");
        assertEquals(List.of("model-profile"), configured.getActiveProfileIds());
        assertEquals("retained", configured.getUserProperties().getProperty("model.only"));
    }

    @Test
    void copyDoesNotPrematurelyMaterializeLazyProjectConfiguration() {
        var original = new DefaultMavenExecutionRequest();
        original.getUserProperties().setProperty("version", "first");
        var first = DefaultMavenExecutionRequest.copy(original);
        original.getUserProperties().setProperty("version", "second");
        var second = DefaultMavenExecutionRequest.copy(original);
        assertEquals(
                "first", first.getProjectBuildingRequest().getUserProperties().getProperty("version"));
        assertEquals(
                "second", second.getProjectBuildingRequest().getUserProperties().getProperty("version"));
        assertThrows(IllegalStateException.class, second::getRootDirectory);
    }
}
