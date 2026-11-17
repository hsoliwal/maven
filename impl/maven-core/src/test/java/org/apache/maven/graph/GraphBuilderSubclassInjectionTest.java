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
package org.apache.maven.graph;

import javax.inject.Inject;

import java.lang.reflect.Field;
import java.util.List;

import com.google.inject.AbstractModule;
import com.google.inject.name.Names;
import org.codehaus.plexus.ContainerConfiguration;
import org.codehaus.plexus.DefaultContainerConfiguration;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.testing.PlexusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tycho 4.0.x ({@code TychoGraphBuilder}, used by Eclipse Nebula with Tycho 4.0.12) extends {@link DefaultGraphBuilder}
 * through its deprecated no-argument constructor, and the container creates it from a Plexus descriptor. Its
 * collection strategies stayed {@code null}, so every build failed with a {@link NullPointerException} in
 * {@code getProjectsForMavenReactor}.
 */
@PlexusTest
class GraphBuilderSubclassInjectionTest {

    private static final String LEGACY = "legacy";

    private static final List<String> COLLABORATORS = List.of(
            "buildResumptionDataRepository",
            "pomlessCollectionStrategy",
            "multiModuleCollectionStrategy",
            "requestPomCollectionStrategy");

    @Inject
    PlexusContainer container;

    @Test
    void subclassCreatedThroughTheDeprecatedConstructorReceivesItsCollaborators() throws Exception {
        DefaultPlexusContainer extended = new DefaultPlexusContainer(configuration(), new AbstractModule() {
            @Override
            protected void configure() {
                bind(GraphBuilder.class).annotatedWith(Names.named(LEGACY)).to(LegacyGraphBuilder.class);
            }
        });
        try {
            DefaultGraphBuilder builder = (DefaultGraphBuilder) extended.lookup(GraphBuilder.class, LEGACY);

            for (String name : COLLABORATORS) {
                assertNotNull(collaborator(builder, name), name);
            }
        } finally {
            extended.dispose();
        }
    }

    @Test
    void theInjectingConstructorKeepsWorking() throws Exception {
        DefaultGraphBuilder builder = (DefaultGraphBuilder) container.lookup(GraphBuilder.class, GraphBuilder.HINT);

        for (String name : COLLABORATORS) {
            assertNotNull(collaborator(builder, name), name);
        }
    }

    private static ContainerConfiguration configuration() {
        return new DefaultContainerConfiguration()
                .setClassPathScanning(PlexusConstants.SCANNING_INDEX)
                .setAutoWiring(true)
                .setName("graph-builder-subclass");
    }

    private static Object collaborator(DefaultGraphBuilder builder, String name) throws ReflectiveOperationException {
        Field field = DefaultGraphBuilder.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(builder);
    }

    /** The shape of Tycho 4.0.x {@code TychoGraphBuilder}: a subclass with only the implicit constructor. */
    static class LegacyGraphBuilder extends DefaultGraphBuilder {}
}
