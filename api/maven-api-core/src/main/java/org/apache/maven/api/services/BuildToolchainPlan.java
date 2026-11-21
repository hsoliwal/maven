/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.apache.maven.api.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import org.apache.maven.api.LifecyclePlan;
import org.apache.maven.api.MojoExecution;
import org.apache.maven.api.Session;
import org.apache.maven.api.Toolchain;
import org.apache.maven.api.annotations.Experimental;

/**
 * An immutable, read-only stage/toolchain selection and lifecycle observation.
 *
 * <p>The original Maven {@link ToolchainManager} selects tools and the original
 * {@link LifecycleExecutor} executes configured Mojo goals. This class does
 * neither on its own: planning does not execute or skip a goal, install a JDK,
 * assert analyzer completion, or authorize build-cache and artifact promotion.
 *
 * <p>Stage prerequisites form a bounded directed acyclic graph. A project may
 * choose lint -> compile -> bytecode-analysis -> test -> package, but native
 * projects may need configure -> generate -> compile -> link -> test -> package.
 * All dependencies are explicit; no universal Maven phase order is invented.
 *
 * @since 4.2.0
 */
@Experimental
public final class BuildToolchainPlan {
    /** A role describes intent, not an installed plugin, compiler or execution. */
    public enum Role {
        /** Configure native/build-system inputs, e.g. original CMake goals. */
        CONFIGURE,
        /** Generate source/build descriptors using original Maven goals. */
        GENERATE,
        /** Source lint, typically before compilation. */
        LINT,
        /** Source or binary static analysis, subject to configured phase. */
        STATIC_ANALYSIS,
        /** Compile the selected project's sources using original tools. */
        COMPILE,
        /** Link native objects using the original linker toolchain. */
        LINK,
        /** Run configured test goals, possibly after packaging. */
        TEST,
        /** Produce build artifacts with configured packaging goals. */
        PACKAGE,
        /** Sign an artifact with its selected original signing owner. */
        SIGN,
        /** Caller-defined stage, without inferred execution semantics. */
        CUSTOM
    }

    /** A tool availability result, never a successful execution result. */
    public enum ToolStatus {
        /** Stage is owned by an original Maven Mojo, not an external toolchain. */
        NOT_REQUESTED,
        /** An original provider and requested named executable are selected. */
        SELECTED,
        /** No matching original provider/toolchain was found. */
        MISSING_TOOLCHAIN,
        /** Provider selected but the requested executable was unavailable. */
        MISSING_EXECUTABLE
    }

    /**
     * One stage with explicit predecessor identities and optional provider selection.
     *
     * @param id unique canonical stage identifier
     * @param role intended build role
     * @param toolchainType existing Maven toolchain type, empty for Mojo-owned work
     * @param requirements provider-specific matching requirements, e.g. os/arch
     * @param toolName command name resolved by the selected original provider,
     *                 empty for selection-only stages
     * @param after stage IDs that must precede this stage
     * @param required whether missing selection or lifecycle binding is reported
     */
    public record Stage(
            String id,
            Role role,
            String toolchainType,
            Map<String, String> requirements,
            String toolName,
            List<String> after,
            boolean required) {
        /** Validate and immutably copy all caller-provided role constraints. */
        public Stage {
            id = identifier(id, "stage ID");
            role = Objects.requireNonNull(role, "role");
            toolchainType = toolchainType == null ? "" : toolchainType;
            if (!toolchainType.isEmpty()) {
                toolchainType = identifier(toolchainType, "toolchain type");
            }
            toolName = toolName == null ? "" : toolName;
            if (!toolName.isEmpty()) {
                toolName = commandName(toolName);
            }
            if (toolchainType.isEmpty() && !toolName.isEmpty()) {
                throw new IllegalArgumentException("a tool name requires a toolchain type");
            }
            Objects.requireNonNull(requirements, "requirements");
            TreeMap<String, String> copy = new TreeMap<>();
            for (Map.Entry<String, String> entry : requirements.entrySet()) {
                String key = identifier(entry.getKey(), "requirement key");
                String value = Objects.requireNonNull(entry.getValue(), "requirement value");
                if (value.isBlank() || value.length() > 256 || value.chars().anyMatch(c -> c < 32)) {
                    throw new IllegalArgumentException("invalid toolchain requirement value");
                }
                copy.put(key, value);
            }
            if (toolchainType.isEmpty() && !copy.isEmpty()) {
                throw new IllegalArgumentException("requirements require an existing toolchain type");
            }
            requirements = Collections.unmodifiableMap(copy);
            after = List.copyOf(Objects.requireNonNull(after, "after"));
            for (String dependency : after) {
                identifier(dependency, "predecessor ID");
            }
        }
    }

