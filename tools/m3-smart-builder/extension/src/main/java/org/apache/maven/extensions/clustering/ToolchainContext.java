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
package org.apache.maven.extensions.clustering;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Provider;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.ToolchainManager;

/**
 * Whether a JDK toolchain is in the project's build context, where maven-compiler-plugin 3.x takes the javac it then
 * forks, whichever plugin stored it there. Maven 3: {@link ToolchainManager#getToolchainFromBuildContext}. Maven 4's
 * manager looks the project up in the mojo execution scope, which is not entered yet at MojoStarted, so it throws; then
 * the contexts are read where it keeps them, the session data under {@code SessionData.key(ConcurrentHashMap.class,
 * "toolchain-context")}, by project, with the toolchain under {@code toolchain-jdk} (Maven 4 API, by reflection: the
 * extension also loads in Maven 3).
 */
final class ToolchainContext {
    private static final String CONTEXTS = "toolchain-context";
    private static final String JDK = "toolchain-jdk";

    private ToolchainContext() { }

    /** Whether a JDK toolchain is in the build context; throws when neither way can tell. */
    static boolean jdk(Provider<ToolchainManager> toolchains, MavenSession session, MavenProject project) {
        try {
            return toolchains.get().getToolchainFromBuildContext("jdk", session) != null;
        } catch (RuntimeException outOfScope) {
            return maven4(session, project).orElseThrow(() -> outOfScope);
        }
    }

    /** The Maven 4 session data's answer; empty when that API is not there. */
    private static Optional<Boolean> maven4(MavenSession session, MavenProject project) {
        try {
            Object api = MavenSession.class.getMethod("getSession").invoke(session);
            ClassLoader loader = api.getClass().getClassLoader();
            Class<?> data = Class.forName("org.apache.maven.api.SessionData", false, loader);
            Object key = data.getMethod("key", Class.class, Object.class).invoke(null, ConcurrentHashMap.class, CONTEXTS);
            Object store = Class.forName("org.apache.maven.api.Session", false, loader).getMethod("getData").invoke(api);
            Object contexts = data.getMethod("get", key.getClass()).invoke(store, key);
            Class<?> projects = Class.forName("org.apache.maven.api.Project", false, loader);
            return Optional.of(contexts instanceof Map && ((Map<?, ?>) contexts).entrySet().stream()
                    .anyMatch(e -> same(projects, e.getKey(), project) && ((Map<?, ?>) e.getValue()).containsKey(JDK)));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Whether the Maven 4 project has the POM of the Maven 3 one. */
    private static boolean same(Class<?> projects, Object candidate, MavenProject project) {
        try {
            Object pom = projects.getMethod("getPomPath").invoke(candidate);
            return project.getFile() != null && pom instanceof Path
                    && ((Path) pom).toAbsolutePath().normalize().equals(project.getFile().toPath().toAbsolutePath().normalize());
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
