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

// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.maven.api.Project;
import org.apache.maven.api.di.Named;
import org.apache.maven.api.services.Lookup;
import org.apache.maven.api.services.MessageBuilderFactory;
import org.apache.maven.api.xml.XmlNode;
import org.apache.maven.classrealm.ClassRealmManager;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.internal.impl.InternalMavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.ExtensionRealmCache;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.PluginArtifactsCache;
import org.apache.maven.plugin.PluginDescriptorCache;
import org.apache.maven.plugin.PluginRealmCache;
import org.apache.maven.plugin.PluginValidationManager;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugin.version.PluginVersionResolver;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exercises M3 configuration through the real V4 plugin-manager and XML-configurator path. */
class M3AstCompilerV4IntegrationTest {
    @TempDir
    Path directory;

    @Test
    void enhancedV4CompilerReceivesM3ArgumentsWithoutMutatingItsExecutionModel() throws Exception {
        XmlNode configuration = configuration("-parameters");
        CompilerMojo mojo = configure(Map.of("m3.ast", "true"), configuration);

        assertEquals(List.of("-parameters", "-Xplugin:M3AST"), mojo.compilerArgs);
        assertEquals("javac", mojo.compilerId);
        assertTrue(mojo.fork);
        assertEquals("retained", mojo.marker.attribute("evidence"));
        assertEquals(1, configuration.child("compilerArgs").children().size());
        assertEquals(
                "-parameters", configuration.child("compilerArgs").child("arg").value());
    }

    @Test
    void enhancedV4CompilerReceivesBuildFactsWhenNoConfigurationWasPresent() throws Exception {
        CompilerMojo mojo = configure(Map.of("m3.ast.buildFacts", "true"), null);

        assertEquals(1, mojo.compilerArgs.size());
        assertTrue(mojo.compilerArgs.get(0).startsWith("-Xplugin:M3AST facts64="));
        assertTrue(mojo.compilerArgs.get(0).contains(" factsid="));
        assertNull(mojo.marker);
    }

    @Test
    void defaultV4ConfigurationPreservesOriginalXmlNodeIdentity() throws Exception {
        XmlNode configuration = configuration("-parameters");
        CompilerMojo mojo = configure(Map.of(), configuration);

        assertEquals(List.of("-parameters"), mojo.compilerArgs);
        assertSame(configuration.child("marker"), mojo.marker);
    }

    @Test
    void existingM3ArgumentPreservesOriginalXmlNodeIdentityWithoutADuplicate() throws Exception {
        XmlNode configuration = configuration("-Xplugin:M3AST lossless");
        CompilerMojo mojo = configure(Map.of("m3.ast", "true"), configuration);

        assertEquals(List.of("-Xplugin:M3AST lossless"), mojo.compilerArgs);
        assertSame(configuration.child("marker"), mojo.marker);
    }

    private CompilerMojo configure(Map<String, String> properties, XmlNode configuration) throws Exception {
        Path components = directory.resolve("components");
        Files.writeString(components, CompilerMojo.class.getName() + "\n");
        ClassRealm realm = mock(ClassRealm.class);
        when(realm.getResources("META-INF/maven/org.apache.maven.api.di.Inject"))
                .thenAnswer(ignored ->
                        Collections.enumeration(List.of(components.toUri().toURL())));
        doReturn(CompilerMojo.class).when(realm).loadClass(CompilerMojo.class.getName());

        PluginDescriptor plugin = new PluginDescriptor();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-compiler-plugin");
        plugin.setVersion("4.0.0");
        plugin.setClassRealm(realm);
        MojoDescriptor descriptor = new MojoDescriptor(
                plugin,
                org.apache.maven.api.plugin.descriptor.MojoDescriptor.newBuilder()
                        .goal("compile")
                        .implementation(CompilerMojo.class.getName())
                        .configurator("enhanced")
                        .build());
        plugin.addMojo(descriptor);
        MojoExecution execution = new MojoExecution(descriptor);
        execution.setConfiguration(configuration);

        MavenProject project = new MavenProject();
        project.setFile(directory.resolve("pom.xml").toFile());
        Build build = new Build();
        build.setDirectory(directory.resolve("target").toString());
        project.setBuild(build);
        Properties userProperties = new Properties();
        userProperties.putAll(properties);
        MavenSession session = mock(MavenSession.class);
        when(session.getCurrentProject()).thenReturn(project);
        when(session.getUserProperties()).thenReturn(userProperties);
        when(session.getSystemProperties()).thenReturn(new Properties());
        InternalMavenSession sessionV4 = mock(InternalMavenSession.class);
        Project projectV4 = mock(Project.class);
        when(projectV4.getBasedir()).thenReturn(directory);
        when(session.getSession()).thenReturn(sessionV4);
        when(sessionV4.getProject(project)).thenReturn(projectV4);
        when(sessionV4.getEffectiveProperties(projectV4)).thenReturn(properties);
        when(sessionV4.getAllServices()).thenReturn(Map.of());
        when(sessionV4.getService(Lookup.class)).thenReturn(mock(Lookup.class));

        DefaultMavenPluginManager manager = new DefaultMavenPluginManager(
                mock(PlexusContainer.class),
                mock(ClassRealmManager.class),
                mock(PluginDescriptorCache.class),
                mock(PluginRealmCache.class),
                mock(PluginDependenciesResolver.class),
                mock(ExtensionRealmCache.class),
                mock(PluginVersionResolver.class),
                mock(PluginArtifactsCache.class),
                mock(MavenPluginValidator.class),
                List.of(),
                mock(PluginValidationManager.class),
                List.of(),
                mock(MessageBuilderFactory.class));
        return manager.getConfiguredMojo(CompilerMojo.class, session, execution);
    }

    private static XmlNode configuration(String argument) {
        return XmlNode.newBuilder()
                .name("configuration")
                .children(List.of(
                        XmlNode.newBuilder()
                                .name("compilerArgs")
                                .children(List.of(XmlNode.newInstance("arg", argument)))
                                .build(),
                        XmlNode.newBuilder()
                                .name("compilerId")
                                .value("${compiler.impl}")
                                .attributes(Map.of("default-value", "javac"))
                                .build(),
                        XmlNode.newInstance("fork", "true"),
                        XmlNode.newBuilder()
                                .name("marker")
                                .attributes(Map.of("evidence", "retained"))
                                .build()))
                .build();
    }

    @Named("org.apache.maven.plugins:maven-compiler-plugin:4.0.0:compile")
    public static class CompilerMojo {
        private List<String> compilerArgs;
        private String compilerId;
        private boolean fork;
        private XmlNode marker;
    }
}
