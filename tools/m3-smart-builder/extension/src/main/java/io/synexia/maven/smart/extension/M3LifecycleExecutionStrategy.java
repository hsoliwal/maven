/*
 * SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.smart.extension;

import java.io.File;
import java.io.IOException;
import java.lang.module.FindException;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.CumulativeScopeArtifactFilter;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.LifecycleExecutionException;
import org.apache.maven.lifecycle.internal.ExecutionEventCatapult;
import org.apache.maven.lifecycle.internal.LifecycleDependencyResolver;
import org.apache.maven.lifecycle.internal.MojoExecutor;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionRunner;
import org.apache.maven.plugin.MojosExecutionStrategy;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.sisu.Priority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.synexia.maven.polyglot.ModuleStateDb;

/**
 * Opt-in reuse of declared standard Maven compilation/resource/archive results.
 * The original runner remains authoritative for all tests, publication, custom
 * goals and cache misses. Each goal owns a separate receipt in the module DB.
 * Per-goal leases also support Maven 4's fragmented phase execution strategy.
 */
@Named
@Singleton
@Priority(100)
public final class M3LifecycleExecutionStrategy implements MojosExecutionStrategy {
    private static final Logger LOG = LoggerFactory.getLogger(M3LifecycleExecutionStrategy.class);
    private static final String UNQUALIFIED = M3LifecycleExecutionStrategy.class.getName() + ".unqualified";
    private final BuildPluginManager plugins;
    private final LifecycleDependencyResolver dependencies;
    private final Provider<MojoExecutor> executors;
    private final ExecutionEventCatapult events;

    @Inject
    public M3LifecycleExecutionStrategy(BuildPluginManager plugins, LifecycleDependencyResolver dependencies,
            Provider<MojoExecutor> executors, ExecutionEventCatapult events) {
        this.plugins = plugins;
        this.dependencies = dependencies;
        this.executors = executors;
        this.events = events;
    }

    @Override public void execute(List<MojoExecution> goals, MavenSession session, MojoExecutionRunner runner)
            throws LifecycleExecutionException {
        MavenProject project = session.getCurrentProject();
        if (project != null && "true".equals(property(session, "m3.lifecycle.incremental", "false"))) {
            // Maven 4 may submit separate phase fragments for the same project.
            // Serialize that module's own admission/execution; other modules remain independent.
            synchronized (project) { executeProject(goals, session, runner); }
        } else executeProject(goals, session, runner);
    }

