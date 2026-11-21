/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.apache.maven.api.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.maven.api.LifecyclePlan;
import org.apache.maven.api.MojoExecution;
import org.apache.maven.api.Session;
import org.apache.maven.api.Toolchain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Bounded, additive toolchain selection and actual planned-Mojo identity tests. */
class BuildToolchainPlanTest {
    private final Session session = mock(Session.class);

    private static BuildToolchainPlan.Stage stage(
            String id, BuildToolchainPlan.Role role, String type, String tool, boolean required, String... after) {
        return new BuildToolchainPlan.Stage(id, role, type, Map.of(), tool, List.of(after), required);
    }

    private static final class Manager implements ToolchainManager {
        private final Map<String, List<Toolchain>> providers = new HashMap<>();
        private final Map<String, Toolchain> selected = new HashMap<>();

        @Override
        public List<Toolchain> getToolchains(Session session, String type, Map<String, String> requirements) {
            return providers.getOrDefault(type, List.of());
        }

        @Override
        public Optional<Toolchain> getToolchainFromBuildContext(Session session, String type) {
            return Optional.ofNullable(selected.get(type));
        }

        @Override
        public void storeToolchainToBuildContext(Session session, Toolchain toolchain) {
            throw new AssertionError("selection must not alter build context");
        }
    }

    @Test
    void selectsOriginalProviderAndOrdersStagesWithoutExecutingPlugins() {
        Manager manager = new Manager();
        Toolchain jdk = mock(Toolchain.class);
        when(jdk.getType()).thenReturn("jdk");
        when(jdk.matchesRequirements(anyMap())).thenReturn(true);
        when(jdk.findTool("javac")).thenReturn("/jdk/bin/javac");
        when(jdk.findTool("java")).thenReturn("/jdk/bin/java");
        when(jdk.findTool("jar")).thenReturn("/jdk/bin/jar");
        manager.selected.put("jdk", jdk);

        var lint = stage("lint", BuildToolchainPlan.Role.LINT, "", "", false);
        var compile = stage("compile", BuildToolchainPlan.Role.COMPILE, "jdk", "javac", true, "lint");
        var analysis = stage("analysis", BuildToolchainPlan.Role.STATIC_ANALYSIS, "", "", false, "compile");
        var test = stage("test", BuildToolchainPlan.Role.TEST, "jdk", "java", true, "compile");
        var pack = stage("package", BuildToolchainPlan.Role.PACKAGE, "jdk", "jar", true, "analysis", "test");

        BuildToolchainPlan plan = manager.planBuildToolchain(session, List.of(pack, lint, compile, test, analysis));
        assertEquals(List.of("lint", "compile", "test", "analysis", "package"),
                plan.stages().stream().map(BuildToolchainPlan.Stage::id).toList());
        assertTrue(plan.allRequiredToolsSelected());
        assertEquals(BuildToolchainPlan.ToolStatus.NOT_REQUESTED, plan.selections().get(0).status());
        assertSame(jdk, plan.selections().get(1).toolchain().orElseThrow());
        assertEquals("/jdk/bin/javac", plan.selections().get(1).command().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> plan.stages().clear());
    }

    @Test
    void rejectsMissingProvidersAndDistinguishesMissingExecutable() {
        Manager manager = new Manager();
        var absent = manager.planBuildToolchain(
                session, List.of(stage("analysis", BuildToolchainPlan.Role.STATIC_ANALYSIS, "pmd", "", true)));
        assertEquals(BuildToolchainPlan.ToolStatus.MISSING_TOOLCHAIN, absent.selections().get(0).status());
        assertFalse(absent.allRequiredToolsSelected());

        Toolchain cdt = mock(Toolchain.class);
        when(cdt.getType()).thenReturn("cdt");
        when(cdt.matchesRequirements(anyMap())).thenReturn(true);
        when(cdt.findTool("clang++")).thenReturn("/usr/bin/clang++");
        manager.providers.put("cdt", List.of(cdt));
        var selected = manager.planBuildToolchain(session, List.of(
                stage("compile", BuildToolchainPlan.Role.COMPILE, "cdt", "clang++", true),
                stage("link", BuildToolchainPlan.Role.LINK, "cdt", "ld.lld", true, "compile")));
        assertEquals("/usr/bin/clang++", selected.selections().get(0).command().orElseThrow());
        assertEquals(BuildToolchainPlan.ToolStatus.MISSING_EXECUTABLE, selected.selections().get(1).status());
        assertSame(cdt, selected.selections().get(1).toolchain().orElseThrow());
        assertFalse(selected.allRequiredToolsSelected());
        assertThrows(IllegalArgumentException.class,
                () -> stage("invalid", BuildToolchainPlan.Role.COMPILE, "cdt", "../../clang", true));
    }

