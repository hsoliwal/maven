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

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Properties;

import org.apache.maven.api.services.BuildPolicyService;
import org.apache.maven.api.services.BuildPolicyService.JavaFeature;
import org.apache.maven.api.services.BuildPolicyService.MavenVersion;
import org.apache.maven.api.services.BuildPolicyService.Severity;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeBuildPolicyParticipantTest {

    @Test
    void javaRequirementsMapOnlyWhenTheyAreExactlyAMinimumFeature() {
        assertEquals(OptionalInt.of(17), NativeBuildPolicyParticipant.javaMinimum("17"));
        assertEquals(OptionalInt.of(17), NativeBuildPolicyParticipant.javaMinimum(" 17.0 "));
        assertEquals(OptionalInt.of(8), NativeBuildPolicyParticipant.javaMinimum("1.8"));
        assertEquals(OptionalInt.of(8), NativeBuildPolicyParticipant.javaMinimum("1.8.0"));
        assertEquals(OptionalInt.of(21), NativeBuildPolicyParticipant.javaMinimum("[21,)"));
        assertEquals(OptionalInt.of(8), NativeBuildPolicyParticipant.javaMinimum("[1.8,)"));
        for (String kept : new String[] {"11.0.2", "(17,)", "[11,18)", "[17,21]", "${java.min}", "17-ea", "1.0", ""}) {
            assertTrue(NativeBuildPolicyParticipant.javaMinimum(kept).isEmpty(), kept);
        }
    }

    @Test
    void propertiesDeclareRulesWithoutThePlugin() {
        Properties properties = new Properties();
        assertTrue(NativeBuildPolicyParticipant.fromProperties(properties).isEmpty());
        properties.setProperty("maven.policy.mavenVersion", "[3.9,)");
        properties.setProperty("maven.policy.javaFeature", "21");
        assertEquals(
                List.of(new MavenVersion("[3.9,)", Severity.ERROR), new JavaFeature(21, Severity.ERROR)),
                NativeBuildPolicyParticipant.fromProperties(properties));
        properties.setProperty("maven.policy.severity", "WARN");
        properties.setProperty("maven.policy.javaFeature", "twenty-one");
        assertEquals(
                List.of(new MavenVersion("[3.9,)", Severity.WARN)),
                NativeBuildPolicyParticipant.fromProperties(properties));
    }

    @Test
    void rulesMapOnlyWhenEveryRuleIsSupported() {
        List<BuildPolicyService.Rule> out = new ArrayList<>();
        assertTrue(NativeBuildPolicyParticipant.mapRules(
                ruleList(rule("requireMavenVersion", "3.9.6", null), rule("requireJavaVersion", "[17,)", "WARN")),
                Severity.ERROR,
                out));
        assertEquals(List.of(new MavenVersion("3.9.6", Severity.ERROR), new JavaFeature(17, Severity.WARN)), out);
        assertFalse(NativeBuildPolicyParticipant.mapRules(
                ruleList(rule("requireMavenVersion", "3.9", null), rule("bannedDependencies", null, null)),
                Severity.ERROR,
                new ArrayList<>()));
        assertFalse(NativeBuildPolicyParticipant.mapRules(
                ruleList(rule("requireJavaVersion", "[11,18)", null)), Severity.ERROR, new ArrayList<>()));
        assertFalse(NativeBuildPolicyParticipant.mapRules(
                ruleList(rule("requireMavenVersion", null, null)), Severity.ERROR, new ArrayList<>()));
    }

    @Test
    void anEnforcerExecutionIsAbsorbedOnlyWhenFullyCovered() {
        Plugin enforcer = enforcer();
        enforcer.addExecution(execution(
                "versions", rules(rule("requireMavenVersion", "3.9", null), rule("requireJavaVersion", "17", null))));
        enforcer.addExecution(execution(
                "banned", rules(rule("requireJavaVersion", "17", null), rule("bannedDependencies", null, null))));
        Xpp3Dom skipped = rules(rule("requireJavaVersion", "21", null));
        Xpp3Dom skipConfiguration = new Xpp3Dom("configuration");
        skipConfiguration.addChild(skipped.getChild("rules"));
        skipConfiguration.addChild(leaf("skip", "true"));
        PluginExecution skippedExecution = new PluginExecution();
        skippedExecution.setId("skipped");
        skippedExecution.addGoal("enforce");
        skippedExecution.setConfiguration(skipConfiguration);
        enforcer.addExecution(skippedExecution);
        MavenProject project = project(enforcer);

        List<BuildPolicyService.Rule> absorbed = NativeBuildPolicyParticipant.absorbEnforcer(project);

        assertEquals(List.of(new MavenVersion("3.9", Severity.ERROR), new JavaFeature(17, Severity.ERROR)), absorbed);
        List<String> left = project.getBuild().getPlugins().get(0).getExecutions().stream()
                .map(PluginExecution::getId)
                .toList();
        assertEquals(
                List.of("banned", "skipped"), left, "a partly covered and a skipped execution stay with the plugin");
    }

    @Test
    void thePluginGoesWhenNoExecutionRemainsAndFailFalseMeansWarn() {
        Plugin enforcer = enforcer();
        Xpp3Dom configuration = rules(rule("requireJavaVersion", "1.8", null));
        configuration.addChild(leaf("fail", "false"));
        enforcer.setConfiguration(configuration);
        PluginExecution execution = new PluginExecution();
        execution.setId("enforce");
        execution.addGoal("enforce");
        enforcer.addExecution(execution);
        MavenProject project = project(enforcer);

        assertEquals(List.of(new JavaFeature(8, Severity.WARN)), NativeBuildPolicyParticipant.absorbEnforcer(project));
        assertTrue(project.getBuild().getPlugins().isEmpty(), "an enforcer plugin without executions is removed");
    }

    private static MavenProject project(Plugin plugin) {
        Model model = new Model();
        model.setGroupId("g");
        model.setArtifactId("a");
        model.setVersion("1");
        Build build = new Build();
        build.addPlugin(plugin);
        model.setBuild(build);
        return new MavenProject(model);
    }

    private static Plugin enforcer() {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-enforcer-plugin");
        return plugin;
    }

    private static PluginExecution execution(String id, Xpp3Dom configuration) {
        PluginExecution execution = new PluginExecution();
        execution.setId(id);
        execution.addGoal("enforce");
        execution.setConfiguration(configuration);
        return execution;
    }

    private static Xpp3Dom rules(Xpp3Dom... rules) {
        Xpp3Dom configuration = new Xpp3Dom("configuration");
        Xpp3Dom container = new Xpp3Dom("rules");
        for (Xpp3Dom rule : rules) {
            container.addChild(rule);
        }
        configuration.addChild(container);
        return configuration;
    }

    /** The rules element itself, as mapRules takes it. */
    private static Xpp3Dom ruleList(Xpp3Dom... rules) {
        return rules(rules).getChild("rules");
    }

    private static Xpp3Dom rule(String name, String version, String level) {
        Xpp3Dom rule = new Xpp3Dom(name);
        if (version != null) {
            rule.addChild(leaf("version", version));
        }
        if (level != null) {
            rule.addChild(leaf("level", level));
        }
        return rule;
    }

    private static Xpp3Dom leaf(String name, String value) {
        Xpp3Dom leaf = new Xpp3Dom(name);
        leaf.setValue(value);
        return leaf;
    }
}
