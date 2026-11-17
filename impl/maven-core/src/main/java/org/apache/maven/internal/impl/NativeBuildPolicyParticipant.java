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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.api.services.BuildPolicyService;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maven absorbs the enforcer plugin's most used rules. Before any goal runs, each project's
 * {@code requireMavenVersion} and {@code requireJavaVersion} rules, from its maven-enforcer-plugin
 * configuration or from {@code maven.policy.*} properties, are evaluated natively by
 * {@link BuildPolicyService}. An enforcer execution whose rules are all evaluated natively is removed, so the
 * plugin is neither resolved nor run; anything the service cannot express stays with the plugin.
 * {@code -Dmaven.policy.native=false} turns this off.
 */
@Named
@Singleton
public final class NativeBuildPolicyParticipant extends AbstractMavenLifecycleParticipant {
    static final String DISABLE = "maven.policy.native";
    private static final String ENFORCER_GROUP = "org.apache.maven.plugins";
    private static final String ENFORCER = "maven-enforcer-plugin";
    private static final Pattern JAVA_PLAIN = Pattern.compile("(?:1\\.)?(\\d+)(?:\\.0){0,2}");
    private static final Pattern JAVA_FLOOR = Pattern.compile("\\[(?:1\\.)?(\\d+),\\)");
    private static final Logger LOGGER = LoggerFactory.getLogger(NativeBuildPolicyParticipant.class);

    private final BuildPolicyService policies;

    @Inject
    public NativeBuildPolicyParticipant(BuildPolicyService policies) {
        this.policies = Objects.requireNonNull(policies, "policies");
    }