    /**
     * Tool selected by the original manager, or an explicitly missing selection.
     *
     * @param stage original immutable stage identity
     * @param status tool availability, never a successful build result
     * @param toolchain original selected provider's toolchain, if available
     * @param command selected named executable, if requested and present
     */
    public record Selection(Stage stage, ToolStatus status, Optional<Toolchain> toolchain, Optional<String> command) {
        /** Refuse structurally inconsistent provider availability states. */
        public Selection {
            stage = Objects.requireNonNull(stage, "stage");
            status = Objects.requireNonNull(status, "status");
            toolchain = Objects.requireNonNull(toolchain, "toolchain");
            command = Objects.requireNonNull(command, "command");
            boolean requested = !stage.toolchainType().isEmpty();
            boolean namedCommand = !stage.toolName().isEmpty();
            boolean hasToolchain = toolchain.isPresent();
            boolean hasCommand = command.isPresent();
            if (status == ToolStatus.NOT_REQUESTED && (requested || hasToolchain || hasCommand)
                    || status == ToolStatus.MISSING_TOOLCHAIN && (!requested || hasToolchain || hasCommand)
                    || status == ToolStatus.MISSING_EXECUTABLE
                            && (!requested || !namedCommand || !hasToolchain || hasCommand)
                    || status == ToolStatus.SELECTED
                            && (!requested || !hasToolchain || hasCommand != namedCommand)) {
                throw new IllegalArgumentException("inconsistent tool selection state");
            }
        }
    }

    /**
     * An exact, identity-matched Mojo from the existing Maven LifecyclePlan.
     *
     * @param stageId stage identity, not a Maven goal name
     * @param execution the exact original planned Mojo instance
     */
    public record MojoBinding(String stageId, MojoExecution execution) {
        /** Validate a single explicit stage-to-Mojo binding. */
        public MojoBinding {
            stageId = identifier(stageId, "bound stage ID");
            execution = Objects.requireNonNull(execution, "execution");
        }
    }

    /**
     * Configured Mojo references for one stage; not execution status.
     *
     * @param stage immutable role identity
     * @param configuredExecutions exact original lifecycle Mojo objects
     */
    public record Coverage(Stage stage, List<MojoExecution> configuredExecutions) {
        /** Snapshot configured Mojo objects without altering their execution. */
        public Coverage {
            stage = Objects.requireNonNull(stage, "stage");
            configuredExecutions = List.copyOf(Objects.requireNonNull(configuredExecutions, "configuredExecutions"));
        }

        /**
         * Reports only configured original lifecycle coverage, never execution.
         *
         * @return whether at least one original Mojo is bound to the stage
         */
        public boolean configured() {
            return !configuredExecutions.isEmpty();
        }
    }

    /**
     * Read-only correspondence to the original configured lifecycle plan.
     * All configured goals still execute by the original LifecycleExecutor.
     *
     * @param coverage source-ordered original configured Mojo relationships
     * @param allRequiredStagesConfigured whether every required stage is bound
     */
    public record Observation(List<Coverage> coverage, boolean allRequiredStagesConfigured) {
        /** Refuse inconsistent missing/required coverage summaries. */
        public Observation {
            coverage = List.copyOf(Objects.requireNonNull(coverage, "coverage"));
            boolean allRequired = coverage.stream().allMatch(row -> !row.stage().required() || row.configured());
            if (allRequired != allRequiredStagesConfigured) {
                throw new IllegalArgumentException("inconsistent lifecycle coverage summary");
            }
        }
    }

