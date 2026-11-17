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

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.maven.project.MavenProject;

/** The projects of one build in reactor order: index (1-based, as Maven prints it), id, root-relative path. */
final class ReactorIndex {
    /** One project of the reactor. */
    record Module(int index, String id, String path, String packaging, Path basedir) {
        JsonText.Obj json() {
            return new JsonText.Obj().put("index", index).put("id", id).put("path", path).put("packaging", packaging);
        }
    }

    private final List<Module> modules;
    private final Map<String, Module> byId;

    private ReactorIndex(List<Module> modules) {
        this.modules = List.copyOf(modules);
        this.byId = modules.stream().collect(Collectors.toMap(Module::id, Function.identity(), (a, b) -> a));
    }

    /** The index of {@code projects}, which are in reactor order. */
    static ReactorIndex of(List<MavenProject> projects, AgentSettings settings) {
        return new ReactorIndex(IntStream.range(0, projects.size())
                .mapToObj(i -> module(i + 1, projects.get(i), settings)).collect(Collectors.toList()));
    }

    /** An empty index, for a build that failed before its projects were read. */
    static ReactorIndex empty() {
        return new ReactorIndex(List.of());
    }

    private static Module module(int index, MavenProject project, AgentSettings settings) {
        Path basedir = Optional.ofNullable(project.getBasedir()).map(java.io.File::toPath).orElse(settings.root())
                .toAbsolutePath().normalize();
        return new Module(index, id(project), settings.relative(basedir), project.getPackaging(), basedir);
    }

    /** {@code groupId:artifactId}, the id every agent file uses. */
    static String id(MavenProject project) {
        return project.getGroupId() + ":" + project.getArtifactId();
    }

    List<Module> modules() {
        return modules;
    }

    Optional<Module> find(MavenProject project) {
        return Optional.ofNullable(project).map(p -> byId.get(id(p)));
    }

    Optional<Module> byId(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** The project with the given artifactId, when exactly one has it. */
    Optional<Module> byArtifactId(String artifactId) {
        List<Module> hits = modules.stream().filter(m -> m.id().endsWith(":" + artifactId)).collect(Collectors.toList());
        return hits.size() == 1 ? Optional.of(hits.get(0)) : Optional.empty();
    }

    /** The innermost project whose directory holds {@code file}. */
    Optional<Module> owning(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        return modules.stream().filter(m -> absolute.startsWith(m.basedir()))
                .max(Comparator.comparingInt(m -> m.basedir().getNameCount()));
    }
}