    private void executeProject(List<MojoExecution> goals, MavenSession session, MojoExecutionRunner runner)
            throws LifecycleExecutionException {
        MavenProject project = session.getCurrentProject();
        if (project == null || project.getBasedir() == null) {
            for (MojoExecution goal : goals) runner.run(goal);
            return;
        }
        Path root = project.getBasedir().toPath().toAbsolutePath().normalize();
        String backend = property(session, "m3.lifecycle.backend", "embedded");
        boolean enabled = "true".equals(property(session, "m3.lifecycle.incremental", "false"));
        for (MojoExecution goal : goals) {
            boolean known = known(goal);
            if (!enabled || !known || Boolean.TRUE.equals(project.getContextValue(UNQUALIFIED))) {
                if (!known && !alwaysRun(goal)) project.setContextValue(UNQUALIFIED, Boolean.TRUE);
                if (!alwaysRun(goal) || !enabled) {
                    synchronized (project) { revoke(root); }
                }
                runner.run(goal);
                continue;
            }
            final GoalPaths paths;
            final List<Path> resolved;
            final List<Artifact> attachments;
            try {
                paths = paths(session, goal, root);
                attachments = List.copyOf(project.getAttachedArtifacts());
                var context = executors.get().newDependencyContext(session, List.of(goal));
                Set<Artifact> reactor = new HashSet<>();
                for (MavenProject member : session.getProjects()) {
                    if (member.getArtifact() != null) reactor.add(member.getArtifact());
                }
                dependencies.resolveProjectDependencies(project, context.getScopesToCollectForCurrentProject(),
                        context.getScopesToResolveForCurrentProject(), session, false, reactor);
                Set<String> scopes = new HashSet<>(context.getScopesToCollectForCurrentProject());
                scopes.addAll(context.getScopesToResolveForCurrentProject());
                // The original MojoExecutor installs this filter only inside runner.run.
                // Install the same filter before reading the resolved artifacts for reuse.
                project.setArtifactFilter(scopes.isEmpty() ? null : new CumulativeScopeArtifactFilter(scopes));
                resolved = new ArrayList<>();
                // Maven's classpath order is observable when JARs contain the same class.
                for (Artifact artifact : project.getArtifacts()) {
                    if (artifact.getFile() == null) throw new IOException("unresolved dependency " + artifact.getId());
                    Path file = artifact.getFile().toPath().toRealPath();
                    resolved.add(file);
                }
                plugins.getPluginRealm(session, goal.getMojoDescriptor().getPluginDescriptor());
                rejectProcessors(session, goal, resolved);
            } catch (Exception unsupported) {
                LOG.info("M3DB UNCACHED {}: {}", goal, unsupported.getMessage());
                revoke(root);
                runner.run(goal);
                continue;
            }
            String key = M3LifecycleFingerprint.key(goal);
            try (ModuleStateDb state = ModuleStateDb.open(root, backend, true, LOG::info)) {
                final M3LifecycleFingerprint.Evidence before;
                try {
                    before = M3LifecycleFingerprint.observeInputs(session, goal, resolved, paths.inputs());
                    LOG.debug("M3DB SHA-256 before {}: {} files, {} payload bytes",
                            goal, before.filesHashed(), before.payloadBytesRead());
                } catch (IOException unsupported) {
                    state.invalidate(key);
                    LOG.info("M3DB UNCACHED {}: {}", goal, unsupported.getMessage());
                    runner.run(goal);
                    continue;
                }
                String[] prior = state.lookup(key);
                M3LifecycleFingerprint.Evidence oldOutputs = null;
                try {
                    oldOutputs = existingOutputForReuse(
                            prior, before.digest(), paths.outputs(), paths.artifact());
                    if (oldOutputs != null) {
                        LOG.debug("M3DB SHA-256 outputs before {}: {} files, {} payload bytes",
                                goal, oldOutputs.filesHashed(), oldOutputs.payloadBytesRead());
                    } else {
                        LOG.debug("M3DB SHA-256 outputs before {}: skipped (no matching input receipt)", goal);
                    }
                } catch (IOException changed) {
                    LOG.debug("M3DB output miss", changed);
                }
                if (oldOutputs != null && prior[1].equals(oldOutputs.digest())) {
                    restore(project, goal, paths);
                    LOG.info("M3DB UP-TO-DATE {}:{} ({})", goal.getArtifactId(), goal.getGoal(), project.getArtifactId());
                    events.fire(ExecutionEvent.Type.MojoSkipped, session, goal);
                    continue;
                }
                state.invalidate(key);
                forceCompilerContentCheck(goal, paths);
                runFresh(goal, runner);
                try {
                    M3LifecycleFingerprint.Evidence after =
                            M3LifecycleFingerprint.observeInputs(session, goal, resolved, paths.inputs());
                    LOG.debug("M3DB SHA-256 after {}: {} files, {} payload bytes",
                            goal, after.filesHashed(), after.payloadBytesRead());
                    File attached = project.getArtifact() == null ? null : project.getArtifact().getFile();
                    if (before.digest().equals(after.digest()) && unchangedAttachments(project, attachments)
                            && (paths.artifact() == null || (Files.isRegularFile(paths.artifact(), LinkOption.NOFOLLOW_LINKS)
                                    && attached != null && attached.toPath().toRealPath().equals(paths.artifact().toRealPath())))) {
                        M3LifecycleFingerprint.Evidence outputAfter =
                                M3LifecycleFingerprint.observeOutputs(paths.outputs());
                        LOG.debug("M3DB SHA-256 outputs after {}: {} files, {} payload bytes",
                                goal, outputAfter.filesHashed(), outputAfter.payloadBytesRead());
                        state.record(key, after.digest(), outputAfter.digest());
                    } else LOG.info("M3DB UNCACHED {}: inputs or artifact attachments changed", goal);
                } catch (IOException unstable) {
                    LOG.info("M3DB UNCACHED {}: {}", goal, unstable.getMessage());
                }
            } catch (IOException failure) {
                throw new LifecycleExecutionException("M3 module state: " + failure.getMessage(), failure);
            }
        }
    }