    @Test
    void validatesCyclesDuplicatesAnd64StageBoundWithoutToolExecution() {
        Manager manager = new Manager();
        assertThrows(IllegalArgumentException.class, () -> manager.planBuildToolchain(session, List.of()));
        assertThrows(IllegalArgumentException.class, () -> manager.planBuildToolchain(session, List.of(
                stage("a", BuildToolchainPlan.Role.TEST, "", "", true, "b"),
                stage("b", BuildToolchainPlan.Role.TEST, "", "", true, "a"))));
        assertThrows(IllegalArgumentException.class, () -> manager.planBuildToolchain(session, List.of(
                stage("a", BuildToolchainPlan.Role.TEST, "", "", true, "b", "b"),
                stage("b", BuildToolchainPlan.Role.TEST, "", "", true))));
        List<BuildToolchainPlan.Stage> graph = new ArrayList<>();
        for (int i = 0; i < 65; i++) {
            graph.add(stage("s" + i, BuildToolchainPlan.Role.CUSTOM, "", "", false));
        }
        assertEquals(64, manager.planBuildToolchain(session, graph.subList(0, 64)).stages().size());
        assertThrows(IllegalArgumentException.class, () -> manager.planBuildToolchain(session, graph));
    }

    @Test
    void originalMavenResultsDistinguishSubmittedSuccessfulFailedAndFilteredGoals() {
        Manager manager = new Manager();
        BuildToolchainPlan plan = manager.planBuildToolchain(session, List.of(
                stage("lint", BuildToolchainPlan.Role.LINT, "", "", false),
                stage("compile", BuildToolchainPlan.Role.COMPILE, "", "", true, "lint"),
                stage("test", BuildToolchainPlan.Role.TEST, "", "", true, "compile"),
                stage("package", BuildToolchainPlan.Role.PACKAGE, "", "", true, "test")));
        MojoExecution lint = mock(MojoExecution.class);
        MojoExecution compile = mock(MojoExecution.class);
        MojoExecution test = mock(MojoExecution.class);
        MojoExecution integration = mock(MojoExecution.class);
        MojoExecution pack = mock(MojoExecution.class);
        MojoExecution unbound = mock(MojoExecution.class);
        LifecyclePlan original = mock(LifecyclePlan.class);
        when(original.executions()).thenReturn(List.of(lint, compile, test, integration, pack, unbound));
        var bindings = List.of(
                new BuildToolchainPlan.MojoBinding("lint", lint),
                new BuildToolchainPlan.MojoBinding("compile", compile),
                new BuildToolchainPlan.MojoBinding("test", test),
                new BuildToolchainPlan.MojoBinding("test", integration),
                new BuildToolchainPlan.MojoBinding("package", pack));
        LifecycleExecutionResult success = mock(LifecycleExecutionResult.class);
        when(success.plan()).thenReturn(original);
        when(success.executions()).thenReturn(original.executions());
        when(success.executedSuccessfully()).thenReturn(original.executions());
        when(success.failedExecution()).thenReturn(Optional.empty());
        when(success.failureCause()).thenReturn(Optional.empty());

        var complete = plan.observeExecution(original, bindings, success);
        assertTrue(complete.allRequiredStagesReportedSuccessful());
        assertEquals(BuildToolchainPlan.ExecutionStatus.REPORTED_SUCCESS, complete.coverage().get(2).status());
        assertEquals(2, complete.coverage().get(2).succeeded());
        assertThrows(UnsupportedOperationException.class, () -> complete.coverage().clear());

        LifecycleExecutionResult filtered = mock(LifecycleExecutionResult.class);
        when(filtered.plan()).thenReturn(original);
        when(filtered.executions()).thenReturn(List.of(lint, compile, test, pack));
        when(filtered.executedSuccessfully()).thenReturn(List.of(lint, compile, test, pack));
        when(filtered.failedExecution()).thenReturn(Optional.empty());
        when(filtered.failureCause()).thenReturn(Optional.empty());
        var partial = plan.observeExecution(original, bindings, filtered);
        assertFalse(partial.allRequiredStagesReportedSuccessful());
        assertEquals(BuildToolchainPlan.ExecutionStatus.INCOMPLETE, partial.coverage().get(2).status());

        LifecycleExecutionResult failed = mock(LifecycleExecutionResult.class);
        when(failed.plan()).thenReturn(original);
        when(failed.executions()).thenReturn(List.of(lint, compile, test, integration));
        when(failed.executedSuccessfully()).thenReturn(List.of(lint, compile, test));
        when(failed.failedExecution()).thenReturn(Optional.of(integration));
        when(failed.failureCause()).thenReturn(Optional.of(new IllegalStateException("original test failed")));
        var observedFailure = plan.observeExecution(original, bindings, failed);
        assertTrue(observedFailure.lifecycleFailed());
        assertFalse(observedFailure.allRequiredStagesReportedSuccessful());
        assertEquals(BuildToolchainPlan.ExecutionStatus.FAILED, observedFailure.coverage().get(2).status());
        assertEquals(1, observedFailure.coverage().get(2).failed());

        LifecycleExecutionResult unboundPending = mock(LifecycleExecutionResult.class);
        when(unboundPending.plan()).thenReturn(original);
        when(unboundPending.executions()).thenReturn(original.executions());
        when(unboundPending.executedSuccessfully()).thenReturn(List.of(lint, compile, test, integration, pack));
        when(unboundPending.failedExecution()).thenReturn(Optional.empty());
        when(unboundPending.failureCause()).thenReturn(Optional.empty());
        var unboundPendingObserved = plan.observeExecution(original, bindings, unboundPending);
        assertFalse(unboundPendingObserved.lifecycleFailed());
        assertFalse(unboundPendingObserved.allSubmittedMojosReportedSuccessful());
        assertFalse(unboundPendingObserved.allRequiredStagesReportedSuccessful());

        LifecycleExecutionResult foreign = mock(LifecycleExecutionResult.class);
        when(foreign.plan()).thenReturn(original);
        when(foreign.executions()).thenReturn(List.of(lint, mock(MojoExecution.class)));
        when(foreign.executedSuccessfully()).thenReturn(List.of(lint));
        when(foreign.failedExecution()).thenReturn(Optional.empty());
        when(foreign.failureCause()).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> plan.observeExecution(original, bindings, foreign));
        assertThrows(IllegalArgumentException.class,
                () -> new BuildToolchainPlan.ExecutionCoverage(plan.stages().get(2),
                        BuildToolchainPlan.ExecutionStatus.REPORTED_SUCCESS, 2, 1, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new BuildToolchainPlan.ExecutionObservation(observedFailure.coverage(), true, false, true));
        BuildToolchainPlan onlyOptional = manager.planBuildToolchain(session,
                List.of(stage("lint", BuildToolchainPlan.Role.LINT, "", "", false)));
        assertFalse(onlyOptional.observeExecution(original, List.of(), success)
                .allRequiredStagesReportedSuccessful());
    }

