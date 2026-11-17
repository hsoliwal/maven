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
import javax.inject.Named;
import javax.inject.Singleton;

import java.util.Objects;

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.SyncContext;
import org.eclipse.sisu.Typed;

/**
 * Serves the removed Maven Resolver 1.x {@link org.eclipse.aether.impl.SyncContextFactory} from the Resolver 2
 * {@link org.eclipse.aether.spi.synccontext.SyncContextFactory}. It is bound only under the legacy type, so it never
 * competes with, or receives itself as, the factory it delegates to.
 *
 * @since 4.2.0
 */
@Named
@Singleton
@Typed(org.eclipse.aether.impl.SyncContextFactory.class)
@SuppressWarnings("deprecation")
public class LegacySyncContextFactory implements org.eclipse.aether.impl.SyncContextFactory {

    private final org.eclipse.aether.spi.synccontext.SyncContextFactory delegate;

    @Inject
    public LegacySyncContextFactory(org.eclipse.aether.spi.synccontext.SyncContextFactory delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public SyncContext newInstance(RepositorySystemSession session, boolean shared) {
        return delegate.newInstance(session, shared);
    }
}