    /**
     * A pre-execution output hash can authorize a hit only when the existing
     * input receipt is already content-verified. On cold builds, invalidated
     * inputs or missing attached artifacts, reading old output payload would
     * be wasted: the original goal must execute regardless. Post-execution
     * verification and durable publication remain unchanged.
     */
    static M3LifecycleFingerprint.Evidence existingOutputForReuse(
            String[] prior, String inputDigest, List<Path> outputs, Path attachedArtifact) throws IOException {
        if (prior == null || prior.length != 2 || prior[1] == null || !inputDigest.equals(prior[0])
                || (attachedArtifact != null && !Files.isRegularFile(attachedArtifact, LinkOption.NOFOLLOW_LINKS))) {
            return null;
        }
        return M3LifecycleFingerprint.observeOutputs(outputs);
    }

    private static void revoke(Path root) throws LifecycleExecutionException {
        if (!Files.exists(root.resolve(".m3"), LinkOption.NOFOLLOW_LINKS)) return;
        try {
            ModuleStateDb.revokePrefix(root, "lifecycle-", LOG::info);
        } catch (IOException failure) {
            throw new LifecycleExecutionException("Cannot revoke M3 module state", failure);
        }
    }

    static boolean known(MojoExecution goal) {
        if (!"org.apache.maven.plugins".equals(goal.getGroupId()) || goal.getMojoDescriptor() == null
                || goal.getSource() != MojoExecution.Source.LIFECYCLE || goal.getLifecyclePhase() == null
                || goal.getMojoDescriptor().isAggregator() || goal.getMojoDescriptor().isForking()
                || !goal.getForkedExecutions().isEmpty()) return false;
        return switch (goal.getArtifactId()) {
            case "maven-resources-plugin" -> Set.of("3.3.1").contains(goal.getVersion())
                    && Set.of("resources", "testResources").contains(goal.getGoal());
            case "maven-compiler-plugin" -> Set.of("3.13.0", "3.14.1").contains(goal.getVersion())
                    && Set.of("compile", "testCompile").contains(goal.getGoal());
            case "maven-jar-plugin" -> Set.of("3.4.1", "3.4.2").contains(goal.getVersion())
                    && "jar".equals(goal.getGoal());
            default -> false;
        };
    }

    private static boolean alwaysRun(MojoExecution goal) {
        return "org.apache.maven.plugins".equals(goal.getGroupId()) && switch (goal.getArtifactId()) {
            case "maven-surefire-plugin" -> "test".equals(goal.getGoal());
            case "maven-failsafe-plugin" -> Set.of("integration-test", "verify").contains(goal.getGoal());
            case "maven-install-plugin" -> "install".equals(goal.getGoal());
            case "maven-deploy-plugin" -> "deploy".equals(goal.getGoal());
            default -> false;
        };
    }

