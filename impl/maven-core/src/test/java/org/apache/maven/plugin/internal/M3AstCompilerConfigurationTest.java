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

/*
 * Modified 2026-10-21 by Hitesh Soliwal and Contributors to the Synexia Project:
 * Extend real Maven configuration coverage for invocation-scoped shared-AST build-fact receipts.
 */

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M3AstCompilerConfigurationTest {
    @TempDir
    Path directory;

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
                "-Xplugin:M3AST\ttrace=/tmp/task-1.tsv image=/tmp/task-1.m3prim slots=/tmp/task-1.slots.tsv"
                        + " fold-expressions"
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

    @ParameterizedTest
    @ValueSource(strings = {"compile", "testCompile"})
    void buildFactsEnablesSharedAstWithFreshCanonicalReceiptArguments(String goal) throws Exception {
        MavenSession session = factsSession(directory.resolve("build space \u03b1"));
        PlexusConfiguration original = new DefaultPlexusConfiguration("configuration");
        String first = arguments(configure(session, mojo(goal), original)).get(0);
        String second = arguments(configure(session, mojo(goal), original)).get(0);
        assertTrue(first.startsWith("-Xplugin:M3AST facts64="));
        assertEquals(3, first.split("\\s+").length);
        String nonce = option(first, "factsid");
        assertEquals(UUID.fromString(nonce).toString(), nonce);
        Path output = receiptPath(first);
        assertTrue(output.isAbsolute());
        assertEquals(output.normalize(), output);
        assertEquals(
                Path.of(session.getCurrentProject().getBuild().getDirectory())
                        .toAbsolutePath()
                        .normalize()
                        .resolve("m3-ast-build-facts")
                        .resolve(goal + "-" + nonce + ".tsv"),
                output);
        assertNotEquals(nonce, option(second, "factsid"));
        assertNotEquals(output, receiptPath(second));
        assertFalse(Files.exists(output.getParent()));
        assertNull(original.getChild("compilerArgs", false));
        assertNull(session.getUserProperties().getProperty("m3.ast"));
    }

    @Test
    void buildFactsAugmentsOneExistingAstArgumentAndPreservesConfiguration() throws Exception {
        String existing = "-Xplugin:M3AST\tlossless trace=/tmp/a.tsv image=/tmp/a.m3prim"
                + " slots=/tmp/a.slots.tsv fold-expressions";
        PlexusConfiguration original = configuration("-parameters");
        original.setAttribute("implementation", "retained");
        original.getChild("compilerArgs").setAttribute("combine.children", "append");
        PlexusConfiguration ast = new DefaultPlexusConfiguration("arg");
        ast.setAttribute("implementation", "option-retained");
        ast.setValue(existing);
        original.getChild("compilerArgs").addChild(ast);
        PlexusConfiguration configured = configure(factsSession(directory), mojo("compile"), original);
        assertNotSame(original, configured);
        assertEquals(List.of("-parameters", existing), arguments(original));
        assertEquals(2, arguments(configured).size());
        assertEquals("-parameters", arguments(configured).get(0));
        assertTrue(arguments(configured).get(1).startsWith(existing + " facts64="));
        assertEquals("retained", configured.getAttribute("implementation"));
        assertEquals("append", configured.getChild("compilerArgs").getAttribute("combine.children"));
        assertEquals(
                "option-retained",
                configured.getChild("compilerArgs").getChildren()[1].getAttribute("implementation"));
    }

    @Test
    void buildFactsResolvesExistingArgumentWithoutChangingItsPropertyExpression() throws Exception {
        MavenSession session = factsSession(directory);
        session.getUserProperties().setProperty("fixture.m3Argument", "-Xplugin:M3AST lossless");
        PlexusConfiguration original = configuration("${fixture.m3Argument}");
        PlexusConfiguration configured = configure(session, mojo("compile"), original);
        assertEquals(List.of("${fixture.m3Argument}"), arguments(original));
        assertEquals(1, arguments(configured).size());
        assertTrue(arguments(configured).get(0).startsWith("-Xplugin:M3AST lossless facts64="));
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "project", "system"})
    void buildFactsRejectsAnEffectiveExplicitAstFalse(String scope) {
        MavenSession session = factsSession(directory);
        switch (scope) {
            case "user" -> session.getUserProperties().setProperty("m3.ast", "false");
            case "project" -> session.getCurrentProject().getProperties().setProperty("m3.ast", "false");
            case "system" -> session.getSystemProperties().setProperty("m3.ast", "false");
            default -> throw new AssertionError(scope);
        }
        PlexusConfiguration original = new DefaultPlexusConfiguration("configuration");
        PluginConfigurationException failure =
                assertThrows(PluginConfigurationException.class, () -> configure(session, mojo("compile"), original));
        assertTrue(failure.getMessage().contains("conflicts with an explicit m3.ast=false"));
        assertNull(original.getChild("compilerArgs", false));
    }

    @Test
    void buildFactsUsesTheExistingPropertyPrecedence() throws Exception {
        MavenSession session = factsSession(directory);
        session.getUserProperties().setProperty("m3.ast.buildFacts", "false");
        session.getCurrentProject().getProperties().setProperty("m3.ast.buildFacts", "true");
        session.getSystemProperties().setProperty("m3.ast.buildFacts", "true");
        PlexusConfiguration original = new DefaultPlexusConfiguration("configuration");
        assertSame(original, configure(session, mojo("compile"), original));
        session.getUserProperties().remove("m3.ast.buildFacts");
        assertTrue(
                arguments(configure(session, mojo("compile"), original)).get(0).contains(" factsid="));
        session.getCurrentProject().getProperties().setProperty("m3.ast.buildFacts", "false");
        assertSame(original, configure(session, mojo("compile"), original));
        session.getCurrentProject().getProperties().remove("m3.ast.buildFacts");
        session.getSystemProperties().setProperty("m3.ast", "false");
        session.getUserProperties().setProperty("m3.ast", "true");
        assertTrue(
                arguments(configure(session, mojo("compile"), original)).get(0).contains(" factsid="));
    }

    @Test
    void buildFactsRetainsTheJavacOnlyGate() {
        MavenSession session = factsSession(directory);
        session.getUserProperties().setProperty("maven.compiler.compilerId", "eclipse");
        PlexusConfiguration original = new DefaultPlexusConfiguration("configuration");
        original.getChild("compilerId", true).setValue("${maven.compiler.compilerId}");
        PluginConfigurationException failure =
                assertThrows(PluginConfigurationException.class, () -> configure(session, mojo("compile"), original));
        assertTrue(failure.getMessage().contains("compilerId is eclipse"));
        assertNull(original.getChild("compilerArgs", false));
    }

    @Test
    void buildFactsRejectsMultipleAstArguments() {
        PlexusConfiguration original = configuration("-Xplugin:M3AST lossless");
        PlexusConfiguration duplicate = new DefaultPlexusConfiguration("arg");
        duplicate.setValue("-Xplugin:M3AST fold-expressions");
        original.getChild("compilerArgs").addChild(duplicate);
        MavenSession session = factsSession(directory);
        PluginConfigurationException failure =
                assertThrows(PluginConfigurationException.class, () -> configure(session, mojo("compile"), original));
        assertTrue(failure.getMessage().contains("requires a single -Xplugin:M3AST"));
        assertEquals(2, arguments(original).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"facts64=L3RtcC9zdGFsZS50c3Y", "factsid=00000000-0000-0000-0000-000000000000"})
    void buildFactsRefusesCallerOwnedReceiptOptions(String option) throws Exception {
        PlexusConfiguration original = configuration("-Xplugin:M3AST lossless " + option);
        MavenSession session = factsSession(directory);
        PluginConfigurationException failure =
                assertThrows(PluginConfigurationException.class, () -> configure(session, mojo("compile"), original));
        assertTrue(failure.getMessage().contains("owns facts64/factsid"));
        assertSame(original, configure(session(true), mojo("compile"), original));
    }

    @Test
    void buildFactsRequiresUsableProjectBuildOutput() {
        MavenSession missingDirectory = session(false);
        missingDirectory.getUserProperties().setProperty("m3.ast.buildFacts", "true");
        PlexusConfiguration original = new DefaultPlexusConfiguration("configuration");
        PluginConfigurationException failure = assertThrows(
                PluginConfigurationException.class, () -> configure(missingDirectory, mojo("compile"), original));
        assertTrue(failure.getMessage().contains("build directory"));
        MavenSession noBaseDirectory = factsSession(directory);
        noBaseDirectory.getCurrentProject().setFile(null);
        noBaseDirectory.getCurrentProject().getBuild().setDirectory("target");
        failure = assertThrows(
                PluginConfigurationException.class, () -> configure(noBaseDirectory, mojo("compile"), original));
        assertTrue(failure.getMessage().contains("project base directory"));
        assertNull(original.getChild("compilerArgs", false));
    }

    @Test
    void buildFactsResolvesRelativeBuildOutputFromProjectBaseDirectory() throws Exception {
        MavenSession session = factsSession(directory);
        session.getCurrentProject().getBuild().setDirectory("staging/../target");
        String argument = arguments(
                        configure(session, mojo("testCompile"), new DefaultPlexusConfiguration("configuration")))
                .get(0);
        assertEquals(
                directory.resolve("target/m3-ast-build-facts"),
                receiptPath(argument).getParent());
        assertFalse(Files.exists(receiptPath(argument).getParent()));
    }

    @Test
    void buildFactsLeavesUnrelatedGoalsAloneEvenWithAnAstConflict() throws Exception {
        MavenSession session = factsSession(directory);
        session.getUserProperties().setProperty("m3.ast", "false");
        PlexusConfiguration original = new DefaultPlexusConfiguration("configuration");
        assertSame(original, configure(session, mojo("help"), original));
        MojoDescriptor otherPlugin = mojo("compile");
        otherPlugin.getPluginDescriptor().setArtifactId("other-compiler-plugin");
        assertSame(original, configure(session, otherPlugin, original));
    }

    private static MavenSession factsSession(Path root) {
        MavenSession session = session(false);
        MavenProject project = session.getCurrentProject();
        project.setFile(root.resolve("pom.xml").toFile());
        org.apache.maven.model.Build build = new org.apache.maven.model.Build();
        build.setDirectory(root.resolve("target").toString());
        project.getModel().setBuild(build);
        session.getUserProperties().setProperty("m3.ast.buildFacts", "true");
        return session;
    }

    private static String option(String argument, String name) {
        List<String> matching = Arrays.stream(argument.split("\\s+"))
                .filter(value -> value.startsWith(name + "="))
                .map(value -> value.substring(name.length() + 1))
                .toList();
        assertEquals(1, matching.size(), name);
        return matching.get(0);
    }

    private static Path receiptPath(String argument) {
        String encoded = option(argument, "facts64");
        byte[] decoded = Base64.getUrlDecoder().decode(encoded);
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded), encoded);
        return Path.of(new String(decoded, StandardCharsets.UTF_8));
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
