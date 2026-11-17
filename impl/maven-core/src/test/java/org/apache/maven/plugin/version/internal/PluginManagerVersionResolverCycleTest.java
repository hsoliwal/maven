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
package org.apache.maven.plugin.version.internal;

import javax.inject.Inject;

import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.internal.DefaultMavenPluginManager;
import org.apache.maven.plugin.version.PluginVersionResolver;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.testing.PlexusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * {@link DefaultMavenPluginManager} needs a {@link PluginVersionResolver} and {@link DefaultPluginVersionResolver}
 * needs a {@link MavenPluginManager}. Core extensions that require the plugin manager before anything else does
 * (Tycho 4.0.x {@code PluginRealmHelper}, reached from {@code TychoGraphBuilder}) made Guice try to proxy the cycle
 * through Sisu's untyped bean key and fail with {@code CanNotProxyClass}; Maven could not start.
 */
@PlexusTest
class PluginManagerVersionResolverCycleTest {

    @Inject
    PlexusContainer container;

    @Test
    void pluginManagerCanBeTheFirstComponentOfTheCycleToBeCreated() throws Exception {
        assertInstanceOf(DefaultMavenPluginManager.class, container.lookup(MavenPluginManager.class));
        assertInstanceOf(DefaultPluginVersionResolver.class, container.lookup(PluginVersionResolver.class));
    }

    @Test
    void pluginManagerCanBeLookedUpByItsImplementationClass() throws Exception {
        assertInstanceOf(DefaultMavenPluginManager.class, container.lookup(DefaultMavenPluginManager.class));
    }
}