    private static GoalPaths paths(MavenSession session, MojoExecution goal, Path root) throws Exception {
        MavenProject project = session.getCurrentProject();
        if (goal.getExecutionId() == null || !goal.getExecutionId().matches("[A-Za-z0-9_.-]{1,128}")
                || goal.getExecutionId().equals(".") || goal.getExecutionId().equals("..")) {
            throw new IOException("unsupported goal execution identity");
        }
        if (!"jar".equals(project.getPackaging()) || !project.getBuildExtensions().isEmpty()
                || !qualifiedAttachments(project)) throw new IOException("unqualified packaging or extension");
        Path build = M3LifecycleFingerprint.path(root, project.getBuild().getDirectory());
        Path main = M3LifecycleFingerprint.path(root, project.getBuild().getOutputDirectory());
        Path test = M3LifecycleFingerprint.path(root, project.getBuild().getTestOutputDirectory());
        if (!build.startsWith(root) || build.equals(root) || !main.startsWith(build) || !test.startsWith(build)) {
            throw new IOException("outputs must be below the module build directory");
        }
        PluginParameterExpressionEvaluator evaluator = new PluginParameterExpressionEvaluator(session, goal);
        List<Path> inputs = new ArrayList<>(), outputs = new ArrayList<>();
        Path artifact = null;
        boolean testGoal = goal.getGoal().startsWith("test");
        if (goal.getArtifactId().equals("maven-resources-plugin")) {
            for (String name : List.of("resources", "filters")) {
                Xpp3Dom configured = child(goal, name);
                if (configured != null && configured.getChildCount() != 0) {
                    throw new IOException("custom resources require declared polyglot inputs: " + name);
                }
            }
            Path output = configuredPath(evaluator, goal, root, "outputDirectory", testGoal ? test : main);
            if (!output.equals(testGoal ? test : main)) throw new IOException("custom resources output");
            outputs.add(output);
        } else if (goal.getArtifactId().equals("maven-compiler-plugin")) {
            for (String name : List.of("executable", "jdkToolchain", "annotationProcessorPaths",
                    "annotationProcessors", "compilerArgs", "compilerArguments", "compilerArgument",
                    "testCompilerArgument", "testCompilerArguments", "testCompilerArgs", "compileSourceRoots")) {
                Xpp3Dom node = child(goal, name);
                if (node != null && (node.getChildCount() != 0 || nonempty(evaluator, node))) {
                    throw new IOException("custom compiler input: " + name);
                }
            }
            if (Boolean.parseBoolean(value(evaluator, goal, "fork", "false"))
                    || Boolean.parseBoolean(value(evaluator, goal, testGoal ? "skip" : "skipMain", "false"))
                    || !Boolean.parseBoolean(value(evaluator, goal, "useIncrementalCompilation", "true"))
                    || !"javac".equals(value(evaluator, goal, "compilerId", "javac"))) {
                throw new IOException("external compiler requires a declared polyglot task");
            }
            Path output = configuredPath(evaluator, goal, root, "outputDirectory", testGoal ? test : main);
            if (!output.equals(testGoal ? test : main)) throw new IOException("custom compiler output");
            outputs.add(output);
            Path generated = build.resolve(testGoal ? "generated-test-sources/test-annotations" : "generated-sources/annotations");
            if (!configuredPath(evaluator, goal, root, testGoal ? "generatedTestSourcesDirectory" : "generatedSourcesDirectory", generated).equals(generated)) {
                throw new IOException("custom generated source directory");
            }
            outputs.add(generated);
            inputs.add(generated);
            outputs.add(build.resolve("maven-status/maven-compiler-plugin/" + goal.getGoal() + "/" + goal.getExecutionId()));
            if (testGoal) inputs.add(main);
        } else {
            if (!value(evaluator, goal, "classifier", "").isEmpty()) throw new IOException("attached jar classifier");
            Path output = configuredPath(evaluator, goal, root, "outputDirectory", build);
            String name = value(evaluator, goal, "finalName", project.getBuild().getFinalName());
            if (!output.equals(build) || name.contains("/") || name.contains("\\")) {
                throw new IOException("custom jar destination");
            }
            artifact = output.resolve(name + ".jar");
            outputs.add(artifact);
            inputs.add(configuredPath(evaluator, goal, root, "classesDirectory", main));
            Xpp3Dom archive = child(goal, "archive");
            if (archive != null) {
                for (String field : List.of("manifestFile", "pomPropertiesFile")) {
                    Xpp3Dom configured = archive.getChild(field);
                    if (configured != null) {
                        Object file = evaluator.evaluate(configured.getValue());
                        if (file != null) inputs.add(M3LifecycleFingerprint.path(root, file.toString()));
                    }
                }
            }
        }
        return new GoalPaths(List.copyOf(inputs), List.copyOf(outputs), artifact, build);
    }

