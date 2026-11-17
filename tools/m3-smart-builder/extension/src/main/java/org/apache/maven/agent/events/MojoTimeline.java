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
package org.apache.maven.agent.events;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;

/** Start times of running projects and mojos, keyed per project, so parallel ({@code -T}) builds time correctly. */
final class MojoTimeline {
    /** When something started: monotonic time for durations, wall time for comparing file modification times. */
    record Start(long nanos, long wallMillis) {
        long elapsedMillis() {
            return (System.nanoTime() - nanos) / 1_000_000L;
        }
    }

    private final Map<String, Start> starts = new ConcurrentHashMap<>();

    void start(String key) {
        starts.put(key, new Start(System.nanoTime(), System.currentTimeMillis()));
    }

    /** Removes and returns the start of {@code key}; empty when it never started (a skipped project). */
    Optional<Start> end(String key) {
        return Optional.ofNullable(starts.remove(key));
    }

    /** Key of a project. */
    static String key(MavenProject project) {
        return ReactorIndex.id(project);
    }

    /** Key of a mojo execution in a project. */
    static String key(MavenProject project, MojoExecution mojo) {
        return key(project) + "|" + mojo.getGroupId() + ":" + mojo.getArtifactId() + ":" + mojo.getGoal() + "@"
                + mojo.getExecutionId();
    }
}
