// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.extension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds any language through Maven's own phases: m3-build.tsv targets named like a phase run in that phase. */
@Named("polyglot-lifecycle")
@Singleton
public final class PolyglotLifecycleParticipant extends AbstractMavenLifecycleParticipant {
    static final String PROPERTY = "m3.polyglot.lifecycle";
    static final String GROUP = "io.synexia";
    static final String ARTIFACT = "m3-polyglot-maven-plugin";
    static final String VERSION = "0.1.0-SNAPSHOT";
    static final String MANIFEST = "m3-build.tsv";
    static final List<String> PHASES = List.of("validate", "compile", "test", "package", "verify");
    private static final Logger LOG = LoggerFactory.getLogger(PolyglotLifecycleParticipant.class);

    @Override
    public void afterProjectsRead(MavenSession session) {
        String value = session.getUserProperties().getProperty(PROPERTY, session.getSystemProperties().getProperty(PROPERTY));
        if (!"true".equals(value)) return;
        Path plugin = Path.of(session.getRepositorySession().getLocalRepository().getBasedir().getPath(),
                "io", "synexia", ARTIFACT, VERSION, ARTIFACT + "-" + VERSION + ".jar");
        if (!Files.isRegularFile(plugin)) {
            LOG.info("Polyglot lifecycle skipped: " + GROUP + ":" + ARTIFACT + ":" + VERSION + " is not in the local repository");
            return;
        }
        for (MavenProject project : session.getProjects()) {
            Path manifest = project.getBasedir().toPath().resolve(MANIFEST);
            if (!Files.isRegularFile(manifest)) continue;
            try {
                Set<String> phases = phaseTargets(manifest);
                if (bind(project, phases)) {
                    LOG.info("Polyglot lifecycle: " + project.getArtifactId() + " runs m3-build.tsv targets " + phases);
                }
            } catch (IOException unreadable) {
                LOG.warn("Polyglot lifecycle: " + manifest + " is unreadable; nothing bound");
            }
        }
    }

    /** The manifest's task names that equal a bound Maven phase, in phase order. */
    static Set<String> phaseTargets(Path manifest) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            String[] cells = line.split("\t", -1);
            if (cells.length >= 2 && cells[0].equals("task")) names.add(cells[1]);
        }
        Set<String> phases = new LinkedHashSet<>();
        for (String phase : PHASES) {
            if (names.contains(phase)) phases.add(phase);
        }
        return phases;
    }

    /** One m3-polyglot:run execution per phase target, unless the project declares the plugin itself. */
    static boolean bind(MavenProject project, Set<String> phases) {
        if (phases.isEmpty()) return false;
        for (Plugin declared : project.getBuild().getPlugins()) {
            if (GROUP.equals(declared.getGroupId()) && ARTIFACT.equals(declared.getArtifactId())) return false;
        }
        Plugin plugin = new Plugin();
        plugin.setGroupId(GROUP);
        plugin.setArtifactId(ARTIFACT);
        plugin.setVersion(VERSION);
        for (String phase : phases) {
            PluginExecution execution = new PluginExecution();
            execution.setId("polyglot-" + phase);
            execution.setPhase(phase);
            execution.addGoal("run");
            Xpp3Dom configuration = new Xpp3Dom("configuration");
            Xpp3Dom target = new Xpp3Dom("target");
            target.setValue(phase);
            configuration.addChild(target);
            execution.setConfiguration(configuration);
            plugin.addExecution(execution);
        }
        project.getBuild().addPlugin(plugin);
        return true;
    }
}