    @Test
    void observesOnlyOriginalMojoInstancesAndPreservesGoalOrdering() {
        Manager manager = new Manager();
        BuildToolchainPlan graph = manager.planBuildToolchain(session, List.of(
                stage("lint", BuildToolchainPlan.Role.LINT, "", "", false),
                stage("compile", BuildToolchainPlan.Role.COMPILE, "", "", true, "lint"),
                stage("test", BuildToolchainPlan.Role.TEST, "", "", true, "compile"),
                stage("package", BuildToolchainPlan.Role.PACKAGE, "", "", true, "test")));
        MojoExecution lint = mock(MojoExecution.class);
        MojoExecution compiler = mock(MojoExecution.class);
        MojoExecution tests = mock(MojoExecution.class);
        MojoExecution packager = mock(MojoExecution.class);
        LifecyclePlan original = mock(LifecyclePlan.class);
        when(original.executions()).thenReturn(List.of(lint, compiler, tests, packager));
        var matches = List.of(
                new BuildToolchainPlan.MojoBinding("lint", lint),
                new BuildToolchainPlan.MojoBinding("compile", compiler),
                new BuildToolchainPlan.MojoBinding("test", tests),
                new BuildToolchainPlan.MojoBinding("package", packager));
        BuildToolchainPlan.Observation observation = graph.observe(original, matches);
        assertTrue(observation.allRequiredStagesConfigured());
        assertSame(compiler, observation.coverage().get(1).configuredExecutions().get(0));
        assertThrows(IllegalArgumentException.class, () -> graph.observe(original,
                List.of(new BuildToolchainPlan.MojoBinding("test", mock(MojoExecution.class)))));
        assertThrows(IllegalArgumentException.class, () -> graph.observe(original,
                List.of(new BuildToolchainPlan.MojoBinding("compile", compiler),
                        new BuildToolchainPlan.MojoBinding("test", compiler))));
        when(original.executions()).thenReturn(List.of(packager, tests, compiler, lint));
        assertThrows(IllegalArgumentException.class, () -> graph.observe(original, matches));
    }
}