    /**
     * Original Maven lifecycle execution state, never a static-analysis or
     * package-promotion verdict. All values require an original result object.
     */
    public enum ExecutionStatus {
        /** No original configured Mojo was bound to the stage. */
        UNCONFIGURED,
        /** Configured Mojos were omitted by the original execution request. */
        NOT_SUBMITTED,
        /** Only some configured/submitted Mojos reported completion. */
        INCOMPLETE,
        /** An original bound Mojo is the explicitly failed execution. */
        FAILED,
        /** All configured Mojos were submitted and reported successful. */
        REPORTED_SUCCESS
    }

    /**
     * Instance-checked, per-stage counts of Maven-reported Mojo outcomes.
     * The counts are scoped to one stage and are not independent analyzer
     * evidence, artifact signatures, cache permissions, or release approval.
     *
     * @param stage exact stage identity from this plan
     * @param status derived original lifecycle outcome
     * @param configured number of original configured Mojo bindings
     * @param submitted number of those retained by the execution request
     * @param succeeded number of those reported successful by Maven
     * @param failed number of those explicitly failed by Maven, zero or one
     */
    public record ExecutionCoverage(
            Stage stage, ExecutionStatus status, int configured, int submitted, int succeeded, int failed) {
        /** Reject fabricated success and impossible lifecycle counts. */
        public ExecutionCoverage {
            stage = Objects.requireNonNull(stage, "stage");
            status = Objects.requireNonNull(status, "status");
            if (configured < 0 || submitted < 0 || succeeded < 0 || failed < 0 || failed > 1
                    || submitted > configured || succeeded > submitted || failed > submitted - succeeded) {
                throw new IllegalArgumentException("inconsistent original lifecycle execution counts");
            }
            ExecutionStatus expected = configured == 0 ? ExecutionStatus.UNCONFIGURED
                    : failed > 0 ? ExecutionStatus.FAILED
                    : submitted == 0 ? ExecutionStatus.NOT_SUBMITTED
                    : submitted == configured && succeeded == configured ? ExecutionStatus.REPORTED_SUCCESS
                    : ExecutionStatus.INCOMPLETE;
            if (status != expected) {
                throw new IllegalArgumentException("original lifecycle stage status differs from counts");
            }
        }
    }

    /**
     * An immutable observation of one real Maven lifecycle execution result.
     * The caller must provide the authentic original result. It cannot
     * independently authenticate an arbitrary result implementation or
     * establish that analyzer semantics, packaging, signing or
     * external artifacts are valid, and cannot authorize goal skipping.
     *
     * @param coverage status of each stage in the immutable toolchain plan
     * @param lifecycleFailed true if Maven reported an explicit failure/cause
     * @param allSubmittedMojosReportedSuccessful true only when every Mojo
     *        retained by the original request was reported successful, even
     *        for an unbound or optional stage
     * @param allRequiredStagesReportedSuccessful true only when all required
     *        stage Mojos and all submitted original Maven Mojos reported
     *        success, without any explicit original lifecycle failure
     */
    public record ExecutionObservation(
            List<ExecutionCoverage> coverage, boolean lifecycleFailed,
            boolean allSubmittedMojosReportedSuccessful, boolean allRequiredStagesReportedSuccessful) {
        /** Do not permit contradictory aggregate completion claims. */
        public ExecutionObservation {
            coverage = List.copyOf(Objects.requireNonNull(coverage, "coverage"));
            if (lifecycleFailed && allSubmittedMojosReportedSuccessful) {
                throw new IllegalArgumentException("failed original Maven lifecycle cannot report all submitted success");
            }
            boolean requiredPresent = coverage.stream().anyMatch(row -> row.stage().required());
            boolean reportedSuccess = !lifecycleFailed && allSubmittedMojosReportedSuccessful && requiredPresent
                    && coverage.stream().allMatch(row -> !row.stage().required()
                            || row.status() == ExecutionStatus.REPORTED_SUCCESS);
            if (reportedSuccess != allRequiredStagesReportedSuccessful) {
                throw new IllegalArgumentException("contradictory required-stage lifecycle result");
            }
        }
    }

