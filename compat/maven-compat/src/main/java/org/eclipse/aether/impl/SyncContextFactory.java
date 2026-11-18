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
package org.eclipse.aether.impl;

/**
 * The Maven Resolver 1.x name of {@link org.eclipse.aether.spi.synccontext.SyncContextFactory}, removed in
 * Resolver 2. Extensions built for Maven 3.9 still require it: Tycho 4.0.x {@code MavenTargetLocationFactory}
 * declares a requirement on this type, and without it the component cannot be created, so Tycho cannot
 * resolve any target platform.
 *
 * @deprecated use {@link org.eclipse.aether.spi.synccontext.SyncContextFactory}
 */
@Deprecated
public interface SyncContextFactory extends org.eclipse.aether.spi.synccontext.SyncContextFactory {}
