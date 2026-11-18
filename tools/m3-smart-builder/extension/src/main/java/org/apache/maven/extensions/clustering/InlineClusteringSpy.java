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
package org.apache.maven.extensions.clustering;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.ToolchainManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Atomizes every Java compilation of the build inline, with no separate goal and no POM change: just before each
 * {@code compile} and {@code testCompile} execution of maven-compiler-plugin starts (dependencies resolved, mojo
 * not yet configured), it adds the javac plugin {@code CodeClustering} of maven-code-clustering-plugin to that
 * execution ({@link CompilerWiring}). Tables go to {@code ${project.build.directory}/code-clustering/inline} (test
 * sources: its {@code test} directory), apart from the {@code cluster} goal's tables.
 * <p>
 * On while {@code clustering.inline} (user property, POM property or system property) is absent or {@code true},
 * {@code yes}, {@code on} or {@code 1} in any letter case; any other value ({@code false}, {@code 0}, {@code no},
 * {@code off}, empty) turns it off. The plugin jar is {@code clustering.pluginJar}, else maven-code-clustering-plugin
 * {@code clustering.pluginVersion} (default {@value #VERSION}) in the local repository; it must register the javac
 * plugin ({@link PluginJar}). Without such a jar the build runs as usual and the spy logs why once. Options
 * {@code clustering.minTokens}, {@code clustering.radius} and {@code clustering.fileThreshold} pass through. Before each
 * wired execution, the rows of deleted sources are dropped ({@code InlineClustering.prune}). A JDK toolchain in the
 * build context leaves an execution alone ({@link ToolchainManager}). An execution left alone for any reason, the off
 * switch included, loses its earlier inline tables ({@link StaleTables}): no tables stay that this build did not
 * write. Any failure is logged and leaves the execution as it was: the spy never fails a build.
 */
@Named("inline-code-clustering")
@Singleton
public final class InlineClusteringSpy extends AbstractEventSpy {
    static final String GROUP = "org.apache.maven.plugins";
    static final String ARTIFACT = "maven-code-clustering-plugin";
    static final String VERSION = "0.1.0-SNAPSHOT";
    private static final Logger LOG = LoggerFactory.getLogger(InlineClusteringSpy.class);
    /** The values of {@code clustering.inline} that keep it on (any letter case); any other value turns it off. */
    private static final Set<String> ON = Set.of("true", "yes", "on", "1");
    private final Set<String> logged = ConcurrentHashMap.newKeySet();
    private final Provider<ToolchainManager> toolchains;

    @Inject
    public InlineClusteringSpy(Provider<ToolchainManager> toolchains) {
        this.toolchains = toolchains;
    }

    @Override
    public void onEvent(Object event) {
        if (!(event instanceof ExecutionEvent) || ((ExecutionEvent) event).getType() != ExecutionEvent.Type.MojoStarted) {
            return;
        }
        ExecutionEvent started = (ExecutionEvent) event;
        MojoExecution mojo = started.getMojoExecution();
        if (mojo == null || started.getProject() == null || !CompilerWiring.isCompile(mojo)) return;
        try {
            wire(started.getSession(), started.getProject(), mojo);
        } catch (Throwable failure) {
            if (fatal(failure)) throw (Error) failure;
            LOG.warn("Inline code clustering skipped for {} {}: {}", started.getProject().getArtifactId(),
                    mojo.getExecutionId(), failure.toString());
        }
    }

    private void wire(MavenSession session, MavenProject project, MojoExecution mojo) {
        CompilerWiring wiring = new CompilerWiring(session, project, mojo, toolchains);
        Optional<Path> jar = on(property(session, project, "clustering.inline")) ? pluginJar(session, project) : Optional.empty();
        Optional<String> blocked = jar.isEmpty() ? Optional.empty() : wiring.blocked(coordinatesProblem(session, project));
        if (jar.isEmpty() || blocked.isPresent()) {
            leftAlone(wiring, project, mojo, blocked);
            return;
        }
        String options = options(session, project);
        wiring.wire(jar.get(), version(session, project), options);
        once("Inline code clustering: javac plugin CodeClustering from " + jar.get() + " (-Dclustering.inline=false turns it off)");
        prune(jar.get(), wiring.pruneArguments(options), project, mojo);
    }

    /**
     * An execution left alone: off, no usable plugin jar, or {@code blocked}. Its earlier inline tables are removed
     * ({@link StaleTables}); the skip is logged with its reason, and the removal also when it is off (only then).
     */
    private static void leftAlone(CompilerWiring wiring, MavenProject project, MojoExecution mojo, Optional<String> blocked) {
        boolean removed = removeTables(wiring, project, mojo);
        String note = removed ? " (earlier inline tables removed)" : "";
        if (blocked.isPresent()) {
            LOG.info("Inline code clustering skipped for {} {}: {}{}", project.getArtifactId(), mojo.getExecutionId(),
                    blocked.get(), note);
        } else if (removed) {
            LOG.info("Inline code clustering off for {} {}: earlier inline tables removed", project.getArtifactId(),
                    mojo.getExecutionId());
        }
    }

    /**
     * {@link StaleTables#remove} for the execution, unless its own arguments add the plugin (they write the tables); a
     * failure is logged and leaves the build as it is.
     */
    private static boolean removeTables(CompilerWiring wiring, MavenProject project, MojoExecution mojo) {
        try {
            return !wiring.configured() && StaleTables.remove(wiring.out(), !wiring.full());
        } catch (IOException | RuntimeException e) {
            LOG.warn("Inline code clustering: earlier inline tables of {} {} not removed: {}", project.getArtifactId(),
                    mojo.getExecutionId(), e.toString());
            return false;
        }
    }

    /** Whether {@code clustering.inline} keeps it on: absent, or {@link #ON}; false, 0, no, off, empty and others: off. */
    static boolean on(String value) {
        return value == null || ON.contains(value.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean fatal(Throwable failure) {
        return failure instanceof VirtualMachineError && !(failure instanceof StackOverflowError);
    }

    /**
     * Drops the rows of deleted sources before a wired execution (javac may not run at all):
     * {@code InlineClustering.prune} from a class loader of the plugin jar, closed after the call. A failure is logged
     * and leaves the wiring in place.
     */
    private static void prune(Path jar, String[] args, MavenProject project, MojoExecution mojo) {
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, ClassLoader.getSystemClassLoader())) {
            Object line = Class.forName(PluginJar.PLUGIN_CLASS, true, loader).getMethod("prune", String[].class)
                    .invoke(null, (Object) args);
            LOG.debug("Inline code clustering prune for {} {}: {}", project.getArtifactId(), mojo.getExecutionId(), line);
        } catch (Throwable failure) {
            Throwable cause = failure instanceof InvocationTargetException ? failure.getCause() : failure;
            if (fatal(cause)) throw (Error) cause;
            LOG.warn("Inline code clustering: rows of deleted sources not dropped for {} {}: {}", project.getArtifactId(),
                    mojo.getExecutionId(), cause.toString());
        }
    }

    /**
     * {@code clustering.pluginJar}, else the plugin in the local repository, when it registers the javac plugin;
     * otherwise logs why once.
     */
    private Optional<Path> pluginJar(MavenSession session, MavenProject project) {
        String given = property(session, project, "clustering.pluginJar");
        Path jar = (given != null ? Path.of(given) : repositoryJar(session, project)).toAbsolutePath();
        if (given == null && !Files.isRegularFile(jar)) {
            once("Inline code clustering off: " + GROUP + ":" + ARTIFACT + ":" + version(session, project)
                    + " is not in the local repository (or set -Dclustering.pluginJar)");
            return Optional.empty();
        }
        Optional<String> problem = PluginJar.problem(jar);
        problem.ifPresent(p -> once("Inline code clustering off: " + p));
        return problem.isPresent() ? Optional.empty() : Optional.of(jar);
    }

    /**
     * Why annotationProcessorPaths cannot name the plugin by its coordinates; empty when they can. maven-compiler-plugin
     * resolves them through the reactor first, so a reactor that builds the plugin gives its module's output (no
     * javac registration there); outside it they resolve to the local repository's jar, which must register it.
     */
    private static Optional<String> coordinatesProblem(MavenSession session, MavenProject project) {
        boolean inReactor = Stream.of(session.getProjects(), session.getAllProjects()).filter(Objects::nonNull)
                .flatMap(List::stream).anyMatch(p -> GROUP.equals(p.getGroupId()) && ARTIFACT.equals(p.getArtifactId()));
        if (inReactor) {
            return Optional.of("annotationProcessorPaths would resolve " + ARTIFACT + " from this reactor, which builds it");
        }
        return PluginJar.problem(repositoryJar(session, project).toAbsolutePath())
                .map(p -> "annotationProcessorPaths need the plugin in the local repository: " + p);
    }

    /** Where the plugin's jar is in the local repository; annotationProcessorPaths can name only that one. */
    private static Path repositoryJar(MavenSession session, MavenProject project) {
        String version = version(session, project);
        return Path.of(session.getRepositorySession().getLocalRepository().getBasedir().getPath(),
                GROUP.replace('.', '/'), ARTIFACT, version, ARTIFACT + "-" + version + ".jar");
    }

    private static String version(MavenSession session, MavenProject project) {
        String version = property(session, project, "clustering.pluginVersion");
        return version == null ? VERSION : version;
    }

    /** The plugin arguments after {@code out}: the clustering options that are set. */
    private static String options(MavenSession session, MavenProject project) {
        StringBuilder options = new StringBuilder();
        for (String name : new String[] {"minTokens", "radius", "fileThreshold"}) {
            String value = property(session, project, "clustering." + name);
            if (value != null && !value.isBlank()) options.append(' ').append(name).append('=').append(value.trim());
        }
        return options.toString();
    }

    /** A user property, else a property of the project's POM, else a system property. */
    static String property(MavenSession session, MavenProject project, String name) {
        String value = session.getUserProperties().getProperty(name);
        if (value == null) value = project.getProperties().getProperty(name);
        return value != null ? value : session.getSystemProperties().getProperty(name);
    }

    private void once(String message) {
        if (logged.add(message)) LOG.info(message);
    }
}
