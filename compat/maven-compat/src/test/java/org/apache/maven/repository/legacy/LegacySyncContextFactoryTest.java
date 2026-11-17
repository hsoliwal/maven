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
package org.apache.maven.repository.legacy;

import javax.inject.Inject;

import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.testing.PlexusTest;
import org.eclipse.aether.SyncContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tycho 4.0.x (Eclipse Nebula pins 4.0.12) requires the Resolver 1.x {@code org.eclipse.aether.impl.SyncContextFactory};
 * without it {@code MavenTargetLocationFactory} had no implementation and no target platform could be resolved.
 */
@PlexusTest
@SuppressWarnings("deprecation")
class LegacySyncContextFactoryTest {

    @Inject
    PlexusContainer container;

    @Test
    void theResolver1TypeIsAContainerComponent() throws Exception {
        assertInstanceOf(
                LegacySyncContextFactory.class, container.lookup(org.eclipse.aether.impl.SyncContextFactory.class));
    }

    @Test
    void theAdapterIsNotOfferedAsTheResolver2Factory() throws Exception {
        assertFalse(container.lookupList(org.eclipse.aether.spi.synccontext.SyncContextFactory.class).stream()
                .anyMatch(LegacySyncContextFactory.class::isInstance));
    }

    @Test
    void contextsComeFromTheResolver2Factory() {
        SyncContext context = new NoSyncContext();

        SyncContext created = new LegacySyncContextFactory((session, shared) -> context).newInstance(null, true);

        assertSame(context, created);
    }

    private static final class NoSyncContext implements SyncContext {
        @Override
        public void acquire(
                java.util.Collection<? extends org.eclipse.aether.artifact.Artifact> artifacts,
                java.util.Collection<? extends org.eclipse.aether.metadata.Metadata> metadatas) {}

        @Override
        public void close() {}
    }
}
