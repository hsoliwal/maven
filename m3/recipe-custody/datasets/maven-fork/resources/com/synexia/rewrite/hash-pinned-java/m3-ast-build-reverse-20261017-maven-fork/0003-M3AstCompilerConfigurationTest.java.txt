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
package org.apache.maven.plugin.internal;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.maven.api.xml.XmlNode;
import org.apache.maven.api.xml.XmlService;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.internal.xml.XmlPlexusConfiguration;
import org.apache.maven.plugin.PluginConfigurationException;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.configuration.DefaultPlexusConfiguration;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M3AstCompilerConfigurationTest {
    @Test
    void defaultConfigurationKeepsItsIdentity() throws Exception {
        PlexusConfiguration configuration = new DefaultPlexusConfiguration("configuration");
        assertSame(configuration, configure(session(false), mojo("compile"), configuration));
        assertNull(configuration.getChild("compilerArgs", false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"compile", "testCompile"})
    void enablesBothJavaCompilationGoals(String goal) throws Exception {
        PlexusConfiguration original = new DefaultPlexusConfiguration("configuration");
        PlexusConfiguration configured = configure(session(true), mojo(goal), original);
        assertEquals(List.of("-Xplugin:M3AST"), arguments(configured));
        assertNull(original.getChild("compilerArgs", false));
    }

    @Test
    void leavesOtherPluginsAndGoalsAlone() throws Exception {
        PlexusConfiguration configuration = new DefaultPlexusConfiguration("configuration");
        MojoDescriptor mojo = mojo("help");
        assertSame(configuration, configure(session(true), mojo, configuration));
        mojo.setGoal("compile");
        mojo.getPluginDescriptor().setGroupId("org.example");
        assertSame(configuration, configure(session(true), mojo, configuration));
        mojo.getPluginDescriptor().setGroupId("org.apache.maven.plugins");
        mojo.getPluginDescriptor().setArtifactId("other-compiler-plugin");
        assertSame(configuration, configure(session(true), mojo, configuration));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "-Xplugin:M3AST",
                "-Xplugin:M3AST lossless",
                "-Xplugin:M3AST\ttrace=/tmp/task-1.tsv image=/tmp/task-1.m3prim slots=/tmp/task-1.slots.tsv fold-expressions"
            })
    void preservesConfiguredPluginArguments(String argument) throws Exception {
        PlexusConfiguration original = configuration(argument);
        assertSame(original, configure(session(true), mojo("compile"), original));
        assertEquals(List.of(argument), arguments(original));
    }

    @Test
    void doesNotConfuseAPluginNamePrefix() throws Exception {
        PlexusConfiguration configured =
                configure(session(true), mojo("compile"), configuration("-Xplugin:M3ASTOther"));
        assertEquals(List.of("-Xplugin:M3ASTOther", "-Xplugin:M3AST"), arguments(configured));
    }

    @Test
    void appendsAfterTheRealXmlMergeWithoutMutatingItsInput() throws Exception {
        XmlNode inherited = XmlNode.newBuilder()
                .name("configuration")
                .children(List.of(
                        XmlNode.newBuilder()
                                .name("compilerArgs")
                                .children(List.of(XmlNode.newInstance("arg", "-parameters")))
                                .build(),
                        XmlNode.newInstance("fork", "true"),
                        XmlNode.newInstance("executable", "/toolchain/bin/javac"),
                        XmlNode.newBuilder()
                                .name("annotationProcessorPaths")
                                .children(List.of(XmlNode.newBuilder()
                                        .name("path")
                                        .children(List.of(
                                                XmlNode.newInstance("groupId", "org.example"),
                                                XmlNode.newInstance("artifactId", "processor")))
                                        .build()))
                                .build()))
                .build();
        XmlNode execution = XmlNode.newBuilder()
                .name("configuration")
                .attributes(Map.of("implementation", "retained"))
                .children(List.of(XmlNode.newBuilder()
                        .name("compilerArgs")
                        .attributes(Map.of("combine.children", "append"))
                        .children(List.of(XmlNode.newInstance("arg", "-Xlint:all")))
                        .build()))
                .build();
        PlexusConfiguration merged =
                XmlPlexusConfiguration.toPlexusConfiguration(XmlService.merge(execution, inherited));
        List<String> before = arguments(merged);
        assertEquals(2, before.size());
        assertTrue(before.containsAll(List.of("-parameters", "-Xlint:all")));

        PlexusConfiguration configured = configure(session(true), mojo("compile"), merged);
        assertNotSame(merged, configured);
        assertEquals(before, arguments(merged));
        assertEquals(List.of(before.get(0), before.get(1), "-Xplugin:M3AST"), arguments(configured));
        assertEquals("retained", configured.getAttribute("implementation"));
        assertEquals("append", configured.getChild("compilerArgs").getAttribute("combine.children"));
        assertEquals("true", configured.getChild("fork").getValue());
        assertEquals("/toolchain/bin/javac", configured.getChild("executable").getValue());
        assertEquals(
                "processor",
                configured
                        .getChild("annotationProcessorPaths")
                        .getChild("path")
                        .getChild("artifactId")
                        .getValue());
        assertSame(configured, configure(session(true), mojo("compile"), configured));
    }

    @Test
    void anExplicitFalseOverridesProjectAndSystemOptIns() throws Exception {
        MavenSession session = session(false);
        session.getSystemProperties().setProperty("m3.ast", "true");
        session.getCurrentProject().getProperties().setProperty("m3.ast", "true");
        session.getUserProperties().setProperty("m3.ast", "false");
        PlexusConfiguration original = new DefaultPlexusConfiguration("configuration");
        assertSame(original, configure(session, mojo("compile"), original));
    }

    @Test
    void usesProjectAndSystemOptInsWhenTheUserDidNotOverrideThem() throws Exception {
        MavenSession session = session(false);
        session.getCurrentProject().getProperties().setProperty("m3.ast", "true");
        assertEquals(
                List.of("-Xplugin:M3AST"),
                arguments(configure(session, mojo("compile"), new DefaultPlexusConfiguration("configuration"))));
        session.getCurrentProject().getProperties().clear();
        session.getSystemProperties().setProperty("m3.ast", "true");
        assertEquals(
                List.of("-Xplugin:M3AST"),
                arguments(configure(session, mojo("compile"), new DefaultPlexusConfiguration("configuration"))));
    }

    @Test
    void rejectsAnExplicitOptInWithAnAlternateCompiler() {
        PlexusConfiguration original = new DefaultPlexusConfiguration("configuration");
        original.getChild("compilerId", true).setValue("eclipse");
        PluginConfigurationException failure = assertThrows(
                PluginConfigurationException.class, () -> configure(session(true), mojo("compile"), original));
        assertTrue(failure.getMessage().contains("compilerId is eclipse"));
        assertNull(original.getChild("compilerArgs", false));
    }

    @Test
    void usesTheDescriptorDefaultForAnUnconfiguredCompilerExpression() throws Exception {
        PlexusConfiguration original = new DefaultPlexusConfiguration("configuration");
        PlexusConfiguration compilerId = original.getChild("compilerId", true);
        compilerId.setValue("${maven.compiler.compilerId}");
        compilerId.setAttribute("default-value", "javac");
        PlexusConfiguration configured = configure(session(true), mojo("compile"), original);
        assertEquals(List.of("-Xplugin:M3AST"), arguments(configured));
        assertEquals(
                "${maven.compiler.compilerId}",
                configured.getChild("compilerId").getValue());
        assertEquals("javac", configured.getChild("compilerId").getAttribute("default-value"));
    }

    @Test
    void evaluatesAnExplicitCompilerPropertyBeforeRefusingIt() {
        MavenSession session = session(true);
        session.getUserProperties().setProperty("maven.compiler.compilerId", "eclipse");
        PlexusConfiguration original = new DefaultPlexusConfiguration("configuration");
        PlexusConfiguration compilerId = original.getChild("compilerId", true);
        compilerId.setValue("${maven.compiler.compilerId}");
        compilerId.setAttribute("default-value", "javac");
        PluginConfigurationException failure =
                assertThrows(PluginConfigurationException.class, () -> configure(session, mojo("compile"), original));
        assertTrue(failure.getMessage().contains("compilerId is eclipse"));
    }

    @Test
    void preservesAnExistingPluginArgumentSuppliedByAProperty() throws Exception {
        MavenSession session = session(true);
        session.getUserProperties().setProperty("fixture.m3Argument", "-Xplugin:M3AST lossless");
        PlexusConfiguration original = configuration("${fixture.m3Argument}");
        assertSame(original, configure(session, mojo("compile"), original));
        assertEquals(List.of("${fixture.m3Argument}"), arguments(original));
    }

    private static PlexusConfiguration configure(
            MavenSession session, MojoDescriptor mojo, PlexusConfiguration configuration)
            throws PluginConfigurationException {
        return M3AstCompilerConfiguration.configure(
                session, mojo, configuration, new PluginParameterExpressionEvaluator(session));
    }

    private static PlexusConfiguration configuration(String argument) {
        PlexusConfiguration configuration = new DefaultPlexusConfiguration("configuration");
        configuration.getChild("compilerArgs", true).getChild("arg", true).setValue(argument);
        return configuration;
    }

    private static List<String> arguments(PlexusConfiguration configuration) {
        return Arrays.stream(configuration.getChild("compilerArgs").getChildren())
                .map(PlexusConfiguration::getValue)
                .toList();
    }

    @SuppressWarnings("deprecation")
    private static MavenSession session(boolean enabled) {
        MavenSession session =
                new MavenSession(null, null, new DefaultMavenExecutionRequest(), new DefaultMavenExecutionResult());
        session.setCurrentProject(new MavenProject());
        if (enabled) {
            session.getUserProperties().setProperty("m3.ast", "true");
        }
        return session;
    }

    private static MojoDescriptor mojo(String goal) {
        PluginDescriptor plugin = new PluginDescriptor();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-compiler-plugin");
        MojoDescriptor mojo = new MojoDescriptor();
        mojo.setPluginDescriptor(plugin);
        mojo.setGoal(goal);
        return mojo;
    }
}