    private static final int MAX_STAGES = 64;
    private final List<Stage> stages;
    private final List<Selection> selections;
    private final Map<String, Integer> positions;
    private final long[] predecessorMasks;

    private BuildToolchainPlan(
            List<Stage> stages, List<Selection> selections, Map<String, Integer> positions, long[] predecessorMasks) {
        this.stages = List.copyOf(stages);
        this.selections = List.copyOf(selections);
        this.positions = Map.copyOf(positions);
        this.predecessorMasks = predecessorMasks.clone();
    }

    /**
     * Validate and select tools through the existing Maven manager, without
     * invoking a Mojo or modifying its session build context.
     *
     * @param manager original Maven toolchain selection owner
     * @param session original Maven session
     * @param requested stage graph in caller-preferred stable tie-break order
     * @return immutable graph and the original, unwrapped selected toolchains
     * @throws ToolchainManagerException for original provider errors
     */
    public static BuildToolchainPlan select(ToolchainManager manager, Session session, List<Stage> requested)
            throws ToolchainManagerException {
        Objects.requireNonNull(manager, "manager");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(requested, "requested");
        if (requested.isEmpty() || requested.size() > MAX_STAGES) {
            throw new IllegalArgumentException("build toolchain requires 1..64 stages");
        }
        Stage[] source = requested.toArray(Stage[]::new);
        Map<String, Integer> sourceIndex = new HashMap<>();
        for (int i = 0; i < source.length; i++) {
            Stage stage = Objects.requireNonNull(source[i], "stage");
            if (sourceIndex.putIfAbsent(stage.id(), i) != null) {
                throw new IllegalArgumentException("duplicate stage ID: " + stage.id());
            }
        }
        long[] originalMasks = new long[source.length];
        for (int i = 0; i < source.length; i++) {
            for (String dependency : source[i].after()) {
                Integer parent = sourceIndex.get(dependency);
                if (parent == null || parent == i) {
                    throw new IllegalArgumentException("missing or self prerequisite: " + dependency);
                }
                long bit = 1L << parent;
                if ((originalMasks[i] & bit) != 0) {
                    throw new IllegalArgumentException("duplicate prerequisite for " + source[i].id());
                }
                originalMasks[i] |= bit;
            }
        }

        // Deterministic stable topological ordering; only primitive masks represent edges.
        int[] order = new int[source.length];
        long completed = 0L;
        for (int step = 0; step < source.length; step++) {
            int found = -1;
            for (int i = 0; i < source.length; i++) {
                long bit = 1L << i;
                if ((completed & bit) == 0 && (originalMasks[i] & ~completed) == 0) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                throw new IllegalArgumentException("cycle in build toolchain prerequisites");
            }
            order[step] = found;
            completed |= 1L << found;
        }
        List<Stage> ordered = new ArrayList<>(source.length);
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < order.length; i++) {
            ordered.add(source[order[i]]);
            positions.put(source[order[i]].id(), i);
        }
        long[] masks = new long[source.length];
        for (int i = 0; i < source.length; i++) {
            Stage stage = ordered.get(i);
            for (String dependency : stage.after()) {
                masks[i] |= 1L << positions.get(dependency);
            }
        }
        // All graph/refusal checks above run before consulting an original provider.
        List<Selection> chosen = new ArrayList<>(ordered.size());
        for (Stage stage : ordered) {
            if (stage.toolchainType().isEmpty()) {
                chosen.add(new Selection(stage, ToolStatus.NOT_REQUESTED, Optional.empty(), Optional.empty()));
                continue;
            }
            Optional<Toolchain> selected = Objects.requireNonNull(
                    manager.selectToolchain(session, stage.toolchainType(), stage.requirements()), "toolchain selection");
            if (selected.isEmpty()) {
                chosen.add(new Selection(stage, ToolStatus.MISSING_TOOLCHAIN, Optional.empty(), Optional.empty()));
                continue;
            }
            Toolchain toolchain = selected.orElseThrow();
            if (!stage.toolchainType().equals(toolchain.getType())
                    || !toolchain.matchesRequirements(stage.requirements())) {
                throw new IllegalStateException("original toolchain provider violated selection constraints");
            }
            if (stage.toolName().isEmpty()) {
                chosen.add(new Selection(stage, ToolStatus.SELECTED, selected, Optional.empty()));
                continue;
            }
            String command = toolchain.findTool(stage.toolName());
            if (command == null || command.isBlank()) {
                chosen.add(new Selection(stage, ToolStatus.MISSING_EXECUTABLE, selected, Optional.empty()));
            } else {
                chosen.add(new Selection(stage, ToolStatus.SELECTED, selected, Optional.of(command)));
            }
        }
        return new BuildToolchainPlan(ordered, chosen, positions, masks);
    }

    /**
     * Presents the verified stage dependency order without changing Maven goals.
     *
     * @return stages in stable dependency-respecting order, not Maven Mojo order
     */
    public List<Stage> stages() {
        return stages;
    }

    /**
     * Presents original provider selections without resolving or invoking plugins.
     *
     * @return exact original provider tool selections in stage order
     */
    public List<Selection> selections() {
        return selections;
    }

    /**
     * Distinguishes required tool availability from plugin execution success.
     *
     * @return whether required tools are selected, NOT whether plugins ran
     */
    public boolean allRequiredToolsSelected() {
        return selections.stream().allMatch(row -> !row.stage().required()
                || row.status() == ToolStatus.SELECTED
                || row.status() == ToolStatus.NOT_REQUESTED);
    }

    /**
     * Bind staged goals only to the same {@code MojoExecution} objects present
     * in the original configured lifecycle plan, without executing or filtering it.
     * Each configured Mojo may be claimed once; one stage may contain multiple
     * configured Mojos. A prerequisite's final execution must precede the
     * dependent stage's first execution whenever both stages are bound.
     *
     * @param plan original Maven lifecycle plan, not a synthesized phase list
     * @param bindings exact stage-to-Mojo reference pairs
     * @return immutable coverage; missing required goals remain unconfigured
     */
    public Observation observe(LifecyclePlan plan, List<MojoBinding> bindings) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(bindings, "bindings");
        List<MojoExecution> planned = Objects.requireNonNull(plan.executions(), "plan.executions");
        if (planned.size() > 4096 || bindings.size() > 4096) {
            throw new IllegalArgumentException("Maven lifecycle observation budget exceeded");
        }
        List<MojoExecution> actual = List.copyOf(planned);
        IdentityHashMap<MojoExecution, Integer> index = new IdentityHashMap<>();
        for (int i = 0; i < actual.size(); i++) {
            if (index.put(Objects.requireNonNull(actual.get(i), "Mojo execution"), i) != null) {
                throw new IllegalArgumentException("same Mojo instance repeated in original lifecycle plan");
            }
        }
        IdentityHashMap<MojoExecution, Boolean> alreadyBound = new IdentityHashMap<>();
        int[] min = new int[stages.size()];
        int[] max = new int[stages.size()];
        Arrays.fill(min, Integer.MAX_VALUE);
        Arrays.fill(max, -1);
        List<List<MojoExecution>> grouped = new ArrayList<>(stages.size());
        for (int i = 0; i < stages.size(); i++) {
            grouped.add(new ArrayList<>());
        }
        for (MojoBinding binding : bindings) {
            Objects.requireNonNull(binding, "binding");
            Integer stage = positions.get(binding.stageId());
            Integer at = index.get(binding.execution());
            if (stage == null || at == null) {
                throw new IllegalArgumentException("unknown stage or foreign original Maven Mojo binding");
            }
            if (alreadyBound.put(binding.execution(), true) != null) {
                throw new IllegalArgumentException("duplicate original Maven Mojo binding");
            }
            grouped.get(stage).add(binding.execution());
            min[stage] = Math.min(min[stage], at);
            max[stage] = Math.max(max[stage], at);
        }
        for (int stage = 0; stage < stages.size(); stage++) {
            long dependencies = predecessorMasks[stage];
            while (dependencies != 0) {
                int predecessor = Long.numberOfTrailingZeros(dependencies);
                dependencies &= dependencies - 1;
                if (max[predecessor] >= 0 && max[stage] >= 0 && max[predecessor] >= min[stage]) {
                    throw new IllegalArgumentException("configured Maven Mojo violates stage prerequisite order");
                }
            }
        }
        List<Coverage> coverage = new ArrayList<>(stages.size());
        boolean allRequired = true;
        for (int i = 0; i < stages.size(); i++) {
            List<MojoExecution> matching = grouped.get(i);
            matching.sort((a, b) -> Integer.compare(index.get(a), index.get(b)));
            Coverage row = new Coverage(stages.get(i), matching);
            coverage.add(row);
            allRequired &= !row.stage().required() || row.configured();
        }
        return new Observation(coverage, allRequired);
    }

    /**
     * Cross-check exact original Maven execution outcomes against this plan.
     *
     * <p>This method observes a result returned by the existing
     * {@link LifecycleExecutor} (including a partial result attached to its
     * failure exception). It does not call {@code execute()}, modify a Mojo,
     * infer analyzer completion from a configured goal, or claim a build-cache
     * hit. Only the exact {@code MojoExecution} instances present in the
     * original {@link LifecyclePlan} may appear in submitted/success/failure
     * lists. Results from another plan are refused even when goal names match.
     *
     * <p>The original execution request may filter configured Mojos. Those
     * excluded original goals are never marked successful; a partial result
     * leaves uncompleted goals {@link ExecutionStatus#INCOMPLETE}. A failure in
     * an otherwise unbound Mojo makes the overall required-stage success
     * flag false, preserving the original Maven failure signal.
     *
     * @param originalPlan exact Maven plan, identical to {@code result.plan()}
     * @param bindings exact stage to original configured Mojo relationships
     * @param result original completed or partial Maven execution result
     * @return immutable, identity-checked Maven-reported stage statuses, not
     *         an independent execution proof or release authorization
     * @throws IllegalArgumentException if a result comes from a foreign or
     *         contradictory Maven execution inventory
     */
    public ExecutionObservation observeExecution(
            LifecyclePlan originalPlan, List<MojoBinding> bindings, LifecycleExecutionResult result) {
        Objects.requireNonNull(originalPlan, "originalPlan");
        Objects.requireNonNull(result, "result");
        if (result.plan() != originalPlan) {
            throw new IllegalArgumentException("foreign original Maven lifecycle result plan");
        }
        Observation configured = observe(originalPlan, bindings);
        List<MojoExecution> originalExecutions =
                Objects.requireNonNull(originalPlan.executions(), "plan.executions");
        List<MojoExecution> retained = Objects.requireNonNull(result.executions(), "result.executions");
        List<MojoExecution> completed =
                Objects.requireNonNull(result.executedSuccessfully(), "result.executedSuccessfully");
        if (originalExecutions.size() > 4096 || retained.size() > 4096 || completed.size() > retained.size()) {
            throw new IllegalArgumentException("original Maven result observation budget exceeded");
        }
        List<MojoExecution> planExecutions = List.copyOf(originalExecutions);
        List<MojoExecution> submitted = List.copyOf(retained);
        List<MojoExecution> successful = List.copyOf(completed);
        Optional<MojoExecution> failed = Objects.requireNonNull(result.failedExecution(), "result.failedExecution");
        Optional<Throwable> cause = Objects.requireNonNull(result.failureCause(), "result.failureCause");

        IdentityHashMap<MojoExecution, Integer> originalIndex = new IdentityHashMap<>();
        for (int i = 0; i < planExecutions.size(); i++) {
            if (originalIndex.put(Objects.requireNonNull(planExecutions.get(i), "planned Mojo"), i) != null) {
                throw new IllegalArgumentException("repeated original Maven planned Mojo");
            }
        }
        IdentityHashMap<MojoExecution, Boolean> selected = new IdentityHashMap<>();
        int priorIndex = -1;
        for (MojoExecution mojo : submitted) {
            Integer index = originalIndex.get(Objects.requireNonNull(mojo, "submitted Mojo"));
            if (index == null || index <= priorIndex || selected.put(mojo, true) != null) {
                throw new IllegalArgumentException("foreign or reordered submitted Maven execution");
            }
            priorIndex = index;
        }
        IdentityHashMap<MojoExecution, Boolean> passed = new IdentityHashMap<>();
        for (MojoExecution mojo : successful) {
            if (!selected.containsKey(Objects.requireNonNull(mojo, "successful Mojo"))
                    || passed.put(mojo, true) != null) {
                throw new IllegalArgumentException("foreign or duplicate successful Maven execution");
            }
        }
        if (failed.isPresent() && (!selected.containsKey(failed.orElseThrow())
                || passed.containsKey(failed.orElseThrow()))) {
            throw new IllegalArgumentException("failed Maven Mojo not selected or already successful");
        }

        List<ExecutionCoverage> rows = new ArrayList<>(stages.size());
        for (Coverage stageCoverage : configured.coverage()) {
            int requestedCount = 0;
            int successfulCount = 0;
            int failedCount = 0;
            for (MojoExecution mojo : stageCoverage.configuredExecutions()) {
                if (selected.containsKey(mojo)) {
                    requestedCount++;
                }
                if (passed.containsKey(mojo)) {
                    successfulCount++;
                }
                if (failed.isPresent() && failed.orElseThrow() == mojo) {
                    failedCount++;
                }
            }
            int configuredCount = stageCoverage.configuredExecutions().size();
            ExecutionStatus status = configuredCount == 0 ? ExecutionStatus.UNCONFIGURED
                    : failedCount > 0 ? ExecutionStatus.FAILED
                    : requestedCount == 0 ? ExecutionStatus.NOT_SUBMITTED
                    : requestedCount == configuredCount && successfulCount == configuredCount
                            ? ExecutionStatus.REPORTED_SUCCESS : ExecutionStatus.INCOMPLETE;
            rows.add(new ExecutionCoverage(stageCoverage.stage(), status,
                    configuredCount, requestedCount, successfulCount, failedCount));
        }
        boolean failedLifecycle = failed.isPresent() || cause.isPresent();
        // A partial result may omit completion of a submitted, unrelated
        // Mojo even without a matching stage binding or explicit failure.
        // That must never produce a falsely complete aggregate stage report.
        boolean allSubmittedSuccess = !failedLifecycle && successful.size() == submitted.size();
        boolean requiredPresent = rows.stream().anyMatch(row -> row.stage().required());
        boolean requiredSuccess = allSubmittedSuccess && requiredPresent && rows.stream()
                .allMatch(row -> !row.stage().required() || row.status() == ExecutionStatus.REPORTED_SUCCESS);
        return new ExecutionObservation(rows, failedLifecycle, allSubmittedSuccess, requiredSuccess);
    }

    private static String commandName(String name) {
        Objects.requireNonNull(name, "tool name");
        // Native and CDT tool names include clang++, g++, c++, ld.lld and clang-cl.
        // Only a bare command is accepted; the original provider resolves its path.
        if (!name.matches("[A-Za-z][A-Za-z0-9_+.-]{0,63}")) {
            throw new IllegalArgumentException("invalid executable command name: " + name);
        }
        return name;
    }

    private static String identifier(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("invalid " + name + ": " + value);
        }
        return value;
    }
}