    /**
     * Maven 4 attaches its lazy consumer POM before executing any goal. That
     * exact core-owned object remains live and is transformed by Maven when
     * install/deploy asks for it. Never serialize, replace or force its file here.
     * Custom attachments, even ones named "consumer", still require execution.
     */
    private static boolean qualifiedAttachments(MavenProject project) {
        List<Artifact> attachments = project.getAttachedArtifacts();
        if (attachments.isEmpty()) return true;
        if (attachments.size() != 1) return false;
        Artifact artifact = attachments.get(0);
        if (!artifact.getClass().getName().equals(
                "org.apache.maven.internal.transformation.impl.TransformedArtifact")
                || artifact.getClass().getClassLoader() != MavenProject.class.getClassLoader()
                || !"pom".equals(artifact.getType()) || !"consumer".equals(artifact.getClassifier())) return false;
        return Objects.equals(project.getGroupId(), artifact.getGroupId())
                && Objects.equals(project.getArtifactId(), artifact.getArtifactId())
                && Objects.equals(project.getVersion(), artifact.getVersion());
    }

    private static boolean unchangedAttachments(MavenProject project, List<Artifact> before) {
        List<Artifact> after = project.getAttachedArtifacts();
        if (!qualifiedAttachments(project) || before.size() != after.size()) return false;
        for (int i = 0; i < before.size(); i++) if (before.get(i) != after.get(i)) return false;
        return true;
    }