    @Override
    public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        String disabled = session.getUserProperties()
                .getProperty(DISABLE, session.getSystemProperties().getProperty(DISABLE));
        if ("false".equals(disabled)) {
            return;
        }
        Set<String> errors = new LinkedHashSet<>();
        for (MavenProject project : session.getProjects()) {
            List<BuildPolicyService.Rule> rules = new ArrayList<>(fromProperties(project.getProperties()));
            rules.addAll(absorbEnforcer(project));
            if (rules.isEmpty()) {
                continue;
            }
            BuildPolicyService.Report report =
                    policies.evaluate(BuildPolicyService.Request.currentJvm(session.getSession(), null, rules));
            for (BuildPolicyService.Finding finding : report.findings()) {
                String line = project.getArtifactId() + ": " + finding.message() + " (" + finding.rule() + ")";
                if (finding.severity() == BuildPolicyService.Severity.ERROR) {
                    errors.add(line);
                } else {
                    LOGGER.warn("Native policy: {}", line);
                }
            }
        }
        if (!errors.isEmpty()) {
            MavenProject top = session.getTopLevelProject();
            throw new MavenExecutionException(
                    "Native build policy failed:\n  " + String.join("\n  ", errors),
                    top == null ? null : top.getFile());
        }
    }

    /** {@code maven.policy.mavenVersion}, {@code maven.policy.javaFeature}, severity {@code maven.policy.severity}. */
    static List<BuildPolicyService.Rule> fromProperties(Properties properties) {
        List<BuildPolicyService.Rule> rules = new ArrayList<>();
        BuildPolicyService.Severity severity = "WARN".equals(properties.getProperty("maven.policy.severity"))
                ? BuildPolicyService.Severity.WARN
                : BuildPolicyService.Severity.ERROR;
        String maven = properties.getProperty("maven.policy.mavenVersion");
        if (maven != null && !maven.isBlank()) {
            rules.add(new BuildPolicyService.MavenVersion(maven.trim(), severity));
        }
        String java = properties.getProperty("maven.policy.javaFeature");
        if (java != null && java.trim().matches("\\d+")) {
            rules.add(new BuildPolicyService.JavaFeature(Integer.parseInt(java.trim()), severity));
        }
        return rules;
    }

    /** Takes over the enforcer executions whose rules are all expressible natively; returns their rules. */
    static List<BuildPolicyService.Rule> absorbEnforcer(MavenProject project) {
        List<BuildPolicyService.Rule> absorbed = new ArrayList<>();
        for (Iterator<Plugin> plugins = project.getBuild().getPlugins().iterator(); plugins.hasNext(); ) {
            Plugin plugin = plugins.next();
            String group = plugin.getGroupId() == null ? ENFORCER_GROUP : plugin.getGroupId();
            if (!ENFORCER_GROUP.equals(group) || !ENFORCER.equals(plugin.getArtifactId())) {
                continue;
            }
            for (Iterator<PluginExecution> executions = plugin.getExecutions().iterator(); executions.hasNext(); ) {
                PluginExecution execution = executions.next();
                if (!execution.getGoals().contains("enforce")) {
                    continue;
                }
                Xpp3Dom configuration = dom(execution.getConfiguration());
                if (configuration == null || configuration.getChild("rules") == null) {
                    configuration = dom(plugin.getConfiguration());
                }
                if (configuration == null || "true".equals(value(configuration, "skip"))) {
                    continue;
                }
                BuildPolicyService.Severity severity = "false".equals(value(configuration, "fail"))
                        ? BuildPolicyService.Severity.WARN
                        : BuildPolicyService.Severity.ERROR;
                List<BuildPolicyService.Rule> mapped = new ArrayList<>();
                Xpp3Dom rules = configuration.getChild("rules");
                if (rules != null && mapRules(rules, severity, mapped) && !mapped.isEmpty()) {
                    executions.remove();
                    absorbed.addAll(mapped);
                    LOGGER.info(
                            "Native policy: {} evaluates enforcer execution '{}' natively",
                            project.getArtifactId(),
                            execution.getId());
                }
            }
            if (plugin.getExecutions().isEmpty()) {
                plugins.remove();
            }
        }
        project.getBuild().flushPluginMap();
        return absorbed;
    }

    /** True when every rule maps; a rule's level overrides the execution's severity. */
    static boolean mapRules(
            Xpp3Dom rules, BuildPolicyService.Severity defaultSeverity, List<BuildPolicyService.Rule> out) {
        for (Xpp3Dom rule : rules.getChildren()) {
            String level = value(rule, "level");
            BuildPolicyService.Severity severity = "WARN".equals(level)
                    ? BuildPolicyService.Severity.WARN
                    : "ERROR".equals(level) ? BuildPolicyService.Severity.ERROR : defaultSeverity;
            String version = value(rule, "version");
            if (version == null) {
                return false;
            }
            if ("requireMavenVersion".equals(rule.getName())) {
                out.add(new BuildPolicyService.MavenVersion(version, severity));
            } else if ("requireJavaVersion".equals(rule.getName())) {
                OptionalInt minimum = javaMinimum(version);
                if (minimum.isEmpty()) {
                    return false;
                }
                out.add(new BuildPolicyService.JavaFeature(minimum.getAsInt(), severity));
            } else {
                return false;
            }
        }
        return true;
    }

    /** The feature version a Java requirement means, when it is exactly a minimum feature; else empty. */
    static OptionalInt javaMinimum(String specification) {
        String value = specification.trim();
        Matcher plain = JAVA_PLAIN.matcher(value);
        Matcher floor = JAVA_FLOOR.matcher(value);
        Matcher matched = plain.matches() ? plain : floor.matches() ? floor : null;
        if (matched == null) {
            return OptionalInt.empty();
        }
        int feature = Integer.parseInt(matched.group(1));
        return feature > 0 ? OptionalInt.of(feature) : OptionalInt.empty();
    }

    private static Xpp3Dom dom(Object configuration) {
        return configuration instanceof Xpp3Dom xpp ? xpp : null;
    }

    private static String value(Xpp3Dom parent, String name) {
        Xpp3Dom child = parent.getChild(name);
        return child == null || child.getValue() == null
                ? null
                : child.getValue().trim();
    }
}
