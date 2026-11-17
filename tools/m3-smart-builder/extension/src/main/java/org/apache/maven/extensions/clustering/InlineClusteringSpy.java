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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Atomizes every Java compilation of the build inline, with no separate goal and no POM change: just before each
 * {@code compile} and {@code testCompile} execution of maven-compiler-plugin starts (dependencies resolved, mojo
 * not yet configured), it adds the javac plugin {@code CodeClustering} of maven-code-clustering-plugin to that
 * execution ({@link CompilerWiring}). Tables go to {@code ${project.build.directory}/code-clustering} (test sources:
 * its {@code test} directory).
 * <p>
 * On unless {@code clustering.inline} is {@code false} (user property, POM property or system property). The plugin
 * jar is {@code clustering.pluginJar}, else maven-code-clustering-plugin {@code clustering.pluginVersion} (default
 * {@value #VERSION}) in the local repository; without it the build runs as usual and the spy logs why once. Options
 * {@code clustering.minTokens}, {@code clustering.radius} and {@code clustering.fileThreshold} pass through. Any
 * failure is logged and leaves the execution as it was: the spy never fails a build.
 */
@Named("inline-code-clustering")
@Singleton
public final class InlineClusteringSpy extends AbstractEventSpy {
    static final String GROUP = "org.apache.maven.plugins";
    static final String ARTIFACT = "maven-code-clustering-plugin";
    static final String VERSION = "0.1.0-SNAPSHOT";
    private static final Logger LOG = LoggerFactory.getLogger(InlineClusteringSpy.class);
    private final Set<String> logged = ConcurrentHashMap.newKeySet();

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
        } catch (RuntimeException | LinkageError failure) {
            LOG.warn("Inline code clustering skipped for {} {}: {}", started.getProject().getArtifactId(),
                    mojo.getExecutionId(), failure.toString());
        }
    }

    private void wire(MavenSession session, MavenProject project, MojoExecution mojo) {
        if ("false".equals(property(session, project, "clustering.inline"))) return;
        Optional<Path> jar = pluginJar(session, project);
        if (jar.isEmpty()) {
            once("Inline code clustering off: " + GROUP + ":" + ARTIFACT + ":" + version(session, project)
                    + " is not in the local repository (or set -Dclustering.pluginJar)");
            return;
        }
        CompilerWiring wiring = new CompilerWiring(session, project, mojo);
        Optional<String> blocked = wiring.blocked(Files.isRegularFile(repositoryJar(session, project)));
        if (blocked.isPresent()) {
            LOG.info("Inline code clustering skipped for {} {}: {}", project.getArtifactId(), mojo.getExecutionId(), blocked.get());
            return;
        }
        wiring.wire(jar.get(), version(session, project), options(session, project));
        once("Inline code clustering: javac plugin CodeClustering from " + jar.get() + " (-Dclustering.inline=false turns it off)");
    }

    /** {@code clustering.pluginJar} when it names a file, else the plugin in the local repository, if there. */
    private static Optional<Path> pluginJar(MavenSession session, MavenProject project) {
        String given = property(session, project, "clustering.pluginJar");
        Path jar = given != null ? Path.of(given) : repositoryJar(session, project);
        return Files.isRegularFile(jar) ? Optional.of(jar.toAbsolutePath()) : Optional.empty();
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
