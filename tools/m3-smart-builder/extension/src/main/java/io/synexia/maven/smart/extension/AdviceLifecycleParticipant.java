// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.extension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
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

/** Binds m3:build-hook before and after every project's build when -Dchromellm.advice=true; no POM change. */
@Named("chromellm-advice")
@Singleton
public final class AdviceLifecycleParticipant extends AbstractMavenLifecycleParticipant {
    public static final String PROPERTY = "chromellm.advice";
    static final String GROUP = "io.synexia";
    static final String ARTIFACT = "m3-smart-maven-plugin";
    static final String VERSION = "0.1.0-SNAPSHOT";
    /** The hook's main class in the plugin jar, run directly for the advice on a failed build. */
    public static final String HOOK_MAIN = "io.synexia.maven.smart.BuildHookMain";
    /** The m3:build-hook parameters a failed build's hook gets on its command line, when set as properties. */
    private static final Map<String, String> HOOK_OPTIONS = Map.ofEntries(Map.entry("m3.smart.llmBackend", "--backend"),
            Map.entry("m3.smart.chromeLlmJar", "--chrome-llm-jar"), Map.entry("m3.smart.chromeBin", "--chrome-binary"),
            Map.entry("m3.smart.chromeProfile", "--chrome-profile"), Map.entry("m3.smart.chromeHeadless", "--headless"),
            Map.entry("m3.smart.chromeDownload", "--allow-download"), Map.entry("m3.smart.llamaBin", "--llama-bin"),
            Map.entry("m3.smart.jniLib", "--jni-lib"), Map.entry("m3.smart.modelPath", "--model"),
            Map.entry("m3.smart.llmTimeoutSeconds", "--timeout-seconds"), Map.entry("m3.smart.bridgeUrl", "--bridge-url"),
            Map.entry("m3.smart.bridgeTokenFile", "--bridge-token-file"), Map.entry("m3.smart.summarizer", "--summarizer"));
    private static final Logger LOG = LoggerFactory.getLogger(AdviceLifecycleParticipant.class);

    /** The plugin jar the hook runs from, in {@code localRepository}. */
    public static Path pluginJar(Path localRepository) {
        return localRepository.resolve(Path.of("io", "synexia", ARTIFACT, VERSION, ARTIFACT + "-" + VERSION + ".jar"));
    }

    /**
     * Arguments of {@link #HOOK_MAIN} for the post-build advice of a failed build of {@code projectDir}: outcome
     * failure, the build log when known, and every m3:build-hook parameter set as a property (user over system).
     */
    public static List<String> failureHookArguments(Properties user, Properties system, Path projectDir, Optional<Path> log) {
        List<String> arguments = new ArrayList<>(List.of("--phase", "post-build", "--project-dir", projectDir.toString(),
                "--build-dir", "target", "--build-outcome", "failure"));
        log.ifPresent(file -> arguments.addAll(List.of("--log-file", file.toString())));
        HOOK_OPTIONS.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(option -> {
            String value = user.getProperty(option.getKey(), system.getProperty(option.getKey()));
            if (value != null && !value.isBlank()) arguments.addAll(List.of(option.getValue(), value.trim()));
        });
        return arguments;
    }

    /** The hook's provider timeout ({@code m3.smart.llmTimeoutSeconds}, default 120), within 1..600 seconds. */
    public static int hookTimeoutSeconds(Properties user, Properties system) {
        try {
            int seconds = Integer.parseInt(user.getProperty("m3.smart.llmTimeoutSeconds",
                    system.getProperty("m3.smart.llmTimeoutSeconds", "120")).trim());
            return Math.max(1, Math.min(600, seconds));
        } catch (NumberFormatException unparsable) {
            return 120;
        }
    }

    @Override
    public void afterProjectsRead(MavenSession session) {
        String value = session.getUserProperties().getProperty(PROPERTY, session.getSystemProperties().getProperty(PROPERTY));
        if (!"true".equals(value)) return;
        if (Runtime.version().feature() < 21) {
            LOG.info("ChromeLLM advice skipped: m3:build-hook needs Maven on Java 21 or newer");
            return;
        }
        Path plugin = pluginJar(session.getRepositorySession().getLocalRepository().getBasedir().toPath());
        if (!Files.isRegularFile(plugin)) {
            LOG.info("ChromeLLM advice skipped: " + GROUP + ":" + ARTIFACT + ":" + VERSION + " is not in the local repository");
            return;
        }
        int bound = 0;
        for (MavenProject project : session.getProjects()) {
            if (bind(project)) bound++;
        }
        LOG.info("ChromeLLM advice: m3:build-hook bound before and after " + bound + " project(s)");
    }

    /** Adds the hook unless the project declares the plugin itself; removes the Ant form of the same steps. */
    static boolean bind(MavenProject project) {
        removeAntSteps(project);
        for (Plugin declared : project.getBuild().getPlugins()) {
            if (GROUP.equals(declared.getGroupId()) && ARTIFACT.equals(declared.getArtifactId())) return false;
        }
        Plugin plugin = new Plugin();
        plugin.setGroupId(GROUP);
        plugin.setArtifactId(ARTIFACT);
        plugin.setVersion(VERSION);
        plugin.setConfiguration(configuration("failOnError", "false"));
        plugin.addExecution(execution("chromellm-pre-build", "validate", "pre-build", null));
        plugin.addExecution(execution("chromellm-post-build", "verify", "post-build", "success"));
        project.getBuild().addPlugin(plugin);
        return true;
    }

    /** The version 2 POM profile ran the same steps with maven-antrun-plugin; that form resolves dependencies first. */
    static void removeAntSteps(MavenProject project) {
        for (Iterator<Plugin> plugins = project.getBuild().getPlugins().iterator(); plugins.hasNext();) {
            Plugin declared = plugins.next();
            if (!"org.apache.maven.plugins".equals(declared.getGroupId())
                    || !"maven-antrun-plugin".equals(declared.getArtifactId())) continue;
            declared.getExecutions().removeIf(execution -> "chromellm-pre-build".equals(execution.getId())
                    || "chromellm-post-build".equals(execution.getId()));
            if (declared.getExecutions().isEmpty()) plugins.remove();
        }
        project.getBuild().flushPluginMap();
    }

    private static PluginExecution execution(String id, String lifecyclePhase, String hookPhase, String outcome) {
        PluginExecution execution = new PluginExecution();
        execution.setId(id);
        execution.setPhase(lifecyclePhase);
        execution.addGoal("build-hook");
        Xpp3Dom configuration = configuration("phase", hookPhase);
        if (outcome != null) {
            Xpp3Dom child = new Xpp3Dom("buildOutcome");
            child.setValue(outcome);
            configuration.addChild(child);
        }
        execution.setConfiguration(configuration);
        return execution;
    }

    private static Xpp3Dom configuration(String name, String value) {
        Xpp3Dom configuration = new Xpp3Dom("configuration");
        Xpp3Dom child = new Xpp3Dom(name);
        child.setValue(value);
        configuration.addChild(child);
        return configuration;
    }
}