    private static void rejectProcessors(MavenSession session, MojoExecution goal, List<Path> dependencies)
            throws Exception {
        if (!goal.getArtifactId().equals("maven-compiler-plugin")) return;
        PluginParameterExpressionEvaluator evaluator = new PluginParameterExpressionEvaluator(session, goal);
        if ("none".equals(value(evaluator, goal, "proc", ""))) return;
        MavenProject project = session.getCurrentProject();
        Path root = project.getBasedir().toPath().toAbsolutePath().normalize();
        List<Path> discovery = new ArrayList<>(dependencies);
        // Maven's real javac classpath also includes the project's own output
        // directories. A module can provide its own processor to testCompile.
        List<String> classpath = goal.getGoal().equals("testCompile")
                ? project.getTestClasspathElements() : project.getCompileClasspathElements();
        for (String entry : classpath) {
            Path path = M3LifecycleFingerprint.path(root, entry);
            if (!discovery.contains(path)) discovery.add(path);
        }
        for (Path path : discovery) {
            if (Files.isDirectory(path)) {
                if (Files.exists(path.resolve("META-INF/services/javax.annotation.processing.Processor"))) {
                    throw new IOException("annotation processor requires complete declared inputs");
                }
            } else if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                // javac also accepts ZIP files and other classpath entries that
                // ModuleFinder cannot classify. They cannot prove absence of a processor.
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.toString().endsWith(".jar")) {
                    throw new IOException("uninspectable compiler classpath entry");
                }
                try {
                    boolean processor = ModuleFinder.of(path).findAll().stream().anyMatch(module ->
                            module.descriptor().provides().stream().anyMatch(service ->
                                    service.service().equals("javax.annotation.processing.Processor")));
                    if (processor) throw new IOException("annotation processor requires complete declared inputs");
                } catch (FindException malformed) {
                    throw new IOException("uninspectable compiler dependency", malformed);
                }
            }
        }
    }

    private static void restore(MavenProject project, MojoExecution goal, GoalPaths paths) {
        if (paths.artifact() != null) project.getArtifact().setFile(paths.artifact().toFile());
        if (goal.getArtifactId().equals("maven-compiler-plugin")) {
            boolean test = goal.getGoal().equals("testCompile");
            Path generated = paths.build().resolve(test ? "generated-test-sources/test-annotations" : "generated-sources/annotations");
            if (Files.isDirectory(generated)) {
                if (test) project.addTestCompileSourceRoot(generated.toString());
                else project.addCompileSourceRoot(generated.toString());
            }
        }
        project.addLifecyclePhase(goal.getLifecyclePhase());
    }

    /**
     * A content miss must reach javac even when source timestamps were restored.
     * Invalidate only the compiler's input inventory; preserve createdFiles.lst
     * so the original compiler can remove class files belonging to deleted sources.
     */
    private static void forceCompilerContentCheck(MojoExecution goal, GoalPaths paths) throws IOException {
        if (!goal.getArtifactId().equals("maven-compiler-plugin")) return;
        Path inventory = paths.build().resolve("maven-status/maven-compiler-plugin/" + goal.getGoal()
                + "/" + goal.getExecutionId() + "/inputFiles.lst");
        for (Path path = inventory; path != null; path = path.getParent()) {
            if (Files.isSymbolicLink(path)) throw new IOException("symlink in compiler state");
        }
        Files.deleteIfExists(inventory);
    }

    /** Bypass the original tools' timestamp shortcuts only after a verified content miss. */
    private static void runFresh(MojoExecution goal, MojoExecutionRunner runner) throws LifecycleExecutionException {
        String parameter = switch (goal.getArtifactId()) {
            case "maven-resources-plugin" -> "overwrite";
            case "maven-jar-plugin" -> "forceCreation";
            default -> null;
        };
        if (parameter == null) { runner.run(goal); return; }
        Xpp3Dom original = goal.getConfiguration();
        Xpp3Dom effective = original == null ? new Xpp3Dom("configuration") : new Xpp3Dom(original);
        Xpp3Dom flag = effective.getChild(parameter);
        if (flag == null) { flag = new Xpp3Dom(parameter); effective.addChild(flag); }
        flag.setValue("true");
        goal.setConfiguration(effective);
        try { runner.run(goal); }
        finally { goal.setConfiguration(original); }
    }

    static String property(MavenSession session, String name, String fallback) {
        return session.getUserProperties().getProperty(name,
                session.getSystemProperties().getProperty(name,
                        session.getCurrentProject().getProperties().getProperty(name, fallback)));
    }

    private static Xpp3Dom child(MojoExecution goal, String name) {
        return goal.getConfiguration() == null ? null : goal.getConfiguration().getChild(name);
    }
    private static boolean nonempty(PluginParameterExpressionEvaluator evaluator, Xpp3Dom value) throws Exception {
        Object resolved = value.getValue() == null ? null : evaluator.evaluate(value.getValue());
        return resolved != null && !resolved.toString().isBlank();
    }
    private static String value(PluginParameterExpressionEvaluator evaluator, MojoExecution goal,
            String name, String fallback) throws Exception {
        Xpp3Dom node = child(goal, name);
        Object resolved = node == null || node.getValue() == null ? null : evaluator.evaluate(node.getValue());
        return resolved == null ? fallback : resolved.toString();
    }
    private static Path configuredPath(PluginParameterExpressionEvaluator evaluator, MojoExecution goal,
            Path root, String name, Path fallback) throws Exception {
        return M3LifecycleFingerprint.path(root, value(evaluator, goal, name, fallback.toString()));
    }
    private record GoalPaths(List<Path> inputs, List<Path> outputs, Path artifact, Path build) {}
}
